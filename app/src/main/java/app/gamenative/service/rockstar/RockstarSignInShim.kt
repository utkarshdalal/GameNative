package app.gamenative.service.rockstar

import org.json.JSONObject

/**
 * The launcher bridge the Rockstar sign-in page expects.
 *
 * `/signin/user-form?cid=launcher` is served to the Rockstar Games Launcher, and it drives its
 * host over `window.rgscQuery` and `window.rgscAddSubscription`. Loaded in a plain browser those
 * are missing, so the page never starts the flow. This provides them, answering the handful of
 * queries the page makes before it will show a form and, at the end, catching
 * `CallAuthResult{authCode}`.
 *
 * Ported from the shim that produced a working token on 2026-09-08. Two details there are load
 * bearing and were found the hard way:
 *   - the fingerprint MUST carry `device_name`
 *   - the shim has to run on a page of the signin origin that is not the app itself (robots.txt),
 *     then fetch the form and write it into the document; injecting over an already-running copy
 *     breaks its webpack config with 'webpackPath' undefined
 *
 * The auth code expires in about sixty seconds, so it is handed straight to the host rather than
 * left for a poller. The `gn_code` cookie is still set as the original did, as a fallback.
 */
object RockstarSignInShim {

    /** Reported through GetDeviceId. `device_name` is required; the rest simply has to be present. */
    fun fingerprint(deviceName: String = "GAMENATIVE"): JSONObject = JSONObject()
        .put("device_name", deviceName)
        .put("machine_name", deviceName)
        .put("os_version", "10.0.19045")
        .put("volume_serial", "1a2b3c4d")
        .put("cpu_info", "178bfbff")

    /**
     * @param titleName the ROS title the sign-in is for, e.g. "rdr2"
     * @param bridge    the name of the Kotlin object exposed with addJavascriptInterface
     */
    fun script(titleName: String, bridge: String, deviceName: String = "GAMENATIVE"): String {
        val fp = fingerprint(deviceName).toString()
        return """
        (function(){
          if (window.__gnShim) return 'already';
          window.__gnShim = true;
          var __id = 0;
          var FP = $fp;
          window.__fp = FP;

          window.rgscQuery = function(q){
            var rid = ++__id;
            var r = {};
            try { $bridge.onQuery(q && q.method ? q.method : '(none)'); } catch(e) {}
            if (q.method === 'GetDeviceId')                  r = { fingerprintKvp: FP };
            else if (q.method === 'GetVersionInfo')          r = { version: "1.0.33.319", titleVersion: "" };
            else if (q.method === 'GetCommandLineArguments') r = { arguments: [] };
            else if (q.method === 'GetProfileList')          r = { profiles: [] };
            else if (q.method === 'CallAuthResult') {
              var code = q.params && q.params.authCode;
              if (code) {
                document.cookie = 'gn_code=' + code + '; domain=.rockstargames.com; path=/; secure';
                try { $bridge.onAuthCode(code, JSON.stringify(FP)); } catch(e) {}
              } else {
                try { $bridge.onAuthFailed(JSON.stringify(q.params || {})); } catch(e) {}
              }
            }
            setTimeout(function(){ try { q.onSuccess && q.onSuccess(r); } catch(e) {} }, 0);
            return rid;
          };

          window.rgscAddSubscription = function(s){
            var rid = ++__id;
            try { $bridge.onQuery('sub:' + (s && s.method ? s.method : '(none)')); } catch(e) {}
            if (s.method === 'OnStartAuth') {
              setTimeout(function(){
                s.onSuccess({ launchPlatform: 0, titleLaunchInfo: { activeTitleName: "$titleName" } });
              }, 100);
            }
            return rid;
          };

          window.rgscQueryCancel = function(){};

          fetch('/signin/user-form?cid=launcher', { credentials: 'same-origin' })
            .then(function(r){ return r.text(); })
            .then(function(html){
              history.replaceState(null, '', '/signin/user-form?cid=launcher');
              document.open(); document.write(html); document.close();
            })
            .catch(function(e){ try { $bridge.onAuthFailed('form fetch: ' + e); } catch(_) {} });
          return 'ok';
        })();
        """.trimIndent()
    }

    /**
     * Exchanges the auth code for the token, run on the rgl origin so the request carries its
     * cookies. The fingerprint parameter's exact encoding is NOT confirmed, so the status and the
     * response's field names are reported back before anything is trusted.
     */
    fun exchangeScript(authCode: String, fingerprintJson: String, bridge: String): String {
        val code = JSONObject.quote(authCode)
        val fp = JSONObject.quote(fingerprintJson)
        return """
        (function(){
          var url = '/api/connect/gateway?code=' + encodeURIComponent($code) +
                    '&fingerprint=' + encodeURIComponent($fp);
          fetch(url, { credentials: 'include', headers: { 'X-Requested-With': 'XMLHttpRequest' } })
            .then(function(r){ return r.text().then(function(t){ return { s: r.status, t: t }; }); })
            .then(function(o){
              var names = '', token = '';
              try {
                var j = JSON.parse(o.t);
                names = Object.keys(j).join(',');
                token = j.ScAuthToken || j.scAuthToken || (j.data && (j.data.ScAuthToken || j.data.scAuthToken)) || '';
              } catch(e) { names = 'not json'; }
              $bridge.onExchange(o.s, names, token);
            })
            .catch(function(e){ $bridge.onExchange(0, 'fetch failed: ' + e, ''); });
        })();
        """.trimIndent()
    }
}
