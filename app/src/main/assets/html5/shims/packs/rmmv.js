// pack:rmmv shim:
// (a) Input.gamepadMapper W3C defaults (A→ok, B→cancel, X→shift, Y→menu, L→pageup, R→pagedown).
// (b) Input.keyMapper keyboard fallback (Enter/Escape/Shift/Arrow keys) -- MANDATORY for the
// keyboard-fallback titles that don't poll Gamepad API.
// (c) DataManager.makeSavefileInfo wrapper calling through with .call(this).
// (d) Scene_Title hardware-back handler: Escape/Backspace → SceneManager.pop().
// (e) RmmvPluginRegistry late-bind via DataManager.loadDataFile hook.
// (f) Decrypter.decryptArrayBuffer bypass when host already decrypted: detects fake-RPGMV
// signature in header; if absent, bytes are already real and we pass through. keeps JS
// decrypter as fallback when the host hasn't pre-decrypted.
// (g) AudioManager.audioFileExt → '.ogg' -- MV picks .m4a on Utils.isMobileDevice()=true (Android),
// but most RMMV titles only ship .rpgmvo (encrypted Ogg). every audio 404s, game retries in tight
// loop → silent game + CPU burn. force .ogg -- Chromium decodes it natively; our .rpgmvo decrypt
// pipeline already produces real Ogg bytes. matches TERMINA standalone's ForceOggAudio plugin.
(function () {
    'use strict';

    function patchWhenReady(fn, retries) {
        try {
            if (fn()) return;
        } catch (e) {}
        if (retries > 0) setTimeout(function () { patchWhenReady(fn, retries - 1); }, 50);
    }

    // --- (a) Input.gamepadMapper -- W3C standard gamepad layout ---
    patchWhenReady(function () {
        if (!window.Input) return false;
        Input.gamepadMapper = Object.assign(Input.gamepadMapper || {}, {
            0: 'ok',        // A (W3C index 0)
            1: 'cancel',    // B (W3C index 1)
            2: 'shift',     // X (index 2)
            3: 'menu',      // Y (index 3)
            4: 'pageup',    // L1 (index 4)
            5: 'pagedown',  // R1 (index 5)
            // 6: L2, 7: R2 -- RMMV default mapper doesn't use these; leave unmapped
        });

        // --- (b) Input.keyMapper -- keyboard fallback ---
        // RMMV ships sane defaults; this augmentation defends against user-config dropping them
        // and ensures gamepad-name → RMMV-action bindings from input-fallback work.
        // 27 and 88 MUST be 'escape', NOT 'cancel': Scene_Map.isMenuCalled checks
        // Input.isTriggered('menu'), which aliases via _isEscapeCompatible only through 'escape'.
        // Mapping these to 'cancel' silently breaks menu-open in every RMMV/RMMZ title.
        Input.keyMapper = Object.assign(Input.keyMapper || {}, {
            13: 'ok',       // Enter → confirm
            27: 'escape',   // Escape → escape (cancel + menu via _isEscapeCompatible)
            32: 'ok',       // Space → confirm
            88: 'escape',   // X → escape (RMMV stock; menu open requires this)
            90: 'ok',       // Z → confirm (RMMV convention)
            16: 'shift',    // Shift → shift (dash)
            17: 'control',  // Control → control (skip)
            81: 'pageup',   // Q → pageup
            87: 'pagedown', // W → pagedown
            37: 'left',
            38: 'up',
            39: 'right',
            40: 'down',
        });
        return true;
    }, 100);

    // --- (c) DataManager.makeSavefileInfo rescue ---
    // stock impl reads `this._globalId`. any plugin wrapper that forwards via
    // `var info = _orig();` (no .call(this)) drops the binding → `this === window` → info.globalId
    // becomes undefined → DataManager.isThisGameFile → false → Continue stays disabled.
    // TERMINA's MrTS_SimpleSaveLoadMenu.js is one such plugin (see TERMINA_Android capacitor/
    // force DataManager binding unconditionally so we're immune to upstream/downstream wrappers
    // that forget .call(this). stock's `this._globalId` → DataManager._globalId is the correct value.
    patchWhenReady(function () {
        if (!window.DataManager || !DataManager.makeSavefileInfo) return false;
        var orig = DataManager.makeSavefileInfo;
        DataManager.makeSavefileInfo = function () {
            return orig.call(DataManager);
        };
        return true;
    }, 100);

    // --- (d) Scene_Title hardware-back handler ---
    // Escape/Backspace = Android BACK in WebView contexts. Only pops on Scene_Title;
    // outside Scene_Title the event propagates so AndroidEvent.BackPressed routes normally 
    document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape' || e.key === 'Backspace') {
            if (window.SceneManager && SceneManager._scene &&
                SceneManager._scene.constructor.name === 'Scene_Title') {
                SceneManager.pop();
                e.preventDefault();
            }
        }
    }, { capture: true });

    // --- (e) Plugin late-bind via DataManager.loadDataFile hook ---
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
                        // we ship a stub for `name` but this game doesn't import it -- expected,
                        // not an error. nothing to inject.
                        console.log('gamenative pack:rmmv — stub available for ' + name + ', game does not use it; skipping');
                    }
                });
                // re-install canvas fit/center AFTER plugin IIFEs have run (YEP_CoreEngine
                // assigns its own Graphics._updateRealScale at load time and would otherwise
                // win the assignment race). see (j) below for the implementation.
                applyCanvasFitAndCenter();
            }
        };
        return true;
    }, 100);

    // --- (j) Canvas fit-to-window + explicit-pixel centering ---
    // two distinct WebView regressions vs desktop NW.js this addresses:
    //
    // 1. SIZING: stock RMMV scales to min(innerW/_width, innerH/_height) when _stretchEnabled
    //    (true by default on Utils.isMobileDevice()). YEP_CoreEngine OVERRIDES _updateRealScale
    //    to snap to discrete tiers (3, 2, 1.5, 1, 0.5). On small viewports where computed
    //    scale < 1, YEP falls into the 0.5 bucket → canvas at half native size in a corner.
    //    Pre-YEP fix was per-game patches.json regex (OMORI). Pack-level: override
    //    _updateRealScale to a clean fit-min, no tiers -- applies uniformly across all RMMV
    //    titles regardless of YEP version / config.
    //
    // 2. CENTERING: stock RMMV._centerElement uses `inset:0; margin:auto`. Chromium WebView
    //    inside our Compose AndroidView resolves this to negative top → canvas centered on
    //    y=0 instead of y=mid → top half clipped. IndexHtmlRewriter pre-injects a stylesheet
    //    forcing `top:0 !important; bottom:auto !important` so canvas pins to top instead.
    //    That fixed clipping but lost vertical centering (Look Outside symptom: bottom strip).
    //    Earlier attempt to re-center via `transform: translateY(-50%)` reverted to broken
    //    state, unexplained. Approach here: replace _centerElement entirely with explicit
    //    pixel left/top, using setProperty(..., 'important') so inline-style-with-important
    //    beats the stylesheet's !important. Robust against RMMV inline-style writes.
    //
    // install timing: applyCanvasFitAndCenter is called twice -- once in patchWhenReady (early,
    // before plugins load, so very-first frames are sane) and again in the loadDataFile hook
    // above (post-plugins, to overwrite any plugin assignments).
    function setProp(el, prop, val) {
        try { el.style.setProperty(prop, val, 'important'); } catch (_) {}
    }
    function applyCanvasFitAndCenter() {
        if (!window.Graphics) return;
        // 1. fit-min scaling, no tiers.
        if (Graphics._updateRealScale) {
            Graphics._updateRealScale = function () {
                var w = this._width || 0, h = this._height || 0;
                var sw = window.innerWidth || document.documentElement.clientWidth || 0;
                var sh = window.innerHeight || document.documentElement.clientHeight || 0;
                if (w > 0 && h > 0 && sw > 0 && sh > 0) {
                    this._realScale = Math.min(sw / w, sh / h);
                } else {
                    this._realScale = 1;
                }
            };
        }
        // belt-and-suspenders for paths that read _stretchEnabled directly (some plugins
        // gate their own scale logic on this).
        try { Graphics._stretchEnabled = true; } catch (_) {}
        // 2. explicit-pixel centering via setProperty('important'). beats both the
        // IndexHtmlRewriter stylesheet's !important AND any non-important inline writes
        // RMMV/plugin code may attempt later.
        if (Graphics._centerElement) {
            Graphics._centerElement = function (element) {
                var scale = this._realScale || 1;
                var dispW = (element.width || 0) * scale;
                var dispH = (element.height || 0) * scale;
                var sw = window.innerWidth || 0;
                var sh = window.innerHeight || 0;
                setProp(element, 'position', 'absolute');
                setProp(element, 'margin', '0');
                setProp(element, 'left', Math.floor((sw - dispW) / 2) + 'px');
                setProp(element, 'top', Math.floor((sh - dispH) / 2) + 'px');
                setProp(element, 'right', 'auto');
                setProp(element, 'bottom', 'auto');
                setProp(element, 'width', dispW + 'px');
                setProp(element, 'height', dispH + 'px');
            };
        }
        // re-layout immediately if Graphics is far enough along.
        try { Graphics._updateAllElements && Graphics._updateAllElements(); } catch (_) {}
    }
    patchWhenReady(function () {
        if (!window.Graphics || !Graphics._updateRealScale || !Graphics._centerElement) return false;
        applyCanvasFitAndCenter();
        // re-fit on viewport change (rotation, IME show/hide, immersive toggle).
        window.addEventListener('resize', function () {
            try { Graphics._updateAllElements && Graphics._updateAllElements(); } catch (_) {}
        });
        return true;
    }, 200);

    // --- (e2) Input._pollGamepads defensive wrap ---
    // some title plugins (e.g. master2015hp_InStarTimeSnippet) override _updateGamepadState and
    // assume SceneManager._scene is non-null on every poll. _pollGamepads runs from frame 1, BEFORE
    // Scene_Boot.create populates _scene → throw → updateMain never reached → boot hangs (YEP catches
    // via SceneManager.catchException but that doesn't unblock anything; the engine just spins).
    // wrap _pollGamepads (stock fn in rpg_core.js, present at our shim load) so the per-pad
    // _updateGamepadState throw is contained. dynamic dispatch on this._updateGamepadState still
    // picks up plugin overrides even though we wrap the caller earlier.
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

    // --- (f) Decrypter.decryptArrayBuffer bypass ---
    // Html5DecryptContext decrypts .rpgmv{p,o,m} server-side. the JS Decrypter then sees real
    // asset bytes (PNG/OGG/M4A) instead of the fake RPGMV signature and throws "Header is wrong".
    // detect the fake signature; if PRESENT, host didn't decrypt (no key / short file / error) --
    // fall through to the original JS Decrypter so the game still works. if ABSENT, bytes are
    // already real -- pass through as identity.
    patchWhenReady(function () {
        if (!window.Decrypter || !Decrypter.decryptArrayBuffer) return false;
        var orig = Decrypter.decryptArrayBuffer;
        // fake header = "RPGMV" ascii = 52 50 47 4D 56
        Decrypter.decryptArrayBuffer = function (arrayBuffer) {
            if (!arrayBuffer || arrayBuffer.byteLength < 16) return orig.call(this, arrayBuffer);
            var h = new Uint8Array(arrayBuffer, 0, 5);
            if (h[0] === 0x52 && h[1] === 0x50 && h[2] === 0x47 && h[3] === 0x4D && h[4] === 0x56) {
                // fake header still present -- host decrypt didn't run. let JS handle it.
                return orig.call(this, arrayBuffer);
            }
            // host already decrypted -- bytes are real.
            return arrayBuffer;
        };
        return true;
    }, 100);

    // --- (h) SceneManager.exit / .terminate + window.close → __gnRuntimeBridge.exit ---
    // belt-and-suspenders: wrap BOTH the engine-level exit hooks AND DOM window.close.
    // RMMV: SceneManager.terminate → window.close()
    // RMMZ: SceneManager.terminate → nw.App.quit() when Utils.isNwjs(), else window.close()
    // Chromium blocks window.close() on main windows → no-op without override.
    // the nw.js proxy also catches nw.App.quit / Window.close / etc., so this is additive.
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

    // --- (g) AudioManager.audioFileExt → '.ogg' ---
    // MV default on Utils.isMobileDevice()=true returns '.m4a'. Titles that only ship .rpgmvo
    // (Termina class) end up fetching .rpgmvm → 404 → tight-loop retries → silent game + fan spin.
    // hard-set to .ogg -- our host decrypt + Chromium OGG decoder handle the rest.
    patchWhenReady(function () {
        if (!window.AudioManager) return false;
        AudioManager.audioFileExt = function () { return '.ogg'; };
        return true;
    }, 100);

    // --- (h) ErrorPrinter pointer-events: none ---
    // Graphics._createErrorPrinter installs <p id="ErrorPrinter"> at ~50% screen with z-index:99
    // for displaying engine errors. Even when empty, it sits on top of the canvas and swallows
    // touchstart/mousedown via elementFromPoint, so taps in the centered band never reach the
    // game canvas. CSS makes it transparent to pointer events without changing what the engine
    // does on a real error (text still displays, just doesn't block input).
    try {
        var s = document.createElement('style');
        s.textContent = '#ErrorPrinter { pointer-events: none !important; }';
        (document.head || document.documentElement).appendChild(s);
    } catch (e) {}

    // --- (i) zero-strength BlurFilter bypass (OMORI banding) ---
    // OMORI keeps a PIXI BlurFilter on Spriteset_Map with strength 0 (no visible blur) but
    // quality 4 -- the scene still ping-pongs through 8 RGBA8 render-target passes. each pass
    // multiplies by the gaussian kernel sum (~1.0) and requantizes to 8 bits; measured
    // on-device (odin 3): a verified-flat input gains a uniform +1 LSB per pass (result sits
    // on a rounding tie), and from the second pass on the tie breaks differently per row
    // (per-row interpolation jitter), splitting rows ±1. eight passes random-walk rows
    // 5-8 LSB apart = horizontal screen-locked banding. tie-break behavior is GPU/driver
    // specific -- thor/WV109 and desktop stay clean on identical code. an identity filter
    // pass through the same RT path measured bitwise-clean, so when the blur is a no-op we
    // substitute that; real blurs (strength > 0) are untouched.
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

    if (self.__gnShimVerbose) try { console.log('gamenative pack:rmmv shim loaded'); } catch (e) {}
})();
