package app.gamenative.html5.host

// desktop Chrome UA that keeps the real chromium milestone token.
internal fun synthesizeDesktopChromeUa(originalUa: String): String {
    val chromeToken = Regex("""Chrome/[\d.]+""").find(originalUa)?.value ?: "Chrome/124.0.0.0"
    return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "$chromeToken Safari/537.36"
}

// Electron's other paths (userData/appData/...) are derived JS-side in packs/electron.js from process.env.
//
// appPath is dot-relative with backslashes so Tyrano's getExePath() strips `\resources\app` down to "."
// (the install dir); its .sav writes then land in the install root where Steam Cloud picks them up.
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
