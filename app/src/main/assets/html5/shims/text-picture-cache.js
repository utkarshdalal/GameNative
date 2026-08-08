// text-picture-cache: memoize RMMZ TextPicture.js bitmap creation by text content
// with an LRU cap, eliminating the rapid PIXI BaseTexture create-destroy churn that
// trips a deterministic CHECK in chromium 109's renderer threadpool.

// canary pattern: an RMMZ Common Event that runs a tight LOOP calling TextPicture
// .setFromVar + Show Picture with a few cycling labels (e.g. ~6 unique strings).
// TextPicture.js destroys the old PIXI texture and creates a new one EVERY iteration,
// churning the GPU upload path.

// fix: cache bitmaps by text. cache hit → reuse the bitmap, no destroy+create cycle.
// since the loop cycles ~6 unique strings, hit rate is near 100% after the first round.

// safety:
// - only intercepts the TextPicture flow (this._pictureName === "" + non-empty text).
// normal Show Picture (with image name) falls through to the original updateBitmap.
// - cached bitmaps have mzkp_isTextPicture cleared so the original
// destroyTextPictureBitmap closure (which gates on that flag) becomes a no-op,
// preventing destroy of a still-referenced cached bitmap.
// - LRU eviction destroys evicted bitmaps explicitly to release GPU memory.
// - install is idempotent (gated on _gnTextPicCacheInstalled).
// - install is deferred until Sprite_Picture is defined (MZ runtime loaded).
(function () {
    'use strict';

    var LIMIT = 32;

    function tryInstall() {
        if (typeof Sprite_Picture === 'undefined') return false;
        if (Sprite_Picture.prototype._gnTextPicCacheInstalled) return true;
        Sprite_Picture.prototype._gnTextPicCacheInstalled = true;

        var cache = new Map();
        var orig = Sprite_Picture.prototype.updateBitmap;

        Sprite_Picture.prototype.updateBitmap = function () {
            // non-TextPicture sprite → original behavior
            if (!this.visible || this._pictureName !== '') {
                return orig.apply(this, arguments);
            }
            var picture = this.picture();
            if (!picture) return orig.apply(this, arguments);
            var text = picture.mzkp_text || '';
            var textChanged = picture.mzkp_textChanged;
            // no text → original (handles cleanup of mzkp_text marker)
            if (!text) return orig.apply(this, arguments);
            // same text and no change flag → nothing to do, AND avoid running orig
            // (which would still call _Sprite_Picture_updateBitmap base -- that's fine
            // to skip when there's no text change since the bitmap is already correct)
            if (this.mzkp_text === text && !textChanged) {
                return;
            }

            var cached = cache.get(text);
            if (cached) {
                // hit: reuse cached bitmap, do NOT destroy current one (it may be cached too)
                this.mzkp_text = text;
                this.bitmap = cached;
                picture.mzkp_textChanged = false;
                // promote LRU
                cache.delete(text);
                cache.set(text, cached);
                return;
            }

            // miss: run original (destroys old, creates new) then cache the new bitmap.
            // current TextPicture plugin variants build the bitmap ASYNCHRONOUSLY -- this.bitmap
            // is null synchronously after orig.apply. defer the cache.set to a microtask (then
            // a frame as last resort) so we capture the populated bitmap once the plugin's
            // callback runs. legacy sync plugins still work via the first branch.
            var sprite = this;
            orig.apply(this, arguments);

            var attemptCache = function () {
                var bmp = sprite.bitmap;
                if (bmp && bmp.mzkp_isTextPicture) {
                    // disable the destroy hook for this bitmap -- destroyTextPictureBitmap
                    // gates on mzkp_isTextPicture, so clearing it makes the destroy a no-op.
                    // we manage destruction ourselves on LRU eviction.
                    bmp.mzkp_isTextPicture = false;
                    cache.set(text, bmp);
                    while (cache.size > LIMIT) {
                        var firstKey = cache.keys().next().value;
                        var firstBmp = cache.get(firstKey);
                        cache.delete(firstKey);
                        if (firstBmp && typeof firstBmp.destroy === 'function') {
                            try { firstBmp.destroy(); } catch (e) {}
                        }
                    }
                    return true;
                }
                return false;
            };

            if (!attemptCache()) {
                Promise.resolve().then(function () {
                    if (!attemptCache()) {
                        requestAnimationFrame(attemptCache);
                    }
                });
            }
        };

        if (self.__gnShimVerbose) {
            try {
                console.log('[text-picture-cache] installed (limit=' + LIMIT + ')');
            } catch (e) {}
        }
        return true;
    }

    if (tryInstall()) return;
    // MZ not yet loaded -- retry on a short interval until Sprite_Picture is defined.
    var tries = 0;
    var iv = setInterval(function () {
        if (tryInstall() || ++tries > 200) {
            clearInterval(iv);
        }
    }, 50);
})();
