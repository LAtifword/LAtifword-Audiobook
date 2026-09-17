LATIF VOICE STUDIO DESKTOP 3.3
================================

Windows x64 desktop companion to LATIF Voice Studio Android.

WHAT THIS BUILD DOES
--------------------
- Fully offline SILMA TTS v1 / F5-TTS ONNX narration.
- Uses DirectML on a compatible Windows GPU and falls back to ONNX Runtime CPU.
- No API key, cloud account, server, Python installation, or runtime model download.
- Opens PDF, EPUB, DOCX and TXT manuscripts, or pasted text.
- Default LATIF Author Narrator reference, 0.90x speed, Studio 32-step F5 quality.
- Optional 24/16/8-step modes for faster office previews and production.
- Optional custom PCM WAV reference voice with its exact transcript.
- One-section preview before committing to a full book.
- Streams generated PCM directly into AAC-LC M4A instead of retaining the whole book in RAM.
- Shows active backend, F5 refinement progress, RTF and estimated time remaining.
- Writes a matching .chapters.json sidecar based on detected chapter headings.

RUNNING
-------
1. Extract the complete portable ZIP to a normal folder. Do not run the EXE from inside the ZIP.
2. Open LATIF-Voice-Studio-3.3.exe.
3. Choose a book or paste text.
4. Keep Backend on "Auto · DirectML GPU → CPU" for the first run.
5. Use "Render one-section preview" first.
6. If the voice and speed are correct, choose "Generate full audiobook".

OUTPUT
------
Finished audio is written to:

  %USERPROFILE%\Music\LATIF Audiobooks\

Each completed audiobook produces:
- <title>.m4a
- <title>.chapters.json

GPU NOTES
---------
DirectML works through DirectX 12 and supports a broad range of modern NVIDIA, AMD and Intel GPUs. The app uses GPU adapter ID 0 by default. On a multi-GPU office laptop, adapter 0 may be the integrated GPU; the adapter ID control lets you try another installed adapter without changing the application.

The DirectML provider requires sequential ONNX execution and memory-pattern optimization disabled. LATIF Voice Studio configures those settings automatically. If DirectML cannot initialize all SILMA sessions, Auto mode falls back to CPU and reports the active backend in the UI.

QUALITY MODES
-------------
Studio:   32 F5 steps — default, maximum exported trajectory
High:     24 F5 steps
Balanced: 16 F5 steps
Fast:      8 F5 steps — recommended for quick preview only

The lower modes intentionally decode an earlier F5 refinement state. They are speed/quality choices, not different narrator models.

CUSTOM VOICE
------------
The optional custom reference must be a PCM WAV file and should be at least 2 seconds long. Enter the exact words spoken in that WAV. The app trims references longer than 15 seconds. Use only recordings you have permission to use.

PORTABLE BUILD
--------------
This release is packaged with PyInstaller as a Windows one-folder application. Keep the folder together because the EXE depends on the bundled runtime, DirectML/ONNX Runtime libraries, FFmpeg/PyAV libraries and the embedded SILMA model directory.

Windows Authenticode signing is separate from the Android JKS certificate. This portable desktop build is not Authenticode-signed unless a Windows code-signing certificate is added to the release pipeline later.
