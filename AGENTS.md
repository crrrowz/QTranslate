# AGENTS.md — working rules for AI agents in QTranslate

QTranslate is a Kotlin/Swing desktop translator. Gradle modules: `api` (plugin contract), `core`
(logic, no UI), `ui-swing`, `app` (wiring, `AppDependencies.kt`, `Main.kt`), `plugins/*` (bundled
plugins, each its own jar). Read `wiki/Architecture.md` before structural changes.

## Spend tokens carefully

- **Find before you read.** If the `graft` CLI is available (`npm i -g @nanonets/graft`, then
  `graft build`; the `graft/` index is gitignored), use it first: `graft ask "<question>" --source`
  to locate and understand, `graft grep "<symbol>"` for every occurrence, `graft skeleton <file>` for a
  file's API, `graft callers <symbol> --depth 2` before changing a signature. Otherwise use ripgrep.
- Read only the line range you need, never a whole large file to "get context". `MainAppFrame.kt`,
  `DocumentTranslation.kt`, `PluginsPanel.kt` and `MainStore.kt` are thousands of lines.
- Do not re-read a file you just edited, and do not re-run a search with reworded terms; switch tool.
- Keep command output short: filter Gradle output to errors and test summaries
  (`grep -E "^e:|FAILED|BUILD"`), never print whole logs or test XML.
- Run the narrowest test that proves the change (`--tests '*ClassName*'`) while iterating; the full
  suite once before each commit.

## Work in small, committed steps

- Plan the steps first and keep a visible task list.
- Every step ends **buildable, with tests passing, and committed** before the next starts. A stop at
  any moment (for example tokens running out) must never leave a broken tree.
- Branch first if on `develop`. Do not push, open PRs or merge unless asked.
- Never commit or modify unrelated user files: `PowerShellHistory.txt`, the graft-added `.ignore` and
  `.gitignore` lines, or local tooling changes you did not make.

## Build and test

- `buildSrc` needs a **Java 11 toolchain**. If none is installed, Gradle can provision one when
  `buildSrc/settings.gradle.kts` applies `org.gradle.toolchains.foojay-resolver-convention`; otherwise
  build in Docker with JDK 11 (give Gradle `-Xmx4g`; the default heap runs out on the full build).
- `./gradlew test` runs ~1500 tests. Opt-in tests skip unless their env vars are set
  (`QT_SCREENSHOTS` + a display/xvfb for window screenshots; `VOSK_URL`/`VOSK_WAV` for a real Vosk server).
- `./gradlew assemblePortable` builds `build/release/QTranslate-<version>.zip`. The installed app must
  be closed to run a test copy (single-instance lock, port 49185).
- Never claim a build, test or behaviour you did not actually observe; say what was verified by running
  it and what only by tests.

## Code conventions

- Match the surrounding code. KDoc explains *why*, not what. No speculative abstractions.
- `kotlin-result` 2.x: `Result` is a value class. Use `getError()`, `get()`, `fold`, `map`, `andThen`;
  never `is Ok` / `as Err`, and no `vararg` of `Result`.
- `core` must not depend on Swing. UI state flows through stores/`StateFlow`; Swing work on the EDT
  (`Dispatchers.Swing`).
- Plugin API (`api`) changes keep existing plugins compiling: new members get defaults, optional
  behaviour goes in a separate interface (see `MultipartHttpClient`). Bump `ApiVersion` when the
  contract changes.
- A new `ServiceRole` needs branches in `PluginPanelModel.kt`, `PluginsPanel.kt` (two `when`s) and
  `TranslatorSetSection.kt`, plus a `settings_services.*` string.
- `MainAppFrame`'s last constructor parameter must stay `initiallyHidden` (a test checks it).

## Strings and localization

- All user-facing text goes through `LocalizationManager` with literal keys
  (`getString("section.key")`), so the key test can find them.
- Add every new key to `core/src/main/resources/localization/embedded_en.toml` **and**
  `languages/en-GB.toml` (identical key sets, enforced by a test); add Arabic in `languages/ar-SA.toml`.
  Other languages fall back to English. One key per line, values on one line, keep `%s`/`%d` intact.

## Feature notes

- Conversation Sessions: status and remaining work in `docs/conversation-sessions-status.md`; the next
  steps as a ready prompt in `docs/conversation-sessions-next-steps-prompt.md`; user guide in
  `wiki/Conversation-Sessions.md`.
