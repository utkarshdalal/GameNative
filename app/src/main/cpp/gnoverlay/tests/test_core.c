#include "../gnoverlay_core.h"

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <unistd.h>
#ifdef __APPLE__
#include <sys/xattr.h>
#else
#include <sys/syscall.h>
#include <sys/xattr.h>
#endif

static int g_fail, g_pass;
static char ROOT[GNO_PATH_MAX], U[GNO_PATH_MAX], L[GNO_PATH_MAX], A[GNO_PATH_MAX];

#define CHECK(c)                                                                  \
    do {                                                                          \
        if (c) g_pass++;                                                          \
        else {                                                                    \
            g_fail++;                                                             \
            fprintf(stderr, "FAIL %s:%d: %s (errno=%s)\n", __FILE__, __LINE__, #c, \
                    strerror(errno));                                             \
        }                                                                         \
    } while (0)

static int o_open(const char *p, int f, mode_t m) { return open(p, f, m); }
static int o_rename_exchange(const char *a, const char *b)
{
#ifdef __APPLE__
    return renamex_np(a, b, RENAME_SWAP);
#else
    return (int)syscall(SYS_renameat2, AT_FDCWD, a, AT_FDCWD, b, 2);
#endif
}
#ifdef __APPLE__
static ssize_t o_flistxattr(int fd, char *l, size_t n) { return flistxattr(fd, l, n, 0); }
static ssize_t o_fgetxattr(int fd, const char *nm, void *v, size_t n) { return fgetxattr(fd, nm, v, n, 0, 0); }
static int o_fsetxattr(int fd, const char *nm, const void *v, size_t n, int f)
{
    return fsetxattr(fd, nm, v, n, 0, f);
}
static ssize_t o_fd_path(int fd, char *buf, size_t n)
{
    char tmp[1024];
    if (fcntl(fd, F_GETPATH, tmp) < 0) return -1;
    size_t l = strlen(tmp);
    if (l >= n) return -1;
    memcpy(buf, tmp, l);
    return (ssize_t)l;
}
#else
static ssize_t o_flistxattr(int fd, char *l, size_t n) { return flistxattr(fd, l, n); }
static ssize_t o_fgetxattr(int fd, const char *nm, void *v, size_t n) { return fgetxattr(fd, nm, v, n); }
static int o_fsetxattr(int fd, const char *nm, const void *v, size_t n, int f) { return fsetxattr(fd, nm, v, n, f); }
static ssize_t o_fd_path(int fd, char *buf, size_t n)
{
    char p[64];
    snprintf(p, sizeof(p), "/proc/self/fd/%d", fd);
    return readlink(p, buf, n);
}
#endif

static char g_race_up[GNO_PATH_MAX];
static int g_link_refuse;
static int count_tmp(const char *dir);
static int g_link_calls;

static int o_link(const char *a, const char *b)
{
    g_link_calls++;
    if (g_link_refuse) {
        g_link_refuse = 0;
        errno = EPERM;
        return -1;
    }
    if (g_race_up[0] && !strcmp(b, g_race_up)) {
        int fd = open(b, O_WRONLY | O_CREAT | O_EXCL, 0644);
        if (fd >= 0) {
            write(fd, "winner", 6);
            close(fd);
        }
        g_race_up[0] = 0;
    }
    return link(a, b);
}

static int o_rename_noreplace(const char *a, const char *b)
{
#ifdef __APPLE__
    return renamex_np(a, b, RENAME_EXCL);
#else
    return (int)syscall(SYS_renameat2, AT_FDCWD, a, AT_FDCWD, b, 1);
#endif
}

static gno_ops make_ops(void)
{
    gno_ops o = {0};
    o.open = o_open;
    o.close = close;
    o.read = read;
    o.write = write;
    o.fstat = fstat;
    o.stat = stat;
    o.lstat = lstat;
    o.mkdir = mkdir;
    o.rmdir = rmdir;
    o.unlink = unlink;
    o.rename = rename;
    o.rename_exchange = o_rename_exchange;
    o.readlink = readlink;
    o.symlink = symlink;
    o.link = o_link;
    o.rename_noreplace = o_rename_noreplace;
    o.fchmod = fchmod;
    o.futimens = futimens;
    o.opendir = opendir;
    o.readdir = readdir;
    o.closedir = closedir;
    o.flistxattr = o_flistxattr;
    o.fgetxattr = o_fgetxattr;
    o.fsetxattr = o_fsetxattr;
    o.getcwd = getcwd;
    o.fd_path = o_fd_path;
    return o;
}

static char *P(const char *base, const char *rel)
{
    static char bufs[8][GNO_PATH_MAX];
    static int i;
    char *b = bufs[i++ & 7];
    snprintf(b, GNO_PATH_MAX, "%s/%s", base, rel);
    return b;
}

static void put(const char *path, const char *data)
{
    char tmp[GNO_PATH_MAX];
    snprintf(tmp, sizeof(tmp), "%s", path);
    for (char *s = tmp + 1; *s; s++) {
        if (*s == '/') {
            *s = 0;
            mkdir(tmp, 0755);
            *s = '/';
        }
    }
    FILE *f = fopen(path, "w");
    fputs(data, f);
    fclose(f);
}

static int exists(const char *p)
{
    struct stat st;
    return lstat(p, &st) == 0;
}

static int h_open(const char *p, int flags, mode_t mode)
{
    gno_res r;
    int k = gno_resolve(AT_FDCWD, p, &r);
    if (k == GNO_PASS) return open(p, flags, mode);
    if (k < 0) return -1;
    char real[GNO_PATH_MAX];
    int created, isdir;
    if (gno_prepare_open(&r, flags, real, &created, &isdir) < 0) return -1;
    int fd = open(real, flags, mode);
    if (fd >= 0 && created) gno_finish_create(&r);
    return fd;
}

static char *h_read(const char *p)
{
    static char buf[4096];
    int fd = h_open(p, O_RDONLY, 0);
    if (fd < 0) return NULL;
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    buf[n < 0 ? 0 : n] = 0;
    return buf;
}

