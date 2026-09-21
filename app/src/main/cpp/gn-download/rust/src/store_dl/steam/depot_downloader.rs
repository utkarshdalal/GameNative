use crate::store_dl::steam::cdn_client::{auth_status, CdnClient, CdnManifestResult};
use crate::store_dl::steam::content_manifest::ContentManifest;
use crate::store_dl::steam::depot_config::{DepotConfigStore, DepotProgressStore, INVALID_MANIFEST_ID};
use crate::store_dl::steam::depot_writer::{write_depot_sequential, CdnAuthTokenRefresher, DepotWriteOptions};
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

pub fn clean_pause_marker_name(depot_id: u32, manifest_id: u64) -> String {
    format!("{depot_id}_{manifest_id}.cleanpause")
}

pub fn clean_pause_marker_path(
    config_dir: impl AsRef<Path>,
    depot_id: u32,
    manifest_id: u64,
) -> PathBuf {
    config_dir
        .as_ref()
        .join(clean_pause_marker_name(depot_id, manifest_id))
}

pub fn has_clean_pause_marker(
    config_dir: impl AsRef<Path>,
    depot_id: u32,
    manifest_id: u64,
) -> bool {
    clean_pause_marker_path(config_dir, depot_id, manifest_id).is_file()
}

pub fn write_clean_pause_marker(
    config_dir: impl AsRef<Path>,
    depot_id: u32,
    manifest_id: u64,
) -> bool {
    let path = clean_pause_marker_path(config_dir, depot_id, manifest_id);
    let Some(parent) = path.parent() else {
        return false;
    };
    if fs::create_dir_all(parent).is_err() {
        return false;
    }
    fs::write(path, manifest_id.to_string()).is_ok()
}

