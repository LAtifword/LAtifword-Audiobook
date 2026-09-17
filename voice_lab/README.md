# LATIF Voice Lab — SILMA long-form audiobook generation

This folder provides a reproducible Google Colab/GitHub workflow for the open SILMA TTS v1 model already used by the LATIF Android application. It accepts Arabic text and an authorized reference WAV, creates bounded WAV clips, and concatenates them without any audio editing.

## Voice and consent

The previous Manus `Charon` voice is a hosted voice and cannot be exported into GitHub or Colab. This workflow therefore uses SILMA’s local reference-audio voice cloning. Use only a reference recording for which you have permission. Disclose AI-generated audio when sharing it.

## Pure-audio behavior

The script does **not** apply fade-in, fade-out, normalization, compression, denoise, EQ, silence insertion, music, or crossfades. It concatenates the generated PCM WAV clips directly. Natural pauses are produced only by the TTS model from the supplied punctuation.

## Google Colab

1. Open `colab_silma_audiobook.ipynb` from this repository in Colab.
2. Upload your manuscript text and an authorized reference WAV plus its exact transcript.
3. Run all cells.
4. Download `audiobook_output/audiobook_master.wav` and the individual clips.

The notebook installs the official package from SILMA AI and runs on a GPU when Colab provides one. It does not commit private audio, secrets, or generated books to GitHub.

## Local command

```bash
python -m venv .venv
. .venv/bin/activate
pip install -r voice_lab/requirements.txt
python voice_lab/generate_audiobook.py \
  --text manuscript.txt \
  --reference-audio reference.wav \
  --reference-text 'ويدقق النظر في القرآن الكريم وسائر الكتب السماوية ويتبع مسالك الرسل العظام عليهم الصلاة والسلام.' \
  --output-dir audiobook_output
```

For deterministic reruns, pass `--seed 1234`. The default segment size is 4,200 characters to stay below service/model context limits.

## Convert WAV to M4A with metadata and cover art

Install FFmpeg, then run:

```bash
python voice_lab/package_m4a.py \
  --wav audiobook_output/audiobook_master.wav \
  --output audiobook_output/my-book.m4a \
  --cover cover.jpg \
  --chapters-json audiobook_output/chapters.json \
  --title 'My Book' \
  --author 'Author Name' \
  --album 'My Book' \
  --narrator 'LATIF Author Narrator' \
  --year 2026 \
  --genre Audiobook \
  --description 'AI-generated audiobook for private author review' \
  --codec aac \
  --bitrate 128k
```

Use `--codec alac` instead of `--codec aac` for lossless M4A. AAC is smaller and widely compatible; ALAC preserves the WAV samples but produces a larger file.

The script adds title, author, album, narrator, year, genre, description, embedded JPEG cover art, and MP4 chapter markers. It does not apply fades, normalization, compression, EQ, denoise, or gain changes. The only compression in AAC mode is the requested AAC encoding required by the M4A output format.

The `chapters.json` file may be produced by the Android sidecar utility or use this shape:

```json
{
  "chapters": [
    {"title": "Chapter 1", "startMs": 0, "endMs": 180000},
    {"title": "Chapter 2", "startMs": 180000, "endMs": 360000}
  ]
}
```
