# Rebuilding jPlay 0.20.28 from this source snapshot

Source revision: `c02df1253a06d826966c95b9892921faef5d97d0` (`discover-breadth-001`) plus shared-workspace snapshot `db6b88c3fa0825853fe49a18c2fff1bd8dc76ff6`. This archive contains the `discovery-demo/` app plus the shared workspace dependencies and root build metadata needed to reproduce it. It does not contain Android signing material, dependency caches, local SDK configuration, or model weights.

## Prerequisites

- Node.js 22.12+ and pnpm 10.33.0 (the root package declares the pinned package manager)
- JDK 17 and Android SDK Platform 35
- Python 3.12+ and `uv` if running the optional server tests
- `unzip` to restore model files from the release APK

## Install pinned dependencies

From the archive root:

```sh
pnpm install --frozen-lockfile --filter . --filter '@now-playing/aqua-ui...'
npm ci --prefix discovery-demo/web
```

The `discovery-demo/web/` package is not a pnpm workspace member. The root install supplies its pinned TypeScript/Vitest/Vite tools. The filtered install includes the root toolchain and the shared UI dependency closure without installing unrelated monorepo apps. This Android-focused snapshot omits those other apps; the shared UI package's own gallery/styleguide typecheck imports some of them, so use the app-level web checks and Android build below as the release verification path.

## Restore bundled models

The release APK contains the model files that are intentionally omitted from this source archive. From the archive root, with `jplay-0.20.28.apk` beside it:

```sh
mkdir -p discovery-demo/.data/models/whisper-tiny.en
unzip -p jplay-0.20.28.apk assets/web/models/model_epoch_36.onnx > discovery-demo/.data/models/model_epoch_36.onnx
unzip -p jplay-0.20.28.apk assets/web/models/model_epoch_36.onnx.data > discovery-demo/.data/models/model_epoch_36.onnx.data
unzip -p jplay-0.20.28.apk assets/web/models/whisper-tiny.en/encoder_model_quantized.onnx > discovery-demo/.data/models/whisper-tiny.en/encoder_model_quantized.onnx
unzip -p jplay-0.20.28.apk assets/web/models/whisper-tiny.en/decoder_model_quantized.onnx > discovery-demo/.data/models/whisper-tiny.en/decoder_model_quantized.onnx
unzip -p jplay-0.20.28.apk assets/web/models/whisper-tiny.en/vocab.json > discovery-demo/.data/models/whisper-tiny.en/vocab.json
unzip -p jplay-0.20.28.apk assets/web/models/whisper-tiny.en/generation_config.json > discovery-demo/.data/models/whisper-tiny.en/generation_config.json
```

| Model file | SHA-256 |
|---|---|
| `model_epoch_36.onnx` | `17860403f8fc90aff8ac0632a0741eb5e58d8c0b0ad2fce5ced967274b0ea971` |
| `model_epoch_36.onnx.data` | `2a735b23c2aad7b12d9ffc85334cebcc659c07696d2ff60e2e378da28b6df657` |
| `whisper-tiny.en/encoder_model_quantized.onnx` | `8cc3c6f8563d1b3fbd2c5af9f64c2bed8b020bc593c402d1ef53b9f08fbf1b90` |
| `whisper-tiny.en/decoder_model_quantized.onnx` | `62b1761a6c23961cdabbf7caa25495525d78528a64baacf7e4283c83cb2941f1` |
| `whisper-tiny.en/vocab.json` | `f6bd25a65e4e63ca31360e9fb11c7e4f9a391a78385d640acd814092dd6eee4f` |
| `whisper-tiny.en/generation_config.json` | `132c95ba9db45f4498f2eab3fea7c1d6a174005010f8f6b7d20cfd5e9795996b` |

See `discovery-demo/docs/LICENSES.md` for model/source license information. The model files are required for the bundled Android release build and are intentionally excluded from this archive; the APK contains them.

## Verify and build

```sh
cd discovery-demo/web
node ../../node_modules/typescript/bin/tsc -p tsconfig.json --noEmit
node ../../node_modules/vitest/vitest.mjs run --root . --dir src
node ../../node_modules/vite/bin/vite.js build
cd ../..
node --test discovery-demo/windows/*.test.cjs
PYTHONPATH= uv run --project discovery-demo/server --frozen pytest -q
cd discovery-demo/android
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease
```

On Windows, use `gradlew.bat` in place of `./gradlew` and configure the Android SDK through an untracked `android/local.properties` file.

## Signing

Release builds are signed only when `AIRWAVE_SIGNING_DIR` points to an external signing folder containing `keystore.properties`. Do not put the signing folder, keystore, or credentials in this archive or in Git. Without external signing configuration, Gradle produces an unsigned release APK. Keep the same signing key for install-over updates.

## Release contents and verification notes

The GitHub release contains the Android APK, this source archive, SHA-256 checksums, and release notes. No Windows installer is included. The Android Auto / radio playback tests were run on Android emulators; no physical phone or car test is claimed.
