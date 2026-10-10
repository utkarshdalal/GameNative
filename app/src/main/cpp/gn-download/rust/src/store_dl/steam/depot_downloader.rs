use crate::store_dl::steam::cdn_client::{auth_status, CdnClient, CdnManifestResult};
use crate::store_dl::steam::content_manifest::ContentManifest;
use crate::store_dl::steam::depot_config::DepotConfigStore;
use crate::store_dl::steam::depot_writer::{
    write_depot_sequential, CdnAuthTokenRefresher, DepotWriteOptions, DEPOT_FILE_FLAG_DIRECTORY,
};
use crate::store_dl::steam::pb::ccontentserverdirectory::CContentServerDirectoryServerInfo;
use std::fs;
use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;
use std::time::Duration;

pub const MAX_MANIFEST_FETCH_ATTEMPTS: usize = 5;

/// Name of the per-install download journal directory, created next to the game files.
///
/// GameNative keeps this engine's journal in the same `.DepotDownloader/` directory the
/// JavaSteam DepotDownloader used: both record "installed manifest per depot" in the same
/// `installedManifestIDs` JSON format, and the app reads cached `<depot>_<manifest>.manifest`
/// files from this directory, so downloads resume seamlessly across the engine swap.
pub const CONFIG_DIR_NAME: &str = ".DepotDownloader";

