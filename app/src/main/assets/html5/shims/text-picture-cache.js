// LRU cache of RMMZ TextPicture.js bitmaps keyed by text. the plugin destroys and recreates a PIXI texture on
// every text change, so an event loop cycling a few labels churns textures fast enough to trip a deterministic
// CHECK in chromium 109's renderer threadpool. normal Show Picture (with an image name) is untouched.
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
            if (!this.visible || this._pictureName !== '') {
                return orig.apply(this, arguments);
            }
            var picture = this.picture();
            if (!picture) return orig.apply(this, arguments);
            var text = picture.mzkp_text || '';
            var textChanged = picture.mzkp_textChanged;
            // orig handles clearing the mzkp_text marker.
            if (!text) return orig.apply(this, arguments);
            // bitmap already correct; skipping orig here is safe.
            if (this.mzkp_text === text && !textChanged) {
                return;
            }

            var cached = cache.get(text);
            if (cached) {
                // do NOT destroy the current bitmap: it may be cached too.
                this.mzkp_text = text;
                this.bitmap = cached;
                picture.mzkp_textChanged = false;
                cache.delete(text);
                cache.set(text, cached);
                return;
            }

            // newer plugin variants build the bitmap ASYNCHRONOUSLY (this.bitmap is still null after orig), so retry
            // the cache insert after a microtask, then a frame.
            var sprite = this;
            orig.apply(this, arguments);

            var attemptCache = function () {
                var bmp = sprite.bitmap;
                if (bmp && bmp.mzkp_isTextPicture) {
                    // the plugin's destroy hook gates on this flag; clearing it stops it destroying a cached bitmap.
                    // we destroy on LRU eviction instead.
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
    // MZ runtime not loaded yet.
    var tries = 0;
    var iv = setInterval(function () {
        if (tryInstall() || ++tries > 200) {
            clearInterval(iv);
        }
    }, 50);
})();