static int h_write(const char *p, int flags, const char *data)
{
    int fd = h_open(p, flags, 0644);
    if (fd < 0) return -1;
    ssize_t n = write(fd, data, strlen(data));
    close(fd);
    return n < 0 ? -1 : 0;
}

static int h_stat(const char *p, struct stat *st, int follow)
{
    gno_res r;
    int k = gno_resolve(AT_FDCWD, p, &r);
    if (k == GNO_PASS) return follow ? stat(p, st) : lstat(p, st);
    if (k < 0) return -1;
    char real[GNO_PATH_MAX];
    if (gno_read_path(&r, follow, real) < 0) return -1;
    return follow ? stat(real, st) : lstat(real, st);
}

#define PATHOP(name, fn)                                           \
    static int name(const char *p)                                 \
    {                                                              \
        gno_res r;                                                 \
        int k = gno_resolve(AT_FDCWD, p, &r);                   \
        if (k < 0) return -1;                                      \
        if (k != GNO_IN) return -99;                               \
        return fn(&r);                                             \
    }
PATHOP(h_unlink, gno_unlink)
PATHOP(h_rmdir, gno_rmdir)

static int h_mkdir(const char *p, mode_t m)
{
    gno_res r;
    int k = gno_resolve(AT_FDCWD, p, &r);
    if (k < 0) return -1;
    return gno_mkdir(&r, m);
}

static int h_rename(const char *a, const char *b, unsigned flags)
{
    static gno_res ra, rb;
    if (gno_resolve_abs(AT_FDCWD, a, &ra) < 0 || gno_resolve_abs(AT_FDCWD, b, &rb) < 0) return -1;
    return gno_rename(&ra, &rb, flags);
}

static int cmpstr(const void *a, const void *b) { return strcmp(*(char *const *)a, *(char *const *)b); }

static char *h_list(const char *p)
{
    static char out[8192];
    gno_res r;
    out[0] = 0;
    if (gno_resolve(AT_FDCWD, p, &r) != GNO_IN) return NULL;
    gno_dir *d = gno_opendir(&r, -1);
    if (!d) return NULL;
    char *names[512];
    int n = 0;
    struct dirent *de;
    while ((de = gno_readdir(d)) && n < 512) {
        if (!strcmp(de->d_name, ".") || !strcmp(de->d_name, "..")) continue;
        names[n++] = strdup(de->d_name);
    }
    gno_closedir(d);
    qsort(names, n, sizeof(char *), cmpstr);
    for (int i = 0; i < n; i++) {
        if (i) strcat(out, ",");
        strcat(out, names[i]);
        free(names[i]);
    }
    return out;
}

static void setup(void)
{
    const char *base = getenv("GNO_TEST_TMP");
    char tmpl[GNO_PATH_MAX];
    snprintf(tmpl, sizeof(tmpl), "%s/gnotest.XXXXXX", base && *base ? base : "/tmp");
    if (!mkdtemp(tmpl)) {
        perror("mkdtemp");
        exit(2);
    }
    realpath(tmpl, ROOT);
    snprintf(U, sizeof(U), "%s/upper/.wine", ROOT);
    snprintf(L, sizeof(L), "%s/lower/.wine", ROOT);
    snprintf(A, sizeof(A), "%s/alias/.wine", ROOT);
    put(P(U, "user.reg"), "upper-user");
    put(P(L, "user.reg"), "lower-user");
    put(P(L, "system.reg"), "lower-system");
    put(P(L, "drive_c/windows/win.ini"), "lower-winini");
    put(P(L, "drive_c/windows/system32/a.dll"), "lower-a-dll");
    put(P(L, "drive_c/windows/system32/b.dll"), "lower-b-dll");
    put(P(L, "dir1/x"), "x");
    put(P(L, "dir1/y"), "y");
    put(P(L, "dir2/sub/f"), "lower-f");
    put(P(L, "dir4/keep"), "k");
    mkdir(P(L, "dosdevices"), 0755);
    symlink("../drive_c", P(L, "dosdevices/c:"));
    symlink("/", P(L, "dosdevices/z:"));
    symlink("../dir4", P(L, "dosdevices/d:"));
    mkdir(P(U, "dosdevices"), 0755);
    symlink("../drive_c", P(U, "dosdevices/c:"));
    symlink("/", P(U, "dosdevices/z:"));
    chmod(P(L, "drive_c/windows/system32/a.dll"), 0640);
    struct timeval tv[2] = {{1000000000, 0}, {1100000000, 0}};
    utimes(P(L, "drive_c/windows/system32/a.dll"), tv);
#ifdef __APPLE__
    setxattr(P(L, "drive_c/windows/system32/a.dll"), "user.test", "val", 3, 0, 0);
#else
    setxattr(P(L, "drive_c/windows/system32/a.dll"), "user.test", "val", 3, 0);
#endif
    mkdir(P(ROOT, "alias"), 0755);
    symlink(U, A);
}

static void test_normalize(void)
{
    char out[GNO_PATH_MAX];
    CHECK(gno_normalize(AT_FDCWD, "/data/data/app.gamenative/x/../y//z/.", out, sizeof(out)) == 0);
    CHECK(!strcmp(out, "/data/user/0/app.gamenative/y/z"));
    CHECK(gno_normalize(AT_FDCWD, "/a/../../b", out, sizeof(out)) == 0 && !strcmp(out, "/b"));
    CHECK(gno_normalize(AT_FDCWD, "/data/database", out, sizeof(out)) == 0 && !strcmp(out, "/data/database"));
    CHECK(!gno_candidate("/system/lib64/libc.so"));
    CHECK(gno_candidate(P(U, "x")));
    CHECK(gno_candidate("/data/data/app.gamenative/files/imagefs/home/xuser/.wine/x"));
    CHECK(gno_candidate("/x/../y"));
    CHECK(gno_candidate("relative"));
    gno_res r;
    CHECK(gno_resolve(AT_FDCWD, "/data/data/app.gamenative/files/imagefs/home/xuser/.wine/drive_c/windows/win.ini", &r) == GNO_IN);
    CHECK(!strcmp(r.path, "drive_c/windows/win.ini"));
    CHECK(gno_resolve(AT_FDCWD, "/data/user/0/app.gamenative/files/imagefs/home/xuser/.wine", &r) == GNO_IN && r.path[0] == 0);
    CHECK(gno_resolve(AT_FDCWD, "/data/user/0/app.gamenative/files/imagefs/home/xuser/.wine2/x", &r) == GNO_PASS);
    CHECK(gno_resolve(AT_FDCWD, P(A, "user.reg"), &r) == GNO_IN && !strcmp(r.path, "user.reg"));
    CHECK(!strcmp(h_read("/data/data/app.gamenative/files/imagefs/home/xuser/.wine/system.reg"), "lower-system"));
}

