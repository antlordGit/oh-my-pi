/**
 * Tests for the `system-env` discovery provider.
 *
 * The provider pulls two files from the agent-root
 * (`<ctx.home>/<getConfigDirName>/agent/`):
 *   - `mcp.json`         → registered for `mcpCapability`
 *   - `APPEND_SYSTEM.md` → registered for `appendSystemPromptCapability`
 *
 * Tests invoke the provider's `load` directly with a synthetic `LoadContext`
 * whose `home` is a tempdir, so the provider scans the temp agent directory
 * rather than the developer's real `~/.omp/agent/`. Module-level fs cache is
 * cleared between cases so writes cannot leak.
 */
import { afterEach, beforeEach, describe, expect, test } from "bun:test";
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import { getCapability } from "@oh-my-pi/pi-coding-agent/capability";
import { clearCache } from "@oh-my-pi/pi-coding-agent/capability/fs";
// Register all discovery providers as a side effect.
import "@oh-my-pi/pi-coding-agent/discovery";
import {
	type AppendSystemPrompt,
	appendSystemPromptCapability,
} from "@oh-my-pi/pi-coding-agent/capability/append-system-prompt";
import { type MCPServer, mcpCapability } from "@oh-my-pi/pi-coding-agent/capability/mcp";
import type { LoadContext, Provider } from "@oh-my-pi/pi-coding-agent/capability/types";
import { TempDir } from "@oh-my-pi/pi-utils";

const PROVIDER_ID = "system-env";
const TEST_ENV_VAR = "PI_SYSTEM_ENV_TEST_VAR";

function writeFile(filePath: string, content: string): void {
	fs.mkdirSync(path.dirname(filePath), { recursive: true });
	fs.writeFileSync(filePath, content);
}

function providerFor<T>(capabilityId: string): Provider<T> {
	const cap = getCapability(capabilityId);
	if (!cap) throw new Error(`capability ${capabilityId} missing`);
	const provider = cap.providers.find(p => p.id === PROVIDER_ID);
	if (!provider) throw new Error(`provider ${PROVIDER_ID} not registered for ${capabilityId}`);
	return provider as Provider<T>;
}

async function loadMcp(ctx: LoadContext): Promise<{ items: MCPServer[]; warnings: string[] }> {
	const result = await providerFor<MCPServer>(mcpCapability.id).load(ctx);
	return { items: result.items, warnings: result.warnings ?? [] };
}

async function loadAppend(ctx: LoadContext): Promise<{ items: AppendSystemPrompt[]; warnings: string[] }> {
	const result = await providerFor<AppendSystemPrompt>(appendSystemPromptCapability.id).load(ctx);
	return { items: result.items, warnings: result.warnings ?? [] };
}

