// dev-only: logs localStorage / indexedDB / serviceWorker calls to Html5DiagnosticBridge. no-op when the bridge
// isn't registered (release builds). must be the FIRST shim so save-related calls are captured from the start.
(function () {
  if (window.__GAMENATIVE_DIAG_INSTALLED__) return;
  window.__GAMENATIVE_DIAG_INSTALLED__ = true;

  var bridge =
    (typeof Html5DiagnosticBridge !== 'undefined' && Html5DiagnosticBridge) ||
    (typeof android !== 'undefined' && android.Html5DiagnosticBridge) ||
    null;
  if (!bridge) return;

  function topFrame() {
    try {
      var e = new Error();
      var stack = e.stack || '';
      // first frame outside this file; best-effort, stack formats vary.
      var lines = stack.split('\n');
      for (var i = 1; i < lines.length; i++) {
        if (lines[i].indexOf('diagnostic.js') === -1) return lines[i].trim();
      }
      return lines[1] ? lines[1].trim() : '';
    } catch (_) {
      return '';
    }
  }

  function safeLog(obj) {
    try {
      bridge.log(JSON.stringify(obj));
    } catch (_) {
      // diagnostic shim must never break the game.
    }
  }

  // patch methods, not the object, so game code keeps the same Storage instance.
  if (window.localStorage) {
    var ls = window.localStorage;
    var origSet = ls.setItem.bind(ls);
    var origGet = ls.getItem.bind(ls);
    var origRemove = ls.removeItem.bind(ls);
    var origClear = ls.clear.bind(ls);
    ls.setItem = function (k, v) {
      safeLog({
        ts: Date.now(),
        api: 'localStorage',
        method: 'setItem',
        key: String(k),
        valueSize: v == null ? 0 : String(v).length,
        stackFrame: topFrame(),
      });
      return origSet(k, v);
    };
    ls.getItem = function (k) {
      safeLog({
        ts: Date.now(),
        api: 'localStorage',
        method: 'getItem',
        key: String(k),
        stackFrame: topFrame(),
      });
      return origGet(k);
    };
    ls.removeItem = function (k) {
      safeLog({
        ts: Date.now(),
        api: 'localStorage',
        method: 'removeItem',
        key: String(k),
        stackFrame: topFrame(),
      });
      return origRemove(k);
    };
    ls.clear = function () {
      safeLog({
        ts: Date.now(),
        api: 'localStorage',
        method: 'clear',
        stackFrame: topFrame(),
      });
      return origClear();
    };
  }

  // only open/deleteDatabase: the point is WHICH dbs a game uses; per-transaction logging is too noisy.
  if (window.indexedDB) {
    var idb = window.indexedDB;
    var origOpen = idb.open.bind(idb);
    var origDelete = idb.deleteDatabase.bind(idb);
    idb.open = function (name, version) {
      safeLog({
        ts: Date.now(),
        api: 'indexedDB',
        method: 'open',
        dbName: String(name),
        version: version || null,
        stackFrame: topFrame(),
      });
      return origOpen(name, version);
    };
    idb.deleteDatabase = function (name) {
      safeLog({
        ts: Date.now(),
        api: 'indexedDB',
        method: 'deleteDatabase',
        dbName: String(name),
        stackFrame: topFrame(),
      });
      return origDelete(name);
    };
  }

  // our http://<id>.localhost origins count as potentially trustworthy, so a service worker registration WOULD
  // succeed. that matters: SW fetches bypass our WebViewClient interceptors (no shim injection or patching), the SW
  // cache outlives the app, and uninstall doesn't clear SW registrations. logged rather than blocked until a real
  // title is seen trying.
  try {
    var swc = navigator.serviceWorker;
    if (swc && typeof swc.register === 'function') {
      var origRegister = swc.register.bind(swc);
      swc.register = function (script, opts) {
        safeLog({
          ts: Date.now(),
          api: 'serviceWorker',
          method: 'register',
          key: String(script),
          scope: (opts && opts.scope) ? String(opts.scope) : null,
          stackFrame: topFrame(),
        });
        return origRegister(script, opts);
      };
      // an EXISTING registration means one survived a previous session -- the persistence case.
      if (typeof swc.getRegistrations === 'function') {
        swc.getRegistrations().then(function (rs) {
          if (rs && rs.length) {
            safeLog({
              ts: Date.now(),
              api: 'serviceWorker',
              method: 'preexisting',
              valueSize: rs.length,
            });
          }
        }, function () {});
      }
    }
  } catch (_e) { /* no navigator.serviceWorker */ }

  safeLog({ ts: Date.now(), api: 'diagnostic', method: 'installed' });

})();
