# Author Narrator Integration

This working tree changes `v2-src/standalone-android/app/src/main/java/com/latif/audiobook/offline/AudiobookService.kt` so every upload uses one deterministic local narrator configuration:

- Bundled `silma-f5/default_ref.wav` reference voice.
- Modern Standard Arabic reference transcript already shipped with the app.
- Literary pacing profile.
- Fixed speed: `0.90x`.
- Full SILMA F5 trajectory: `32` refinement steps.
- No edge fade or crossfade added by the service.
- Existing upload, PDF/DOCX/TXT parsing, preview, offline inference, and output flow remain unchanged.

## Important voice identity note

The prior audiobook task used the remote `Charon` service voice. That proprietary service voice cannot be exported into this APK. This integration therefore makes the app consistently use its own embedded SILMA narrator reference. It will be repeatable for every upload, but it is not a mathematically identical clone of `Charon`.

## Build status

The repository checkout does not include a Gradle wrapper and this sandbox does not have an Android SDK configured, so the modified source was not compiled or signed here. Build it from `v2-src/standalone-android` in Android Studio or a configured Android build environment, then sign the release with your existing LATIF certificate.
