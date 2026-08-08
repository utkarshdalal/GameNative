// gamenative html5 desktop-spoof shim
//
// half of a two-pronged "navigator looks like Windows desktop" intervention. host side
// rewrites WebSettings.userAgentString (covers navigator.userAgent + navigator.appVersion +
// outbound HTTP headers). this shim handles what host-side can't reach:
//   - navigator.platform        → 'Win32'
//   - navigator.userAgentData   → { platform: 'Windows', mobile: false, brands: [...] }
//
// rationale: pack:nwjs already poses as Windows on the node side (process.platform === 'win32';
// see project_html5_windows_nwjs_posture). the browser side leaking mobile WebView identity
// creates a mixed fiction -- c2 reports `isNWjs: true` AND `isAndroid: true` simultaneously,
// which no real platform produces and which game plugins branch on inconsistently.
//
// what we DO NOT touch: window.ontouchstart, navigator.maxTouchPoints, TouchEvent,
// pointer/mouse event delivery. games that branch on raw event-target detection still
// hit the touch path; that's a separate intervention if needed.
//
// IMPLEMENTATION NOTES -- chromium WebView 124 caveats observed empirically:
//   - navigator.platform: instance-level Object.defineProperty WORKS.
//   - navigator.userAgentData: instance-level FAILS silently (non-configurable). we walk to
//     Navigator.prototype and define there as a fallback. failures are LOGGED, not swallowed.
//
// scope: opt-in via EngineProfile.desktopUaSpoof. starts per-title so we observe behavior
// before pack-wide promotion.

(function () {
    'use strict';
    if (typeof navigator === 'undefined') return;

    var origUa = navigator.userAgent || '';
    var chromeMatch = origUa.match(/Chrome\/[\d.]+/);
    var chromeToken = chromeMatch ? chromeMatch[0] : 'Chrome/124.0.0.0';
    var majorChrome = chromeToken.split('/')[1].split('.')[0];

    function warn(msg) {
        try { console.warn('gamenative desktop-spoof: ' + msg); } catch (_e) {}
    }

    // attempt instance-level redefine first. if instance has the property non-configurable
    // (chromium pattern for some navigator props), walk to Navigator.prototype as fallback.
    // returns true on success, false if both attempts fail. logs the path taken either way.
    function spoofGetter(key, value) {
        var descriptor = {
            configurable: true,
            enumerable: true,
            get: function () { return value; },
        };
        try {
            Object.defineProperty(navigator, key, descriptor);
            return true;
        } catch (instanceErr) {
            // try the prototype. instance prop (if any) shadows prototype, but if instance
            // hasn't materialized the property yet, the prototype getter is what reads return.
            var proto = Object.getPrototypeOf(navigator);
            while (proto) {
                if (Object.prototype.hasOwnProperty.call(proto, key)) {
                    try {
                        Object.defineProperty(proto, key, descriptor);
                        return true;
                    } catch (protoErr) {
                        warn('failed to redefine "' + key + '" on prototype: ' + protoErr.message);
                        return false;
                    }
                }
                proto = Object.getPrototypeOf(proto);
            }
            warn('failed to redefine "' + key + '" on instance and no prototype owns it: ' + instanceErr.message);
            return false;
        }
    }

    var platformOk = spoofGetter('platform', 'Win32');
    var uaDataOk = spoofGetter('userAgentData', {
        brands: [
            { brand: 'Google Chrome', version: majorChrome },
            { brand: 'Chromium', version: majorChrome },
            { brand: 'Not_A Brand', version: '24' },
        ],
        mobile: false,
        platform: 'Windows',
    });

    if (self.__gnShimVerbose) try {
        console.log(
            'gamenative desktop-spoof loaded: platform=' + (platformOk ? 'ok' : 'FAILED') +
            ' userAgentData=' + (uaDataOk ? 'ok' : 'FAILED') +
            ' (host-side handles userAgent + appVersion via WebSettings)',
        );
    } catch (_e) {}
})();
