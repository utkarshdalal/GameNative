package app.gamenative.ui.enums

enum class AppOptionMenuType(val text: String) {
    StorePage("Open store page"),
    CreateShortcut("Create shortcut"),
    ExportFrontend("Export for frontend"),
    RunContainer("Open container"),
    EditContainer("Edit container"),
    ResetToDefaults("Reset container"),
    GetSupport("Get support"),
    SubmitFeedback("Submit feedback"),
    ResetDrm("Reset DRM"),
    UseKnownConfig("Use known config"),
    ImportConfig("Import config"),
    ExportConfig("Export config"),
    Uninstall("Uninstall"),
    VerifyFiles("Verify files"),
    Update("Update"),
    MoveToExternalStorage("Move to external storage"),
    MoveToInternalStorage("Move to internal storage"),
    ForceCloudSync("Force cloud sync"),
    ForceDownloadRemote("Force download remote saves"),
    ForceUploadLocal("Force upload local saves"),
    FetchSteamGridDBImages("Fetch game images"),
    TestGraphics("Test graphics"),
    ManageGameContent("Manage DLC");
}
