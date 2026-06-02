// gamenative pack:nwjs shim -- overrides for Impact-engine NW.js titles (e.g. CrossCode).

(function () {
    'use strict';

    if (self.__gnShimVerbose) try {
        console.log('gamenative pack:nwjs shim loaded');
    } catch (e) {}

    // Impact's ig.storage.data.save() branches on ig.platform: DESKTOP writes a save file via
    // require('fs'); BROWSER falls back to localStorage. auto-detection sees our mobile UA and no
    // `process.versions.node` and picks BROWSER, so saves never reach disk and cloud sync uploads
    // leveldb files instead of the save file. nw.App.dataPath alone doesn't help -- Impact ignores it.
    // ig.platform is read on every save/load, so overriding after init is enough.
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
        } catch (e) { /* keep polling -- engine may not be ready */ }
    }, 50);
    // no ig.PLATFORM_TYPES after 30s = not an Impact engine.
    setTimeout(function () { clearInterval(platformOverridePoll); }, 30000);

    // Impact's ig.Font _loadMetrics delimits glyphs by alpha == 0 columns. desktop chromium decodes
    // crisp 0/255 alpha, but Android WebView decodes SOFT alpha for the same PNG (0 -> 1, 255 -> 254),
    // so `!= 0` merges glyph runs and text renders shifted by one glyph ("Cargo" -> "Dbshp").
    // thresholding at 128 restores desktop parity. createImageBitmap premultiplyAlpha /
    // colorSpaceConversion options don't help -- the softness is in the WebView decoder.
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

            // fonts that loaded before the patch have wrong metrics.
            if (ig.cacheList && ig.cacheList.MultiFont) {
                Object.keys(ig.cacheList.MultiFont).forEach(function (path) {
                    var f = ig.cacheList.MultiFont[path];
                    if (f && f.data && f.data.complete && f.loaded) {
                        f._loadMetrics(f.data);
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
        } catch (e) { /* keep polling -- engine may not be ready */ }
    }, 50);
    setTimeout(function () { clearInterval(fontFixPoll); }, 30000);

    // shader precision promotion mediump -> highp. desktop GPUs IGNORE precision qualifiers (always fp32),
    // so desktop-authored engines ship `precision mediump float` untested at real fp16. mobile GPUs honor
    // it: epsilon ~0.0005 near 1.0 is one texel in a 2048px atlas -> tile-seam stripes, fuzzy shadows.
    // WebGL2 only: ES 3.0 mandates fragment highp so the rewrite can't break a compile; WebGL1 fragment
    // highp is optional hardware support.
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
