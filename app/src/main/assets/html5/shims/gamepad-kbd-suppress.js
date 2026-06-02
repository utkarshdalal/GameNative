// gamenative gamepad-kbd-suppress shim
//
// suppresses DOM keyboard events fired by WebView's native KeyEvent dispatch when a
// gamepad button is currently pressed. chromium auto-maps SOURCE_GAMEPAD KeyEvents to
// DOM KeyboardEvent (e.g. KEYCODE_BUTTON_START → keyCode 13/Enter, KEYCODE_BUTTON_SELECT
// → keyCode 32/Space, KEYCODE_BUTTON_A → keyCode 0/Unidentified) for every physical
// gamepad press. engines that read both navigator.getGamepads() AND DOM keydowns end up
// double-counting input or worse -- Impact-engine titles flip ig.input.currentDevice to
// KEYBOARD_AND_MOUSE on each phantom keyup → tutorial UI shows kbd icons even when the
// player is using a gamepad.
//
// default-on for all html5 containers. packs that genuinely want chromium's gamepad→kbd
// auto-dispatch can opt out via EngineProfile.suppressGamepadKbdEcho = false (e.g. titles
// that read DOM keydowns INSTEAD of polling the Gamepad API).
//
// scoped behavior:
// - only swallows when a real gamepad button is pressed at keydown time. solo keyboard
//   users (no gamepad activity) are unaffected.
// - tracks swallowed keyCodes so the matching keyup is also swallowed (gamepad button
//   is already released by then; can't re-poll for it).
// - capture-phase + stopImmediatePropagation kills both other listeners and any engine's
//   bubble-phase handler.

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

    function onKeyUp(e) {
        if (swallowed.has(e.keyCode)) {
            swallowed.delete(e.keyCode);
            try { e.stopImmediatePropagation(); } catch (_) {}
            try { e.preventDefault(); } catch (_) {}
        }
    }

    // capture phase + window-level so we beat all bubble-phase document/element listeners
    // any engine installs.
    try {
        window.addEventListener('keydown', onKeyDown, true);
        window.addEventListener('keyup', onKeyUp, true);
    } catch (_e) { /* swallow — shim MUST NOT crash the host */ }

    if (self.__gnShimVerbose) try {
        console.log('gamenative gamepad-kbd-suppress shim loaded');
    } catch (e) {}
})();
