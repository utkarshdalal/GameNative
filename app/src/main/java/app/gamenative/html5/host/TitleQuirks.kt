package app.gamenative.html5.host

// registry of every title-specific behavior in the html5 runtime. each entry's impl lives in its own
// file and cross-references back here. engine-generic fixes that one title happens to need do NOT
// belong here -- they go in the pack JSON or pack shim.
object TitleQuirks {

    enum class KnownTitleQuirks(
        val titleName: String,
        val appIds: List<String>,
        val reason: String,
        val impl: String,
    ) {
        OMORI(
            titleName = "OMORI",
            appIds = listOf("STEAM_1150690"),
            reason = "AES-256-CTR decrypt for .OMORI/.KEL/.PLUTO assets — key arrives via Steam PICS " +
                "launch argument as `--<32-hex>`. RMMV xor key derived from KEL.",
            impl = "OmoriDecryptContext + Html5PackSetup.omoriContext/decryptContext/nwArgvJson",
        ),

        TYRANO_TPATCH(
            titleName = "any pack:tyrano title that ships .tpatch overlays",
            appIds = listOf("(generic to pack:tyrano)"),
            reason = "Tyrano script-pack overlays land as sibling .tpatch zips; the asset interceptor " +
                "must consult them before the base data.zip.",
            impl = "TyranoTpatchOverlay.scan",
        ),

        EFFEKSEER_WASM(
            titleName = "RMMZ titles shipping Effekseer particles on WebView < 124",
            appIds = listOf("(generic to RMMZ + WebView<124)"),
            reason = "Effekseer's instantiated WASM + active WebAudio triggers a deterministic audio " +
                "CHECK (SIGTRAP, renderer dies) on chromium WebView < 124; stub the wasm load so " +
                "particle effects skip — there is no fallback.",
            impl = "EffekseerWasmGate",
        ),

        ANTIMATTER_DIMENSIONS_SAVE_EXPORT(
            titleName = "Antimatter Dimensions (and any title using <a href=data:... download> for save export)",
            appIds = listOf("STEAM_1399720"),
            reason = "WebView swallows data:-URL anchor downloads without an explicit DownloadListener. " +
                "we decode the data URL and write to public Downloads/. behavior is generic but AD is " +
                "the live test case.",
            impl = "WebViewSetup (webView.setDownloadListener)",
        ),

        ;
    }
}
