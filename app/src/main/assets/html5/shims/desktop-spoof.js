// makes navigator.platform / navigator.userAgentData look like Windows desktop; the host rewrites the UA string
// itself. the node side already reports win32, and a leaking Android identity makes engines report impossible
// combos (c2: isNWjs AND isAndroid) that plugins branch on inconsistently. touch detection is deliberately left alone.
// opt-in via EngineProfile.desktopUaSpoof.
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

    // on chromium 124 WebView, instance-level defineProperty works for platform but fails for userAgentData,
    // which must be redefined on Navigator.prototype. failures are logged, not swallowed.
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
