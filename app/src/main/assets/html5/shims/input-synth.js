// HTML5 input synthesis shim.
// drains __gnInputBridge queue per requestAnimationFrame tick; dispatches DOM events
// at synthetic cursor coords. complements __gnGamepadBridge (existing) -- both run in parallel.

(function () {
    'use strict';

    var BRIDGE_NAME = '__gnInputBridge';
    var cursorX = window.innerWidth / 2;
    var cursorY = window.innerHeight / 2;

    function tick() {
        var bridge = window[BRIDGE_NAME];
        if (bridge && typeof bridge.drainQueue === 'function') {
            var raw;
            try { raw = bridge.drainQueue(); } catch (e) { raw = null; }
            if (raw && raw !== '[]') {
                var specs;
                try { specs = JSON.parse(raw); } catch (e) {}
                if (specs && specs.length) {
                    for (var i = 0; i < specs.length; i++) {
                        try { dispatchSpec(specs[i]); } catch (e) {}
                    }
                }
            }
        }
        requestAnimationFrame(tick);
    }
    requestAnimationFrame(tick);

    function dispatchSpec(spec) {
        if (!spec || !spec.type) return;
        if (typeof spec.x === 'number') cursorX = spec.x;
        if (typeof spec.y === 'number') cursorY = spec.y;
        switch (spec.type) {
            case 'keydown':
            case 'keyup':
                dispatchKeyEvent(spec); break;
            case 'mousedown':
            case 'mouseup':
            case 'click':
                dispatchMouseEvent(spec); break;
            case 'cursormove':
                // cursor coords already updated above -- no DOM event, just track
                break;
        }
    }

    function dispatchKeyEvent(spec) {
        // dispatch on focused element so games' keyboard handlers (which gate on activeElement
        // for UI-vs-game routing -- wayward, c3 .selectable framework etc.) see the event.
        // elementFromPoint(cursorX, cursorY) sounded right for "where the cursor is" but cursor
        // is at viewport-center default; that hits HUD overlays the game has rendered there
        // (.selectable.button, action-slots) and triggers UI nav instead of game movement.
        var target = document.activeElement || document.body || document.documentElement;
        var init = {
            bubbles: true, cancelable: true, composed: true,
            key: spec.key || '',
            code: spec.code || '',
            keyCode: spec.keyCode || 0,
            which: spec.keyCode || 0,
            charCode: spec.charCode || 0,
            shiftKey: !!spec.shiftKey,
            ctrlKey: !!spec.ctrlKey,
            altKey: !!spec.altKey,
            metaKey: false,
            repeat: false,
            location: 0,
            view: window
        };
        var ev;
        try {
            ev = new KeyboardEvent(spec.type, init);
        } catch (e) {
            // legacy fallback for old WebView builds
            try {
                ev = document.createEvent('KeyboardEvent');
                ev.initKeyboardEvent(spec.type, true, true, window, spec.key, 0, false, false, false, false);
            } catch (e2) {
                return;
            }
        }
        // belt-and-suspenders for old engines: keyCode)
        if (ev.keyCode === 0 && spec.keyCode && spec.keyCode !== 0) {
            try {
                Object.defineProperty(ev, 'keyCode', { get: function () { return spec.keyCode; } });
                Object.defineProperty(ev, 'which',   { get: function () { return spec.keyCode; } });
            } catch (_e) {}
        }
        try { target.dispatchEvent(ev); } catch (_e) {}
        // also dispatch on document for handlers attached at document level (RMMV pattern)
        try { document.dispatchEvent(ev); } catch (_e) {}
    }

    function dispatchMouseEvent(spec) {
        var target = document.elementFromPoint(cursorX, cursorY)
                     || document.body
                     || document.documentElement;
        var btn = spec.button || 0;
        var init = {
            bubbles: true, cancelable: true, view: window,
            clientX: cursorX, clientY: cursorY,
            screenX: cursorX, screenY: cursorY,
            button: btn,
            buttons: (spec.type === 'mousedown') ? (1 << btn) : 0
        };
        var ev;
        try { ev = new MouseEvent(spec.type, init); } catch (e) { return; }
        try { target.dispatchEvent(ev); } catch (_e) {}
    }

    // input-synth has no DOM listeners to remove (rAF is self-driven).
    // hot-swap is handled at the touch-shim layer; this shim stays loaded for the session.
})();
