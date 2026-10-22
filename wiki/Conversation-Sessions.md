# Conversation Sessions

Conversation Sessions records a voice call — a Discord call, for example — shows a live transcript,
and translates any word you point at. Open it from the main menu or the tray: **Conversation
Sessions...**

- Your microphone and the call's audio are recorded as **two separate streams**. Each line in the
  transcript says where it came from: **Me** (your microphone) or **Call** (everything the call
  played).
- **Hover** a word to see its translation, and what the whole sentence means.
- **Select** a phrase to translate the phrase.
- Click **Meanings** in the card for dictionary entries, or **Play** to hear that word again.
- Click a speaker label (`CALL · 1:05`) to replay that line.
- Sessions are saved automatically. Search them, rename, export the transcript as text, or delete
  them from the list on the left.

## Setup

### 1. A speech recognition provider

Transcription uses the bundled **Speech Recognition** plugin. Open it from the conversation window's
**Speech settings...** button, or from **Settings > Plugins > Speech Recognition > Configure**, and
pick a provider under **Accurate text**:

| Provider | What you enter | Notes |
|---|---|---|
| **None (live text only)** | Nothing | Only with live text (below): the live words are the transcript, and nothing leaves your network. |
| **Groq** | API key from [console.groq.com](https://console.groq.com/) | Fast, inexpensive, has a free tier. Default model `whisper-large-v3-turbo`. |
| **OpenAI** | API key | Default model `whisper-1`. `gpt-4o-transcribe` also works, without word timings. |
| **Deepgram** | API key from [console.deepgram.com](https://console.deepgram.com/) | Default model `nova-3`. |
| **Local Whisper server** | Nothing, or the server's URL and model | Nothing leaves your computer. Default `http://localhost:8000/v1` (Speaches, faster-whisper-server, LocalAI). |
| **Custom (OpenAI-compatible)** | Server URL, model, and a key if it needs one | Any server with an OpenAI-style `/audio/transcriptions` endpoint, local or cloud. |
| **Audio chat model (OpenAI-compatible)** | Server URL, model, and a key if it needs one | A multimodal chat model (Gemini, gpt-4o-audio) behind an OpenAI-compatible gateway such as LiteLLM. The audio goes to `/chat/completions`. No word timings. |

Leave **Server URL** and **Model** blank to use the provider's defaults. Press **Test Connection** to
check what is on screen (it applies the values first). The window shows which provider is in use next to **Speech settings...**.

Set **My language** and **Their language** in the window (or leave them on Auto-Detect). Naming the
language noticeably improves accuracy on short lines, and you and the people on the call often speak
different ones.

Word-level replay needs word timings, which Groq, `whisper-1`, Deepgram and most local Whisper servers
report. Without them everything else still works; only **Play** on a single word is missing.

### Live text (optional): Vosk

With an accurate provider alone, a line appears only after its speaker pauses and the provider
answers, which with a slow model can take 20 seconds. **Live text** shows words while people are still
talking, from a [Vosk](https://alphacephei.com/vosk/) server on your own computer or network.
Nothing goes to the internet: QTranslate talks to the server over a local WebSocket API. Measured on a
home network, words appear about one second after they are spoken.

**1. Run a Vosk server.** QTranslate speaks the protocol of the Vosk STT extension's
`vosk_server.py` (`configure`, raw PCM, `partial`/`result`, `stop`). Models go in a folder laid out
as `models/<lang>/<model-folder>/`, for example `models/en/vosk-model-en-us-0.22-lgraph` and
`models/ar/vosk-model-ar-0.22-linto-1.1.0` (download from the
[Vosk model list](https://alphacephei.com/vosk/models)).

- *Without Docker* (Windows, macOS or Linux, Python 3.11 or newer):

  ```
  pip install vosk websockets
  python vosk_server.py --models ./models --preload en --session-timeout 0
  ```

  The server listens on `ws://localhost:8765`.
- *With Docker* (for example on another machine on your network):

  ```
  docker build -t vosk-stt-server .
  docker run -d --name vosk-stt --restart unless-stopped -p 8765:8765 -v vosk-models:/models vosk-stt-server --preload ar,en
  ```

`--session-timeout 0` matters for calls: the default ends a session after 10 minutes.
`--preload` loads models at startup, so the first sentence is not delayed.

**2. Point QTranslate at it.** In **Settings > Plugins > Speech Recognition**, under **Live text**,
set **Live engine** to **Vosk (local server)**, **Vosk server URL** to the server (e.g.
`ws://localhost:8765` or `ws://192.168.1.20:8765`), and **Language for Auto-Detect** to the model to
use when a language is left on Auto-Detect (Vosk cannot detect languages). **Test connection** checks
that the server answers.

**Hybrid mode.** Under **Accurate text**, keep a provider to have each finished line replaced by its
more accurate text; live words show at once, in italics while provisional. Choose **None (live text
only)** to keep just the live text, with nothing sent outside your network.

If the server drops out, recording carries on: lines are cut at pauses the usual way while
QTranslate reconnects, and live text returns when the server is back.

### 2. The call's audio

On Windows, the **Call audio** picker lists your output devices, and QTranslate records what each one
plays through WASAPI loopback. No driver or extra software is needed.

- Pick the output Discord plays to. Recording your usual headphones captures everything they
  play: the call, but also games, music and videos.
- For the call alone, give Discord an output of its own. **SteelSeries Sonar** already has one
  (**Sonar - Chat**), and QTranslate preselects it when it finds it. Otherwise, set Discord's
  **Output Device** (User Settings > Voice & Video) to a separate device, or to a virtual cable such
  as VB-Cable, and pick that here.

On macOS and Linux, loopback is not available. Route the call through a virtual input device
(BlackHole on macOS, a PulseAudio monitor source on Linux) and pick it under **Call audio**.

### 3. Headphones

Use headphones. With speakers, your microphone also picks up the call, and every line from the
call is transcribed twice.

## Privacy

- Recordings and transcripts are stored only on this computer, in the `conversations` folder of
  QTranslate's data directory: one folder per session, holding `session.json` (the transcript)
  and `microphone.wav` / `system.wav` (the recordings).
- To transcribe, each spoken clip is sent to the speech provider you configured. With a local
  Whisper server, nothing leaves your computer.
- Hover translations go to your active translator, and meanings to your active dictionary, as
  everywhere else in QTranslate.
- Untick **Keep recordings after the session** to keep only the transcript: recordings are
  deleted when the session ends. Replay and retry are then unavailable for that session.
- Tell the people on the call that you are recording. Recording a conversation without
  everyone's consent is illegal in many places.

## What to expect

- **Delay.** Without a live recognizer, a line appears once its speaker pauses and the provider
  answers: a second or two with Groq, 20 seconds or more with a multimodal chat model. While a clip is
  being transcribed, its line shows *Transcribing...*. With Vosk, words appear as they are spoken.
- **Source, not speaker.** "Call" is everyone on the call mixed together; QTranslate does not tell
  your friends apart.
- **Failures don't lose audio.** If the speech provider is down, out of credit, or not set up, the
  recording carries on and the affected lines say *Could not transcribe this part*. Click one to
  retry it, or press **Retry failed** to retry them all once the provider works.
- **Devices.** If a device is unplugged mid-session, only its stream stops; its light turns red,
  and the other stream keeps recording.
- **Crashes.** If QTranslate closes unexpectedly while recording, the session is marked
  *interrupted* the next time it starts, and the audio up to that moment is kept and playable.
- **Pausing** stops both streams together. Nothing said while paused is recorded or sent anywhere.
- **Disk use.** About 2 MB per minute for each stream.
