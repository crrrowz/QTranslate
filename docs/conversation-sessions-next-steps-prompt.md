# Prompt: finish QTranslate Conversation Sessions (next steps)

Copy everything below the line into a coding agent that has access to this repository.

---

You are continuing work on **QTranslate**, a Kotlin/Swing desktop translator (Gradle multi-module:
`api`, `core`, `ui-swing`, `app`, `plugins/*`). The feature **Conversation Sessions** is already built
on branch `feature/live-conversation`: it records a call (microphone + system audio via WASAPI
loopback) as two streams, transcribes it, and translates any word on hover. Speech-to-text lives in
the bundled plugin `plugins/speech-services` and has two service roles:

- `SPEECH_RECOGNIZER` — batch ("Accurate text"): a finished clip in, text out
  (`SpeechRecognizer`, `SpeechClient.kt`, providers in `SpeechProvider.kt`: None, Groq, OpenAI,
  Deepgram prerecorded, local Whisper, custom OpenAI-compatible, audio chat models).
- `LIVE_SPEECH_RECOGNIZER` — realtime ("Live text"): streamed audio in, partial and final text out
  (`api/.../speech/LiveSpeechRecognizer.kt`; today only `VoskLiveService.kt`, a client for a local
  Vosk WebSocket server).

In hybrid mode the recorder (`core/.../conversation/ConversationRecorder.kt`) shows live words at once
and replaces each finished line with the accurate provider's text.

## Read first

1. `docs/conversation-sessions-status.md` — what is built, what is verified, what remains.
2. `wiki/Conversation-Sessions.md` — the user guide.
3. The code you will touch: `plugins/speech-services/src/main/kotlin/.../speech/*`,
   `core/src/main/kotlin/.../core/conversation/*`, `ui-swing/src/main/kotlin/.../ui/swing/conversation/*`.
   If the repo has a `graft/` index, use `graft ask` / `graft grep` / `graft skeleton` before reading
   whole files.

## Working rules (mandatory)

- **Small steps, each finished before the next:** the build passes, `./gradlew test` passes, then
  commit on `feature/live-conversation`. Never leave several unfinished changes uncommitted; a stop at
  any point must leave a working program.
- Plan the steps first and keep a visible task list.
- Match the surrounding code: KDoc that explains *why*, kotlin-result 2.x (`Result` is a value class:
  use `getError()`, `get()`, `fold`, never `is Ok`/`as Err`), user-facing strings through
  `LocalizationManager` with keys in `core/src/main/resources/localization/embedded_en.toml`,
  `languages/en-GB.toml` (identical keys, enforced by a test) and `languages/ar-SA.toml`.
- Plugin API is `2.2.0` and unreleased: you may extend it, with defaults that keep existing plugins
  compiling. Settings screens use exhaustive `when` over `ServiceRole`; adding a role means updating
  `PluginPanelModel.kt`, `PluginsPanel.kt` (twice) and `TranslatorSetSection.kt`.
- `MainAppFrame`'s last constructor parameter must stay `initiallyHidden` (a test checks it).
- Do not change unrelated user files (`PowerShellHistory.txt`, `.gitignore`/`.ignore` from graft).
- Do not push or open a PR unless asked.

## Building

The build needs a Java 11 toolchain. Either:
- locally, if `buildSrc/settings.gradle.kts` applies `org.gradle.toolchains.foojay-resolver-convention`
  (Gradle then downloads JDK 11 itself), or
- in Docker: the user's Docker context is a remote Debian VM (no bind mounts). Use a long-lived
  container from an image with `gradle:jdk21` + `openjdk-11-jdk-headless` + `xvfb`, a volume for
  `GRADLE_USER_HOME` with `org.gradle.jvmargs=-Xmx4g` and `org.gradle.workers.max=4` (the default heap
  runs out), and sync sources with
  `tar -c --exclude=.git --exclude='*/build' --exclude=./graft . | docker exec -i <container> tar -x -C /app`.

