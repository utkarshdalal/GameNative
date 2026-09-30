#ifndef GNOVERLAY_CORE_H
#define GNOVERLAY_CORE_H

#include <dirent.h>
#include <stddef.h>
#include <stdint.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>

#define GNO_PATH_MAX 4096

#define GNO_RENAME_NOREPLACE (1u << 0)
#define GNO_RENAME_EXCHANGE  (1u << 1)

/** libc entry points used by the core; every file-system call the core makes goes through here. */
typedef struct gno_ops {
    int (*open)(const char *path, int flags, mode_t mode);
    int (*close)(int fd);
    ssize_t (*read)(int fd, void *buf, size_t n);
    ssize_t (*write)(int fd, const void *buf, size_t n);
    int (*fstat)(int fd, struct stat *st);
    int (*stat)(const char *path, struct stat *st);
    int (*lstat)(const char *path, struct stat *st);
    int (*mkdir)(const char *path, mode_t mode);
    int (*rmdir)(const char *path);
    int (*unlink)(const char *path);
    int (*rename)(const char *from, const char *to);
    int (*rename_exchange)(const char *a, const char *b);
    /** rename that fails with EEXIST instead of replacing (RENAME_NOREPLACE); may be NULL. */
    int (*rename_noreplace)(const char *from, const char *to);
    ssize_t (*readlink)(const char *path, char *buf, size_t n);
    int (*symlink)(const char *target, const char *path);
    int (*link)(const char *from, const char *to);
    int (*fchmod)(int fd, mode_t mode);
    int (*futimens)(int fd, const struct timespec ts[2]);
    DIR *(*opendir)(const char *path);
    struct dirent *(*readdir)(DIR *d);
    int (*closedir)(DIR *d);
    ssize_t (*flistxattr)(int fd, char *list, size_t n);
    ssize_t (*fgetxattr)(int fd, const char *name, void *val, size_t n);
    int (*fsetxattr)(int fd, const char *name, const void *val, size_t n, int flags);
    char *(*getcwd)(char *buf, size_t n);
    /** Absolute path of an open fd (readlink of /proc/self/fd/N on Linux). Returns length or -1. */
    ssize_t (*fd_path)(int fd, char *buf, size_t n);
    /** Optional extra log destination (logcat on Android). */
    void (*log_sink)(const char *msg);
} gno_ops;

enum { GNO_PASS = 0, GNO_IN = 1 };
enum { GNO_NONE = 0, GNO_UPPER = 1, GNO_LOWER = 2 };

/**
 * Result of path resolution.
 * GNO_IN: path is the overlay-relative path ("" for the root), textually normalised.
 * GNO_PASS: path (only filled by gno_resolve_abs) is the absolute normalised path.
 */
typedef struct gno_res {
    int kind;
    char path[GNO_PATH_MAX];
} gno_res;

typedef struct gno_dent {
    uint64_t ino;
    unsigned char type;
    unsigned char side;
    char *name;
} gno_dent;

typedef struct gno_snap {
    gno_dent *e;
    size_t n;
    size_t cap;
} gno_snap;

typedef struct gno_dir gno_dir;

/** Loop iterations spent in path scanning/normalisation since the last reset (for tests). */
extern unsigned long gno_iters;

/** Configure the overlay. Returns 1 when active, 0 when disabled (bad/missing dirs). */
int gno_init(const gno_ops *ops, const char *upper, const char *lower, const char *aliases,
             int debug, const char *logpath);
int gno_active(void);
void gno_shutdown(void);
void gno_log(const char *fmt, ...) __attribute__((format(printf, 1, 2)));

/** Cheap test: 0 means the absolute path can never be inside the overlay. */
int gno_candidate(const char *path);

/** Lexically normalise path (relative to dirfd or cwd) into an absolute path. */
int gno_normalize(int dirfd, const char *path, char *out, size_t outsz);

/** Returns GNO_PASS (use the caller's original arguments), GNO_IN, or -1 with errno. */
int gno_resolve(int dirfd, const char *path, gno_res *r);
/** Like gno_resolve but r->path always holds an absolute path for PASS results. */
int gno_resolve_abs(int dirfd, const char *path, gno_res *r);

/** Side holding r (lstat semantics) or GNO_NONE with errno set; st and real may be NULL. */
int gno_lookup(const gno_res *r, struct stat *st, char *real);
void gno_upper_path(const char *rel, char *out);
void gno_lower_path(const char *rel, char *out);

/**
 * Follow a symlink in the last component of r (up to 8 hops). Updates r->path.
 * Returns 0 when the target is inside the overlay, 1 when it leaves it (outside gets the
 * absolute target), or -1 with errno.
 */
int gno_follow(gno_res *r, char *outside);

/** Real path for a read-only operation. follow: resolve a final symlink first. 0 or -1. */
int gno_read_path(gno_res *r, int follow, char *out);

/**
 * Prepare an open() of r with the caller's flags. Fills out with the path to open, sets
 * *created_new when gno_finish_create must run after a successful open, and *is_dir when the
 * target is an existing directory. May update r->path when a final symlink is followed.
 */
int gno_prepare_open(gno_res *r, int flags, char *out, int *created_new, int *is_dir);
void gno_finish_create(const gno_res *r);
/** fopen mode string to open(2) flags. */
int gno_fopen_flags(const char *mode);

/** Ensure r exists in upper (copy-up of file/symlink, or creation of the directory node). */
int gno_materialize(gno_res *r, int follow, int copy_data, char *path_out);

int gno_unlink(const gno_res *r);
int gno_rmdir(const gno_res *r);
int gno_mkdir(const gno_res *r, mode_t mode);
int gno_symlink(const char *target, const gno_res *r);
/** Either side may be GNO_PASS with an absolute path. */
int gno_link(const gno_res *from, const gno_res *to);
int gno_rename(const gno_res *from, const gno_res *to, unsigned flags);
/** realpath() replacement for overlay paths; lower paths come back in upper spelling. */
int gno_realpath(gno_res *r, char *out);

int gno_snapshot(const char *rel, gno_snap *s);
void gno_snap_free(gno_snap *s);

gno_dir *gno_opendir(const gno_res *r, int fd);
int gno_is_dir(const void *d);
struct dirent *gno_readdir(gno_dir *d);
int gno_closedir(gno_dir *d);
void gno_rewinddir(gno_dir *d);
long gno_telldir(gno_dir *d);
void gno_seekdir(gno_dir *d, long pos);
int gno_dirfd(gno_dir *d);

/**
 * If fd refers to a file in the lower layer, copy it up and write its upper path to out.
 * Returns 1 (copied up; operate on out), 0 (not a lower fd; pass through), -1 error.
 */
int gno_fd_lower_copyup(int fd, char *out);

/**
 * dlsym hook decision. Returns hooks[i] when name == names[i], the lookup was not RTLD_NEXT, and
 * resolved is either the real libc function reals[i] or hooks[i]; otherwise returns resolved.
 */
void *gno_dlsym_pick(int handle_is_next, const char *name, void *resolved, const char *const *names,
                     void *const *hooks, void *const *reals, size_t n);

#endif
