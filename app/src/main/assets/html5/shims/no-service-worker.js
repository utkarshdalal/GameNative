// a page served at a real site's origin must not register a service worker there: one would outlive the session
// and answer that origin's requests instead of the host. injected first, before any of the app's code.
(function () {
    'use strict';
    var sw = navigator.serviceWorker;
    if (!sw) return;
    function refuse() {
        return Promise.reject(new DOMException('service workers are not available here', 'SecurityError'));
    }
    try {
        Object.defineProperty(sw, 'register', { value: refuse, configurable: false, writable: false });
    } catch (e) {
        try { sw.register = refuse; } catch (e2) { /* swallow */ }
    }
    try {
        if (typeof sw.getRegistrations === 'function') {
            sw.getRegistrations().then(function (regs) {
                regs.forEach(function (r) { try { r.unregister(); } catch (e) { /* swallow */ } });
            }, function () {});
        }
    } catch (e) { /* swallow */ }
})();
