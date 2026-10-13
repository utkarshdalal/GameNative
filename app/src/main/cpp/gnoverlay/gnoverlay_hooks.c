#define _GNU_SOURCE
#include "gnoverlay_core.h"

#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <linux/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/xattr.h>
#include <unistd.h>
#ifdef __ANDROID__
#include <android/log.h>
#endif

#pragma clang diagnostic ignored "-Wdeprecated-declarations"

#define EXPORT __attribute__((visibility("default")))
#define PM GNO_PATH_MAX

#ifndef O_TMPFILE
#define O_TMPFILE (020000000 | O_DIRECTORY)
#endif

int __open_2(const char *path, int flags);
int __openat_2(int dirfd, const char *path, int flags);
ssize_t __readlink_chk(const char *path, char *buf, size_t n, size_t bufsz);
ssize_t __readlinkat_chk(int dirfd, const char *path, char *buf, size_t n, size_t bufsz);
int renameat2(int olddirfd, const char *oldpath, int newdirfd, const char *newpath, unsigned flags);
int statx(int dirfd, const char *path, int flags, unsigned mask, struct statx *buf);

typedef int (*scandir_filter_t)(const struct dirent *);
typedef int (*scandir_cmp_t)(const struct dirent **, const struct dirent **);

#define HOOKS(X)                                                                                   \
    X(open) X(open64) X(openat) X(openat64) X(__open_2) X(__openat_2) X(creat) X(fopen) X(fopen64) \
    X(stat) X(lstat) X(fstatat) X(fstatat64) X(statx) X(access) X(faccessat)                       \
    X(readlink) X(readlinkat) X(__readlink_chk) X(__readlinkat_chk)                                \
    X(getxattr) X(lgetxattr) X(listxattr) X(llistxattr) X(setxattr) X(lsetxattr) X(removexattr)    \
    X(lremovexattr) X(fsetxattr) X(fremovexattr) X(fchmod) X(chmod) X(fchmodat) X(utimensat)       \
    X(truncate) X(unlink) X(unlinkat) X(rmdir) X(mkdir) X(mkdirat) X(rename) X(renameat)           \
    X(renameat2) X(symlink) X(symlinkat) X(link) X(linkat)                                         \
    X(opendir) X(fdopendir) X(readdir) X(readdir64) X(readdir_r) X(readdir64_r) X(rewinddir)       \
    X(telldir) X(seekdir) X(closedir) X(dirfd) X(scandir) X(chdir) X(realpath)

#define DECL_REAL(n) static __typeof__(n) *real_##n;
HOOKS(DECL_REAL)

typedef struct hook_ent {
    const char *name;
    void *hook;
    void **real;
} hook_ent;

#define HOOK_ENT(n) {#n, (void *)n, (void **)&real_##n},
static const hook_ent g_hooks[] = {HOOKS(HOOK_ENT)};
#define NHOOKS (sizeof(g_hooks) / sizeof(g_hooks[0]))

static pthread_once_t g_once = PTHREAD_ONCE_INIT;
static void *(*real_dlsym)(void *, const char *);
static void *(*loader_dlsym)(void *, const char *, const void *);
static const char *g_names[NHOOKS];
static void *g_hookp[NHOOKS];
static void *g_realp[NHOOKS];

static void resolve_reals(void)
{
    real_dlsym = (void *(*)(void *, const char *))dlvsym(RTLD_NEXT, "dlsym", NULL);
    loader_dlsym = (void *(*)(void *, const char *, const void *))dlvsym(RTLD_DEFAULT, "__loader_dlsym", NULL);
    for (size_t i = 0; i < NHOOKS; i++) {
        void *p = real_dlsym ? real_dlsym(RTLD_NEXT, g_hooks[i].name) : NULL;
        if (p == g_hooks[i].hook) p = NULL;
        *g_hooks[i].real = p;
        g_names[i] = g_hooks[i].name;
        g_hookp[i] = g_hooks[i].hook;
        g_realp[i] = p;
    }
}

static inline void ensure_reals(void) { pthread_once(&g_once, resolve_reals); }

#define R(n) (real_##n ? real_##n : (ensure_reals(), real_##n))

