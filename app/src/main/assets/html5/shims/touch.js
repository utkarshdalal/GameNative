// config-driven touch -> mouse/keyboard gesture shim. window.__gnGestureConfig is read FRESH on
// every event so the host can live-update it without re-injecting or tearing down listeners.
(function () {
    'use strict';

    // guards against double listener registration on a re-inject.
    if (window.__gnTouchShimActive === 'touch') return;

    // safety net if the host didn't inject a config; mirrors TouchGestureConfig.kt defaults.
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

    var TAP_RADIUS_PX = 10;             // matches TouchMouse.MAX_TAP_TRAVEL_DISTANCE
    var TAP_MAX_MS = 200;               // matches TouchMouse.MAX_TAP_MILLISECONDS
    var DOUBLE_TAP_DISTANCE_PX = 100;   // mirrors TouchGestureConfig.DOUBLE_TAP_DISTANCE_PX
    var TWO_FINGER_TAP_DURATION_MAX = 250;
    var TWO_FINGER_TAP_RADIUS = 12;
    var SCROLL_THRESHOLD_PX = 4;
    var PINCH_THRESHOLD_PX = 6;         // delta-distance threshold to dispatch pinch step
    var RELATIVE_SENSITIVITY = 1.5;

    // editable targets need the native click to raise the soft keyboard: no synth, no preventDefault.
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

    // relative-mode synthetic cursor. mode is captured once per gesture so switching cursorMode
    // mid-gesture can't corrupt running state.
    var cursorX = (window.innerWidth | 0) >> 1;
    var cursorY = (window.innerHeight | 0) >> 1;

    var primary = null;
    var lastTapTime = 0, lastTapX = 0, lastTapY = 0;
    var currentHover = null;            // absolute mode only

    // { id, x0, y0, x, y, t0 } per active touch.
    var fingers = [];

    var twoFingerState = null;          // captured when the SECOND finger lands
    var pinchAccum = 0;
    // peak finger count this sequence, so a 3-finger tap still classifies as 3 when fingers
    // lift one at a time.
    var maxFingersThisSequence = 0;
    // once a multi-finger tap is classified (or skipped), trailing finger-lifts must not
    // re-fire it as a lower-finger tap.
    var multiFingerHandled = false;

    // c3 runtime POJO injection (see packs/c3.js header)
    function injectC3Move(x, y) {
        var fn = window.__gnC3InjectMousePointer;
        if (fn) try { fn('pointermove', x, y, 0); } catch (e) {}
    }
    function injectC3Click(x, y, button) {
        var fn = window.__gnC3InjectMousePointer;
        if (!fn) return;
        var btn = button | 0;
        try { fn('pointerdown', x, y, 1 << btn); } catch (e) {}
        // 2-rAF gap so c3's runtime sees pointerdown in its own tick before pointerup.
        requestAnimationFrame(function () {
            requestAnimationFrame(function () {
                var fn2 = window.__gnC3InjectMousePointer;
                if (fn2) try { fn2('pointerup', x, y, 0); } catch (e) {}
            });
        });
    }

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
            // engines that reject the MouseEvent ctor.
            ev = document.createEvent('MouseEvent');
            ev.initMouseEvent(type, true, true, window, 0, x, y, x, y, false, false, false, false, button | 0, null);
        }
        try { target.dispatchEvent(ev); } catch (e) {}
        return target;
    }

    // absolute mode only: some games gate click handlers on a preceding mouseover/mouseenter.
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

    // show_keyboard only comes from wine-side configs; map it to the closest html5 analog
    // rather than silently no-op.
    function dispatch3fAction(action, x, y) {
        if (action === 'show_keyboard') action = 'open_quick_menu';
        switch (action) {
            case 'open_quick_menu':
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
            var keyName = action.substring(4);
            var info = keyToCodeAndKey(keyName);
            if (info) dispatchKey(info.code, info.key, info.codeName);
        }
    }

    // covers the key_<X> actions the action picker offers.
    function keyToCodeAndKey(keyName) {
        if (!keyName) return null;
        if (keyName === 'ESC') return { code: 27, key: 'Escape', codeName: 'Escape' };
        if (keyName === 'SPACE') return { code: 32, key: ' ', codeName: 'Space' };
        if (keyName === 'TAB') return { code: 9, key: 'Tab', codeName: 'Tab' };
        if (keyName === 'ENTER') return { code: 13, key: 'Enter', codeName: 'Enter' };
        if (keyName.length >= 2 && keyName.charAt(0) === 'F') {
            var fnum = parseInt(keyName.substring(1), 10);
            if (fnum >= 1 && fnum <= 12) {
                return { code: 111 + fnum, key: keyName, codeName: keyName };
            }
        }
        if (keyName.length === 1 && keyName >= 'A' && keyName <= 'Z') {
            return { code: keyName.charCodeAt(0), key: keyName.toLowerCase(), codeName: 'Key' + keyName };
        }
        if (keyName.length === 1 && keyName >= '0' && keyName <= '9') {
            return { code: keyName.charCodeAt(0), key: keyName, codeName: 'Digit' + keyName };
        }
        return null;
    }

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
        primary.consumed = true;
    }

    // once per movement step.
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
                // mouseup comes at gesture end.
                if (!twoFingerState.middleDown) {
                    fireMouse('mousedown', x, y, 1);
                    twoFingerState.middleDown = true;
                }
                fireMouse('mousemove', x, y, 1);
                break;
        }
    }

    // one step per PINCH_THRESHOLD_PX of distance change.
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

    // touchscreen mode (__gnTouchModeActive === false) suspends all gesture interpretation;
    // raw touch events still reach games with native touch handling.
    function isActive() { return window.__gnTouchModeActive !== false; }

    function onTouchStart(e) {
        if (!isActive()) return;
        var cfg = getCfg();
        for (var i = 0; i < e.changedTouches.length; i++) {
            var ct = e.changedTouches[i];
            fingers.push({ id: ct.identifier, x0: ct.clientX, y0: ct.clientY, x: ct.clientX, y: ct.clientY, t0: Date.now() });
        }
        if (fingers.length > maxFingersThisSequence) maxFingersThisSequence = fingers.length;

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
                // released at touchend even when `consumed`, or the page keeps the button held.
                mouseDownFired: false,
            };
            // 1f tap + drag both off: game gets native touch; multi-finger gestures still apply.
            var passthrough1f = !cfg.tapEnabled && !cfg.dragEnabled;
            if (!primary.editable && !passthrough1f) {
                if (primary.mode === 'absolute') {
                    // hover FIRST so click-gating games see over/enter -> move -> down -> up -> click.
                    syncHover(target, t.clientX, t.clientY);
                    dispatchMouseAt('mousemove', t.clientX, t.clientY);
                    dispatchMouseAt('mousedown', t.clientX, t.clientY);
                    primary.mouseDownFired = true;
                    injectC3Move(t.clientX, t.clientY);
                } else {
                    // viewport may have shrunk since the cursor last moved.
                    cursorX = Math.max(0, Math.min((window.innerWidth | 0) - 1, cursorX));
                    cursorY = Math.max(0, Math.min((window.innerHeight | 0) - 1, cursorY));
                }
                if (cfg.longPressEnabled) {
                    var lpDelay = (cfg.longPressDelay | 0) || 300;
                    primary.longPressTimer = setTimeout(onLongPressFire, lpDelay);
                }
            }
        }

        if (e.touches.length === 2 && twoFingerState === null && fingers.length >= 2) {
            if (primary && primary.longPressTimer) {
                clearTimeout(primary.longPressTimer);
                primary.longPressTimer = null;
            }
            if (primary) primary.consumed = true;
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
        for (var i = 0; i < e.changedTouches.length; i++) {
            var ct = e.changedTouches[i];
            var idx = findFingerIdx(ct.identifier);
            if (idx >= 0) {
                fingers[idx].x = ct.clientX;
                fingers[idx].y = ct.clientY;
            }
        }

        // the game never sees multi-finger touchmove.
        if (e.touches.length >= 2 && twoFingerState !== null && fingers.length >= 2) {
            var f0 = fingers[0], f1 = fingers[1];
            var dx2 = f1.x - f0.x, dy2 = f1.y - f0.y;
            var curDist = Math.sqrt(dx2 * dx2 + dy2 * dy2);
            var midX = (f0.x + f1.x) / 2, midY = (f0.y + f1.y) / 2;

            if (cfg.pinchEnabled) {
                var distDelta = curDist - twoFingerState.lastDist;
                pinchAccum += distDelta;
                while (Math.abs(pinchAccum) >= PINCH_THRESHOLD_PX) {
                    var sign = pinchAccum > 0 ? -1 : 1; // spread = zoom in = negative wheel deltaY
                    dispatchPinchStep(twoFingerState.pinchAction, sign, midX, midY);
                    pinchAccum -= sign < 0 ? PINCH_THRESHOLD_PX : -PINCH_THRESHOLD_PX;
                }
                twoFingerState.lastDist = curDist;
            }

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
                    // keeps input-synth's keyboard-driven mouse in sync.
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
        // includes trailing finger-ups of a multi-finger sequence, even when no tap fires.
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

            if (primary !== null && t.identifier === primary.id) {
                var passthrough1f = !cfg.tapEnabled && !cfg.dragEnabled;
                // ALWAYS release, even when consumed: `consumed` only means "not a tap" (long-press
                // fired, or a second finger landed). skipping this leaves the button stuck down.
                if (primary.mouseDownFired) {
                    var rx = primary.mode === 'relative' ? cursorX : t.clientX;
                    var ry = primary.mode === 'relative' ? cursorY : t.clientY;
                    if (primary.mode === 'absolute') {
                        dispatchMouseAt('mouseup', rx, ry);
                    } else {
                        dispatchAtCursor('mouseup', 0);
                    }
                    primary.mouseDownFired = false;
                }
                if (!primary.editable && !passthrough1f && !primary.consumed) {
                    if (primary.longPressTimer) {
                        clearTimeout(primary.longPressTimer);
                        primary.longPressTimer = null;
                    }
                    var px = primary.mode === 'relative' ? cursorX : t.clientX;
                    var py = primary.mode === 'relative' ? cursorY : t.clientY;
                    var totalDx = t.clientX - primary.startX;
                    var totalDy = t.clientY - primary.startY;
                    var totalDist = Math.sqrt(totalDx * totalDx + totalDy * totalDy);
                    var totalDt = Date.now() - primary.startTime;
                    var isTap = (totalDist <= TAP_RADIUS_PX && totalDt <= TAP_MAX_MS);

                    if (isTap && cfg.tapEnabled) {
                        if (primary.mode === 'absolute') dispatchMouseAt('click', px, py);
                        else dispatchAtCursor('click', 0);
                        injectC3Click(px, py, 0);

                        if (cfg.doubleTapEnabled) {
                            var ddt = Date.now() - lastTapTime;
                            var ddx = px - lastTapX;
                            var ddy = py - lastTapY;
                            var dDist = ddx * ddx + ddy * ddy;
                            var dWindow = (cfg.doubleTapDelay | 0) || 300;
                            if (ddt < dWindow && dDist < (DOUBLE_TAP_DISTANCE_PX * DOUBLE_TAP_DISTANCE_PX)) {
                                if (primary.mode === 'absolute') {
                                    dispatchMouseAt('mousedown', px, py);
                                    dispatchMouseAt('mouseup', px, py);
                                    dispatchMouseAt('click', px, py);
                                    // browsers do NOT derive dblclick from synthetic clicks, and
                                    // e.g. Construct 2's Mouse plugin only sees double-clicks via it.
                                    dispatchMouseAt('dblclick', px, py);
                                } else {
                                    dispatchAtCursor('mousedown', 0);
                                    dispatchAtCursor('mouseup', 0);
                                    dispatchAtCursor('click', 0);
                                    dispatchAtCursor('dblclick', 0);
                                }
                                injectC3Click(px, py, 0);
                                lastTapTime = 0; // a 3rd quick tap starts fresh, not a triple.
                            } else {
                                lastTapTime = Date.now();
                                lastTapX = px; lastTapY = py;
                            }
                        }
                        // suppress the native click.
                        try { e.preventDefault(); } catch (err) {}
                    }
                    if (primary.mode === 'absolute') clearHover(px, py);
                }
                primary = null;
            }

            fingers.splice(idx, 1);
            break;
        }

        if (e.touches.length < 2 && twoFingerState !== null) {
            if (twoFingerState.middleDown) {
                fireMouse('mouseup', twoFingerState.lastMidX, twoFingerState.lastMidY, 1);
                twoFingerState.middleDown = false;
            }
            twoFingerState = null;
            pinchAccum = 0;
        }

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

    // passive:false so preventDefault works on touchmove (suppresses native 2-finger zoom).
    var tapOpts = { capture: true, passive: false };
    var moveOpts = { capture: true, passive: false };
    document.addEventListener('touchstart', onTouchStart, tapOpts);
    document.addEventListener('touchmove', onTouchMove, moveOpts);
    document.addEventListener('touchend', onTouchEnd, tapOpts);
    document.addEventListener('touchcancel', onTouchCancel, tapOpts);
    window.__gnTouchShimActive = 'touch';

    // screen-exit cleanup ONLY; config updates just replace window.__gnGestureConfig.
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
