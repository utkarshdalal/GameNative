#include "gnoverlay_core.h"

#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#ifdef __APPLE__
#define GNO_ATIM(s) ((s)->st_atimespec)
#define GNO_MTIM(s) ((s)->st_mtimespec)
#else
#define GNO_ATIM(s) ((s)->st_atim)
#define GNO_MTIM(s) ((s)->st_mtim)
#endif

#ifdef O_PATH
#define GNO_O_PATH O_PATH
#else
#define GNO_O_PATH O_RDONLY
#endif

#define PM GNO_PATH_MAX
#define META ".gnoverlay"
#define META_LEN 10
#define TMP_SUFFIX ".gnoverlay-tmp"
#define TMP_SUFFIX_LEN 14
#define MAX_FOLLOW 8
#define MAX_PREFIXES 32
#define DIR_MAGIC 0x79616c72766f6e67ULL

enum { M_NONE, M_SELF, M_ANC, M_DIR };

typedef struct prefix {
    char *s;
    size_t len;
    int upper;
} prefix;

struct gno_dir {
    uint64_t magic;
    char *rel;
    gno_snap snap;
    size_t pos;
    int fd;
    struct dirent ent;
};

unsigned long gno_iters;

static gno_ops O;
static int g_active;
static int g_debug;
static int g_logfd = -1;
static char g_upper[PM];
static size_t g_upper_len;
static char g_lower[PM];
static size_t g_lower_len;
static char g_meta[PM];
static prefix g_pfx[MAX_PREFIXES];
static int g_npfx;
static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;

static void **g_dirs;
static size_t g_ndirs, g_capdirs;

int gno_active(void) { return g_active; }

void gno_log(const char *fmt, ...)
{
    if (!g_debug) return;
    char buf[1536];
    int n = snprintf(buf, sizeof(buf), "gnoverlay[%d]: ", (int)getpid());
    va_list ap;
    va_start(ap, fmt);
    int m = vsnprintf(buf + n, sizeof(buf) - n - 1, fmt, ap);
    va_end(ap);
    if (m < 0) return;
    n += m;
    if (n > (int)sizeof(buf) - 2) n = (int)sizeof(buf) - 2;
    buf[n++] = '\n';
    int e = errno;
    if (O.write) {
        O.write(2, buf, n);
        if (g_logfd >= 0) O.write(g_logfd, buf, n);
    }
    if (O.log_sink) {
        buf[n - 1] = 0;
        O.log_sink(buf);
    }
    errno = e;
}

static void warn_once(const char *fmt, const char *a, const char *b)
{
    int d = g_debug;
    g_debug = 1;
    gno_log(fmt, a ? a : "(unset)", b ? b : "(unset)");
    g_debug = d;
}

static int fail(int e)
{
    errno = e;
    return -1;
}

static int pjoin(char *out, const char *a, const char *b)
{
    size_t la = strlen(a), lb = strlen(b);
    if (!la) {
        if (lb >= PM) return fail(ENAMETOOLONG);
        memmove(out, b, lb + 1);
        return 0;
    }
    if (!lb) {
        if (la >= PM) return fail(ENAMETOOLONG);
        if (out != a) memmove(out, a, la + 1);
        return 0;
    }
    int slash = a[la - 1] != '/';
    if (la + slash + lb >= PM) return fail(ENAMETOOLONG);
    if (out != a) memmove(out, a, la);
    if (slash) out[la] = '/';
    memcpy(out + la + slash, b, lb + 1);
    return 0;
}

static int side_path(const char *base, const char *rel, char *out) { return pjoin(out, base, rel); }
void gno_upper_path(const char *rel, char *out) { side_path(g_upper, rel, out); }
void gno_lower_path(const char *rel, char *out) { side_path(g_lower, rel, out); }

static int collapse(const char *in, char *out, size_t outsz)
{
    size_t o = 0;
    if (outsz < 2) return fail(ENAMETOOLONG);
    out[o++] = '/';
    const char *s = in;
    size_t guard = strlen(in) + 2;
    while (*s) {
        gno_iters++;
        if (!guard--) return fail(EINVAL);
        while (*s == '/') s++;
        if (!*s) break;
        const char *e = s;
        while (*e && *e != '/') e++;
        size_t len = (size_t)(e - s);
        if (len == 1 && s[0] == '.') {
        } else if (len == 2 && s[0] == '.' && s[1] == '.') {
            if (o > 1) {
                while (o > 0 && out[o - 1] != '/') o--;
                if (o > 1) o--;
            }
        } else {
            if (o > 1) {
                if (o + 1 >= outsz) return fail(ENAMETOOLONG);
                out[o++] = '/';
            }
            if (o + len >= outsz) return fail(ENAMETOOLONG);
            memcpy(out + o, s, len);
            o += len;
        }
        s = e;
    }
    out[o] = 0;
    return 0;
}

static int data_rewrite(char *p)
{
    if (strncmp(p, "/data/data", 10) || (p[10] != '/' && p[10] != 0)) return 0;
    size_t rest = strlen(p + 10);
    if (12 + rest >= PM) return fail(ENAMETOOLONG);
    memmove(p + 12, p + 10, rest + 1);
    memcpy(p, "/data/user/0", 12);
    return 0;
}

static int canon_abs(const char *abs, char *out)
{
    if (collapse(abs, out, PM) < 0) return -1;
    return data_rewrite(out);
}

int gno_normalize(int dirfd, const char *path, char *out, size_t outsz)
{
    (void)outsz;
    if (!path) return fail(EFAULT);
    if (!*path) return fail(ENOENT);
    if (path[0] == '/') return canon_abs(path, out);
    char base[PM];
    if (dirfd == AT_FDCWD) {
        if (!O.getcwd(base, sizeof(base))) return -1;
    } else {
        ssize_t n = O.fd_path(dirfd, base, sizeof(base) - 1);
        if (n < 0) return fail(EBADF);
        base[n] = 0;
        if (base[0] != '/') return fail(ENOTDIR);
    }
    char tmp[PM * 2];
    size_t lb = strlen(base), lp = strlen(path);
    if (lb + 1 + lp >= sizeof(tmp)) return fail(ENAMETOOLONG);
    memcpy(tmp, base, lb);
    tmp[lb] = '/';
    memcpy(tmp + lb + 1, path, lp + 1);
    return canon_abs(tmp, out);
}

static const prefix *match(const char *abs, const char **rel)
{
    for (int i = 0; i < g_npfx; i++) {
        const prefix *p = &g_pfx[i];
        if (!strncmp(abs, p->s, p->len) && (abs[p->len] == '/' || abs[p->len] == 0)) {
            const char *r = abs + p->len;
            while (*r == '/') r++;
            *rel = r;
            return p;
        }
    }
    return NULL;
}

static int is_hidden(const char *rel)
{
    return !strncmp(rel, META, META_LEN) && (rel[META_LEN] == '/' || rel[META_LEN] == 0);
}

