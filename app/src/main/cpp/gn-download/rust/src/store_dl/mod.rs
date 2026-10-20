//! Store download engines (Steam / Epic / GOG / Amazon) built on [`crate::fetch_core`].
//!
//! Each store module owns its manifest → plan → `FetchItem` mapping, its `FetchSink` (decrypt /
//! inflate + hash + write in the Java manager's exact output layout) and its JNI facade.
//! Resume/skip, retry semantics and error surfaces mirror the corresponding Java manager
//! one-to-one — see `docs/RUST_STORE_ENGINES.md` and the per-store `docs/RUST_<STORE>_PARITY.md`.

pub mod amazon;
pub mod epic;
pub mod gog;
pub mod ordered_drain;
pub mod steam;

/// Path-traversal guard for manifest/server-supplied relative paths. Every store joins its
/// manifest filenames onto `install_dir` textually, so an entry like `../../x` (or an absolute
/// path, which `Path::join` lets REPLACE the base) escapes the install dir — and error
/// cleanup would then `remove_file` outside it. Rejects empty, absolute, `..`-containing and
/// NUL-containing paths after normalising '\\' to '/'. Reject the manifest (or skip the
/// entry, per store parity) at PARSE/PLAN time, never mid-download.
pub(crate) fn rel_path_is_safe(p: &str) -> bool {
    let p = p.replace('\\', "/");
    !p.is_empty()
        && !p.starts_with('/')
        && !p.split('/').any(|seg| seg == "..")
        && !p.contains('\0')
}

/// Result of [`canonicalize_case_paths`]: the canonical spelling and the case-folded identity
/// of every input path.
pub(crate) struct CaseCanonicalization {
    /// Canonical spelling per input index (first-seen spelling per path component).
    pub paths: Vec<String>,
    /// Case-folded full-path key per input index — two entries sharing a key are the SAME
    /// file on a case-insensitive filesystem (Windows, Android shared storage, exFAT SD).
    pub keys: Vec<String>,
}

/// Canonicalize manifest relative paths case-insensitively, reproducing Windows/Android-storage
/// semantics on ANY filesystem: every path component adopts the FIRST-seen spelling, so
/// `Game/a` and `game/b` land in ONE on-disk directory even on case-sensitive storage, and
/// entries whose full paths differ only in case share one canonical path (their `keys` match —
/// the store decides which one to keep; Steam keeps the LAST, manifest-order last-writer-wins).
/// Without this, `Game/` + `game/` depots (e.g. Steam 16451/16452) split into twin directories
/// and case-duplicate files race two writers onto one inode.
pub(crate) fn canonicalize_case_paths(paths: &[&str]) -> CaseCanonicalization {
    use std::collections::HashMap;
    // folded prefix -> canonical spelling of that prefix (first-seen wins).
    let mut canon: HashMap<String, String> = HashMap::new();
    let mut out_paths = Vec::with_capacity(paths.len());
    let mut out_keys = Vec::with_capacity(paths.len());
    for p in paths {
        let norm = p.replace('\\', "/");
        let mut prefix = String::new(); // canonical, always ends with '/' while building
        let mut folded = String::new(); // folded, same shape
        for seg in norm.split('/') {
            if seg.is_empty() {
                continue;
            }
            folded.push_str(&seg.to_lowercase());
            folded.push('/');
            let key = &folded[..folded.len() - 1];
            if !canon.contains_key(key) {
                let spelling = format!("{prefix}{seg}");
                canon.insert(key.to_string(), spelling);
            }
            // canon stores the canonical FULL prefix — replace, don't append.
            prefix = canon[key].clone();
            prefix.push('/');
        }
        let canonical = prefix.trim_end_matches('/').to_string();
        let key = folded.trim_end_matches('/').to_string();
        out_paths.push(canonical);
        out_keys.push(key);
    }
    CaseCanonicalization {
        paths: out_paths,
        keys: out_keys,
    }
}

