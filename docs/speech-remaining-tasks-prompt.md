# برومبت إنجاز باقي مهام الكلام والموديلات المحلية

انسخ البلوك كله وأرسله للذكاء الاصطناعي. يشتغل على المهام بالترتيب، وكل خطوة تنتهي بـ commit.
إذا الجلسة طولت أو خلصت التوكنات، أرسل نفس البرومبت بجلسة جديدة: يكمّل من آخر مهمة مخلّصة.

```text
You are working in the QTranslate repository (Kotlin/Swing desktop translator; Gradle modules api, core,
ui-swing, app, plugins/*). Your job: finish the speech / local-model fixes described in
docs/local-models-fix-prompts.md, one task at a time, until all are done.

## Read first (in this order)
1. AGENTS.md and CLAUDE.md in the repo root. They are the project's rules and override your defaults.
2. docs/local-models-fix-prompts.md: the shared preamble at the top applies to every task, and each
   "المهمة N" section holds the English prompt for task N. Follow that task prompt as your spec.
3. The audits the tasks come from: docs/local-models-audit.md (tasks 1-7, items A/B/C/D/E) and
   docs/speech-providers-audit.md (tasks 8-10, items P/R). Treat findings as leads: verify each in
   the code before changing it, and say so when one is wrong.

## Already done on branch fix/speech-quick-wins (do not redo; build on it)
- af923a27 P1: OpenAI Realtime listeners set `socket` in onOpen, so session.update is sent. A local
  WebSocket test in OpenAiRealtimeTest proves it (it fails without the fix). Reuse that test server for
  task 8's config-frame tests. Task 8 step 0 and step 1 are therefore done.
- 55efa7a0 A1 (part): VoskModelManager.isValidModelDir now requires am/final.mdl. Task 1 still needs
  the atomic install (temp folder + rename), real resume length / HTTP 416 handling, model-root
  detection by content, one download per model, the "incomplete, delete and download again" status,
  and confirming the full required file layout against the Vosk docs.
- fc9ad272 P12: 172.16.0.0/12 counts as local. Task 10 step 8 still needs IPv6 loopback/ULA.

## Order
Work on branch fix/speech-quick-wins (create `fix/local-models-<n>` from it only if the user asks for
separate branches). Do the tasks in this order, skipping any whose items are already marked fixed
in the audit files:

  8 (rest: P2 transcription-only session, Live Model meaning, language, tests)
  1 (rest, see above)
  2 -> 9 -> 3 -> 4 -> 5 -> 6 -> 7 -> 10

Before starting a task, check `git log --oneline` and the audit files for what is already done, so a
restarted session resumes instead of repeating work.

## How to work
- Find code with graft first (`graft ask "<q>" --source`, `graft grep`, `graft skeleton <file>`,
  `graft callers <sym> --depth 2` before changing a signature). If graft is missing, use ripgrep and
  the cards under graft/. Read only the line ranges you need; never whole large files.
- Keep a visible task list. Every step ends buildable, tested and committed; a stop at any moment must
  never leave a broken tree. Commit messages name the audit item IDs (for example "(A3)").
- Build/test: a Java 11 toolchain is provisioned by foojay; run `./gradlew` from the repo root. Iterate
  with `--tests '*ClassName*'`, run the full `./gradlew test` once before each commit, and filter output
  (`grep -E "^e:|FAILED|BUILD"`). The suite is currently 1603 tests, 6 skipped (opt-in), 0 failures.
- For any external provider API (OpenAI Realtime, Deepgram, Groq, model names, event formats, Vosk model
  layout and download URLs), check the provider's current official docs before coding, and put the doc
  URL and date in the commit message. If you cannot confirm something, do not guess: follow the
  fallback written in the task prompt and report it as unverified.
- Localize every user-facing string (app strings per AGENTS.md; speech plugin strings in
  plugins/speech-services/src/main/resources/localization/en.toml and ar.toml).
- Swing tests run on the EDT. Plugin API changes keep existing plugins compiling (defaults, optional
  interfaces, bump ApiVersion).
- Do not touch or commit: `.gitignore` local changes, docs/conversation-ui-fix-prompt.md,
  PowerShellHistory.txt, graft's .ignore lines. Do not push, open PRs or merge.

## After each task
1. Mark the fixed items in the matching audit file with the commit hash.
2. Write a short report: commits, tests added, what was verified by running it, what only by tests,
   and what is still unverified (real downloads from alphacephei.com, real cloud API calls, behaviour
   in the running app).
3. Continue with the next task without waiting, unless a task prompt says a decision is the user's
   (then stop and ask with the options and your recommendation).

## When everything is done
Run the full suite once more and report totals. List any items you skipped or could not verify. Give
the user a short manual test plan for the running app: download model A, switch to model B and see
Download; interrupt a download and confirm the folder does not count as installed; a Conversation
Session with Local only, Hybrid and Cloud; OpenAI Realtime and Deepgram live with real keys.
```
