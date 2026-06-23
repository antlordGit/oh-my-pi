# rtk-proxy Hook — Operational Guide

This document describes the runtime deployment of `rtk-proxy.ts`, a hook that
transparently rewrites every bash command through RTK before it reaches the
underlying tool, yielding 60-90% token savings on tool output.

## What it does

Every Bash tool call the LLM emits (e.g. `git status`) is intercepted at
`tool_call`. The hook invokes `rtk rewrite` and, if RTK produces a replacement
(e.g. `rtk git status`), returns `{ updatedInput }` so the wrapper executes the
rewritten command instead. The LLM sees only the *result*; the substitution is
invisible to it.

```
LLM emits   →  bash("git status")
                │
                ▼
             HookToolWrapper.emitToolCall("tool_call")
                │
                ▼
             rtk-proxy.ts: pi.exec("rtk", ["rewrite", "git status"]) → "rtk git status"
                │
                ▼
             return { updatedInput: { command: "rtk git status" } }
                │
                ▼
             Bash tool executes "rtk git status"
                │
                ▼
LLM receives  ←  compact output (~30% of `git status` raw text)
```

Compound commands (`git status && cargo test`) are passed as a single string;
`rtk rewrite` performs segment-level rewriting (commands without an RTK
equivalent — `echo`, `cd`, `ssh`, … — are kept verbatim).

## Prerequisites

- `rtk` installed and on `$PATH` inside the runtime that spawns omp
- omp built from a source tree that includes the
  `ToolCallEventResult.updatedInput` extension
  (`packages/coding-agent/src/extensibility/shared-events.ts`).
  The extension ships in the current development branch.
- Hook loaded at startup, e.g. `--hook ~/.omp/hooks/rtk-proxy.ts`

## Hook file

Install location: `~/.omp/hooks/rtk-proxy.ts`

```ts
import type { HookAPI } from "@oh-my-pi/pi-coding-agent/extensibility/hooks";

const RTK_BIN = "rtk";
const HOOK_NAME = "rtk-proxy";

export default function rtkProxy(pi: HookAPI): void {
  pi.on("tool_call", async (event, ctx) => {
    if (event.toolName !== "bash") return;

    const input = event.input as Record<string, unknown>;
    const command = typeof input.command === "string" ? input.command : "";
    if (!command.trim()) return;

    let rewritten: string;
    try {
      const result = await pi.exec(RTK_BIN, ["rewrite", ...command.split(/\s+/).filter(Boolean)], {
        cwd: ctx.cwd,
        timeout: 5000,
      });
      const out = result.stdout.trim();
      if (!out) return;          // no rewrite — keep original
      rewritten = out;
    } catch {
      return;                    // RTK missing/failed — fail open
    }

    if (rewritten === command) return;

    ctx.ui.notify(`${HOOK_NAME}: ${command} → ${rewritten}`, "info");

    return {
      updatedInput: { ...input, command: rewritten },
    };
  });
}
```

Design notes:
- **Fail-open**: any RTK error returns no result; the original command runs
  unchanged. The hook never blocks the user.
- **5-second timeout** via `pi.exec({ timeout })` — the `bash` tool's own
  default is 120s; capping the rewrite call keeps the hook snappy.
- **notify()** surfaces each rewrite in the TUI footer for easy auditing.
- **Identity guard**: when RTK echoes the same command back, no `updatedInput`
  is returned (saves a redundant round-trip).

## CLI usage

```bash
# Single hook
omp --hook ~/.omp/hooks/rtk-proxy.ts

# Multiple hooks (executed in order; later hooks see the rewritten input)
omp \
  --hook ~/.omp/hooks/rtk-proxy.ts \
  --hook ~/.omp/hooks/safety-guard.ts \
  --hook ~/.omp/hooks/log-audit.ts
```

`--hook` is repeatable (`string[]` in `cli/args.ts`).

## Java backend integration

`backend/src/main/java/com/yourorg/omp/rpc/OmpProcessSpec.java` already
conditionally loads an RTK *extension* at `/root/.omp/agent/extensions/rtk.ts`.
The companion hook is loaded right after it:

```java
// OmpProcessSpec.toArgv() — appended after the existing RTK extension block:
Path rtkProxyHook = Path.of("/root/.omp/hooks/rtk-proxy.ts");
if (Files.isRegularFile(rtkProxyHook)) {
    argv.add("--hook");
    argv.add(rtkProxyHook.toString());
}
```

Properties driving spawn:
- `app.omp.binary` — `omp-dev.sh` in dev (loads `packages/coding-agent/src/cli.ts`
  via bun, so the `updatedInput` extension is active), or the system `omp`
  binary in prod.
- `app.omp.stderr-log-dir` — receives the hook's stderr if anything goes wrong.

### Production deploy

The `omp-allinone` Docker image is responsible for shipping the file at
`/root/.omp/hooks/rtk-proxy.ts`. Either copy it via the Dockerfile or fetch
it from a release artifact at image build time:

```dockerfile
RUN mkdir -p /root/.omp/hooks
COPY deploy/rtk-proxy.ts /root/.omp/hooks/rtk-proxy.ts
```

If the file is missing, the conditional `Files.isRegularFile` check skips the
`--hook` arg and the system falls back to RTK-only behaviour — never a hard
error.

### Local dev (Mac / Linux without RTK)

Drop the file at `~/.omp/hooks/rtk-proxy.ts` and run `omp` with the flag.
If `rtk` is not installed, the hook silently no-ops and the agent works as
before.

## Verification

1. Start a session with the hook loaded.
2. Ask the agent to run `git status`.
3. Check the TUI footer — you should see `rtk-proxy: git status → rtk git status`.
4. Inspect `/data/omp/logs/omp-<sessionId>.err.log` — no hook errors should
   appear.
5. For protocol-level auditing: set `RTK_HOOK_AUDIT=1` and run
   `rtk hook-audit` to confirm rewrites.

## Failure modes and recovery

| Symptom | Cause | Recovery |
|---------|-------|----------|
| Hook never rewrites anything | `rtk` not on `$PATH` | Install rtk, restart session |
| `rtk-proxy: cmd → rtk cmd` floods the footer | RTK version mismatch (older RTK lacks `rewrite`) | Update rtk to ≥0.x |
| Bash tool errors with hook timeout | RTK subprocess hung (network filesystem?) | Increase `pi.exec({ timeout })` or remove hook |
| Tool output is unchanged from before | Hook didn't load (path mismatch) | Inspect spawn argv in `OmpProcessSpec.toArgv()` |

## Related

- `docs/hooks.md` — full hook subsystem reference (events, ordering, semantics)
- `docs/extensions.md` — when to prefer an extension over a hook
- `docs/DEPLOY.md §18.4` — RTK install layer in the `omp-allinone` image
- `python/omp-rpc` — protocol-level client; useful for ad-hoc testing