static void test_lookup_and_symlinks(void)
{
    CHECK(!strcmp(h_read(P(U, "user.reg")), "upper-user"));
    CHECK(!strcmp(h_read(P(U, "system.reg")), "lower-system"));
    CHECK(!strcmp(h_read(P(U, "drive_c/windows/win.ini")), "lower-winini"));
    CHECK(!exists(P(U, "drive_c")));
    gno_res r;
    CHECK(gno_resolve(AT_FDCWD, P(U, "dosdevices/c:/windows/system32/a.dll"), &r) == GNO_IN);
    CHECK(!strcmp(r.path, "dosdevices/c:/windows/system32/a.dll"));
    CHECK(!strcmp(h_read(P(U, "dosdevices/c:/windows/system32/a.dll")), "lower-a-dll"));
    CHECK(!strcmp(h_read(P(U, "dosdevices/d:/keep")), "k"));
    char out[GNO_PATH_MAX];
    CHECK(gno_resolve(AT_FDCWD, P(U, "dosdevices/c:"), &r) == GNO_IN && gno_follow(&r, out) == 0 && !strcmp(r.path, "drive_c"));
    CHECK(gno_resolve(AT_FDCWD, P(U, "dosdevices/z:"), &r) == GNO_IN && gno_follow(&r, out) == 1 && !strcmp(out, "/"));
    CHECK(gno_resolve(AT_FDCWD, P(U, "dosdevices/z:/etc/hosts"), &r) == GNO_IN && gno_read_path(&r, 1, out) == 0 &&
          !strcmp(out, P(U, "dosdevices/z:/etc/hosts")));
    struct stat st;
    CHECK(h_stat(P(U, "dosdevices/c:"), &st, 0) == 0 && S_ISLNK(st.st_mode));
    CHECK(h_stat(P(U, "dosdevices/c:"), &st, 1) == 0 && S_ISDIR(st.st_mode));
    CHECK(h_stat(P(U, "dosdevices/z:/etc"), &st, 1) == 0 && S_ISDIR(st.st_mode));
    CHECK(h_stat(P(U, "nope/x"), &st, 1) < 0 && errno == ENOENT);
    CHECK(h_stat(P(U, "system.reg/x"), &st, 1) < 0 && (errno == ENOENT || errno == ENOTDIR));
    CHECK(h_stat(P(U, "user.reg/x"), &st, 1) < 0 && errno == ENOTDIR);
    CHECK(gno_resolve(AT_FDCWD, P(U, ".gnoverlay/x"), &r) == GNO_PASS);
    CHECK(gno_resolve(AT_FDCWD, P(A, ".gnoverlay/x"), &r) < 0 && errno == ENOENT);
    CHECK(gno_resolve(AT_FDCWD, P(L, "system.reg"), &r) == GNO_IN && !strcmp(r.path, "system.reg"));

    char cwd[GNO_PATH_MAX];
    getcwd(cwd, sizeof(cwd));
    chdir(P(L, "drive_c"));
    CHECK(gno_resolve(AT_FDCWD, "windows/../windows/win.ini", &r) == GNO_IN && !strcmp(r.path, "drive_c/windows/win.ini"));
    chdir(cwd);
    char rp[GNO_PATH_MAX];
    CHECK(gno_resolve(AT_FDCWD, P(A, "dosdevices/c:/windows"), &r) == GNO_IN && gno_realpath(&r, rp) == 0 &&
          !strcmp(rp, P(U, "drive_c/windows")));
}

static void test_dosdevices_markers(void)
{
    struct stat st;
    put(P(L, "drive_c/windows/system32/e.dll"), "lower-e");
    CHECK(h_write(P(U, "dosdevices/c:/windows/system32/viac.dll"), O_WRONLY | O_CREAT, "v") == 0);
    CHECK(exists(P(U, "drive_c/windows/system32/viac.dll")));
    CHECK(lstat(P(U, "dosdevices/c:"), &st) == 0 && S_ISLNK(st.st_mode));
    CHECK(h_unlink(P(U, "dosdevices/c:/windows/system32/e.dll")) == 0);
    CHECK(exists(P(U, ".gnoverlay/wh/drive_c/windows/system32/e.dll")));
    CHECK(h_stat(P(U, "drive_c/windows/system32/e.dll"), &st, 1) < 0 && errno == ENOENT);
    CHECK(h_stat(P(U, "dosdevices/c:/windows/system32/e.dll"), &st, 1) < 0 && errno == ENOENT);
    char *l = h_list(P(U, "dosdevices/c:/windows/system32"));
    CHECK(l && !strstr(l, "e.dll") && strstr(l, "viac.dll"));
    CHECK(h_write(P(U, "drive_c/windows/system32/e.dll"), O_WRONLY | O_CREAT, "B") == 0);
    CHECK(!exists(P(U, ".gnoverlay/wh/drive_c/windows/system32/e.dll")));
    CHECK(!strcmp(h_read(P(U, "dosdevices/c:/windows/system32/e.dll")), "B"));
    CHECK(h_unlink(P(U, "dosdevices/c:/windows/system32/e.dll")) == 0);
    CHECK(h_unlink(P(U, "dosdevices/c:/windows/system32/viac.dll")) == 0);
    CHECK(h_stat(P(U, "drive_c/windows/system32/e.dll"), &st, 1) < 0 && errno == ENOENT);
    unlink(P(L, "drive_c/windows/system32/e.dll"));
    unlink(P(U, ".gnoverlay/wh/drive_c/windows/system32/e.dll"));

    CHECK(h_write(P(U, "dosdevices/d:/viad"), O_WRONLY | O_CREAT, "d") == 0);
    CHECK(lstat(P(U, "dosdevices/d:"), &st) == 0 && S_ISLNK(st.st_mode));
    CHECK(exists(P(U, "dir4/viad")) && exists(P(L, "dir4/keep")));
    CHECK(!strcmp(h_read(P(U, "dosdevices/d:/keep")), "k"));
    CHECK(h_unlink(P(U, "dir4/viad")) == 0);

    symlink("loop2", P(U, "loop1"));
    symlink("loop1", P(U, "loop2"));
    gno_res r;
    char out[GNO_PATH_MAX];
    CHECK(gno_resolve(AT_FDCWD, P(U, "loop1"), &r) == GNO_IN && gno_follow(&r, out) < 0 && errno == ELOOP);
    unlink(P(U, "loop1"));
    unlink(P(U, "loop2"));
}

