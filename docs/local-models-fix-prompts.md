# برومبتات إصلاح الموديلات المحلية (Vosk)

كل برومبت يتنفذ بجلسة ذكاء اصطناعي منفصلة، **بالترتيب**. التفاصيل والأدلة بـ [local-models-audit.md](local-models-audit.md) (المهام 1–7، Vosk المحلي) و [speech-providers-audit.md](speech-providers-audit.md) (المهام 8–10، المزودات السحابية والسيرفرات).

**الترتيب المقترح:** 8 أول (خلل P0 معزول بملفين)، بعدين 1 ← 2 ← 9 ← 3 ← 4 ← 5 ← 6 ← 7 ← 10. كلهن بنفس الإضافة، فلا تشغّل اثنين سوا.
الصق **المقدمة المشتركة** أول، وبعدها برومبت المهمة.

---

## المقدمة المشتركة (تنلصق قبل كل مهمة)

```text
You are working in the QTranslate repository (Kotlin/Swing desktop translator, Gradle modules:
api, core, ui-swing, app, plugins/*).

Before you write any code:
1. Read AGENTS.md and CLAUDE.md in the repo root and follow them exactly. They are the project's rules
   and override your defaults: small committed steps, branch off develop first, localization rules,
   kotlin-result 2.x usage (no `is Ok` / `as Err`), core must not depend on Swing, plugin API changes
   keep existing plugins compiling (new members get defaults, optional behaviour in a separate
   interface, bump ApiVersion), never commit PowerShellHistory.txt or graft's .ignore/.gitignore lines.
2. Read the audit this task comes from: docs/local-models-audit.md for tasks 1-7 (item IDs A1, B3,
   C1...) and docs/speech-providers-audit.md for tasks 8-10 (item IDs P1-P13, R1-R7). Treat its
   findings as leads to verify, not facts: re-check each one in the code before changing anything, and
   say so if one turns out wrong. Anything about an external provider API (model names, endpoints,
   event formats, supported languages) must be checked against that provider's current official docs
   before you code it; write down the doc URL and date you used in the commit message or the audit.
3. Use graft to find code before reading files. If `graft` is not on PATH, install it
   (`npm i -g @nanonets/graft`, then `graft build`); if that is not possible, use ripgrep and the
   prebuilt cards under graft/.
   - `graft ask "<question>" --source` to locate and understand
   - `graft grep "<symbol>"` for every occurrence
   - `graft skeleton <file>` for a file's API instead of reading it whole
   - `graft callers <symbol> --depth 2` before changing any signature
   Never read a whole large file (MainAppFrame.kt, ConversationRecorder.kt, PluginsPanel.kt,
   DynamicPluginSettingsDialog.kt): read only the line range you need.

How to work:
- Start with a visible task list. Each step ends buildable, with tests passing, and committed before the
  next starts. A stop at any moment must never leave a broken tree.
- If the current branch is develop, create `fix/local-models-<task-number>` first. Do not push, open PRs
  or merge unless asked.
- Build/test: buildSrc needs a Java 11 toolchain (see AGENTS.md "Build and test"; Docker with JDK 11 and
  -Xmx4g is the fallback). Iterate with the narrowest test (`--tests '*ClassName*'`), run the full
  `./gradlew test` once before each commit. Filter output (`grep -E "^e:|FAILED|BUILD"`).
- Every user-facing string goes through localization. App strings: LocalizationManager with literal keys,
  added to core/src/main/resources/localization/embedded_en.toml AND languages/en-GB.toml (identical key
  sets) plus Arabic in languages/ar-SA.toml. Speech plugin strings: the plugin's own
  plugins/speech-services/src/main/resources/localization/en.toml and ar.toml via SpeechLocalization.
- Tests: add or update unit tests for every behaviour you change. Swing tests run on the EDT
  (SwingUtilities.invokeAndWait), like the existing conversation tests.
- Never claim a build, test or behaviour you did not observe. At the end, report what was verified by
  running it, what only by tests, and what is still unverified (for example real downloads from
  alphacephei.com or behaviour in the running app).
- When done, update the audit file your task came from: mark the items you fixed, with the commit hash.
```

