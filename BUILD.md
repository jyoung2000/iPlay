# jPlay 0.20.2 — building from source

Built from commit 96ed554 (96ed554b1b9500af4af0cbd772368c7d4f58c02d) of the development repository (app folder `discovery-demo/`).

## Layout
- `discovery-demo/` — the app: `web/` (React UI + recommendation engine), `android/` (Kotlin shell, Android Auto),
  `windows/` (Electron shell), `server/`, `docs/`
- `packages/aqua-ui/` — shared UI kit the web app reads from source (`../../packages/aqua-ui`)
- `package.json`, `pnpm-lock.yaml`, `pnpm-workspace.yaml`, `tsconfig.base.json` — the workspace toolchain
  (react, vite, typescript, vitest and electron are hoisted here)

## Build
1. At the root: `pnpm install` (Node 22). In `discovery-demo/web` also `npm ci` (its own pinned onnxruntime-web).
2. Web: from `discovery-demo/web` run `node ../../node_modules/vite/bin/vite.js build`;
   tests: `node ../../node_modules/vitest/vitest.mjs run --root . --dir src`.
3. Android: from `discovery-demo/android` run `./gradlew assembleRelease` (JDK 17, Android SDK 35).
   Signing is read from `$AIRWAVE_SIGNING_DIR` or `~/airwave-signing/keystore.properties`; without it the build is unsigned.
4. Windows: from `discovery-demo/windows` run `../../node_modules/.bin/electron-builder --win --config electron-builder.config.cjs`
   (code signing from `codesign.properties` in the same signing folder; unsigned without it).
   Windows tests: `node --test *.test.cjs` inside `windows/`.

No signing keys or passwords are in this source. Bundled speech/sound models and downloader tools are fetched by
the build scripts (see `discovery-demo/docs/LICENSES.md`).