#if defined(__aarch64__)
static long sc6(long nr, long a, long b, long c, long d, long e, long f)
{
    register long x8 __asm__("x8") = nr;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    register long x4 __asm__("x4") = e;
    register long x5 __asm__("x5") = f;
    __asm__ volatile("svc #0"
                     : "+r"(x0)
                     : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5)
                     : "memory", "cc");
    return x0;
}
#else
static long sc6(long nr, long a, long b, long c, long d, long e, long f)
{
    long r = syscall(nr, a, b, c, d, e, f);
    return r == -1 ? -errno : r;
}
#endif

static long sc_ret(long r)
{
    if (r < 0 && r > -4096) {
        errno = (int)-r;
        return -1;
    }
    return r;
}

#define SC(nr, a, b, c, d, e, f) sc_ret(sc6((nr), (long)(a), (long)(b), (long)(c), (long)(d), (long)(e), (long)(f)))

static int op_open(const char *p, int f, mode_t m) { return (int)SC(__NR_openat, AT_FDCWD, p, f, m, 0, 0); }
static int op_close(int fd) { return (int)SC(__NR_close, fd, 0, 0, 0, 0, 0); }
static ssize_t op_read(int fd, void *b, size_t n) { return SC(__NR_read, fd, b, n, 0, 0, 0); }
static ssize_t op_write(int fd, const void *b, size_t n) { return SC(__NR_write, fd, b, n, 0, 0, 0); }
static int op_fstat(int fd, struct stat *st) { return (int)SC(__NR_fstat, fd, st, 0, 0, 0, 0); }
static int op_stat(const char *p, struct stat *st) { return (int)SC(__NR_newfstatat, AT_FDCWD, p, st, 0, 0, 0); }
static int op_lstat(const char *p, struct stat *st)
{
    return (int)SC(__NR_newfstatat, AT_FDCWD, p, st, AT_SYMLINK_NOFOLLOW, 0, 0);
}
static int op_mkdir(const char *p, mode_t m) { return (int)SC(__NR_mkdirat, AT_FDCWD, p, m, 0, 0, 0); }
static int op_rmdir(const char *p) { return (int)SC(__NR_unlinkat, AT_FDCWD, p, AT_REMOVEDIR, 0, 0, 0); }
static int op_unlink(const char *p) { return (int)SC(__NR_unlinkat, AT_FDCWD, p, 0, 0, 0, 0); }
static int op_rename2(const char *a, const char *b, unsigned flags)
{
    long r = sc6(__NR_renameat2, AT_FDCWD, (long)a, AT_FDCWD, (long)b, flags, 0);
    if (r == -ENOSYS && !flags) r = sc6(__NR_renameat, AT_FDCWD, (long)a, AT_FDCWD, (long)b, 0, 0);
    return (int)sc_ret(r);
}
static int op_rename(const char *a, const char *b) { return op_rename2(a, b, 0); }
static int op_rename_exchange(const char *a, const char *b) { return op_rename2(a, b, 2); }
static int op_rename_noreplace(const char *a, const char *b) { return op_rename2(a, b, 1); }
static ssize_t op_readlink(const char *p, char *b, size_t n)
{
    return SC(__NR_readlinkat, AT_FDCWD, p, b, n, 0, 0);
}
static int op_symlink(const char *t, const char *p) { return (int)SC(__NR_symlinkat, t, AT_FDCWD, p, 0, 0, 0); }
static int op_link(const char *a, const char *b) { return (int)SC(__NR_linkat, AT_FDCWD, a, AT_FDCWD, b, 0, 0); }
static int op_fchmod(int fd, mode_t m) { return (int)SC(__NR_fchmod, fd, m, 0, 0, 0, 0); }
static int op_futimens(int fd, const struct timespec ts[2]) { return (int)SC(__NR_utimensat, fd, 0, ts, 0, 0, 0); }
static ssize_t op_flistxattr(int fd, char *l, size_t n) { return SC(__NR_flistxattr, fd, l, n, 0, 0, 0); }
static ssize_t op_fgetxattr(int fd, const char *nm, void *v, size_t n)
{
    return SC(__NR_fgetxattr, fd, nm, v, n, 0, 0);
}
static int op_fsetxattr(int fd, const char *nm, const void *v, size_t n, int f)
{
    return (int)SC(__NR_fsetxattr, fd, nm, v, n, f, 0);
}
static char *op_getcwd(char *b, size_t n) { return SC(__NR_getcwd, b, n, 0, 0, 0, 0) < 0 ? NULL : b; }
static ssize_t op_fd_path(int fd, char *buf, size_t n)
{
    char p[32];
    snprintf(p, sizeof(p), "/proc/self/fd/%d", fd);
    return op_readlink(p, buf, n);
}