---

## المهمة 1: سلامة تنصيب الموديل (A1–A5، وإعادة إنتاج B1)

```text
Task: make "is this Vosk model installed?" trustworthy, and make downloads/extraction atomic and
recoverable. Files: plugins/speech-services/src/main/kotlin/.../speech/VoskModelManager.kt and its test
VoskModelManagerTest.kt.

Step 0 - reproduce the reported bug first (B1): "after downloading one model, choosing another model
does not show the Download button". Write a failing unit test (or a documented manual repro) for the
most likely cause: a non-empty but incomplete model folder (left by an interrupted extraction) is
reported as installed by isInstalled(), so downloadSelectedModelVisible() returns false. Also check the
other two candidates listed in the audit (shared disabled button during a running download; swallowed
reflection errors in DynamicPluginSettingsDialog.updateVisibility/createWorkingCopy) and write down
which ones are real. Do not fix the dialog here; that is task 2.

Then fix, one committed step each:
1. A real model check: a folder is a Vosk model only when it has the files Vosk needs (at least
   am/final.mdl and conf/model.conf, plus a graph: graph/HCLG.fst or graph/HCLr.fst+Gr.fst; confirm the
   exact layout against the Vosk docs/model READMEs and the existing test fixtures). isInstalled,
   listInstalledModels and findModelForLanguage all use it. Partial folders are ignored, and
   statusAction reports them as "incomplete, delete and download again".
2. Atomic install: download to <name>.zip.part, extract into a temporary sibling folder
   (<name>.extracting), find the model root inside it by the check above (not by name guessing - A4),
   then move it to its final name in one rename. On any failure or cancel, delete the temp folder.
   A crash can then never leave a folder that looks installed.
3. Resume correctness (A3): never trust the catalogue's approximate sizeBytes as the end of the file.
   Get the real length from the server (HEAD or the first response's Content-Length / Content-Range).
   Treat HTTP 416 as "the .part is already complete" when its length equals the real size, otherwise
   delete the .part and restart. Keep sizeBytes only for display.
4. One download at a time per model (A5): a lock or in-flight set in VoskModelManager; a second request
   for the same model returns a clear "already downloading" result instead of writing the same .part.
5. Keep the old non-resumable downloadModel() consistent with the same rules, or remove it if nothing
   but tests uses it (check with graft callers).

Done when: tests cover partial folder, interrupted extraction, stale/complete .part with 416, wrong
archive root name, concurrent download; the full test suite passes; nothing in the UI changed yet.
```

---

## المهمة 2: واجهة إدارة الموديلات بالإعدادات (B1–B6، A6)

```text
Task: make the Speech Recognition settings page a clear, reliable model manager. Prerequisite: task 1
is merged into your branch (isInstalled is now trustworthy).

Files: plugins/speech-services/.../SpeechSettings.kt, SpeechPlugin.kt, VoskModelManager.kt,
VoskDownloadDialog.kt; ui-swing/.../settings/panels/DynamicPluginSettingsDialog.kt (shared by all
plugins - keep its behaviour for other plugins unchanged and covered by tests); the two places that
decide whether "Test connection" shows: ui-swing/.../main/MainAppFrame.kt openPluginConfiguration and
ui-swing/.../settings/panels/PluginsPanel.kt onConfigure.

Steps (each committed):
1. B1: using the repro from task 1, make the Download/Delete/Import buttons always reflect the model
   currently selected on screen. While a download runs, show which model is downloading and disable
   only what must be disabled; switching the dropdown must still show the right buttons. If
   createWorkingCopy or a <action>Visible() predicate throws, log it instead of silently returning true.
   Add an EDT test for: download A -> select B -> Download visible and enabled.
2. B2: the model dropdown shows a readable label per model: language, size, and an installed mark
   (for example "Arabic - 318 MB - installed"). The stored value stays the model name so saved settings
   keep working. Find how DropdownSetting options are rendered and add display labels in a way that
   does not break other plugins (prefer an optional mechanism with a default).
3. B3: "Test connection" must be available in Local only and Hybrid modes too, and for the built-in
   engine it reports whether the selected model is installed and loads. Today it shows only when a
   service has metadata.requiresConfiguration == true, which SpeechRecognitionService sets only in Cloud
   mode. Fix it without making every plugin show a meaningless test button.
4. B4: Import is always visible (it is not about the selected model). After an import, the dropdown
   and button visibility refresh.
5. B5: pausing must not busy-wait forever; closing the settings dialog during a paused download either
   cancels it or keeps it running with its own progress window - pick one, make it explicit, and make
   sure no thread is left blocked.
6. B6: after a successful download of the selected model, the selection is applied (saved) so the user
   does not have to remember to press Save; say so in the success message.
7. A6: before downloading, check free disk space (archive size x ~2.5 for extraction) and refuse with a
   clear, localized message when it is not enough.

All new and touched messages are localized (plugin en.toml/ar.toml; app strings per AGENTS.md).
Done when: EDT tests cover the button states; full suite passes; you state clearly that the real
download from alphacephei.com was or was not exercised.
```

