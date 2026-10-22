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

### 1. A speech recognition service

Transcription uses the **AI Speech Recognition** service of the bundled AI Services plugin. It needs
an endpoint that transcribes audio; OpenRouter, the plugin's default for chat, does not.

Open **Settings > Plugins > AI Services > Configure > Speech Recognition** and choose one:

| Provider | Speech Base URL | Speech Model | Notes |
|---|---|---|---|
| [Groq](https://console.groq.com/) | `https://api.groq.com/openai/v1` | `whisper-large-v3-turbo` | Fast, inexpensive, has a free tier. |
| OpenAI | `https://api.openai.com/v1` | `whisper-1` | `gpt-4o-transcribe` also works, but without word timings. |
| Local Whisper server | e.g. `http://localhost:8000/v1` | the server's model name | Nothing leaves your computer. Any OpenAI-compatible server works (faster-whisper-server, Speaches, LocalAI). |

Paste the provider's key into **Speech API Key** (leave it blank for a local server), then select
**AI Speech Recognition** under **Settings > Services & Presets**.

Word-level replay needs a service that reports word timings (Groq, `whisper-1` and most local
Whisper servers do). Without them, everything else still works; only **Play** on a single word is
missing.

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
- To transcribe, each spoken clip is sent to the speech service you configured. With a local
  Whisper server, nothing leaves your computer.
- Hover translations go to your active translator, and meanings to your active dictionary, as
  everywhere else in QTranslate.
- Untick **Keep recordings after the session** to keep only the transcript: recordings are
  deleted when the session ends. Replay and retry are then unavailable for that session.
- Tell the people on the call that you are recording. Recording a conversation without
  everyone's consent is illegal in many places.

## What to expect

- **Delay.** A line appears once its speaker pauses, usually one to two seconds later. While a
  clip is being transcribed, its line shows *Transcribing...*.
- **Source, not speaker.** "Call" is everyone on the call mixed together; QTranslate does not tell
  your friends apart.
- **Failures don't lose audio.** If the speech service is down, out of credit, or not set up, the
  recording carries on and the affected lines say *Could not transcribe this part*. Click one to
  retry once the service works again.
- **Devices.** If a device is unplugged mid-session, only its stream stops; its light turns red,
  and the other stream keeps recording.
- **Crashes.** If QTranslate closes unexpectedly while recording, the session is marked
  *interrupted* the next time it starts, and the audio up to that moment is kept and playable.
- **Pausing** stops both streams together. Nothing said while paused is recorded or sent anywhere.
- **Disk use.** About 2 MB per minute for each stream.
