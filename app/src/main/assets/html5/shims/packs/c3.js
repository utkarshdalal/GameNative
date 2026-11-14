// gamenative pack:c3 shim.
// (1) drop native touch-pointer events at DOMHandler._PostToRuntimeMaybeSync so c3's runtime never sees them.
// (2) expose window.__gnC3InjectMousePointer(type, x, y, buttons), which POJO-injects mouse-pointer events
// via orig.call on the POINTER DOMHandler instance, so touch.js gestures drive c3 like every other pack.
//
// CRITICAL: the instance MUST be captured from a POINTER event. each DOMHandler subclass (video, pointer,
// dom-element, ...) handles one component; calling orig.call on the wrong one is silently rejected.
// CRITICAL: reuse opts captured from a real native pointer event on the SAME instance (per-instance routing).
// CRITICAL: maintain _mousePointerLastButtons on the instance -- c3 detects clicks by bit-diff against it.
// DO NOT switch to dispatchEvent(new PointerEvent) -- only POJO injection via orig.call is known to work.
(function () {
    'use strict';

    // Steam4C2 / Steam4C3 wrappers require arch-suffixed native node addons (Steam4C2-win64 etc.) we can't
    // provide; without a stub `Steam4C2.__proto__ = EventEmitter.prototype` throws on first execution.
    // the proxy keeps direct property writes (EventEmitter.call initializes `_events`), passes prototype
    // reads through (emit resolves to EventEmitter's), and returns a no-op for unknown methods.
    if (window.require && typeof window.require.register === 'function' &&
        typeof window.require.register.pattern === 'function') {
        // matches both the native binding (Steam4C2-win64) AND the thin wrapper (./Steam4C2) that
        // re-exports it: the permissive proxy satisfies the wrapper's own init, so it never needs loading.
        var steamSdkPattern = /(^|\/)Steam4C[23](-(linux|win|osx)(32|64))?$/;
        function makeSteamSdkStub() {
            var seed = { _steam_events: {} };
            var noop = function () {};
            return new Proxy(seed, {
                get: function (target, prop, receiver) {
                    var v = Reflect.get(target, prop, receiver);
                    if (v !== undefined) return v;
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

    // injections before the hook is ready are queued for replay. the wait can be endless, so the queue
    // is bounded and released when the hook budget runs out:
    //   1. pack:c3 also covers c2runtime titles, which have no DOMHandler AT ALL.
    //   2. pointerInstance is only captured from a NATIVE pointer event; our own injections bypass that
    //      capture, so a touch-only session may never capture one.
    var pendingInjections = [];
    var hookGaveUp = false;
    var pendingOverflowed = false;
    // keeps the FIRST events: a coherent down/move/up prefix beats a ragged suffix (an up with no down).
    var MAX_PENDING_INJECTIONS = 256;

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
        if (hookGaveUp) return;
        var payload = makePayload(x, y, buttons | 0);
        if (pointerInstance && origRef && capturedOpts) {
            try { origRef.call(pointerInstance, type, payload, capturedOpts); } catch (_e) {}
        } else if (pendingInjections.length < MAX_PENDING_INJECTIONS) {
            pendingInjections.push({ type: type, payload: payload });
        } else if (!pendingOverflowed) {
            pendingOverflowed = true;
            try { console.warn('gamenative pack:c3: pointer injection queue full (' + MAX_PENDING_INJECTIONS + ') — runtime hook never became ready; dropping further injections'); } catch (_e) {}
        }
    };

    function hookWhenReady(retries) {
        if (!self.DOMHandler || !self.DOMHandler.prototype || !self.DOMHandler.prototype._PostToRuntimeMaybeSync) {
            if (retries > 0) {
                setTimeout(function () { hookWhenReady(retries - 1); }, 50);
                return;
            }
            // no DOMHandler after the budget = c2runtime title (expected).
            hookGaveUp = true;
            pendingInjections.length = 0;
            if (self.__gnShimVerbose) {
                try { console.log('gamenative pack:c3 — no DOMHandler after budget (c2runtime title); pointer-injection queue released'); } catch (_e) {}
            }
            return;
        }
        origRef = self.DOMHandler.prototype._PostToRuntimeMaybeSync;
        self.DOMHandler.prototype._PostToRuntimeMaybeSync = function (type, payload, opts) {
            var isPointer = payload &&
                (type === 'pointerdown' || type === 'pointermove' ||
                 type === 'pointerup' || type === 'pointercancel');
            // POINTER event only -- see CRITICAL notes at top.
            if (isPointer && !pointerInstance) {
                pointerInstance = this;
                capturedOpts = opts;
                flushPending();
            }
            // c3 only sees the mouse pointers touch.js injects via __gnC3InjectMousePointer.
            if (isPointer && payload.pointerType === 'touch') {
                return;
            }
            return origRef.apply(this, arguments);
        };
        if (self.__gnShimVerbose) try { console.log('gamenative pack:c3 — runtime hook installed (Option E v2)'); } catch (_e) {}
    }
    hookWhenReady(100); // 5s budget (100 x 50ms)

    // pack:c3 runs with useWideViewPort=true, which widens the layout viewport (~833 -> 980 CSS px); c2/c3
    // size their canvas against it, so it can overflow the visible viewport on non-integer-dpr devices.
    // !important beats the engines' inline style writes. selector matches `canvas` at any depth because
    // c3runtime nests it deeper than c2's #c2canvasdiv; missing it on first paint flashes a scrollbar.
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
