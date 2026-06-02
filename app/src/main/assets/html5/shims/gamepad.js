// sync-read gamepad bridge shim. patches navigator.getGamepads() to pull state
// synchronously from the kotlin bridge (__gnGamepadBridge.readState()). a setInterval loop
// (10Hz -- connect/disconnect events are user-perceptible, not frame-perceptible) fires
// gamepadconnected / gamepaddisconnected events so games that listen (not poll) still light
// up. zero dependencies; safe to inject before any game script.

// perf: was rAF (60Hz) -- burned a bridge call + JSON.parse every frame even at
// idle. dropped to setInterval(100ms). disconnect debounce changed from frame count to a
// small tick count to match.
(function () {
    'use strict';
    var BRIDGE_NAME = '__gnGamepadBridge';
    var CONNECTED_ID = 'GameNative Controller (Standard Gamepad Vendor: 0000 Product: 0000)';
    var TICK_INTERVAL_MS = 100;

    var cachedGamepad = null;
    var connected = false;

    function readFromBridge() {
        var bridge = window[BRIDGE_NAME];
        if (!bridge || typeof bridge.readState !== 'function') return null;
        try {
            var raw = bridge.readState();
            if (!raw) return null;
            var arr = JSON.parse(raw);
            return (arr && arr.length > 0) ? arr[0] : null;
        } catch (e) {
            return null;
        }
    }

    // mimic Chromium's GamepadButton interface: property descriptors live on the prototype,
    // instance carries backing fields. OMORI options menu (and likely YEP_CoreEngine paths)
    // does Object.defineProperty(button, "pressed", Object.getOwnPropertyDescriptor(
    // Object.getPrototypeOf(button), "pressed")) during fullscreen toggle. plain-object
    // buttons inherit from Object.prototype which has no "pressed" descriptor → undefined →
    // defineProperty throws → uncaught in PIXI ticker → game freeze. routing through a
    // backing-field prototype getter keeps consumers like `button.pressed` returning the
    // boolean and survives the prototype-walk trick without behavior change.
    var GAMEPAD_BUTTON_PROTO = (function () {
        var p = {};
        function getter(field, dflt) {
            return function () {
                var v = this['_' + field];
                return v !== undefined ? v : dflt;
            };
        }
        Object.defineProperty(p, 'pressed', { get: getter('pressed', false), configurable: true, enumerable: true });
        Object.defineProperty(p, 'touched', { get: getter('touched', false), configurable: true, enumerable: true });
        Object.defineProperty(p, 'value',   { get: getter('value',   0),     configurable: true, enumerable: true });
        return p;
    })();

    function buildButton(b) {
        var btn = Object.create(GAMEPAD_BUTTON_PROTO);
        btn._pressed = !!b.pressed;
        btn._touched = !!b.touched;
        btn._value = +b.value;
        return btn;
    }

    function buildGamepadObj(data) {
        // data is already shaped as a W3C-like entry from the kotlin side (Html5GamepadBridge
        // ). we just wrap it so games can read .buttons / .axes directly.
        return {
            id: CONNECTED_ID,
            index: 0,
            mapping: 'standard',
            connected: true,
            timestamp: (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now(),
            buttons: data.buttons.map(buildButton),
            axes: data.axes.map(function (a) { return +a; }),
        };
    }

    // mask the native gamepad entirely -- the kotlin bridge IS the only gamepad source for html5.
    // leaking the physical pad (with Vendor/Product IDs) breaks strict consumers: Unity's InputSystem
    // builds a HID layout from the real device id and throws "exceeds 511-bit" on some pads (Odin).
    // expose ONLY our synthetic standard-mapping pad -- NO native fallback.
    navigator.getGamepads = function () {
        var data = readFromBridge();
        if (!data) return [null, null, null, null];
        cachedGamepad = buildGamepadObj(data);
        // forward-compat: kotlin side currently returns length-1 array but shim always
        // exposes a 4-slot tuple for games that iterate.
        return [cachedGamepad, null, null, null];
    };

    // suppress NATIVE gamepadconnected/disconnected (the real physical device) -- only our synthetic
    // events (gamepad.id === CONNECTED_ID) reach the game. capture phase so we intercept first; our own
    // dispatched events carry CONNECTED_ID and pass through. without this, Unity's InputSystem still
    // sees the physical Odin pad via the connect event and throws building its HID layout.
    ['gamepadconnected', 'gamepaddisconnected'].forEach(function (type) {
        window.addEventListener(type, function (e) {
            if (!e.gamepad || e.gamepad.id !== CONNECTED_ID) {
                e.stopImmediatePropagation();
            }
        }, true);
    });

    function hasInput(data) {
        return (data.connected === true) ||
            (data.buttons && data.buttons.some(function (b) { return b.pressed; })) ||
            (data.axes && data.axes.some(function (a) { return Math.abs(+a) > 0.01; }));
    }

    function tick() {
        var data = readFromBridge();
        if (data && hasInput(data)) {
            cachedGamepad = buildGamepadObj(data);
            // connect ONCE on first real input (proves a controller exists), then STAY connected for
            // the session. we deliberately do NOT fire gamepaddisconnected on idle: the bridge reports
            // ACTIVITY, not PRESENCE, so the old "disconnect after 500ms quiet" churned connect/reconnect,
            // and strict consumers (Unity InputSystem) re-added the device every cycle and latched the
            // connect-moment stick direction → stuck input. getGamepads() returns live state every frame,
            // so a held-then-released stick reads centered without any connect/disconnect churn.
            if (!connected) {
                connected = true;
                try {
                    var evc = new Event('gamepadconnected');
                    evc.gamepad = cachedGamepad;
                    window.dispatchEvent(evc);
                } catch (e) { /* some UAs disallow mutating Event; ignore */ }
            }
        }
    }
    setInterval(tick, TICK_INTERVAL_MS);
})();
