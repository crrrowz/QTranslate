# Prompt: implement the local (Vosk) STT improvement plan

Copy everything below the line into a coding agent that can access this repository (and, for step 1,
the Vosk server repository).

---

You are improving **live (local) speech-to-text** in QTranslate's Conversation Sessions, on branch
`feature/live-conversation`. The plan, with the evidence and causes, is
`docs/local-stt-improvement-plan.md` (written in Arabic; read it fully first — sections 0–5). Follow the
repository rules in `AGENTS.md` (small steps; each ends buildable, tested and committed; graft/rg before
reading; no unrelated files).

## Before you start (mandatory)

The branch moves: other agents have already built parts of this. **Do not redo or overwrite their work.**

1. Run `git log --oneline -15` and `git status`. Read the recent `feat(speech)` commits, in particular:
   - `0cef446` — built-in offline Vosk engine (`VoskBuiltinService.kt`) and model manager
     (`VoskModelManager.kt`), settings migration, Windows build guide (`docs/windows-build-and-run-guide.md`).
   - `dd3cfce` — provider capabilities, settings visibility (`SpeechProvider.kt`, `SpeechSettings.kt`,
     `DynamicPluginSettingsDialog.kt`), cloud live engines (`DeepgramLiveService.kt`,
     `OpenAiRealtimeService.kt`).
2. **Uncommitted changes** in `ConversationRecorder.kt` / `ConversationRecorderTest.kt` may belong to
   another agent working right now (at the time of writing: switching the live model to the language the
   accurate provider detects, `updateDetectedLanguage`). If such changes exist and you did not make them,
   stop and ask the user whether to build on them, wait, or leave them — never discard or reformat them.
3. For each step below, **first check whether it is already done or partly done** (code, tests, docs),
   and only implement what is missing. Say so in your report ("step 3: already done in dd3cfce; added X").
4. Read `docs/handy-analysis.md` and section 5.1 of the plan: the Handy project (MIT) has ready patterns
   for several steps (noted per step below). You may port code from it with its licence notice.

## Context you need

- Live recognition: role `LIVE_SPEECH_RECOGNIZER` (`api/.../speech/LiveSpeechRecognizer.kt`). Engines
  (check the current set): Vosk server client `VoskLiveService.kt` (JDK `java.net.http.WebSocket`, sends
  chained in `enqueue`), built-in Vosk `VoskBuiltinService.kt`, cloud `DeepgramLiveService.kt` and
  `OpenAiRealtimeService.kt`. Steps that concern "the live engine" apply to all of them unless stated. Accurate recognition: `SpeechClient.kt`, providers in `SpeechProvider.kt`,
  settings in `SpeechSettings.kt` (groups "live" / "accurate", `LIVE_ENGINE_OPTIONS`,
  `liveAutoLanguage`), persistence in `SpeechPlugin.kt`.
- Recorder: `core/.../conversation/ConversationRecorder.kt` — `StreamRecorder` per source: capture loop,
  `connectLive`/`closeLive`/`onLiveEvent`/`settle` (live finals → draft segments → accurate refinement),
  `liveFailed` (back-off reconnect), segmenter fallback. Voice detector: `SpeechSegmenter.kt`
  (`activeStartSample`, adaptive noise floor). Session model: `ConversationSession.kt`
  (`languageOf(source)`, segment `draft`/`pending`/`failed`).
- Window: `ui-swing/.../conversation/ConversationWindow.kt` (device pickers, `loadDevices`, live strip,
  status line), `TranscriptView.kt` (renders segments, per-paragraph direction), strings in
  `ConversationStrings.kt` + `[conversation]` keys in the three language files.
- Devices: `JavaSoundAudioBackend.kt` (mic), `WasapiLoopbackBackend.kt` +
  `core/src/main/resources/conversation/wasapi-loopback.ps1` (outputs; device ids are WASAPI endpoint ids).
- Vosk server: `D:\files\Contracted projects\google extensions\Vosk-STT-Chrome-Extension\server\vosk_server.py`
  (+ `Dockerfile`). It runs in Docker on the user's VM as container `vosk-stt`
  (`ws://192.168.85.129:8765`, volume `vosk-models` with `ar` and `en` models). That repository has
  uncommitted changes of the user's: edit only what a step needs and do not commit there unless asked.
- Build: Java 11 toolchain (foojay in `buildSrc` or the Docker build described in `AGENTS.md`).
  Opt-in real-server test: `VoskLiveIntegrationTest` with `VOSK_URL`, `VOSK_WAV`, `VOSK_LANG`.

## Decisions (use these defaults unless the user says otherwise; record them in the plan doc)

- Turn merge gap: **2.0 s**, user-adjustable 1–4 s.
- Language auto-detect: **parallel recognisers per candidate language + confidence voting** first;
  a language-ID model only if that proves insufficient.
- Candidate languages: the stream's chosen language, or for Auto the **user's languages from the
  window** (not hard-coded ar/en); at most 2 candidates per stream.
- Default mode for new users: **Hybrid** when an accurate provider is configured, otherwise Local.

## Steps (one commit each; stop and report if a step's acceptance check fails)

