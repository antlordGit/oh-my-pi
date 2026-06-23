/**
 * Append System Prompt Capability
 *
 * APPEND_SYSTEM.md files that are *appended* to the agent's base system prompt.
 * Distinct from {@link systemPromptCapability} (which holds the replacement-style
 * SYSTEM.md): a single level can hold multiple append prompts (one per file),
 * and downstream consumers concatenate them after the base/system prompt.
 */
import { defineCapability } from ".";
import type { SourceMeta } from "./types";

/**
 * A custom append-system-prompt file (typically `APPEND_SYSTEM.md`).
 */
export interface AppendSystemPrompt {
	/** Absolute path to the file. */
	path: string;
	/** File content. */
	content: string;
	/** Which level this came from. */
	level: "user" | "project";
	/** Source metadata. */
	_source: SourceMeta;
}

export const appendSystemPromptCapability = defineCapability<AppendSystemPrompt>({
	id: "append-system-prompt",
	displayName: "Append System Prompt",
	description: "APPEND_SYSTEM.md files that are appended to the base system prompt",
	// Key on absolute path so multiple providers can each contribute their own
	// file (e.g. system-env + a future project-level provider) without
	// collapsing into a single item per level.
	key: sp => sp.path,
	validate: sp => {
		if (!sp.path) return "Missing path";
		if (sp.content === undefined) return "Missing content";
		return undefined;
	},
});
