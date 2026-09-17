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
