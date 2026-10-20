# ScreenLens (Android, phase 1)

Use one phone as a remote camera and another as the controller. The camera phone
photographs a screen, book page or notes. The controller phone asks whether you want a
**Solution** or an **Explanation**, sends the photo to Claude and shows the answer.
You can then ask follow-up questions about the same photo.

Test setup: **Galaxy S24 Ultra = Controller**, **Galaxy S20 Ultra = Camera**.

```
 S20 Ultra (Camera)                          S24 Ultra (Controller)
 CameraX + HTTP server  <── same Wi-Fi ──>   live view, torch/zoom/focus, Capture
 advertises itself (mDNS)                    "Solution" / "Explanation" prompt
                                             └──> Claude API (claude-opus-5-5) ──> answer
```

## Install

1. Every push that changes `screenlens/` runs the **ScreenLens Android build** workflow.
   Open the run under GitHub → Actions and download the `screenlens-debug-apk` artifact.
   You can also open `screenlens/` in Android Studio and click Run.
2. Copy `app-debug.apk` to both phones and open it. Android asks you to allow
   "Install unknown apps" for your file manager or browser.

## Use

1. Connect both phones to the **same Wi-Fi**. Some guest or office networks block
   phone-to-phone traffic. If that happens, turn on the S24's hotspot and join it from the S20.
2. **S20:** open ScreenLens → **Camera**. Allow camera access. The screen shows the
   address and a 6-digit PIN. Put the phone on a stand facing the screen.
3. **S24:** open ScreenLens → **Controller**.
   - Paste your Claude API key under *Settings* (from console.anthropic.com). You only do this once.
   - Tap the S20 in *Cameras found* (or type its address), enter the PIN, tap **Connect**.
   - Frame the screen using the live view. Use zoom, focus and torch as needed. Avoid
     the torch on glossy screens because it adds glare.
   - Tap **Capture the screen**. Choose **Solution** or **Explanation** and optionally type what
     you want (for example "explain like I'm a beginner" or "answer in Portuguese").
   - Read the answer, ask follow-ups, or tap **New photo**. Each answer shows what it cost.

## Cost

Pick a model under *Settings* or on the "What should I show you?" card:

| Option | Model | About per photo |
|---|---|---|
| Economy (default) | Claude Haiku 5.5 | 0.2¢ |
| Balanced | Claude Sonnet 5.5 | 2–3¢ |
| Best | Claude Opus 5.5 | 6–10¢ |

Other savings built in: photos are sent at 1568 px on the long edge, answers are kept
short, and the photo is cached so follow-up questions re-read it at a tenth of the price.

## How it works

| Piece | File |
|---|---|
| Role picker | `app/src/main/java/com/pedropetrov/screenlens/MainActivity.kt` |
| Camera (CameraX preview, capture, torch, zoom) | `camera/CameraEngine.kt` |
| Camera phone's HTTP API (PIN-protected) | `camera/CameraHttpServer.kt` |
| Auto-discovery on Wi-Fi (NSD/mDNS) | `net/Discovery.kt` |
| Controller UI and state | `controller/ControllerScreen.kt`, `controller/ControllerViewModel.kt` |
| Claude call (image + mode, streamed answer, follow-ups) | `ai/ClaudeTutor.kt` |

## Phase 1 limits (to address before real users)

- The Claude API key is stored on the controller phone. For production, put a small
  backend between the app and Claude so the key never ships in the app, and add accounts
  and usage limits.
- Phone-to-phone traffic is plain HTTP on the local network, protected only by the PIN.
- The camera phone must stay in the foreground with ScreenLens open.
