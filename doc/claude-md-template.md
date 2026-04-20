# Claude Code project guidance for clj-xref

This file is a `CLAUDE.md` snippet. Copy everything below the first `---` divider into your project's `CLAUDE.md` to teach Claude Code to use clj-xref proactively when working on Clojure code. Combine it with the [`/xref` slash command](claude-slash-command.md) for the fullest integration.

## Prerequisites

- The `:xref` alias set up in `deps.edn` (see the main [README](../README.md#depsedn)).
- `.clj-xref/` in `.gitignore` — the database is generated, not committed.

The snippet assumes the default database path (`.clj-xref/xref.edn`). Adjust paths in the snippet if your project overrides them.

## Snippet

Copy from here:

---

## clj-xref cross-reference database

This project uses [clj-xref](https://github.com/danlentz/clj-xref) — a pre-computed cross-reference database at `.clj-xref/xref.edn`. Query it through the CLI (`clj -M:xref <command>`) or by reading the EDN file directly. Use it instead of `grep` / manual file-walking whenever you need to understand how vars relate to each other.

### When to use clj-xref

Query the xref database before taking any of these actions:

- **Changing a function signature** — run `who-calls` on the target var to find every caller that needs updating.
- **Renaming or moving a var** — run `who-references` (catches both calls and non-call usages) to find every site that needs updating.
- **Deleting a var** — run `who-references`; if empty it is safe to delete, otherwise confirm with the user.
- **Understanding a protocol or multimethod** — `who-implements <Protocol>` and `who-dispatches <multi>` enumerate every implementation / dispatch value.
- **Estimating blast radius** — `call-graph` or `ns-dependents` shows what transitively depends on the thing you're changing.
- **Finding dead code** — `unused` identifies vars nothing references. Useful during cleanup or before writing new code that might duplicate existing vars.
- **Onboarding to unfamiliar code** — `ns-deps` / `calls-who` trace flow from a top-level function downward.

### How to run queries

```bash
clj -M:xref who-calls myapp.orders/process-payment
clj -M:xref calls-who myapp.web/handler
clj -M:xref who-references myapp.config/*db-url*
clj -M:xref who-implements myapp.protocols/Billable
clj -M:xref who-dispatches myapp.events/handle-event
clj -M:xref unused
clj -M:xref ns-deps myapp.orders
clj -M:xref ns-dependents myapp.orders
clj -M:xref apropos process
clj -M:xref graph myapp.core/main
```

The database is auto-generated on first query if `.clj-xref/xref.edn` does not yet exist.

### Efficient usage patterns

- **Read the EDN directly when doing multiple lookups.** `.clj-xref/xref.edn` is plain EDN with the shape `{:vars [...] :refs [...] :namespaces [...]}`. Reading it once with the Read tool and querying it programmatically is faster than shelling out for each query.
- **Don't grep when xref knows.** `grep process-payment src/` returns string matches, comments, and false positives. `clj -M:xref who-calls myapp.orders/process-payment` returns exact call sites only.
- **Use `calls-who` to read unfamiliar code top-down.** Starting at an entry point and walking outgoing calls is usually faster than reading whole files sequentially.

### Keeping the database fresh

The database reflects the state at the last `init`. After significant source edits, regenerate before querying:

```bash
# Full rebuild
clj -M:xref init

# Incremental — just re-analyze specific files (faster)
clj -T:xref generate :only '["src/path/to/edited.clj"]'
```

Queries made after edits without regeneration may show stale data. If the user's question depends on code you just changed, regenerate first.

### What clj-xref does not see

- **Macros and their expansions.** clj-xref sees macro usage sites but not what a macro expands into. Custom macros without clj-kondo hooks may have invisible internal dependencies.
- **Higher-order function dispatch.** `(map f coll)` — `map` is recorded as called, but the identity of `f` is not resolved statically.
- **Runtime-only relationships.** Dynamic `require`, `eval`, `alter-var-root`, `with-redefs`, etc.

Fall back to Grep or Read when you hit these limits. Flag the limitation to the user if the answer may be incomplete.

---

End of snippet.
