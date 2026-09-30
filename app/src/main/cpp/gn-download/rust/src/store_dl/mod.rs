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

/// Re-spell each EXISTING component of `base/rel` to its on-disk case (case-insensitive
/// directory scan), returning the re-spelled RELATIVE path. Resume finds files an older
/// manifest wrote with different casing, and a second depot writing `game/` after another
/// wrote `Game/` lands in the existing directory. Components that don't exist yet keep the
/// manifest's spelling (they are about to be created, already canonicalized). On a
/// case-insensitive filesystem the exact-match fast path always hits, so this costs nothing.
pub(crate) fn resolve_existing_case(base: &str, rel: &str) -> String {
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

#[cfg(test)]
mod tests {
    use super::{canonicalize_case_paths, rel_path_is_safe, resolve_existing_case};

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
}
