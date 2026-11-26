// WebView's decodeAudioData fails or hangs under parallel load: games that Promise.all many large decodes see some
// reject with EncodingError ("Unable to decode audio data") or never settle, so the game waits forever. the same
// files decoded SERIALLY always succeed. serialize decodes, with one retry from a fresh copy.
//
// two lanes, each serial: small files (sound effects) get their own lane so they never queue behind a big music
// decode -- on slow decoders one BGM can take 20s+ and every footstep behind it plays late in a burst. at most two
// decodes overlap, one of them small.
//
// must run before game code captures decodeAudioData.
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

    // lanes span all contexts: the decoder limits are per renderer process.
    var SMALL_MAX_BYTES = 256 * 1024;
    var lanes = {
        small: { tail: Promise.resolve(), depth: 0 },
        large: { tail: Promise.resolve(), depth: 0 },
    };

    function now() {
        return (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
    }

    // debug runs set self.__gnDecodeObserver (diag-game.js) to time each decode; unset = no cost.
    function observe(info) {
        var fn = self.__gnDecodeObserver;
        if (typeof fn === 'function') { try { fn(info); } catch (_) {} }
    }

    function attempt(ctx, audioData) {
        return new Promise(function (resolve, reject) {
            try {
                var p = orig.call(ctx, audioData);
                if (p && typeof p.then === 'function') {
                    p.then(resolve, reject);
                } else {
                    // callback-only implementation; we never pass it callbacks, so there is no result to wait on.
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

        var laneName = size <= SMALL_MAX_BYTES ? 'small' : 'large';
        var lane = lanes[laneName];
        var tEnqueue = now();
        var depthAtEnqueue = lane.depth++;
        var tStart = 0;
        var task = lane.tail.then(function () {
            var t0 = (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
            tStart = t0;
            // the retry copy guards against builds that detach the input during decode. take it HERE, not at call
            // time: slicing up front keeps a copy of every queued buffer alive at once (hundreds of decodes can queue).
            var retryData = safeSlice(audioData);
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
        lane.tail = task.then(function () {}, function () {});

        task.then(function (buf) { settle(true, buf); }, function () { settle(false, null); });
        function settle(ok, buf) {
            lane.depth--;
            var tEnd = now();
            observe({
                ok: ok, size: size, input: audioData, depthAtEnqueue: depthAtEnqueue,
                waitMs: Math.round(tStart - tEnqueue), decodeMs: Math.round(tEnd - tStart),
                audioSec: buf ? Math.round(buf.duration * 10) / 10 : null,
            });
        }

        // native fires callbacks AND resolves the promise; some games use both.
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