int gno_candidate(const char *p)
{
    if (!g_active || !p) return 0;
    if (p[0] != '/') return 1;
    size_t guard = strlen(p) + 1;
    for (const char *s = p; *s; s++) {
        gno_iters++;
        if (!guard--) return 1;
        if (s[0] != '/') continue;
        if (s[1] == '/') return 1;
        if (s[1] == '.') {
            if (s[2] == '/' || s[2] == 0) return 1;
            if (s[2] == '.' && (s[3] == '/' || s[3] == 0)) return 1;
        }
    }
    int dd = !strncmp(p, "/data/data/", 11);
    for (int i = 0; i < g_npfx; i++) {
        const prefix *x = &g_pfx[i];
        if (!strncmp(p, x->s, x->len)) return 1;
        if (dd && x->len > 13 && !strncmp(x->s, "/data/user/0/", 13) &&
            !strncmp(p + 11, x->s + 13, x->len - 13))
            return 1;
    }
    return 0;
}

static int resolve_common(int dirfd, const char *path, gno_res *r, int want_abs)
{
    char abs[PM];
    if (gno_normalize(dirfd, path, abs, sizeof(abs)) < 0) {
        if (!want_abs) return GNO_PASS;
        if (path[0] != '/' || strlen(path) >= PM) return -1;
        r->kind = GNO_PASS;
        strcpy(r->path, path);
        return GNO_PASS;
    }
    const char *rel;
    const prefix *p = match(abs, &rel);
    if (!p || (p->upper && is_hidden(rel))) {
        if (want_abs) {
            r->kind = GNO_PASS;
            strcpy(r->path, abs);
        }
        return GNO_PASS;
    }
    if (is_hidden(rel)) return fail(ENOENT);
    r->kind = GNO_IN;
    memmove(r->path, rel, strlen(rel) + 1);
    return GNO_IN;
}

int gno_resolve(int dirfd, const char *path, gno_res *r)
{
    if (!g_active || !path || !*path) return GNO_PASS;
    if (path[0] == '/' && !gno_candidate(path)) return GNO_PASS;
    return resolve_common(dirfd, path, r, 0);
}

int gno_resolve_abs(int dirfd, const char *path, gno_res *r)
{
    if (!g_active || !path || !*path) return GNO_PASS;
    if (path[0] == '/' && !gno_candidate(path)) {
        if (strlen(path) >= PM) return fail(ENAMETOOLONG);
        r->kind = GNO_PASS;
        strcpy(r->path, path);
        return GNO_PASS;
    }
    return resolve_common(dirfd, path, r, 1);
}

static int parent_of(const char *path, char *out)
{
    const char *sl = strrchr(path, '/');
    if (!sl) {
        out[0] = 0;
        return 0;
    }
    size_t n = (size_t)(sl - path);
    memcpy(out, path, n);
    out[n] = 0;
    return 0;
}

static int layer_rel(const char *canon, char *rel)
{
    const char *base;
    size_t n;
    if (!strncmp(canon, g_upper, g_upper_len) && (canon[g_upper_len] == '/' || !canon[g_upper_len])) {
        base = canon + g_upper_len;
    } else if (!strncmp(canon, g_lower, g_lower_len) && (canon[g_lower_len] == '/' || !canon[g_lower_len])) {
        base = canon + g_lower_len;
    } else {
        return -1;
    }
    while (*base == '/') base++;
    n = strlen(base);
    memmove(rel, base, n + 1);
    return 0;
}

static int real_dir_rel(const char *dir_rel, char *out)
{
    char p[PM], buf[PM], c[PM];
    const char *bases[2] = {g_upper, g_lower};
    for (int i = 0; i < 2; i++) {
        if (side_path(bases[i], dir_rel, p) < 0) return -1;
        int fd = O.open(p, GNO_O_PATH | O_DIRECTORY | O_CLOEXEC, 0);
        if (fd < 0) continue;
        ssize_t n = O.fd_path(fd, buf, sizeof(buf) - 1);
        O.close(fd);
        if (n <= 0) continue;
        buf[n] = 0;
        if (canon_abs(buf, c) < 0) return -1;
        return layer_rel(c, out) < 0 ? 1 : 0;
    }
    strcpy(out, dir_rel);
    return 0;
}

static int canon_key(const char *rel, int whole, char *out)
{
    int e = errno;
    char dir[PM], rd[PM];
    const char *name = "";
    if (whole) {
        strcpy(dir, rel);
    } else {
        const char *sl = strrchr(rel, '/');
        if (!sl) {
            strcpy(out, rel);
            return 0;
        }
        parent_of(rel, dir);
        name = sl + 1;
    }
    if (!dir[0]) {
        strcpy(out, rel);
        return 0;
    }
    int k = real_dir_rel(dir, rd);
    errno = e;
    if (k) return 1;
    return pjoin(out, rd, name) < 0 ? 1 : 0;
}

static int meta_root(const char *kind)
{
    char p[PM];
    struct stat st;
    int e = errno;
    int r = pjoin(p, g_meta, kind) == 0 && O.lstat(p, &st) == 0 && S_ISDIR(st.st_mode);
    errno = e;
    return r;
}

static int key_state(const char *kind, const char *key)
{
    char a[PM], p[PM];
    struct stat st;
    int e = errno, r = M_NONE;
    if (pjoin(a, g_meta, kind) == 0 && pjoin(p, a, key) == 0) {
        if (O.lstat(p, &st) == 0) r = S_ISDIR(st.st_mode) ? M_DIR : M_SELF;
        else if (errno == ENOTDIR) r = M_ANC;
    }
    errno = e;
    return r;
}

static int meta_state(const char *kind, const char *rel, int whole)
{
    char key[PM];
    if (!rel[0] || !meta_root(kind) || canon_key(rel, whole, key)) return M_NONE;
    return key_state(kind, key);
}

static int lower_hidden(const char *rel)
{
    int w = meta_root("wh"), o = meta_root("opaque");
    char key[PM];
    if ((!w && !o) || !rel[0] || canon_key(rel, 0, key)) return 0;
    if (w) {
        int s = key_state("wh", key);
        if (s == M_SELF || s == M_ANC) return 1;
    }
    return o && key_state("opaque", key) == M_ANC;
}

static int opaque_ancestor(const char *rel) { return meta_state("opaque", rel, 0) == M_ANC; }

static int lookup_one(const char *rel, struct stat *st)
{
    char p[PM];
    if (is_hidden(rel)) return (errno = ENOENT), GNO_NONE;
    if (side_path(g_upper, rel, p) < 0) return GNO_NONE;
    if (O.lstat(p, st) == 0) return GNO_UPPER;
    if (errno != ENOENT) return GNO_NONE;
    if (lower_hidden(rel)) return (errno = ENOENT), GNO_NONE;
    if (side_path(g_lower, rel, p) < 0) return GNO_NONE;
    if (O.lstat(p, st) == 0) return GNO_LOWER;
    if (errno == ENOTDIR) errno = ENOENT;
    return GNO_NONE;
}

