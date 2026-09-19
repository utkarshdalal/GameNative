// chromium echoes every gamepad button press as a DOM KeyboardEvent (START -> Enter, SELECT -> Space, ...).
// engines reading both the Gamepad API and keydowns double-count input, or flip to keyboard mode and show kbd
// prompts (e.g. Impact's ig.input.currentDevice). swallow keys that arrive while a pad button is held.
// packs whose games read ONLY keydowns opt out via EngineProfile.suppressGamepadKbdEcho = false.
(function () {
    'use strict';
    var swallowed = new Set();

    function anyGamepadButtonPressed() {
        var pads = (typeof navigator !== 'undefined' && navigator.getGamepads) ?
            navigator.getGamepads() : null;
        if (!pads) return false;
        for (var i = 0; i < pads.length; i++) {
            var p = pads[i];
            if (!p || !p.buttons) continue;
            for (var b = 0; b < p.buttons.length; b++) {
                var btn = p.buttons[b];
                if (btn && btn.pressed) return true;
            }
        }
        return false;
    }

    function onKeyDown(e) {
        if (anyGamepadButtonPressed()) {
            swallowed.add(e.keyCode);
            try { e.stopImmediatePropagation(); } catch (_) {}
            try { e.preventDefault(); } catch (_) {}
        }
    }

    // by keyup the pad button is already released, so match on the keyCodes swallowed at keydown.
    function onKeyUp(e) {
        if (swallowed.has(e.keyCode)) {
            swallowed.delete(e.keyCode);
            try { e.stopImmediatePropagation(); } catch (_) {}
            try { e.preventDefault(); } catch (_) {}
        }
    }

    // window capture phase runs before any engine listener.
    try {
        window.addEventListener('keydown', onKeyDown, true);
        window.addEventListener('keyup', onKeyUp, true);
    } catch (_e) { /* shim MUST NOT crash the page */ }

    if (self.__gnShimVerbose) try {
        console.log('gamenative gamepad-kbd-suppress shim loaded');
    } catch (e) {}
})();
