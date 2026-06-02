// unified config-driven touch shim. replaces pointer-with-tap / touch-touchpad /
// touch-passthrough / touch-gestures. dispatches all 8 gesture branches (1f-tap, 1f-drag,
// long-press, double-tap, 2f-tap, 2f-drag, pinch, 3f-tap) by reading window.__gnGestureConfig
// FRESH on each event. live-update via WebViewScreen evaluateJavascript writes a new
// __gnGestureConfig object -- next event sees the new config -- no shim re-inject, no
// listener teardown, no game-state loss).
(function () {
    'use strict';

    // idempotent init. window.__gnTouchShimActive guard prevents double-listener
    // registration if asset interceptor + a stray hot-swap race.
    if (window.__gnTouchShimActive === 'touch') return;

    // defensive default. if window.__gnGestureConfig is missing (host failed to
    // inject), every gesture defaults to the data-class shape from TouchGestureConfig.kt.
    // safety net only -- IndexHtmlRewriter emits the parse-time snippet.
    var DEFAULTS = {
        tapEnabled: true, tapAction: 'left_click',
        dragEnabled: true, dragAction: 'left_click_drag',
        longPressEnabled: false, longPressAction: 'right_click', longPressDelay: 300,
        doubleTapEnabled: true, doubleTapDelay: 300,
        twoFingerDragEnabled: true, twoFingerDragAction: 'arrow_keys',
        pinchEnabled: true, pinchAction: 'scroll_wheel',
        twoFingerTapEnabled: true, twoFingerTapAction: 'right_click',
        twoFingerHoldEnabled: false, twoFingerHoldAction: 'middle_click', twoFingerHoldDelay: 300,
        threeFingerTapEnabled: false, threeFingerTapAction: 'open_quick_menu',
        threeFingerDragEnabled: false, threeFingerDragAction: 'arrow_keys',
        threeFingerHoldEnabled: false, threeFingerHoldAction: 'key_ESC', threeFingerHoldDelay: 300,
        showClickHighlight: false, showGestureDebugOverlay: false,
        gestureThreshold: 40,
        cursorMode: 'absolute',
    };
    function getCfg() { return window.__gnGestureConfig || DEFAULTS; }

    // ── tunables (mirror old shim constants) ────────────────────────
    var TAP_RADIUS_PX = 10;             // matches TouchMouse.MAX_TAP_TRAVEL_DISTANCE
    var TAP_MAX_MS = 200;               // matches TouchMouse.MAX_TAP_MILLISECONDS
    var DOUBLE_TAP_DISTANCE_PX = 100;   // mirrors TouchGestureConfig.DOUBLE_TAP_DISTANCE_PX
    var TWO_FINGER_TAP_DURATION_MAX = 250;
    var TWO_FINGER_TAP_RADIUS = 12;
    var SCROLL_THRESHOLD_PX = 4;
    var PINCH_THRESHOLD_PX = 6;         // delta-distance threshold to dispatch pinch step
    var RELATIVE_SENSITIVITY = 1.5;

    // ── editable-target detection (port from pointer-with-tap.js:44-63) ──
    // when the user taps an <input>/<textarea>/[contenteditable], the soft-keyboard path
    // needs the native click to fire; suppress synth + don't preventDefault.
    function isEditableTarget(el) {
        if (!el) return false;
        var cur = el;
        while (cur && cur.nodeType === 1) {
            if (cur.isContentEditable) return true;
            cur = cur.parentNode;
        }
        var tag = el.tagName;
        if (tag === 'TEXTAREA') return true;
        if (tag === 'INPUT') {
            var type = (el.type || 'text').toLowerCase();
            return type === 'text' || type === 'search' || type === 'url' ||
                type === 'email' || type === 'tel' || type === 'password' ||
                type === 'number';
        }
        return false;
    }

    function resolveTarget(x, y) {
        var target = null;
        try { target = document.elementFromPoint(x, y); } catch (e) {}
        return target || document.body || document.documentElement;
    }

    // ── relative-cursor state (port from touch-touchpad.js:16-17, 99-104) ──
    // ONLY consulted when primary.mode === 'relative' (capture-once at gesture start --
    // switching cursorMode mid-gesture must NOT corrupt running state).
    var cursorX = (window.innerWidth | 0) >> 1;
    var cursorY = (window.innerHeight | 0) >> 1;

    // ── per-gesture state ──
    // primary tracks the 1-finger gesture (tap / drag / long-press / double-tap classifier).
    // mode is captured ONCE here and reused through this gesture's lifetime.
    var primary = null;
    var lastTapTime = 0, lastTapX = 0, lastTapY = 0;
    var currentHover = null;            // hover-sync target (absolute mode only)

    // fingers tracks ALL active touches for multi-finger gesture state.
    // each entry: { id, x0, y0, x, y, t0 }.
    var fingers = [];

    // 2-finger gesture state -- captured at touchstart of the SECOND finger.
    var twoFingerState = null;          // { startDist, lastDist, lastMidY, action }
    // pinch detection -- accumulated wheel delta resets each step.
    var pinchAccum = 0;
    // high-water mark of finger count during a gesture sequence (touch-down to all-up).
    // drives multi-finger classification so a 3-finger tap is still classified as 3 even
    // when fingers lift one at a time. resets to 0 when fingers.length returns to 0.
    var maxFingersThisSequence = 0;
    // set true once a multi-finger tap has been classified (or explicitly skipped) for
    // the current sequence -- prevents subsequent finger-lift events from re-firing a
    // lower-finger tap (the cascade bug).
    var multiFingerHandled = false;

    // ── c3 runtime POJO injection (Option E v2 -- see packs/c3.js header) ──
    function injectC3Move(x, y) {
        var fn = window.__gnC3InjectMousePointer;
        if (fn) try { fn('pointermove', x, y, 0); } catch (e) {}
    }
    function injectC3Click(x, y, button) {
        var fn = window.__gnC3InjectMousePointer;
        if (!fn) return;
        var btn = button | 0;
        try { fn('pointerdown', x, y, 1 << btn); } catch (e) {}
        // 2-rAF gap so c3's runtime processes pointerdown in one tick before pointerup --
        // mirrors OLD c3.js's tick separation precedent.
        requestAnimationFrame(function () {
            requestAnimationFrame(function () {
                var fn2 = window.__gnC3InjectMousePointer;
                if (fn2) try { fn2('pointerup', x, y, 0); } catch (e) {}
            });
        });
    }

    // ── input-bridge keyboard dispatch (port touch-gestures.js:46-54) ──
    function dispatchKey(keyCode, key, code) {
        var b = window.__gnInputBridge;
        if (!b || typeof b.enqueue !== 'function') return;
        try {
            b.enqueue('{"type":"keydown","key":"' + key + '","code":"' + code + '","keyCode":' + (keyCode | 0) + ',"charCode":0}');
            b.enqueue('{"type":"keyup","key":"' + key + '","code":"' + code + '","keyCode":' + (keyCode | 0) + ',"charCode":0}');
        } catch (e) {}
    }

    function fireMouse(type, x, y, button) {
        var target = resolveTarget(x, y);
        var init = {
            bubbles: true,
            cancelable: true,
            view: window,
            clientX: x, clientY: y,
            screenX: x, screenY: y,
            button: button | 0,
            buttons: (type === 'mousedown') ? (1 << (button | 0)) : 0,
        };
        var ev;
        try {
            ev = new MouseEvent(type, init);
        } catch (e) {
            // legacy fallback for engines that reject MouseEvent ctor.
            ev = document.createEvent('MouseEvent');
            ev.initMouseEvent(type, true, true, window, 0, x, y, x, y, false, false, false, false, button | 0, null);
        }
        try { target.dispatchEvent(ev); } catch (e) {}
        return target;
    }

    // ── hover sync (port pointer-with-tap.js:65-104). absolute-mode only --
    // games like Curious Expedition gate click handlers on preceding mouseover/mouseenter.
    function dispatchHoverEvent(type, target, x, y) {
        if (!target) return;
        var bubbles = (type === 'mouseover' || type === 'mouseout');
        try {
            target.dispatchEvent(new MouseEvent(type, {
                bubbles: bubbles, cancelable: true, view: window,
                clientX: x, clientY: y, screenX: x, screenY: y,
                button: 0, buttons: 0,
            }));
        } catch (e) {}
    }
    function syncHover(target, x, y) {
        if (currentHover === target) return;
        if (currentHover) {
            dispatchHoverEvent('mouseout', currentHover, x, y);
            dispatchHoverEvent('mouseleave', currentHover, x, y);
        }
        if (target) {
            dispatchHoverEvent('mouseover', target, x, y);
            dispatchHoverEvent('mouseenter', target, x, y);
        }
        currentHover = target;
    }
    function clearHover(x, y) {
        if (!currentHover) return;
        dispatchHoverEvent('mouseout', currentHover, x, y);
        dispatchHoverEvent('mouseleave', currentHover, x, y);
        currentHover = null;
    }

    // 3-finger-tap action dispatcher. master's TapHoldActionPicker emits open_quick_menu
    // (html5-only), the legacy mouse trio, and key_<X> for any keyboard-key action.
    // show_keyboard arrives only from old wine-side configs; treat it as open_quick_menu
    // (closest html5 analog) so legacy data doesn't silently no-op.
    function dispatch3fAction(action, x, y) {
        if (action === 'show_keyboard') action = 'open_quick_menu';
        switch (action) {
            case 'open_quick_menu':
                // Direct bridge call -- window event dispatch doesn't reach the
                // listener installed via WebView.evaluateJavascript on this Android version, so
                // skip the indirection and call __gnInputBridge.openQuickMenu directly. dispatchEvent
                // kept for any in-page listeners that might consume it.
                try { window.dispatchEvent(new Event('gn-open-quickmenu')); } catch (e) {}
                try {
                    var bridge = window.__gnInputBridge || (window.top && window.top.__gnInputBridge);
                    if (bridge && bridge.openQuickMenu) bridge.openQuickMenu();
                } catch (e) {}
                return;
            case 'left_click':
                fireMouse('mousedown', x, y, 0);
                fireMouse('mouseup', x, y, 0);
                injectC3Click(x, y, 0);
                return;
            case 'right_click':
                fireMouse('mousedown', x, y, 2);
                fireMouse('mouseup', x, y, 2);
                fireMouse('contextmenu', x, y, 2);
                injectC3Click(x, y, 2);
                return;
            case 'middle_click':
                fireMouse('mousedown', x, y, 1);
                fireMouse('mouseup', x, y, 1);
                injectC3Click(x, y, 1);
                return;
        }
        if (typeof action === 'string' && action.indexOf('key_') === 0) {
            var keyName = action.substring(4); // e.g. "ESC", "SPACE", "F1", "A"
            var info = keyToCodeAndKey(keyName);
            if (info) dispatchKey(info.code, info.key, info.codeName);
        }
    }

    // map master's key_<X> identifier to a KeyboardEvent.keyCode + key + code triple.
    // covers ACTION_KEY_ESC default + buildActionCategories' Common Game / Letters / Numbers / F-keys.
    function keyToCodeAndKey(keyName) {
        if (!keyName) return null;
        if (keyName === 'ESC') return { code: 27, key: 'Escape', codeName: 'Escape' };
        if (keyName === 'SPACE') return { code: 32, key: ' ', codeName: 'Space' };
        if (keyName === 'TAB') return { code: 9, key: 'Tab', codeName: 'Tab' };
        if (keyName === 'ENTER') return { code: 13, key: 'Enter', codeName: 'Enter' };
        // F1-F12
        if (keyName.length >= 2 && keyName.charAt(0) === 'F') {
            var fnum = parseInt(keyName.substring(1), 10);
            if (fnum >= 1 && fnum <= 12) {
                return { code: 111 + fnum, key: keyName, codeName: keyName };
            }
        }
        // single letter A-Z
        if (keyName.length === 1 && keyName >= 'A' && keyName <= 'Z') {
            return { code: keyName.charCodeAt(0), key: keyName.toLowerCase(), codeName: 'Key' + keyName };
        }
        // single digit 0-9
        if (keyName.length === 1 && keyName >= '0' && keyName <= '9') {
            return { code: keyName.charCodeAt(0), key: keyName, codeName: 'Digit' + keyName };
        }
        return null;
    }

    // ── long-press fire ──
    function onLongPressFire() {
        if (primary === null || primary.editable || primary.consumed) return;
        var cfg = getCfg();
        if (!cfg.longPressEnabled) return;
        var x = primary.mode === 'relative' ? cursorX : primary.lastX;
        var y = primary.mode === 'relative' ? cursorY : primary.lastY;
        var action = cfg.longPressAction || 'right_click';
        var lpBtn = -1;
        switch (action) {
            case 'left_click':
                fireMouse('mousedown', x, y, 0); fireMouse('mouseup', x, y, 0); fireMouse('click', x, y, 0);
                lpBtn = 0;
                break;
            case 'right_click':
                fireMouse('mousedown', x, y, 2); fireMouse('mouseup', x, y, 2); fireMouse('contextmenu', x, y, 2);
                lpBtn = 2;
                break;
            case 'middle_click':
                fireMouse('mousedown', x, y, 1); fireMouse('mouseup', x, y, 1);
                lpBtn = 1;
                break;
        }
        if (lpBtn >= 0) injectC3Click(x, y, lpBtn);
        primary.longPressFired = true;
        primary.consumed = true;        // suppress tap classification on touchend
    }

    // ── 2-finger drag action dispatcher ──
    // dispatches the configured action ONCE per movement step (deltas accumulated outside).
    function dispatch2fDrag(action, dx, dy, x, y) {
        switch (action) {
            case 'wasd':
                if (Math.abs(dy) > Math.abs(dx)) {
                    if (dy < 0) dispatchKey(87, 'w', 'KeyW'); else dispatchKey(83, 's', 'KeyS');
                } else {
                    if (dx < 0) dispatchKey(65, 'a', 'KeyA'); else dispatchKey(68, 'd', 'KeyD');
                }
                break;
            case 'arrow_keys':
                if (Math.abs(dy) > Math.abs(dx)) {
                    if (dy < 0) dispatchKey(38, 'ArrowUp', 'ArrowUp'); else dispatchKey(40, 'ArrowDown', 'ArrowDown');
                } else {
                    if (dx < 0) dispatchKey(37, 'ArrowLeft', 'ArrowLeft'); else dispatchKey(39, 'ArrowRight', 'ArrowRight');
                }
                break;
            case 'middle_mouse_pan':
                // synth middle-mouse drag: down once on first move, mousemove per step,
                // up on touchend (handled in twoFingerEnd).
                if (!twoFingerState.middleDown) {
                    fireMouse('mousedown', x, y, 1);
                    twoFingerState.middleDown = true;
                }
                fireMouse('mousemove', x, y, 1);
                break;
        }
    }

    // ── pinch action dispatcher (one step per PINCH_THRESHOLD_PX delta) ──
    function dispatchPinchStep(action, sign, x, y) {
        switch (action) {
            case 'scroll_wheel':
                try {
                    var target = resolveTarget(x, y);
                    target.dispatchEvent(new WheelEvent('wheel', {
                        bubbles: true, cancelable: true,
                        deltaY: sign * 100, deltaMode: 0,
                        clientX: x, clientY: y,
                    }));
                } catch (e) {}
                break;
            case 'plus_minus':
                if (sign < 0) dispatchKey(187, '+', 'Equal'); else dispatchKey(189, '-', 'Minus');
                break;
            case 'page_up_down':
                if (sign < 0) dispatchKey(33, 'PageUp', 'PageUp'); else dispatchKey(34, 'PageDown', 'PageDown');
                break;
        }
    }

    // ── primary handlers ──
    // wine-parity TOUCHSCREEN_MODE: when window.__gnTouchModeActive === false (set by host
    // via parse-time inject OR live evaluateJavascript), all gesture interpretation is
    // suspended. raw touch events still propagate to the document/canvas so games with
    // native touch handling continue to work. unset / true → existing behavior.
    function isActive() { return window.__gnTouchModeActive !== false; }

    function onTouchStart(e) {
        if (!isActive()) return;
        var cfg = getCfg();
        for (var i = 0; i < e.changedTouches.length; i++) {
            var ct = e.changedTouches[i];
            fingers.push({ id: ct.identifier, x0: ct.clientX, y0: ct.clientY, x: ct.clientX, y: ct.clientY, t0: Date.now() });
        }
        if (fingers.length > maxFingersThisSequence) maxFingersThisSequence = fingers.length;

        // 1-finger gesture start: capture mode + start coords (capture-once).
        if (e.touches.length === 1 && primary === null) {
            var t = e.changedTouches[0];
            var target = resolveTarget(t.clientX, t.clientY);
            primary = {
                id: t.identifier,
                startX: t.clientX, startY: t.clientY,
                lastX: t.clientX, lastY: t.clientY,
                startTime: Date.now(),
                mode: cfg.cursorMode === 'relative' ? 'relative' : 'absolute',
                hadMovement: false,
                editable: isEditableTarget(target),
                longPressTimer: null,
                longPressFired: false,
                consumed: false,
            };
            // direct-touch passthrough: when both 1f tap + drag are off, do NOT preventDefault
            // and do NOT synth -- game receives native touch events. multi-finger gestures
            // still consumed when their flags are true.
            var passthrough1f = !cfg.tapEnabled && !cfg.dragEnabled;
            if (!primary.editable && !passthrough1f) {
                if (primary.mode === 'absolute') {
                    // absolute mode: hover sync FIRST so games gating click on preceding
                    // mouseover (Electron-style) see over/enter → move → down → up → click.
                    syncHover(target, t.clientX, t.clientY);
                    dispatchMouseAt('mousemove', t.clientX, t.clientY);
                    dispatchMouseAt('mousedown', t.clientX, t.clientY);
                    injectC3Move(t.clientX, t.clientY);
                } else {
                    // relative mode: clamp synthetic cursor inside viewport on edge cases.
                    cursorX = Math.max(0, Math.min((window.innerWidth | 0) - 1, cursorX));
                    cursorY = Math.max(0, Math.min((window.innerHeight | 0) - 1, cursorY));
                }
                if (cfg.longPressEnabled) {
                    var lpDelay = (cfg.longPressDelay | 0) || 300;
                    primary.longPressTimer = setTimeout(onLongPressFire, lpDelay);
                }
            }
        }

        // 2-finger gesture start (second finger touches down): capture initial pinch baseline +
        // pick action from cfg. action is captured-once per gesture (
        if (e.touches.length === 2 && twoFingerState === null && fingers.length >= 2) {
            // cancel any pending 1f long-press -- gesture is now multi-finger.
            if (primary && primary.longPressTimer) {
                clearTimeout(primary.longPressTimer);
                primary.longPressTimer = null;
            }
            if (primary) primary.consumed = true; // suppress tap classification
            var f0 = fingers[0], f1 = fingers[1];
            var ddx = f1.x - f0.x, ddy = f1.y - f0.y;
            var startDist = Math.sqrt(ddx * ddx + ddy * ddy);
            twoFingerState = {
                startDist: startDist,
                lastDist: startDist,
                lastMidX: (f0.x + f1.x) / 2,
                lastMidY: (f0.y + f1.y) / 2,
                dragAction: cfg.twoFingerDragAction || 'middle_mouse_pan',
                pinchAction: cfg.pinchAction || 'scroll_wheel',
                middleDown: false,
                hadDragMovement: false,
            };
            pinchAccum = 0;
        }

        // multi-finger gestures are owned by the shim -- block native gesture detection AND
        // any game-side touch listener so a disabled gesture really is disabled.
        if (e.touches.length >= 2) {
            try { e.preventDefault(); } catch (err) {}
            try { e.stopImmediatePropagation(); } catch (err) {}
        }
    }

    // single-finger absolute-mode dispatch helper (used in start/move/end).
    function dispatchMouseAt(type, x, y) {
        var target = resolveTarget(x, y);
        var init = {
            bubbles: true, cancelable: true, view: window,
            clientX: x, clientY: y, screenX: x, screenY: y,
            button: 0,
            buttons: (type === 'mouseup' || type === 'click') ? 0 : 1,
        };
        var ev;
        try { ev = new MouseEvent(type, init); }
        catch (e) {
            ev = document.createEvent('MouseEvent');
            ev.initMouseEvent(type, true, true, window, 0, x, y, x, y, false, false, false, false, 0, null);
        }
        try { target.dispatchEvent(ev); } catch (e) {}
        return target;
    }

    // single-finger relative-mode dispatch helper -- uses synthetic cursorX/Y.
    function dispatchAtCursor(type, button) {
        var target = resolveTarget(cursorX, cursorY);
        try {
            target.dispatchEvent(new MouseEvent(type, {
                bubbles: true, cancelable: true, view: window,
                clientX: cursorX, clientY: cursorY,
                screenX: cursorX, screenY: cursorY,
                button: button | 0,
                buttons: (type === 'mousedown') ? 1 : 0,
            }));
        } catch (e) {}
        return target;
    }

    function findFingerIdx(id) {
        for (var i = 0; i < fingers.length; i++) {
            if (fingers[i].id === id) return i;
        }
        return -1;
    }

    function onTouchMove(e) {
        if (!isActive()) return;
        var cfg = getCfg();
        // update finger positions FIRST so multi-finger calcs read fresh coords.
        for (var i = 0; i < e.changedTouches.length; i++) {
            var ct = e.changedTouches[i];
            var idx = findFingerIdx(ct.identifier);
            if (idx >= 0) {
                fingers[idx].x = ct.clientX;
                fingers[idx].y = ct.clientY;
            }
        }

        // 2+ finger move: dispatch pinch + 2f drag. game does not see synthesized touchmove.
        if (e.touches.length >= 2 && twoFingerState !== null && fingers.length >= 2) {
            var f0 = fingers[0], f1 = fingers[1];
            var dx2 = f1.x - f0.x, dy2 = f1.y - f0.y;
            var curDist = Math.sqrt(dx2 * dx2 + dy2 * dy2);
            var midX = (f0.x + f1.x) / 2, midY = (f0.y + f1.y) / 2;

            // pinch -- distance delta vs last step (cfg.pinchEnabled).
            if (cfg.pinchEnabled) {
                var distDelta = curDist - twoFingerState.lastDist;
                pinchAccum += distDelta;
                while (Math.abs(pinchAccum) >= PINCH_THRESHOLD_PX) {
                    var sign = pinchAccum > 0 ? -1 : 1; // expand → zoom in (negative wheel deltaY)
                    dispatchPinchStep(twoFingerState.pinchAction, sign, midX, midY);
                    pinchAccum -= sign < 0 ? PINCH_THRESHOLD_PX : -PINCH_THRESHOLD_PX;
                }
                twoFingerState.lastDist = curDist;
            }

            // 2-finger drag -- midpoint delta over scroll threshold.
            if (cfg.twoFingerDragEnabled) {
                var mdx = midX - twoFingerState.lastMidX;
                var mdy = midY - twoFingerState.lastMidY;
                if (Math.abs(mdx) > SCROLL_THRESHOLD_PX || Math.abs(mdy) > SCROLL_THRESHOLD_PX) {
                    dispatch2fDrag(twoFingerState.dragAction, mdx, mdy, midX, midY);
                    twoFingerState.lastMidX = midX;
                    twoFingerState.lastMidY = midY;
                    twoFingerState.hadDragMovement = true;
                }
            }
            try { e.preventDefault(); } catch (err) {}
            try { e.stopImmediatePropagation(); } catch (err) {}
            return;
        }

        // 1-finger move.
        if (primary === null || primary.editable) return;
        var passthrough1f = !cfg.tapEnabled && !cfg.dragEnabled;
        if (passthrough1f) return;

        for (var j = 0; j < e.changedTouches.length; j++) {
            var t = e.changedTouches[j];
            if (t.identifier !== primary.id) continue;

            var dxp = t.clientX - primary.lastX;
            var dyp = t.clientY - primary.lastY;
            primary.lastX = t.clientX;
            primary.lastY = t.clientY;

            // tap-vs-drag classification: cancel long-press if movement past TAP_RADIUS.
            var totalDx = t.clientX - primary.startX;
            var totalDy = t.clientY - primary.startY;
            var dist = Math.sqrt(totalDx * totalDx + totalDy * totalDy);
            if (dist > TAP_RADIUS_PX) {
                primary.hadMovement = true;
                if (primary.longPressTimer) {
                    clearTimeout(primary.longPressTimer);
                    primary.longPressTimer = null;
                }
            }

            // dispatch mousemove (drag stream). gated on dragEnabled -- when disabled, single
            // finger only fires tap (on release within tap bounds), no move events.
            if (cfg.dragEnabled || cfg.tapEnabled) {
                if (primary.mode === 'absolute') {
                    var hovered = dispatchMouseAt('mousemove', t.clientX, t.clientY);
                    syncHover(hovered, t.clientX, t.clientY);
                    injectC3Move(t.clientX, t.clientY);
                } else {
                    var maxX = ((window.innerWidth | 0) || 1) - 1;
                    var maxY = ((window.innerHeight | 0) || 1) - 1;
                    cursorX = Math.max(0, Math.min(maxX, cursorX + dxp * RELATIVE_SENSITIVITY));
                    cursorY = Math.max(0, Math.min(maxY, cursorY + dyp * RELATIVE_SENSITIVITY));
                    // mirror cursor to bridge so input-synth keyboard-driven mouse stays in sync.
                    var b = window.__gnInputBridge;
                    if (b && typeof b.enqueue === 'function') {
                        try { b.enqueue('{"type":"cursormove","x":' + (cursorX | 0) + ',"y":' + (cursorY | 0) + '}'); } catch (err) {}
                    }
                    dispatchAtCursor('mousemove', 0);
                    injectC3Move(cursorX, cursorY);
                }
            }
            break;
        }
    }

    function onTouchEnd(e) {
        if (!isActive()) return;
        var cfg = getCfg();
        // multi-finger sequences (peak ≥ 2) are owned by the shim -- block all native and
        // game-side handling so a disabled gesture really is disabled. covers the trailing
        // finger-up events too, when our cascade-guard means we're not firing a tap.
        if (maxFingersThisSequence >= 2) {
            try { e.preventDefault(); } catch (_) {}
            try { e.stopImmediatePropagation(); } catch (_) {}
        }

        for (var i = 0; i < e.changedTouches.length; i++) {
            var t = e.changedTouches[i];
            var idx = findFingerIdx(t.identifier);
            if (idx < 0) continue;
            var f = fingers[idx];
            var dt = Date.now() - f.t0;
            var dx = t.clientX - f.x0;
            var dy = t.clientY - f.y0;
            var dist = Math.sqrt(dx * dx + dy * dy);

            // multi-finger tap classification -- drive off the gesture's high-water mark so a
            // 3-finger tap stays a 3-finger tap even when fingers lift one event at a time.
            // multiFingerHandled gate ensures we fire (or explicitly skip) at most once 
            // gesture, so trailing finger-lifts don't cascade to a lower-finger tap.
            if (!multiFingerHandled && maxFingersThisSequence >= 2) {
                multiFingerHandled = true;
                if (maxFingersThisSequence === 2 && cfg.twoFingerTapEnabled) {
                    if (dt < TWO_FINGER_TAP_DURATION_MAX && dist < TWO_FINGER_TAP_RADIUS) {
                        var action = cfg.twoFingerTapAction || 'right_click';
                        var btn = action === 'left_click' ? 0 : (action === 'middle_click' ? 1 : 2);
                        fireMouse('mousedown', t.clientX, t.clientY, btn);
                        fireMouse('mouseup', t.clientX, t.clientY, btn);
                        if (btn === 2) fireMouse('contextmenu', t.clientX, t.clientY, btn);
                        injectC3Click(t.clientX, t.clientY, btn);
                    }
                } else if (maxFingersThisSequence === 3 && cfg.threeFingerTapEnabled) {
                    if (dt < TWO_FINGER_TAP_DURATION_MAX && dist < TWO_FINGER_TAP_RADIUS) {
                        dispatch3fAction(cfg.threeFingerTapAction || 'open_quick_menu', t.clientX, t.clientY);
                    }
                }
            }

            // 1-finger end -- tap / drag-end / double-tap / passthrough.
            if (primary !== null && t.identifier === primary.id) {
                var passthrough1f = !cfg.tapEnabled && !cfg.dragEnabled;
                if (!primary.editable && !passthrough1f && !primary.consumed) {
                    if (primary.longPressTimer) {
                        clearTimeout(primary.longPressTimer);
                        primary.longPressTimer = null;
                    }
                    var px = primary.mode === 'relative' ? cursorX : t.clientX;
                    var py = primary.mode === 'relative' ? cursorY : t.clientY;
                    if (primary.mode === 'absolute') {
                        dispatchMouseAt('mouseup', px, py);
                    } else {
                        dispatchAtCursor('mouseup', 0);
                    }
                    var totalDx = t.clientX - primary.startX;
                    var totalDy = t.clientY - primary.startY;
                    var totalDist = Math.sqrt(totalDx * totalDx + totalDy * totalDy);
                    var totalDt = Date.now() - primary.startTime;
                    var isTap = (totalDist <= TAP_RADIUS_PX && totalDt <= TAP_MAX_MS);

                    if (isTap && cfg.tapEnabled) {
                        if (primary.mode === 'absolute') dispatchMouseAt('click', px, py);
                        else dispatchAtCursor('click', 0);
                        injectC3Click(px, py, 0);

                        // double-tap detection (port touch-gestures + Wine TouchpadView pattern).
                        if (cfg.doubleTapEnabled) {
                            var ddt = Date.now() - lastTapTime;
                            var ddx = px - lastTapX;
                            var ddy = py - lastTapY;
                            var dDist = ddx * ddx + ddy * ddy;
                            var dWindow = (cfg.doubleTapDelay | 0) || 300;
                            if (ddt < dWindow && dDist < (DOUBLE_TAP_DISTANCE_PX * DOUBLE_TAP_DISTANCE_PX)) {
                                // fire second click sequence -- games use dblclick listeners.
                                if (primary.mode === 'absolute') {
                                    dispatchMouseAt('mousedown', px, py);
                                    dispatchMouseAt('mouseup', px, py);
                                    dispatchMouseAt('click', px, py);
                                    // c2's Mouse plugin binds jQuery(document).dblclick;
                                    // its OnClick(button, type) condition gates on triggerType=1
                                    // which only flips when onDoubleClick fires. browser does
                                    // NOT auto-fire dblclick from synthetic clicks, so dispatch
                                    // it explicitly here. Hypnospace icons are double-click --
                                    // without this, dbl-tap registers as two singles.
                                    dispatchMouseAt('dblclick', px, py);
                                } else {
                                    dispatchAtCursor('mousedown', 0);
                                    dispatchAtCursor('mouseup', 0);
                                    dispatchAtCursor('click', 0);
                                    dispatchAtCursor('dblclick', 0);
                                }
                                injectC3Click(px, py, 0);
                                lastTapTime = 0; // consume -- 3rd quick tap is fresh tap, not triple.
                            } else {
                                lastTapTime = Date.now();
                                lastTapX = px; lastTapY = py;
                            }
                        }
                        // suppress native click dedupe.
                        try { e.preventDefault(); } catch (err) {}
                    }
                    if (primary.mode === 'absolute') clearHover(px, py);
                }
                primary = null;
            }

            fingers.splice(idx, 1);
            break; // one finger per change-event iteration
        }

        // 2-finger gesture cleanup (when last 2nd finger lifts).
        if (e.touches.length < 2 && twoFingerState !== null) {
            if (twoFingerState.middleDown) {
                fireMouse('mouseup', twoFingerState.lastMidX, twoFingerState.lastMidY, 1);
                twoFingerState.middleDown = false;
            }
            twoFingerState = null;
            pinchAccum = 0;
        }

        // gesture sequence ends when the last finger lifts -- reset multi-finger trackers
        // so the next gesture starts fresh.
        if (fingers.length === 0) {
            maxFingersThisSequence = 0;
            multiFingerHandled = false;
        }
    }

    function onTouchCancel(e) {
        if (!isActive()) return;
        if (primary && primary.longPressTimer) {
            clearTimeout(primary.longPressTimer);
        }
        if (twoFingerState && twoFingerState.middleDown) {
            try { fireMouse('mouseup', twoFingerState.lastMidX, twoFingerState.lastMidY, 1); } catch (err) {}
        }
        primary = null;
        twoFingerState = null;
        pinchAccum = 0;
        fingers.length = 0;
        maxFingersThisSequence = 0;
        multiFingerHandled = false;
        clearHover(0, 0);
    }

    // ── listener registration ──
    // RESEARCH/note: passive:false uniformly across all four listeners (matches 3-of-4
    // old shims). cost is one synchronous JS hop per move event; benefit is preventDefault
    // works on touchmove (needed for 2-finger gesture native-zoom suppression).
    var tapOpts = { capture: true, passive: false };
    var moveOpts = { capture: true, passive: false };
    document.addEventListener('touchstart', onTouchStart, tapOpts);
    document.addEventListener('touchmove', onTouchMove, moveOpts);
    document.addEventListener('touchend', onTouchEnd, tapOpts);
    document.addEventListener('touchcancel', onTouchCancel, tapOpts);
    window.__gnTouchShimActive = 'touch';

    // __gnTouchShimUnload kept for screen-exit cleanup ONLY. config updates
    // do NOT call this -- they update window.__gnGestureConfig and the next event reads fresh.
    window.__gnTouchShimUnload = function () {
        try { document.removeEventListener('touchstart',  onTouchStart,  tapOpts);  } catch (e) {}
        try { document.removeEventListener('touchmove',   onTouchMove,   moveOpts); } catch (e) {}
        try { document.removeEventListener('touchend',    onTouchEnd,    tapOpts);  } catch (e) {}
        try { document.removeEventListener('touchcancel', onTouchCancel, tapOpts);  } catch (e) {}
        if (primary && primary.longPressTimer) clearTimeout(primary.longPressTimer);
        primary = null;
        twoFingerState = null;
        pinchAccum = 0;
        fingers.length = 0;
        currentHover = null;
        window.__gnTouchShimUnload = null;
        window.__gnTouchShimActive = null;
    };
})();