Packaging: `./gradlew assemblePortable` → `build/release/QTranslate-<version>.zip`. To run it on
Windows: `java -Dsun.java2d.d3d=false -DappData=<folder> -jar QTranslate.jar` (the installed QTranslate
must be closed: single-instance lock on port 49185). Window screenshots: the opt-in test
`ConversationWindowScreenshotTest` under `xvfb-run` with `QT_SCREENSHOTS=<dir>`.

Opt-in integration tests: `VoskLiveIntegrationTest` (env `VOSK_URL`, `VOSK_WAV`, optional `VOSK_LANG`).
A Vosk server runs at `ws://192.168.85.129:8765` (Docker container `vosk-stt`, Arabic and English models).

## Tasks, in order

### 1. Built-in Vosk engine (no Docker, no Python) — highest priority

Goal: a user installs QTranslate and gets local live text with no extra software.

- Add the official Java binding `com.alphacephei:vosk` (Apache-2.0; JNA; bundled natives for
  Windows/Linux/macOS) to `plugins/speech-services`. Check it shades correctly into the plugin jar and
  that natives load on Windows; record the jar size increase.
- New `LiveSpeechRecognizer` implementation "Vosk (built-in)": an in-process `Model` +
  `KaldiRecognizer` (`SetWords(true)`), fed 16 kHz mono PCM, emitting `Partial`/`Final` exactly like
  `VoskSession`. Load models off the UI thread; cache one `Model` per language; free recognizers on
  close.
- Model manager in the plugin settings: list of models per language (small ~40–50 MB, large), download
  with progress into the plugin data directory (`context.getPluginDataDirectory()`), verify, unzip,
  delete. Download only after the user picks a model (show size and source, alphacephei.com).
- Live engine dropdown becomes: Off, Vosk (built-in), Vosk (local server). Keep the server option.
- Tests: recognizer lifecycle with a fake model boundary, model-manager file handling (download faked),
  settings migration. Docs: wiki "Live text" section leads with the built-in engine.

Done when: with no server running, choosing Vosk (built-in) and an English model shows live text in a
recording on Windows.

### 2. Batch vs realtime providers, and cloud live engines

- Give providers declared capabilities (batch, realtime, word timings, language detection) and show in
  each settings group only providers of that kind (hide irrelevant fields with `showIf` where possible).
- Add realtime engines as `LiveSpeechRecognizer`: **Deepgram live** (`wss://api.deepgram.com/v1/listen`,
  `interim_results=true`, `Authorization: Token`), **OpenAI Realtime transcription**
  (`gpt-4o-transcribe`), optionally **AssemblyAI streaming**. Reuse the JDK `java.net.http.WebSocket`
  pattern and send-chaining from `VoskSession`; keys in plugin secrets.
- Tests in the style of `SpeechClientTest` / `VoskMessageTest`; opt-in integration tests gated on env
  keys.

### 3. Language detection for live text

Vosk cannot detect languages (today "Language for Auto-Detect" picks one model). Options to evaluate
and implement one: run two models and keep the more confident final; or use the language the accurate
provider detects for the previous lines of that source. Keep explicit language choices untouched.

### 4. Localize the new strings

Keys under `[conversation]`, `settings_services.speech_recognizer` and
`settings_services.live_speech_recognizer` exist only in English and Arabic; the other 16 files in
`languages/` fall back to English. Add translations, keeping `%s`/`%d` placeholders (a test checks
format safety).

### 5. Unrelated issues found along the way (separate commits)

- System Services OCR on Windows fails with `Exception calling "Join" with "2" argument(s): "Value
  cannot be null. Parameter name: values"` (see `plugins/system-services`, the Windows OCR PowerShell
  script) — likely no text found; handle the empty case.
- Main window drew broken (white blocks, duplicated bars) when run with Java2D Direct3D on the user's
  NVIDIA machine; it was relaunched with `-Dsun.java2d.d3d=false`. Confirm with the user whether that
  fixed it before changing anything.

### 6. Hand-over

Update `docs/conversation-sessions-status.md` (done / verified / remaining) and `CHANGELOG.md`, run the
full test suite, and report: commits made, what was verified by running it versus only by tests, and
anything left. Do not claim a build, test or behaviour you did not observe.
