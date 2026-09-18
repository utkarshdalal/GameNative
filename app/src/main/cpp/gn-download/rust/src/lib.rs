//! GameNative unified game-download engine (`libgndownload.so`).
//!
//! All real store download pipelines run in Rust, under [`store_dl`]:
//! - Steam CDN depot downloads (`store_dl::steam`: `depot_downloader` / `depot_writer` /
//!   `cdn_client`), fed by JavaSteam-resolved depot keys, manifest request codes and CDN server
//!   lists from Kotlin (`store_dl::steam::jni`).
//! - Epic / GOG / Amazon chunk engines (`store_dl::{epic, gog, amazon}`), fed manifest/plan JSON
//!   by the Kotlin store services through `GameDownloadService`.
//!
//! Shared plumbing ([`fetch_core`], [`md5_small`]) stays at the crate root.
//!
//! Login / CM traffic stays in JavaSteam on the Kotlin side; this crate only moves bytes.

pub mod fetch_core;
pub mod jni_tree_delete;
pub mod md5_small;
pub mod store_dl;
pub mod tree_delete;