static void test_copy_up(void)
{
    const char *a = P(U, "dosdevices/c:/windows/system32/a.dll");
    int fd = h_open(a, O_RDONLY, 0);
    CHECK(fd >= 0);
    close(fd);
    CHECK(!exists(P(U, "drive_c/windows/system32/a.dll")));
    fd = h_open(a, O_RDWR, 0);
    CHECK(fd >= 0);
    CHECK(write(fd, "U", 1) == 1);
    close(fd);
    struct stat us, ls;
    CHECK(lstat(P(U, "drive_c/windows/system32/a.dll"), &us) == 0);
    CHECK(lstat(P(L, "drive_c/windows/system32/a.dll"), &ls) == 0);
    CHECK((us.st_mode & 07777) == 0640);
    CHECK(!strcmp(h_read(a), "Uower-a-dll"));
    CHECK(!strcmp(h_read(P(L, "drive_c/windows/system32/a.dll")), "Uower-a-dll"));
    FILE *f = fopen(P(L, "drive_c/windows/system32/a.dll"), "r");
    char buf[64] = {0};
    fread(buf, 1, sizeof(buf) - 1, f);
    fclose(f);
    CHECK(!strcmp(buf, "lower-a-dll"));
    char v[16] = {0};
#ifdef __APPLE__
    CHECK(getxattr(P(U, "drive_c/windows/system32/a.dll"), "user.test", v, sizeof(v), 0, 0) == 3);
#else
    CHECK(getxattr(P(U, "drive_c/windows/system32/a.dll"), "user.test", v, sizeof(v)) == 3);
#endif
    CHECK(!strcmp(v, "val"));
    CHECK(count_tmp(P(U, "drive_c/windows/system32")) == 0);

    put(P(L, "mt"), "mtime");
    struct timeval tv[2] = {{1200000000, 0}, {1300000000, 0}};
    utimes(P(L, "mt"), tv);
    gno_res r;
    char up[GNO_PATH_MAX];
    CHECK(gno_resolve(AT_FDCWD, P(U, "mt"), &r) == GNO_IN && gno_materialize(&r, 0, 1, up) == 0);
    CHECK(lstat(up, &us) == 0 && us.st_mtime == 1300000000 && us.st_size == 5);

    put(P(L, "sealed.dll"), "sealed");
    chmod(P(L, "sealed.dll"), 0444);
    fd = h_open(P(U, "sealed.dll"), O_WRONLY | O_APPEND, 0);
    CHECK(fd >= 0 && write(fd, "+w", 2) == 2);
    close(fd);
    CHECK(lstat(P(U, "sealed.dll"), &us) == 0 && (us.st_mode & 07777) == 0644);
    CHECK(lstat(P(L, "sealed.dll"), &ls) == 0 && (ls.st_mode & 07777) == 0444);
    fd = h_open(P(U, "sealed.dll"), O_RDWR, 0);
    CHECK(fd >= 0);
    close(fd);
    CHECK(!strcmp(h_read(P(U, "sealed.dll")), "sealed+w"));
    put(P(L, "sealed2.dll"), "s2");
    chmod(P(L, "sealed2.dll"), 0444);
    CHECK(h_write(P(U, "sealed2.dll"), O_WRONLY | O_TRUNC, "t") == 0);
    CHECK(!strcmp(h_read(P(U, "sealed2.dll")), "t"));
    mkdir(P(L, "sealeddir"), 0755);
    put(P(L, "sealeddir/f"), "f");
    chmod(P(L, "sealeddir"), 0555);
    CHECK(h_write(P(U, "sealeddir/new"), O_WRONLY | O_CREAT, "n") == 0);
    CHECK(lstat(P(U, "sealeddir"), &us) == 0 && (us.st_mode & 0700) == 0700);
    chmod(P(L, "sealeddir"), 0755);

    CHECK(h_write(P(U, "drive_c/windows/win.ini"), O_WRONLY | O_TRUNC, "new") == 0);
    CHECK(!strcmp(h_read(P(U, "drive_c/windows/win.ini")), "new"));
    CHECK(lstat(P(L, "drive_c/windows/win.ini"), &ls) == 0 && ls.st_size == 12);

    CHECK(h_write(P(U, "system.reg"), O_WRONLY | O_APPEND, "+app") == 0);
    CHECK(!strcmp(h_read(P(U, "system.reg")), "lower-system+app"));

    CHECK(h_open(P(U, "drive_c/windows/system32/b.dll"), O_WRONLY | O_CREAT | O_EXCL, 0644) < 0 && errno == EEXIST);
    CHECK(h_open(P(U, "user.reg"), O_WRONLY | O_CREAT | O_EXCL, 0644) < 0 && errno == EEXIST);
    CHECK(h_write(P(U, "drive_c/windows/system32/new.dll"), O_WRONLY | O_CREAT | O_EXCL, "n") == 0);
    CHECK(!strcmp(h_read(P(U, "drive_c/windows/system32/new.dll")), "n"));
    CHECK(h_open(P(U, "nodir/new"), O_WRONLY | O_CREAT, 0644) < 0 && errno == ENOENT);
    CHECK(h_open(P(U, "missing"), O_WRONLY, 0644) < 0 && errno == ENOENT);
    CHECK(h_open(P(U, "drive_c/windows/system32/b.dll"), O_RDONLY | O_CREAT, 0644) >= 0);
    CHECK(!exists(P(U, "drive_c/windows/system32/b.dll")));

    fd = h_open(P(L, "dir4/keep"), O_RDONLY, 0);
    CHECK(fd >= 0);
    CHECK(gno_fd_lower_copyup(fd, up) == 1 && !strcmp(up, P(U, "dir4/keep")) && exists(up));
    close(fd);
    fd = open(P(U, "user.reg"), O_RDONLY);
    CHECK(gno_fd_lower_copyup(fd, up) == 0);
    close(fd);

    gno_res ra, rb;
    CHECK(gno_resolve_abs(AT_FDCWD, P(U, "drive_c/windows/system32/b.dll"), &ra) == GNO_IN);
    CHECK(gno_resolve_abs(AT_FDCWD, P(U, "drive_c/windows/system32/b2.dll"), &rb) == GNO_IN);
    CHECK(gno_link(&ra, &rb) == 0);
    CHECK(lstat(P(U, "drive_c/windows/system32/b.dll"), &us) == 0 && us.st_nlink == 2);
    CHECK(!strcmp(h_read(P(U, "drive_c/windows/system32/b2.dll")), "lower-b-dll"));
}