1. **Vosk server off the event loop.** In `vosk_server.py`, run `AcceptWaveform`/`Result`/
   `PartialResult`/`FinalResult` in a per-connection worker (`loop.run_in_executor` with a dedicated
   single-thread executor per connection, preserving order); add `--ping-interval`/`--ping-timeout`
   options (defaults that tolerate load, e.g. 20 s / 60 s); add `--endpoint-delays T_START,T_END,T_MAX`
   mapped to `KaldiRecognizer.SetEndpointerDelays` when the installed vosk version has it (log and skip
   otherwise). Rebuild the image and restart `vosk-stt` with `--preload ar,en`.
   *Accept:* two parallel streams for 20 minutes (use the integration test in a loop or a script) with no
   `keepalive ping timeout` in the server log. Also confirm the **built-in** engine never runs recognition
   on a coroutine dispatcher thread shared with I/O or the UI (dedicated thread per session); if it does,
   fix it the same way. *Handy reference:* engine work isolated from the app (`engine_supervisor/`).
2. **Reconnect without losing speech.** In `VoskSession`/`StreamRecorder`: answer pings explicitly if the
   JDK client does not; keep a rolling buffer of the last ~10 s of PCM per stream; on reconnect, replay
   the gap's audio to the new session before live audio; show a live status (connected / reconnecting /
   off) in the window. *Accept:* a recorder test with a scripted live session that fails mid-stream and
   reconnects proves the gap audio is sent; no speech is lost.
3. **Mode selector.** Replace the user-facing split with a single Mode: Local only / Cloud only / Hybrid
   in `SpeechSettings` (hide fields not used by the mode with `showIf`), remove "Language for
   Auto-Detect" (languages come from the window only), migrate saved settings (Vosk + provider → Hybrid,
   Vosk + None → Local, Off → Cloud). Update `VoskLiveService`/`SpeechClient` accordingly and the wiki.
   *Accept:* settings tests for migration and per-mode behaviour; the provider fields are hidden in Local.
   *Note:* `dd3cfce` added capabilities and settings visibility, and `0cef446` added a migration test —
   extend those rather than adding a parallel mechanism.
4. **Device check.** In the window: a "Test devices" action with 3-second level meters per stream;
   warnings when the mic device name matches Stream/Mix/Monitor/Chat/Loopback, and when "them" is a
   general output while an output named "*Chat*" exists; prefer real microphones and Chat outputs by
   default. *Accept:* unit tests for the heuristics; screenshot test still passes at 780/1000/1300 px.
5. **Echo / duplicate guard.** Detect the mic's speech in the "them" stream (energy-envelope correlation
   over a few seconds, and/or near-simultaneous finals with high text similarity); show a notice with the
   fix; drop the duplicate line from "them". *Accept:* recorder test with the same audio in both streams
   yields one line plus the notice.
6. **Per-stream level normalisation.** *Handy reference:* `audio_toolkit/vad/smoothed.rs`
   (prefill / onset / hangover) for the VAD side. Slow AGC + limiter per stream before the segmenter and the live
   recogniser; per-stream noise-floor calibration in `SpeechSegmenter`. *Accept:* tests with a quiet
   stream (−30 dBFS speech) produce segments; level meter reflects normalised level.
7. **Turns.** Separate engine segments from displayed turns: merge finals of the same source when the
   gap ≤ merge gap and the other source did not speak; close on speaker change, gap > merge gap, length >
   45 s, or language change. Store turns (or turn ids on segments) and render one paragraph per turn.
   *Accept:* pure tests for every rule, including "resumes after 5–6 s → new turn" and "2 s thinking pause
   → same turn"; existing transcript features (hover, selection, replay) still work. Use a VAD hangover
   (Handy's `SmoothedVad`) so short pauses do not end segments in the first place.
8. **Refine per turn.** When a turn closes, send its whole audio to the accurate provider (fall back to
   per-segment for turns > 45 s); replace the drafts of that turn. *Accept:* recorder test shows one
   refinement request per closed turn and the turn text replaced.
9. **Real auto-detect.** For Auto (or two candidate languages), open one live session per candidate per
   stream; score each final by average word `conf` (Vosk word results), unknown-word rate and script
   consistency; keep the best, discard the rest; hysteresis (switch only on a clear margin or two
   consecutive wins); partials follow the current leader; the segment records its language. Never show
   two languages for the same speech. *Accept:* tests with scripted sessions for switching, ties and
   hysteresis; manual check with the real server (Arabic sentence then English sentence → each in its
   own language, no stacked duplicates). Mind the doubled server load (step 1 first).
   *Build on, don't replace:* any existing "switch the live model to the accurate provider's detected
   language" logic (see "Before you start"); use it as one signal next to confidence voting.
   *Handy references:* the audio language-ID model **VoxLingua107 ECAPA** (apache-2.0, in Handy's
   catalogue) as a tie-breaker, and confidence-gated text language ID (`audio_toolkit/lang_id.rs`:
   accept only reliable detections ≥ 0.9 restricted to the candidate languages; otherwise keep the current
   language).
10. **Fixtures and metrics.** Add short fixtures (English, Iraqi Arabic, alternating, mixed, quiet call,
    pauses) under test resources with reference text, and an opt-in test that reports speech capture
    rate, wrong-language lines and live latency against the real server.

## When you finish (or stop)

Update `docs/local-stt-improvement-plan.md` (mark done steps, record decisions and measured numbers) and
`docs/conversation-sessions-status.md`; run the full test suite; report the commits, what you verified by
running it (server logs, real-server tests, screenshots) versus by unit tests only, and what remains.
Do not push or open a PR unless asked.
