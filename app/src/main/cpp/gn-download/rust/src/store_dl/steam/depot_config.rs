//! The per-install depot store, kept in `<game dir>/.DepotDownloader/`.
//!
//! Two roles, two directories. They used to share one gid-keyed cache plus a pointer file
//! (`depot.config`), which meant every consumer had to reconstruct which role it was looking at —
//! and the delta, the sweep and VERIFY all read that pointer. Splitting them removes the pointer and
//! the state that only existed to make one file serve two jobs:
//!
//! * `target/<depot>_<gid>.manifest` — the manifests THIS press is installing/updating to. Fetched
//!   when the run starts, never reused from an earlier press (a resume is a new press, so it reads
//!   what the CDN has *now* instead of finishing a build published days ago), and read from disk for
//!   the rest of the run.
//! * `completed/<depot>_<gid>.manifest` — written only when that depot's writes succeeded, and the
//!   only durable manifest state there is. These FILENAMES are the installed record: which depots are
//!   installed and at which gid is read from the names, so there is no pointer to go stale and a
//!   half-written depot can never be mistaken for an installed one (the old store wrote a sentinel
//!   gid in that case).
//!
//! Who reads what: the update delta and the removed-content sweep diff `completed/` (the build the
//! tree is) against `target/` (the build it is becoming); VERIFY reads `completed/` and needs no
//! fetch at all, because the gid it verifies against is by construction one that completed.
//!
//! `<depot>_<gid>.inflight` marks a target whose writes started and never finished. That depot is
//! walked in full next time: the interrupted run may have half-rewritten a file that the *new*
//! target lists as unchanged, which the delta would otherwise trust.

use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};

/// Folder holding the manifests the current press is installing (see the module docs).
pub const TARGET_DIR: &str = "target";
/// Folder holding one manifest per depot: the build that last completed there.
pub const COMPLETED_DIR: &str = "completed";
const MANIFEST_SUFFIX: &str = ".manifest";
const INFLIGHT_SUFFIX: &str = ".inflight";

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct DepotConfigStore {
    config_dir: PathBuf,
    /// `<depot> -> gid`, scanned from `completed/` at load and kept in step with it by the mutating
    /// methods. The directory is the source of truth; this is the same data, read once.
    installed: BTreeMap<u32, u64>,
}

impl DepotConfigStore {
    pub fn load(config_dir: impl Into<PathBuf>) -> Self {
        let config_dir = config_dir.into();
        let installed = scan_completed(&config_dir);
        Self {
            config_dir,
            installed,
        }
    }

    pub fn config_dir(&self) -> &Path {
        &self.config_dir
    }

    /// Where the manifests of this press live.
    pub fn target_dir(&self) -> PathBuf {
        self.config_dir.join(TARGET_DIR)
    }

    /// Where the manifests of completed builds live — the installed record.
    pub fn completed_dir(&self) -> PathBuf {
        self.config_dir.join(COMPLETED_DIR)
    }

    pub fn target_manifest_path(&self, depot_id: u32, manifest_id: u64) -> PathBuf {
        self.target_dir()
            .join(manifest_file_name(depot_id, manifest_id))
    }

    pub fn completed_manifest_path(&self, depot_id: u32, manifest_id: u64) -> PathBuf {
        self.completed_dir()
            .join(manifest_file_name(depot_id, manifest_id))
    }

    /// True when this exact build is the one `completed/` records for the depot — the manifest is
    /// already on disk, so a run that asks for it (VERIFY pins to the installed gid) needs no fetch.
    pub fn has_completed(&self, depot_id: u32, manifest_id: u64) -> bool {
        self.is_installed(depot_id, manifest_id)
            && self
                .completed_manifest_path(depot_id, manifest_id)
                .is_file()
    }

    /// Every depot with a completed build, as `(depot_id, manifest_id)`. Used by the removed-content
    /// sweep to notice depots it cannot enumerate: their installed build's filenames need a depot key
    /// this layer does not hold.
    pub fn installed_depots(&self) -> Vec<(u32, u64)> {
        self.installed
            .iter()
            .map(|(depot, manifest)| (*depot, *manifest))
            .collect()
    }

    pub fn installed_manifest(&self, depot_id: u32) -> u64 {
        self.installed.get(&depot_id).copied().unwrap_or(0)
    }

    pub fn is_installed(&self, depot_id: u32, manifest_id: u64) -> bool {
        self.installed
            .get(&depot_id)
            .is_some_and(|installed| *installed == manifest_id)
    }

    /// Marks this depot's target as started. The marker is only removed by [`Self::finish_depot`], so
    /// a run that dies (or is cancelled) leaves it behind and the next run knows the tree may not be
    /// the requested build.
    pub fn begin_depot(&self, depot_id: u32, manifest_id: u64) -> bool {
        let path = inflight_path(&self.config_dir, depot_id, manifest_id);
        match path.parent() {
            Some(parent) => fs::create_dir_all(parent).is_ok() && fs::write(&path, b"").is_ok(),
            None => false,
        }
    }