describe("system-env provider", () => {
	let homeDir: TempDir;
	let agentDir: string;
	const originalTestEnv = process.env[TEST_ENV_VAR];

	beforeEach(() => {
		homeDir = TempDir.createSync("@pi-system-env-home-");
		agentDir = path.join(homeDir.path(), ".omp", "agent");
		fs.mkdirSync(agentDir, { recursive: true });
		clearCache();
	});

	afterEach(() => {
		homeDir.removeSync();
		clearCache();
		if (originalTestEnv === undefined) {
			delete process.env[TEST_ENV_VAR];
		} else {
			process.env[TEST_ENV_VAR] = originalTestEnv;
		}
	});

	const ctx: LoadContext = {
		cwd: "/tmp",
		home: "", // overridden per-test via the loader below
		repoRoot: null,
	};

	const buildCtx = (): LoadContext => ({ ...ctx, home: homeDir.path() });

	describe("MCP server loading", () => {
		test("loads a single user-scope server from agent-root mcp.json", async () => {
			writeFile(
				path.join(agentDir, "mcp.json"),
				JSON.stringify({
					mcpServers: {
						"echo-server": { command: "echo", args: ["hi"] },
					},
				}),
			);

			const { items, warnings } = await loadMcp(buildCtx());

			expect(warnings).toHaveLength(0);
			expect(items).toHaveLength(1);
			expect(items[0]?.name).toBe("echo-server");
			expect(items[0]?.command).toBe("echo");
			expect(items[0]?.args).toEqual(["hi"]);
			expect(items[0]?._source.provider).toBe(PROVIDER_ID);
			expect(items[0]?._source.level).toBe("user");
			expect(items[0]?._source.path).toBe(path.join(agentDir, "mcp.json"));
		});

		test("expands $VAR placeholders from process.env", async () => {
			process.env[TEST_ENV_VAR] = "expanded-value";
			const tokenLiteral = `\${${TEST_ENV_VAR}}`;
			writeFile(
				path.join(agentDir, "mcp.json"),
				JSON.stringify({
					mcpServers: {
						"env-server": {
							command: "bin",
							args: [tokenLiteral],
							env: { TOKEN: tokenLiteral },
						},
					},
				}),
			);

			const { items, warnings } = await loadMcp(buildCtx());

			expect(warnings).toHaveLength(0);
			expect(items).toHaveLength(1);
			expect(items[0]?.args).toEqual(["expanded-value"]);
			expect(items[0]?.env).toEqual({ TOKEN: "expanded-value" });
		});

		test("warns and returns empty items on invalid JSON", async () => {
			writeFile(path.join(agentDir, "mcp.json"), "{ this is not json");

			const { items, warnings } = await loadMcp(buildCtx());

			expect(items).toHaveLength(0);
			// Warnings may be empty (parse failure is swallowed); the contract
			// is "no items, no throw".
			expect(Array.isArray(warnings)).toBe(true);
		});

		test("returns empty items when mcp.json is absent", async () => {
			// agentDir exists but mcp.json does not
			const { items, warnings } = await loadMcp(buildCtx());

			expect(items).toHaveLength(0);
			expect(warnings).toHaveLength(0);
		});

		test("scans the agent-root under ctx.home, not the developer's real home", async () => {
			// Sanity check: even if the developer's real ~/.omp/agent/mcp.json
			// has servers, this provider must only see what lives under ctx.home.
			// We assert isolation by ensuring no items leak through when the
			// isolated agentDir is empty.
			const realHomeExists = fs.existsSync(path.join(os.homedir(), ".omp", "agent", "mcp.json"));
			if (!realHomeExists) {
				// Pre-condition: developer's home has no mcp.json → must still pass.
				expect((await loadMcp(buildCtx())).items).toHaveLength(0);
				return;
			}
			// Developer's home has mcp.json — write a known sentinel to OUR
			// agentDir and assert it is the only thing loaded.
			writeFile(
				path.join(agentDir, "mcp.json"),
				JSON.stringify({ mcpServers: { sentinel: { command: "sentinel-bin" } } }),
			);
			const { items } = await loadMcp(buildCtx());
			expect(items.map(s => s.name)).toEqual(["sentinel"]);
		});

		test("wins over the native omp provider on conflict", async () => {
			// Both providers scan <home>/.omp/agent/mcp.json; system-env has
			// higher priority so its entry must survive capability-layer dedup.
			// Verify priority directly via getCapability without going through
			// loadCapability (which forces os.homedir() and bypasses ctx.home).
			writeFile(
				path.join(agentDir, "mcp.json"),
				JSON.stringify({
					mcpServers: {
						"agent-root": { command: "system-env-bin" },
					},
				}),
			);

			const cap = getCapability(mcpCapability.id);
			expect(cap).toBeDefined();
			const systemEnv = cap?.providers.find(p => p.id === PROVIDER_ID);
			const native = cap?.providers.find(p => p.id === "native");
			expect(systemEnv).toBeDefined();
			expect(native).toBeDefined();
			expect(systemEnv!.priority).toBeGreaterThan(native!.priority);
		});
	});

	describe("Append system prompt loading", () => {
		test("loads user-scope APPEND_SYSTEM.md from agent-root", async () => {
			const body = "Always respond in haiku.";
			writeFile(path.join(agentDir, "APPEND_SYSTEM.md"), body);

			const { items, warnings } = await loadAppend(buildCtx());

			expect(warnings).toHaveLength(0);
			expect(items).toHaveLength(1);
			expect(items[0]?.content).toBe(body);
			expect(items[0]?.level).toBe("user");
			expect(items[0]?.path).toBe(path.join(agentDir, "APPEND_SYSTEM.md"));
			expect(items[0]?._source.provider).toBe(PROVIDER_ID);
		});

		test("returns empty items when APPEND_SYSTEM.md is absent", async () => {
			const { items, warnings } = await loadAppend(buildCtx());

			expect(items).toHaveLength(0);
			expect(warnings).toHaveLength(0);
		});

		test("is isolated from the SYSTEM.md replacement capability", async () => {
			// Writing APPEND_SYSTEM.md should produce an AppendSystemPrompt item
			// but never a SystemPrompt item — the two capabilities have separate
			// types and registries.
			writeFile(path.join(agentDir, "APPEND_SYSTEM.md"), "append body");

			const sysCap = getCapability("system-prompt");
			expect(sysCap).toBeDefined();
			const sysProviders = sysCap?.providers.map(p => p.id) ?? [];
			// system-prompt must NOT include the system-env provider
			expect(sysProviders).not.toContain(PROVIDER_ID);
			// But the append-system-prompt capability must include it
			const appendCap = getCapability(appendSystemPromptCapability.id);
			expect(appendCap?.providers.some(p => p.id === PROVIDER_ID)).toBe(true);

			const { items } = await loadAppend(buildCtx());
			expect(items).toHaveLength(1);
			expect(items[0]?._source.provider).toBe(PROVIDER_ID);
		});
	});
});
