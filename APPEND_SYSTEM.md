## CodeGraph (mcp__codegraph__*)

This workspace has a CodeGraph knowledge graph (`.codegraph/`) for surgical
code intelligence. Prefer its tools over grep/glob/Read when exploring code:

- `mcp__codegraph__codegraph_explore` — primary tool. One call returns relevant
  symbols verbatim source grouped by file, plus call paths and blast radius.
  Use for "how does X work", "trace X → Y", "what would change if I edit X".
- `mcp__codegraph__codegraph_callers` / `..._impact` — narrow follow-ups after
  explore surfaces a symbol of interest.

The graph auto-syncs ~2s after file edits. If a fresh edit is not reflected,
retry once; do not fall back to grep without trying codegraph first.

Fallback (graph missing / broken): use built-in `search` + `find` + `read`.