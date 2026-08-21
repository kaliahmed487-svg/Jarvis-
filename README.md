# Jarvis — Practical Edition (Android / Kotlin)

Same pitch-black, single-glowing-ring, voice-only Jarvis as the main build,
but every dependency is a **prebuilt Maven artifact** — nothing to compile
with the NDK, no C++ to write or build yourself. This is the version to
start with if you just want it running.

## What changed vs. the full build

| Piece | Full build | Practical build |
|---|---|---|
| LLM brain | Hand-built llama.cpp JNI (`LlamaBridge.kt`) — any GGUF, but you compile the `.so` yourself | `MediaPipeLlmEngine.kt` using Google's official `com.google.mediapipe:tasks-genai` — prebuilt, just supply a `.task` model |
| STT / wake word | Vosk (prebuilt AAR) | same — Vosk (prebuilt AAR) |
| TTS | Android native `TextToSpeech`, optional Piper via sherpa-onnx | same — native by default, optional Piper via sherpa-onnx (prebuilt AAR) |
| Native build required? | Yes, one component | No |

Everything else — the visualizer, the service state machine, file tools,
command routing, permissions flow — is identical to the full build.

## One-time setup (do this before building)

Bundle these three files under `app/src/main/assets/`:

1. **`vosk-model-small-en-us.zip`** — the Vosk small English model, zipped
   exactly as downloaded from https://alphacephei.com/vosk/models
   (`vosk-model-small-en-us-0.15.zip`, ~40MB). `ModelProvisioner` unzips it
   into app-private storage automatically on first launch.

2. **`gemma3-1b-it-int4.task`** — a Gemma 3 1B IT model pre-converted to
   MediaPipe's `.task` format. Google publishes ready-to-use `.task` bundles
   on Kaggle Models / Hugging Face under "Gemma 3 MediaPipe" — download the
   int4 variant (~550MB) and drop it in as-is, no conversion step needed.
   `ModelProvisioner` copies it into app-private storage on first launch.

   (Other MediaPipe-supported families work too — Phi-2, Falcon-RW-1B,
   StableLM 3B — just update `ModelProvisioner.LLM_MODEL_FILENAME`.)

3. *(Optional, for the real-amplitude Piper voice)* a Piper ONNX voice —
   `voice.onnx` + `tokens.txt` + `espeak-ng-data/` — under
   `app/src/main/assets/piper-voice/`. If omitted, the app automatically
   falls back to Android's native TTS engine; nothing else needs to change.

No `adb push`, no manual file copying at runtime — `ModelProvisioner`
(`core/ModelProvisioner.kt`) handles extraction on first launch and shows
progress in the persistent notification ("Unpacking speech recognition
model…", "Copying language model…").

## Build & run

```
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Grant microphone + notifications on first launch, then follow the prompt to
Settings for "All files access" (needed for the file-management voice
commands). The ring will show "Setting up…" in the notification while
`ModelProvisioner` unpacks assets, then switch to listening for "Jarvis".

## Trade-offs of this edition

- **Model choice is narrower.** MediaPipe's LLM Inference API only supports
  a specific model family list (Gemma, Phi-2, Falcon-RW-1B, StableLM),
  vs. "any GGUF llama.cpp can load" in the full build.
- **Larger dependency footprint** (`tasks-genai` pulls in more than a
  stripped llama.cpp `.so`), but you trade that for zero native build steps.
- Everything else (visualizer, service architecture, file tools, command
  router, persona/system prompt) is unchanged — swapping back to the full
  llama.cpp build later only touches `MediaPipeLlmEngine.kt` /
  `LlamaBridge.kt` and the two lines in `JarvisService.kt` that call it.
