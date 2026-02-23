package app.gamenative.gamefixes

val GOG_Fix_1998527297: GameFix = RegistryKeyFix(
    registryKey = "Software\\Wow6432Node\\Bethesda Softworks\\Fallout4",
    defaultValues = mapOf(
        "InstalledPath" to INSTALL_PATH_PLACEHOLDER,
    ),
)
