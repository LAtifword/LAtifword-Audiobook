# LATIF Voice Studio 3.3 Desktop

A fully local Windows x64 companion to LATIF Voice Studio Android.

## What 3.3 does

- Runs the same SILMA TTS v1 / F5-TTS ONNX pipeline used by the Android app.
- Bundles the same Arabic Author Narrator reference voice and transcript.
- Uses fixed Literary pacing at **0.90x** by default.
- Offers 8 / 12 / 16 / 24 / 32 F5 refinement presets; 32 is the Studio setting matching the 3.2.1 quality target.
- Tries **DirectML GPU** first on Windows, then falls back to tuned ONNX Runtime CPU.
- Accepts PDF, DOCX, EPUB, and TXT.
- Supports a one-section preview before committing to a whole book.
- Writes 24 kHz mono AAC-LC M4A at 128 kbps.
- Writes a `.chapters.json` sidecar with real generated timing.
- Caches completed sections on disk, so interrupted jobs can resume instead of starting over.
- Shows live backend, section/refinement progress, ETA, and measured RTF.
- Can optionally use a WAV reference voice with its exact transcript.

## Privacy

Narration is local. The desktop application does not require an API key, account, server, or cloud inference.

## Windows package

The GitHub workflow builds a portable folder and ZIP. Unzip it and run:

`LATIF Voice Studio 3.3 Desktop.exe`

The SILMA model is bundled in the package; there is no post-install model download.

### Windows SmartScreen

The first portable build is not Authenticode-signed because this repository does not contain a Windows code-signing certificate. Windows may show a SmartScreen warning for a new unsigned executable. This is separate from the Android signing identity.

## Backend strategy

The packaged Windows build uses `onnxruntime-directml`. DirectML works on DirectX 12-capable NVIDIA, AMD, and Intel GPUs and allows CPU fallback for unsupported graph partitions. If the DirectML sessions cannot initialize, the app retries all three SILMA sessions on the CPU.

The application intentionally initializes preprocess, transformer, and decoder with one session configuration before declaring the backend ready.

## Source layout

- `latif_voice_studio/engine.py` — SILMA ONNX runtime
- `latif_voice_studio/book_parser.py` — PDF/DOCX/EPUB/TXT parsing + semantic chunking
- `latif_voice_studio/renderer.py` — cache/resume, F5 loop, M4A, sidecar, ETA/RTF
- `latif_voice_studio/main.py` — PySide6 desktop UI
- `LATIFVoiceStudioDesktop.spec` — PyInstaller portable build
- `tests/` — core regression tests

## Local developer run

Requires Python 3.12 x64.

```powershell
cd desktop-v3.3
py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements-win.txt
```

Place these exact assets under `assets/silma-f5/`:

- F5_Preprocess.onnx
- model.onnx
- F5_Decode.onnx
- config.json
- default_ref.wav
- vocab.txt

Then:

```powershell
python run_desktop.py
```

## Important performance note

Desktop hardware should have much more thermal and compute headroom than the phone, but the 32-step F5 trajectory is still sequential: each refinement step depends on the previous one. GPU acceleration reduces the cost of each step; it does not turn the 31 transformer iterations into 31 independent parallel jobs.

Use **Test first section** to measure the machine before starting a full audiobook.