typedef struct rawdir {
    int fd;
    int pos;
    int len;
    struct dirent ent;
    char buf[8192];
} rawdir;

static DIR *op_opendir(const char *p)
{
    int fd = op_open(p, O_RDONLY | O_DIRECTORY | O_CLOEXEC, 0);
    if (fd < 0) return NULL;
    rawdir *d = malloc(sizeof(*d));
    if (!d) {
        op_close(fd);
        errno = ENOMEM;
        return NULL;
    }
    d->fd = fd;
    d->pos = d->len = 0;
    return (DIR *)d;
}

static struct dirent *op_readdir(DIR *dp)
{
    rawdir *d = (rawdir *)dp;
    if (d->pos >= d->len) {
        long n = SC(__NR_getdents64, d->fd, d->buf, sizeof(d->buf), 0, 0, 0);
        if (n <= 0) return NULL;
        d->len = (int)n;
        d->pos = 0;
    }
    const unsigned char *r = (const unsigned char *)d->buf + d->pos;
    uint16_t rl;
    memcpy(&rl, r + 16, 2);
    d->pos += rl;
    memcpy(&d->ent.d_ino, r, 8);
    memcpy(&d->ent.d_off, r + 8, 8);
    d->ent.d_reclen = sizeof(d->ent);
    d->ent.d_type = r[18];
    snprintf(d->ent.d_name, sizeof(d->ent.d_name), "%s", (const char *)r + 19);
    return &d->ent;
}

static int op_closedir(DIR *dp)
{
    rawdir *d = (rawdir *)dp;
    int r = op_close(d->fd);
    free(d);
    return r;
}

#ifdef __ANDROID__
static void op_log_sink(const char *msg) { __android_log_write(ANDROID_LOG_INFO, "gnoverlay", msg); }
#endif

__attribute__((constructor)) static void gnoverlay_ctor(void)
{
    ensure_reals();
    gno_ops o;
    memset(&o, 0, sizeof(o));
    o.open = op_open;
    o.close = op_close;
    o.read = op_read;
    o.write = op_write;
    o.fstat = op_fstat;
    o.stat = op_stat;
    o.lstat = op_lstat;
    o.mkdir = op_mkdir;
    o.rmdir = op_rmdir;
    o.unlink = op_unlink;
    o.rename = op_rename;
    o.rename_exchange = op_rename_exchange;
    o.rename_noreplace = op_rename_noreplace;
    o.readlink = op_readlink;
    o.symlink = op_symlink;
    o.link = op_link;
    o.fchmod = op_fchmod;
    o.futimens = op_futimens;
    o.opendir = op_opendir;
    o.readdir = op_readdir;
    o.closedir = op_closedir;
    o.flistxattr = op_flistxattr;
    o.fgetxattr = op_fgetxattr;
    o.fsetxattr = op_fsetxattr;
    o.getcwd = op_getcwd;
    o.fd_path = op_fd_path;
#ifdef __ANDROID__
    o.log_sink = op_log_sink;
#endif
    const char *dbg = getenv("GN_OVERLAY_DEBUG");
    gno_init(&o, getenv("GN_OVERLAY_UPPER"), getenv("GN_OVERLAY_LOWER"), getenv("GN_OVERLAY_ALIASES"),
             dbg && !strcmp(dbg, "1"), getenv("GN_OVERLAY_LOG"));
}

#define RESOLVE(dirfd, path, passexpr, errval)                            \
    gno_res r_;                                                           \
    int k_ = gno_resolve((dirfd), (path), &r_);                           \
    if (k_ == GNO_PASS) return passexpr;                                  \
    if (k_ < 0) return errval;

#define READ_PATH(var, follow, errval)                                    \
    char var[PM];                                                         \
    if (gno_read_path(&r_, (follow), var) < 0) return errval;

#define WRITE_PATH(var, follow, copy)                                     \
    char var[PM];                                                         \
    if (gno_materialize(&r_, (follow), (copy), var) < 0) return -1;

