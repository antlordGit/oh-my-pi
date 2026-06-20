import * as fs from "node:fs/promises";
import * as os from "node:os";
import * as path from "node:path";
import { getProjectDir, normalizePathForComparison, setProjectDir } from "@oh-my-pi/pi-utils";
import type { Args } from "./args";

async function maybeAutoChdir(parsed: Args): Promise<void> {
	if (parsed.allowHome || parsed.cwd) {
		return;
	}

	const home = os.homedir();
	if (!home) {
		return;
	}

	const normalizePath = normalizePathForComparison;

	const cwd = normalizePath(getProjectDir());
	const normalizedHome = normalizePath(home);
	if (cwd !== normalizedHome) {
		return;
	}

	const isDirectory = async (p: string) => {
		try {
			const s = await fs.stat(p);
			return s.isDirectory();
		} catch {
			return false;
		}
	};

	const candidates = [path.join(home, "tmp"), "/tmp", "/var/tmp"];
	for (const candidate of candidates) {
		try {
			if (!(await isDirectory(candidate))) {
				continue;
			}
			setProjectDir(candidate);
			return;
		} catch {
			// Try next candidate.
		}
	}

	try {
		const fallback = os.tmpdir();
		if (fallback && normalizePath(fallback) !== cwd && (await isDirectory(fallback))) {
			setProjectDir(fallback);
		}
	} catch {
		// Ignore fallback errors.
	}
}

export async function applyStartupCwd(parsed: Args): Promise<void> {
	if (parsed.cwd) {
		// Validate up front so a missing --cwd surfaces a clear message instead of
		// a raw ENOENT chdir stack trace from setProjectDir.
		const resolvedCwd = path.resolve(parsed.cwd);
		let stat: Awaited<ReturnType<typeof fs.stat>> | undefined;
		try {
			stat = await fs.stat(resolvedCwd);
		} catch {
			throw new Error(`--cwd directory does not exist: ${resolvedCwd}`);
		}
		if (!stat.isDirectory()) {
			throw new Error(`--cwd is not a directory: ${resolvedCwd}`);
		}
		setProjectDir(parsed.cwd);
		// setProjectDir resolves the (possibly relative) target against the launch
		// cwd and chdirs into it. Re-sync parsed.cwd to the resolved absolute path
		// so downstream consumers (buildSessionOptions, settings/discovery, session
		// persistence) don't re-resolve a relative string against the new cwd.
		parsed.cwd = getProjectDir();
		return;
	}
	await maybeAutoChdir(parsed);
}
