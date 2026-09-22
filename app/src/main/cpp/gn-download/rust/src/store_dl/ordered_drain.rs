//! Per-file ordered write queue shared by the streamed store engines (GOG gen2, Epic) — the
//! Steam depot_writer model: decoded regions park in `pending` keyed by file offset and only
//! the contiguous run at `cursor` is appended to the staging file. The file therefore grows by
//! pure sequential appends: no positioned writes past EOF, no filesystem zero-fill (the
//! exFAT/FUSE freeze class), no preallocation of any kind.
//!
//! A region is `Arc<[u8]>` + a slice range, so one decoded chunk consumed by several files
//! (Epic's 1:N chunk dedup) is stored once; the GOG case is simply a chunk's full buffer.
//!
//! The queue is pure bookkeeping: the caller owns the file handle, the write itself, budget
//! accounting and completion. `drain` returns coalesced batches; the caller pwrites each batch
//! at its `offset` (always the cursor when popped) and credits `piece_lens` per region.

use std::collections::BTreeMap;
use std::sync::Arc;

/// Default coalesce cap: adjacent drained regions are concatenated into ONE pwrite up to this
/// many bytes — the Steam engine's COALESCE_WRITE_BYTES value, 16× fewer FUSE/exFAT round
/// trips than per-region writes, without delaying any region by more than one card-write.
pub const DRAIN_COALESCE_BYTES: u64 = 16 * 1024 * 1024;

/// One queued region: `data[start..start + len]` belonging at a file offset. `raw_len` is the
/// wire/compressed size the caller reserved against its fetch budget (informational here — the
/// caller frees budget when the region drains).
struct PendingRegion {
    raw_len: u64,
    data: Arc<[u8]>,
    start: usize,
    len: usize,
}

/// One coalesced contiguous run popped at the cursor, to be written with a single append.
pub struct DrainedBatch {
    /// File offset of the first byte — always the queue's cursor when the batch was popped.
    pub offset: u64,
    /// Σ `raw_len` of the batch's regions (the budget bytes the caller frees on write-complete).
    pub raw_len: u64,
    buf: BatchBuf,
    /// Per-region decompressed lengths in order — the caller's completion/progress credit.
    pub piece_lens: Vec<u64>,
}

enum BatchBuf {
    /// Single region: zero-copy slice of the shared buffer.
    Shared {
        data: Arc<[u8]>,
        start: usize,
        len: usize,
    },
    /// Several regions concatenated.
    Owned(Vec<u8>),
}

impl DrainedBatch {
    /// The bytes to append at `offset`.
    pub fn bytes(&self) -> &[u8] {
        match &self.buf {
            BatchBuf::Shared { data, start, len } => &data[*start..*start + *len],
            BatchBuf::Owned(buf) => buf,
        }
    }

    /// Total byte length of the batch (== `bytes().len()` without materializing the slice).
    pub fn len(&self) -> u64 {
        self.piece_lens.iter().sum()
    }

    /// Batches are never empty (a drain with nothing contiguous returns no batches).
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }
}

/// Per-file ordered write state: the append cursor plus the out-of-order reorder cushion.
#[derive(Default)]
pub struct OrderedDrain {
    /// Next offset to write == bytes appended so far.
    cursor: u64,
    pending: BTreeMap<u64, PendingRegion>,
}

impl OrderedDrain {
    /// A drain whose cursor starts past an already-on-disk verified prefix (resume): the first
    /// region expected is at `cursor`, and every append continues after it.
    pub fn with_cursor(cursor: u64) -> Self {
        Self {
            cursor,
            pending: BTreeMap::new(),
        }
    }

    pub fn cursor(&self) -> u64 {
        self.cursor
    }

    /// Buffered regions still waiting on a gap (diagnostics / end-of-run drained checks).
    pub fn pending_count(&self) -> usize {
        self.pending.len()
    }

    pub fn is_drained(&self) -> bool {
        self.pending.is_empty()
    }

    /// Queue one decoded region: `data[start..start + len]` belongs at `offset` in the file.
    /// A region fully below the cursor is DROPPED: those bytes are already on disk (a resumed
    /// file's verified prefix) and must never be rewritten.
    pub fn insert(&mut self, offset: u64, raw_len: u64, data: Arc<[u8]>, start: usize, len: usize) {
        if offset.saturating_add(len as u64) <= self.cursor {
            return;
        }
        self.pending.insert(
            offset,
            PendingRegion {
                raw_len,
                data,
                start,
                len,
            },
        );
    }

