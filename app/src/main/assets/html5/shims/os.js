// gamenative html5 os shim -- minimal node-compat os module for require("os").
//
// reports 'win32' platform -- see project_html5_windows_nwjs_posture memory. HTML5 games
// load the WINDOWS NW.js distribution; matching identity here keeps platform-conditional
// game code on the Windows branch so save paths compose under %APPDATA% (which the bridge
// maps to <wine>/drive_c/users/xuser/AppData/Roaming/) where cloud sync watches.
(function () {
    'use strict';

    var PLATFORM = (typeof self !== 'undefined' && self.__gnPlatform) || 'win32';
    var HOMEDIR = 'C:/users/xuser';
    var EOL = PLATFORM === 'win32' ? '\r\n' : '\n';

    var osModule = {
        platform: function () { return PLATFORM; },
        type: function () { return PLATFORM === 'win32' ? 'Windows_NT' : 'Linux'; },
        arch: function () { return PLATFORM === 'win32' ? 'x64' : 'arm64'; },
        release: function () { return '0.0.0-gamenative-stub'; },
        EOL: EOL,
        homedir: function () { return HOMEDIR; },
        tmpdir: function () { return PLATFORM === 'win32' ? 'C:/users/xuser/AppData/Local/Temp' : '/tmp'; },
        hostname: function () { return 'gamenative'; },
        cpus: function () { return []; },
    };

    if (window.require && typeof window.require.register === 'function') {
        window.require.register('os', osModule);
        if (self.__gnShimVerbose) try { console.log('gamenative os shim registered'); } catch (e) {}
    }
})();