static int lower_exists(const char *rel)
{
    char p[PM];
    struct stat st;
    int e = errno;
    int r = side_path(g_lower, rel, p) == 0 && O.lstat(p, &st) == 0 && !opaque_ancestor(rel);
    errno = e;
    return r;
}

int gno_lookup(const gno_res *r, struct stat *st, char *real)
{
    struct stat tmp;
    if (!st) st = &tmp;
    if (r->kind != GNO_IN) return (errno = EINVAL), GNO_NONE;
    int side;
    if (!r->path[0]) side = O.lstat(g_upper, st) == 0 ? GNO_UPPER : GNO_NONE;
    else side = lookup_one(r->path, st);
    if (side != GNO_NONE && real) side_path(side == GNO_UPPER ? g_upper : g_lower, r->path, real);
    return side;
}

static int link_target(const char *rel, int side, char *out_rel, char *outside)
{
    char real[PM], t[PM], dir[PM], abs[PM * 2], c[PM];
    if (side_path(side == GNO_UPPER ? g_upper : g_lower, rel, real) < 0) return -1;
    ssize_t n = O.readlink(real, t, sizeof(t) - 1);
    if (n < 0) return -1;
    t[n] = 0;
    if (t[0] == '/') {
        strcpy(abs, t);
    } else {
        parent_of(rel, dir);
        if (snprintf(abs, sizeof(abs), "%s/%s/%s", g_upper, dir, t) >= (int)sizeof(abs)) return fail(ENAMETOOLONG);
    }
    if (canon_abs(abs, c) < 0) return -1;
    const char *r;
    const prefix *p = match(c, &r);
    if (!p || is_hidden(r)) {
        strcpy(outside, c);
        return 1;
    }
    memmove(out_rel, r, strlen(r) + 1);
    return 0;
}

int gno_follow(gno_res *r, char *outside)
{
    for (int i = 0; i <= MAX_FOLLOW; i++) {
        gno_iters++;
        struct stat st;
        int e = errno;
        int side = gno_lookup(r, &st, NULL);
        errno = e;
        if (side == GNO_NONE || !S_ISLNK(st.st_mode)) return 0;
        if (i == MAX_FOLLOW) return fail(ELOOP);
        int k = link_target(r->path, side, r->path, outside);
        if (k) return k;
    }
    return fail(ELOOP);
}

int gno_read_path(gno_res *r, int follow, char *out)
{
    if (r->kind != GNO_IN) {
        strcpy(out, r->path);
        return 0;
    }
    if (follow) {
        int k = gno_follow(r, out);
        if (k < 0) return -1;
        if (k == 1) return 0;
    }
    if (gno_lookup(r, NULL, out) == GNO_NONE) return -1;
    return 0;
}

static int dir_exists_merged(const char *rel)
{
    char p[PM];
    struct stat st;
    if (!rel[0]) return 1;
    if (is_hidden(rel)) return 0;
    int e = errno, r = 0;
    if (side_path(g_upper, rel, p) == 0 && O.stat(p, &st) == 0) {
        r = S_ISDIR(st.st_mode);
    } else if (errno == ENOENT && !lower_hidden(rel) && side_path(g_lower, rel, p) == 0 && O.stat(p, &st) == 0) {
        r = S_ISDIR(st.st_mode);
    }
    errno = e;
    return r;
}

static int mkdirs_abs(const char *path)
{
    char p[PM];
    if (strlen(path) >= PM) return fail(ENAMETOOLONG);
    strcpy(p, path);
    size_t start = strncmp(p, g_upper, g_upper_len) ? 1 : g_upper_len;
    for (size_t i = start + 1;; i++) {
        if (p[i] != '/' && p[i] != 0) continue;
        char c = p[i];
        p[i] = 0;
        struct stat st;
        if (O.lstat(p, &st) == 0) {
            if (!S_ISDIR(st.st_mode)) {
                if (O.unlink(p) < 0 || (O.mkdir(p, 0755) < 0 && errno != EEXIST)) return -1;
            }
        } else if (O.mkdir(p, 0755) < 0 && errno != EEXIST) {
            return -1;
        }
        p[i] = c;
        if (!c) break;
    }
    return 0;
}

static int rm_rf(const char *path)
{
    struct stat st;
    if (O.lstat(path, &st) < 0) return errno == ENOENT ? 0 : -1;
    if (!S_ISDIR(st.st_mode)) return O.unlink(path);
    DIR *d = O.opendir(path);
    if (d) {
        struct dirent *de;
        char *child = malloc(PM);
        if (!child) {
            O.closedir(d);
            return fail(ENOMEM);
        }
        while ((de = O.readdir(d))) {
            if (!strcmp(de->d_name, ".") || !strcmp(de->d_name, "..")) continue;
            if (pjoin(child, path, de->d_name) == 0) rm_rf(child);
        }
        free(child);
        O.closedir(d);
    }
    return O.rmdir(path);
}

