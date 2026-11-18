// wires the bundled js-yaml (loaded BEFORE this shim, on window.jsyaml) into the require dispatcher.
// js-yaml 3.x keeps both legacy `safeLoad` and `load`; older plugins still call safeLoad.
(function () {
    'use strict';

    if (typeof window.jsyaml === 'undefined') {
        try { console.warn('gamenative yaml-bridge: jsyaml not on window — js-yaml.min.js failed to load'); } catch (e) {}
        return;
    }
    if (!window.require || typeof window.require.register !== 'function') {
        try { console.warn('gamenative yaml-bridge: require-dispatcher missing — yaml not available via require()'); } catch (e) {}
        return;
    }

    window.require.register('js-yaml', window.jsyaml);
    window.require.register('yaml', window.jsyaml);

    // games also require a vendored copy by relative folder (`./js/libs/js-yaml-master`, `../libs/js-yaml-master/`).
    if (typeof window.require.register.pattern === 'function') {
        window.require.register.pattern(/js-yaml(-master)?\/?$/, window.jsyaml);
    }

    if (self.__gnShimVerbose) try { console.log('gamenative yaml-bridge installed (js-yaml ' + (window.jsyaml.version || '?') + ')'); } catch (e) {}
})();