---

## المهمة 3: ربط الجلسة بالموديل (C1، C2، C6)

```text
Task: when a Conversation Session cannot use the local model, the user must see why and how to fix it,
before and during recording. Prerequisite: tasks 1-2 merged.

Files: core/.../conversation/ConversationRecorder.kt (only the live-open failure path around connectLive),
core/.../conversation/ConversationFeature.kt, ui-swing/.../conversation/ConversationWindow.kt and
ConversationStrings.kt, plugins/speech-services/.../VoskBuiltinService.kt, localization files.

Steps (each committed):
1. C1: a ConfigurationError from openLive (for example "no model for language 'ar'") must produce a
   visible notice, not only "Live: off". Add a RecorderNotice for it (or reuse LiveFailed with a
   distinct, localized message) and show it once per session and source. Keep "no accurate provider
   chosen" silent - that one is a legitimate setup. Add recorder tests for both.
2. C2: with the built-in engine, the spoken-language pickers list the languages of the catalogue,
   marking those without an installed model (for example "Arabic (model not installed)"). When nothing
   is installed, do not pretend English is available (VoskBuiltinService.supportedLanguages fallback).
   Choosing a language without a model shows a short message with a button that opens Speech
   Recognition settings (onOpenSpeechSettings already exists). Decide whether the language data comes
   through SupportedLanguages or a new optional plugin interface; follow AGENTS.md for API changes.
3. C6: before starting a recording in Local only (or with Vosk live), check that a model exists for
   each selected language; if not, warn with the same message and the settings button, and let the
   user cancel.
4. Optional, only if the user asked for it (see "Decisions" in the audit): a Download button directly
   in the conversation window that runs the same VoskModelManager download with its progress window.

Done when: tests cover the notice, the language list with and without installed models, and the
pre-recording check; full suite passes; Arabic strings added.
```

---

## المهمة 4: تحقق صادق بالوضع المحلي (C3، C4)

```text
Task: in Local only mode, the "accurate" service is Vosk itself, so rolling verification compares Vosk
with Vosk and shows VERIFIED/✓ with a high agreement - the opposite of the honest VerificationState the
project introduced. It also doubles CPU work. And core detects this case by matching service names
("Vosk offline", "None (live text only)") in ConversationFeature.speechServiceName.

First verify in code that, in Local only mode with Vosk live text, ConversationRecorder's rolling
verification and settle paths do call SpeechRecognitionService.transcribe, which routes to
VoskBuiltinService.transcribeClip (SpeechPlugin.kt SpeechRecognitionService.transcribe). Report what
you find before changing anything.

Then:
1. Add an optional capability in the api module that lets a SpeechRecognizer say it is not an
   independent accurate source (for example an interface with a property like `isIndependentVerifier`,
   default true for every existing recognizer). Follow AGENTS.md: separate interface, existing plugins
   compile unchanged, bump ApiVersion, KDoc says why.
2. SpeechRecognitionService implements it: false in Local only mode and when the provider is None.
3. ConversationRecorder: when the active recognizer is not an independent verifier and live text is
   on, skip rolling/settle verification and leave live words UNVERIFIED (no badge, no agreement %).
   Keep using transcribe for clips when live text is unavailable (that is the only transcription then).
4. ConversationFeature: replace the name string matching (C4) with the new capability.
5. Tests: recorder tests for Local only (no verification calls, UNVERIFIED), Hybrid (verification
   unchanged), and live-unavailable fallback (clips still transcribed). Run the existing
   conversation/verification tests 3 times to check nothing became flaky.
```

