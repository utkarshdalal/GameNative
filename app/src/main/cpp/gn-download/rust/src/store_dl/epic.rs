//! Epic Games Store download adapter for the shared fetch core (`crate::fetch_core`).
//!
//! Scope = the chunk-fetch inner loop of `EpicDownloadManager.java` PLUS the assembly stage:
//! the fetch unit is one (file, part) job, and every verified, decompressed part is queued
//! straight into its owning file's ordered drain — sequential appends DIRECTLY into the final
//! file. A chunk shared by several files is fetched once per consuming file, decoded in
//! memory, and never touches disk outside its target file. The Java manager still parses the
//! manifest API JSON, downloads the manifest, selects files (install tags), runs the
//! delta/verify pass and does every post-install step.
//!
//! Submodules:
//! - [`manifest`] — ChunksV4 binary manifest + legacy JSON manifest parsers (1:1 with the Java
//!   `parseManifest` / `parseJsonManifest`).
//! - [`plan`] — install-tag selection, per-(file, part) jobs, chunk CDN URLs.
//! - [`chunk`] — chunk header parse, zlib inflate, SHA-1 verify (in memory).
//! - [`driver`] — plan → `FetchItem`s → `fetch_core::run_fetch` with the streamed-write sink.
//! - [`jni`] — `Java_com_winlator_star_store_blsteam_BlEpicDownload_native*` exports.
//!
//! The selective-install-tag, delta/resume and chunk verification rules mirrored here were ported
//! into the Java manager from GameNative (`service/epic/EpicDownloadManager.kt`,
//! `manifest/ManifestUtils.kt`), itself derived from Legendary; both are GPL-3.0 and this crate
//! carries the same licence (see `Cargo.toml`).

pub mod chunk;
pub mod driver;
pub mod jni;
pub mod manifest;
pub mod plan;

/// User-Agent the Java manager sends on every chunk request (`EpicDownloadManager.UA`).
pub const USER_AGENT: &str =
    "UELauncher/11.0.1-14907503+++Portal+Release-Live Windows/10.0.19041.1.256.64bit";

/// Fixed pool width of the Java chunk downloader (`Executors.newFixedThreadPool(8)`).
pub const JAVA_POOL_THREADS: usize = 8;

/// logcat tag for every engine line (Java side mirrors it into `bh_epic_debug.txt`).
pub const LOG_TAG: &str = "GN_EPIC_DL";
