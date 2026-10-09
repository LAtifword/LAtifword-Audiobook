# Validation — 21 September 2026

## Passed in the Linux development environment

- Seven backend unit/integration tests: session/CSRF/Host checks, reference length
  validation, full manuscript preservation, pause/resume, missing-model status,
  text import, checkpoint reuse and actual FFmpeg MP3 export.
- Python compilation, JavaScript syntax check, Bash syntax checks.
- Downloaded and resolved all dependencies as Python 3.12 ARM64 manylinux wheels.
  This verifies wheel availability, not execution on Android.
- Downloaded the six real SILMA assets; verified exact lengths and recorded then
  pinned SHA-256 checksums. Revalidation against those hashes passed.
- Real ONNX CPU synthesis using two worker threads completed the full trajectory:
  87,552 PCM samples / 3.648 seconds audio; 107.82 seconds including model loading;
  peak process RSS 1,835,324 KiB (about 1.75 GiB).
- Real application integration through the ASGI test client: reference upload →
  queued full-text generation → real SILMA inference → FFmpeg → successful MP3
  download. Completed in 114.52 seconds, producing 59,593 bytes of MP3; peak process
  RSS 1,915,176 KiB (about 1.83 GiB). No synthesis mock used in this test.

## Not established

- Motorola G85 / Android 16 / Termux / PRoot runtime performance, thermal behavior,
  long-book stability and memory peak. This environment is not the user's phone.
- Audible voice similarity, pronunciation accuracy and premium-quality narration:
  generation success and nonzero audio energy do not prove these.
- Browser-rendered visual QA: the remote browser refused the local URL with
  `net::ERR_BLOCKED_BY_CLIENT`. The JavaScript parsed, but layout/interactions
  have not been visually verified.
- A native standalone Android APK. This deliverable is a Termux-supported local
  web application, installed once with internet, then usable without cloud APIs.
- Complete books have not been rendered by this validation run. It used a short
  Arabic sentence; long-book completion is not claimed.

The measured Linux speed is not a phone benchmark and is substantially slower
than realtime. Larger text chunks and references may require more memory.