static void test_whiteouts(void)
{
    struct stat st;
    CHECK(h_unlink(P(U, "drive_c/windows/system32/b2.dll")) == 0);
    CHECK(h_unlink(P(U, "dir1/x")) == 0);
    CHECK(exists(P(U, ".gnoverlay/wh/dir1/x")));
    CHECK(h_stat(P(U, "dir1/x"), &st, 1) < 0 && errno == ENOENT);
    CHECK(exists(P(L, "dir1/x")));
    CHECK(!strcmp(h_list(P(U, "dir1")), "y"));
    CHECK(h_unlink(P(U, "dir1/x")) < 0 && errno == ENOENT);
    CHECK(h_rmdir(P(U, "dir1")) < 0 && errno == ENOTEMPTY);
    CHECK(h_write(P(U, "dir1/x"), O_WRONLY | O_CREAT | O_TRUNC, "again") == 0);
    CHECK(!exists(P(U, ".gnoverlay/wh/dir1/x")));
    CHECK(!strcmp(h_read(P(U, "dir1/x")), "again"));
    CHECK(h_unlink(P(U, "dir1/x")) == 0);
    CHECK(!exists(P(U, "dir1/x")) && exists(P(U, ".gnoverlay/wh/dir1/x")));
    CHECK(h_unlink(P(U, "dir1/y")) == 0);
    CHECK(!strcmp(h_list(P(U, "dir1")), ""));
    CHECK(h_unlink(P(U, "dir1")) < 0 && errno == EISDIR);
    CHECK(h_rmdir(P(U, "dir1")) == 0);
    CHECK(h_stat(P(U, "dir1"), &st, 1) < 0 && errno == ENOENT);
    CHECK(h_stat(P(U, "dir1/y"), &st, 1) < 0 && errno == ENOENT);
    CHECK(exists(P(U, ".gnoverlay/wh/dir1")) && lstat(P(U, ".gnoverlay/wh/dir1"), &st) == 0 && S_ISREG(st.st_mode));
    CHECK(h_open(P(U, "dir1/z"), O_WRONLY | O_CREAT, 0644) < 0 && errno == ENOENT);
    CHECK(h_mkdir(P(U, "dir1"), 0755) == 0);
    CHECK(exists(P(U, ".gnoverlay/opaque/dir1")));
    CHECK(!exists(P(U, ".gnoverlay/wh/dir1")));
    CHECK(!strcmp(h_list(P(U, "dir1")), ""));
    CHECK(h_stat(P(U, "dir1/x"), &st, 1) < 0 && errno == ENOENT);
    CHECK(h_write(P(U, "dir1/z"), O_WRONLY | O_CREAT, "z") == 0);
    CHECK(!strcmp(h_list(P(U, "dir1")), "z"));
    CHECK(h_mkdir(P(U, "dir1"), 0755) < 0 && errno == EEXIST);
    CHECK(h_mkdir(P(U, "system.reg"), 0755) < 0 && errno == EEXIST);
    CHECK(h_unlink(P(U, "user.reg")) == 0);
    CHECK(h_stat(P(U, "user.reg"), &st, 1) < 0 && errno == ENOENT);
    CHECK(!exists(P(U, "user.reg")) && exists(P(U, ".gnoverlay/wh/user.reg")));
}