pub fn remove_clean_pause_marker(config_dir: impl AsRef<Path>, depot_id: u32, manifest_id: u64) {
    let _ = fs::remove_file(clean_pause_marker_path(config_dir, depot_id, manifest_id));
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

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum DepotResumeDecision {
    SkipInstalled,
    Download { trust_existing_chunks: bool },
}

pub fn decide_depot_resume(
    fresh: bool,
    cfg: &DepotConfigStore,
    spec: DepotSpec,
    clean_pause_marker_exists: bool,
) -> DepotResumeDecision {
    if !fresh && cfg.is_installed(spec.depot_id, spec.manifest_id) {
        DepotResumeDecision::SkipInstalled
    } else {
        DepotResumeDecision::Download {
            trust_existing_chunks: !fresh && clean_pause_marker_exists,
        }
    }
}

pub fn in_progress_manifest_id() -> u64 {
    INVALID_MANIFEST_ID
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

    let mut cfg = DepotConfigStore::load(&config_dir);
    if fresh {
        // Reset only this batch's depots; a global discard would wipe earlier batches' records.
        for depot in depots {
            cfg.forget_depot(depot.depot_id);
            DepotProgressStore::remove(&config_dir, depot.depot_id, depot.manifest_id);
            remove_clean_pause_marker(&config_dir, depot.depot_id, depot.manifest_id);
        }
    }

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
        let clean_pause = has_clean_pause_marker(&config_dir, depot.depot_id, depot.manifest_id);
        if decide_depot_resume(fresh, &cfg, spec, clean_pause) == DepotResumeDecision::SkipInstalled
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

        let cache_path = cfg.manifest_cache_path(depot.depot_id, depot.manifest_id);
        // A cached manifest is only usable when it parses AND its metadata matches the
        // requested depot/gid — a truncated write or a file sitting under the wrong key
        // is deleted and refetched once instead of poisoning every subsequent attempt
        // ("manifest parse failed" is a permanent error; nothing would self-heal it).
        let cached = read_cached_manifest(&cache_path).and_then(|raw| {
            let parsed = ContentManifest::parse(&raw)?;
            (parsed.metadata.depot_id == depot.depot_id
                && parsed.metadata.gid_manifest == depot.manifest_id)
                .then_some((raw, parsed))
        });
        let (_, mut manifest) = match cached {
            Some(pair) => pair,
            None => {
                let _ = fs::remove_file(&cache_path); // no-op when absent
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
                let _ = write_manifest_cache(&cache_path, &fetched.raw_manifest);
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

    // ── CDN probe: rank the ASSIGNED servers in the background. The on-disk cache seeds the
    // ranking synchronously (one small file read) so even the first chunks follow the last known
    // order; a background thread re-probes when the cache is stale/absent and the scheduler
    // reprioritizes mid-download. The server pool itself is never extended.
    let probe_hints = crate::store_dl::steam::cdn_probe::seed_from_cache(install_dir, &usable_servers);
    let probe_manifests: Vec<&ContentManifest> = resolved.iter().map(|(_, m)| m).collect();
    crate::store_dl::steam::cdn_probe::spawn_background_probe(
        &probe_hints,
        install_dir,
        ca_bundle_path,
        &usable_servers,
        &probe_manifests,
    );

    // ── Phase 2: all metadata resolved — download the depots in order.
    for (depot, manifest) in resolved {
        if cancel.is_some_and(|cancel| cancel.load(Ordering::Relaxed)) {
            return DepotDownloadResult::fail("cancelled");
        }
        if !cfg.begin_depot(depot.depot_id) {
            return DepotDownloadResult::fail(format!(
                "download: depot.config begin failed for depot {}",
                depot.depot_id
            ));
        }

        let depot_id = depot.depot_id;
        let chunk_progress = |done: u64, total: u64, verifying: bool| {
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
        let write_result = write_depot_sequential(
            &manifest,
            &depot.depot_key,
            &cdn,
            &usable_servers,
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
                ..Default::default()
            },
        );
        if !write_result.ok() {
            if write_result.resume_trust_safe {
                let _ = write_clean_pause_marker(&config_dir, depot.depot_id, depot.manifest_id);
            }
            return DepotDownloadResult::fail(format!(
                "download: depot {} write failed: {}",
                depot.depot_id, write_result.error
            ));
        }

        if !cfg.finish_depot(depot.depot_id, depot.manifest_id) {
            return DepotDownloadResult::fail(format!(
                "download: depot.config finish failed for depot {}",
                depot.depot_id
            ));
        }
        DepotProgressStore::new(&config_dir, depot.depot_id, depot.manifest_id).discard();
        remove_clean_pause_marker(&config_dir, depot.depot_id, depot.manifest_id);
        result.bytes_written += write_result.bytes_written;
        result.depots_completed += 1;
        depots_done += 1;
    }

    result
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
    fn marker_names_match_cpp_format() {
        assert_eq!(clean_pause_marker_name(123, 456), "123_456.cleanpause");
    }

    #[test]
    fn clean_pause_marker_files_roundtrip() {
        let dir = temp_dir("clean_pause");
        assert!(!has_clean_pause_marker(&dir, 123, 456));
        assert!(write_clean_pause_marker(&dir, 123, 456));
        assert!(has_clean_pause_marker(&dir, 123, 456));
        assert_eq!(
            fs::read_to_string(clean_pause_marker_path(&dir, 123, 456)).unwrap(),
            "456"
        );
        remove_clean_pause_marker(&dir, 123, 456);
        assert!(!has_clean_pause_marker(&dir, 123, 456));
        let _ = fs::remove_dir_all(&dir);
    }

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
        cfg.finish_depot(100, 555);
        let spec = DepotSpec {
            depot_id: 100,
            manifest_id: 555,
        };
        assert_eq!(
            decide_depot_resume(false, &cfg, spec, false),
            DepotResumeDecision::SkipInstalled
        );
        assert_eq!(
            decide_depot_resume(true, &cfg, spec, true),
            DepotResumeDecision::Download {
                trust_existing_chunks: false
            }
        );
        assert_eq!(
            decide_depot_resume(
                false,
                &cfg,
                DepotSpec {
                    depot_id: 100,
                    manifest_id: 777
                },
                true
            ),
            DepotResumeDecision::Download {
                trust_existing_chunks: true
            }
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
        assert_eq!(in_progress_manifest_id(), INVALID_MANIFEST_ID);
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
    fn resolved_download_uses_cached_manifest_and_records_install() {
        let dir = temp_dir("resolved_download_cached_manifest");
        let config_dir = config_dir_path(&dir);
        assert_eq!(config_dir, dir.join(".DepotDownloader"));
        fs::create_dir_all(&config_dir).unwrap();
        let raw_manifest = raw_layout_manifest(100, 555, "empty.bin", 5);
        fs::write(config_dir.join("100_555.manifest"), raw_manifest).unwrap();

        let result = download_resolved_depots(
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

        assert!(result.success, "{}", result.error);
        assert_eq!(result.depots_completed, 1);
        assert_eq!(result.depots_skipped, 0);
        assert_eq!(fs::metadata(dir.join("empty.bin")).unwrap().len(), 5);
        let cfg = DepotConfigStore::load(&config_dir);
        assert!(cfg.is_installed(100, 555));

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

    #[test]
    fn resolved_download_rejects_bad_cache_and_refetches() {
        let dir = temp_dir("resolved_download_bad_cache");
        let config_dir = config_dir_path(&dir);
        fs::create_dir_all(&config_dir).unwrap();
        let raw = raw_layout_manifest(100, 555, "empty.bin", 5);
        // Wrong-key cache: named for gid 999, content is gid 555 (metadata mismatch).
        fs::write(config_dir.join("100_999.manifest"), &raw).unwrap();
        // Truncated cache: unparseable.
        fs::write(config_dir.join("200_555.manifest"), &raw[..raw.len() / 2]).unwrap();

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

        // Both bad caches must be treated as a miss: refetch (fails here — no real
        // CDN) instead of failing permanently on parse, and the poison file is gone.
        let wrong_key = download_resolved_depots(
            dir.to_str().unwrap(),
            &[spec(100, 999)],
            std::slice::from_ref(&server),
            "",
            false,
            4,
            4,
        );
        assert!(!wrong_key.success);
        assert!(
            wrong_key.error.contains("manifest fetch failed"),
            "{}",
            wrong_key.error
        );
        assert!(
            !config_dir.join("100_999.manifest").exists(),
            "mismatched cache must be deleted"
        );

        let truncated = download_resolved_depots(
            dir.to_str().unwrap(),
            &[spec(200, 555)],
            std::slice::from_ref(&server),
            "",
            false,
            4,
            4,
        );
        assert!(!truncated.success);
        assert!(
            truncated.error.contains("manifest fetch failed"),
            "{}",
            truncated.error
        );
        assert!(
            !config_dir.join("200_555.manifest").exists(),
            "truncated cache must be deleted"
        );
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
