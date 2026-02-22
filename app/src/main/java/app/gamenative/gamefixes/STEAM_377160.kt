package app.gamenative.gamefixes

val STEAM_Fix_377160: GameFix = RegistryKeyFix(
    registryKey = "Software\\Wow6432Node\\Bethesda Softworks\\Fallout4",
    defaultValues = mapOf(
        "InstalledPath" to INSTALL_PATH_PLACEHOLDER,
    ),
)