/// Re-spell each EXISTING component of `base/rel` to its on-disk case, returning the re-spelled
/// RELATIVE path. Resume finds files an older manifest wrote with different casing, and a second
/// depot writing `game/` after another wrote `Game/` lands in the existing directory. Components
/// that don't exist yet keep the manifest's spelling (they are about to be created, already
/// canonicalized). On a case-insensitive filesystem the exact-match fast path always hits, so one
/// call costs almost nothing — but a per-file caller should use [`CaseResolver`], which caches the
/// directory listings (see below).
pub(crate) fn resolve_existing_case(base: &str, rel: &str) -> String {
    CaseResolver::new().resolve(base, rel)
}

/// Case-insensitive on-disk spelling resolver with a per-directory listing cache, for the loops
/// that re-spell every file of a manifest (depot planning/prepare, store sweeps).
///
/// [`resolve_existing_case`] alone costs a `stat` per path component per file, plus a `read_dir`
/// and a linear, lowercasing scan of the parent for EVERY component that does not exist with the
/// manifest's spelling — which on an update is every new file, re-scanning the whole (possibly
/// thousands-of-entries) parent directory each time. The pre-passes that run before a depot's first
/// chunk call it once per file, so on storage where metadata ops dominate the run (FUSE/sdcardfs,
/// exFAT SD) they were the bulk of the wait before the first `Verifying Files (n/N)` status could be
/// reported at all: measured on a 40k-file tree, plan+prepare spent 296 ms in resolution against a
/// 48 ms plain `stat`-per-file floor — ~6 avoidable metadata ops per file, on a warm NVMe metadata
/// cache; the same shape costs tens of seconds per 40k files on device storage.
///
/// Each component is still checked with one `exists()` stat first (the exact-spelling fast path —
/// on a case-insensitive filesystem, and for the overwhelming majority of resumes, that hits), so a
/// one-shot caller pays exactly what it paid before and never worse. The cache only answers the
/// MISSES: a component that does not exist with the manifest's spelling, which is where the old
/// code scanned the parent directory — once per miss, i.e. once per new file in the update case.
///
/// Resolve before creating entries: a cached listing does not see files written afterwards (the
/// plan/prepare passes run before the layout pass and before any chunk write, which is what this is
/// for). A directory that cannot be listed is cached as such; a miss below it stays a miss, exactly
/// as the old `read_dir`-failure path behaved.
pub(crate) struct CaseResolver {
    /// Directory -> `(case-folded name, on-disk name)` for every entry, or `None` when the
    /// directory could not be listed (then a miss below it is a miss — the old behaviour).
    dirs: std::collections::HashMap<std::path::PathBuf, Option<Vec<(String, String)>>>,
}

impl CaseResolver {
    pub(crate) fn new() -> Self {
        Self {
            dirs: std::collections::HashMap::new(),
        }
    }

    /// Re-spell each EXISTING component of `base/rel` to its on-disk case (see
    /// [`resolve_existing_case`]). Components that don't exist keep the manifest's spelling.
    pub(crate) fn resolve(&mut self, base: &str, rel: &str) -> String {
        let mut current = std::path::PathBuf::from(base);
        let mut out = String::new();
        let mut missing = false;
        for seg in rel.replace('\\', "/").split('/') {
            if seg.is_empty() {
                continue;
            }
            // `.` is accepted by `path_is_safe` (`./game/data.bin`) but `read_dir` never lists it,
            // so it must pass through untouched: looking it up would mark the whole remaining path
            // missing and lose the case re-spelling of every component after it.
            let mut chosen = seg.to_string();
            if !missing && seg != "." && !current.join(seg).exists() {
                match self.lookup(&current, seg) {
                    Some(found) => chosen = found,
                    None => missing = true,
                }
            }
            if !out.is_empty() {
                out.push('/');
            }
            out.push_str(&chosen);
            current.push(&chosen);
        }
        out
    }

