/**
 * Bash intent interceptor - redirects common shell patterns to proper tools.
 *
 * When an LLM calls bash with patterns like `grep`, `cat`, `find`, etc.,
 * this interceptor provides helpful error messages directing them to use
 * the specialized tools instead.
 */
import * as path from "node:path";
import { type BashInterceptorRule, DEFAULT_BASH_INTERCEPTOR_RULES } from "../config/settings-schema";

export interface InterceptionResult {
	/** If true, the bash command should be blocked */
	block: boolean;
	/** Error message to return instead of executing */
	message?: string;
	/** Suggested tool to use instead */
	suggestedTool?: string;
}

/**
 * Compiled out-of-workspace path intercept result.
 */
export interface BashPathInterceptResult {
	block: boolean;
	offendingPath?: string;
}

// =============================================================================
// Workspace path-boundary interceptor
// =============================================================================

/**
 * Scan a shell command string for file paths that would reach outside `cwd`.
 *
 * Shell-safety notes:
 * - Skips quoted regions (single and double) so `echo "/etc/hosts"` passes.
 * - Skips words that look like command options (`--foo`, `-e`).
 * - Skips words that contain shell expansions (`$VAR`, `${...}`, `$(...)`).
 * - Validates absolute paths (`/etc/passwd`) and relative paths with `..`
 *   segments (`../foo`, `./../../bar`).
 * - Only blocks plain strings; shell expansions/backticks are left to runtime.
 *
 * @returns `{ block: true, offendingPath }` when an out-of-workspace path is found.
 */
export function checkBashCwdPath(command: string, cwd: string): BashPathInterceptResult {
	const absCwd = path.resolve(cwd);

	// Tokenise shell command: honour quoting rules and escape handling.
	// We never execute the command; we only pick up string-literal paths that
	// unambiguously live outside cwd.
	const tokens = shellTokenize(command);

	for (const token of tokens) {
		if (isShellOption(token)) continue;
		if (token.includes("$") || token.includes("`")) continue;

		const resolved = resolveShellTokenPath(token, absCwd);
		if (resolved === null) continue;
		if (!resolved.outside) continue;

		return { block: true, offendingPath: resolved.absolute };
	}

	return { block: false };
}

interface ResolvedShellToken {
	absolute: string;
	outside: boolean;
}

function resolveShellTokenPath(token: string, absCwd: string): ResolvedShellToken | null {
	// Strip surrounding quotes that were already handled by tokenization.
	let cleaned = token;
	if ((cleaned.startsWith("'") && cleaned.endsWith("'")) || (cleaned.startsWith('"') && cleaned.endsWith('"'))) {
		cleaned = cleaned.slice(1, -1);
		if (cleaned.length === 0) return null;
	}

	// Must look like a filesystem path — starts with /, ./, ../, or ~
	if (!/^[~/.]/.test(cleaned)) return null;

	// Expand ~ to home directory early so the boundary check uses the real path.
	let expanded: string;
	if (cleaned.startsWith("~")) {
		const home = path.resolve(process.env.HOME ?? process.env.USERPROFILE ?? "/");
		if (cleaned === "~") {
			expanded = home;
		} else if (cleaned.startsWith("~/")) {
			expanded = path.join(home, cleaned.slice(2));
		} else {
			// ~otheruser — too rare to bother normalising; let it through.
			return null;
		}
	} else {
		expanded = cleaned;
	}

	const absolute = path.isAbsolute(expanded) ? path.resolve(expanded) : path.resolve(absCwd, expanded);
	const outside = !isPathInside(absolute, absCwd);
	return { absolute, outside };
}

function isPathInside(child: string, parent: string): boolean {
	const absChild = path.resolve(child);
	const absParent = path.resolve(parent);
	if (absChild === absParent) return true;
	const rel = path.relative(absParent, absChild);
	return rel !== "" && !rel.startsWith(`..${path.sep}`) && rel !== ".." && !path.isAbsolute(rel);
}

