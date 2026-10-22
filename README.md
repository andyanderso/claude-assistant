# Copilot — talk to Claude like you talk to Gemini

A hands-free voice assistant for Claude. You talk, Claude answers out loud, and you keep the
conversation going without touching the phone. Answers are grounded in reputable sources,
start with a spoken confidence cue ("High confidence — …"), and show their sources on screen.

There are two versions in this repo:

| | Web app (`/`) | Android app (`/android`) |
|---|---|---|
| Install | Open in Chrome → **Install app** | Build in Android Studio, sideload |
| Start it | Tap the icon, or "Hey Google, open Copilot" | Say **"Hey Claude"** |
| Listens in the background | No | Optional (setting) |
| Works with the screen off | No | Yes, in "Always" mode |

Both use your own Anthropic API key (a Claude Pro/Max subscription doesn't cover API use).
Create a key at console.anthropic.com and set a monthly spend limit on it.

---

## 1. Web app (GitHub Pages)

1. Repo **Settings → Pages → Build and deployment → Deploy from a branch → `main` / `(root)`**.
2. After a minute or two it's live at `https://andyanderso.github.io/claude-assistant/`.
3. On your phone, open that link in **Chrome → ⋮ → Install app**, tap ⚙, paste your API key, allow the mic.
4. Say **"Hey Google, open Copilot"**. It opens already listening ("Listen when opened" setting).

Notes:
- Free GitHub Pages needs a public repo. That's fine: the key is typed into the app and kept in
  your phone's browser storage. It is never in the code. **Never commit a key.**
- Every project page under `andyanderso.github.io` shares one browser storage area, which is one more reason to cap the key's spending.
- `claude-copilot.html` now just forwards to the new page; your saved settings carry over.

---

## 2. Android app

### What it does

- **"Hey Claude"** wakes it, using an on-device detector ([openWakeWord](https://github.com/dscripka/openWakeWord)).
  Audio is never sent anywhere until it hears the wake phrase.
- **"Hey Google" keeps working.** Google stays your default assistant; Copilot runs its own separate listener.
- A chime plays, it listens, Claude answers out loud, then it listens for a follow-up.
  Stay silent or say **"that's all"** to end; it goes back to waiting for "Hey Claude".
- Music and navigation audio pause while you talk.
- **Listening mode** (Settings, or tap the chip on the main screen):
  - **Only while the app is open** (default). It listens while Copilot is on screen and stops the
    moment you leave. The screen stays awake while it's open, which suits a phone on the dash.
  - **Always, in the background.** It keeps listening with the screen off or other apps open, shows
    a notification, and runs until you tap **Stop**. This costs battery. After a reboot, open Copilot
    once: Android 14+ doesn't let apps start the microphone on their own at boot.

### Step 1 — Train the "Hey Claude" model (≈1 hour, mostly waiting)

The app needs a small model file, `hey_claude.onnx`, trained for your phrase. Until you add it,
everything still works by tapping the orb; only the voice wake-up is missing.

1. Open the openWakeWord training notebook in Google Colab. The link is in the openWakeWord README,
   under training custom models: https://github.com/dscripka/openWakeWord
2. Set the target phrase to `hey claude`. Use the two-word phrase: "Claude" alone would fire on
   "cloud", "clawed", or anyone named Claude.
3. Run all cells. It generates synthetic voices saying the phrase and trains a model.
4. Download the resulting **`.onnx`** file, rename it to `hey_claude.onnx`, and put it in
   `android/app/src/main/assets/`.
5. Rebuild the app (Step 2).

If it wakes too often or too rarely, adjust **Wake sensitivity** in Settings. 0.5 is
openWakeWord's default. If false wakes stay a problem, openWakeWord supports a second-stage
"verifier" model trained on your own voice.

### Step 2 — Build and install

On your computer, with Android Studio installed:

1. **File → Open → `claude-assistant/android`**. Let Gradle sync. If Studio offers to upgrade the
   Android Gradle Plugin, accept.
2. On the phone: **Settings → About phone → tap Build number 7×**, then
   **Developer options → USB debugging** on. Plug it in.
3. Press **Run ▶**. On first launch, allow the microphone and notifications, tap ⚙, and add your API key.

From a terminal instead:

```bash
cd claude-assistant/android
./gradlew assembleDebug                                 # builds app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk  # -r replaces an existing install
```

### Step 3 — Test before trusting it on the road

These depend on your specific phone, so check them parked:

- [ ] "Hey Claude" wakes it with music playing and with road noise.
- [ ] "Hey Google, navigate to …" still works while Copilot is listening. The two listeners share the mic.
- [ ] In **Always** mode, "Hey Claude" works with the screen off and the conversation completes.
      This relies on Google's speech recognizer accepting requests from a background service.
- [ ] Audio comes through the car speakers over Bluetooth / Android Auto.
- [ ] It doesn't wake from radio or podcast speech over a normal drive.

Say "Hey Claude", wait for the chime, then talk. Speech in the first instant after the wake word
can get clipped.

### How it's put together

| File | Job |
|---|---|
| `AssistantService.kt` | Foreground service: wake word → listen → Claude → speak → follow-up loop; mic hand-off; audio focus |
| `ClaudeClient.kt` | Messages API call with web search, citation extraction, per-question model routing, confidence cue parsing |
| `SpeechIO.kt` | Android speech recognizer + text-to-speech, with one-callback-per-operation handling |
| `MainActivity.kt` | The screen. Starts the service when visible; stops it on exit in "while open" mode |
| `SettingsActivity.kt` | Key, model, grounding, listening mode, wake sensitivity, system prompt |
| `wakeword/` | Vendored openWakeWord Android library (Apache-2.0); see `wakeword/VENDORED.md` |

The API key lives in Copilot's private app storage, and backups are turned off so it doesn't get
copied to the cloud.

---

## Limits

- **Not on the Android Auto screen, and not on the steering-wheel button.** Google restricts
  Android Auto to approved app categories and keeps the assistant button for its own assistant.
- **The wake word costs battery.** Google's "Hey Google" runs on dedicated low-power hardware
  that third-party apps can't use, so Copilot's listener runs on the main processor.
- **Model routing is a keyword heuristic.** Pick a fixed model in Settings if you prefer.

## Licenses

This project: GPL-3.0 (see `LICENSE`). Vendored wake-word library and the two shared feature
models: Apache-2.0 (see `android/wakeword/LICENSE` and `NOTICE`). openWakeWord's own pre-trained
wake-word models are CC BY-NC-SA 4.0 and are not included; you train your own "Hey Claude".