    /// The on-disk spelling of `name` inside `dir` — only called once the exact spelling has been
    /// ruled out by the caller's `exists()`: prefers an entry whose stored spelling is already
    /// exact (a dangling symlink, which `exists()` follows and rejects), else the first
    /// case-insensitive match in directory order, else `None`.
    fn lookup(&mut self, dir: &std::path::Path, name: &str) -> Option<String> {
        if !self.dirs.contains_key(dir) {
            let listing = std::fs::read_dir(dir).ok().map(|rd| {
                rd.filter_map(|e| e.ok())
                    .map(|e| {
                        let on_disk = e.file_name().to_string_lossy().into_owned();
                        (on_disk.to_lowercase(), on_disk)
                    })
                    .collect::<Vec<_>>()
            });
            self.dirs.insert(dir.to_path_buf(), listing);
        }
        let entries = self.dirs.get(dir)?.as_ref()?;
        if let Some((_, on_disk)) = entries.iter().find(|(_, on_disk)| on_disk == name) {
            return Some(on_disk.clone());
        }
        let folded = name.to_lowercase();
        entries
            .iter()
            .find(|(folded_name, _)| *folded_name == folded)
            .map(|(_, on_disk)| on_disk.clone())
    }
}

#[cfg(test)]
mod tests {
    use super::{canonicalize_case_paths, rel_path_is_safe, resolve_existing_case, CaseResolver};

    #[test]
    fn rel_path_is_safe_rules() {
        assert!(rel_path_is_safe("a/b/file.txt"));
        assert!(rel_path_is_safe("./a/file.txt"));
        assert!(rel_path_is_safe("a\\b\\file.txt")); // backslashes normalise away
        assert!(!rel_path_is_safe(""));
        assert!(!rel_path_is_safe("/abs/file.txt"));
        assert!(!rel_path_is_safe("//data/data/x")); // still absolute after one strip
        assert!(!rel_path_is_safe("../file.txt"));
        assert!(!rel_path_is_safe("a/../file.txt"));
        assert!(!rel_path_is_safe("a/../../file.txt"));
        assert!(!rel_path_is_safe("a/b\0c"));
    }

    #[test]
    fn canonicalize_merges_case_differing_directories_first_seen_wins() {
        let c = canonicalize_case_paths(&[
            "Game/data/pak0.pk4",
            "game/config.cfg",
            "GAME/Data/pak1.pk4",
            "other/file.txt",
        ]);
        assert_eq!(c.paths[0], "Game/data/pak0.pk4");
        assert_eq!(c.paths[1], "Game/config.cfg", "game/ folds into first-seen Game/");
        assert_eq!(c.paths[2], "Game/data/pak1.pk4", "nested dirs merge too");
        assert_eq!(c.paths[3], "other/file.txt");
        // Exact case-duplicates share a key; distinct files never do.
        let d = canonicalize_case_paths(&["Game/foo.txt", "game/FOO.txt", "game/bar.txt"]);
        assert_eq!(d.keys[0], d.keys[1]);
        assert_ne!(d.keys[0], d.keys[2]);
        assert_eq!(d.paths[1], "Game/foo.txt", "first-seen spelling canonical for both");
        // Backslashes and case-fold are stable.
        let b = canonicalize_case_paths(&["Game\\Foo.txt"]);
        assert_eq!(b.paths[0], "Game/Foo.txt");
    }

