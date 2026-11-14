// WebView's decodeAudioData fails or hangs under parallel load: games that Promise.all many large decodes see some
// reject with EncodingError ("Unable to decode audio data") or never settle, so the game waits forever. the same
// files decoded SERIALLY always succeed. serialize all decodes, with one retry from a fresh copy.
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

    // one queue across all contexts: the decoder limits are per renderer process.
    var queue = Promise.resolve();

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

        var task = queue.then(function () {
            var t0 = (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
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
        queue = task.then(function () {}, function () {});

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