    /// Pop every region contiguous at the cursor as coalesced batches (≤ `coalesce` bytes
    /// each), advancing the cursor past them IN ORDER. Returns nothing while the head-of-line
    /// region is missing — writing past a gap would leave a hole, which is exactly what this
    /// queue forbids.
    pub fn drain(&mut self, coalesce: u64) -> Vec<DrainedBatch> {
        let mut out = Vec::new();
        loop {
            let mut batch_len = 0u64;
            let mut raw_total = 0u64;
            let mut piece_lens: Vec<u64> = Vec::new();
            let mut regions: Vec<PendingRegion> = Vec::new();
            while batch_len < coalesce {
                // The next contiguous region sits at cursor + what this batch already collected
                // (the cursor itself advances only when the batch is sealed).
                let next_off = self.cursor + batch_len;
                let at_cursor =
                    matches!(self.pending.first_key_value(), Some((&off, _)) if off == next_off);
                if !at_cursor {
                    break;
                }
                let region = self.pending.remove(&next_off).expect("region at cursor");
                batch_len += region.len as u64;
                raw_total += region.raw_len;
                piece_lens.push(region.len as u64);
                regions.push(region);
            }
            if regions.is_empty() {
                break;
            }
            let offset = self.cursor;
            self.cursor += batch_len;
            let buf = if regions.len() == 1 {
                let region = regions.pop().expect("one region");
                BatchBuf::Shared {
                    data: region.data,
                    start: region.start,
                    len: region.len,
                }
            } else {
                let mut buf = Vec::with_capacity(batch_len as usize);
                for region in regions {
                    buf.extend_from_slice(&region.data[region.start..region.start + region.len]);
                }
                BatchBuf::Owned(buf)
            };
            out.push(DrainedBatch {
                offset,
                raw_len: raw_total,
                buf,
                piece_lens,
            });
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn arc(data: &[u8]) -> Arc<[u8]> {
        Arc::from(data)
    }

    #[test]
    fn out_of_order_regions_drain_only_the_contiguous_prefix() {
        let mut drain = OrderedDrain::default();
        // Beyond the cursor: nothing may drain (a write there would leave a hole).
        drain.insert(2, 1, arc(b"ccddee"), 0, 2);
        assert!(drain.drain(DRAIN_COALESCE_BYTES).is_empty());
        assert_eq!(drain.cursor(), 0);
        drain.insert(0, 1, arc(b"aa"), 0, 2);
        let batches = drain.drain(DRAIN_COALESCE_BYTES);
        assert_eq!(batches.len(), 1);
        assert_eq!(batches[0].offset, 0);
        assert_eq!(batches[0].bytes(), b"aacc");
        assert_eq!(batches[0].piece_lens, vec![2, 2]);
        assert_eq!(batches[0].raw_len, 2);
        assert_eq!(drain.cursor(), 4);
        assert!(drain.is_drained());
        // A region behind a genuine gap still waits.
        drain.insert(8, 1, arc(b"zz"), 0, 2);
        assert!(drain.drain(DRAIN_COALESCE_BYTES).is_empty());
        assert_eq!(drain.cursor(), 4);
    }

    #[test]
    fn shared_chunk_slices_stay_zero_copy_when_single() {
        let chunk: Arc<[u8]> = arc(b"xxPAYLOADxx");
        let mut drain = OrderedDrain::default();
        // Two files referencing slices of the SAME decoded chunk: stored once, drained as
        // zero-copy single-region batches.
        drain.insert(0, 1, Arc::clone(&chunk), 2, 7);
        let batches = drain.drain(DRAIN_COALESCE_BYTES);
        assert_eq!(batches.len(), 1);
        assert_eq!(batches[0].bytes(), b"PAYLOAD");
        assert!(matches!(batches[0].buf, BatchBuf::Shared { .. }));
    }

    #[test]
    fn coalesce_cap_splits_long_runs_into_sequential_batches() {
        let mut drain = OrderedDrain::default();
        for i in 0..6u64 {
            drain.insert(i * 2, 1, arc(b"ab"), 0, 2);
        }
        // The cap is checked BEFORE a region joins (Steam semantics): cap 5 with 2-byte
        // regions → 3 regions (6 bytes) per batch.
        let batches = drain.drain(5);
        assert_eq!(batches.len(), 2);
        for (i, batch) in batches.iter().enumerate() {
            assert_eq!(batch.offset, i as u64 * 6);
            assert_eq!(batch.bytes(), b"ababab");
        }
        assert_eq!(drain.cursor(), 12);
    }

    #[test]
    fn with_cursor_resumes_appends_after_a_verified_prefix() {
        // Resume: the drain starts past an on-disk verified prefix — the first region expected
        // sits AT the seeded cursor, and appends continue after it.
        let mut drain = OrderedDrain::with_cursor(100);
        // A region fully below the cursor is dropped (those bytes are the verified prefix
        // already on disk — never rewritten).
        drain.insert(50, 10, arc(b"0123456789"), 0, 10);
        assert_eq!(drain.pending_count(), 0);
        assert_eq!(drain.cursor(), 100);
        // The region at the cursor drains normally.
        drain.insert(100, 5, arc(b"hello"), 0, 5);
        let batches = drain.drain(DRAIN_COALESCE_BYTES);
        assert_eq!(batches.len(), 1);
        assert_eq!(batches[0].offset, 100);
        assert_eq!(batches[0].bytes(), b"hello");
        assert_eq!(drain.cursor(), 105);
    }
}