static void test_rename(void)
{
    struct stat st;
    CHECK(h_rename(P(U, "system.reg"), P(U, "system.reg.bak"), 0) == 0);
    CHECK(h_stat(P(U, "system.reg"), &st, 1) < 0 && errno == ENOENT);
    CHECK(!strcmp(h_read(P(U, "system.reg.bak")), "lower-system+app"));
    CHECK(h_write(P(U, "reg.tmp"), O_WRONLY | O_CREAT | O_EXCL, "saved") == 0);
    CHECK(h_rename(P(U, "reg.tmp"), P(U, "system.reg"), 0) == 0);
    CHECK(!exists(P(U, ".gnoverlay/wh/system.reg")));
    CHECK(!strcmp(h_read(P(U, "system.reg")), "saved"));
    CHECK(h_stat(P(U, "reg.tmp"), &st, 1) < 0);

    CHECK(h_write(P(U, "drive_c/windows/win2.ini"), O_WRONLY | O_CREAT, "w2") == 0);
    CHECK(h_rename(P(U, "drive_c/windows/win2.ini"), P(U, "drive_c/windows/system32/a.dll"), GNO_RENAME_NOREPLACE) < 0 && errno == EEXIST);
    CHECK(h_rename(P(U, "dosdevices/c:/windows/system32/b.dll"), P(U, "drive_c/windows/win.ini"), 0) == 0);
    CHECK(!strcmp(h_read(P(U, "drive_c/windows/win.ini")), "lower-b-dll"));

    CHECK(h_rename(P(U, "dir2"), P(U, "dir3"), 0) == 0);
    CHECK(h_stat(P(U, "dir2"), &st, 1) < 0 && errno == ENOENT);
    CHECK(!strcmp(h_read(P(U, "dir3/sub/f")), "lower-f"));
    CHECK(exists(P(L, "dir2/sub/f")));

    CHECK(h_mkdir(P(U, "newdir"), 0755) == 0);
    CHECK(h_write(P(U, "newdir/only"), O_WRONLY | O_CREAT, "o") == 0);
    CHECK(h_rename(P(U, "newdir"), P(U, "dir2"), 0) == 0);
    CHECK(exists(P(U, ".gnoverlay/opaque/dir2")));
    CHECK(!strcmp(h_list(P(U, "dir2")), "only"));
    CHECK(h_stat(P(U, "dir2/sub"), &st, 1) < 0 && errno == ENOENT);

    CHECK(h_rename(P(U, "dir3"), P(U, "dir4"), 0) < 0 && errno == ENOTEMPTY);
    CHECK(h_rename(P(U, "dir3"), P(U, "dir3/sub/in"), 0) < 0 && errno == EINVAL);
    CHECK(h_rename(P(U, "dir3"), P(U, "system.reg"), 0) < 0 && errno == ENOTDIR);
    CHECK(h_rename(P(U, "system.reg"), P(U, "dir3"), 0) < 0 && errno == EISDIR);

    char outside[GNO_PATH_MAX];
    snprintf(outside, sizeof(outside), "%s/outside.txt", ROOT);
    CHECK(h_rename(P(U, "dir4/keep"), outside, 0) == 0);
    CHECK(!strcmp(h_read(outside), "k"));
    CHECK(h_stat(P(U, "dir4/keep"), &st, 1) < 0 && errno == ENOENT);
    CHECK(h_rename(outside, P(U, "dir4/back"), 0) == 0);
    CHECK(!strcmp(h_read(P(U, "dir4/back")), "k"));

    CHECK(h_write(P(U, "ex1"), O_WRONLY | O_CREAT, "one") == 0);
    CHECK(h_rename(P(U, "ex1"), P(U, "drive_c/windows/system32/new.dll"), GNO_RENAME_EXCHANGE) == 0);
    CHECK(!strcmp(h_read(P(U, "ex1")), "n"));
    CHECK(!strcmp(h_read(P(U, "drive_c/windows/system32/new.dll")), "one"));
}

static void test_symlink(void)
{
    gno_res r;
    CHECK(gno_resolve(AT_FDCWD, P(U, "lnk"), &r) == GNO_IN && gno_symlink("drive_c/windows", &r) == 0);
    CHECK(!strcmp(h_read(P(U, "lnk/system32/a.dll")), "Uower-a-dll"));
    char t[256] = {0};
    CHECK(readlink(P(U, "lnk"), t, sizeof(t)) > 0 && !strcmp(t, "drive_c/windows"));
    CHECK(gno_symlink("x", &r) < 0 && errno == EEXIST);
    CHECK(h_write(P(U, "lnk/system32/vialnk.dll"), O_WRONLY | O_CREAT, "l") == 0);
    CHECK(exists(P(U, "drive_c/windows/system32/vialnk.dll")));
    CHECK(gno_resolve(AT_FDCWD, P(U, "lnk"), &r) == GNO_IN && gno_unlink(&r) == 0 && !exists(P(U, "lnk")));
}

static void test_listing(void)
{
    char *root = h_list(P(U, ""));
    CHECK(root && !strstr(root, ".gnoverlay"));
    CHECK(root && strstr(root, "system.reg") && strstr(root, "drive_c") && !strstr(root, "user.reg"));
    char *sys32 = h_list(P(U, "dosdevices/c:/windows/system32"));
    CHECK(sys32 && !strcmp(sys32, "a.dll,new.dll,vialnk.dll"));

    gno_res r;
    CHECK(gno_resolve(AT_FDCWD, P(U, "drive_c/windows/system32"), &r) == GNO_IN);
    gno_dir *d = gno_opendir(&r, -1);
    CHECK(d != NULL && gno_is_dir(d));
    int n = 0, dots = 0;
    struct dirent *de;
    long mid = -1;
    char midname[256] = {0};
    while ((de = gno_readdir(d))) {
        if (!strcmp(de->d_name, ".") || !strcmp(de->d_name, "..")) dots++;
        if (n == 1) mid = gno_telldir(d);
        if (n == 2) snprintf(midname, sizeof(midname), "%s", de->d_name);
        n++;
    }
    CHECK(n == 5 && dots == 2);
    gno_seekdir(d, mid);
    de = gno_readdir(d);
    CHECK(de && !strcmp(de->d_name, midname));
    CHECK(gno_dirfd(d) >= 0);
    CHECK(h_write(P(U, "drive_c/windows/system32/c.dll"), O_WRONLY | O_CREAT, "c") == 0);
    gno_rewinddir(d);
    n = 0;
    while ((de = gno_readdir(d))) n++;
    CHECK(n == 6);
    gno_closedir(d);
    CHECK(!gno_is_dir(d));

    int fd = open(P(L, "drive_c/windows/system32"), O_RDONLY | O_DIRECTORY);
    CHECK(gno_resolve(AT_FDCWD, P(L, "drive_c/windows/system32"), &r) == GNO_IN);
    d = gno_opendir(&r, fd);
    n = 0;
    while (d && (de = gno_readdir(d))) n++;
    CHECK(n == 6);
    if (d) gno_closedir(d);
}

