package app.gamenative.gamefixes

val GOG_Fix_1454587428: GameFix = RegistryKeyFix(
    registryKey = "Software\\Wow6432Node\\Bethesda Softworks\\FalloutNV",
    defaultValues = mapOf(
        "Installed Path" to INSTALL_PATH_PLACEHOLDER,
    ),
)
