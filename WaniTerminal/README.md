# Wani Terminal

Android terminal app (`com.wani.terminal`) that runs Alpine Linux through proot.
Packages come from Alpine's own repo (`apk add ...`), so no Termux bootstrap or apt repo is needed.

## 1. Add proot (required, not included)
Put these two files in `app/src/main/jniLibs/arm64-v8a/`:

- `libproot.so`         -> a **static** aarch64 proot binary, renamed
- `libproot-loader.so`  -> the proot loader (`loader` binary), renamed

If your proot is dynamically linked (e.g. copied from Termux), also add the libraries it needs
(for example `libtalloc.so`). Android only lets the app execute files from `nativeLibraryDir`,
which is why they must be named `lib*.so`.

## 2. Build
Open the folder in Android Studio (it creates the Gradle wrapper), then Run, or:
`gradle wrapper && ./gradlew assembleDebug`

## 3. Known things to check
- Terminal library coordinate (`com.github.termux.termux-app:terminal-view:v0.118.0`) is from JitPack.
  If it does not resolve, add termux-app's `terminal-emulator` and `terminal-view` modules as git submodules instead.
- If proot cannot start programs inside Alpine, set `targetSdk = 28` in `app/build.gradle.kts`,
  or add `PROOT_NO_SECCOMP=1` in `ProotLauncher.kt`.
- Storage: `/sdcard` is bound, but Android 11+ needs "All files access" granted in system settings for it to be readable.
- License: terminal-view/emulator are GPLv3, so this app must be GPLv3 too.