static int do_open(int dirfd, const char *path, int flags, mode_t mode)
{
    RESOLVE(dirfd, path, R(openat)(dirfd, path, flags, mode), -1);
    char p[PM];
    int created = 0, is_dir = 0;
    if ((flags & O_TMPFILE) == O_TMPFILE) {
        if (gno_materialize(&r_, 1, 0, p) < 0) return -1;
        return R(openat)(AT_FDCWD, p, flags, mode);
    }
    if (gno_prepare_open(&r_, flags, p, &created, &is_dir) < 0) return -1;
    int fd = R(openat)(AT_FDCWD, p, flags, mode);
    if (fd >= 0 && created) gno_finish_create(&r_);
    return fd;
}

static mode_t va_mode(int flags, va_list ap)
{
    if ((flags & O_CREAT) || (flags & O_TMPFILE) == O_TMPFILE) return (mode_t)va_arg(ap, int);
    return 0;
}

#define OPEN_VA(name, dirfd_expr, ...)                 \
    EXPORT int name(__VA_ARGS__, int flags, ...)       \
    {                                                  \
        va_list ap;                                    \
        va_start(ap, flags);                           \
        mode_t m = va_mode(flags, ap);                 \
        va_end(ap);                                    \
        return do_open(dirfd_expr, path, flags, m);    \
    }

OPEN_VA(open, AT_FDCWD, const char *path)
OPEN_VA(open64, AT_FDCWD, const char *path)
OPEN_VA(openat, dirfd, int dirfd, const char *path)
OPEN_VA(openat64, dirfd, int dirfd, const char *path)

EXPORT int __open_2(const char *path, int flags) { return do_open(AT_FDCWD, path, flags, 0); }
EXPORT int __openat_2(int dirfd, const char *path, int flags) { return do_open(dirfd, path, flags, 0); }
EXPORT int creat(const char *path, mode_t mode) { return do_open(AT_FDCWD, path, O_WRONLY | O_CREAT | O_TRUNC, mode); }

static FILE *do_fopen(const char *path, const char *mode, FILE *(*realfn)(const char *, const char *))
{
    gno_res r;
    int k = gno_resolve(AT_FDCWD, path, &r);
    if (k == GNO_PASS) return realfn(path, mode);
    if (k < 0) return NULL;
    char p[PM];
    int created = 0, is_dir = 0;
    if (gno_prepare_open(&r, gno_fopen_flags(mode), p, &created, &is_dir) < 0) return NULL;
    FILE *f = realfn(p, mode);
    if (f && created) gno_finish_create(&r);
    return f;
}

EXPORT FILE *fopen(const char *path, const char *mode) { return do_fopen(path, mode, R(fopen)); }
EXPORT FILE *fopen64(const char *path, const char *mode) { return do_fopen(path, mode, R(fopen64)); }

EXPORT int stat(const char *path, struct stat *st)
{
    RESOLVE(AT_FDCWD, path, R(stat)(path, st), -1);
    READ_PATH(p, 1, -1);
    return R(stat)(p, st);
}

EXPORT int lstat(const char *path, struct stat *st)
{
    RESOLVE(AT_FDCWD, path, R(lstat)(path, st), -1);
    READ_PATH(p, 0, -1);
    return R(lstat)(p, st);
}

EXPORT int fstatat(int dirfd, const char *path, struct stat *st, int flags)
{
    if (path && !*path && (flags & AT_EMPTY_PATH)) return R(fstatat)(dirfd, path, st, flags);
    RESOLVE(dirfd, path, R(fstatat)(dirfd, path, st, flags), -1);
    READ_PATH(p, !(flags & AT_SYMLINK_NOFOLLOW), -1);
    return R(fstatat)(AT_FDCWD, p, st, flags);
}

EXPORT int fstatat64(int dirfd, const char *path, struct stat64 *st, int flags)
{
    if (path && !*path && (flags & AT_EMPTY_PATH)) return R(fstatat64)(dirfd, path, st, flags);
    RESOLVE(dirfd, path, R(fstatat64)(dirfd, path, st, flags), -1);
    READ_PATH(p, !(flags & AT_SYMLINK_NOFOLLOW), -1);
    return R(fstatat64)(AT_FDCWD, p, st, flags);
}

static int call_statx(int dirfd, const char *path, int flags, unsigned mask, struct statx *buf)
{
    if (R(statx)) return real_statx(dirfd, path, flags, mask, buf);
    return (int)SC(__NR_statx, dirfd, path, flags, mask, buf, 0);
}