/// `<install_dir>/.DepotDownloader`.
pub fn config_dir_path(install_dir: impl AsRef<Path>) -> PathBuf {
    install_dir.as_ref().join(CONFIG_DIR_NAME)
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct DepotSpec {
    pub depot_id: u32,
    pub manifest_id: u64,
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct ResolvedDepotSpec {
    pub depot_id: u32,
    pub manifest_id: u64,
    pub depot_key: Vec<u8>,
    pub manifest_request_code: u64,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct DepotDownloadProgress {
    pub depot_id: u32,
    pub depot_done: u64,
    pub depot_total: u64,
    pub depots_done: u32,
    pub depots_total: u32,
    pub verifying: bool,
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct DepotDownloadResult {
    pub success: bool,
    pub error: String,
    pub bytes_written: u64,
    pub depots_completed: u32,
    pub depots_skipped: u32,
}

impl DepotDownloadResult {
    pub fn fail(error: impl Into<String>) -> Self {
        Self {
            success: false,
            error: error.into(),
            ..Default::default()
        }
    }

    pub fn ok(bytes_written: u64, depots_completed: u32, depots_skipped: u32) -> Self {
        Self {
            success: true,
            bytes_written,
            depots_completed,
            depots_skipped,
            error: String::new(),
        }
    }
}

pub fn validate_download_inputs(
    install_dir: &str,
    depots: &[DepotSpec],
) -> Result<(), DepotDownloadResult> {
    if install_dir.is_empty() {
        return Err(DepotDownloadResult::fail("download: empty install dir"));
    }
    if depots.is_empty() {
        return Err(DepotDownloadResult::fail("download: no depots"));
    }
    Ok(())
}

pub fn validate_resolved_download_inputs(
    install_dir: &str,
    depots: &[ResolvedDepotSpec],
    servers: &[CContentServerDirectoryServerInfo],
) -> Result<(), DepotDownloadResult> {
    if install_dir.is_empty() {
        return Err(DepotDownloadResult::fail("download: empty install dir"));
    }
    if depots.is_empty() {
        return Err(DepotDownloadResult::fail("download: no depots"));
    }
    if servers.is_empty() {
        return Err(DepotDownloadResult::fail(
            "download: no CDN servers available",
        ));
    }
    Ok(())
}

pub fn filter_usable_cdn_servers(
    servers: impl IntoIterator<Item = CContentServerDirectoryServerInfo>,
) -> Vec<CContentServerDirectoryServerInfo> {
    servers
        .into_iter()
        .filter(|server| !server.steam_china_only && !server.host.is_empty())
        .collect()
}

/// Region preference for the CDN pool: Valve's own SteamPipe caches are named
/// `cache<N>-<dc>.steamcontent.com`, so servers whose host (or vhost) carries `-<dc>.` are moved to
/// the front, keeping the directory's order inside each group. Manifest fetches start at index 0
/// and chunk workers bias their rotation by index, so this steers most traffic to the chosen
/// datacenter while every other server stays available for rotation and retries. An empty `dc`
/// (Auto without a remembered winner) or no matching host leaves the order unchanged.
pub fn prefer_cdn_servers_for_dc(
    servers: Vec<CContentServerDirectoryServerInfo>,
    dc: &str,
) -> Vec<CContentServerDirectoryServerInfo> {
    let dc = dc.trim().to_ascii_lowercase();
    if dc.is_empty() {
        return servers;
    }
    let needle = format!("-{dc}.");
    let matches = |server: &CContentServerDirectoryServerInfo| {
        server.host.to_ascii_lowercase().contains(&needle)
            || server.vhost.to_ascii_lowercase().contains(&needle)
    };
    let (preferred, rest): (Vec<_>, Vec<_>) = servers.into_iter().partition(matches);
    if preferred.is_empty() {
        return rest;
    }
    preferred.into_iter().chain(rest).collect()
}

pub fn manifest_retry_server_indices(server_count: usize, attempts: usize) -> Vec<usize> {
    if server_count == 0 {
        return Vec::new();
    }
    (0..attempts)
        .map(|attempt| attempt % server_count)
        .collect()
}

pub fn retry_backoff_millis(attempt: u32) -> u64 {
    if attempt == 0 {
        0
    } else {
        (300u64 << (attempt - 1)).min(4000)
    }
}

#[allow(clippy::too_many_arguments)]
pub fn fetch_manifest_with_retry(
    cdn: &CdnClient,
    servers: &[CContentServerDirectoryServerInfo],
    depot_id: u32,
    manifest_id: u64,
    request_code: u64,
    cdn_auth_token: &str,
    timeout: Duration,
    cancel: Option<&AtomicBool>,
    auth_refresher: Option<&CdnAuthTokenRefresher>,
    code_refresher: Option<ManifestCodeRefresher>,
) -> CdnManifestResult {
    if servers.is_empty() {
        return CdnManifestResult {
            error: "download: no CDN servers available".to_string(),
            ..Default::default()
        };
    }
    // JavaSteam `DepotDownloader` parity: a 404 is permanent; a 401/403 gets ONE same-host
    // retry with a freshly requested CDN auth token (fatal only when EVERY host rejects
    // auth); 5xx/network faults keep rotating hosts so a CDN-side outage does not fail
    // the depot — bounded by MAX_MANIFEST_FETCH_ATTEMPTS tries per server (not infinite),
    // refreshing the manifest request code after each full fault pass (it may have expired).
    let mut request_code = request_code;
    let mut last = CdnManifestResult::default();
    let mut tokens: HashMap<usize, String> = HashMap::new();
    let mut auth_failed: HashSet<usize> = HashSet::new();
    let mut per_server = vec![0usize; servers.len()];
    let mut retry_server: Option<usize> = None;
    let mut rotation = 0usize;
    let mut faults = 0u32;
    loop {
        if cancel.is_some_and(|c| c.load(Ordering::Relaxed)) {
            last.error = "cancelled".to_string();
            return last;
        }
        let server_idx = match retry_server.take() {
            Some(idx) => idx,
            None => {
                let Some(idx) = (0..servers.len())
                    .map(|k| (rotation + k) % servers.len())
                    .find(|&i| per_server[i] < MAX_MANIFEST_FETCH_ATTEMPTS)
                else {
                    return last; // every server exhausted its attempts
                };
                per_server[idx] += 1;
                rotation += 1;
                idx
            }
        };
        if faults > 0 && retry_server.is_none() {
            thread::sleep(Duration::from_millis(retry_backoff_millis(faults.min(5))));
        }
        let token = tokens
            .get(&server_idx)
            .map(String::as_str)
            .unwrap_or(cdn_auth_token);
        last = cdn.fetch_manifest(
            &servers[server_idx],
            depot_id,
            manifest_id,
            request_code,
            token,
            timeout,
        );
        if last.ok() {
            return last;
        }
        let status = last.http_status;
        if status == 404 {
            return last; // permanent: the manifest is not on this CDN, rotating won't help
        }
        if auth_status(status) {
            if let Some(refresher) = auth_refresher {
                if !tokens.contains_key(&server_idx) {
                    if let Some(fresh) = refresher(depot_id, &servers[server_idx].host) {
                        tokens.insert(server_idx, fresh);
                        retry_server = Some(server_idx);
                        continue; // immediate same-host retry with the fresh token
                    }
                }
            }
            auth_failed.insert(server_idx);
            if auth_failed.len() == servers.len() {
                return last; // every host rejects auth: fatal (JavaSteam aborts here too)
            }
        } else {
            faults += 1;
            if faults as usize % servers.len() == 0 {
                // Full fault pass done: the request code may have expired mid-outage.
                if let Some(fresh) = code_refresher
                    .and_then(|refresh| refresh(depot_id, manifest_id))
                    .filter(|fresh| *fresh != request_code)
                {
                    request_code = fresh;
                }
            }
        }
    }
}

/// Whether a depot still needs work, or is already at the requested build.
///
/// INSTALL asks for the build the app records as installed, so `completed/` already holding that gid
/// means there is nothing to do; UPDATE and VERIFY pass `fresh` and always walk, because the engine
/// cannot tell "unchanged" from "same gid, different content" without the manifest diff and the
/// per-chunk re-hash that follow.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum DepotResumeDecision {
    SkipInstalled,
    Download,
}

pub fn decide_depot_resume(
    fresh: bool,
    cfg: &DepotConfigStore,
    spec: DepotSpec,
) -> DepotResumeDecision {
    if !fresh && cfg.is_installed(spec.depot_id, spec.manifest_id) {
        DepotResumeDecision::SkipInstalled
    } else {
        DepotResumeDecision::Download
    }
}

pub fn map_write_progress(
    depot_id: u32,
    depots_done: u32,
    depots_total: u32,
    done: u64,
    total: u64,
    verifying: bool,
) -> DepotDownloadProgress {
    DepotDownloadProgress {
        depot_id,
        depot_done: done,
        depot_total: total,
        depots_done,
        depots_total,
        verifying,
    }
}

pub type DepotProgressCallback<'a> = &'a (dyn Fn(&DepotDownloadProgress) + Sync);

/// Returns a fresh manifest request code for (depot_id, manifest_id); Steam rotates codes ~every 5 min.
pub type ManifestCodeRefresher<'a> = &'a (dyn Fn(u32, u64) -> Option<u64> + Sync);

pub fn download_resolved_depots(
    install_dir: &str,
    depots: &[ResolvedDepotSpec],
    servers: &[CContentServerDirectoryServerInfo],
    ca_bundle_path: &str,
    fresh: bool,
    max_workers: u32,
    max_process_workers: u32,
) -> DepotDownloadResult {
    download_resolved_depots_with_cancel_progress(
        install_dir,
        depots,
        servers,
        ca_bundle_path,
        fresh,
        &[],
        max_workers,
        max_process_workers,
        None,
        None,
        None,
        None,
        None,
        None,
    )
}

#[allow(clippy::too_many_arguments)]
pub fn download_resolved_depots_with_cancel(
    install_dir: &str,
    depots: &[ResolvedDepotSpec],
    servers: &[CContentServerDirectoryServerInfo],
    ca_bundle_path: &str,
    fresh: bool,
    max_workers: u32,
    max_process_workers: u32,
    cancel: Option<&AtomicBool>,
) -> DepotDownloadResult {
    download_resolved_depots_with_cancel_progress(
        install_dir,
        depots,
        servers,
        ca_bundle_path,
        fresh,
        &[],
        max_workers,
        max_process_workers,
        cancel,
        None,
        None,
        None,
        None,
        None,
    )
}

#[allow(clippy::too_many_arguments)]
pub fn download_resolved_depots_with_cancel_progress(
    install_dir: &str,
    depots: &[ResolvedDepotSpec],
    servers: &[CContentServerDirectoryServerInfo],
    ca_bundle_path: &str,
    fresh: bool,
    untrusted_paths: &[String],
    max_workers: u32,
    max_process_workers: u32,
    cancel: Option<&AtomicBool>,
    on_progress: Option<DepotProgressCallback<'_>>,
    code_refresher: Option<ManifestCodeRefresher<'_>>,
    auth_token_refresher: Option<&CdnAuthTokenRefresher>,
    log: Option<crate::store_dl::steam::depot_writer::DepotLogCallback<'_>>,
    verify_status: Option<crate::store_dl::steam::depot_writer::DepotStatusCallback<'_>>,
) -> DepotDownloadResult {
    if let Err(error) = validate_resolved_download_inputs(install_dir, depots, servers) {
        return error;
    }
    let usable_servers = filter_usable_cdn_servers(servers.iter().cloned());
    if usable_servers.is_empty() {
        return DepotDownloadResult::fail("download: no usable CDN server");
    }

    if let Err(error) = fs::create_dir_all(install_dir) {
        return DepotDownloadResult::fail(format!("download: mkdir install dir: {error}"));
    }
    let config_dir = config_dir_path(install_dir);
    if let Err(error) = fs::create_dir_all(&config_dir) {
        return DepotDownloadResult::fail(format!("download: mkdir config dir: {error}"));
    }

    // The installed record (`completed/` filenames) is read here and only ever changed by
    // `finish_depot` after a depot's writes succeeded — nothing else in the run touches it, so the
    // delta and the sweep below diff against the build the tree really is. `fresh` used to forget
    // these depots up front, which silently disabled both of them.
    let mut cfg = DepotConfigStore::load(&config_dir);

    let cdn = CdnClient::new(ca_bundle_path);
    let mut result = DepotDownloadResult {
        success: true,
        ..Default::default()
    };

    let depots_total = depots.len() as u32;
    let mut depots_done = 0u32;

    // ── Phase 1: resolve ALL depot manifests before a single chunk is downloaded (the "20/20"
    // point). Only after every depot's metadata is in hand does any file content get scheduled.
    let mut resolved: Vec<(&ResolvedDepotSpec, ContentManifest)> = Vec::new();
    for depot in depots {
        if cancel.is_some_and(|cancel| cancel.load(Ordering::Relaxed)) {
            return DepotDownloadResult::fail("cancelled");
        }
        let spec = DepotSpec {
            depot_id: depot.depot_id,
            manifest_id: depot.manifest_id,
        };
        if decide_depot_resume(fresh, &cfg, spec) == DepotResumeDecision::SkipInstalled
        {
            result.depots_skipped += 1;
            depots_done += 1;
            continue;
        }
        if depot.depot_key.len() != 32 {
            return DepotDownloadResult::fail(format!(
                "download: depot key unavailable for depot {}",
                depot.depot_id
            ));
        }

        let target_path = cfg.target_manifest_path(depot.depot_id, depot.manifest_id);
        // A manifest is usable only when it parses AND its metadata matches the depot/gid it is filed
        // under; anything else is a fetch, never a diff base (a wrong manifest would produce a wrong
        // delta, a wrong sweep and a wrong verify).
        let usable = |path: &Path| -> Option<(Vec<u8>, ContentManifest)> {
            read_cached_manifest(path).and_then(|raw| {
                let parsed = ContentManifest::parse(&raw)?;
                (parsed.metadata.depot_id == depot.depot_id
                    && parsed.metadata.gid_manifest == depot.manifest_id)
                    .then_some((raw, parsed))
            })
        };
        // `target/` holds THIS press's manifests and nothing else: whatever an earlier press left for
        // this depot is dropped, so a resume re-reads what the CDN serves now instead of finishing a
        // build that may have been superseded while the download sat paused.
        let _ = cfg.drop_stale_targets(depot.depot_id);
        // A depot asking for the build `completed/` already records (VERIFY pins to the installed gid)
        // needs no fetch and no manifest request code — the manifest is on disk, so a verify works
        // with no network at all.
        let installed_now = cfg.has_completed(depot.depot_id, depot.manifest_id);
        let local = installed_now
            .then(|| cfg.completed_manifest_path(depot.depot_id, depot.manifest_id))
            .and_then(|path| usable(&path));
        let (_, mut manifest) = match local {
            Some(pair) => pair,
            None => {
                if cancel.is_some_and(|cancel| cancel.load(Ordering::Relaxed)) {
                    return DepotDownloadResult::fail("cancelled");
                }
                // Prefer a code obtained now; the pre-resolved one may have expired.
                let refreshed_code = code_refresher
                    .and_then(|refresh| refresh(depot.depot_id, depot.manifest_id));
                let request_code = refreshed_code.unwrap_or(depot.manifest_request_code);
                // Status-aware retry (JavaSteam parity): 404 permanent, 401/403 retried
                // once per host with a fresh CDN auth token, 5xx/network rotated across
                // hosts with the request code refreshed after each full fault pass.
                let fetched = fetch_manifest_with_retry(
                    &cdn,
                    &usable_servers,
                    depot.depot_id,
                    depot.manifest_id,
                    request_code,
                    "",
                    CdnClient::default_timeout(),
                    cancel,
                    auth_token_refresher,
                    code_refresher,
                );
                if !fetched.ok() {
                    return DepotDownloadResult::fail(format!(
                        "download: manifest fetch failed for depot {}: {}",
                        depot.depot_id, fetched.error
                    ));
                }
                let Some(parsed) = ContentManifest::parse(&fetched.raw_manifest) else {
                    return DepotDownloadResult::fail(format!(
                        "download: manifest parse failed for depot {}",
                        depot.depot_id
                    ));
                };
                if parsed.metadata.depot_id != depot.depot_id
                    || parsed.metadata.gid_manifest != depot.manifest_id
                {
                    return DepotDownloadResult::fail(format!(
                        "download: manifest metadata mismatch for depot {}: served depot {} gid {}",
                        depot.depot_id, parsed.metadata.depot_id, parsed.metadata.gid_manifest
                    ));
                }
                let _ = write_manifest_cache(&target_path, &fetched.raw_manifest);
                (fetched.raw_manifest, parsed)
            }
        };

        if !manifest.decrypt_filenames(&depot.depot_key) {
            return DepotDownloadResult::fail(format!(
                "download: filename decryption failed for depot {}",
                depot.depot_id
            ));
        }
        crate::store_dl::steam::depot_writer::normalize_manifest_case_paths(&mut manifest);
        resolved.push((depot, manifest));
    }

    // ── CDN probe: rank the ASSIGNED servers and promote predicted foreign caches that
    // measurably beat the assigned-set median. The on-disk cache seeds the ranking (and any
    // cached winners) synchronously so even the first chunks follow the last known order, and
    // the download starts IMMEDIATELY on the assigned servers. The fresh probe runs in the
    // background but is only spawned on the FIRST downloaded byte (below) — never during
    // preparation or the verify sweep, so it can't race or delay the start (its results are
    // congestion-guarded — a probe that raced saturated download traffic is discarded, not
    // cached/published). The pool grows at depot boundaries only (extended_servers below):
    // an in-flight depot's futures borrow a fixed server slice.
    let probe_hints = crate::store_dl::steam::cdn_probe::seed_from_cache(install_dir, &usable_servers);
    let probe_manifests: Vec<&ContentManifest> = resolved.iter().map(|(_, m)| m).collect();
    // Sampled BEFORE the depot loop so `resolved` can move into the loop (owned String).
    let probe_url_path = crate::store_dl::steam::cdn_probe::sample_url_for_probe(&probe_manifests);
    drop(probe_manifests);
    let probe_spawned = AtomicBool::new(false);

    // Depots share one install dir, so a path this depot's update drops may still belong to
    // another depot: every path ANY depot of this run installs is off-limits to the sweep below.
    let mut protected: HashSet<String> = HashSet::new();
    let mut resolved_ids: HashSet<u32> = HashSet::new();
    for (depot, manifest) in &resolved {
        resolved_ids.insert(depot.depot_id);
        for file in &manifest.files {
            protected.insert(crate::store_dl::steam::depot_writer::folded_entry_key(&file.filename));
        }
    }
    // A depot `completed/` records but this run does not resolve cannot be enumerated — its
    // manifest's filenames need a depot key this layer does not hold — so a removed path cannot be
    // proven unowned. Skip the sweep for the whole run rather than risk deleting another depot's
    // file; it runs again on the next update that resolves everything.
    let unenumerable_installed: Vec<u32> = cfg
        .installed_depots()
        .into_iter()
        .map(|(id, _)| id)
        .filter(|id| !resolved_ids.contains(id))
        .collect();
    if !unenumerable_installed.is_empty() {
        if let Some(log) = log {
            log(&format!(
                "depot-prune disabled: {} installed depot(s) not part of this run ({:?}) \
cannot be enumerated for ownership",
                unenumerable_installed.len(),
                unenumerable_installed
            ));
        }
    }

    // ── Phase 2: all metadata resolved — download the depots in order.
    for (depot, manifest) in resolved {
        if cancel.is_some_and(|cancel| cancel.load(Ordering::Relaxed)) {
            return DepotDownloadResult::fail("cancelled");
        }
        let depot_id = depot.depot_id;
        // The build this depot is at: the gid in `completed/`'s filename is the record, and only a
        // promotion ever rewrites it — so unlike the old pointer file it cannot be blanked by the run
        // that is about to read it. This is what the delta and the sweep diff against.
        let previous_manifest_id = cfg.installed_manifest(depot_id);
        // Read the interrupted flag BEFORE marking this run, or we would read our own marker.
        let interrupted = cfg.interrupted(depot_id);
        if !cfg.begin_depot(depot.depot_id, depot.manifest_id) {
            return DepotDownloadResult::fail(format!(
                "download: depot store begin failed for depot {}",
                depot.depot_id
            ));
        }

        // No usable previous build: nothing is installed here yet, the requested build IS the
        // installed one (a verify), or a previous run for this depot was interrupted — in which case
        // the tree may hold part of a build that is neither the completed nor the requested one, and
        // a file that run half-rewrote could look unchanged to the delta. Walk those in full.
        let previous = if previous_manifest_id == 0
            || previous_manifest_id == depot.manifest_id
            || interrupted
        {
            if interrupted {
                if let Some(log) = log {
                    log(&format!(
                        "depot-delta depot={depot_id} skipped: a previous run for this depot was \
interrupted — full walk"
                    ));
                }
            }
            None
        } else {
            load_cached_manifest(&cfg, depot_id, previous_manifest_id, &depot.depot_key)
        };
        // Files this run must NOT touch: the previous build's manifest proves their bytes already
        // match — minus the ones the app patched (see `trusted_files`).
        let trusted = trusted_files(previous.as_ref(), &manifest, untrusted_paths);
        let trusted_chunks = trusted_chunks(previous.as_ref(), &manifest, untrusted_paths, &trusted);
        // Only a run with patched files can lose a trusted file, and only then is it worth diffing
        // again just to report how many (the diff itself is the expensive part of this block).
        if !untrusted_paths.is_empty() {
            if let (Some(log), Some(previous)) = (log, previous.as_ref()) {
                let unchanged = crate::store_dl::steam::depot_writer::unchanged_files(
                    previous, &manifest,
                );
                if unchanged.len() > trusted.len() {
                    log(&format!(
                        "depot-delta depot={depot_id} patched={} of {} unchanged files \
revalidated (DRM backup present)",
                        unchanged.len() - trusted.len(),
                        unchanged.len()
                    ));
                }
            }
        }
        // Depots share one install dir, so a path this depot dropped may still belong to another
        // depot: `protected` (built before the loop from the manifests of this run) holds every path
        // any of them installs, and the sweep skips those. A depot recorded as installed that this
        // run does NOT resolve cannot be added to that set — its cached manifest's filenames need a
        // depot key this layer does not hold — so `unenumerable_installed` disables the sweep
        // entirely for the run instead (see the guard below and its log line).

        match previous.as_ref() {
            Some(p) => {
                if let Some(log) = log {
                    log(&format!(
                        "depot-delta depot={depot_id} unchanged={}/{} changed={} removed={} \
chunks_trusted={}",
                        trusted.len(),
                        manifest.files.len(),
                        manifest.files.len() - trusted.len(),
                        crate::store_dl::steam::depot_writer::removed_files(p, &manifest).len(),
                        trusted_chunks.len(),
                    ));
                }
            }
            None => {
                // No usable previous manifest: either the requested manifest IS the installed one
                // (a verify, or an update the Kotlin side could not prove is up to date) or the
                // cache/config is missing. Either way this depot is walked in full — say so, the
                // log is otherwise indistinguishable from a silent full re-hash.
                if let Some(log) = log {
                    if previous_manifest_id == depot.manifest_id {
                        log(&format!(
                            "depot-delta depot={depot_id} no delta: manifest {} is already the installed one — full walk",
                            depot.manifest_id
                        ));
                    }
                }
            }
        }
        let chunk_progress = |done: u64, total: u64, verifying: bool| {
            // First real (non-verify) byte = the download has genuinely started: NOW spawn
            // the background CDN probe. An all-verified run never spawns it (nothing to
            // optimize); a cancelled start never probes either.
            if !verifying && !probe_spawned.swap(true, Ordering::Relaxed) {
                crate::store_dl::steam::cdn_probe::spawn_background_probe(
                    &probe_hints,
                    install_dir,
                    ca_bundle_path,
                    &usable_servers,
                    &probe_url_path,
                );
            }
            if let Some(on_progress) = on_progress {
                let progress = map_write_progress(
                    depot_id,
                    depots_done,
                    depots_total,
                    done,
                    total,
                    verifying,
                );
                on_progress(&progress);
            }
        };
        let chunk_progress: crate::store_dl::steam::depot_writer::DepotChunkProgressCallback =
            &chunk_progress;
        // Promote median-beating foreign caches the probe has found so far (cache-seeded on
        // depot 1, live-probe winners from depot 2 onward). Assigned servers always come first;
        // promoted hosts are seeded/ranked/demoted by the same scheduler paths as assigned ones.
        let depot_servers = probe_hints.extended_servers(&usable_servers);
        if depot_servers.len() != usable_servers.len() {
            if let Some(log) = log {
                log(&format!(
                    "cdn-probe: depot {depot_id} pool extended to {} servers",
                    depot_servers.len()
                ));
            }
        }
        let write_result = write_depot_sequential(
            &manifest,
            &depot.depot_key,
            &cdn,
            &depot_servers,
            install_dir,
            DepotWriteOptions {
                max_workers,
                max_process_workers,
                cancel,
                on_progress: Some(chunk_progress),
                log,
                status: verify_status,
                auth_token_refresher,
                probe_hints: Some(probe_hints.clone()),
                // Update delta: these files already match the manifest, so no jobs, no re-hash,
                // no finalize. Empty for a fresh install / verify / when the previous manifest is
                // unknown, which leaves the classic full-walk behaviour.
                trusted_files: Some(&trusted),
                trusted_chunks: Some(&trusted_chunks),
                ..Default::default()
            },
        );
        if !write_result.ok() {
            // The in-flight marker stays: that is what tells the next run this depot's tree may not
            // be any single build, and it is cleared only by a successful promotion.
            return DepotDownloadResult::fail(format!(
                "download: depot {} write failed: {}",
                depot.depot_id, write_result.error
            ));
        }

        // ── Removed content: files the PREVIOUS manifest listed that this one does not. Steam's
        // client prunes this set as part of an update; we do it only AFTER the depot's writes
        // succeeded, so a failed or cancelled run never destroys data. The previous manifest is
        // taken from the local cache (written when it was downloaded) — no extra network round
        // trip, and when it is not cached the sweep is skipped with a log line rather than failing.
        match previous.as_ref().filter(|_| unenumerable_installed.is_empty()) {
            Some(previous) => {
                let removed = crate::store_dl::steam::depot_writer::removed_files(previous, &manifest);
                // Directories this build still declares: the sweep may empty them but must not
                // remove them (see `prune_removed_files`).
                let retained_dirs: HashSet<String> = manifest
                    .files
                    .iter()
                    .filter(|file| (file.flags & DEPOT_FILE_FLAG_DIRECTORY) != 0)
                    .map(|file| crate::store_dl::steam::depot_writer::folded_entry_key(&file.filename))
                    .collect();
                let stats = crate::store_dl::steam::depot_writer::prune_removed_files(
                    install_dir, &removed, &protected, &retained_dirs, log,
                );
                if let Some(log) = log {
                    log(&stats.line(depot_id));
                }
            }
            None => {
                if let Some(log) = log {
                    if previous_manifest_id != 0 && previous_manifest_id != depot.manifest_id {
                        log(&format!(
                            "depot-prune depot={depot_id} skipped: previous manifest \
{previous_manifest_id} not usable from the local cache"
                        ));
                    }
                }
            }
        }

        // This depot's writes succeeded, so the target manifest becomes the installed record —
        // `completed/<depot>_<gid>.manifest`, which is also what the next run diffs against and what
        // VERIFY reads. Anything the manifest promised is on disk now; before this point it is not.
        if !cfg.finish_depot(depot.depot_id, depot.manifest_id) {
            return DepotDownloadResult::fail(format!(
                "download: depot store finish failed for depot {}",
                depot.depot_id
            ));
        }
        if let Some(log) = log {
            log(&format!(
                "depot-store depot={depot_id} completed gid={}",
                depot.manifest_id
            ));
        }
        result.bytes_written += write_result.bytes_written;
        result.depots_completed += 1;
        depots_done += 1;
    }

    result
}

/// The files this run must NOT touch: the previous build's manifest proves their bytes already match,
/// so they get no chunk jobs, no re-hash and no finalize.
///
/// `untrusted` are the paths the app patched (DRM): a file the app rewrote no longer holds the
/// previous manifest's bytes, even when path, size and content hash all agree, so it is revalidated
/// and rewritten like any other change instead of being trusted forever.
fn trusted_files(
    previous: Option<&ContentManifest>,
    manifest: &ContentManifest,
    untrusted_paths: &[String],
) -> Vec<u32> {
    let Some(previous) = previous else {
        return Vec::new();
    };
    let mut trusted = crate::store_dl::steam::depot_writer::unchanged_files(previous, manifest);
    if !untrusted_paths.is_empty() {
        // The app sends Windows-style relative paths; both sides are folded the same way so a
        // separator or case difference cannot smuggle a patched file back into the trusted set.
        let untrusted: HashSet<String> = untrusted_paths
            .iter()
            .map(|path| crate::store_dl::steam::depot_writer::folded_entry_key(path))
            .collect();
        trusted.retain(|index| {
            let file = &manifest.files[*index as usize];
            !untrusted.contains(&crate::store_dl::steam::depot_writer::folded_entry_key(
                &file.filename,
            ))
        });
    }
    trusted
}

/// Chunks of a CHANGED file that the previous build's manifest proves are already on disk, minus
/// DRM-patched paths and files already trusted as a whole.
fn trusted_chunks(
    previous: Option<&ContentManifest>,
    manifest: &ContentManifest,
    untrusted_paths: &[String],
    trusted_files: &[u32],
) -> Vec<(u32, u32)> {
    let Some(previous) = previous else {
        return Vec::new();
    };
    let whole: HashSet<u32> = trusted_files.iter().copied().collect();
    let untrusted: HashSet<String> = untrusted_paths
        .iter()
        .map(|path| crate::store_dl::steam::depot_writer::folded_entry_key(path))
        .collect();
    let mut chunks = crate::store_dl::steam::depot_writer::unchanged_chunks(previous, manifest);
    chunks.retain(|(file_idx, _)| {
        let file = &manifest.files[*file_idx as usize];
        !whole.contains(file_idx)
            && !untrusted.contains(&crate::store_dl::steam::depot_writer::folded_entry_key(
                &file.filename,
            ))
    });
    chunks
}

/// Parse + decrypt the INSTALLED build's manifest from `completed/`, exactly as the resolve phase
/// does for the manifest it is downloading. `None` = not on disk (or unusable), which makes the
/// caller walk the depot in full instead of diffing and sweeping against it.
fn load_cached_manifest(
    cfg: &DepotConfigStore,
    depot_id: u32,
    manifest_id: u64,
    depot_key: &[u8],
) -> Option<ContentManifest> {
    let completed = cfg.completed_manifest_path(depot_id, manifest_id);
    let raw = read_cached_manifest(&completed)?;
    let mut manifest = ContentManifest::parse(&raw)?;
    // Same identity check the Phase-1 resolve path applies to a local manifest: a truncated write,
    // or a file sitting under the wrong `<depot>_<gid>.manifest` name, must not be diffed against.
    // A wrong previous manifest would produce a wrong removed-file set (the sweep only ever deletes
    // paths absent from the new manifest, so the damage is bounded to files this depot no longer
    // lists — but that includes another depot's paths, which is why the check matters).
    if manifest.metadata.gid_manifest != manifest_id
        || (manifest.metadata.depot_id != 0 && manifest.metadata.depot_id != depot_id)
    {
        return None;
    }
    if !manifest.decrypt_filenames(depot_key) {
        return None;
    }
    crate::store_dl::steam::depot_writer::normalize_manifest_case_paths(&mut manifest);
    Some(manifest)
}

fn read_cached_manifest(path: &Path) -> Option<Vec<u8>> {
    let bytes = fs::read(path).ok()?;
    (!bytes.is_empty()).then_some(bytes)
}

fn write_manifest_cache(path: &Path, raw_manifest: &[u8]) -> bool {
    let Some(parent) = path.parent() else {
        return false;
    };
    if fs::create_dir_all(parent).is_err() {
        return false;
    }
    if fs::write(path, raw_manifest).is_err() {
        // Never leave a partial write behind: the read side already treats an
        // unparseable/mismatched file as a cache miss, but drop it now anyway.
        let _ = fs::remove_file(path);
        return false;
    }
    true
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::store_dl::steam::content_manifest::{END_OF_MANIFEST_MAGIC, METADATA_MAGIC, PAYLOAD_MAGIC};
    use crate::store_dl::steam::proto_wire::Writer;
    use std::time::{SystemTime, UNIX_EPOCH};

    #[test]
    fn filters_non_china_servers_with_hosts() {
        let servers = filter_usable_cdn_servers([
            CContentServerDirectoryServerInfo {
                host: "ok".into(),
                ..Default::default()
            },
            CContentServerDirectoryServerInfo {
                host: "china".into(),
                steam_china_only: true,
                ..Default::default()
            },
            CContentServerDirectoryServerInfo {
                host: String::new(),
                ..Default::default()
            },
        ]);
        assert_eq!(servers.len(), 1);
        assert_eq!(servers[0].host, "ok");
    }

    #[test]
    fn resume_decision_matches_cpp_fresh_and_installed_rules() {
        let dir = temp_dir("resume_decision");
        let mut cfg = DepotConfigStore::load(&dir);
        fs::create_dir_all(cfg.target_dir()).unwrap();
        fs::write(cfg.target_manifest_path(100, 555), b"manifest").unwrap();
        assert!(cfg.finish_depot(100, 555));
        let spec = |manifest_id: u64| DepotSpec {
            depot_id: 100,
            manifest_id,
        };
        // INSTALL asks for the build `completed/` records: there is nothing left to do.
        assert_eq!(
            decide_depot_resume(false, &cfg, spec(555)),
            DepotResumeDecision::SkipInstalled
        );
        // UPDATE and VERIFY send `fresh`, so the same gid is still walked — the manifest diff and the
        // per-chunk re-hash decide what is really left.
        assert_eq!(
            decide_depot_resume(true, &cfg, spec(555)),
            DepotResumeDecision::Download
        );
        // A different build always has work to do.
        assert_eq!(
            decide_depot_resume(false, &cfg, spec(777)),
            DepotResumeDecision::Download
        );
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn region_preference_moves_matching_hosts_first_and_keeps_order() {
        let server = |host: &str| CContentServerDirectoryServerInfo {
            host: host.into(),
            ..Default::default()
        };
        let input = vec![
            server("cache1-iad1.steamcontent.com"),
            server("edge.steam-dns.top.comcast.net"),
            server("cache2-fra2.steamcontent.com"),
            server("cache9-fra2.steamcontent.com"),
        ];
        let out = prefer_cdn_servers_for_dc(input.clone(), "FRA2");
        let hosts: Vec<&str> = out.iter().map(|s| s.host.as_str()).collect();
        assert_eq!(
            hosts,
            [
                "cache2-fra2.steamcontent.com",
                "cache9-fra2.steamcontent.com",
                "cache1-iad1.steamcontent.com",
                "edge.steam-dns.top.comcast.net",
            ]
        );
        let unchanged = prefer_cdn_servers_for_dc(input.clone(), "");
        assert_eq!(unchanged, input);
        let no_match = prefer_cdn_servers_for_dc(input.clone(), "syd1");
        assert_eq!(no_match, input);
    }

    #[test]
    fn retry_rotation_and_progress_mapping_match_cpp() {
        assert_eq!(manifest_retry_server_indices(3, 5), [0, 1, 2, 0, 1]);
        assert_eq!(manifest_retry_server_indices(0, 5), Vec::<usize>::new());
        assert_eq!(retry_backoff_millis(1), 300);
        assert_eq!(retry_backoff_millis(5), 4000);
        assert_eq!(
            map_write_progress(100, 2, 4, 10, 20, true),
            DepotDownloadProgress {
                depot_id: 100,
                depot_done: 10,
                depot_total: 20,
                depots_done: 2,
                depots_total: 4,
                verifying: true
            }
        );
    }

    #[test]
    fn validates_download_inputs() {
        assert_eq!(
            validate_download_inputs("", &[DepotSpec::default()])
                .unwrap_err()
                .error,
            "download: empty install dir"
        );
        assert_eq!(
            validate_download_inputs("/tmp/app", &[]).unwrap_err().error,
            "download: no depots"
        );
        assert!(validate_download_inputs("/tmp/app", &[DepotSpec::default()]).is_ok());
    }

    #[test]
    fn validates_resolved_inputs_and_filters_servers() {
        assert_eq!(
            validate_resolved_download_inputs("", &[ResolvedDepotSpec::default()], &[])
                .unwrap_err()
                .error,
            "download: empty install dir"
        );
        assert_eq!(
            validate_resolved_download_inputs("/tmp/app", &[], &[])
                .unwrap_err()
                .error,
            "download: no depots"
        );
        assert_eq!(
            validate_resolved_download_inputs("/tmp/app", &[ResolvedDepotSpec::default()], &[])
                .unwrap_err()
                .error,
            "download: no CDN servers available"
        );
    }

    #[test]
    fn resolved_download_uses_the_installed_manifest_without_a_fetch() {
        let dir = temp_dir("resolved_download_installed_manifest");
        let config_dir = config_dir_path(&dir);
        assert_eq!(config_dir, dir.join(".DepotDownloader"));
        let store = DepotConfigStore::load(&config_dir);
        fs::create_dir_all(store.completed_dir()).unwrap();
        // The build `completed/` records IS the one being asked for: VERIFY pins to it, so the
        // manifest is read from disk and the run needs no CDN at all.
        fs::write(
            store.completed_manifest_path(100, 555),
            raw_layout_manifest(100, 555, "empty.bin", 5),
        )
        .unwrap();

        let result = download_resolved_depots_with_cancel_progress(
            dir.to_str().unwrap(),
            &[ResolvedDepotSpec {
                depot_id: 100,
                manifest_id: 555,
                depot_key: vec![1u8; 32],
                manifest_request_code: 0,
            }],
            &[CContentServerDirectoryServerInfo {
                host: "cdn.example".into(),
                https_support: "mandatory".into(),
                ..Default::default()
            }],
            "",
            true, // walked: the manifest diff and the per-chunk re-hash decide the work
            &[],
            4,
            4,
            None,
            None,
            None,
            None,
            None,
            None,
        );

        assert!(result.success, "{}", result.error);
        assert_eq!(result.depots_completed, 1);
        assert_eq!(result.depots_skipped, 0);
        assert_eq!(fs::metadata(dir.join("empty.bin")).unwrap().len(), 5);
        let cfg = DepotConfigStore::load(&config_dir);
        assert!(cfg.is_installed(100, 555));
        assert!(
            !store.target_manifest_path(100, 555).exists(),
            "nothing was fetched, so nothing was staged in target/"
        );

        let skipped = download_resolved_depots(
            dir.to_str().unwrap(),
            &[ResolvedDepotSpec {
                depot_id: 100,
                manifest_id: 555,
                depot_key: vec![1u8; 32],
                manifest_request_code: 0,
            }],
            &[CContentServerDirectoryServerInfo {
                host: "cdn.example".into(),
                https_support: "mandatory".into(),
                ..Default::default()
            }],
            "",
            false,
            4,
            4,
        );
        assert!(skipped.success);
        assert_eq!(skipped.depots_completed, 0);
        assert_eq!(skipped.depots_skipped, 1);
        let _ = fs::remove_dir_all(&dir);
    }

    /// The update delta is a manifest diff: a file whose path, size and content hash are the same in
    /// both builds is not touched at all (no job, no re-hash, no finalize).
    #[test]
    fn delta_trusts_only_files_the_previous_manifest_proves_unchanged() {
        let (previous, manifest) = same_file_pair();
        assert_eq!(trusted_files(Some(&previous), &manifest, &[]), vec![0]);

        // A different content hash is the whole point of the diff: not trusted.
        let mut changed = manifest.clone();
        changed.files[0].sha_content = vec![9u8; 20];
        assert!(trusted_files(Some(&previous), &changed, &[]).is_empty());

        // A different size is not trusted either.
        let mut resized = manifest.clone();
        resized.files[0].size = 6;
        assert!(trusted_files(Some(&previous), &resized, &[]).is_empty());

        // Nothing to compare against (a fresh install, a verify, or a previous build that could not
        // be read): everything is walked.
        assert!(trusted_files(None, &manifest, &[]).is_empty());
    }

    #[test]
    fn delta_trusts_the_unchanged_chunks_of_a_changed_file() {
        let (mut previous, mut manifest) = same_file_pair();
        let chunk = |offset: u64, sha: u8| crate::store_dl::steam::content_manifest::ChunkData {
            sha: vec![sha; 20],
            offset,
            cb_original: 5,
            ..Default::default()
        };
        previous.files[0].size = 10;
        previous.files[0].chunks = vec![chunk(0, 7), chunk(5, 8)];
        manifest.files[0].size = 10;
        manifest.files[0].sha_content = vec![2u8; 20];
        manifest.files[0].chunks = vec![chunk(0, 7), chunk(5, 9)];

        let whole = trusted_files(Some(&previous), &manifest, &[]);
        assert!(whole.is_empty(), "the file changed, so it is not trusted as a whole");
        assert_eq!(
            trusted_chunks(Some(&previous), &manifest, &[], &whole),
            vec![(0u32, 0u32)],
            "only the chunk with the same offset and sha is trusted"
        );

        let patched = ["GAME\\game.exe".to_string()];
        assert!(trusted_chunks(Some(&previous), &manifest, &patched, &whole).is_empty());

        assert!(trusted_chunks(Some(&previous), &manifest, &[], &[0]).is_empty(),
            "a file trusted as a whole contributes no chunks");
        assert!(trusted_chunks(None, &manifest, &[], &[]).is_empty());
    }

    /// A DRM-patched file (a Steamless-unpacked exe, a replaced steam_api*.dll) has a backup sibling
    /// on disk, so its bytes are NOT the previous manifest's even though path, size and hash all
    /// match. Trusting it would keep the patch forever, and the app's post-download backup cleanup
    /// would then delete the only copy of the original. Paths arrive from the app as Windows-style
    /// relative paths, so the match folds case and separators.
    #[test]
    fn delta_never_trusts_a_drm_patched_path() {
        let (previous, manifest) = same_file_pair();
        let patched = ["GAME\\game.exe".to_string()];
        assert!(
            trusted_files(Some(&previous), &manifest, &patched).is_empty(),
            "a patched file must be revalidated and rewritten like any other change"
        );

        // An unrelated patched path does not disturb the delta.
        let other = ["other/dll.orig".to_string()];
        assert_eq!(trusted_files(Some(&previous), &manifest, &other), vec![0]);
    }

    /// One file (`Game/game.exe`, 5 bytes, one content hash) present in both manifests.
    fn same_file_pair() -> (ContentManifest, ContentManifest) {
        let build = |manifest_id: u64| ContentManifest {
            metadata: crate::store_dl::steam::content_manifest::Metadata {
                depot_id: 100,
                gid_manifest: manifest_id,
                filenames_encrypted: false,
                ..Default::default()
            },
            files: vec![crate::store_dl::steam::content_manifest::FileMapping {
                filename: "Game/game.exe".into(),
                size: 5,
                sha_content: vec![7u8; 20],
                ..Default::default()
            }],
            signature: Vec::new(),
        };
        (build(111), build(222))
    }

    #[test]
    fn a_manifest_that_does_not_match_its_name_is_never_trusted() {
        let dir = temp_dir("resolved_download_bad_manifest");
        let config_dir = config_dir_path(&dir);
        let store = DepotConfigStore::load(&config_dir);
        fs::create_dir_all(store.completed_dir()).unwrap();
        let raw = raw_layout_manifest(100, 555, "empty.bin", 5);
        // Wrong-gid manifest: named for gid 999, content is gid 555 (metadata mismatch).
        fs::write(store.completed_manifest_path(100, 999), &raw).unwrap();
        // Truncated manifest: unparseable.
        fs::write(
            store.completed_manifest_path(200, 555),
            &raw[..raw.len() / 2],
        )
        .unwrap();

        let server = CContentServerDirectoryServerInfo {
            host: "cdn.example".into(),
            https_support: "mandatory".into(),
            ..Default::default()
        };
        let spec = |depot_id: u32, manifest_id: u64| ResolvedDepotSpec {
            depot_id,
            manifest_id,
            depot_key: vec![1u8; 32],
            manifest_request_code: 0,
        };

        // Neither is usable, so neither is diffed against: the run fetches instead (and fails here,
        // because there is no real CDN) rather than trusting a file that is not the build it claims.
        let wrong_gid = download_resolved_depots(
            dir.to_str().unwrap(),
            &[spec(100, 999)],
            std::slice::from_ref(&server),
            "",
            true,
            4,
            4,
        );
        assert!(!wrong_gid.success);
        assert!(
            wrong_gid.error.contains("manifest fetch failed"),
            "{}",
            wrong_gid.error
        );

        let truncated = download_resolved_depots(
            dir.to_str().unwrap(),
            &[spec(200, 555)],
            std::slice::from_ref(&server),
            "",
            true,
            4,
            4,
        );
        assert!(!truncated.success);
        assert!(
            truncated.error.contains("manifest fetch failed"),
            "{}",
            truncated.error
        );
        // The unusable files stay where they are: the next successful run promotes a good manifest
        // over them, and until then the record must not be silently dropped.
        assert_eq!(DepotConfigStore::load(&config_dir).installed_manifest(100), 999);
        assert_eq!(DepotConfigStore::load(&config_dir).installed_manifest(200), 555);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn manifest_cache_write_roundtrips_and_replaces() {
        let dir = temp_dir("manifest_cache_write");
        let path = dir.join("100_555.manifest");
        let raw = raw_layout_manifest(100, 555, "empty.bin", 5);
        assert!(write_manifest_cache(&path, &raw));
        assert_eq!(fs::read(&path).unwrap(), raw);
        // Second write replaces cleanly.
        assert!(write_manifest_cache(&path, &raw[..raw.len() / 2]));
        assert_eq!(fs::read(&path).unwrap().len(), raw.len() / 2);
        let _ = fs::remove_dir_all(&dir);
    }

    fn temp_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "blsteam_downloader_{name}_{}",
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        let _ = fs::remove_dir_all(&dir);
        dir
    }

    fn raw_layout_manifest(depot_id: u32, manifest_id: u64, filename: &str, size: u64) -> Vec<u8> {
        let mut file_body = Vec::new();
        {
            let mut writer = Writer::new(&mut file_body);
            writer.string_field(1, filename);
            writer.uint64_field(2, size);
        }

        let mut payload = Vec::new();
        Writer::new(&mut payload).submessage_field(1, &file_body);

        let mut metadata = Vec::new();
        {
            let mut writer = Writer::new(&mut metadata);
            writer.uint32_field(1, depot_id);
            writer.uint64_field(2, manifest_id);
            writer.bool_field_force(4, false);
        }

        let mut raw = Vec::new();
        push_section(&mut raw, PAYLOAD_MAGIC, &payload);
        push_section(&mut raw, METADATA_MAGIC, &metadata);
        raw.extend_from_slice(&END_OF_MANIFEST_MAGIC.to_le_bytes());
        raw
    }

    fn push_section(out: &mut Vec<u8>, magic: u32, body: &[u8]) {
        out.extend_from_slice(&magic.to_le_bytes());
        out.extend_from_slice(&(body.len() as u32).to_le_bytes());
        out.extend_from_slice(body);
    }
}
