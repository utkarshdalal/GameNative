// navigator.getGamepads() reads state synchronously from the host bridge. a 10Hz loop, not rAF (a bridge call +
// JSON.parse every frame is wasted at idle), fires gamepadconnected for games that listen instead of poll.
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

    // like chromium's GamepadButton, the descriptors live on the prototype. some games copy
    // Object.getOwnPropertyDescriptor(Object.getPrototypeOf(button), "pressed") onto the button; on a plain object
    // that descriptor is undefined, defineProperty throws inside the render ticker, and the game freezes.
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

    // mask the native gamepad entirely, NO fallback: the bridge is the only source. a leaked physical pad breaks
    // strict consumers, e.g. Unity's InputSystem builds a HID layout from the real device id and throws
    // "exceeds 511-bit" on some pads.
    navigator.getGamepads = function () {
        var data = readFromBridge();
        if (!data) return [null, null, null, null];
        cachedGamepad = buildGamepadObj(data);
        return [cachedGamepad, null, null, null];
    };

    // likewise drop native connect/disconnect events; only ours (CONNECTED_ID) reach the game.
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
            // connect ONCE on first real input and stay connected. never disconnect on idle: the bridge reports
            // activity, not presence, and connect/disconnect churn makes strict consumers (Unity InputSystem)
            // re-add the device and latch the stick direction from the connect moment (stuck input).
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