EXPORT int statx(int dirfd, const char *path, int flags, unsigned mask, struct statx *buf)
{
    if (path && !*path && (flags & AT_EMPTY_PATH)) return call_statx(dirfd, path, flags, mask, buf);
    RESOLVE(dirfd, path, call_statx(dirfd, path, flags, mask, buf), -1);
    READ_PATH(p, !(flags & AT_SYMLINK_NOFOLLOW), -1);
    return call_statx(AT_FDCWD, p, flags, mask, buf);
}

EXPORT int access(const char *path, int mode)
{
    RESOLVE(AT_FDCWD, path, R(access)(path, mode), -1);
    READ_PATH(p, 1, -1);
    return R(access)(p, mode);
}

EXPORT int faccessat(int dirfd, const char *path, int mode, int flags)
{
    RESOLVE(dirfd, path, R(faccessat)(dirfd, path, mode, flags), -1);
    READ_PATH(p, !(flags & AT_SYMLINK_NOFOLLOW), -1);
    return R(faccessat)(AT_FDCWD, p, mode, flags);
}

EXPORT ssize_t readlink(const char *path, char *buf, size_t n)
{
    RESOLVE(AT_FDCWD, path, R(readlink)(path, buf, n), -1);
    READ_PATH(p, 0, -1);
    return R(readlink)(p, buf, n);
}

EXPORT ssize_t readlinkat(int dirfd, const char *path, char *buf, size_t n)
{
    RESOLVE(dirfd, path, R(readlinkat)(dirfd, path, buf, n), -1);
    READ_PATH(p, 0, -1);
    return R(readlinkat)(AT_FDCWD, p, buf, n);
}

EXPORT ssize_t __readlink_chk(const char *path, char *buf, size_t n, size_t bufsz)
{
    if (n > bufsz) abort();
    return readlink(path, buf, n);
}

EXPORT ssize_t __readlinkat_chk(int dirfd, const char *path, char *buf, size_t n, size_t bufsz)
{
    if (n > bufsz) abort();
    return readlinkat(dirfd, path, buf, n);
}

EXPORT ssize_t getxattr(const char *path, const char *name, void *val, size_t n)
{
    RESOLVE(AT_FDCWD, path, R(getxattr)(path, name, val, n), -1);
    READ_PATH(p, 1, -1);
    return R(getxattr)(p, name, val, n);
}

EXPORT ssize_t lgetxattr(const char *path, const char *name, void *val, size_t n)
{
    RESOLVE(AT_FDCWD, path, R(lgetxattr)(path, name, val, n), -1);
    READ_PATH(p, 0, -1);
    return R(lgetxattr)(p, name, val, n);
}

EXPORT ssize_t listxattr(const char *path, char *list, size_t n)
{
    RESOLVE(AT_FDCWD, path, R(listxattr)(path, list, n), -1);
    READ_PATH(p, 1, -1);
    return R(listxattr)(p, list, n);
}

EXPORT ssize_t llistxattr(const char *path, char *list, size_t n)
{
    RESOLVE(AT_FDCWD, path, R(llistxattr)(path, list, n), -1);
    READ_PATH(p, 0, -1);
    return R(llistxattr)(p, list, n);
}

EXPORT int setxattr(const char *path, const char *name, const void *val, size_t n, int flags)
{
    RESOLVE(AT_FDCWD, path, R(setxattr)(path, name, val, n, flags), -1);
    WRITE_PATH(p, 1, 1);
    return R(setxattr)(p, name, val, n, flags);
}

EXPORT int lsetxattr(const char *path, const char *name, const void *val, size_t n, int flags)
{
    RESOLVE(AT_FDCWD, path, R(lsetxattr)(path, name, val, n, flags), -1);
    WRITE_PATH(p, 0, 1);
    return R(lsetxattr)(p, name, val, n, flags);
}

EXPORT int removexattr(const char *path, const char *name)
{
    RESOLVE(AT_FDCWD, path, R(removexattr)(path, name), -1);
    WRITE_PATH(p, 1, 1);
    return R(removexattr)(p, name);
}

EXPORT int lremovexattr(const char *path, const char *name)
{
    RESOLVE(AT_FDCWD, path, R(lremovexattr)(path, name), -1);
    WRITE_PATH(p, 0, 1);
    return R(lremovexattr)(p, name);
}

#define FD_WRITE(fd, pathcall, fdcall)            \
    char p[PM];                                   \
    int c = gno_fd_lower_copyup((fd), p);         \
    if (c < 0) return -1;                         \
    if (c == 1) return pathcall;                  \
    return fdcall;