    /// True when a previous run for this depot started a target and never finished it.
    pub fn interrupted(&self, depot_id: u32) -> bool {
        let Ok(entries) = fs::read_dir(&self.config_dir) else {
            return false;
        };
        entries.flatten().any(|entry| {
            entry
                .file_name()
                .to_str()
                .and_then(|name| name.strip_suffix(INFLIGHT_SUFFIX))
                .and_then(parse_depot_gid)
                .is_some_and(|(depot, _)| depot == depot_id)
        })
    }

    /// Records the depot as installed at `manifest_id`: the target manifest becomes the completed one
    /// (replacing whatever build was there), and the in-flight marker goes away.
    ///
    /// A target that is already the completed build (VERIFY reading its own manifest) has nothing to
    /// move — the record is simply refreshed.
    pub fn finish_depot(&mut self, depot_id: u32, manifest_id: u64) -> bool {
        let target = self.target_manifest_path(depot_id, manifest_id);
        let completed = self.completed_manifest_path(depot_id, manifest_id);
        if target.is_file() {
            if fs::create_dir_all(self.completed_dir()).is_err()
                || fs::rename(&target, &completed).is_err()
            {
                return false;
            }
        } else if !completed.is_file() {
            // Neither a fetched target nor an existing completed manifest: there is nothing to
            // record, so the run must not claim this depot is installed.
            return false;
        }
        drop_other_manifests(&self.completed_dir(), depot_id, manifest_id);
        self.clear_inflight(depot_id);
        self.installed.insert(depot_id, manifest_id);
        true
    }

    /// Drops this depot's target manifests. A press re-reads what the CDN has now, so anything an
    /// earlier press stored is stale by definition — for the same build too, which is what makes a
    /// resume refresh the manifest instead of finishing a build that may have been superseded.
    pub fn drop_stale_targets(&self, depot_id: u32) -> bool {
        let Ok(entries) = fs::read_dir(self.target_dir()) else {
            return true;
        };
        let mut ok = true;
        for entry in entries.flatten() {
            let name = entry.file_name();
            let own = name
                .to_str()
                .and_then(parse_manifest_file_name)
                .is_some_and(|(depot, _)| depot == depot_id);
            if own && fs::remove_file(entry.path()).is_err() {
                ok = false;
            }
        }
        ok
    }

    fn clear_inflight(&self, depot_id: u32) -> bool {
        let Ok(entries) = fs::read_dir(&self.config_dir) else {
            return true;
        };
        let mut ok = true;
        for entry in entries.flatten() {
            let name = entry.file_name();
            let other = name
                .to_str()
                .and_then(|name| name.strip_suffix(INFLIGHT_SUFFIX))
                .and_then(parse_depot_gid)
                .is_some_and(|(depot, _)| depot == depot_id);
            if other && fs::remove_file(entry.path()).is_err() {
                ok = false;
            }
        }
        ok
    }
}

/// `"<depot>_<gid>.manifest"` -> `(depot, gid)`.
pub fn parse_manifest_file_name(name: &str) -> Option<(u32, u64)> {
    parse_depot_gid(name.strip_suffix(MANIFEST_SUFFIX)?)
}

/// `"<depot>_<gid>"` -> `(depot, gid)`.
fn parse_depot_gid(stem: &str) -> Option<(u32, u64)> {
    let (depot, gid) = stem.split_once('_')?;
    Some((depot.parse().ok()?, gid.parse().ok()?))
}

fn manifest_file_name(depot_id: u32, manifest_id: u64) -> String {
    format!("{depot_id}_{manifest_id}{MANIFEST_SUFFIX}")
}

fn inflight_path(config_dir: &Path, depot_id: u32, manifest_id: u64) -> PathBuf {
    config_dir.join(format!("{depot_id}_{manifest_id}{INFLIGHT_SUFFIX}"))
}

/// The installed record: one manifest per depot in `completed/`.
fn scan_completed(config_dir: &Path) -> BTreeMap<u32, u64> {
    let mut newest: BTreeMap<u32, (u64, Option<std::time::SystemTime>)> = BTreeMap::new();
    let Ok(entries) = fs::read_dir(config_dir.join(COMPLETED_DIR)) else {
        return BTreeMap::new();
    };
    for entry in entries.flatten() {
        let name = entry.file_name();
        let Some((depot, gid)) = name.to_str().and_then(parse_manifest_file_name) else {
            continue;
        };
        // Promotion replaces the previous manifest, so a second one for a depot only appears if an
        // interrupted promotion left it behind: the newest wins rather than the record being lost.
        let modified = entry.metadata().and_then(|meta| meta.modified()).ok();
        match newest.get(&depot) {
            Some((_, existing)) if existing >= &modified => {}
            _ => {
                newest.insert(depot, (gid, modified));
            }
        }
    }
    newest
        .into_iter()
        .map(|(depot, (gid, _))| (depot, gid))
        .collect()
}

