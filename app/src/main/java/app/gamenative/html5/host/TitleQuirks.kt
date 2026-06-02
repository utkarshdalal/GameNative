package app.gamenative.html5.host

// single grep target for "which titles have special-cased code in the html5 runtime".
// every per-title hack registers an entry here AND lives in a dedicated file (or named
// remember{} block in WebViewScreen). adding a new title-specific behavior means:
//   1. add an entry to KnownTitleQuirks below with a one-line reason
//   2. land the impl in its own file (preferred) or as a labeled remember{} block
//   3. cross-reference both ways so a future grep for the title name lands either way
//
// titles whose behavior is "engine-generic that one title happens to need" do NOT belong
// here -- those belong in the pack JSON or the pack shim. (e.g. RMMZ TextPicture bitmap cache,
// pack:nwjs Impact-engine `dataPath` truthiness -- both pack-level, not title-level.)
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
            impl = "OmoriDecryptContext + WebViewScreen.omoriContext/decryptContext/nwArgvJson",
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
            impl = "WebViewScreen.setDownloadListener (in WebView(context).apply{})",
        ),

        ;
    }
}
