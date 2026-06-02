// gamenative pack:nwjs shim
//
// Impact-engine NW.js titles (CrossCode-class) specific overrides. the gamepad-kbd-echo
// suppression is now handled by the always-injected gamepad-kbd-suppress.js shim.

(function () {
    'use strict';

    if (self.__gnShimVerbose) try {
        console.log('gamenative pack:nwjs shim loaded');
    } catch (e) {}

    // Impact-engine save-storage adapter selection.
    // Impact's ig.storage.data.save() branches on ig.platform: DESKTOP routes to
    // _saveToFile() (require('fs').writeFile, the path real CrossCode-class titles use);
    // BROWSER falls to localStorage.setItem(this.path, ...). Impact's auto-detection sees
    // our Mobile-Safari userAgent + lack of `process.versions.node` and concludes BROWSER,
    // so saves never reach disk and the GOG cloud manifest gets uploaded as .ldb files
    // instead of cc.save. populated nw.App.dataPath alone isn't enough -- Impact doesn't
    // gate on it.
    //
    // poll for `ig.PLATFORM_TYPES`, force ig.platform to DESKTOP exactly once, stop polling.
    // Impact reads ig.platform synchronously on each save/load, so post-init override
    // takes effect immediately on the first save.
    var platformOverridePoll = setInterval(function () {
        try {
            if (typeof ig !== 'undefined' && ig && ig.PLATFORM_TYPES &&
                typeof ig.PLATFORM_TYPES.DESKTOP === 'number') {
                ig.platform = ig.PLATFORM_TYPES.DESKTOP;
                clearInterval(platformOverridePoll);
                if (self.__gnShimVerbose) try {
                    console.log('gamenative pack:nwjs: forced ig.platform = DESKTOP');
                } catch (e) {}
            }
        } catch (e) { /* keep polling — engine may not be ready */ }
    }, 50);
    // safety: stop polling after 30s regardless. if ig.PLATFORM_TYPES isn't there by then,
    // the title isn't an Impact-class engine and the override doesn't apply.
    setTimeout(function () { clearInterval(platformOverridePoll); }, 30000);

    // Impact bitmap-font auto-detect alpha-decode workaround.
    //
    // Impact's `ig.Font.prototype._loadMetrics` scans the bottom row of each glyph row
    // looking for transparent (alpha == 0) columns to delimit glyphs. desktop NW.js's
    // chromium PNG decoder produces crisp 0/255 alpha values, so the strict `!= 0`
    // check works. Android WebView's chromium decoder produces SOFT alpha values for
    // the same PNG bytes (255 → 254/253, 0 → 1). The "1" values get treated as opaque
    // by `!= 0`, merging glyph runs across what should be transparent boundaries and
    // producing wildly wrong widthMap/indicesX. Visible symptom: save-slot location text
    // rendered with each char shifted +1 in the alphabet ("Cargo Ship - Teleporter" →
    // "Dbshp !Tijq !. !Ufmfqpsufs") because the bitmap font lookup index points at the
    // next glyph in the sprite sheet.
    //
    // fix: replace `_loadMetrics` with a version that thresholds alpha at 128 instead
    // of strict `!= 0`. Soft 1s read as transparent, soft 254s read as opaque, output
    // matches what desktop chromium produces for the same image.
    //
    // we tested premultiplyAlpha/colorSpaceConversion options on createImageBitmap --
    // none produced crisp alpha. the softness is baked into the WebView decoder.
    var fontFixPoll = setInterval(function () {
        try {
            if (typeof ig === 'undefined' || !ig.Font || !ig.Font.prototype || !ig.Font.prototype._loadMetrics) return;
            clearInterval(fontFixPoll);

            ig.Font.prototype._loadMetrics = function (a) {
                if (!this.charHeight) this.charHeight = a.height - 1;
                this.widthMap = [];
                this.indicesX = [];
                this.indicesY = [];
                var canvas = document.createElement('canvas');
                canvas.width = a.width;
                canvas.height = a.height;
                var ctx = canvas.getContext('2d');
                ctx.drawImage(a, 0, 0);
                var THRESH = 128;
                for (var c = 0; c + this.charHeight < a.height;) {
                    var rowData = ctx.getImageData(0, c + this.charHeight, a.width, 1).data;
                    for (var e = 0, g = 0; g < a.width; g++) {
                        var alpha = rowData[g * 4 + 3];
                        if (alpha >= THRESH) {
                            e++;
                        } else if (e) {
                            this.widthMap.push(e);
                            this.indicesX.push(g - e);
                            this.indicesY.push(c);
                            e = 0;
                        }
                    }
                    if (e) {
                        this.widthMap.push(e);
                        this.indicesX.push(a.width - e);
                        this.indicesY.push(c);
                    }
                    c = c + (this.charHeight + 1);
                }
            };

            // re-run metrics on already-loaded fonts (may have been built with the
            // pre-patch buggy algorithm if our shim raced after font onload).
            if (ig.cacheList && ig.cacheList.MultiFont) {
                Object.keys(ig.cacheList.MultiFont).forEach(function (path) {
                    var f = ig.cacheList.MultiFont[path];
                    if (f && f.data && f.data.complete && f.loaded) {
                        f._loadMetrics(f.data);
                        // invalidate cached prerenders so next draw uses fresh metrics
                        (f.iconChangeListeners || []).forEach(function (l) {
                            l.commands = [];
                            l.prerendered = null;
                            l.buffer = null;
                        });
                    }
                });
            }

            if (self.__gnShimVerbose) try {
                console.log('gamenative pack:nwjs: patched ig.Font.prototype._loadMetrics with alpha-threshold (128) for chromium-decode parity');
            } catch (e) {}
        } catch (e) { /* keep polling — engine may not be ready */ }
    }, 50);
    setTimeout(function () { clearInterval(fontFixPoll); }, 30000);

    // shader precision promotion: mediump -> highp.
    //
    // desktop GPUs IGNORE precision qualifiers and always compute fp32, so desktop-authored
    // nwjs engines ship `precision mediump float` boilerplate untested at real fp16. mobile
    // GPUs (adreno/mali) honor it: 11-bit mantissa, epsilon ~0.0005 near 1.0 -- one texel in
    // a 2048px atlas. terra (Alabaster Dawn) computes atlas UVs and shadow depth in mediump,
    // producing tile-seam stripes on panel backgrounds and fuzzy shadows that are absent on
    // windows (device-validated 2026-06-05: highp removes both; fractional DPR was NOT the
    // cause).
    //
    // WebGL2 only: ES 3.0 mandates highp support in fragment shaders so the rewrite can
    // never break a compile. WebGL1 fragment highp is optional hardware support -- don't
    // touch it until a title needs it (impact engines are canvas2d anyway).
    try {
        if (self.WebGL2RenderingContext && WebGL2RenderingContext.prototype.shaderSource) {
            var origShaderSource = WebGL2RenderingContext.prototype.shaderSource;
            WebGL2RenderingContext.prototype.shaderSource = function (shader, source) {
                return origShaderSource.call(this, shader, String(source).replace(/precision\s+mediump\s+float/g, 'precision highp float'));
            };
            if (self.__gnShimVerbose) try {
                console.log('gamenative pack:nwjs: shaderSource mediump->highp promotion active');
            } catch (e) {}
        }
    } catch (e) { /* leave shaders untouched if the hook fails */ }
})();
