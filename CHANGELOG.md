# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](https://keepachangelog.com/).

## [Unreleased]

## [0.1.1] - 2026-04-20

The 0.1.1 release makes clj-xref a first-class tool for AI coding
assistants. A new CLI, a `/xref` slash command for Claude Code, and
a `CLAUDE.md` project guidance snippet together let Claude query the
cross-reference database during normal coding work — before changing
signatures, tracing flow, or estimating blast radius — instead of
falling back to grep. Also ships derived queries (`unused`, `call-graph`,
`apropos`), Graphviz output, incremental analysis, and an LLM token
savings benchmark.

### Added

- **Claude Code `/xref` slash command** (`doc/claude-slash-command.md`):
  a drop-in markdown file for `.claude/commands/xref.md` that exposes
  xref queries as user-invocable slash commands — `/xref init`,
  `/xref who-calls ns/fn`, `/xref unused`, `/xref graph ns/fn`, etc.
  Wraps the CLI so Claude runs the query in the user's terminal.
- **Claude Code `CLAUDE.md` project guidance** (`doc/claude-md-template.md`):
  a drop-in snippet that teaches Claude Code to invoke xref *proactively*
  during coding work — before signature changes, renames, or deletions;
  when estimating blast radius; when looking for dead code; when
  onboarding to unfamiliar code. Shifts the initiative from user-typed
  slash commands to Claude recognizing xref-worthy moments from context.
  Both artifacts work with any AI assistant that can run shell commands.
- **CLI with subcommand dispatch** (`clj -M:xref <command>`): `init`,
  `who-calls`, `calls-who`, `who-references`, `who-implements`,
  `who-dispatches`, `who-macroexpands`, `unused`, `ns-deps`,
  `ns-dependents`, `apropos`, `graph`. Auto-generates the database on
  first query. Output is human-readable text, parseable by downstream
  tools.
- Derived query functions on the query API: `unused-vars`, `call-graph`
  (transitive, depth-limited, bidirectional), `apropos` (regex search
  over var names).
- `clj-xref.graph` namespace: DOT/Graphviz output for namespace
  dependency graphs (`ns-dep-dot`) and call graphs (`call-graph-dot`).
- Incremental analysis: `:only` flag in both the lein plugin and
  deps.edn tool for re-analyzing specific files without a full rebuild.
- LLM token savings benchmark (`lein measure-improvement`): compares
  whole-tree vs xref-guided context selection using the Claude API.

### Changed

- All formatted output now uses the clj-format Hiccup DSL. No more
  format strings or printf.
- Adopted clj-uuid section banner style across all source files.
- Bumped clj-kondo to 2026.01.19 and clj-format to 0.1.2.
- Best-effort database generation: warns to stderr when clj-kondo
  reports analysis errors but still writes the database (was: abort on
  error). Real-world codebases with custom macros or incomplete
  classpaths now produce a usable database instead of nothing.
- Consolidated the `:xref` deps.edn alias docs to a single dual-mode
  form that serves both `clj -M:xref <cmd>` (CLI) and
  `clj -T:xref generate` (tool) invocations.
- Renamed lein project aliases (`xref-dev`, `bench`) so they no longer
  shadow the real `leiningen.xref` and `leiningen.measure-improvement`
  plugin tasks or drop their arguments.

### Fixed

- Atomic EDN writes via temp file + rename. Interrupted writes no
  longer leave a corrupt database in place.
- Symbols with leading `@` (npm scoped packages like
  `@tanstack/react-pacer`) are normalized before serialization (#1).
- `TokenNode` values that clj-kondo emits in `:var-definitions :name`
  for macros expanding to `def` are coerced to proper symbols across
  all transform paths, restoring EDN round-tripping (#3).
- Restored `-T:xref generate` tool mode in the deps.edn `:xref` alias
  alongside the new `-M:xref` CLI mode.

## [0.1.0] - 2026-04-13

### Added
- Cross-reference database for Clojure code, built on clj-kondo static analysis.
- Leiningen plugin (`lein xref`) and deps.edn tool (`clj -T:xref generate`) for generating EDN xref databases.
- Query API: `who-calls`, `calls-who`, `who-references`, `who-macroexpands`, `who-implements`, `who-dispatches`, `ns-vars`, `ns-deps`, `ns-dependents`.
- Xref entry kinds: `:call`, `:reference`, `:macroexpand`, `:dispatch`, `:implement`.
- Protocol implementation type inference from enclosing `defrecord`/`deftype`.
- Validation: `from-kondo-analysis` throws on missing analysis data instead of producing empty databases.
- Warning to stderr when clj-kondo reports analysis errors.
- Deep-merge of `:analysis` kondo config so caller additions don't clobber built-in defaults.
- Comprehensive test suite: unit, adversarial, stress, generative (test.check), and integration tests.
- GitHub Actions CI workflow (Temurin JDK 25, lein + clojure CLI).

[Unreleased]: https://github.com/danlentz/clj-xref/compare/v0.1.1...HEAD
[0.1.1]: https://github.com/danlentz/clj-xref/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/danlentz/clj-xref/releases/tag/v0.1.0