---

## المهمة 5: الذاكرة ودورة حياة الموديل (D1–D4)

```text
Task: load Vosk models only when they are needed, keep at most what is needed in memory, and report
native-library failures clearly. Files: plugins/speech-services/.../SpeechPlugin.kt,
VoskBuiltinService.kt and their tests (VoskBuiltinServiceTest uses fake model handles - keep it that way).

Steps (each committed):
1. D1: do not preload at onEnable unless mode != Cloud only AND live engine is Vosk (built-in).
   Preferred trigger: when the Conversation window opens (needs a small hook; if that requires an API
   change, do it per AGENTS.md) - otherwise on the first openSession/transcribeClip.
2. D2: when the selected model changes, unload models that are no longer selected and not used by an
   open session. Never close a model a live session is using (track in-use counts).
3. D3: load a model outside the shared lock (per-model loading guard), so loading a large model does
   not block the other track or clip transcription for unrelated models.
4. D4: catch UnsatisfiedLinkError/LinkageError when loading the native library and return a clear,
   localized ServiceUnavailableError naming the OS/arch and that built-in Vosk is not supported there.
   A model that fails to load says "the model files look damaged - delete and download it again".
5. Show approximate RAM need for large models in the model label or description (data from the
   catalogue), so users on small machines pick the small model.

Done when: unit tests cover preload rules, eviction with in-use models, parallel loads, and the native
error message; full suite passes.
```

---

## المهمة 6: الموديلات المستوردة واللغة التلقائية (E1، C5، E3)

```text
Task: imported models must be usable, and language choice with Vosk must be predictable.
Files: plugins/speech-services/.../VoskModelManager.kt, VoskBuiltinService.kt, SpeechSettings.kt,
SpeechPlugin.kt and tests.

Steps (each committed):
1. E1: models not in AVAILABLE_MODELS (imported) appear in the model dropdown and in the supported
   languages. Infer the language from the folder name (vosk-model-<...>-<lang>-...) or, when it cannot
   be inferred, ask once during import and store it (for example a small json next to the model).
   ZIP import finds the model root with the task-1 model check, not by the zip file name.
2. C5: with "Auto" and the built-in engine, behaviour is explicit: use the selected model's language,
   and the UI says "Auto uses <language> with the offline engine". If the session language differs
   from the selected model's language and another installed model matches, use it but log and show
   which model is in use (status line), never silently.
3. E3: on first run (no saved voskBuiltinModel), default to the catalogue's small model for the system
   language when one exists, else English small.

Done when: tests cover import with/without inferable language, Auto resolution, first-run default;
full suite passes.
```

---

## المهمة 7: الترجمة والتوثيق وجاهزية الإصدار (B7، E2، E4، E5، A7)

