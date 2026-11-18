// exposes `--gn-bottom-inset` on the root element: how far the LAYOUT viewport extends below the visible (visual)
// viewport. non-zero when a page's fixed `<meta viewport width=N>` doesn't fit the device, so elements anchored at
// `bottom:0` land off-screen. pack CSS uses it as e.g. `bottom: var(--gn-bottom-inset, 0px)`.
//
// uses visualViewport.height, not screen.availHeight: availHeight ignores page zoom (initial-scale) and would
// over-report the inset once a pack rescales the viewport.
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
