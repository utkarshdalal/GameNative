//! Steam depot download engine (the largest store pipeline).
//!
//! `depot_downloader` orchestrates: manifests (`content_manifest`, `proto_wire` + `pb` for the
//! content-server directory protobufs), depot keys/config (`depot_config`), the CDN client
//! (`cdn_client`, `cdn_probe` for background throughput ranking of the assigned servers) and the writer
//! (`depot_writer`: verify/resume, chunk dispatch over [`crate::fetch_core`], ordered writes,
//! stall watchdog). `depot_chunk` + `crypto` do chunk decrypt (AES-256) + VZip/LZMA decompress.
//! `jni` is the JNI facade called from Kotlin; `base64` is a small local helper.

pub mod base64;
pub mod cdn_client;
pub mod cdn_probe;
pub mod content_manifest;
pub mod crypto;
pub mod depot_chunk;
pub mod depot_config;
pub mod depot_downloader;
pub mod depot_writer;
pub mod jni;
pub mod pb;
pub mod proto_wire;
