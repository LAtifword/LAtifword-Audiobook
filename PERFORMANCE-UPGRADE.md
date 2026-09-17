# LATIF Audiobook Performance Upgrade

## What changed

The offline Android renderer now uses a **fast literary profile** tuned for long Arabic books. The default F5 refinement count is reduced from 32 to 24 steps, which removes 25% of denoising iterations while retaining the same model, reference voice, Arabic language path, and emotional narration controls.

Narration sections now target approximately **560 characters** instead of 140–220 characters. Splitting remains sentence-aware and only hard-wraps unusually long sentences. This reduces repeated reference/encoder startup overhead and substantially lowers the number of model invocations for a full book.

The Arabic cleanup path now removes standalone `***` layout separators so they are not accidentally spoken. Existing Arabic letters, hamza forms, ta marbuta, alef maqsura, and diacritics remain untouched.

The mobile hot paths were also tightened. WAV parsing now decodes little-endian PCM directly from the byte array instead of allocating a `ByteBuffer` per sample. Reference-audio resampling uses fixed-point linear interpolation rather than per-sample floating-point `floor` calculations. Android and desktop token encoders use reusable single-character lookup tables, and desktop Arabic normalization compiles its regular expressions once instead of once per chunk.

## Quality and output guarantees

The renderer continues to use the local SILMA F5 pipeline, Gacrux-style mature female author narration configuration, Arabic `ar-001`, natural pauses, stable volume, and transactional M4A output. No fade-in, fade-out, background music, or post-processing was added.

## Expected effect

For a 20,000-word manuscript, the previous short-section policy could create roughly 140–180 model calls. The new semantic target is expected to reduce this to approximately 40–70 calls, depending on punctuation and chapter structure. Actual elapsed time remains device-dependent and should be measured from the runtime ETA/RTF display.

## Validation performed

The repository's Node test suite passes, `git diff --check` passes, and the modified Kotlin source files are present in the Android v2 source tree. A full Android compile was not run in this sandbox because Gradle is not installed locally; the existing GitHub Actions Android workflow remains the appropriate full APK validation path.
