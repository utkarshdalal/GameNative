# libgnoverlay

`libgnoverlay.so` is an `LD_PRELOAD` copy-on-write overlay for a Wine prefix. A per-game
"upper" prefix is layered over a shared read-only "lower" base prefix (one per Proton version).
Reads fall through to lower, writes copy the file up into upper, deletions are recorded as
whiteouts in upper, and directory listings merge both. Wine is not modified.

## Environment contract (set by the app)

| Variable | Meaning |
|---|---|
| `GN_OVERLAY_UPPER` | Absolute host path of the per-game layer prefix, e.g. `/data/user/0/app.gamenative/files/imagefs_shared/home/xuser-STEAM_204360/.wine` |
| `GN_OVERLAY_LOWER` | Absolute host path of the base prefix, e.g. `/data/user/0/app.gamenative/files/imagefs_shared/proton/proton-10.0-4-x86_64/base_prefix/.wine` |
| `GN_OVERLAY_ALIASES` | Colon-separated extra absolute prefixes that also mean UPPER (symlinked spellings, guest spelling `/home/xuser/.wine`) |
| `GN_OVERLAY_DEBUG=1` | Verbose log to stderr and logcat (tag `gnoverlay`); never stdout |
| `GN_OVERLAY_LOG` | With debug, also append the log to this file |

A path is inside the overlay when, after normalisation, it equals UPPER, an alias, or LOWER, or
starts with one of them followed by `/`. LOWER is matched too, because the kernel reports lower
paths for fds and cwd (`/proc/self/fd`, `getcwd`) after a read-only open or `chdir` lands there.
`/data/data/` is rewritten to `/data/user/0/` before matching, so the env vars must be given in
`/data/user/0` form.

If UPPER or LOWER is unset, relative, not a directory, or the two overlap, the library logs one
line (when either var is set, or debug is on) and every hook passes through.

## On-disk format

- Whiteout: empty regular file `<UPPER>/.gnoverlay/wh/<key>`. The path does not exist even if
  lower has it. A whiteout on a directory hides the whole lower subtree. Removed when the path
  is created again in upper.
- Opaque directory: empty regular file `<UPPER>/.gnoverlay/opaque/<key>`. The upper directory
  fully shadows lower's; lookups and listings under it use upper only. Written when a directory
  is deleted then recreated, or when a directory is renamed onto a path that exists in lower.
- `<key>` is the canonical overlay-relative path: the parent directory is resolved by the kernel
  (open with `O_PATH` + `/proc/self/fd`), then the final name is appended unresolved. So
  `dosdevices/c:/windows/x.dll` and `drive_c/windows/x.dll` share one marker. Parents that
  resolve outside the overlay (for example through `dosdevices/z:`) never get markers.
- Copy-up temp: `<upper path>.<pid>.<seq>.gnoverlay-tmp`, unique per copy. It is published
  without replacing: `link(tmp, upper)` then `unlink(tmp)`, or `renameat2(RENAME_NOREPLACE)`
  if `link` is refused. If the upper file already exists (another process copied it up first),
  the temp is discarded and the existing upper file is used. Temps are removed on failure and
  hidden from listings.
- `.gnoverlay` is hidden from the UPPER root listing and returns `ENOENT` through aliases.
  Accessing it through the literal UPPER spelling passes straight through.

## Semantics

- Paths are made absolute (cwd, or `readlink(/proc/self/fd/N)` for `*at()` dirfds), and `//`,
  `.`, `..` are collapsed textually. Every scan is bounded by the input length. Intermediate
  symlinks are resolved by the kernel: lookups try `<UPPER>/<rel>` with `lstat`, then
  `<LOWER>/<rel>`. This relies on upper and lower carrying the same `dosdevices` links.
- A final-component symlink is followed in the merged view (at most 8 hops) for calls that
  follow links (`stat`, `open` without `O_NOFOLLOW`, `opendir`, `chdir`, `chmod`, ...). This
  keeps an upper link whose target exists only in lower working. Targets outside the overlay are
  passed to the real call unchanged.
- Lookup: upper wins; an upper `ENOTDIR` (a non-directory shadows the path) means absent; else a
  whiteout on the path or an ancestor, or an opaque ancestor, hides lower; else lower.
- Read-only calls (`open` without write intent, `fopen "r"`, `stat`/`lstat`/`fstatat`/`statx`,
  `access`/`faccessat`, `readlink`/`readlinkat`, `getxattr`/`listxattr` and their `l`
  variants, `chdir`) run on the looked-up real path.