EXPORT int fsetxattr(int fd, const char *name, const void *val, size_t n, int flags)
{
    FD_WRITE(fd, R(setxattr)(p, name, val, n, flags), R(fsetxattr)(fd, name, val, n, flags));
}

EXPORT int fremovexattr(int fd, const char *name)
{
    FD_WRITE(fd, R(removexattr)(p, name), R(fremovexattr)(fd, name));
}

EXPORT int fchmod(int fd, mode_t mode) { FD_WRITE(fd, R(fchmodat)(AT_FDCWD, p, mode, 0), R(fchmod)(fd, mode)); }

EXPORT int chmod(const char *path, mode_t mode)
{
    RESOLVE(AT_FDCWD, path, R(chmod)(path, mode), -1);
    WRITE_PATH(p, 1, 1);
    return R(fchmodat)(AT_FDCWD, p, mode, 0);
}

EXPORT int fchmodat(int dirfd, const char *path, mode_t mode, int flags)
{
    RESOLVE(dirfd, path, R(fchmodat)(dirfd, path, mode, flags), -1);
    WRITE_PATH(p, !(flags & AT_SYMLINK_NOFOLLOW), 1);
    return R(fchmodat)(AT_FDCWD, p, mode, flags);
}

EXPORT int utimensat(int dirfd, const char *path, const struct timespec ts[2], int flags)
{
    if (!path) {
        FD_WRITE(dirfd, R(utimensat)(AT_FDCWD, p, ts, 0), R(utimensat)(dirfd, path, ts, flags));
    }
    RESOLVE(dirfd, path, R(utimensat)(dirfd, path, ts, flags), -1);
    WRITE_PATH(p, !(flags & AT_SYMLINK_NOFOLLOW), 1);
    return R(utimensat)(AT_FDCWD, p, ts, flags);
}

EXPORT int truncate(const char *path, off_t len)
{
    RESOLVE(AT_FDCWD, path, R(truncate)(path, len), -1);
    WRITE_PATH(p, 1, len != 0);
    return R(truncate)(p, len);
}

EXPORT int unlink(const char *path)
{
    RESOLVE(AT_FDCWD, path, R(unlink)(path), -1);
    return gno_unlink(&r_);
}

EXPORT int rmdir(const char *path)
{
    RESOLVE(AT_FDCWD, path, R(rmdir)(path), -1);
    return gno_rmdir(&r_);
}

EXPORT int unlinkat(int dirfd, const char *path, int flags)
{
    RESOLVE(dirfd, path, R(unlinkat)(dirfd, path, flags), -1);
    return (flags & AT_REMOVEDIR) ? gno_rmdir(&r_) : gno_unlink(&r_);
}

EXPORT int mkdir(const char *path, mode_t mode)
{
    RESOLVE(AT_FDCWD, path, R(mkdir)(path, mode), -1);
    return gno_mkdir(&r_, mode);
}

EXPORT int mkdirat(int dirfd, const char *path, mode_t mode)
{
    RESOLVE(dirfd, path, R(mkdirat)(dirfd, path, mode), -1);
    return gno_mkdir(&r_, mode);
}

enum { RN_RENAME, RN_RENAMEAT, RN_RENAMEAT2 };

static int real_rename2(int which, int ofd, const char *o, int nfd, const char *n, unsigned flags)
{
    if (which == RN_RENAME && ofd == AT_FDCWD && nfd == AT_FDCWD && R(rename)) return real_rename(o, n);
    if (which != RN_RENAMEAT2 && !flags && R(renameat)) return real_renameat(ofd, o, nfd, n);
    if (R(renameat2)) return real_renameat2(ofd, o, nfd, n, flags);
    return (int)SC(__NR_renameat2, ofd, o, nfd, n, flags, 0);
}

