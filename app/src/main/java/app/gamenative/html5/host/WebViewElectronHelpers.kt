package app.gamenative.html5.host

// rewrites a mobile WebView UA to a Windows desktop Chrome UA, preserving the underlying
// Chromium milestone token. consumed when EngineProfile.desktopUaSpoof = true.
// input examples accepted:
//   "Mozilla/5.0 (Linux; Android 15; Odin3 ...; wv) AppleWebKit/537.36 (KHTML, like Gecko)
//    Version/4.0 Chrome/124.0.6367.219 Mobile Safari/537.36"
// output:
//   "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)
//    Chrome/124.0.6367.219 Safari/537.36"
// if the input lacks a Chrome/X.Y.Z.W token, falls back to "Chrome/124.0.0.0" so the helper
// still produces a coherent string instead of throwing.
internal fun synthesizeDesktopChromeUa(originalUa: String): String {
    val chromeToken = Regex("""Chrome/[\d.]+""").find(originalUa)?.value ?: "Chrome/124.0.0.0"
    return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "$chromeToken Safari/537.36"
}

// builds __gnElectronCtx map -- productName + appPath + version. Electron's other
// path keys (userData/appData/documents/temp/home/...) are derived JS-side in packs/electron.js
// from process.env (APPDATA/USERPROFILE/TEMP populated by IndexHtmlRewriter as part of the
// Windows-NWjs posture). single source of truth for path strings.
//
// `appPath` -- what app.getAppPath() returns. dot-relative + Windows backslash so Tyrano's
// getExePath() can strip `\resources\app` and land on "." (=sandboxRoot=install dir). bridge
// resolves relative paths under sandboxRoot, so Tyrano's save writes (out_path + "/" + key +
// ".sav") land at <install>/<key>.sav where Steam Cloud UFS picks them up.
internal fun buildElectronCtx(
    productName: String,
    asarVersion: String?,
): Map<String, String> {
    return mapOf(
        "productName" to productName,
        "appPath" to ".\\resources\\app",
        "version" to (asarVersion ?: "0.0.0"),
    )
}
