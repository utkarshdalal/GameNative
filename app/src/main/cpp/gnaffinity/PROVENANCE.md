# libgnaffinity.so provenance

`app/src/main/jniLibs/arm64-v8a/libgnaffinity.so` is a prebuilt (the app does not run CMake during packaging),
built from `jni/gnaffinity.c`:

- sha256: `61272eb70f568de532333ebc6574866eb23b7bd88119975aba2cc16c526650b9`
- NDK 27.3.13750724, arm64-v8a, android-26, MinSizeRel, `-z max-page-size=16384`, stripped; a rebuild yields the same sha256
- build: `app/src/main/cpp/gnaffinity/build.sh`, then
  `cp app/src/main/cpp/gnaffinity/out/libgnaffinity.so app/src/main/jniLibs/arm64-v8a/`

Exports only `Java_app_gamenative_powercontrol_TaskAffinity_nativeSetTaskAffinity`. Only dependencies: libc, libm, libdl.
