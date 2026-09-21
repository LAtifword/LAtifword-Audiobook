# LATIF Offline Voice Studio — Termux / Ubuntu edition

An independent phone-local voice studio, not ElevenLabs and not an ElevenLabs model.
This is a runnable local web application backed by ONNX inference, **not an APK**.
The browser is its interface; Ubuntu in Termux is its runtime. No Windows PC or
hosted website is involved. Initial installation needs internet; normal narration
does not use internet, external inference APIs, subscriptions, or API keys.

## Implemented

- Mobile-first UI, including right-to-left manuscript and reference text.
- Named voice references with explicit permission confirmation and exact transcript.
- 2–15 second 16-bit PCM WAV reference, with rejection rather than hidden truncation.
- TXT, DOCX, text-based PDF import and editable manuscript review.
- First-section preview clearly separated from full-book jobs.
- One CPU inference job at a time, 160-character chunks, full 32-step trajectory.
- Persistent queue metadata, per-section WAV checkpoints, pause/resume, recovery
  after a process restart. A partly synthesized section is rerun, not lost text.
- One final MP3 per book, assembled using FFmpeg only after all sections complete.
- No automatic fallback voice, inserted silent gaps, fades, or background music.
- Loopback-only listener, same-origin UI, session cookie, CSRF and Host checks.

## Install on the phone

Requires ARM64 Termux and a recent proot-distro with OCI image support. These commands
create a separate `latif-voice` Ubuntu 24.04 environment; they do not reset your existing Ubuntu.
Run in Termux, not inside an existing Ubuntu shell:

```bash
pkg install -y git
git clone --single-branch --branch codex/termux-offline-studio https://github.com/LAtifword/LAtifword-Audiobook.git LATIF-OFFLINE-STUDIO
cd LATIF-OFFLINE-STUDIO
bash termux-studio/install-termux.sh
bash termux-studio/start-termux.sh
```

Open **http://127.0.0.1:8765** on the same phone. Keep the Termux session running.
For later launches, only the last command is needed, from the same project folder.
The startup script requests a Termux wake lock and releases it on exit. Android's
battery restrictions may still interrupt long jobs; exempt Termux in phone settings.
If another engine uses port 8765, stop that engine before starting this one.

The model files total approximately 749 MB. Ubuntu, Python libraries, temporary
installation files, WAV checkpoints and MP3 output require additional space.
At 24 kHz mono PCM16, checkpoint audio alone uses about 173 MB per generated hour.
A 3 GB application budget does not imply a 3 GB total working-space requirement.

## Existing Ubuntu / Linux

Run `bash termux-studio/install-ubuntu.sh` as root inside Ubuntu 24.04, followed by
`bash termux-studio/start-ubuntu.sh`. Python 3.12 is the tested dependency target.
Do not install the Windows DirectML dependencies. This port uses Linux ONNX Runtime
CPU wheels. `LATIF_THREADS` defaults to 2; no claim of verified phone throughput is made.

## Model provenance

Uses the clean engine from repository commit
`adf01df124928dd38f553612805b04025d683a18`, not the malformed default-branch merge.
The original desktop code is unchanged by this port.

- SILMA model: https://huggingface.co/silma-ai/silma-tts
- ONNX conversion: https://huggingface.co/OpenVoiceOS/phoonnx-f5tts/tree/main/silma-tts-v1
- Conversion model card identifies this particular SILMA checkpoint as Apache-2.0;
  other checkpoints in that repository have different licenses.
- The installer retrieves the reference asset needed by the inherited asset check,
  but does not offer it as a user-owned voice. Supply your own authorized reference.

The installation downloader validates expected byte sizes and pinned SHA-256 hashes,
and records results in `installed-manifest.json`. ONNX downloads use a fixed revision.
The server never calls the downloader and has no network inference implementation.

## Data and recovery

Data resides inside Ubuntu under `/root/.local/share/latif-studio` by default.
`LATIF_DATA` and `LATIF_MODELS` can override storage paths. Back up that data directory
and the code before removing the Ubuntu container. A paused/failed job retains its
finished sections. The download action is enabled only for completed exports.
The app does not delete old jobs automatically. Failed temporary voice imports are
removed; existing voice files and book checkpoints are not removed.

## Boundaries of this build

- No bundled standalone APK, speech-to-speech, dubbing, emotion sliders, STT, or music generation.
- EPUB and scanned-PDF OCR are not implemented in this interface.
- Extraction must be reviewed, especially Arabic PDF reading order and diacritics.
- SILMA is Arabic-first; multilingual/dialect quality is not guaranteed.
- Linux tests do not establish Motorola G85 compatibility, speed, temperature,
  peak memory, or voice similarity. Those require a real phone run.
- No claim of ElevenLabs-equivalent quality or realtime narration.

## Tests

Install `httpx==0.28.1` into a test environment, then run:

```bash
python -m unittest discover -s termux-studio -p 'test_*.py' -v
```

The unit suite uses explicitly mocked PCM only for backend tests and real FFmpeg
for MP3 export. It is not a TTS-quality benchmark. See `VALIDATION.md` for actual
inference results and outstanding device testing.
