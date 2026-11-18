//! Per-file ordered write queue shared by the streamed store engines (GOG gen2, Epic) — the
//! Steam depot_writer model: decoded regions park in `pending` keyed by file offset and only
//! the contiguous run at `cursor` is appended to the staging file. The file therefore grows by
//! pure sequential appends: no positioned writes past EOF, no filesystem zero-fill (the
//! exFAT/FUSE freeze class), no preallocation of any kind.
//!
//! A region is `Arc<[u8]>` + a slice range, so one decoded chunk consumed by several files
//! (Epic's 1:N chunk dedup) is stored once; the GOG case is simply a chunk's full buffer.
//!
//! SELECTIVE RESUME (the Steam writer's Verified-marker concept): a chunk/part whose on-disk
//! bytes re-hashed against the manifest is queued as a VERIFIED MARKER via
//! [`OrderedDrain::insert_verified`]. Markers carry no data: when one reaches the cursor the
//! drain simply advances the cursor past it — the bytes are already on disk. This lets the
//! engines re-fetch ONLY the mismatched/missing chunks of a partially-corrupt file (gaps
//! between verified regions) instead of the whole suffix past the first bad chunk, while the
//! no-hole invariant is preserved: the cursor still walks every byte in order, markers and
//! written regions alike.
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

/// One pending entry: either decoded data to write, or a verified marker naming a byte range
/// already on disk (selective resume — the cursor steps over it without a write).
enum PendingEntry {
    Region(PendingRegion),
    Verified(u64),
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
    pending: BTreeMap<u64, PendingEntry>,
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
            PendingEntry::Region(PendingRegion {
                raw_len,
                data,
                start,
                len,
            }),
        );
    }

    /// Queue a verified marker: `len` bytes at `offset` re-hashed against the manifest and are
    /// already on disk (selective resume). The cursor steps over the marker in order — no
    /// write, no hole. Zero-length markers and markers fully below the cursor are dropped.
    pub fn insert_verified(&mut self, offset: u64, len: u64) {
        if len == 0 || offset.saturating_add(len) <= self.cursor {
            return;
        }
        self.pending.insert(offset, PendingEntry::Verified(len));
    }

    /// Consume every verified marker sitting exactly at the cursor, advancing the cursor past
    /// their (already-on-disk) bytes. Runs before each batch and after each sealed batch, so a
    /// marker never joins a write batch and the cursor never jumps an unwritten gap.
    fn skip_verified(&mut self) {
        loop {
            // Peek only: a data region at the cursor stays queued as the next batch's head.
            let verified_len = match self.pending.first_key_value() {
                Some((&off, PendingEntry::Verified(len))) if off == self.cursor => Some(*len),
                _ => None,
            };
            let Some(len) = verified_len else { break };
            self.pending.remove(&self.cursor);
            self.cursor += len;
        }
    }

    /// Pop every region contiguous at the cursor as coalesced batches (≤ `coalesce` bytes
    /// each), advancing the cursor past them IN ORDER — verified markers interleaved between
    /// regions are consumed between batches (see [`Self::skip_verified`]). Returns nothing
    /// while the head-of-line entry is missing — writing past a gap would leave a hole, which
    /// is exactly what this queue forbids.
    pub fn drain(&mut self, coalesce: u64) -> Vec<DrainedBatch> {
        let mut out = Vec::new();
        self.skip_verified();
        loop {
            let batch_offset = self.cursor;
            let mut batch_len = 0u64;
            let mut raw_total = 0u64;
            let mut piece_lens: Vec<u64> = Vec::new();
            let mut regions: Vec<PendingRegion> = Vec::new();
            while batch_len < coalesce {
                // The next contiguous region sits at the batch start + what this batch
                // already collected (the cursor itself advances only when the batch seals).
                let next_off = batch_offset + batch_len;
                let at_cursor = matches!(
                    self.pending.first_key_value(),
                    Some((&off, PendingEntry::Region(_))) if off == next_off
                );
                if !at_cursor {
                    break; // a gap, or a verified marker (handled between batches)
                }
                let entry = self.pending.remove(&next_off).expect("region at cursor");
                let PendingEntry::Region(region) = entry else {
                    unreachable!("peeked Region");
                };
                batch_len += region.len as u64;
                raw_total += region.raw_len;
                piece_lens.push(region.len as u64);
                regions.push(region);
            }
            if regions.is_empty() {
                break;
            }
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
                offset: batch_offset,
                raw_len: raw_total,
                buf,
                piece_lens,
            });
            self.skip_verified();
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

    #[test]
    fn verified_markers_advance_the_cursor_without_writes() {
        // Selective resume: bytes [0,2) and [6,10) re-hashed on disk; [2,6) is re-fetched and
        // [10,12) is another pending region parked behind the gap. The cursor walks 0→2
        // (marker), 2→6 (data), 6→10 (marker), 10→12 (data) — write batches contain ONLY
        // re-fetched regions and the cursor never jumps an unwritten gap.
        let mut drain = OrderedDrain::default();
        drain.insert_verified(0, 2);
        drain.insert_verified(6, 4);
        drain.insert(10, 1, arc(b"yz"), 0, 2);
        // The gap at [2,6) blocks everything, including the parked region at 10.
        assert!(drain.drain(DRAIN_COALESCE_BYTES).is_empty());
        assert_eq!(drain.cursor(), 2, "leading marker consumed, gap holds");
        drain.insert(2, 1, arc(b"abcd"), 0, 4);
        let batches = drain.drain(DRAIN_COALESCE_BYTES);
        assert_eq!(batches.len(), 2, "markers split the write runs, never join a batch");
        assert_eq!(batches[0].offset, 2);
        assert_eq!(batches[0].bytes(), b"abcd");
        assert_eq!(batches[1].offset, 10);
        assert_eq!(batches[1].bytes(), b"yz");
        assert_eq!(drain.cursor(), 12, "trailing marker consumed between the batches");
        assert!(drain.is_drained());
    }

    #[test]
    fn marker_at_cursor_before_region_then_region_drains_in_one_call() {
        // Marker at the cursor, then data: one drain call consumes the marker AND the batch.
        let mut drain = OrderedDrain::default();
        drain.insert_verified(0, 3);
        drain.insert(3, 1, arc(b"xy"), 0, 2);
        let batches = drain.drain(DRAIN_COALESCE_BYTES);
        assert_eq!(batches.len(), 1);
        assert_eq!(batches[0].offset, 3);
        assert_eq!(batches[0].bytes(), b"xy");
        assert_eq!(drain.cursor(), 5);
        // Zero-length and below-cursor markers are dropped at insert.
        drain.insert_verified(5, 0);
        drain.insert_verified(2, 3);
        assert!(drain.is_drained());
    }
}