static int do_rename(int which, int ofd, const char *o, int nfd, const char *n, unsigned flags)
{
    if (!gno_active() || (o && o[0] == '/' && !gno_candidate(o) && n && n[0] == '/' && !gno_candidate(n)))
        return real_rename2(which, ofd, o, nfd, n, flags);
    gno_res *rs = malloc(2 * sizeof(gno_res));
    if (!rs) {
        errno = ENOMEM;
        return -1;
    }
    int ka = gno_resolve_abs(ofd, o, &rs[0]);
    int kb = ka < 0 ? -1 : gno_resolve_abs(nfd, n, &rs[1]);
    int ret;
    if (ka < 0 || kb < 0) {
        ret = -1;
    } else if (ka == GNO_PASS && kb == GNO_PASS) {
        ret = real_rename2(which, ofd, o, nfd, n, flags);
    } else if (flags & ~3u) {
        errno = EINVAL;
        ret = -1;
    } else {
        unsigned gf = ((flags & 1) ? GNO_RENAME_NOREPLACE : 0) | ((flags & 2) ? GNO_RENAME_EXCHANGE : 0);
        ret = gno_rename(&rs[0], &rs[1], gf);
    }
    int e = errno;
    free(rs);
    errno = e;
    return ret;
}

EXPORT int rename(const char *o, const char *n) { return do_rename(RN_RENAME, AT_FDCWD, o, AT_FDCWD, n, 0); }
EXPORT int renameat(int ofd, const char *o, int nfd, const char *n) { return do_rename(RN_RENAMEAT, ofd, o, nfd, n, 0); }
EXPORT int renameat2(int ofd, const char *o, int nfd, const char *n, unsigned flags)
{
    return do_rename(RN_RENAMEAT2, ofd, o, nfd, n, flags);
}

EXPORT int symlink(const char *target, const char *path)
{
    RESOLVE(AT_FDCWD, path, R(symlink)(target, path), -1);
    return gno_symlink(target, &r_);
}

EXPORT int symlinkat(const char *target, int dirfd, const char *path)
{
    RESOLVE(dirfd, path, R(symlinkat)(target, dirfd, path), -1);
    return gno_symlink(target, &r_);
}

static int do_link(int ofd, const char *o, int nfd, const char *n, int flags)
{
    if (!gno_active() || (o && o[0] == '/' && !gno_candidate(o) && n && n[0] == '/' && !gno_candidate(n)))
        return R(linkat)(ofd, o, nfd, n, flags);
    gno_res *rs = malloc(2 * sizeof(gno_res));
    if (!rs) {
        errno = ENOMEM;
        return -1;
    }
    int ka = gno_resolve_abs(ofd, o, &rs[0]);
    int kb = ka < 0 ? -1 : gno_resolve_abs(nfd, n, &rs[1]);
    int ret;
    if (ka < 0 || kb < 0) ret = -1;
    else if (ka == GNO_PASS && kb == GNO_PASS) ret = R(linkat)(ofd, o, nfd, n, flags);
    else if (ka == GNO_IN && (flags & AT_SYMLINK_FOLLOW) && gno_follow(&rs[0], rs[0].path) == 1) {
        rs[0].kind = GNO_PASS;
        ret = gno_link(&rs[0], &rs[1]);
    } else ret = gno_link(&rs[0], &rs[1]);
    int e = errno;
    free(rs);
    errno = e;
    return ret;
}

EXPORT int link(const char *o, const char *n) { return do_link(AT_FDCWD, o, AT_FDCWD, n, 0); }
EXPORT int linkat(int ofd, const char *o, int nfd, const char *n, int flags) { return do_link(ofd, o, nfd, n, flags); }

EXPORT DIR *opendir(const char *path)
{
    RESOLVE(AT_FDCWD, path, R(opendir)(path), NULL);
    char out[PM];
    int k = gno_follow(&r_, out);
    if (k < 0) return NULL;
    if (k == 1) return R(opendir)(out);
    return (DIR *)gno_opendir(&r_, -1);
}

EXPORT DIR *fdopendir(int fd)
{
    if (!gno_active()) return R(fdopendir)(fd);
    char p[PM];
    ssize_t l = op_fd_path(fd, p, sizeof(p) - 1);
    if (l <= 0) return R(fdopendir)(fd);
    p[l] = 0;
    gno_res *r = malloc(sizeof(*r));
    if (!r) return R(fdopendir)(fd);
    DIR *d;
    if (gno_resolve(AT_FDCWD, p, r) != GNO_IN) {
        d = R(fdopendir)(fd);
    } else {
        struct stat st;
        if (op_fstat(fd, &st) < 0) d = NULL;
        else if (!S_ISDIR(st.st_mode)) {
            errno = ENOTDIR;
            d = NULL;
        } else {
            d = (DIR *)gno_opendir(r, fd);
        }
    }
    int e = errno;
    free(r);
    errno = e;
    return d;
}

