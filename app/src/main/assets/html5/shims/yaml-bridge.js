// gamenative html5 yaml bridge -- wires the bundled js-yaml UMD into the require dispatcher.
// js-yaml.min.js is loaded BEFORE this shim and exposes itself on window.jsyaml. titles that
// `require('./js/libs/js-yaml-master')` (NW.js relative-folder require) get the same instance.

// js-yaml 3.14.1 retains both `safeLoad` (legacy) and `load`. OMORI's plugins call safeLoad;
// newer titles may call load. both work because we hand back the whole UMD module.
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

    // exact-name registrations cover the conventional ids.
    window.require.register('js-yaml', window.jsyaml);
    window.require.register('yaml', window.jsyaml);

    // pattern: any require id ending in `js-yaml-master` (with or without `./` prefix /
    // trailing slash) routes to the bundled module. matches both `./js/libs/js-yaml-master`
    // and `../libs/js-yaml-master/` variants.
    if (typeof window.require.register.pattern === 'function') {
        window.require.register.pattern(/js-yaml(-master)?\/?$/, window.jsyaml);
    }

    if (self.__gnShimVerbose) try { console.log('gamenative yaml-bridge installed (js-yaml ' + (window.jsyaml.version || '?') + ')'); } catch (e) {}
})();