static void test_fontconfig_rename(gno_ops *ops)
{
    char up[GNO_PATH_MAX], lo[GNO_PATH_MAX], fc[GNO_PATH_MAX], src[GNO_PATH_MAX], dst[GNO_PATH_MAX];
    const char *home = "/data/user/0/app.gamenative/files/imagefs_shared/home/xuser-STEAM_1942280";
    const char *cache = ".cache/fontconfig/777a0abed1a003ff2f5b9a90ce906d5a-le64.cache-9";
    snprintf(up, sizeof(up), "%s/dev%s/.wine", ROOT, home);
    snprintf(lo, sizeof(lo), "%s/dev/proton/proton-9.0-arm64ec/base_prefix/.wine", ROOT);
    snprintf(src, sizeof(src), "%s/dev%s/%s.NEW", ROOT, home, cache);
    snprintf(dst, sizeof(dst), "%s/dev%s/%s", ROOT, home, cache);
    put(P(up, "system.reg"), "x");
    put(P(lo, "system.reg"), "y");
    put(src, "cache");
    snprintf(fc, sizeof(fc), "%s/dev%s/.cache/fontconfig", ROOT, home);
    char aliases[GNO_PATH_MAX * 3];
    snprintf(aliases, sizeof(aliases),
             "/data/user/0/app.gamenative/files/imagefs/home/xuser/.wine:"
             "/data/user/0/app.gamenative/files/imagefs_shared/home/xuser/.wine:/home/xuser/.wine:"
             "%s/dev/data/user/0/app.gamenative/files/imagefs/home/xuser/.wine", ROOT);
    gno_shutdown();
    CHECK(gno_init(ops, up, lo, aliases, 0, NULL) == 1);

    gno_res r;
    char dev_src[GNO_PATH_MAX], dev_dst[GNO_PATH_MAX], dd_src[GNO_PATH_MAX], out[GNO_PATH_MAX];
    snprintf(dev_src, sizeof(dev_src), "%s/%s.NEW", home, cache);
    snprintf(dev_dst, sizeof(dev_dst), "%s/%s", home, cache);
    snprintf(dd_src, sizeof(dd_src), "/data/data/app.gamenative/files/imagefs_shared/home/xuser-STEAM_1942280/%s.NEW", cache);
    gno_iters = 0;
    CHECK(gno_normalize(AT_FDCWD, dd_src, out, sizeof(out)) == 0 && !strcmp(out, dev_src));
    CHECK(gno_resolve(AT_FDCWD, dev_src, &r) == GNO_PASS);
    CHECK(gno_resolve(AT_FDCWD, dd_src, &r) == GNO_PASS);
    CHECK(gno_resolve_abs(AT_FDCWD, dev_dst, &r) == GNO_PASS && !strcmp(r.path, dev_dst));
    CHECK(gno_iters < 2000);

    gno_iters = 0;
    CHECK(!gno_candidate(src) || gno_resolve(AT_FDCWD, src, &r) == GNO_PASS);
    CHECK(gno_resolve_abs(AT_FDCWD, src, &r) == GNO_PASS && !strcmp(r.path, src));
    CHECK(gno_resolve_abs(AT_FDCWD, dst, &r) == GNO_PASS && !strcmp(r.path, dst));
    char cwd[GNO_PATH_MAX];
    getcwd(cwd, sizeof(cwd));
    CHECK(chdir(fc) == 0);
    CHECK(gno_resolve_abs(AT_FDCWD, "777a0abed1a003ff2f5b9a90ce906d5a-le64.cache-9.NEW", &r) == GNO_PASS &&
          !strcmp(r.path, src));
    CHECK(gno_resolve_abs(AT_FDCWD, "./../fontconfig/./777a0abed1a003ff2f5b9a90ce906d5a-le64.cache-9", &r) == GNO_PASS &&
          !strcmp(r.path, dst));
    CHECK(gno_resolve_abs(AT_FDCWD, "../../.wine/system.reg", &r) == GNO_IN && !strcmp(r.path, "system.reg"));
    chdir(cwd);
    CHECK(gno_iters < 2000);

    static gno_res a, b;
    CHECK(gno_resolve_abs(AT_FDCWD, src, &a) == GNO_PASS && gno_resolve_abs(AT_FDCWD, dst, &b) == GNO_PASS);
    CHECK(rename(a.path, b.path) == 0 && exists(dst) && !exists(src));

    char p1[GNO_PATH_MAX];
    snprintf(p1, sizeof(p1), "%s/dev%s/./.wine/./system.reg", ROOT, home);
    CHECK(gno_resolve(AT_FDCWD, p1, &r) == GNO_IN && !strcmp(r.path, "system.reg"));
    snprintf(p1, sizeof(p1), "%s/dev%s/.wine/.", ROOT, home);
    CHECK(gno_resolve(AT_FDCWD, p1, &r) == GNO_IN && r.path[0] == 0);
    snprintf(p1, sizeof(p1), "%s/dev%s/.wine./system.reg", ROOT, home);
    CHECK(gno_resolve(AT_FDCWD, p1, &r) == GNO_PASS);
    snprintf(p1, sizeof(p1), "%s/dev%s/.cache/../.wine/system.reg", ROOT, home);
    CHECK(gno_resolve(AT_FDCWD, p1, &r) == GNO_IN && !strcmp(r.path, "system.reg"));
    snprintf(p1, sizeof(p1), "%s/dev%s/.wine/../.wine/.gnoverlay/gen", ROOT, home);
    CHECK(gno_resolve(AT_FDCWD, p1, &r) == GNO_PASS);
    CHECK(gno_resolve(AT_FDCWD, "/home/xuser/./.wine/./system.reg", &r) == GNO_IN && !strcmp(r.path, "system.reg"));
    CHECK(gno_resolve(AT_FDCWD, "/home/xuser/.wine./system.reg", &r) == GNO_PASS);
    CHECK(gno_resolve(AT_FDCWD, "/home/./xuser/../xuser/.wine", &r) == GNO_IN && r.path[0] == 0);
    CHECK(gno_resolve(AT_FDCWD, "/data/data/app.gamenative/files/imagefs/home/xuser/./.wine/.", &r) == GNO_IN &&
          r.path[0] == 0);
}

