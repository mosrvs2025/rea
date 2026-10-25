# Squeeze

An Android video compressor with no ads, no analytics and no network access. The manifest has no `INTERNET` permission, so the app cannot reach the network.

This is an independent app in this repository's `squeeze-android/` directory. It is unrelated to REA.

## What it does

- Pick one or many videos with the system photo picker, or share them to Squeeze from any app.
- Choose **Balanced**, **Small**, **Tiny**, or **Size** (compress each video to at most N MB).
- Live estimate of the result before you start. Compression runs in a foreground service, so you can leave the app.
- Results are saved to `Movies/Squeeze`. Originals are never modified.
- **Library** tab keeps every finished video with total space saved. Entries disappear if the video is deleted elsewhere.
- **Before/after** drag slider compares the same frame from the original and the result.
- **Nerd stats** compare measured resolution, frame rate, codec, bitrates, bits per pixel, plus encode time, speed, compression ratio and estimate accuracy.

## How the compression works

The planner (`model/Planner.kt`, pure Kotlin, unit-tested) picks settings per video:

1. **Best hardware codec.** HEVC when the phone has a hardware encoder (about 30% fewer bits than H.264 at equal quality), AV1 on request where supported, otherwise H.264.
2. **Bits-per-pixel budget** instead of a fixed bitrate, scaled by resolution so 4K, 1080p and 720p each get a sensible rate.
3. **Never spends more than the source.** The ceiling is the source bitrate translated to the new codec's efficiency. Files that would shrink by less than 10% are skipped.
4. **Size-target mode** solves for the bitrate that fits the size, then lowers frame rate before resolution until picture quality stays above a watchability floor. It warns when a target is unreachable.
5. **Output guard.** If the encoded file is not at least 3% smaller than the original, it is discarded and the original is kept.

Encoding uses Media3 Transformer and the phone's MediaCodec hardware encoders in VBR mode. HDR sources are tone-mapped to SDR.

### Limits

- Hardware encoders are fast but less efficient than software x265, and there is no true two-pass mode. The planner compensates with a conservative bitrate budget.
- The size estimate assumes the encoder hits its bitrate. Real output varies with how busy the footage is.
- Compression ratios and encode speed have not been measured on a physical device in this repository.
- The library lives in app-private storage; clearing app data or uninstalling removes the history (not the videos).

## Build

Requirements: JDK 17+, Gradle 8.9+, Android SDK with platform 35 and build-tools 35.

```sh
export ANDROID_HOME=/path/to/android-sdk
gradle :app:testDebugUnitTest     # planner tests
gradle :app:assembleRelease       # app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the debug key for sideloading. Configure your own keystore before distributing.