- Write opens (`O_WRONLY`, `O_RDWR`, `O_TRUNC`, `O_APPEND`, or `O_CREAT` of a missing file):
  - Missing upper parents are created, mirroring lower. Directories get `mode | 0700`, and lower
    symlinks (e.g. `dosdevices/d:`) are recreated as symlinks with their targets materialised.
  - A lower file is copied up: data is skipped with `O_TRUNC`; mode `| 0600`, atime/mtime and
    `user.*` xattrs are preserved.
  - Then upper is opened with the caller's flags, and the whiteout is removed after the open
    succeeds.
  - `O_CREAT|O_EXCL` returns `EEXIST` when the merged view has the file.
- Other write-side calls:
  - `unlink`/`unlinkat`: removes the upper copy, then writes a whiteout if lower has the path.
  - `rmdir`: the merged directory must be empty.
  - `mkdir`/`mkdirat`: over a whited-out lower directory it writes an opaque marker.
  - `rename`/`renameat`/`renameat2`: supports `RENAME_NOREPLACE` and `RENAME_EXCHANGE`.
    Directories are copied up recursively first.
  - `symlink`/`symlinkat` and `link`/`linkat`; cross-boundary renames and links copy up, then
    act on upper.
- `chmod`/`fchmodat`/`utimensat`/`truncate`/`setxattr`/`lsetxattr`/`removexattr`/
  `lremovexattr` copy up first. On bionic, `utimes`, `utime`, `lutimes` and `futimens` all go
  through `utimensat@plt`, so they are covered.
- `fchmod`/`fsetxattr`/`fremovexattr` and `utimensat(fd, NULL, ...)` on an fd that points into
  LOWER (checked with `readlink(/proc/self/fd/N)`) copy the file up and apply the change to the
  upper path. The caller's fd keeps reading the lower file.
- Directory listing: `opendir`/`fdopendir` of an overlay directory returns a private tagged
  object holding a merged, de-duplicated snapshot (upper entries, then lower entries that are
  not shadowed or whited-out, unless opaque). `readdir`, `readdir64`, `readdir_r`,
  `readdir64_r`, `rewinddir` (re-snapshots), `telldir`, `seekdir`, `closedir`, `dirfd` and
  `scandir` understand it; real `DIR*` pass through. Wine's ntdll reads directories with
  `opendir`/`readdir` and wineserver with `fdopendir`/`readdir`. Raw `getdents64` is not
  intercepted.
- `realpath` returns the canonical path in upper spelling, even for files that live in lower.

## Exported hooks (62)

`open open64 openat openat64 __open_2 __openat_2 creat fopen fopen64 stat lstat fstatat
fstatat64 statx access faccessat readlink readlinkat __readlink_chk __readlinkat_chk getxattr
lgetxattr listxattr llistxattr setxattr lsetxattr removexattr lremovexattr fsetxattr
fremovexattr fchmod chmod fchmodat utimensat truncate unlink unlinkat rmdir mkdir mkdirat rename
renameat renameat2 symlink symlinkat link linkat opendir fdopendir readdir readdir64 readdir_r
readdir64_r rewinddir telldir seekdir closedir dirfd scandir chdir realpath dlsym`

## Interposition model (bionic)

Plain symbol interposition: the library sits in the global lookup group via `LD_PRELOAD`, so
the wine executable and every library it `dlopen`s later (`ntdll.so`, `win32u.so`, ...) bind
their libc imports to these hooks.

Real functions are resolved once through the real `dlsym`, obtained with
`dlvsym(RTLD_NEXT, "dlsym", NULL)`, using `RTLD_NEXT`.

`dlsym` is hooked for box64. x86-64 Proton runs under `imagefs/usr/bin/box64`, which gets native
libc functions with `dlsym` on a real library handle, so it would otherwise bypass interposition.
The hook works like this:

- It forwards to the linker's `__loader_dlsym(handle, name, caller)` with the true caller, so
  other libraries' `RTLD_NEXT` lookups keep their meaning. That is exactly what bionic's libdl
  `dlsym` does. If `__loader_dlsym` is not found, it falls back to the real `dlsym`.
- For `RTLD_NEXT` it returns the result unchanged, so interposer chains are never broken.
- Otherwise it returns our hook only when the name is one of our exported hooks and the
  resolved address is the real libc function we resolved for that hook (or already our hook).
  Everything else is returned unchanged.