static void test_fuzz_normalize(void)
{
    static const char *alpha[] = {"/", ".", "..", "a", "-le64.cache-9.NEW", "xuser-STEAM_1", ".wine", ".cache"};
    char path[GNO_PATH_MAX], out[GNO_PATH_MAX];
    gno_res r;
    unsigned seed = 12345;
    int bad = 0, over = 0;
    for (int i = 0; i < 100000; i++) {
        size_t len = 0;
        path[0] = 0;
        if (i & 1) len = (size_t)snprintf(path, sizeof(path), "%s", U);
        int parts = (int)((seed = seed * 1103515245u + 12345u) >> 16) % 24;
        for (int j = 0; j < parts; j++) {
            seed = seed * 1103515245u + 12345u;
            const char *t = alpha[(seed >> 16) % 8];
            size_t tl = strlen(t);
            if (len + tl + 1 >= 1024) break;
            memcpy(path + len, t, tl + 1);
            len += tl;
        }
        if (!len) continue;
        gno_iters = 0;
        if (path[0] == '/' && gno_normalize(AT_FDCWD, path, out, sizeof(out)) == 0) {
            if (out[0] != '/' || strstr(out, "//") || strstr(out, "/./") || strstr(out, "/../")) bad++;
        }
        gno_candidate(path);
        gno_resolve(AT_FDCWD, path, &r);
        if (gno_iters > 4 * (unsigned long)(len + 16)) over++;
    }
    CHECK(bad == 0);
    CHECK(over == 0);
}

static int fake_open_hook, fake_open_real, fake_stat_hook, fake_stat_real, fake_other;

static void test_dlsym_pick(void)
{
    const char *names[] = {"open", "stat"};
    void *hooks[] = {&fake_open_hook, &fake_stat_hook};
    void *reals[] = {&fake_open_real, NULL};
    CHECK(gno_dlsym_pick(0, "open", &fake_open_real, names, hooks, reals, 2) == &fake_open_hook);
    CHECK(gno_dlsym_pick(0, "open", &fake_open_hook, names, hooks, reals, 2) == &fake_open_hook);
    CHECK(gno_dlsym_pick(1, "open", &fake_open_real, names, hooks, reals, 2) == &fake_open_real);
    CHECK(gno_dlsym_pick(1, "open", &fake_open_hook, names, hooks, reals, 2) == &fake_open_hook);
    CHECK(gno_dlsym_pick(0, "open", &fake_other, names, hooks, reals, 2) == &fake_other);
    CHECK(gno_dlsym_pick(0, "stat", &fake_other, names, hooks, reals, 2) == &fake_other);
    CHECK(gno_dlsym_pick(0, "stat", &fake_stat_hook, names, hooks, reals, 2) == &fake_stat_hook);
    CHECK(gno_dlsym_pick(0, "stat", NULL, names, hooks, reals, 2) == NULL);
    CHECK(gno_dlsym_pick(0, "stat", &fake_stat_real, names, hooks, reals, 2) == &fake_stat_real);
    CHECK(gno_dlsym_pick(0, "malloc", &fake_open_real, names, hooks, reals, 2) == &fake_open_real);
    CHECK(gno_dlsym_pick(0, NULL, &fake_open_real, names, hooks, reals, 2) == &fake_open_real);
    CHECK(gno_dlsym_pick(0, "ope", &fake_open_real, names, hooks, reals, 2) == &fake_open_real);
}

static int count_tmp(const char *dir)
{
    int n = 0;
    DIR *d = opendir(dir);
    struct dirent *de;
    while (d && (de = readdir(d)))
        if (strstr(de->d_name, ".gnoverlay-tmp")) n++;
    if (d) closedir(d);
    return n;
}

static void test_copy_up_race(void)
{
    put(P(L, "race/app.ini"), "lower-ini");
    put(P(U, "race/keep"), "k");
    snprintf(g_race_up, sizeof(g_race_up), "%s", P(U, "race/app.ini"));
    int fd = h_open(P(U, "race/app.ini"), O_WRONLY | O_APPEND, 0);
    CHECK(fd >= 0);
    CHECK(fd >= 0 && write(fd, "+a", 2) == 2);
    if (fd >= 0) close(fd);
    CHECK(g_race_up[0] == 0);
    CHECK(!strcmp(h_read(P(U, "race/app.ini")), "winner+a"));
    CHECK(count_tmp(P(U, "race")) == 0);

    put(P(L, "race/b.ini"), "lower-b");
    int before = g_link_calls;
    fd = h_open(P(U, "race/b.ini"), O_RDWR, 0);
    CHECK(fd >= 0);
    if (fd >= 0) close(fd);
    CHECK(g_link_calls == before + 1);
    CHECK(!strcmp(h_read(P(U, "race/b.ini")), "lower-b"));
    CHECK(count_tmp(P(U, "race")) == 0);
    struct stat st;
    CHECK(lstat(P(U, "race/b.ini"), &st) == 0 && st.st_nlink == 1);

    put(P(L, "race/c.ini"), "lower-c");
    g_link_refuse = 1;
    fd = h_open(P(U, "race/c.ini"), O_RDWR, 0);
    CHECK(fd >= 0);
    if (fd >= 0) close(fd);
    CHECK(!strcmp(h_read(P(U, "race/c.ini")), "lower-c"));
    CHECK(count_tmp(P(U, "race")) == 0);

    put(P(U, "race/stray.ini.1.2.gnoverlay-tmp"), "x");
    char *l = h_list(P(U, "race"));
    CHECK(l && !strstr(l, "gnoverlay-tmp"));
}

int main(void)
{
    setup();
    gno_ops ops = make_ops();
    char aliases[GNO_PATH_MAX * 2];
    snprintf(aliases, sizeof(aliases), "%s:/data/user/0/app.gamenative/files/imagefs/home/xuser/.wine:/home/xuser/.wine", A);
    gno_res r;
    CHECK(gno_init(&ops, "/nonexistent/u", L, NULL, 0, NULL) == 0);
    CHECK(gno_resolve(AT_FDCWD, P(U, "x"), &r) == GNO_PASS);
    const char *dbg = getenv("GN_OVERLAY_DEBUG");
    CHECK(gno_init(&ops, U, L, aliases, dbg && !strcmp(dbg, "1"), NULL) == 1);
    test_normalize();
    test_lookup_and_symlinks();
    test_dosdevices_markers();
    test_copy_up();
    test_whiteouts();
    test_rename();
    test_symlink();
    test_listing();
    test_fuzz_normalize();
    test_dlsym_pick();
    test_copy_up_race();
    test_fontconfig_rename(&ops);
    gno_shutdown();
    printf("%d passed, %d failed (tmp %s)\n", g_pass, g_fail, ROOT);
    return g_fail ? 1 : 0;
}
