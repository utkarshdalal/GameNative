//! Progress emit throttle for the JNI boundary.
//!
//! `fetch_core` and the depot writer fire progress per streamed piece / per chunk (hundreds of
//! calls per second at speed). Every unthrottled JNI `onProgress` allocates a status String,
//! fans out to Compose listeners, and records a speed sample on the Kotlin side — the
//! allocation storm behind `Waiting for a blocking GC Alloc`, and a recomposition source that
//! can starve gamepad input into ANRs during downloads.
//!
//! Byte accounting is unaffected (bytes accrue natively regardless); only the JNI crossing is
//! throttled. The gate never drops milestones:
//! - the FIRST callback always emits (backdated start), so screens get an immediate status;
//! - a `count` change (files_done / depots_done — per store) bypasses the interval so status
//!   text like "Downloading (x/y files)…" stays fresh;
//! - `force` bypasses unconditionally (Steam verify-sweep markers: the Kotlin resume-crediting
//!   logic keys off them and they must never be merged away);
//! - the 100% marker always emits so the UI settles before `onComplete`.

use std::sync::Mutex;
use std::time::{Duration, Instant};

const PROGRESS_EMIT_INTERVAL: Duration = Duration::from_millis(200);

pub(crate) struct ProgressGate {
    state: Mutex<(Instant, u64)>, // (last emit, last count)
}

impl ProgressGate {
    pub(crate) fn new() -> Self {
        Self {
            // Backdated so the very first progress callback always emits.
            state: Mutex::new((Instant::now() - PROGRESS_EMIT_INTERVAL, u64::MAX)),
        }
    }

    pub(crate) fn should_emit(&self, bytes_done: u64, bytes_total: u64, count: u64, force: bool) -> bool {
        let mut st = self.state.lock().unwrap_or_else(|e| e.into_inner());
        let complete = bytes_total > 0 && bytes_done >= bytes_total;
        if force || complete || count != st.1 || st.0.elapsed() >= PROGRESS_EMIT_INTERVAL {
            st.0 = Instant::now();
            st.1 = count;
            true
        } else {
            false
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn throttles_but_never_drops_milestones() {
        let gate = ProgressGate::new();
        // First callback always emits (backdated start).
        assert!(gate.should_emit(1, 1000, 0, false));
        // Same tick, no count change, not complete → throttled.
        assert!(!gate.should_emit(2, 1000, 0, false));
        assert!(!gate.should_emit(500, 1000, 0, false));
        // Count change bypasses the interval (status text freshness).
        assert!(gate.should_emit(600, 1000, 1, false));
        // force bypasses unconditionally (Steam verify-sweep markers).
        assert!(gate.should_emit(700, 1000, 1, true));
        // 100% marker always emits so the UI settles before onComplete.
        assert!(gate.should_emit(1000, 1000, 1, false));
        // Interval elapsed → emits again.
        {
            let mut st = gate.state.lock().unwrap();
            st.0 = Instant::now() - PROGRESS_EMIT_INTERVAL;
        }
        assert!(gate.should_emit(1000, 1000, 1, false));
        // bytes_total == 0 (unknown size) must not count as "complete".
        let gate = ProgressGate::new();
        assert!(gate.should_emit(0, 0, 0, false)); // first call
        assert!(!gate.should_emit(10, 0, 0, false));
    }
}
