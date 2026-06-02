// gamenative html5 viewport-inset -- exposes `--gn-bottom-inset` CSS variable on the root
// element equal to how much of the LAYOUT viewport overflows BELOW the visible (visual)
// viewport. zero on devices where layout viewport == visual viewport (most cases).

// non-zero when a page sets `<meta name="viewport" content="width=N">` with a fixed N that
// doesn't match the device's CSS pixel width AND pack:electron's viewport-fit hasn't shrunk
// it to fit. selectors anchored to layout-viewport bottom (e.g. `position:absolute; bottom:0`)
// land below the visible area in that case.

// formula uses visualViewport.height (post-zoom) when available -- that's the true visible
// viewport height in layout CSS px. window.screen.availHeight is in DEVICE CSS px and
// doesn't account for page zoom (initial-scale), so it would over-report the inset when
// pack:electron rewrites initial-scale to fit. fallback to availHeight for browsers without
// visualViewport (none we ship to -- Chromium ≥ 100 has it).

// pack-level overrides reference this primitive without recomputing it themselves:
// #notes { bottom: var(--gn-bottom-inset, 0px) !important; }

// always-on in WebViewScreen's shim chain alongside url-sanitize / crypto / os. updates on
// `resize` and `visualViewport.resize` (rotation, soft-nav-bar toggle, pinch/zoom changes).
(function () {
    'use strict';
    if (typeof window === 'undefined' || typeof document === 'undefined') return;
    function apply() {
        try {
            var visualH = (window.visualViewport && window.visualViewport.height) || window.screen.availHeight;
            var inset = Math.max(0, (window.innerHeight | 0) - (visualH | 0));
            document.documentElement.style.setProperty('--gn-bottom-inset', inset + 'px');
        } catch (_e) {}
    }
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', apply, { once: true });
    } else {
        apply();
    }
    window.addEventListener('resize', apply);
    if (window.visualViewport) {
        try { window.visualViewport.addEventListener('resize', apply); } catch (_e) {}
    }
})();
