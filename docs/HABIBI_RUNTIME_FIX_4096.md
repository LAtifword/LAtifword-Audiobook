# Habibi MSA Android runtime fix — 4096-frame overflow

## Symptom

Android generation failed in `F5_Preprocess.onnx` with:

`/f5_text_embed/Gather ... idx=4096 must be within [-4096,4095]`

The same failure occurred with short visible text, long text and document input, making it look like a text vocabulary limit.

## Reproduction

The pinned Habibi MSA export revision is:

`78b2d00397fc1bc8064d8e4d849e794fa8325245`

A GitHub Actions diagnostic ran the exact `F5_Preprocess.onnx`, `config.json`, `vocab.txt` and 24 kHz embedded reference outside Android.

Verified model vocabulary:

- `num_symbols = 2666`
- token IDs `0..2665`
- `vocab.txt` has exactly 2666 entries
- config token map and vocab line order match exactly
- short Arabic probe had maximum token ID 2559

Verified acoustic duration boundary:

- 1103 frames: PASS
- 1500 frames: PASS
- 3500 frames: PASS
- 4095 frames: PASS
- 4096 frames: PASS
- 4097 frames: FAIL with the exact Android `/f5_text_embed/Gather idx=4096` error
- 4200 frames: same failure

Therefore `4096` is an acoustic/position frame ceiling in this exported F5 preprocess graph, not a 4096-character, word or token limit.

At 24 kHz with a 256-sample hop, 4096 frames correspond to about 43.7 seconds of acoustic timeline for one synthesis invocation. Audiobooks must use many model invocations and concatenate their output.

## Android repair

The service now performs two-stage segmentation:

1. semantic sentence-aware splitting for natural narration;
2. adaptive acoustic-frame splitting after the actual reference voice and transcript are loaded.

Each final section is predicted with the same duration estimator used by the Android engine and must fit below a conservative 3900-frame budget before synthesis starts. Oversized sections are recursively split until safe.

This adapts to:

- PDF, EPUB, DOCX, TXT and pasted text;
- embedded or custom reference voices;
- different reference durations and transcripts;
- narration speed.

The debug runtime-fix build uses application ID suffix `.habibifix` so it can be installed beside the earlier v3.4 candidate for physical A/B testing without uninstalling the existing app.

## Separate reference-audio issue

`Reference voice must be WAV/RIFF` is unrelated to the 4096-frame error. The Android reference decoder currently expects a real RIFF/WAVE PCM container. Renaming MP3 or another compressed format to `.wav` does not convert it.

## Release gate

Do not merge/promote this repair to the production release until a physical Moto G85 test confirms:

- embedded-reference short preview produces audible speech;
- a document preview produces audible speech;
- full-book section planning proceeds beyond the first section without the 4096 Gather error;
- optional real PCM WAV custom reference works;
- startup RAM, thermal behaviour and generation time remain acceptable.
