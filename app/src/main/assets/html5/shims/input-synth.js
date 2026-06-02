// drains the host's synthetic key/mouse queue once per frame and dispatches DOM events at a virtual cursor.
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
                break;
        }
    }

    function dispatchKeyEvent(spec) {
        // target the focused element, not elementFromPoint: games route keys UI-vs-game by activeElement, and the
        // cursor (viewport-center by default) often sits over HUD elements, turning movement keys into UI nav.
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
        // KeyboardEvent init ignores keyCode/which, but older engines still read them.
        if (ev.keyCode === 0 && spec.keyCode && spec.keyCode !== 0) {
            try {
                Object.defineProperty(ev, 'keyCode', { get: function () { return spec.keyCode; } });
                Object.defineProperty(ev, 'which',   { get: function () { return spec.keyCode; } });
            } catch (_e) {}
        }
        try { target.dispatchEvent(ev); } catch (_e) {}
        // redispatch on document for engines (e.g. RMMV) that listen there.
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
})();
