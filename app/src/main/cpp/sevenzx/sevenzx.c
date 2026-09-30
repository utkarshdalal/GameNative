/*
 * sevenzx: extract the files under one directory of a 7z archive that sits at a byte offset
 * inside another file (a 7z self-extractor). Public-domain LZMA SDK decoder underneath.
 *
 *   sevenzx <file> <offset> <out-dir> [prefix/]
 *
 * Prints "P <done> <total>" after each file. Exit 0 when every matching file was written,
 * 4 when there is no readable archive at the offset.
 */
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include "lzma/7z.h"
#include "lzma/7zAlloc.h"
#include "lzma/7zCrc.h"

typedef struct { ISeekInStream vt; FILE *f; Int64 base; } COffsetStream;

static SRes OffsetRead(const ISeekInStream *p, void *buf, size_t *size)
{
    COffsetStream *s = CONTAINER_FROM_VTBL(p, COffsetStream, vt);
    size_t n = fread(buf, 1, *size, s->f);
    if (n != *size && ferror(s->f)) return SZ_ERROR_READ;
    *size = n;
    return SZ_OK;
}

static SRes OffsetSeek(const ISeekInStream *p, Int64 *pos, ESzSeek origin)
{
    COffsetStream *s = CONTAINER_FROM_VTBL(p, COffsetStream, vt);
    int whence = origin == SZ_SEEK_SET ? SEEK_SET : origin == SZ_SEEK_CUR ? SEEK_CUR : SEEK_END;
    off_t target = origin == SZ_SEEK_SET ? (off_t)(s->base + *pos) : (off_t)*pos;
    if (fseeko(s->f, target, whence)) return SZ_ERROR_READ;
    *pos = (Int64)ftello(s->f) - s->base;
    return SZ_OK;
}

static size_t utf16ToUtf8(const UInt16 *src, size_t len, char *dst, size_t cap)
{
    size_t o = 0;
    for (size_t i = 0; i < len && src[i]; i++) {
        unsigned c = src[i];
        if (c >= 0xD800 && c < 0xDC00 && i + 1 < len) { c = 0x10000 + ((c - 0xD800) << 10) + (src[i + 1] - 0xDC00); i++; }
        if (c < 0x80) { if (o + 1 >= cap) break; dst[o++] = (char)c; }
        else if (c < 0x800) { if (o + 2 >= cap) break; dst[o++] = (char)(0xC0 | (c >> 6)); dst[o++] = (char)(0x80 | (c & 0x3F)); }
        else if (c < 0x10000) { if (o + 3 >= cap) break; dst[o++] = (char)(0xE0 | (c >> 12)); dst[o++] = (char)(0x80 | ((c >> 6) & 0x3F)); dst[o++] = (char)(0x80 | (c & 0x3F)); }
        else { if (o + 4 >= cap) break; dst[o++] = (char)(0xF0 | (c >> 18)); dst[o++] = (char)(0x80 | ((c >> 12) & 0x3F)); dst[o++] = (char)(0x80 | ((c >> 6) & 0x3F)); dst[o++] = (char)(0x80 | (c & 0x3F)); }
    }
    dst[o] = 0;
    return o;
}

static int safeRelative(const char *rel)
{
    if (!*rel || *rel == '/') return 0;
    for (const char *p = rel; *p; ) {
        const char *q = strchr(p, '/');
        size_t n = q ? (size_t)(q - p) : strlen(p);
        if (n == 0 || (n == 1 && p[0] == '.') || (n == 2 && p[0] == '.' && p[1] == '.')) return 0;
        if (!q) break;
        p = q + 1;
    }
    return 1;
}

static int makeParents(char *path)
{
    for (char *p = path + 1; *p; p++) {
        if (*p != '/') continue;
        *p = 0;
        if (mkdir(path, 0700) && errno != EEXIST) { *p = '/'; return 0; }
        *p = '/';
    }
    return 1;
}