```text
Task: finish the local-model feature for public release.
Files: plugins/speech-services (VoskModelManager.kt, SpeechSettings.kt, VoskBuiltinService.kt,
resources/localization/en.toml and ar.toml), wiki/Conversation-Sessions.md, docs/.

Steps (each committed):
1. B7: every remaining hard-coded user-facing message in the speech plugin (download/delete/import/
   check results, file chooser title, error messages shown to users) goes through SpeechLocalization
   with en and ar entries. Add a test that every key used exists in both files.
2. E2: add a test that VoskModelManager.DROPDOWN_OPTIONS equals AVAILABLE_MODELS names in order
   (it must stay a const for the annotation).
3. E4: build the User-Agent from the real app/plugin version; make downloads honour system proxy
   settings (java.net.useSystemProxies or the app's existing proxy handling, if any - check first).
4. A7: one models directory. Decide between the plugin data directory and the fixed %APPDATA% path,
   migrate models found in the other location once, and fix the import message wording.
5. E5: update wiki/Conversation-Sessions.md "Built-in Vosk" section: first-time setup in 3 steps, which
   model to pick (size/RAM table), what Auto means offline, what to do when a model is missing or
   damaged, supported platforms (state that only Windows x64 was verified so far). Keep docs in the
   existing style.
6. Release check: build with ./gradlew assemblePortable, run the portable build (close the installed
   app first - single-instance lock on port 49185), download the smallest model, record a short
   session in Local only mode, and report exactly what you saw. If you cannot run the app in your
   environment, say so and list the manual steps for the user instead.
```

---

# مزودات التحويل السحابية والسيرفرات (المهام 8–10)

المصدر: [speech-providers-audit.md](speech-providers-audit.md). نفس المقدمة المشتركة فوق.

## المهمة 8: إصلاح OpenAI Realtime (P1، P2)

```text
Task: make the "OpenAI Realtime" live engine actually transcribe, at transcription cost, in the language
the user picked. Files: plugins/speech-services/.../OpenAiRealtimeService.kt, WebSocketLiveSession.kt,
SpeechSettings.kt (Live Model description), tests OpenAiRealtimeTest.kt, localization.

Step 0 - prove P1 with a test before fixing it. Write a test with a real local WebSocket server (JDK
or a small test dependency already allowed in the build; check gradle/libs.versions.toml first) that
records the first text frame the client sends after connecting. Expected today: no session.update
arrives, because onOpen calls enqueue() while `socket` is still null. Report the result. If the test
shows the config does arrive, say so and skip to step 2.

Steps (each committed):
1. P1: send the session configuration through the WebSocket passed to onOpen (or set `socket` there),
   so it can never be dropped. Apply the same fix to the shared base so Deepgram's keep-alive and any
   future engine cannot hit it. The test from step 0 now passes.
2. P2: check OpenAI's current Realtime documentation for transcription-only sessions (as of writing,
   a transcription intent/session type exists that takes a transcription model such as
   gpt-4o-transcribe, gpt-4o-mini-transcribe or whisper-1, plus a language). Use that instead of a
   conversation session on a realtime chat model, so no assistant responses are generated or billed.
   Use the GA interface if the beta header/preview model is deprecated.
3. "Live Model" now means the transcription model for OpenAI Realtime (default: the cheapest current
   model that streams partial text, per the docs) and stays the streaming model for Deepgram live.
   Update the field description and localization accordingly.
4. Pass the selected language (SpeechClient.primaryTag) to the session; Auto sends none.
5. Tests: config frame content (type, model, language, no response creation), partial deltas
   accumulate per item and reset on completion, error event before ready fails the open, error after
   ready ends the session with LiveSpeechEvent.Failed.

If you cannot confirm the current OpenAI interface from official docs, do not guess: hide the engine
from the Live engine dropdown behind a clear note, keep the P1 fix, and report what is unverified.
Done when: full suite passes; you state that no real OpenAI connection was made (unless one was, with
the user's own key provided for this purpose).
```

---

## المهمة 9: أنواع الموديلات والفحص الحقيقي (P3–P9، P13)

