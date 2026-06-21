package com.yourorg.omp.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns a single `omp --mode rpc` child process.
 *
 * <p>Threading model:
 * <ul>
 *   <li>One reader thread drains stdout and parses one JSON object per line.</li>
 *   <li>Stdout writes are serialized through a lock — concurrent sends are safe but ordered.</li>
 *   <li>Responses (frames with `id` and `type=response`) are routed to a pending {@link CompletableFuture}.</li>
 *   <li>All other frames are emitted to {@link #events()} (multicast sink) for subscribers (WS, audit).</li>
 * </ul>
 *
 * <p>Process death (EOF or non-zero exit) fails all pending futures and emits an error event,
 * then closes the event sink so subscribers see onComplete.
 */
public class OmpRpcClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OmpRpcClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Default timeout for a command awaiting a response. */
    private static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofMinutes(2);

    private final String sessionId;
    private final OmpProcessSpec spec;
    private final Process process;
    private final BufferedWriter stdin;
    private final Object stdinLock = new Object();
    private final ExecutorService reader;
    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Sinks.Many<JsonNode> eventSink =
            Sinks.many().multicast().directBestEffort();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    /** Optional hook called once per non-response frame (used by SessionManager to fan-out via EventBus). */
    private volatile java.util.function.Consumer<JsonNode> fanout;

    private OmpRpcClient(String sessionId, OmpProcessSpec spec, Process process) {
        this.sessionId = sessionId;
        this.spec = spec;
        this.process = process;
        try {
            this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("无法打开 omp 标准输入流", e);
        }
        this.reader = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "omp-rpc-reader-" + sessionId);
            t.setDaemon(true);
            return t;
        });
        // The readLoop is started by start() AFTER the constructor, so we don't race the ready latch.
    }

    private final java.util.concurrent.CountDownLatch readyLatch = new java.util.concurrent.CountDownLatch(1);
    private volatile Throwable startupError;

    /**
     * Spawn omp and block until the `{type:"ready"}` frame is received or the process dies.
     */
    public static OmpRpcClient start(String sessionId,
                                     OmpProcessSpec spec,
                                     Duration startupTimeout) throws IOException {
        Files.createDirectories(spec.stderrLog().getParent());
        // ★ Log the exact spawn invocation (with argv + cwd + key env entries, but redact api-key)
        var redactedArgv = new java.util.ArrayList<String>(spec.toArgv());
        for (int i = 0; i < redactedArgv.size() - 1; i++) {
            if ("--api-key".equals(redactedArgv.get(i))) {
                String k = redactedArgv.get(i + 1);
                redactedArgv.set(i + 1, k == null || k.length() < 8 ? "***" : k.substring(0, 4) + "***" + k.substring(k.length() - 4));
            }
        }
        log.info("[omp.spawn] session={} cwd={} stderr={} argv={}",
                sessionId, spec.workspaceCwd(), spec.stderrLog(), redactedArgv);
        log.info("[omp.spawn] session={} agentDir={} env(PI_CODING_AGENT_DIR + PI_NOTIFICATIONS only logged)",
                sessionId, spec.agentDir());
        ProcessBuilder pb = new ProcessBuilder(spec.toArgv())
                .directory(spec.workspaceCwd().toFile())
                .redirectError(spec.stderrLog().toFile())
                .redirectInput(ProcessBuilder.Redirect.PIPE);
        spec.effectiveEnv().forEach(pb.environment()::put);
        Process proc = pb.start();
        if (proc.getOutputStream() == null) {
            proc.destroyForcibly();
            throw new IOException("omp process has no writable stdin (sandbox restriction?)");
        }
        OmpRpcClient client = new OmpRpcClient(sessionId, spec, proc);
        client.reader.submit(client::readLoop);
        client.awaitReady(startupTimeout);
        log.info("[omp.spawn] session={} READY pid-alive={}", sessionId, client.isAlive());
        return client;
    }

    private void awaitReady(Duration timeout) {
        try {
            boolean got = readyLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!got) {
                if (process.isAlive()) process.destroyForcibly();
                throw new OmpStartupException("等待 omp 就绪帧超时");
            }
            if (startupError != null) {
                if (process.isAlive()) process.destroyForcibly();
                throw new OmpStartupException("omp 启动失败", startupError);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OmpStartupException("等待就绪时被中断", e);
        }
    }

    /**
     * Send an RPC command and return a future that completes when the matching response arrives.
     * The future has a default timeout of {@value DEFAULT_COMMAND_TIMEOUT_MINUTES} minutes;
     * call {@link CompletableFuture#orTimeout} on the result to extend.
     */
    public CompletableFuture<JsonNode> send(ObjectNode cmd) {
        if (closed.get()) {
            CompletableFuture<JsonNode> dead = new CompletableFuture<>();
            dead.completeExceptionally(new IllegalStateException("OmpRpcClient is closed"));
            return dead;
        }
        if (!cmd.has("id")) {
            cmd.put("id", "req_" + UUID.randomUUID());
        }
        String id = cmd.get("id").asText();
        CompletableFuture<JsonNode> fut = new CompletableFuture<>();
        pending.put(id, fut);
        try {
            writeLine(cmd);
        } catch (IOException e) {
            pending.remove(id);
            fut.completeExceptionally(e);
            return fut;
        }
        return fut.orTimeout(DEFAULT_COMMAND_TIMEOUT.toMinutes(), TimeUnit.MINUTES);
    }

    private static final long DEFAULT_COMMAND_TIMEOUT_MINUTES = 2;

    private void writeLine(ObjectNode cmd) throws IOException {
        String json = MAPPER.writeValueAsString(cmd);
        // ★ Log every command sent INTO omp's stdin (truncate huge prompt bodies for readability)
        if (log.isInfoEnabled()) {
            String preview = json.length() > 400 ? json.substring(0, 400) + "...(+" + (json.length() - 400) + ")" : json;
            log.info("[omp→IN ] session={} cmd={}", sessionId, preview);
        }
        synchronized (stdinLock) {
            stdin.write(json);
            stdin.newLine();
            stdin.flush();
        }
    }

    /**
     * Write a frame to omp's stdin without registering a pending future.
     * Used for replying to omp-initiated requests (e.g. extension_ui_response)
     * where we don't expect a correlated response.
     */
    public void writeFrame(ObjectNode frame) {
        try {
            writeLine(frame);
        } catch (IOException e) {
            log.error("[omp] writeFrame failed session={}: {}", sessionId, e.getMessage());
        }
    }

    /** Multicast stream of every non-response frame omp emits (events, host callbacks, etc). */
    public Flux<JsonNode> events() {
        return eventSink.asFlux();
    }

    private void readLoop() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                JsonNode frame;
                try {
                    frame = MAPPER.readTree(line);
                } catch (Exception parseErr) {
                    log.warn("omp session={} dropped unparseable frame: {}", sessionId, parseErr.getMessage());
                    continue;
                }
                if (readyLatch.getCount() > 0) {
                    if ("ready".equals(frame.path("type").asText())) {
                        readyLatch.countDown();
                        continue;
                    }
                    // Pre-ready non-ready frames (e.g. extension_ui_request setWidget) — forward
                    // to subscribers anyway so we don't lose audit/UI events.
                }
                routeFrame(frame);
            }
            handleProcessExit("EOF on stdout");
        } catch (IOException e) {
            handleProcessExit("stdout read error: " + e.getMessage());
        }
    }

    private void routeFrame(JsonNode frame) {
        String type = frame.path("type").asText("");
        // ★ Log every frame coming OUT of omp's stdout. message_update/text_delta is logged compactly
        // because it fires hundreds of times per turn.
        if (log.isInfoEnabled()) {
            if ("message_update".equals(type)) {
                JsonNode evt = frame.path("assistantMessageEvent");
                String etype = evt.path("type").asText();
                // 所有 *_delta 流式增量（text_delta / thinking_delta / toolcall_delta 等）每轮触发数百次，
                // 统一降到 debug，避免刷屏；非 delta 的关键事件仍保留 INFO。
                if (etype.endsWith("_delta")) {
                    log.debug("[omp→OUT] session={} {} {} delta={}", sessionId, type, etype,
                            evt.path("delta").asText("").replace("\n", "\\n"));
                } else {
                    log.info("[omp→OUT] session={} {} evt={}", sessionId, type, etype);
                }
            } else {
                String s = frame.toString();
                String preview = s.length() > 400 ? s.substring(0, 400) + "...(+" + (s.length() - 400) + ")" : s;
                log.info("[omp→OUT] session={} {}", sessionId, preview);
            }
        }
        if (frame.has("id") && RpcFrameType.isResponse(frame.path("type").asText())) {
            String id = frame.get("id").asText();
            CompletableFuture<JsonNode> fut = pending.remove(id);
            if (fut != null) {
                fut.complete(frame);
            } else {
                log.debug("omp session={} response with unknown id={}", sessionId, id);
            }
            return;
        }
        // Non-response: emit to subscribers (audit, WS gateway).
        eventSink.tryEmitNext(frame);
        if (fanout != null) {
            try { fanout.accept(frame); } catch (Exception ignore) {}
        }
    }

    private void handleProcessExit(String reason) {
        if (closed.compareAndSet(false, true)) {
            int exitCode = process.isAlive() ? -1 : process.exitValue();
            log.warn("[omp.exit] session={} reason={} exitCode={} pendingRequests={}",
                    sessionId, reason, exitCode, pending.size());
            IOException cause = new IOException("omp process exited: " + reason);
            // If we hadn't reached ready yet, surface as startup failure
            if (readyLatch.getCount() > 0) {
                startupError = cause;
                readyLatch.countDown();
            }
            pending.values().forEach(f -> f.completeExceptionally(cause));
            pending.clear();
            eventSink.tryEmitError(cause);
            reader.shutdownNow();
        }
    }

    public boolean isAlive() {
        return !closed.get() && process.isAlive();
    }

    public OmpProcessSpec spec() {
        return spec;
    }

    public String sessionId() {
        return sessionId;
    }

    /** Set a hook invoked once per non-response frame, in addition to the multicast sink. */
    public void setFanout(java.util.function.Consumer<JsonNode> fanout) {
        this.fanout = fanout;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                // Closing stdin tells omp to shut down (rpc-mode exits on stdin close).
                synchronized (stdinLock) {
                    try { stdin.close(); } catch (IOException ignore) {}
                }
                if (process.isAlive()) {
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            } finally {
                eventSink.tryEmitComplete();
                reader.shutdownNow();
            }
        }
    }
}