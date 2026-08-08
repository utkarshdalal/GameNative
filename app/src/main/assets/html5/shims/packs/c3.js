// gamenative pack:c3 shim -- Option E v2 (corrected instance capture).
// Two responsibilities:
// (1) Drop native touch-pointer events at DOMHandler._PostToRuntimeMaybeSync so
// c3's runtime queue never sees them.
// (2) Expose window.__gnC3InjectMousePointer(type, x, y, buttons) that POJO-
// injects mouse-pointer events via orig.call on the POINTER DOMHandler
// instance -- so touch.js's 8-branch gesture classifier can drive c3's
// runtime with the same vocabulary as every other html5 pack.

// CRITICAL: domHandlerInstance MUST be captured from a POINTER event, not the
// first event of any kind. Each DOMHandler subclass (video, pointer, dom-element,
// etc.) handles a specific component -- capturing the video subclass instance and
// then calling orig.call on it for pointer events routes to the wrong component
// and c3 silently rejects. Verified empirically (Option E v1 hit this).

// CRITICAL: must reuse opts captured from a real native pointer event on the
// SAME instance. opts likely carries per-instance routing data.

// CRITICAL: must maintain _mousePointerLastButtons on the DOMHandler instance
// for c3's bit-diff click detection.

// DO NOT switch to dispatchEvent(new PointerEvent) -- see feedback_c3_pojo_injection.md.
(function () {
    'use strict';

    // Steam4C2 / Steam4C3 native binding stub.
    // c2-on-NW.js Steam wrappers (Steam4C2.js) do:
    //     Steam4C2 = require('./Steam4C2-linux64')   // or -win64/-osx64 etc.
    // those are arch-suffixed native node addons we can't provide. without a stub,
    // Steam4C2 is undefined and the chain `Steam4C2.__proto__ = EventEmitter.prototype`
    // throws on first script execution. register a proxy noop for the family -- direct
    // property writes survive (so `EventEmitter.call(Steam4C2)` initializes `_events`,
    // and `Steam4C2._steam_events.on = ...` lands), prototype-chain reads pass through
    // (so `Steam4C2.emit(...)` resolves to EventEmitter.prototype.emit after the proto
    // assignment), and unknown methods (requestStats, activateGameOverlay, etc.) return
    // a no-op so downstream calls don't TypeError.
    if (window.require && typeof window.require.register === 'function' &&
        typeof window.require.register.pattern === 'function') {
        // matches both the platform-arch native binding (Steam4C2-win64, etc.) AND the
        // wrapper module (./Steam4C2). c2 wrappers typically ship a thin Steam4C2.js that
        // internally does `require('./Steam4C2-' + platform + arch)` and re-exports -- c2
        // plugin code calls `require('./Steam4C2')` (no suffix) to get the wrapper. our
        // stub is the same Proxy either way (wrapper-internal init like __proto__ +
        // EventEmitter is satisfied by the Proxy's permissive get/set), so returning the
        // noop for BOTH forms skips needing to load the wrapper file.
        var steamSdkPattern = /(^|\/)Steam4C[23](-(linux|win|osx)(32|64))?$/;
        function makeSteamSdkStub() {
            var seed = { _steam_events: {} };
            var noop = function () {};
            return new Proxy(seed, {
                get: function (target, prop, receiver) {
                    var v = Reflect.get(target, prop, receiver);
                    if (v !== undefined) return v;
                    // walk prototype set by `Steam4C2.__proto__ = EventEmitter.prototype`
                    var proto = Object.getPrototypeOf(target);
                    if (proto) {
                        var pv = Reflect.get(proto, prop, receiver);
                        if (pv !== undefined) return pv;
                    }
                    return noop;
                },
                set: function (target, prop, value) {
                    target[prop] = value;
                    return true;
                },
                setPrototypeOf: function (target, proto) {
                    return Reflect.setPrototypeOf(target, proto);
                },
            });
        }
        window.require.register.pattern(steamSdkPattern, makeSteamSdkStub());
    }

    var origRef = null;
    var pointerInstance = null;
    var capturedOpts = null;
    var pendingInjections = [];

    function makePayload(x, y, buttons) {
        var lb = (pointerInstance && pointerInstance._mousePointerLastButtons) || 0;
        var p = {
            pointerId: 1,
            pointerType: 'mouse',
            button: 0,
            buttons: buttons,
            lastButtons: lb,
            clientX: x, clientY: y,
            pageX: x, pageY: y,
            movementX: 0, movementY: 0,
            width: 0, height: 0,
            pressure: buttons ? 0.5 : 0,
            tangentialPressure: 0,
            tiltX: 0, tiltY: 0, twist: 0,
            timeStamp: performance.now(),
        };
        if (pointerInstance) pointerInstance._mousePointerLastButtons = buttons;
        return p;
    }

    function flushPending() {
        if (!pointerInstance || !origRef || !capturedOpts) return;
        while (pendingInjections.length > 0) {
            var inj = pendingInjections.shift();
            try { origRef.call(pointerInstance, inj.type, inj.payload, capturedOpts); } catch (_e) {}
        }
    }

    window.__gnC3InjectMousePointer = function (type, x, y, buttons) {
        if (type !== 'pointermove' && type !== 'pointerdown' &&
            type !== 'pointerup' && type !== 'pointercancel') return;
        var payload = makePayload(x, y, buttons | 0);
        if (pointerInstance && origRef && capturedOpts) {
            try { origRef.call(pointerInstance, type, payload, capturedOpts); } catch (_e) {}
        } else {
            pendingInjections.push({ type: type, payload: payload });
        }
    };

    function hookWhenReady(retries) {
        if (!self.DOMHandler || !self.DOMHandler.prototype || !self.DOMHandler.prototype._PostToRuntimeMaybeSync) {
            if (retries > 0) setTimeout(function () { hookWhenReady(retries - 1); }, 50);
            return;
        }
        origRef = self.DOMHandler.prototype._PostToRuntimeMaybeSync;
        self.DOMHandler.prototype._PostToRuntimeMaybeSync = function (type, payload, opts) {
            var isPointer = payload &&
                (type === 'pointerdown' || type === 'pointermove' ||
                 type === 'pointerup' || type === 'pointercancel');
            // capture instance + opts from the first POINTER event -- not the first
            // event of any kind. each DOMHandler subclass handles ONE component;
            // we need the pointer subclass for our injections to route correctly.
            if (isPointer && !pointerInstance) {
                pointerInstance = this;
                capturedOpts = opts;
                flushPending();
            }
            // drop native touch-pointer at runtime ingress -- c3 only sees mouse pointers
            // injected by touch.js via __gnC3InjectMousePointer above.
            if (isPointer && payload.pointerType === 'touch') {
                return;
            }
            return origRef.apply(this, arguments);
        };
        if (self.__gnShimVerbose) try { console.log('gamenative pack:c3 — runtime hook installed (Option E v2)'); } catch (_e) {}
    }
    hookWhenReady(100); // 5-second budget (100 × 50ms)

    // pack:c3 ships both c2runtime + c3runtime games. with useWideViewPort=true (set in
    // WebViewScreen for pack:c3) the layout viewport jumps from ~833 CSS to 980 CSS, which
    // c2/c3 size their canvas against → can overflow the visible viewport on a non-integer-
    // dpr device.
    //
    // cap canvas dims to visual viewport via CSS max-width/max-height with !important. beats
    // c2/c3 inline style writes (which are without !important). max-* preserves aspect ratio
    // for the typical 16:9 game on a near-16:9 viewport -- both caps trigger proportionally.
    // selector matches `canvas` at any depth -- c3runtime nests its canvas one or more divs
    // deeper than c2's body > #c2canvasdiv > canvas, and missing the cap on first paint
    // produces a brief scrollbar flash before the engine's own resize handler catches up.
    function injectCanvasFitCss() {
        try {
            var vw = (window.visualViewport && window.visualViewport.width) || window.outerWidth || window.innerWidth;
            var vh = (window.visualViewport && window.visualViewport.height) || window.outerHeight || window.innerHeight;
            var css =
                'canvas { ' +
                    'max-width: ' + vw + 'px !important; ' +
                    'max-height: ' + vh + 'px !important; ' +
                '}';
            var styleEl = document.getElementById('__gnC3CanvasFit');
            if (!styleEl) {
                styleEl = document.createElement('style');
                styleEl.id = '__gnC3CanvasFit';
                (document.head || document.documentElement).appendChild(styleEl);
            }
            styleEl.textContent = css;
            if (self.__gnShimVerbose) try { console.log('gn-c3-fit: applied max-w/h ' + vw + 'x' + vh); } catch (_e) {}
        } catch (_e) {}
    }
    injectCanvasFitCss();
    if (window.visualViewport) {
        try { window.visualViewport.addEventListener('resize', injectCanvasFitCss); } catch (_e) {}
    }

    if (self.__gnShimVerbose) try { console.log('gamenative pack:c3 shim loaded'); } catch (e) {}
})();
