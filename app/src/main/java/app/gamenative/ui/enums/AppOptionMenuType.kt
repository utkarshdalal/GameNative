package app.gamenative.ui.enums

enum class AppOptionMenuType(val text: String) {
    StorePage("Open store page"),
    RunContainer("Open container"),
    EditContainer("Edit container"),
    ResetDrm("Reset DRM"),
    Uninstall("Uninstall"),
    VerifyFiles("Verify files"),
    Update("Update"),
    MoveToExternalStorage("Move to external storage"),
    MoveToInternalStorage("Move to internal storage"),
    ForceCloudSync("Force cloud sync"),
    ForceDownloadRemote("Force download remote saves"),
    ForceUploadLocal("Force upload local saves")
}
