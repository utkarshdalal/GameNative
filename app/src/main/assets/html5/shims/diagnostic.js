// dev-only diagnostic shim -- wraps localStorage + indexedDB; each call logs
// {ts, api, method, key|dbName, valueSize, stackFrame} to Html5DiagnosticBridge for later review.
// idempotent: runs at most once per document. quietly no-ops in release builds (bridge not registered).

// injection order: FIRST shim in ShimBundles (before game scripts) so every save-related API call
// is captured from frame zero.
(function () {
  if (window.__GAMENATIVE_DIAG_INSTALLED__) return;
  window.__GAMENATIVE_DIAG_INSTALLED__ = true;

  // bridge absent in release builds -- FeatureGate.ENABLE_HTML5_DIAGNOSTIC_SHIM gates registration.
  var bridge =
    (typeof Html5DiagnosticBridge !== 'undefined' && Html5DiagnosticBridge) ||
    (typeof android !== 'undefined' && android.Html5DiagnosticBridge) ||
    null;
  if (!bridge) return;

  function topFrame() {
    try {
      var e = new Error();
      var stack = e.stack || '';
      // return the first caller after this file -- best-effort; different engines format differently.
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
      // swallow -- diagnostic shim must never break the game.
    }
  }

  // localStorage wrapping. we reassign methods (not the object) so game code gets the same
  // Storage instance back; just with traced entry points.
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

  // indexedDB wrapping. only open/deleteDatabase -- per-transaction instrumentation is too noisy
  // for this shim's purpose (we want to know WHICH dbs game opens + when, not every put).
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

  safeLog({ ts: Date.now(), api: 'diagnostic', method: 'installed' });

})();