The decision is `gno_dlsym_pick` in the core, which the host tests cover.

Two rules keep the interposition chain (other preloads such as `libredirect` and `libsysvshm`
sit in the same chain) from recursing:

- Every file-system operation performed inside the core uses raw syscalls (inline `svc #0` on
  aarch64), never a libc wrapper. Bionic wrappers such as `unlink` call `unlinkat@plt`, which
  re-enters the preload chain.
- Each hook passes through to its own real function (`renameat2` -> real `renameat2`, `rename`
  -> real `rename`). Bionic's `rename` and `renameat` tail-call `renameat2@plt`. A hook that
  forwarded `renameat2(flags=0)` to libc `renameat` spun forever in a tail-call cycle, which
  was seen on device in `winedevice.exe` via fontconfig's `FcAtomicReplaceOrig`. The remaining
  cross-calls go to direct-syscall stubs (`openat`, `fchmodat` with flags 0, `setxattr`).

## Build

```
./build.sh
```

Uses NDK 27.3 clang directly (`--target=aarch64-linux-android29 -O2 -fPIC -shared
-fvisibility=hidden -U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=0`, `-ldl -llog`,
`-Wl,-z,max-page-size=16384`, stripped), writes `out/bionic/libgnoverlay.so`, and copies it to
`app/src/main/jniLibs/arm64-v8a/libgnoverlay.so` next to `libevshim.so`. `CMakeLists.txt` builds
the same library through the NDK CMake toolchain. Set `ANDROID_NDK_HOME` to use another NDK.

## Tests

```
./tests/run.sh
```

Builds `gnoverlay_core.c` natively with ASan/UBSan and runs `tests/test_core.c` against temp
directories through the ops vtable (host libc, no interposition). It covers:

- lookup precedence, whiteouts and opaque directories;
- copy-up (mode, mtime, xattrs, 0444 lower files);
- rename, link and symlink;
- `dosdevices` links in both layers and in lower only, with canonical marker keys;
- merged listings;
- the fontconfig rename regression;
- a 100k-path normalisation fuzz with an iteration bound;
- `.` components at alias boundaries;
- the `dlsym` hook decision.

`TMPDIR` selects the scratch location.

## Known limitations

- Inode identity changes after copy-up: `st_dev`/`st_ino` of a file switch from lower to upper
  the first time it is written. Wine and wineserver key some state on dev/ino.
- An fd opened read-only on a lower file keeps reading lower after a later copy-up.
- fd-based changes are copied up only for `fchmod`, `fsetxattr`, `fremovexattr` and `futimens`
  (via `utimensat`). `fchown`, `ftruncate` and writes need a write-mode fd, which is always
  upper.
- If upper and lower carry different intermediate symlinks at the same path (for example a
  game repoints `dosdevices/d:`), a path is looked up through upper's link first and then
  through lower's, so results can mix the two targets.
- Directory reads through raw `getdents64` on an fd see only the physical directory (upper or
  lower), not the merged view.
- Whiteout/opaque checks cost an extra `open`+`readlink`+`close` to canonicalise the parent,
  but only when `.gnoverlay/wh` or `.gnoverlay/opaque` exists.
- Concurrent copy-ups of the same file (for example wine and wineserver on a registry file)
  are safe: one copy wins and the other is discarded, so a published upper file is never
  replaced by a fresh copy of lower. A process still holding a lower fd keeps reading lower.
- Lower files sealed read-only (`0444`) report that mode through `stat`/`access` until copied
  up. Wine derives `FILE_ATTRIBUTE_READONLY` from missing write bits.
- `O_RDONLY|O_CREAT` on a file that exists only in lower opens lower and does not copy up.
- Renaming a directory that exists in lower copies its merged tree into upper first.
- Paths that enter the overlay through a symlink outside it that is not listed in
  `GN_OVERLAY_ALIASES` are not overlaid.
- Relative paths cost one `getcwd` per call even when cwd is outside the overlay.
- Not hooked: `mknod`, `mkfifo`, `chown` family, `execve`, `statfs`/`statvfs`, `freopen`,
  `scandirat`, `openat2`, `getdents64`/`syscall`, `close`/`dup*`/`lseek`/`fcntl`.
  Bionic's `stat64`, `lstat64`, `creat64`, `scandir64` and `scandirat64` reach the hooks
  through libc-internal PLT calls.