static int set_marker(const char *kind, const char *rel)
{
    char key[PM], a[PM], p[PM], par[PM];
    int e = errno;
    if (canon_key(rel, 0, key)) return 0;
    if (!strcmp(kind, "opaque") && key_state("opaque", key) == M_ANC) return 0;
    if (pjoin(a, g_meta, kind) < 0 || pjoin(p, a, key) < 0) return -1;
    struct stat st;
    if (O.lstat(p, &st) == 0) {
        if (S_ISREG(st.st_mode)) return 0;
        rm_rf(p);
    }
    parent_of(p, par);
    if (mkdirs_abs(par) < 0) return -1;
    int fd = O.open(p, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (fd < 0) return -1;
    O.close(fd);
    gno_log("marker %s/%s", kind, key);
    errno = e;
    return 0;
}

static void clear_marker(const char *kind, const char *rel, int tree)
{
    char key[PM], a[PM], p[PM];
    struct stat st;
    int e = errno;
    if (!meta_root(kind) || canon_key(rel, 0, key)) return;
    if (pjoin(a, g_meta, kind) == 0 && pjoin(p, a, key) == 0 && O.lstat(p, &st) == 0) {
        if (!S_ISDIR(st.st_mode)) O.unlink(p);
        else if (tree) rm_rf(p);
    }
    errno = e;
}

static int ensure_dir(const char *rel, int depth);

static int ensure_link_target(const char *rel, int depth)
{
    char t[PM], outside[PM];
    int k = link_target(rel, GNO_UPPER, t, outside);
    if (k < 0) return -1;
    if (k == 1) return fail(ENOENT);
    return ensure_dir(t, depth + 1);
}

static int ensure_dir(const char *rel, int depth)
{
    char p[PM], up[PM], lp[PM], t[PM];
    if (depth > MAX_FOLLOW) return fail(ELOOP);
    if (strlen(rel) >= PM) return fail(ENAMETOOLONG);
    strcpy(p, rel);
    size_t n = strlen(p);
    for (size_t i = 0; i <= n; i++) {
        gno_iters++;
        if (p[i] != '/' && p[i] != 0) continue;
        char c = p[i];
        p[i] = 0;
        if (!p[0]) {
            p[i] = c;
            continue;
        }
        struct stat st;
        side_path(g_upper, p, up);
        if (O.stat(up, &st) == 0) {
            if (!S_ISDIR(st.st_mode)) return fail(ENOTDIR);
        } else if (O.lstat(up, &st) == 0) {
            if (!S_ISLNK(st.st_mode) || ensure_link_target(p, depth) < 0) return fail(errno ? errno : ENOTDIR);
        } else {
            mode_t mode = 0755;
            side_path(g_lower, p, lp);
            if (O.lstat(lp, &st) == 0 && S_ISLNK(st.st_mode)) {
                ssize_t l = O.readlink(lp, t, sizeof(t) - 1);
                if (l < 0) return -1;
                t[l] = 0;
                if (O.symlink(t, up) < 0 && errno != EEXIST) return -1;
                if (O.stat(up, &st) != 0 && ensure_link_target(p, depth) < 0) return -1;
            } else {
                if (O.stat(lp, &st) == 0 && S_ISDIR(st.st_mode)) mode = st.st_mode & 07777;
                if (O.mkdir(up, mode | 0700) < 0 && errno != EEXIST) return -1;
            }
        }
        p[i] = c;
    }
    return 0;
}

static int ensure_upper_parents(const char *rel)
{
    char p[PM];
    parent_of(rel, p);
    return p[0] ? ensure_dir(p, 0) : 0;
}

static void copy_xattrs(int sfd, int dfd)
{
    if (!O.flistxattr) return;
    ssize_t n = O.flistxattr(sfd, NULL, 0);
    if (n <= 0) return;
    char *names = malloc((size_t)n + 1);
    if (!names) return;
    n = O.flistxattr(sfd, names, (size_t)n);
    if (n <= 0) {
        free(names);
        return;
    }
    names[n] = 0;
    for (char *nm = names; nm < names + n; nm += strlen(nm) + 1) {
        if (strncmp(nm, "user.", 5) || !strncmp(nm, "user.gnoverlay.", 15)) continue;
        ssize_t vl = O.fgetxattr(sfd, nm, NULL, 0);
        if (vl < 0) continue;
        char *v = malloc((size_t)vl + 1);
        if (!v) continue;
        vl = O.fgetxattr(sfd, nm, v, (size_t)vl);
        if (vl >= 0 && O.fsetxattr(dfd, nm, v, (size_t)vl, 0) < 0)
            gno_log("copy xattr %s failed: %s", nm, strerror(errno));
        free(v);
    }
    free(names);
}

static unsigned long g_tmp_seq;

static int publish_noreplace(const char *tmp, const char *up)
{
    if (O.link(tmp, up) == 0) {
        O.unlink(tmp);
        return 0;
    }
    int e = errno;
    if (e == EEXIST) return -1;
    if (O.rename_noreplace && O.rename_noreplace(tmp, up) == 0) return 0;
    if (O.rename_noreplace) e = errno;
    errno = e;
    return -1;
}

static int copy_file(const char *lp, const char *up, const struct stat *lst, int copy_data)
{
    char tmp[PM];
    unsigned long seq = __atomic_add_fetch(&g_tmp_seq, 1, __ATOMIC_RELAXED);
    if (snprintf(tmp, sizeof(tmp), "%s.%d.%lu%s", up, (int)getpid(), seq, TMP_SUFFIX) >= (int)sizeof(tmp))
        return fail(ENAMETOOLONG);
    int sfd = O.open(lp, O_RDONLY | O_NOFOLLOW | O_CLOEXEC, 0);
    if (sfd < 0) return -1;
    int dfd = O.open(tmp, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (dfd < 0) {
        int e = errno;
        O.close(sfd);
        return fail(e);
    }
    int err = 0;
    if (copy_data) {
        size_t bsz = 1 << 18;
        char *buf = malloc(bsz);
        if (!buf) err = ENOMEM;
        while (!err) {
            ssize_t n = O.read(sfd, buf, bsz);
            if (n < 0) {
                if (errno == EINTR) continue;
                err = errno;
                break;
            }
            if (n == 0) break;
            for (ssize_t off = 0; off < n;) {
                ssize_t w = O.write(dfd, buf + off, (size_t)(n - off));
                if (w < 0) {
                    if (errno == EINTR) continue;
                    err = errno;
                    break;
                }
                off += w;
            }
        }
        free(buf);
    }
    if (!err) {
        copy_xattrs(sfd, dfd);
        if (O.fchmod(dfd, (lst->st_mode & 07777) | 0600) < 0) err = errno;
    }
    if (!err) {
        struct timespec ts[2] = {GNO_ATIM(lst), GNO_MTIM(lst)};
        if (O.futimens(dfd, ts) < 0) err = errno;
    }
    O.close(sfd);
    if (O.close(dfd) < 0 && !err) err = errno;
    if (!err && publish_noreplace(tmp, up) < 0) {
        err = errno;
        if (err == EEXIST) {
            O.unlink(tmp);
            gno_log("copy-up %s: %s already published by another copier", lp, up);
            return 0;
        }
    }
    if (err) {
        O.unlink(tmp);
        gno_log("copy-up %s failed: %s", lp, strerror(err));
        return fail(err);
    }
    gno_log("copy-up %s -> %s%s", lp, up, copy_data ? "" : " (no data)");
    return 0;
}

static int copy_up_node(const char *rel, const struct stat *lst, int copy_data)
{
    char lp[PM], up[PM];
    if (side_path(g_lower, rel, lp) < 0 || side_path(g_upper, rel, up) < 0) return -1;
    if (S_ISREG(lst->st_mode)) return copy_file(lp, up, lst, copy_data);
    if (S_ISDIR(lst->st_mode)) {
        if (O.mkdir(up, (lst->st_mode & 07777) | 0700) < 0 && errno != EEXIST) return -1;
        int fd = O.open(up, O_RDONLY | O_DIRECTORY | O_CLOEXEC, 0);
        if (fd >= 0) {
            int sfd = O.open(lp, O_RDONLY | O_DIRECTORY | O_CLOEXEC, 0);
            if (sfd >= 0) {
                copy_xattrs(sfd, fd);
                O.close(sfd);
            }
            O.fchmod(fd, (lst->st_mode & 07777) | 0700);
            struct timespec ts[2] = {GNO_ATIM(lst), GNO_MTIM(lst)};
            O.futimens(fd, ts);
            O.close(fd);
        }
        return 0;
    }
    if (S_ISLNK(lst->st_mode)) {
        char t[PM];
        ssize_t n = O.readlink(lp, t, sizeof(t) - 1);
        if (n < 0) return -1;
        t[n] = 0;
        if (O.symlink(t, up) < 0 && errno != EEXIST) return -1;
        return 0;
    }
    return fail(ENOTSUP);
}

static int materialize_rel(const char *rel, int copy_data, char *upper_out)
{
    struct stat st;
    int side = lookup_one(rel, &st);
    if (side == GNO_NONE) return -1;
    if (upper_out && side_path(g_upper, rel, upper_out) < 0) return -1;
    if (side == GNO_UPPER) return 0;
    if (ensure_upper_parents(rel) < 0) return -1;
    return copy_up_node(rel, &st, copy_data);
}

int gno_materialize(gno_res *r, int follow, int copy_data, char *path_out)
{
    if (r->kind != GNO_IN) {
        strcpy(path_out, r->path);
        return 0;
    }
    if (follow) {
        int k = gno_follow(r, path_out);
        if (k < 0) return -1;
        if (k == 1) return 0;
    }
    if (!r->path[0]) {
        strcpy(path_out, g_upper);
        return 0;
    }
    return materialize_rel(r->path, copy_data, path_out);
}

int gno_fopen_flags(const char *mode)
{
    int f = 0, acc = O_RDONLY;
    if (!mode) return O_RDONLY;
    switch (mode[0]) {
    case 'w': acc = O_WRONLY; f |= O_CREAT | O_TRUNC; break;
    case 'a': acc = O_WRONLY; f |= O_CREAT | O_APPEND; break;
    default: break;
    }
    for (const char *m = mode + 1; *m; m++) {
        if (*m == '+') acc = O_RDWR;
        else if (*m == 'x') f |= O_EXCL;
        else if (*m == 'e') f |= O_CLOEXEC;
    }
    return acc | f;
}

int gno_prepare_open(gno_res *r, int flags, char *out, int *created_new, int *is_dir)
{
    *created_new = 0;
    *is_dir = 0;
    if (r->kind != GNO_IN) {
        strcpy(out, r->path);
        return 0;
    }
    int excl = (flags & (O_CREAT | O_EXCL)) == (O_CREAT | O_EXCL);
    if (!(flags & O_NOFOLLOW) && !excl) {
        int k = gno_follow(r, out);
        if (k < 0) return -1;
        if (k == 1) return 0;
    }
    struct stat st;
    int side = gno_lookup(r, &st, out);
    int acc = flags & O_ACCMODE;
    int writeish = acc != O_RDONLY || (flags & (O_TRUNC | O_APPEND));
    if (side != GNO_NONE) {
        if (excl) return fail(EEXIST);
        if (S_ISDIR(st.st_mode)) {
            *is_dir = 1;
            return 0;
        }
        if (!writeish || side == GNO_UPPER || !S_ISREG(st.st_mode)) return 0;
        char lp[PM];
        strcpy(lp, out);
        if (ensure_upper_parents(r->path) < 0) return -1;
        side_path(g_upper, r->path, out);
        return copy_file(lp, out, &st, !(flags & O_TRUNC));
    }
    if (errno != ENOENT) return -1;
    char par[PM];
    parent_of(r->path, par);
    if (!(flags & O_CREAT) || !dir_exists_merged(par)) return fail(ENOENT);
    if (ensure_upper_parents(r->path) < 0) return -1;
    side_path(g_upper, r->path, out);
    *created_new = 1;
    return 0;
}

void gno_finish_create(const gno_res *r)
{
    if (r->kind == GNO_IN && r->path[0]) clear_marker("wh", r->path, 0);
}

int gno_unlink(const gno_res *r)
{
    struct stat st;
    char real[PM];
    int side = gno_lookup(r, &st, real);
    if (side == GNO_NONE) return -1;
    if (!r->path[0] || S_ISDIR(st.st_mode)) return fail(EISDIR);
    if (side == GNO_UPPER) {
        int lower = lower_exists(r->path);
        if (O.unlink(real) < 0) return -1;
        if (lower && set_marker("wh", r->path) < 0) return -1;
    } else if (set_marker("wh", r->path) < 0) {
        return -1;
    }
    gno_log("unlink %s", r->path);
    return 0;
}

static ssize_t merged_count(const char *rel)
{
    gno_snap s;
    if (gno_snapshot(rel, &s) < 0) return -1;
    ssize_t n = 0;
    for (size_t i = 0; i < s.n; i++)
        if (strcmp(s.e[i].name, ".") && strcmp(s.e[i].name, "..")) n++;
    gno_snap_free(&s);
    return n;
}

int gno_rmdir(const gno_res *r)
{
    struct stat st;
    char real[PM];
    int side = gno_lookup(r, &st, real);
    if (side == GNO_NONE) return -1;
    if (!S_ISDIR(st.st_mode)) return fail(ENOTDIR);
    if (!r->path[0]) return fail(EBUSY);
    ssize_t n = merged_count(r->path);
    if (n < 0) return -1;
    if (n > 0) return fail(ENOTEMPTY);
    int lower = lower_exists(r->path);
    if (side == GNO_UPPER && O.rmdir(real) < 0) return -1;
    clear_marker("opaque", r->path, 1);
    if (lower) {
        if (set_marker("wh", r->path) < 0) return -1;
    } else {
        clear_marker("wh", r->path, 1);
    }
    gno_log("rmdir %s", r->path);
    return 0;
}

int gno_mkdir(const gno_res *r, mode_t mode)
{
    char par[PM], up[PM], lp[PM];
    if (!r->path[0]) return fail(EEXIST);
    struct stat st;
    if (gno_lookup(r, &st, NULL) != GNO_NONE) return fail(EEXIST);
    if (errno != ENOENT) return -1;
    parent_of(r->path, par);
    if (!dir_exists_merged(par)) return fail(ENOENT);
    int wh = meta_state("wh", r->path, 0) == M_SELF;
    int lower_dir = side_path(g_lower, r->path, lp) == 0 && O.lstat(lp, &st) == 0 && S_ISDIR(st.st_mode) &&
                    !opaque_ancestor(r->path);
    if (ensure_upper_parents(r->path) < 0) return -1;
    side_path(g_upper, r->path, up);
    if (O.mkdir(up, mode) < 0) return -1;
    if (wh) {
        clear_marker("wh", r->path, 1);
        if (lower_dir) set_marker("opaque", r->path);
    }
    gno_log("mkdir %s%s", r->path, wh && lower_dir ? " (opaque)" : "");
    return 0;
}

int gno_symlink(const char *target, const gno_res *r)
{
    char par[PM], up[PM];
    if (!r->path[0]) return fail(EEXIST);
    if (gno_lookup(r, NULL, NULL) != GNO_NONE) return fail(EEXIST);
    if (errno != ENOENT) return -1;
    parent_of(r->path, par);
    if (!dir_exists_merged(par)) return fail(ENOENT);
    if (ensure_upper_parents(r->path) < 0) return -1;
    side_path(g_upper, r->path, up);
    if (O.symlink(target, up) < 0) return -1;
    clear_marker("wh", r->path, 1);
    return 0;
}

int gno_link(const gno_res *from, const gno_res *to)
{
    char sp[PM], dp[PM], par[PM];
    if (from->kind == GNO_IN) {
        struct stat st;
        if (gno_lookup(from, &st, NULL) == GNO_NONE) return -1;
        if (S_ISDIR(st.st_mode)) return fail(EPERM);
        if (materialize_rel(from->path, 1, sp) < 0) return -1;
    } else {
        strcpy(sp, from->path);
    }
    if (to->kind != GNO_IN) return O.link(sp, to->path);
    if (gno_lookup(to, NULL, NULL) != GNO_NONE) return fail(EEXIST);
    if (errno != ENOENT) return -1;
    parent_of(to->path, par);
    if (!dir_exists_merged(par)) return fail(ENOENT);
    if (ensure_upper_parents(to->path) < 0) return -1;
    side_path(g_upper, to->path, dp);
    if (O.link(sp, dp) < 0) return -1;
    clear_marker("wh", to->path, 1);
    return 0;
}

static int materialize_tree(const char *rel, int depth)
{
    struct stat st;
    if (depth > 256) return fail(ELOOP);
    int side = lookup_one(rel, &st);
    if (side == GNO_NONE) return -1;
    if (!S_ISDIR(st.st_mode)) return materialize_rel(rel, 1, NULL);
    if (side == GNO_LOWER) {
        if (ensure_upper_parents(rel) < 0 || copy_up_node(rel, &st, 1) < 0) return -1;
    }
    int ms = meta_state("opaque", rel, 1);
    if (ms == M_SELF || ms == M_ANC) return 0;
    gno_snap s;
    if (gno_snapshot(rel, &s) < 0) return -1;
    char *child = malloc(PM);
    int rc = child ? 0 : fail(ENOMEM);
    for (size_t i = 0; !rc && i < s.n; i++) {
        const gno_dent *e = &s.e[i];
        if (!strcmp(e->name, ".") || !strcmp(e->name, "..")) continue;
        if (pjoin(child, rel, e->name) < 0) {
            rc = -1;
            break;
        }
        if (e->type == DT_DIR) rc = materialize_tree(child, depth + 1);
        else if (e->side == GNO_LOWER) rc = materialize_rel(child, 1, NULL);
    }
    free(child);
    gno_snap_free(&s);
    return rc;
}

static int is_under(const char *path, const char *base)
{
    size_t n = strlen(base);
    if (!n) return 1;
    return !strncmp(path, base, n) && path[n] == '/';
}

int gno_rename(const gno_res *from, const gno_res *to, unsigned flags)
{
    char sp[PM], dp[PM], par[PM];
    struct stat sst, dst;
    int sin = from->kind == GNO_IN, din = to->kind == GNO_IN;
    int exch = (flags & GNO_RENAME_EXCHANGE) != 0;
    int dside = GNO_NONE, sdir, ddir = 0;
    if (sin) {
        if (gno_lookup(from, &sst, NULL) == GNO_NONE) return -1;
        if (!from->path[0]) return fail(EBUSY);
    } else if (O.lstat(from->path, &sst) < 0) {
        return -1;
    }
    sdir = S_ISDIR(sst.st_mode);
    if (din) {
        if (!to->path[0]) return fail(EBUSY);
        dside = gno_lookup(to, &dst, NULL);
        if (dside == GNO_NONE && errno != ENOENT) return -1;
        parent_of(to->path, par);
        if (dside == GNO_NONE && !dir_exists_merged(par)) return fail(ENOENT);
    } else if (O.lstat(to->path, &dst) == 0) {
        dside = GNO_UPPER;
    }
    if (sin && din && !strcmp(from->path, to->path)) return 0;
    if ((flags & GNO_RENAME_NOREPLACE) && dside != GNO_NONE) return fail(EEXIST);
    if (exch && dside == GNO_NONE) return fail(ENOENT);
    if (sin && din && sdir && is_under(to->path, from->path)) return fail(EINVAL);
    if (dside != GNO_NONE) {
        ddir = S_ISDIR(dst.st_mode);
        if (!exch) {
            if (sdir && !ddir) return fail(ENOTDIR);
            if (!sdir && ddir) return fail(EISDIR);
            if (ddir && din) {
                ssize_t n = merged_count(to->path);
                if (n < 0) return -1;
                if (n > 0) return fail(ENOTEMPTY);
            }
        }
    }
    int src_lower = sin && lower_exists(from->path);
    int dst_lower = din && lower_exists(to->path);
    if (sin) {
        if ((sdir ? materialize_tree(from->path, 0) : materialize_rel(from->path, 1, NULL)) < 0) return -1;
        side_path(g_upper, from->path, sp);
    } else {
        strcpy(sp, from->path);
    }
    if (din) {
        if (ensure_upper_parents(to->path) < 0) return -1;
        if (exch && (ddir ? materialize_tree(to->path, 0) : materialize_rel(to->path, 1, NULL)) < 0) return -1;
        side_path(g_upper, to->path, dp);
    } else {
        strcpy(dp, to->path);
    }
    if (exch) {
        if (O.rename_exchange(sp, dp) < 0) return -1;
    } else if (O.rename(sp, dp) < 0) {
        return -1;
    }
    if (exch) {
        if (sin) {
            clear_marker("opaque", from->path, 1);
            if (ddir && src_lower) set_marker("opaque", from->path);
        }
        if (din) {
            clear_marker("opaque", to->path, 1);
            if (sdir && dst_lower) set_marker("opaque", to->path);
        }
    } else {
        if (sin) {
            clear_marker("opaque", from->path, 1);
            if (src_lower) set_marker("wh", from->path);
            else clear_marker("wh", from->path, 1);
        }
        if (din) {
            clear_marker("wh", to->path, 1);
            clear_marker("opaque", to->path, 1);
            if (sdir && dst_lower) set_marker("opaque", to->path);
        }
    }
    gno_log("rename %s -> %s", sin ? from->path : sp, din ? to->path : dp);
    return 0;
}

int gno_realpath(gno_res *r, char *out)
{
    char real[PM], buf[PM], c[PM], rel[PM];
    if (gno_read_path(r, 1, real) < 0) return -1;
    int fd = O.open(real, GNO_O_PATH | O_CLOEXEC, 0);
    if (fd < 0) return -1;
    ssize_t n = O.fd_path(fd, buf, sizeof(buf) - 1);
    O.close(fd);
    if (n <= 0) return fail(ENOENT);
    buf[n] = 0;
    if (canon_abs(buf, c) < 0) return -1;
    if (layer_rel(c, rel) == 0) return side_path(g_upper, rel, out);
    strcpy(out, c);
    return 0;
}

static int snap_add(gno_snap *s, const char *name, uint64_t ino, unsigned char type, int side)
{
    if (s->n == s->cap) {
        size_t nc = s->cap ? s->cap * 2 : 64;
        gno_dent *ne = realloc(s->e, nc * sizeof(*ne));
        if (!ne) return fail(ENOMEM);
        s->e = ne;
        s->cap = nc;
    }
    char *nm = strdup(name);
    if (!nm) return fail(ENOMEM);
    gno_dent *e = &s->e[s->n++];
    e->ino = ino;
    e->type = type;
    e->side = (unsigned char)side;
    e->name = nm;
    return 0;
}

void gno_snap_free(gno_snap *s)
{
    for (size_t i = 0; i < s->n; i++) free(s->e[i].name);
    free(s->e);
    s->e = NULL;
    s->n = s->cap = 0;
}

static unsigned char dtype_of(const char *dir, const char *name)
{
    char p[PM];
    struct stat st;
    if (pjoin(p, dir, name) < 0 || O.lstat(p, &st) < 0) return DT_UNKNOWN;
    if (S_ISDIR(st.st_mode)) return DT_DIR;
    if (S_ISREG(st.st_mode)) return DT_REG;
    if (S_ISLNK(st.st_mode)) return DT_LNK;
    if (S_ISFIFO(st.st_mode)) return DT_FIFO;
    if (S_ISSOCK(st.st_mode)) return DT_SOCK;
    if (S_ISCHR(st.st_mode)) return DT_CHR;
    if (S_ISBLK(st.st_mode)) return DT_BLK;
    return DT_UNKNOWN;
}

static int is_tmp_name(const char *name)
{
    size_t n = strlen(name);
    return n > TMP_SUFFIX_LEN && !strcmp(name + n - TMP_SUFFIX_LEN, TMP_SUFFIX);
}

int gno_snapshot(const char *rel, gno_snap *s)
{
    char up[PM], lp[PM], key[PM], child[PM];
    memset(s, 0, sizeof(*s));
    int has_key = rel[0] && (meta_root("wh") || meta_root("opaque")) && !canon_key(rel, 1, key);
    int ost = has_key ? key_state("opaque", key) : M_NONE;
    int opq = ost == M_SELF || ost == M_ANC;
    if (side_path(g_upper, rel, up) < 0 || side_path(g_lower, rel, lp) < 0) return -1;
    DIR *ud = O.opendir(up);
    if (!ud && errno != ENOENT) return -1;
    DIR *ld = opq ? NULL : O.opendir(lp);
    if (!ud && !ld) return fail(ENOENT);
    int root = !rel[0];
    struct dirent *de;
    size_t nup = 0;
    int up_opened = ud != NULL;
    if (ud) {
        while ((de = O.readdir(ud))) {
            const char *nm = de->d_name;
            if (root && !strcmp(nm, META)) continue;
            if (is_tmp_name(nm)) continue;
            unsigned char t = de->d_type;
            if (t == DT_UNKNOWN) t = dtype_of(up, nm);
            if (snap_add(s, nm, (uint64_t)de->d_ino, t, GNO_UPPER) < 0) goto fail;
        }
        O.closedir(ud);
        ud = NULL;
        nup = s->n;
    }
    if (ld) {
        size_t hsz = 64;
        while (hsz < nup * 2 + 16) hsz <<= 1;
        uint32_t *h = calloc(hsz, sizeof(uint32_t));
        if (!h) goto fail_nomem;
        for (size_t i = 0; i < nup; i++) {
            uint32_t k = 2166136261u;
            for (const char *c = s->e[i].name; *c; c++) k = (k ^ (unsigned char)*c) * 16777619u;
            k &= (uint32_t)(hsz - 1);
            while (h[k]) k = (k + 1) & (uint32_t)(hsz - 1);
            h[k] = (uint32_t)i + 1;
        }
        int whd = root ? meta_root("wh") : (has_key && key_state("wh", key) == M_DIR);
        while ((de = O.readdir(ld))) {
            const char *nm = de->d_name;
            int dot = !strcmp(nm, ".") || !strcmp(nm, "..");
            if (dot && up_opened) continue;
            if (root && !strcmp(nm, META)) continue;
            uint32_t k = 2166136261u;
            for (const char *c = nm; *c; c++) k = (k ^ (unsigned char)*c) * 16777619u;
            int dup = 0;
            for (k &= (uint32_t)(hsz - 1); h[k]; k = (k + 1) & (uint32_t)(hsz - 1)) {
                if (!strcmp(s->e[h[k] - 1].name, nm)) {
                    dup = 1;
                    break;
                }
            }
            if (dup) continue;
            if (whd && !dot) {
                if (pjoin(child, root ? "" : key, nm) == 0 && key_state("wh", child) == M_SELF) continue;
            }
            unsigned char t = de->d_type;
            if (t == DT_UNKNOWN) t = dtype_of(lp, nm);
            if (snap_add(s, nm, (uint64_t)de->d_ino, t, GNO_LOWER) < 0) {
                free(h);
                goto fail;
            }
        }
        free(h);
        O.closedir(ld);
    }
    return 0;
fail_nomem:
    errno = ENOMEM;
fail:;
    int e = errno;
    if (ud) O.closedir(ud);
    if (ld) O.closedir(ld);
    gno_snap_free(s);
    return fail(e);
}

static void dirs_add(void *d)
{
    pthread_mutex_lock(&g_mu);
    if (g_ndirs == g_capdirs) {
        size_t nc = g_capdirs ? g_capdirs * 2 : 16;
        void **n = realloc(g_dirs, nc * sizeof(*n));
        if (n) {
            g_dirs = n;
            g_capdirs = nc;
        }
    }
    if (g_ndirs < g_capdirs) g_dirs[g_ndirs++] = d;
    pthread_mutex_unlock(&g_mu);
}

static void dirs_remove(void *d)
{
    pthread_mutex_lock(&g_mu);
    for (size_t i = 0; i < g_ndirs; i++) {
        if (g_dirs[i] == d) {
            g_dirs[i] = g_dirs[--g_ndirs];
            break;
        }
    }
    pthread_mutex_unlock(&g_mu);
}

int gno_is_dir(const void *d)
{
    if (!d || !__atomic_load_n(&g_ndirs, __ATOMIC_RELAXED)) return 0;
    uint64_t m;
    memcpy(&m, d, sizeof(m));
    if (m != DIR_MAGIC) return 0;
    int found = 0;
    pthread_mutex_lock(&g_mu);
    for (size_t i = 0; i < g_ndirs; i++) {
        if (g_dirs[i] == d) {
            found = 1;
            break;
        }
    }
    pthread_mutex_unlock(&g_mu);
    return found;
}

gno_dir *gno_opendir(const gno_res *r, int fd)
{
    struct stat st;
    if (gno_lookup(r, &st, NULL) == GNO_NONE) return NULL;
    if (!S_ISDIR(st.st_mode)) return (errno = ENOTDIR), NULL;
    gno_dir *d = calloc(1, sizeof(*d));
    if (!d) return (errno = ENOMEM), NULL;
    d->magic = DIR_MAGIC;
    d->rel = strdup(r->path);
    d->fd = fd;
    if (!d->rel || gno_snapshot(d->rel, &d->snap) < 0) {
        int e = errno;
        free(d->rel);
        free(d);
        errno = e ? e : ENOMEM;
        return NULL;
    }
    dirs_add(d);
    return d;
}

struct dirent *gno_readdir(gno_dir *d)
{
    if (d->pos >= d->snap.n) return NULL;
    const gno_dent *e = &d->snap.e[d->pos++];
    struct dirent *o = &d->ent;
    memset(o, 0, offsetof(struct dirent, d_name));
    o->d_ino = (ino_t)e->ino;
#ifdef __APPLE__
    o->d_seekoff = d->pos;
    o->d_namlen = (uint16_t)strlen(e->name);
#else
    o->d_off = (off_t)d->pos;
#endif
    o->d_reclen = sizeof(struct dirent);
    o->d_type = e->type;
    snprintf(o->d_name, sizeof(o->d_name), "%s", e->name);
    return o;
}

int gno_closedir(gno_dir *d)
{
    dirs_remove(d);
    d->magic = 0;
    if (d->fd >= 0) O.close(d->fd);
    gno_snap_free(&d->snap);
    free(d->rel);
    free(d);
    return 0;
}

void gno_rewinddir(gno_dir *d)
{
    gno_snap s;
    if (gno_snapshot(d->rel, &s) == 0) {
        gno_snap_free(&d->snap);
        d->snap = s;
    }
    d->pos = 0;
}

long gno_telldir(gno_dir *d) { return (long)d->pos; }
void gno_seekdir(gno_dir *d, long pos) { d->pos = pos < 0 ? 0 : (size_t)pos; }

int gno_dirfd(gno_dir *d)
{
    if (d->fd >= 0) return d->fd;
    char p[PM];
    side_path(g_upper, d->rel, p);
    d->fd = O.open(p, O_RDONLY | O_DIRECTORY | O_CLOEXEC, 0);
    if (d->fd < 0) {
        side_path(g_lower, d->rel, p);
        d->fd = O.open(p, O_RDONLY | O_DIRECTORY | O_CLOEXEC, 0);
    }
    return d->fd;
}

int gno_fd_lower_copyup(int fd, char *out)
{
    if (!g_active) return 0;
    char p[PM], c[PM], rel[PM];
    int e = errno;
    ssize_t l = O.fd_path(fd, p, sizeof(p) - 1);
    if (l <= 0) {
        errno = e;
        return 0;
    }
    p[l] = 0;
    if (p[0] != '/' || canon_abs(p, c) < 0 || strncmp(c, g_lower, g_lower_len) ||
        (c[g_lower_len] != '/' && c[g_lower_len] != 0) || layer_rel(c, rel) < 0) {
        errno = e;
        return 0;
    }
    if (!rel[0]) return fail(EROFS);
    return materialize_rel(rel, 1, out) < 0 ? -1 : 1;
}

static int add_prefix(const char *raw, int upper)
{
    char c[PM];
    if (!raw || raw[0] != '/' || g_npfx >= MAX_PREFIXES) return 0;
    if (canon_abs(raw, c) < 0 || !strcmp(c, "/")) return 0;
    for (int i = 0; i < g_npfx; i++)
        if (!strcmp(g_pfx[i].s, c)) return 0;
    char *s = strdup(c);
    if (!s) return -1;
    g_pfx[g_npfx].s = s;
    g_pfx[g_npfx].len = strlen(s);
    g_pfx[g_npfx].upper = upper;
    g_npfx++;
    return 0;
}

static int cmp_prefix(const void *a, const void *b)
{
    const prefix *x = a, *y = b;
    return x->len < y->len ? 1 : x->len > y->len ? -1 : 0;
}

void gno_shutdown(void)
{
    g_active = 0;
    for (int i = 0; i < g_npfx; i++) free(g_pfx[i].s);
    g_npfx = 0;
    if (g_logfd >= 0) O.close(g_logfd);
    g_logfd = -1;
}

int gno_init(const gno_ops *ops, const char *upper, const char *lower, const char *aliases,
             int debug, const char *logpath)
{
    O = *ops;
    g_debug = debug;
    if (debug && logpath && *logpath)
        g_logfd = O.open(logpath, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
    struct stat st;
    if (!upper || !lower || upper[0] != '/' || lower[0] != '/') {
        if (upper || lower || debug) warn_once("disabled: upper=%s lower=%s must both be absolute", upper, lower);
        return 0;
    }
    if (canon_abs(upper, g_upper) < 0 || canon_abs(lower, g_lower) < 0) return 0;
    if (O.lstat(g_upper, &st) < 0 || !S_ISDIR(st.st_mode) || O.lstat(g_lower, &st) < 0 ||
        !S_ISDIR(st.st_mode)) {
        warn_once("disabled: upper %s or lower %s is not a directory", g_upper, g_lower);
        return 0;
    }
    if (!strcmp(g_upper, g_lower) || is_under(g_upper, g_lower) || is_under(g_lower, g_upper)) {
        warn_once("disabled: upper %s and lower %s overlap", g_upper, g_lower);
        return 0;
    }
    g_upper_len = strlen(g_upper);
    g_lower_len = strlen(g_lower);
    if (pjoin(g_meta, g_upper, META) < 0) return 0;
    if (O.mkdir(g_meta, 0755) < 0 && errno != EEXIST) {
        warn_once("disabled: cannot create %s: %s", g_meta, strerror(errno));
        return 0;
    }
    g_npfx = 0;
    add_prefix(g_upper, 1);
    if (aliases) {
        char buf[PM * 4];
        snprintf(buf, sizeof(buf), "%s", aliases);
        for (char *s = buf, *e; s && *s; s = e) {
            e = strchr(s, ':');
            if (e) *e++ = 0;
            add_prefix(s, 0);
        }
    }
    add_prefix(g_lower, 0);
    qsort(g_pfx, (size_t)g_npfx, sizeof(prefix), cmp_prefix);
    g_active = 1;
    gno_log("active: upper=%s lower=%s prefixes=%d", g_upper, g_lower, g_npfx);
    return 1;
}

void *gno_dlsym_pick(int handle_is_next, const char *name, void *resolved, const char *const *names,
                     void *const *hooks, void *const *reals, size_t n)
{
    if (handle_is_next || !resolved || !name) return resolved;
    for (size_t i = 0; i < n; i++) {
        if (strcmp(names[i], name)) continue;
        if (resolved == hooks[i] || (reals[i] && resolved == reals[i])) return hooks[i];
        break;
    }
    return resolved;
}
