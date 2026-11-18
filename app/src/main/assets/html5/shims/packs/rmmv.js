// pack:rmmv shim -- RPG Maker MV/MZ engine patches.
(function () {
    'use strict';

    function patchWhenReady(fn, retries) {
        try {
            if (fn()) return;
        } catch (e) {}
        if (retries > 0) setTimeout(function () { patchWhenReady(fn, retries - 1); }, 50);
    }

    // --- Input.gamepadMapper -- W3C standard gamepad layout ---
    patchWhenReady(function () {
        if (!window.Input) return false;
        Input.gamepadMapper = Object.assign(Input.gamepadMapper || {}, {
            0: 'ok',        // A
            1: 'cancel',    // B
            2: 'shift',     // X
            3: 'menu',      // Y
            4: 'pageup',    // L1
            5: 'pagedown',  // R1
        });

        // --- Input.keyMapper -- keyboard fallback ---
        // MANDATORY for titles that don't poll the Gamepad API; defends against plugins dropping stock keys.
        // 27 and 88 MUST be 'escape', NOT 'cancel': Input.isTriggered('menu') aliases only through
        // 'escape' (_isEscapeCompatible); 'cancel' silently breaks menu-open in every RMMV/RMMZ title.
        Input.keyMapper = Object.assign(Input.keyMapper || {}, {
            13: 'ok',       // Enter
            27: 'escape',   // Escape
            32: 'ok',       // Space
            88: 'escape',   // X
            90: 'ok',       // Z
            16: 'shift',    // Shift (dash)
            17: 'control',  // Control (skip)
            81: 'pageup',   // Q
            87: 'pagedown', // W
            37: 'left',
            38: 'up',
            39: 'right',
            40: 'down',
        });
        return true;
    }, 100);

    // --- DataManager.makeSavefileInfo rescue ---
    // stock reads `this._globalId`. plugin wrappers that forward via `_orig()` (no .call(this)) make
    // `this === window`, globalId goes undefined, isThisGameFile fails, and Continue stays disabled.
    // binding DataManager unconditionally is immune to any such wrapper.
    patchWhenReady(function () {
        if (!window.DataManager || !DataManager.makeSavefileInfo) return false;
        var orig = DataManager.makeSavefileInfo;
        DataManager.makeSavefileInfo = function () {
            return orig.call(DataManager);
        };
        return true;
    }, 100);

    // --- Scene_Title hardware-back handler ---
    // Escape/Backspace = Android BACK. only pops on Scene_Title; elsewhere the event propagates so
    // AndroidEvent.BackPressed routes normally.
    document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape' || e.key === 'Backspace') {
            if (window.SceneManager && SceneManager._scene &&
                SceneManager._scene.constructor.name === 'Scene_Title') {
                SceneManager.pop();
                e.preventDefault();
            }
        }
    }, { capture: true });

    // --- plugin stub late-bind via DataManager.loadDataFile hook ---
    // values are /_shims/ URLs served by AssetInterceptor.openShimAsset.
    var PLUGIN_STUBS = {
        'YEP_CoreEngine':          '/_shims/packs/rmmv-plugins/yep-core.js',
        'Olivia_AnimatedMainMenu': '/_shims/packs/rmmv-plugins/olivia.js',
        'Galv_MessageBusts':       '/_shims/packs/rmmv-plugins/galv.js',
        'HIME_ChoiceOptions':      '/_shims/packs/rmmv-plugins/hime.js',
    };

    patchWhenReady(function () {
        if (!window.DataManager || !DataManager.loadDataFile) return false;
        var orig = DataManager.loadDataFile;
        var installed = false;
        DataManager.loadDataFile = function () {
            orig.apply(this, arguments);
            if (!installed && window.Imported) {
                installed = true;
                Object.keys(PLUGIN_STUBS).forEach(function (name) {
                    if (Imported[name]) {
                        var s = document.createElement('script');
                        s.src = PLUGIN_STUBS[name];
                        document.head.appendChild(s);
                    } else {
                        if (self.__gnShimVerbose) console.log('gamenative pack:rmmv — stub available for ' + name + ', game does not use it; skipping');
                    }
                });
                // covers initialize having already run on a subclass before our wrapper caught it.
                // see canvas fit-to-window below.
                reassertStretchFit(window.Graphics);
            }
        };
        return true;
    }, 100);

    // --- canvas fit-to-window ---
    // RPG Maker only scales the canvas to the window when Graphics._stretchEnabled is true, which
    // Graphics.initialize sets to Utils.isMobileDevice() -- FALSE since we present as desktop NW.js -- so
    // the game renders at native resolution and overflows the viewport. re-asserting it once right after
    // initialize lets the engine's own _updateRealScale + _centerElement fit whatever canvases it owns,
    // and leaves later genuine writes (e.g. an in-game scaling option) free to take effect.
    //
    // YEP_CoreEngine's _updateRealScale snaps a sub-1.0 fit to 0.5 (half size in a corner), so YEP titles
    // also get a clean min-fit. YEP assigns its version at plugin load, before initialize, so ours wins.
    //
    // wrapping the ORIGINAL initialize survives games reassigning `Graphics = class extends Graphics`
    // (e.g. OMORI): the subclass inherits the wrapper and `this` is the live object.
    function minFitRealScale() {
        var w = this._width || 0, h = this._height || 0;
        var sw = window.innerWidth || document.documentElement.clientWidth || 0;
        var sh = window.innerHeight || document.documentElement.clientHeight || 0;
        this._realScale = (w > 0 && h > 0 && sw > 0 && sh > 0) ? Math.min(sw / w, sh / h) : 1;
    }
    function reassertStretchFit(G) {
        if (!G) return;
        G._stretchEnabled = true;
        if (window.Imported && Imported.YEP_CoreEngine) G._updateRealScale = minFitRealScale;
        try { G._updateAllElements && G._updateAllElements(); } catch (_) {}
    }
    patchWhenReady(function () {
        if (!window.Graphics || typeof Graphics.initialize !== 'function') return false;
        if (!Graphics.__gnInitWrapped) {
            Graphics.__gnInitWrapped = true;
            var origInit = Graphics.initialize;
            Graphics.initialize = function () {
                var r = origInit.apply(this, arguments);
                reassertStretchFit(this);   // `this` is the live (possibly subclassed) Graphics
                return r;
            };
            // initialize may have run before we wrapped (fast boot).
            if (Graphics._canvas) reassertStretchFit(Graphics);
        }
        window.addEventListener('resize', function () {
            try { window.Graphics && Graphics._updateAllElements && Graphics._updateAllElements(); } catch (_) {}
        });
        return true;
    }, 600);

    // --- Input._pollGamepads defensive wrap ---
    // some plugins override _updateGamepadState assuming SceneManager._scene is non-null, but
    // _pollGamepads runs from frame 1, before Scene_Boot.create sets it -> throw -> boot hangs.
    // wrapping the caller still picks up plugin overrides via dynamic dispatch on this._updateGamepadState.
    patchWhenReady(function () {
        if (!window.Input || !Input._pollGamepads) return false;
        var origPoll = Input._pollGamepads;
        var loggedOnce = false;
        Input._pollGamepads = function () {
            try {
                return origPoll.apply(this, arguments);
            } catch (e) {
                if (!loggedOnce) {
                    loggedOnce = true;
                    try { console.warn('gamenative pack:rmmv — _pollGamepads threw, suppressing: ' + (e && e.message)); } catch (_) {}
                }
            }
        };
        return true;
    }, 100);

    // --- Decrypter.decryptArrayBuffer bypass ---
    // Html5DecryptContext decrypts .rpgmv{p,o,m} host-side, so the JS Decrypter would see real bytes and
    // throw "Header is wrong". fake RPGMV signature still present = host didn't decrypt; let JS do it.
    patchWhenReady(function () {
        if (!window.Decrypter || !Decrypter.decryptArrayBuffer) return false;
        var orig = Decrypter.decryptArrayBuffer;
        // fake header = "RPGMV" ascii
        Decrypter.decryptArrayBuffer = function (arrayBuffer) {
            if (!arrayBuffer || arrayBuffer.byteLength < 16) return orig.call(this, arrayBuffer);
            var h = new Uint8Array(arrayBuffer, 0, 5);
            if (h[0] === 0x52 && h[1] === 0x50 && h[2] === 0x47 && h[3] === 0x4D && h[4] === 0x56) {
                return orig.call(this, arrayBuffer);
            }
            return arrayBuffer;
        };
        return true;
    }, 100);

    // --- SceneManager.exit / .terminate + window.close -> __gnRuntimeBridge.exit ---
    // RMMV terminate calls window.close(); RMMZ calls nw.App.quit() under NW.js, else window.close().
    // Chromium blocks window.close() on main windows, so without this quitting is a no-op.
    // additive to the nw.js proxy, which catches nw.App.quit / Window.close.
    function routeExit(source) {
        try {
            if (self.__gnShimVerbose) console.log('gamenative pack:rmmv — routeExit from ' + source);
            if (typeof window.__gnRuntimeBridge !== 'undefined' &&
                typeof window.__gnRuntimeBridge.exit === 'function') {
                window.__gnRuntimeBridge.exit(source);
                return true;
            }
        } catch (_e) {}
        return false;
    }
    try {
        var realClose = (typeof window.close === 'function') ? window.close.bind(window) : null;
        window.close = function () {
            if (routeExit('window.close')) return;
            if (realClose) { try { realClose(); } catch (_e) {} }
        };
    } catch (_e) {}
    patchWhenReady(function () {
        if (!window.SceneManager) return false;
        if (typeof SceneManager.exit === 'function') {
            var origExit = SceneManager.exit;
            SceneManager.exit = function () {
                try { origExit.apply(this, arguments); } catch (_e) {}
                routeExit('SceneManager.exit');
            };
        }
        if (typeof SceneManager.terminate === 'function') {
            var origTerm = SceneManager.terminate;
            SceneManager.terminate = function () {
                if (routeExit('SceneManager.terminate')) return;
                try { origTerm.apply(this, arguments); } catch (_e) {}
            };
        }
        return true;
    }, 100);

    // --- AudioManager.audioFileExt -> '.ogg' ---
    // MV picks '.m4a' on mobile, but many titles only ship .rpgmvo: every fetch 404s and the game
    // retries in a tight loop (silent + CPU burn). Chromium decodes Ogg natively.
    patchWhenReady(function () {
        if (!window.AudioManager) return false;
        AudioManager.audioFileExt = function () { return '.ogg'; };
        return true;
    }, 100);

    // --- ErrorPrinter pointer-events: none ---
    // the engine's <p id="ErrorPrinter"> sits over the canvas mid-screen even when empty and swallows
    // taps in that band. errors still display; they just don't block input.
    try {
        var s = document.createElement('style');
        s.textContent = '#ErrorPrinter { pointer-events: none !important; }';
        (document.head || document.documentElement).appendChild(s);
    } catch (e) {}

    // --- zero-strength BlurFilter bypass ---
    // a strength-0 PIXI BlurFilter (e.g. OMORI's Spriteset_Map) still ping-pongs through 8 RGBA8 passes.
    // on some mobile GPUs each requantize lands on a rounding tie that breaks differently per row, so
    // rows random-walk apart into horizontal banding. an identity pass is bitwise-clean; real blurs untouched.
    patchWhenReady(function () {
        if (!(window.PIXI && PIXI.filters && PIXI.filters.BlurFilter)) return false;
        var identityFilter = null;
        var origApply = PIXI.filters.BlurFilter.prototype.apply;
        PIXI.filters.BlurFilter.prototype.apply = function (fm, input, output, clear) {
            if (this.blur === 0) {
                if (!identityFilter) {
                    identityFilter = new PIXI.Filter(
                        undefined,
                        'varying vec2 vTextureCoord; uniform sampler2D uSampler; void main(){ gl_FragColor = texture2D(uSampler, vTextureCoord); }'
                    );
                }
                return fm.applyFilter(identityFilter, input, output, clear);
            }
            return origApply.call(this, fm, input, output, clear);
        };
        if (self.__gnShimVerbose) try { console.log('gamenative pack:rmmv: zero-strength blur bypass active'); } catch (e) {}
        return true;
    }, 200);

    // --- oversized-texture compat ---
    // Android WebView caps WebGL MAX_TEXTURE_SIZE at 4096. RPG Maker uploads each spritesheet as one
    // BaseTexture and animates by moving the UV window, so the WHOLE sheet must fit in a single GPU
    // texture. VisuStella inject animations ship sheets wider than that (a 1x15 row of large cells =
    // 6912px); texImage2D rejects the upload, leaving an incomplete texture that samples opaque black.
    var maxTexSize = 0;
    function getMaxTexSize() {
        if (maxTexSize) return maxTexSize;
        var gl = null;
        try { gl = window.Graphics && Graphics.app && Graphics.app.renderer && Graphics.app.renderer.gl; } catch (_) {}
        if (!gl) { try { var c = document.createElement('canvas'); gl = c.getContext('webgl2') || c.getContext('webgl') || c.getContext('experimental-webgl'); } catch (_) {} }
        maxTexSize = gl ? gl.getParameter(gl.MAX_TEXTURE_SIZE) : 4096;
        return maxTexSize;
    }

    // general fallback: upload oversized bitmaps DOWNSCALED to fit, but keep the BaseTexture's
    // logical size at the original (resolution compensates) so frame/UV coords are unchanged. renders
    // softer instead of black; covers static giants (battlebacks, parallaxes, big pictures).
    patchWhenReady(function () {
        if (!window.Bitmap || !Bitmap.prototype._createBaseTexture || !window.PIXI) return false;
        if (Bitmap.prototype.__gnOversizeWrapped) return true;
        Bitmap.prototype.__gnOversizeWrapped = true;
        var orig = Bitmap.prototype._createBaseTexture;
        Bitmap.prototype._createBaseTexture = function (source) {
            var max = getMaxTexSize(), w = source.width, h = source.height;
            if (w <= max && h <= max) return orig.call(this, source);
            var scale = Math.min(max / w, max / h);
            var dw = Math.max(1, Math.floor(w * scale)), dh = Math.max(1, Math.floor(h * scale));
            var canvas = document.createElement('canvas');
            canvas.width = dw;
            canvas.height = dh;
            canvas.getContext('2d').drawImage(source, 0, 0, dw, dh);
            this._baseTexture = new PIXI.BaseTexture(canvas);
            this._baseTexture.mipmap = false;
            this._baseTexture.resolution = dw / w;  // logical size stays w x h; only the GPU copy shrinks
            this._baseTexture.width = w;
            this._baseTexture.height = h;
            this._updateScaleMode();
        };
        return true;
    }, 100);

    // full-res path for cell-based inject animations: the sheet stays a full-res 2D source (2D
    // canvases aren't bound by MAX_TEXTURE_SIZE). after the engine's updateFrame sets this._frame to
    // the current cell, blit just that cell into a reused bitmap and re-point the sprite at it -- one
    // small per-frame upload at full sharpness. engages only when the sheet exceeds the cap.
    patchWhenReady(function () {
        if (!window.Sprite_InjectAnimation || !Sprite_InjectAnimation.prototype.updateFrame || !window.Rectangle) return false;
        if (Sprite_InjectAnimation.prototype.__gnPerCell) return true;
        Sprite_InjectAnimation.prototype.__gnPerCell = true;
        var origUpdateFrame = Sprite_InjectAnimation.prototype.updateFrame;
        Sprite_InjectAnimation.prototype.updateFrame = function () {
            origUpdateFrame.call(this);
            var sheet = this._bitmap;
            var src = sheet && (sheet._canvas || sheet._image);
            var max = getMaxTexSize();
            if (!src || (src.width <= max && src.height <= max)) return;
            var f = this._frame;
            var w = Math.max(1, Math.floor(f.width)), h = Math.max(1, Math.floor(f.height));
            if (!this.__gnCell || this.__gnCell.width !== w || this.__gnCell.height !== h) {
                this.__gnCell = new Bitmap(w, h);
                this.__gnCell.smooth = sheet.smooth;
            }
            var ctx = this.__gnCell._context;
            ctx.clearRect(0, 0, w, h);
            ctx.drawImage(src, Math.floor(f.x), Math.floor(f.y), w, h, 0, 0, w, h);
            this.__gnCell.baseTexture.update();
            // re-point the texture at the cell each frame (the engine left it on the oversized sheet).
            // relies on PIXI 5's texture.baseTexture being a plain field -- revisit on a PIXI bump.
            this.texture.baseTexture = this.__gnCell.baseTexture;
            this.texture.frame = new Rectangle(0, 0, w, h);
            this.pivot.set(0, 0);
        };
        return true;
    }, 300);

    if (self.__gnShimVerbose) try { console.log('gamenative pack:rmmv shim loaded'); } catch (e) {}
})();
