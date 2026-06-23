/**
 * System-Environment Provider
 *
 * Single, highest-priority source for system-environment-level config rooted at
 * the user's agent directory (`~/.omp/agent/` by default; overridable via
 * `PI_CODING_AGENT_DIR`). Wires two capabilities:
 *
 * - {@link mcpCapability}:  loads `<agentRoot>/mcp.json` as user-scope MCP servers
 * - {@link appendSystemPromptCapability}: loads `<agentRoot>/APPEND_SYSTEM.md`
 *   as a user-scope append prompt
 *
 * Priority is 110 — above the native `.omp` provider (100) so that the
 * agent-root always wins on conflicts. The native provider scans the same
 * `~/.omp/agent/` files too; the capability layer deduplicates by key, so the
 * higher-priority system-env entry wins.
 *
 * @see ./omp-extension-roots.ts
 * @see ../../docs/extension-loading.md
 */
import * as path from "node:path";
import { logger, tryParseJson } from "@oh-my-pi/pi-utils";
import { registerProvider } from "../capability";
import { type AppendSystemPrompt, appendSystemPromptCapability } from "../capability/append-system-prompt";
import { readFile } from "../capability/fs";
import { type MCPServer, mcpCapability } from "../capability/mcp";
import type { LoadContext, LoadResult } from "../capability/types";
import { createSourceMeta, expandEnvVarsDeep, SOURCE_PATHS } from "./helpers";

const PROVIDER_ID = "system-env";
const DISPLAY_NAME = "System Environment";
const DESCRIPTION = "Highest-priority config from the agent-root (~/.omp/agent/)";
const PRIORITY = 110;
// Resolve user-agent relative path via SOURCE_PATHS so tests that mock
// `os.homedir()` (and rebuild the agent dir under a temp HOME) can isolate us
// the same way the native provider does — using `ctx.home` rather than the
// module-level DirResolver singleton.
const AGENT_DIR_NAME = SOURCE_PATHS.native.userAgent;

// =============================================================================
// MCP Servers
// =============================================================================

/**
 * Parse mcpServers from a JSON document into canonical {@link MCPServer} items.
 *
 * Mirrors {@link ./builtin.ts}'s `parseMcpServers` (kept inline rather than
 * refactored into a shared helper: three providers parse this format with
 * subtly different validation tolerances, and a unifying refactor is out of
 * scope here).
 */
function parseMcpServers(content: string, filePath: string, level: "user" | "project"): MCPServer[] {
	const result: MCPServer[] = [];
	const data = tryParseJson<{ mcpServers?: Record<string, unknown> }>(content);
	if (!data?.mcpServers) return result;

	const expanded = expandEnvVarsDeep(data.mcpServers);
	for (const [serverName, rawCfg] of Object.entries(expanded)) {
		const cfg = rawCfg as Record<string, unknown>;

		// enabled: accept booleans and "true"/"false"/"1"/"0" strings, warn otherwise
		let enabled: boolean | undefined;
		if (cfg.enabled === undefined || cfg.enabled === null) {
			enabled = undefined;
		} else if (typeof cfg.enabled === "boolean") {
			enabled = cfg.enabled;
		} else if (typeof cfg.enabled === "string") {
			const lower = cfg.enabled.toLowerCase();
			if (lower === "false" || lower === "0") enabled = false;
			else if (lower === "true" || lower === "1") enabled = true;
			else {
				logger.warn(`MCP server "${serverName}": invalid enabled value "${cfg.enabled}", ignoring`);
				enabled = undefined;
			}
		} else {
			logger.warn(`MCP server "${serverName}": invalid enabled type ${typeof cfg.enabled}, ignoring`);
			enabled = undefined;
		}

		// timeout: coerce numeric strings, warn on invalid
		let timeout: number | undefined;
		if (cfg.timeout === undefined || cfg.timeout === null) {
			timeout = undefined;
		} else if (typeof cfg.timeout === "number") {
			if (Number.isFinite(cfg.timeout) && cfg.timeout >= 0) {
				timeout = cfg.timeout;
			} else {
				logger.warn(`MCP server "${serverName}": invalid timeout ${cfg.timeout}, ignoring`);
				timeout = undefined;
			}
		} else if (typeof cfg.timeout === "string") {
			const parsed = Number(cfg.timeout);
			if (Number.isFinite(parsed) && parsed >= 0) {
				timeout = parsed;
			} else {
				logger.warn(`MCP server "${serverName}": invalid timeout "${cfg.timeout}", ignoring`);
				timeout = undefined;
			}
		} else {
			logger.warn(`MCP server "${serverName}": invalid timeout type ${typeof cfg.timeout}, ignoring`);
			timeout = undefined;
		}

		result.push({
			name: serverName,
			enabled,
			timeout,
			command: typeof cfg.command === "string" ? cfg.command : undefined,
			args: Array.isArray(cfg.args) ? (cfg.args as string[]) : undefined,
			env: cfg.env && typeof cfg.env === "object" ? (cfg.env as Record<string, string>) : undefined,
			cwd: typeof cfg.cwd === "string" ? cfg.cwd : undefined,
			url: typeof cfg.url === "string" ? cfg.url : undefined,
			headers: cfg.headers && typeof cfg.headers === "object" ? (cfg.headers as Record<string, string>) : undefined,
			auth: cfg.auth as MCPServer["auth"],
			oauth: cfg.oauth as MCPServer["oauth"],
			transport: ["stdio", "sse", "http"].includes(cfg.transport as string)
				? (cfg.transport as MCPServer["transport"])
				: typeof cfg.type === "string" && ["stdio", "sse", "http"].includes(cfg.type)
					? (cfg.type as MCPServer["transport"])
					: undefined,
			_source: createSourceMeta(PROVIDER_ID, filePath, level),
		});
	}
	return result;
}

async function loadAgentMcpJson(ctx: LoadContext): Promise<LoadResult<MCPServer>> {
	const filePath = path.join(ctx.home, AGENT_DIR_NAME, "mcp.json");
	const content = await readFile(filePath);
	if (!content) {
		return { items: [], warnings: [] };
	}
	const items = parseMcpServers(content, filePath, "user");
	return { items, warnings: [] };
}

registerProvider<MCPServer>(mcpCapability.id, {
	id: PROVIDER_ID,
	displayName: DISPLAY_NAME,
	description: DESCRIPTION,
	priority: PRIORITY,
	load: loadAgentMcpJson,
});

// =============================================================================
// Append System Prompt
// =============================================================================

async function loadAgentAppendSystemPrompt(ctx: LoadContext): Promise<LoadResult<AppendSystemPrompt>> {
	const filePath = path.join(ctx.home, AGENT_DIR_NAME, "APPEND_SYSTEM.md");
	const content = await readFile(filePath);
	if (!content) {
		return { items: [], warnings: [] };
	}
	const item: AppendSystemPrompt = {
		path: filePath,
		content,
		level: "user",
		_source: createSourceMeta(PROVIDER_ID, filePath, "user"),
	};
	return { items: [item], warnings: [] };
}

registerProvider<AppendSystemPrompt>(appendSystemPromptCapability.id, {
	id: PROVIDER_ID,
	displayName: DISPLAY_NAME,
	description: DESCRIPTION,
	priority: PRIORITY,
	load: loadAgentAppendSystemPrompt,
});