    #[test]
    fn resolve_existing_case_respells_to_on_disk_spelling() {
        let dir = std::env::temp_dir().join(format!("casekey-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join("Game/data")).unwrap();
        std::fs::write(dir.join("Game/data/PAK0.pk4"), b"x").unwrap();
        let base = dir.to_string_lossy().into_owned();
        // Existing components re-spell to on-disk case...
        assert_eq!(
            resolve_existing_case(&base, "game/data/pak0.PK4"),
            "Game/data/PAK0.pk4"
        );
        // ...a missing tail keeps the manifest spelling (about to be created).
        assert_eq!(
            resolve_existing_case(&base, "game/newdir/file.bin"),
            "Game/newdir/file.bin"
        );
        assert_eq!(resolve_existing_case(&base, "fresh/file.bin"), "fresh/file.bin");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn case_handling_recurses_through_deep_nesting() {
        // Canonicalize: every component level re-spells independently; first-seen spelling is
        // tracked per PATH PREFIX, so `game/Data/other/` keeps `other/` while merging parents.
        let c = canonicalize_case_paths(&[
            "Game/Data/Sub/Deep/a.pk4",
            "game/data/sub/deep/b.pk4",
            "GAME/DATA/SUB/DEEP/c.pk4",
            "game/Data/other/d.pk4",
        ]);
        assert_eq!(c.paths[0], "Game/Data/Sub/Deep/a.pk4");
        assert_eq!(c.paths[1], "Game/Data/Sub/Deep/b.pk4");
        assert_eq!(c.paths[2], "Game/Data/Sub/Deep/c.pk4");
        assert_eq!(
            c.paths[3], "Game/Data/other/d.pk4",
            "parents merge into first-seen case, the new child keeps its own spelling"
        );

        // Resolve: re-spell every level of a deep existing path; a missing deep tail keeps
        // the manifest spelling below the last existing component.
        let dir = std::env::temp_dir().join(format!("casekey-deep-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join("Game/Data/Sub/Deep")).unwrap();
        std::fs::write(dir.join("Game/Data/Sub/Deep/Leaf.BIN"), b"x").unwrap();
        let base = dir.to_string_lossy().into_owned();
        assert_eq!(
            resolve_existing_case(&base, "gAmE/dAtA/sUb/dEeP/leaf.bin"),
            "Game/Data/Sub/Deep/Leaf.BIN"
        );
        assert_eq!(
            resolve_existing_case(&base, "game/data/sub/deep/New/leaf.bin"),
            "Game/Data/Sub/Deep/New/leaf.bin"
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The pre-cache algorithm, kept verbatim as the reference the cached resolver must match:
    /// one `exists()` per component, and a `read_dir` + folded scan only for a component that is
    /// not found exactly. `resolve_existing_case` now delegates to [`CaseResolver`], so comparing
    /// against itself would prove nothing.
    fn legacy_resolve_existing_case(base: &str, rel: &str) -> String {
        let mut current = std::path::PathBuf::from(base);
        let mut out = String::new();
        let mut missing = false;
        for seg in rel.replace('\\', "/").split('/') {
            if seg.is_empty() {
                continue;
            }
            let mut chosen = seg.to_string();
            if !missing && !current.join(seg).exists() {
                match std::fs::read_dir(&current).ok().and_then(|rd| {
                    rd.filter_map(|e| e.ok())
                        .map(|e| e.file_name().to_string_lossy().into_owned())
                        .find(|name| name.to_lowercase() == seg.to_lowercase())
                }) {
                    Some(found) => chosen = found,
                    None => missing = true,
                }
            }
            if !out.is_empty() {
                out.push('/');
            }
            out.push_str(&chosen);
            current.push(&chosen);
        }
        out
    }

    #[test]
    fn case_resolver_matches_the_legacy_algorithm() {
        let dir = std::env::temp_dir().join(format!("casekey-resolver-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join("Game/data/Sub")).unwrap();
        std::fs::write(dir.join("Game/data/Sub/Leaf.BIN"), b"x").unwrap();
        let base = dir.to_string_lossy().into_owned();
        let cases = [
            "gAmE/dAtA/sUb/leaf.bin",        // every component re-spells
            "game/data/sub/leaf.bin",        // same, all lower
            "./game/data/sub/leaf.bin",      // `.` component: accepted by path_is_safe, never listed
            ".\\Game\\DATA\\sub\\leaf.bin", // `.` + backslashes
            "./fresh/file.bin",              // `.` then a missing component
            "Game/data/sub/deep/new.bin",    // missing middle keeps spelling below the last hit
            "fresh/file.bin",                // missing from the root
            "Game/data/Sub/Leaf.BIN",        // already exact
            "game/data/sub",                 // directory-only relative path
            "Game/missing/Sub/Leaf.BIN",     // gap in the middle, exact tail
        ];
        let mut cached = CaseResolver::new();
        for rel in cases {
            assert_eq!(
                cached.resolve(&base, rel),
                legacy_resolve_existing_case(&base, rel),
                "cached resolver must agree with the legacy algorithm for {rel}"
            );
        }
        // A fresh resolver per case must agree too (one-shot callers).
        for rel in cases {
            assert_eq!(
                CaseResolver::new().resolve(&base, rel),
                legacy_resolve_existing_case(&base, rel),
                "one-shot resolve must agree with the legacy algorithm for {rel}"
            );
        }
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn case_resolver_keeps_resolving_when_a_directory_cannot_be_listed() {
        // The legacy code decided with `exists()` per component, so a directory it could not LIST
        // still kept its exact-spelled components (and the components after them) resolvable. The
        // cached lookups must not turn that into "the rest of the path is missing".
        let dir = std::env::temp_dir().join(format!("casekey-unlistable-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join("Game/data")).unwrap();
        std::fs::write(dir.join("Game/data/Leaf.BIN"), b"x").unwrap();
        let base = dir.to_string_lossy().into_owned();
        let mut r = CaseResolver::new();
        // Poison the cache the way a read_dir failure (EACCES/EIO) would: no listing available.
        r.dirs.insert(dir.join("Game"), None);
        assert_eq!(
            r.resolve(&base, "Game/data/leaf.bin"),
            "Game/data/Leaf.BIN",
            "an unlistable `Game/` must not stop `data/` and the file from re-spelling"
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn case_resolver_prefers_the_exact_spelling_over_a_folded_match() {
        // Only distinguishable on a case-SENSITIVE filesystem: the legacy `exists()` fast path
        // picked the exact component, so the cached version must too.
        let dir = std::env::temp_dir().join(format!("casekey-exact-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join("Data")).unwrap();
        std::fs::create_dir_all(dir.join("data")).unwrap();
        let distinct = std::fs::read_dir(&dir).unwrap().count() == 2;
        if !distinct {
            // Case-insensitive host (APFS/NTFS): the two creates collapsed into one entry, so the
            // exact-spelling distinction does not exist. Nothing to assert.
            let _ = std::fs::remove_dir_all(&dir);
            return;
        }
        let base = dir.to_string_lossy().into_owned();
        let mut r = CaseResolver::new();
        assert_eq!(r.resolve(&base, "data/f.bin"), "data/f.bin");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn case_resolver_snapshots_directory_listings() {
        // The listing cache is per pass, so a file created AFTER a directory was resolved is not
        // seen — that is the contract (plan → prepare resolve before the layout pass and before any
        // chunk write). Documented here so a future caller cannot assume fresh listings.
        let dir = std::env::temp_dir().join(format!("casekey-snapshot-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join("Game")).unwrap();
        std::fs::write(dir.join("Game/Existing.bin"), b"x").unwrap();
        let base = dir.to_string_lossy().into_owned();
        let mut r = CaseResolver::new();
        assert_eq!(r.resolve(&base, "game/existing.bin"), "Game/Existing.bin");
        std::fs::write(dir.join("Game/Created.Later"), b"x").unwrap();
        assert_eq!(
            r.resolve(&base, "game/created.later"),
            "Game/created.later",
            "cached listing: entries written after the first resolve are not picked up"
        );
        // A fresh resolver (the next pass) does see it.
        assert_eq!(
            CaseResolver::new().resolve(&base, "game/created.later"),
            "Game/Created.Later"
        );
        let _ = std::fs::remove_dir_all(&dir);
    }
}
