# libgncounters.so provenance

`app/src/main/jniLibs/arm64-v8a/libgncounters.so` is a prebuilt (the app does not run CMake during packaging),
built from the sources in this directory:

- sha256: `f4355e4a2e22c6f41a61df8dc2cf15d3e67e320ec67d74bacf53ab136f5a9f65` (1,187,456 bytes)
- Arm libGPUCounters tag `2.5.0`, commit `378c7b59e2dae87cbbffb0ac9cf96ed13c939823`
  (https://github.com/ARM-software/libGPUCounters, MIT, see `LICENSE-libGPUCounters.md`), linked statically
- NDK 27.3.13750724, arm64-v8a, android-26, c++_static, MinSizeRel, `-z max-page-size=16384`, stripped
- build: `app/src/main/cpp/gncounters/build.sh`, then
  `cp app/src/main/cpp/gncounters/out/libgncounters.so app/src/main/jniLibs/arm64-v8a/`

`build.sh` clones libGPUCounters into `third_party/` (ignored), refuses any other or modified checkout, and the
build is reproducible: a rebuild yields the same sha256. Only dependencies: liblog, libm, libdl, libc.
Exports only the four `Java_app_gamenative_powercontrol_metrics_MaliGpuCounters_*` entry points.
