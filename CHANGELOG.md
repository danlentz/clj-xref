# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](https://keepachangelog.com/).

## [Unreleased]

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

[Unreleased]: https://github.com/danlentz/clj-xref/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/danlentz/clj-xref/releases/tag/v0.1.0
