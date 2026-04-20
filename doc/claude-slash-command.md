# Claude Code `/xref` slash command

Drop this file into `.claude/commands/xref.md` in any Clojure project to add `/xref` as a Claude Code slash command.

## Prerequisites

Add the `:xref` alias to your `~/.clojure/deps.edn` (or project `deps.edn`):

```clojure
:xref {:extra-deps {com.github.danlentz/clj-xref {:mvn/version "0.1.1"}}
       :main-opts  ["-m" "clj-xref.cli"]
       :ns-default clj-xref.tool}
```

The alias serves both `clj -M:xref <cmd>` (CLI subcommands used below) and `clj -T:xref generate` (tool invocation for database generation).

Ensure `.clj-xref/` is in `.gitignore`.

## Slash command file

Copy the following into `.claude/commands/xref.md`:

---

# Cross-reference Clojure code with clj-xref

Query the cross-reference database for the current Clojure project. Built on clj-kondo static analysis.

## How to run

```
/xref init                          — generate/regenerate the xref database
/xref who-calls ns/fn               — who calls this function?
/xref calls-who ns/fn               — what does this function call?
/xref who-implements ns/Protocol     — who implements this protocol?
/xref who-dispatches ns/multi        — defmethod dispatch values
/xref who-macroexpands ns/macro      — where is this macro expanded?
/xref unused                         — find dead code
/xref ns-deps ns                     — namespace dependencies
/xref ns-dependents ns               — reverse namespace dependencies
/xref apropos pattern                — search vars by name pattern
/xref graph ns/fn                    — transitive call graph
```

## Instructions

All commands are run via the CLI:

```bash
clojure -M:xref <command> [args]
```

The database is auto-generated on first query if `.clj-xref/xref.edn` doesn't exist.

### Using xref proactively

When working on code changes in this session, use xref proactively:

- **Before changing a function signature**: run `who-calls` to find all callers that need updating
- **Before understanding unfamiliar code**: run `calls-who` to see dependencies
- **Before removing a function**: run `who-calls` to verify nothing depends on it
- **After refactoring**: run `init` to regenerate, or use incremental mode:

```bash
clojure -M:xref init
```

### Regenerating after edits

After significant code changes, regenerate the database:

```bash
clojure -M:xref init
```
