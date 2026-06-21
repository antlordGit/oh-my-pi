package com.yourorg.omp.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourorg.omp.event.EventBus;
import com.yourorg.omp.pool.ProcessPool;
import com.yourorg.omp.rpc.OmpRpcClient;
import com.yourorg.omp.rpc.RpcCommands;
import com.yourorg.omp.security.CurrentUser;
import com.yourorg.omp.session.SessionManager;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import reactor.core.Disposable;

import javax.crypto.SecretKey;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SessionWsHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SessionWsHandler.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final SessionManager sessions;
    private final ProcessPool pool;
    private final EventBus eventBus;
    private final CurrentUser currentUser;

    @Value("${app.omp.security.jwt-secret:local-dev-secret-please-change-32chars-or-more-yes}")
    private String jwtSecret;

    private final Map<WebSocketSession, Subs> wsSubs = new ConcurrentHashMap<>();

    private record Subs(Disposable eventSub) {}

    public SessionWsHandler(SessionManager sessions, ProcessPool pool, EventBus eventBus, CurrentUser currentUser) {
        this.sessions = sessions;
        this.pool = pool;
        this.eventBus = eventBus;
        this.currentUser = currentUser;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession socket) throws Exception {
        String sessionId = extractSessionId(socket.getUri());
        String token = extractToken(socket.getUri());
        if (token == null || token.isBlank() || !authenticateToken(token)) {
            log.warn("[ws] session={} missing or invalid token, closing", sessionId);
            socket.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        var meta = sessions.findScoped(sessionId, currentUser.scope())
                .orElseThrow(() -> new IllegalStateException("无权访问该会话"));
        // Ensure the omp client is alive (spawn if needed) so EventBus starts receiving frames.
        sessions.acquireClient(meta);
        // Subscribe ONLY to EventBus — the ProcessPool's fanout already publishes every frame
        // from OmpRpcClient.events() to the EventBus. Subscribing to both would duplicate frames.
        Disposable eventSub = eventBus.subscribe(sessionId).subscribe(
                frame -> safeSend(socket, frame),
                err -> log.debug("ws eventbus sub error session={}: {}", sessionId, err.getMessage())
        );
        wsSubs.put(socket, new Subs(eventSub));
        socket.sendMessage(new TextMessage("{\"type\":\"hello\",\"sessionId\":\"" + sessionId + "\"}"));
        log.info("[ws] session={} connected", sessionId);
    }

    private boolean authenticateToken(String token) {
        try {
            SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            String username = c.getSubject();
            String role = c.get("role", String.class);
            if (username == null) return false;
            var auth = new UsernamePasswordAuthenticationToken(
                    username, null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + (role == null ? "USER" : role.toUpperCase()))));
            SecurityContextHolder.getContext().setAuthentication(auth);
            return true;
        } catch (Exception e) {
            log.debug("[ws] token parse failed: {}", e.getMessage());
            return false;
        }
    }

    private String extractToken(URI uri) {
        String q = uri.getQuery();
        if (q == null) return null;
        for (String part : q.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && "token".equals(part.substring(0, eq))) {
                return java.net.URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    @Override
    protected void handleTextMessage(WebSocketSession socket, TextMessage message) throws Exception {
        JsonNode frame;
        try {
            frame = mapper.readTree(message.getPayload());
        } catch (Exception e) {
            return;
        }
        String sessionId = extractSessionId(socket.getUri());
        var meta = sessions.findScoped(sessionId, currentUser.scope()).orElse(null);
        if (meta == null) return;
        String type = frame.path("type").asText("");
        switch (type) {
            case "abort" -> sessions.sendCommand(meta, RpcCommands.abort());
            case "prompt" -> {
                String msg = frame.path("message").asText("");
                sessions.sendCommand(meta, RpcCommands.prompt(msg));
            }
            default -> { /* ignore */ }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        Subs s = wsSubs.remove(socket);
        if (s != null) {
            s.eventSub.dispose();
        }
    }

    private void safeSend(WebSocketSession socket, JsonNode frame) {
        if (!socket.isOpen()) return;
        try {
            synchronized (socket) {
                socket.sendMessage(new TextMessage(mapper.writeValueAsString(frame)));
            }
        } catch (Exception e) {
            log.debug("ws send failed: {}", e.getMessage());
        }
    }

    private String extractSessionId(URI uri) {
        String path = uri.getPath();
        String prefix = "/ws/sessions/";
        int idx = path.indexOf(prefix);
        if (idx < 0) throw new IllegalArgumentException("无效的 WebSocket 路径: " + path);
        String tail = path.substring(idx + prefix.length());
        int slash = tail.indexOf('/');
        return slash < 0 ? tail : tail.substring(0, slash);
    }
}