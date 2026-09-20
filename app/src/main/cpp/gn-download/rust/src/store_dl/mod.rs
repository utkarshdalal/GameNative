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

#[cfg(test)]
mod tests {
    use super::rel_path_is_safe;

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
}