```text
Task: a user who enters an API key and a model must either get working transcription or a clear,
immediate reason why not - from the Test button, not from the first failed line. Files:
plugins/speech-services/.../SpeechProvider.kt, SpeechClient.kt, SpeechSettings.kt, SpeechPlugin.kt,
DeepgramLiveService.kt, tests SpeechClientTest.kt / DeepgramLiveTest.kt, localization; for the Test
button visibility the same host files as audit item B3 (MainAppFrame.kt openPluginConfiguration,
PluginsPanel.kt onConfigure) - if task 2 already fixed B3, build on it instead of redoing it.

Model kinds (see the table in docs/speech-providers-audit.md section 1): transcription, Deepgram,
realtime/streaming, audio-chat. Text-only chat models are valid nowhere.

Steps (each committed):
1. A per-provider list of known models of the right kind (check each provider's current docs; keep it
   small and in one place, for example on SpeechProvider). The Model field becomes an editable dropdown
   showing these suggestions while still accepting any name. If the settings framework has no editable
   dropdown, add one as an optional SettingType/flag with a default so other plugins are unaffected
   (api change rules in AGENTS.md).
2. P4: Test sends a 1-second silent clip (SpeechClient.silentWav) to the real endpoint with the chosen
   model and reports: works / key rejected / model unknown / model does not accept audio (for example a
   chat model on /audio/transcriptions) / server unreachable, each as a localized message. Keep the
   /models check as a fast first step and use its list to suggest the right models. For Audio chat
   model use a longer timeout and say it may take a while.
3. P6: Test is reachable in Hybrid and Local modes and also runs the live engine's validate()
   (Deepgram live ping, OpenAI Realtime ping), reporting live and accurate separately.
4. P3: remember per (base URL, model) that the verbose format was refused, so later lines send one
   request; retry in plain JSON only for a "format not supported"-type 400, not for unknown model,
   too-large file, rate limit or 5xx. Prefer driving this from a real capability (see R1 in the audit).
5. P5: the live engine falls back to the accurate API key only when both are the same vendor
   (OpenAI accurate + OpenAI Realtime, Deepgram + Deepgram live); otherwise it needs its own key and
   says so.
6. P9: Custom has no default model; Test and configurationError() ask for one (offer the /models list).
7. P7/P8: Deepgram live warns when Auto (multi) or the chosen language is not supported by the chosen
   model (per Deepgram's docs), uses the Server URL host when set, and surfaces the handshake HTTP
   status (401 key, 400 model/params) in its error.
8. P13: strip common wrappers from audio-chat replies ("Transcript:", surrounding quotes/code fences)
   and refuse obvious non-transcripts (an apology/explanation instead of speech) as a failed line.

Done when: unit tests cover each outcome with a fake HTTP client / fake WebSocket server; full suite
passes; you list which providers were tested only with fakes.
```

---

## المهمة 10: تنظيف الكود وأمان الإعدادات (R1–R7، P10–P12)

```text
Task: remove dead and duplicated code in the speech plugin and make settings fail safe. Do this after
tasks 8 and 9 so behaviour is settled first. Files: plugins/speech-services/... and its tests.
Run `graft callers <symbol> --depth all` (or ripgrep) on every symbol before deleting or renaming it.

Steps (each committed, no behaviour change unless stated):
1. R1: SpeechProvider capability flags - keep only the ones task 9 now reads, delete the rest.
2. R2-R4: delete the unused LiveEngine enum, the LIVE_VOSK alias, and SpeechSettings.liveEnabled
   (or make VoskLiveService.openSession use liveEnabled so the rule exists once - pick one and update
   SpeechSettingsMigrationTest accordingly).
3. R5: move the shared WebSocket listener (text buffering, onClose/onError mapping), connect and ping
   into WebSocketLiveSession; Deepgram and OpenAI sessions keep only their URL, headers, config frame
   and message parsing.
4. R6: rename the class VoskLiveService to a name that says it routes all live engines; keep
   key = "vosk-live" and say why in KDoc (saved active-service selections use it). Same check for
   any persisted ids.
5. R7: test that SpeechProvider.DROPDOWN_OPTIONS lists exactly the entries' labels in order (and the
   same for LIVE_ENGINE_OPTIONS and the Vosk model list if task 7 has not done it).
6. P10 (behaviour change): an unknown saved provider label maps to None (live text only), not Groq,
   and the settings dialog shows that the provider needs choosing - audio never goes to a cloud
   service the user did not pick.
7. P11: read Deepgram batch word confidence like the live parser does.
8. P12: treat 172.16.0.0/12 (and IPv6 loopback/ULA) as local in isLocal; add tests.

Done when: no unused declarations remain in the plugin (check with the compiler warnings or graft),
full suite passes, and the line count change is reported.
```