int main(int argc, char **argv)
{
    if (argc < 4) { fprintf(stderr, "usage: sevenzx <file> <offset> <out-dir> [prefix/]\n"); return 2; }
    const char *prefix = argc > 4 ? argv[4] : "";
    size_t prefixLen = strlen(prefix);
    COffsetStream in; memset(&in, 0, sizeof in);
    in.f = fopen(argv[1], "rb");
    if (!in.f) { fprintf(stderr, "cannot open %s\n", argv[1]); return 3; }
    in.base = (Int64)strtoll(argv[2], NULL, 10);
    in.vt.Read = OffsetRead; in.vt.Seek = OffsetSeek;
    if (fseeko(in.f, (off_t)in.base, SEEK_SET)) { fprintf(stderr, "bad offset\n"); return 3; }

    ISzAlloc allocImp = { SzAlloc, SzFree }, allocTempImp = { SzAllocTemp, SzFreeTemp };
    CLookToRead2 look; LookToRead2_CreateVTable(&look, False);
    look.buf = (Byte *)ISzAlloc_Alloc(&allocImp, 1 << 18); look.bufSize = 1 << 18; look.realStream = &in.vt; LookToRead2_Init(&look);
    CrcGenerateTable();
    CSzArEx db; SzArEx_Init(&db);
    SRes res = SzArEx_Open(&db, &look.vt, &allocImp, &allocTempImp);
    if (res != SZ_OK) { fprintf(stderr, "not a readable 7z archive at offset %lld (%d)\n", (long long)in.base, res); return 4; }

    UInt16 *name16 = NULL; size_t name16Cap = 0; char name[4096], out[4096];
    UInt64 total = 0, done = 0; unsigned written = 0;
    for (UInt32 i = 0; i < db.NumFiles; i++) {
        if (SzArEx_IsDir(&db, i)) continue;
        size_t len = SzArEx_GetFileNameUtf16(&db, i, NULL);
        if (len > name16Cap) { free(name16); name16Cap = len; name16 = (UInt16 *)malloc(len * sizeof *name16); }
        SzArEx_GetFileNameUtf16(&db, i, name16);
        utf16ToUtf8(name16, len, name, sizeof name);
        if (strncmp(name, prefix, prefixLen) == 0 && safeRelative(name + prefixLen)) total += SzArEx_GetFileSize(&db, i);
    }
    UInt32 blockIndex = 0xFFFFFFFF; Byte *outBuffer = NULL; size_t outBufferSize = 0;
    for (UInt32 i = 0; i < db.NumFiles && res == SZ_OK; i++) {
        if (SzArEx_IsDir(&db, i)) continue;
        size_t len = SzArEx_GetFileNameUtf16(&db, i, NULL);
        SzArEx_GetFileNameUtf16(&db, i, name16);
        utf16ToUtf8(name16, len, name, sizeof name);
        if (strncmp(name, prefix, prefixLen) != 0) continue;
        const char *rel = name + prefixLen;
        if (!safeRelative(rel)) { fprintf(stderr, "skipping unsafe entry %s\n", name); continue; }
        size_t offset = 0, outSizeProcessed = 0;
        res = SzArEx_Extract(&db, &look.vt, i, &blockIndex, &outBuffer, &outBufferSize, &offset, &outSizeProcessed, &allocImp, &allocTempImp);
        if (res != SZ_OK) { fprintf(stderr, "decode failed for %s (%d)\n", name, res); break; }
        snprintf(out, sizeof out, "%s/%s", argv[3], rel);
        if (!makeParents(out)) { fprintf(stderr, "cannot create directory for %s\n", out); res = SZ_ERROR_WRITE; break; }
        FILE *f = fopen(out, "wb");
        if (!f || fwrite(outBuffer + offset, 1, outSizeProcessed, f) != outSizeProcessed) { fprintf(stderr, "cannot write %s\n", out); if (f) fclose(f); res = SZ_ERROR_WRITE; break; }
        fclose(f);
        written++; done += outSizeProcessed;
        printf("P %llu %llu\n", (unsigned long long)done, (unsigned long long)total); fflush(stdout);
    }
    ISzAlloc_Free(&allocImp, outBuffer); free(name16);
    SzArEx_Free(&db, &allocImp); ISzAlloc_Free(&allocImp, look.buf); fclose(in.f);
    if (res != SZ_OK) return 5;
    fprintf(stderr, "%u files written\n", written);
    return written ? 0 : 6;
}