/// Removes every `<depot>_<gid>.manifest` in `dir` except `keep_manifest_id`. A missing directory is
/// not an error: nothing is stored there yet.
fn drop_other_manifests(dir: &Path, depot_id: u32, keep_manifest_id: u64) -> bool {
    let Ok(entries) = fs::read_dir(dir) else {
        return true;
    };
    let mut ok = true;
    for entry in entries.flatten() {
        let name = entry.file_name();
        let other = name
            .to_str()
            .and_then(parse_manifest_file_name)
            .is_some_and(|(depot, gid)| depot == depot_id && gid != keep_manifest_id);
        if other && fs::remove_file(entry.path()).is_err() {
            ok = false;
        }
    }
    ok
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn completed_filenames_are_the_installed_record() {
        let dir = temp_dir("completed_record");
        let mut store = DepotConfigStore::load(&dir);
        assert_eq!(store.installed_manifest(100), 0);
        assert!(!store.is_installed(100, 0));
        assert!(!store.has_completed(100, 555));

        assert!(store.begin_depot(100, 555));
        assert!(store.interrupted(100), "a started depot is in flight");
        fs::create_dir_all(store.target_dir()).unwrap();
        fs::write(store.target_manifest_path(100, 555), b"manifest").unwrap();
        assert!(store.finish_depot(100, 555));

        assert!(!store.interrupted(100), "promotion clears the marker");
        assert_eq!(
            store.completed_manifest_path(100, 555),
            dir.join("completed").join("100_555.manifest")
        );
        assert!(store.completed_manifest_path(100, 555).is_file());
        assert!(
            !store.target_manifest_path(100, 555).exists(),
            "the manifest was promoted, not copied"
        );
        let reloaded = DepotConfigStore::load(&dir);
        assert!(reloaded.is_installed(100, 555));
        assert!(reloaded.has_completed(100, 555));
        assert_eq!(reloaded.installed_manifest(100), 555);
        assert_eq!(reloaded.installed_depots(), vec![(100, 555)]);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_new_build_replaces_the_previous_manifest() {
        let dir = temp_dir("completed_replaces");
        let mut store = DepotConfigStore::load(&dir);
        for gid in [111u64, 222] {
            fs::create_dir_all(store.target_dir()).unwrap();
            fs::write(store.target_manifest_path(100, gid), b"manifest").unwrap();
            assert!(store.finish_depot(100, gid));
        }
        assert!(store.is_installed(100, 222));
        assert!(!store.is_installed(100, 111));
        assert!(
            !store.completed_manifest_path(100, 111).exists(),
            "the previous build's manifest is not kept — the tree is no longer that build"
        );
        assert_eq!(DepotConfigStore::load(&dir).installed_manifest(100), 222);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn finish_without_a_manifest_does_not_invent_a_record() {
        let dir = temp_dir("completed_needs_manifest");
        let mut store = DepotConfigStore::load(&dir);
        assert!(!store.finish_depot(100, 555));
        assert_eq!(DepotConfigStore::load(&dir).installed_manifest(100), 0);

        // A verify reads its manifest straight out of `completed/`, so there is no target to move and
        // the record must survive the promotion untouched.
        fs::create_dir_all(store.completed_dir()).unwrap();
        fs::write(store.completed_manifest_path(100, 555), b"manifest").unwrap();
        assert!(store.finish_depot(100, 555));
        assert!(store.is_installed(100, 555));
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_new_press_drops_the_previous_target_manifests() {
        let dir = temp_dir("stale_targets");
        let store = DepotConfigStore::load(&dir);
        fs::create_dir_all(store.target_dir()).unwrap();
        // Same gid as an earlier press PLUS a different one: neither is reused, the press refetches.
        fs::write(store.target_manifest_path(100, 222), b"earlier press").unwrap();
        fs::write(store.target_manifest_path(100, 333), b"earlier press").unwrap();
        fs::write(store.target_manifest_path(200, 111), b"other depot").unwrap();

        assert!(store.drop_stale_targets(100));
        assert!(!store.target_manifest_path(100, 222).exists());
        assert!(!store.target_manifest_path(100, 333).exists());
        assert!(
            store.target_manifest_path(200, 111).is_file(),
            "another depot's target is untouched"
        );
        // The installed record is a different directory and is never touched by a press.
        assert!(store.drop_stale_targets(100));
        let _ = fs::remove_dir_all(&dir);
    }

    fn temp_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "blsteam_{name}_{}",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        let _ = fs::remove_dir_all(&dir);
        dir
    }
}
