// audio-decode-serial: WebView Chromium's decodeAudioData fails or hangs under parallel
// load. titles that Promise.all over many large song decodes (10MB+ each, multi-minute
// PCM = ~130MB/buffer) hit at least one decode either rejecting EncodingError or never
// settling → game waits forever. SAME files decoded SERIALLY succeed every time. cause
// appears to be the WebView audio-decoder pool / memory budget choking under N concurrent
// large decodes; chromium's user-facing surface is the generic "Unable to decode audio
// data".

// fix: serialize decodes globally so each runs alone. one extra retry from a fresh
// buffer slice in case a transient pool-state failure slips through. queue is a single
// Promise chain -- cheap. boot is slightly slower (sum-of-decode latencies vs max), but
// deterministic instead of "1-in-N games hang forever".

// always-on for html5 containers; head-of-chain so the wrapper is in place before any
// game code captures decodeAudioData. always-on diagnostic logs (size + 16B head + ms)
// so future regressions are easy to spot in logcat.
(function () {
    'use strict';
    var BAC = (typeof BaseAudioContext !== 'undefined') ? BaseAudioContext : null;
    var proto = BAC && BAC.prototype;
    if (!proto || typeof proto.decodeAudioData !== 'function') {
        try { console.warn('[audio-decode-serial] BaseAudioContext.decodeAudioData unavailable; not installed'); } catch (_) {}
        return;
    }
    if (proto.__gnDecodeSerialized) return;

    var orig = proto.decodeAudioData;

    function head16(buf) {
        try {
            var n = Math.min(16, buf && buf.byteLength || 0);
            if (!n) return '<empty>';
            var arr = new Uint8Array(buf, 0, n);
            var s = '';
            for (var i = 0; i < arr.length; i++) {
                if (i) s += ' ';
                var h = arr[i].toString(16);
                s += (h.length < 2 ? '0' + h : h);
            }
            return s;
        } catch (e) { return '<unreadable>'; }
    }

    // global serial queue. all decodeAudioData calls land here regardless of which
    // context invoked -- WebView's pool/budget is per-renderer-process, not per-context.
    var queue = Promise.resolve();

    function attempt(ctx, audioData) {
        // decodeAudioData per spec doesn't always detach the input ArrayBuffer, but some
        // WebView builds historically have. retry uses a slice (fresh buffer view) so a
        // detached input on the first attempt doesn't poison the retry.
        return new Promise(function (resolve, reject) {
            try {
                var p = orig.call(ctx, audioData);
                if (p && typeof p.then === 'function') {
                    p.then(resolve, reject);
                } else {
                    // legacy callback-only signature: orig returned undefined. caller would
                    // pass cb1/cb2 to the original -- but we already swallowed those. fall
                    // back to a wait-loop is impossible; treat as failure.
                    reject(new Error('decodeAudioData returned no promise'));
                }
            } catch (e) {
                reject(e);
            }
        });
    }

    function safeSlice(audioData) {
        try { return audioData.slice(0); } catch (e) { return audioData; }
    }

    proto.decodeAudioData = function (audioData, onSuccess, onError) {
        var ctx = this;
        var size = (audioData && audioData.byteLength) || 0;
        var hd = head16(audioData);
        // capture a fresh slice up front for retry; cheap relative to decoded PCM size,
        // and avoids racing with any post-decode buffer-detach behavior.
        var retryData = safeSlice(audioData);

        var task = queue.then(function () {
            var t0 = (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
            return attempt(ctx, audioData).then(function (buf) {
                var dt = ((typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now()) - t0;
                if (self.__gnShimVerbose) {
                    try {
                        console.log('[audio-decode] OK size=' + size + ' dur=' + buf.duration.toFixed(2) + 's took=' + dt.toFixed(0) + 'ms head=' + hd);
                    } catch (_) {}
                }
                return buf;
            }, function (err) {
                try {
                    console.warn('[audio-decode] retry size=' + size + ' err=' + (err && err.message || err) + ' head=' + hd);
                } catch (_) {}
                // brief yield so the decoder pool can drain any half-finished state.
                return new Promise(function (r) { setTimeout(r, 50); }).then(function () {
                    var t1 = (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
                    return attempt(ctx, retryData).then(function (buf) {
                        var dt2 = ((typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now()) - t1;
                        if (self.__gnShimVerbose) {
                            try {
                                console.log('[audio-decode] OK(retry) size=' + size + ' dur=' + buf.duration.toFixed(2) + 's took=' + dt2.toFixed(0) + 'ms head=' + hd);
                            } catch (_) {}
                        }
                        return buf;
                    });
                });
            });
        });

        // queue advances even on failure so a poison-pill decode doesn't stall the rest.
        queue = task.then(function () {}, function () {});

        // mirror legacy callback API: native fires onSuccess / onError alongside the
        // returned promise. some titles pass callbacks AND await the promise -- both work.
        task.then(function (buf) {
            if (typeof onSuccess === 'function') {
                try { onSuccess(buf); } catch (_) {}
            }
        }, function (err) {
            try {
                console.error('[audio-decode] FAIL size=' + size + ' err=' + (err && err.message || err) + ' head=' + hd);
            } catch (_) {}
            if (typeof onError === 'function') {
                try { onError(err); } catch (_) {}
            }
        });

        return task;
    };

    Object.defineProperty(proto, '__gnDecodeSerialized', {
        value: true, configurable: false, enumerable: false, writable: false,
    });

    if (self.__gnShimVerbose) try { console.log('[audio-decode-serial] installed'); } catch (_) {}
})();