EXPORT struct dirent *readdir(DIR *d)
{
    if (gno_is_dir(d)) return gno_readdir((gno_dir *)d);
    return R(readdir)(d);
}

EXPORT struct dirent64 *readdir64(DIR *d)
{
    if (gno_is_dir(d)) return (struct dirent64 *)gno_readdir((gno_dir *)d);
    return R(readdir64)(d);
}

EXPORT int readdir_r(DIR *d, struct dirent *entry, struct dirent **result)
{
    if (!gno_is_dir(d)) return R(readdir_r)(d, entry, result);
    struct dirent *e = gno_readdir((gno_dir *)d);
    if (e) {
        memcpy(entry, e, sizeof(*e));
        *result = entry;
    } else {
        *result = NULL;
    }
    return 0;
}

EXPORT int readdir64_r(DIR *d, struct dirent64 *entry, struct dirent64 **result)
{
    if (!gno_is_dir(d)) return R(readdir64_r)(d, entry, result);
    return readdir_r(d, (struct dirent *)entry, (struct dirent **)result);
}

EXPORT void rewinddir(DIR *d)
{
    if (gno_is_dir(d)) gno_rewinddir((gno_dir *)d);
    else R(rewinddir)(d);
}

EXPORT long telldir(DIR *d)
{
    if (gno_is_dir(d)) return gno_telldir((gno_dir *)d);
    return R(telldir)(d);
}

EXPORT void seekdir(DIR *d, long pos)
{
    if (gno_is_dir(d)) gno_seekdir((gno_dir *)d, pos);
    else R(seekdir)(d, pos);
}

EXPORT int closedir(DIR *d)
{
    if (gno_is_dir(d)) return gno_closedir((gno_dir *)d);
    return R(closedir)(d);
}

EXPORT int dirfd(DIR *d)
{
    if (gno_is_dir(d)) return gno_dirfd((gno_dir *)d);
    return R(dirfd)(d);
}

EXPORT int scandir(const char *path, struct dirent ***list, scandir_filter_t filter, scandir_cmp_t cmp)
{
    RESOLVE(AT_FDCWD, path, R(scandir)(path, list, filter, cmp), -1);
    char out[PM];
    int k = gno_follow(&r_, out);
    if (k < 0) return -1;
    if (k == 1) return R(scandir)(out, list, filter, cmp);
    gno_dir *d = gno_opendir(&r_, -1);
    if (!d) return -1;
    struct dirent **v = NULL, *e;
    size_t n = 0, cap = 0;
    while ((e = gno_readdir(d))) {
        if (filter && !filter(e)) continue;
        if (n == cap) {
            size_t nc = cap ? cap * 2 : 32;
            struct dirent **nv = realloc(v, nc * sizeof(*nv));
            if (!nv) goto nomem;
            v = nv;
            cap = nc;
        }
        struct dirent *c = malloc(sizeof(*c));
        if (!c) goto nomem;
        memcpy(c, e, sizeof(*c));
        v[n++] = c;
    }
    gno_closedir(d);
    if (cmp && n > 1) qsort(v, n, sizeof(*v), (int (*)(const void *, const void *))cmp);
    *list = v;
    return (int)n;
nomem:
    for (size_t i = 0; i < n; i++) free(v[i]);
    free(v);
    gno_closedir(d);
    errno = ENOMEM;
    return -1;
}

EXPORT int chdir(const char *path)
{
    RESOLVE(AT_FDCWD, path, R(chdir)(path), -1);
    READ_PATH(p, 1, -1);
    return R(chdir)(p);
}

EXPORT char *realpath(const char *path, char *resolved)
{
    RESOLVE(AT_FDCWD, path, R(realpath)(path, resolved), NULL);
    char p[PM];
    if (gno_realpath(&r_, p) < 0) return NULL;
    if (resolved) {
        strcpy(resolved, p);
        return resolved;
    }
    return strdup(p);
}

EXPORT void *dlsym(void *handle, const char *name)
{
    const void *caller = __builtin_return_address(0);
    ensure_reals();
    void *r;
    if (loader_dlsym) r = loader_dlsym(handle, name, caller);
    else if (real_dlsym) r = real_dlsym(handle, name);
    else return NULL;
    return gno_dlsym_pick(handle == RTLD_NEXT, name, r, g_names, g_hookp, g_realp, NHOOKS);
}