function isShellOption(token: string): boolean {
	// Matches short options (-x), long options (--foo), option=value (--foo=bar),
	// and numeric arguments (42, 3.14). Does NOT match a plain /path or ./file.
	return /^-{1,2}[a-zA-Z0-9]/.test(token) || /^\d+/.test(token);
}

// Simple tokeniser that respects shell quoting.
function shellTokenize(command: string): string[] {
	const tokens: string[] = [];
	let i = 0;
	let current = "";
	const len = command.length;

	while (i < len) {
		const ch = command[i];

		if (ch === "\\" && i + 1 < len) {
			// Escaped character — keep the next character literally.
			current += command[i + 1];
			i += 2;
			continue;
		}

		if (ch === "'") {
			// Single-quoted region: everything until next ' is literal.
			const end = command.indexOf("'", i + 1);
			if (end === -1) {
				current += command.slice(i);
				i = len;
			} else {
				current += command.slice(i + 1, end);
				i = end + 1;
			}
			continue;
		}

		if (ch === '"') {
			// Double-quoted region: stop at closing " or EOL.
			const end = command.indexOf('"', i + 1);
			if (end === -1) {
				current += command.slice(i);
				i = len;
			} else {
				current += command.slice(i + 1, end);
				i = end + 1;
			}
			continue;
		}

		if (ch === " " || ch === "\t" || ch === "\n" || ch === "\r") {
			if (current.length > 0) {
				tokens.push(current);
				current = "";
			}
			i++;
			continue;
		}

		// Shell operators: ; | & < > are token boundaries too.
		if (";|&<>".includes(ch)) {
			if (current.length > 0) {
				tokens.push(current);
				current = "";
			}
			// Consume the operator as its own token.
			if (ch === ">" && command[i + 1] === ">") {
				tokens.push(">>");
				i += 2;
				continue;
			}
			if (ch === "&" && command[i + 1] === "&") {
				tokens.push("&&");
				i += 2;
				continue;
			}
			if (ch === "|" && command[i + 1] === "|") {
				tokens.push("||");
				i += 2;
				continue;
			}
			tokens.push(ch);
			i++;
			continue;
		}

		current += ch;
		i++;
	}

	if (current.length > 0) {
		tokens.push(current);
	}

	return tokens;
}

// =============================================================================
// Original bash interceptor (cat/grep/find → read/search/find tools)
// =============================================================================

/**
 * Compile bash interceptor rules into regexes, skipping invalid patterns.
 */
function compileRules(rules: BashInterceptorRule[]): Array<{ rule: BashInterceptorRule; regex: RegExp }> {
	const compiled: Array<{ rule: BashInterceptorRule; regex: RegExp }> = [];
	for (const rule of rules) {
		const flags = rule.flags ?? "";
		try {
			compiled.push({ rule, regex: new RegExp(rule.pattern, flags) });
		} catch {
			// Skip invalid regex patterns
		}
	}
	return compiled;
}

/**
 * Check if a bash command should be intercepted.
 *
 * @param command The bash command to check
 * @param availableTools Set of tool names that are available
 * @returns InterceptionResult indicating if the command should be blocked
 */
export function checkBashInterception(
	command: string,
	availableTools: string[],
	rules: BashInterceptorRule[] = DEFAULT_BASH_INTERCEPTOR_RULES,
): InterceptionResult {
	// Normalize command for pattern matching
	const normalizedCommand = command.trim();
	const compiled = compileRules(rules);

	for (const { rule, regex } of compiled) {
		// Only block if the suggested tool is actually available
		if (!availableTools.includes(rule.tool)) {
			continue;
		}

		if (regex.test(normalizedCommand)) {
			return {
				block: true,
				message: `Blocked: ${rule.message}\n\nOriginal command: ${command}`,
				suggestedTool: rule.tool,
			};
		}
	}

	return { block: false };
}
