package app.gamenative.utils

import app.gamenative.enums.PathType
import `in`.dragonbra.javasteam.types.KeyValue
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyValueUtilsTest {

    /**
     * Blue Revolver (App ID 439490) has a Windows rootoverride that remaps `gameinstall`
     * to `WinAppDataRoaming` with an empty addpath. All three savefiles use `gameinstall`
     * as root, so they should all be remapped; paths should be unchanged.
     */
    @Test
    fun blueRevolverWindowsRootOverrideRemapsGameInstallToWinAppDataRoaming() {
        val kvString = """
            "appinfo"
            {
                "appid"     "439490"
                "ufs"
                {
                    "quota"         "1000000000"
                    "maxnumfiles"   "1000"
                    "savefiles"
                    {
                        "1"
                        {
                            "root"      "gameinstall"
                            "path"      "blue-revolver-final"
                            "pattern"   "save2.lua"
                        }
                        "2"
                        {
                            "root"      "gameinstall"
                            "path"      "blue-revolver-double-action"
                            "pattern"   "brda_save.sav"
                        }
                        "3"
                        {
                            "root"      "gameinstall"
                            "path"      "love/blue-revolver-final"
                            "pattern"   "save2.lua"
                            "platforms"
                            {
                                "1"     "Linux"
                            }
                        }
                    }
                    "rootoverrides"
                    {
                        "0"
                        {
                            "root"          "gameinstall"
                            "os"            "Windows"
                            "oscompare"     "="
                            "useinstead"    "WinAppDataRoaming"
                            "addpath"       ""
                        }
                        "1"
                        {
                            "root"          "gameinstall"
                            "os"            "MacOS"
                            "oscompare"     "="
                            "useinstead"    "MacAppSupport"
                            "addpath"       ""
                        }
                        "2"
                        {
                            "root"          "gameinstall"
                            "os"            "Linux"
                            "oscompare"     "="
                            "useinstead"    "LinuxHome"
                            "addpath"       ".local/share/"
                        }
                    }
                }
            }
        """.trimIndent()
        val kv = KeyValue.loadFromString(kvString)!!
        val steamApp = kv.generateSteamApp()

        val patterns = steamApp.ufs.saveFilePatterns
        assertEquals(2, patterns.size)

        assertEquals(PathType.WinAppDataRoaming, patterns[0].root)
        assertEquals("blue-revolver-final", patterns[0].path)
        assertEquals("save2.lua", patterns[0].pattern)
        assertEquals(0, patterns[0].recursive)
        assertEquals(PathType.GameInstall, patterns[0].uploadRoot)

        assertEquals(PathType.WinAppDataRoaming, patterns[1].root)
        assertEquals("blue-revolver-double-action", patterns[1].path)
        assertEquals("brda_save.sav", patterns[1].pattern)
        assertEquals(0, patterns[1].recursive)
        assertEquals(PathType.GameInstall, patterns[1].uploadRoot)
    }

    /**
     * A Windows rootoverride with a non-empty addpath should prepend the addpath to the original
     * save file path (e.g. addpath="AppData/Roaming" + path="MyGame" → "AppData/Roaming/MyGame").
     */
    @Test
    fun windowsRootOverrideWithNonEmptyAddPathPrependsToSavePath() {
        val kvString = """
            "appinfo"
            {
                "appid"     "123456"
                "ufs"
                {
                    "quota"         "1000000000"
                    "maxnumfiles"   "10"
                    "savefiles"
                    {
                        "0"
                        {
                            "root"      "gameinstall"
                            "path"      "saves"
                            "pattern"   "*.sav"
                        }
                    }
                    "rootoverrides"
                    {
                        "0"
                        {
                            "root"          "gameinstall"
                            "os"            "Windows"
                            "oscompare"     "="
                            "useinstead"    "WinAppDataRoaming"
                            "addpath"       "MyGame"
                        }
                    }
                }
            }
        """.trimIndent()
        val kv = KeyValue.loadFromString(kvString)!!
        val steamApp = kv.generateSteamApp()

        val patterns = steamApp.ufs.saveFilePatterns
        assertEquals(1, patterns.size)
        assertEquals(PathType.WinAppDataRoaming, patterns[0].root)
        assertEquals("MyGame/saves", patterns[0].path)
        assertEquals("*.sav", patterns[0].pattern)
        assertEquals(0, patterns[0].recursive)
        assertEquals(PathType.GameInstall, patterns[0].uploadRoot)
    }

    /**
     * A Windows rootoverride whose addpath has a trailing slash should not produce a double
     * separator (e.g. "MyGame/" + "saves" → "MyGame/saves", not "MyGame//saves").
     */
    @Test
    fun windowsRootOverrideWithTrailingSlashAddPathDoesNotDuplicateSeparator() {
        val kvString = """
            "appinfo"
            {
                "appid"     "123457"
                "ufs"
                {
                    "quota"         "1000000000"
                    "maxnumfiles"   "10"
                    "savefiles"
                    {
                        "0"
                        {
                            "root"      "gameinstall"
                            "path"      "saves"
                            "pattern"   "*.sav"
                        }
                    }
                    "rootoverrides"
                    {
                        "0"
                        {
                            "root"          "gameinstall"
                            "os"            "Windows"
                            "oscompare"     "="
                            "useinstead"    "WinAppDataRoaming"
                            "addpath"       "MyGame/"
                        }
                    }
                }
            }
        """.trimIndent()
        val kv = KeyValue.loadFromString(kvString)!!
        val steamApp = kv.generateSteamApp()

        val patterns = steamApp.ufs.saveFilePatterns
        assertEquals(1, patterns.size)
        assertEquals(PathType.WinAppDataRoaming, patterns[0].root)
        assertEquals("MyGame/saves", patterns[0].path)
        assertEquals("*.sav", patterns[0].pattern)
        assertEquals(0, patterns[0].recursive)
        assertEquals(PathType.GameInstall, patterns[0].uploadRoot)
    }

    /**
     * When a Windows rootoverride prepends an addpath, uploadPath should preserve the original
     * save path (without addpath) so cloud keys remain aligned with what other Steam clients use.
     */
    @Test
    fun windowsRootOverrideWithAddPathPreservesUploadPath() {
        val kvString = """
            "appinfo"
            {
                "appid"     "123459"
                "ufs"
                {
                    "quota"         "1000000000"
                    "maxnumfiles"   "10"
                    "savefiles"
                    {
                        "0"
                        {
                            "root"      "gameinstall"
                            "path"      "saves"
                            "pattern"   "*.sav"
                        }
                    }
                    "rootoverrides"
                    {
                        "0"
                        {
                            "root"          "gameinstall"
                            "os"            "Windows"
                            "oscompare"     "="
                            "useinstead"    "WinAppDataRoaming"
                            "addpath"       "MyGame"
                        }
                    }
                }
            }
        """.trimIndent()
        val kv = KeyValue.loadFromString(kvString)!!
        val steamApp = kv.generateSteamApp()

        val patterns = steamApp.ufs.saveFilePatterns
        assertEquals(1, patterns.size)
        // Local resolution uses remapped root + addPath-prefixed path
        assertEquals(PathType.WinAppDataRoaming, patterns[0].root)
        assertEquals("MyGame/saves", patterns[0].path)
        // Cloud key must use original root + original path (no addpath)
        assertEquals(PathType.GameInstall, patterns[0].uploadRoot)
        assertEquals("saves", patterns[0].uploadPath)
    }

    /**
     * A savefile restricted to Linux via a `platforms` block should be excluded on Windows.
     * A savefile with no `platforms` block should always be included.
     */
    @Test
    fun saveFileWithNonWindowsPlatformIsExcluded() {
        val kvString = """
            "appinfo"
            {
                "appid"     "111111"
                "ufs"
                {
                    "quota"         "1000000000"
                    "maxnumfiles"   "10"
                    "savefiles"
                    {
                        "0"
                        {
                            "root"      "WinMyDocuments"
                            "path"      "saves"
                            "pattern"   "*.sav"
                            "platforms"
                            {
                                "1"     "Linux"
                            }
                        }
                        "1"
                        {
                            "root"      "WinMyDocuments"
                            "path"      "saves"
                            "pattern"   "*.bak"
                        }
                    }
                }
            }
        """.trimIndent()
        val kv = KeyValue.loadFromString(kvString)!!
        val steamApp = kv.generateSteamApp()

        val patterns = steamApp.ufs.saveFilePatterns
        assertEquals(1, patterns.size)
        assertEquals(PathType.WinMyDocuments, patterns[0].root)
        assertEquals("*.bak", patterns[0].pattern)
        assertEquals(PathType.WinMyDocuments, patterns[0].uploadRoot)
    }

    /**
     * Savefiles with an explicit `platforms { "Windows" }` block (Noita-style) should be included.
     */
    @Test
    fun noitaExplicitWindowsPlatformIsIncluded() {
        val kvString = """
            "appinfo"
            {
                "appid"     "881100"
                "ufs"
                {
                    "quota"         "1000000000"
                    "maxnumfiles"   "10"
                    "savefiles"
                    {
                        "0"
                        {
                            "root"      "WinAppDataLocalLow"
                            "path"      "Nolla_Games_Noita"
                            "pattern"   "save00/world/*.bin"
                            "recursive" "1"
                            "platforms"
                            {
                                "1"     "Windows"
                            }
                        }
                        "1"
                        {
                            "root"      "WinAppDataLocalLow"
                            "path"      "Nolla_Games_Noita"
                            "pattern"   "save00/*.xml"
                            "recursive" "0"
                            "platforms"
                            {
                                "1"     "Windows"
                            }
                        }
                    }
                }
            }
        """.trimIndent()
        val kv = KeyValue.loadFromString(kvString)!!
        val steamApp = kv.generateSteamApp()

        val patterns = steamApp.ufs.saveFilePatterns
        assertEquals(2, patterns.size)
        assertEquals(PathType.WinAppDataLocalLow, patterns[0].root)
        assertEquals("Nolla_Games_Noita", patterns[0].path)
        assertEquals("save00/world/*.bin", patterns[0].pattern)
        assertEquals(1, patterns[0].recursive)
        assertEquals(PathType.WinAppDataLocalLow, patterns[0].uploadRoot)
        assertEquals(PathType.WinAppDataLocalLow, patterns[1].root)
        assertEquals("Nolla_Games_Noita", patterns[1].path)
        assertEquals("save00/*.xml", patterns[1].pattern)
        assertEquals(0, patterns[1].recursive)
        assertEquals(PathType.WinAppDataLocalLow, patterns[1].uploadRoot)
    }

    /**
     * Cult of the Lamb uses a Windows rootoverride with `pathtransforms` to remap the save
     * path from `saves` to `Massive Monster/Cult Of The Lamb/saves`.
     */
    @Test
    fun cultOfTheLambPathtransformsRemapsPath() {
        val kvString = """
            "appinfo"
            {
                "appid"     "1313140"
                "ufs"
                {
                    "quota"         "1048576000"
                    "maxnumfiles"   "10000"
                    "savefiles"
                    {
                        "0"
                        {
                            "root"      "gameinstall"
                            "path"      "saves"
                            "pattern"   "*.json"
                            "recursive" "1"
                        }
                        "1"
                        {
                            "root"      "gameinstall"
                            "path"      "saves"
                            "pattern"   "*.mp"
                            "recursive" "1"
                        }
                    }
                    "rootoverrides"
                    {
                        "0"
                        {
                            "root"          "gameinstall"
                            "os"            "Windows"
                            "oscompare"     "="
                            "useinstead"    "WinAppDataLocalLow"
                            "pathtransforms"
                            {
                                "0"
                                {
                                    "find"      "saves"
                                    "replace"   "Massive Monster/Cult Of The Lamb/saves"
                                }
                            }
                        }
                        "1"
                        {
                            "root"          "gameinstall"
                            "os"            "MacOS"
                            "oscompare"     "="
                            "useinstead"    "MacAppSupport"
                            "pathtransforms"
                            {
                                "0"
                                {
                                    "find"      "saves"
                                    "replace"   "Massive Monster/Cult Of The Lamb/saves"
                                }
                            }
                        }
                    }
                }
            }
        """.trimIndent()
        val kv = KeyValue.loadFromString(kvString)!!
        val steamApp = kv.generateSteamApp()

        val patterns = steamApp.ufs.saveFilePatterns
        assertEquals(2, patterns.size)

        assertEquals(PathType.WinAppDataLocalLow, patterns[0].root)
        assertEquals("Massive Monster/Cult Of The Lamb/saves", patterns[0].path)
        assertEquals("*.json", patterns[0].pattern)
        assertEquals(1, patterns[0].recursive)
        assertEquals(PathType.GameInstall, patterns[0].uploadRoot)

        assertEquals(PathType.WinAppDataLocalLow, patterns[1].root)
        assertEquals("Massive Monster/Cult Of The Lamb/saves", patterns[1].path)
        assertEquals("*.mp", patterns[1].pattern)
        assertEquals(1, patterns[1].recursive)
        assertEquals(PathType.GameInstall, patterns[1].uploadRoot)
    }

    /**
     * Hades has only a MacOS rootoverride. Windows save paths should be left untouched.
     */
    @Test
    fun hadesNonWindowsRootOverrideDoesNotAffectSaveFilePaths() {
        val kvString = """
            "appinfo"
            {
                "appid"     "1145360"
                "ufs"
                {
                    "quota"         "62914560"
                    "maxnumfiles"   "20"
                    "savefiles"
                    {
                        "0"
                        {
                            "root"      "WinMyDocuments"
                            "path"      "Saved Games/Hades"
                            "pattern"   "Profile*.sav"
                        }
                    }
                    "rootoverrides"
                    {
                        "1"
                        {
                            "root"          "WinMyDocuments"
                            "os"            "MacOS"
                            "oscompare"     "="
                            "useinstead"    "MacAppSupport"
                        }
                    }
                }
            }
        """.trimIndent()

        val kv = KeyValue.loadFromString(kvString)!!
        val steamApp = kv.generateSteamApp()

        val patterns = steamApp.ufs.saveFilePatterns
        assertEquals(1, patterns.size)
        assertEquals(PathType.WinMyDocuments, patterns[0].root)
        assertEquals("Saved Games/Hades", patterns[0].path)
        assertEquals("Profile*.sav", patterns[0].pattern)
        assertEquals(0, patterns[0].recursive)
        assertEquals(PathType.WinMyDocuments, patterns[0].uploadRoot)
    }

    /**
     * A Windows rootoverride whose original path starts with a slash should not produce a double
     * separator (e.g. addpath="MyGame" + path="/saves" → "MyGame/saves", not "MyGame//saves").
     */
    @Test
    fun windowsRootOverrideWithLeadingSlashPathDoesNotDuplicateSeparator() {
        val kvString = """
            "appinfo"
            {
                "appid"     "123458"
                "ufs"
                {
                    "quota"         "1000000000"
                    "maxnumfiles"   "10"
                    "savefiles"
                    {
                        "0"
                        {
                            "root"      "gameinstall"
                            "path"      "/saves"
                            "pattern"   "*.sav"
                        }
                    }
                    "rootoverrides"
                    {
                        "0"
                        {
                            "root"          "gameinstall"
                            "os"            "Windows"
                            "oscompare"     "="
                            "useinstead"    "WinAppDataRoaming"
                            "addpath"       "MyGame"
                        }
                    }
                }
            }
        """.trimIndent()
        val kv = KeyValue.loadFromString(kvString)!!
        val steamApp = kv.generateSteamApp()

        val patterns = steamApp.ufs.saveFilePatterns
        assertEquals(1, patterns.size)
        assertEquals(PathType.WinAppDataRoaming, patterns[0].root)
        assertEquals("MyGame/saves", patterns[0].path)
        assertEquals("*.sav", patterns[0].pattern)
        assertEquals(0, patterns[0].recursive)
        assertEquals(PathType.GameInstall, patterns[0].uploadRoot)
    }

    /**
     * The Roottrees are Dead (App ID 2754380) has a Windows rootoverride with an empty savefile
     * path and a backslash-separated addpath ("Godot\app_userdata\The Roottrees are Dead").
     * Two bugs this exercises:
     *   1. Empty originalPath must not produce a trailing slash — the addpath alone is the full
     *      directory, so joining "" would yield "Godot/.../The Roottrees are Dead/" which breaks
     *      file-system lookups.
     *   2. Backslashes in addpath must be normalized to '/' — Steam's Windows manifest uses '\',
     *      which is valid on Windows but invalid as a path separator under Wine on Android.
     * uploadPath must remain "" (the original manifest value) so cloud keys stay aligned with
     * what other Steam clients expect.
     */
    @Test
    fun roottreesAreDeadBackslashAddPathWithEmptyOriginalPathProducesCleanPath() {
        val kvString = """
            "appinfo"
            {
                "appid"     "2754380"
                "ufs"
                {
                    "quota"         "5242880"
                    "maxnumfiles"   "20"
                    "savefiles"
                    {
                        "0"
                        {
                            "root"      "gameinstall"
                            "path"      ""
                            "pattern"   "*.save"
                        }
                    }
                    "rootoverrides"
                    {
                        "0"
                        {
                            "root"          "gameinstall"
                            "os"            "Windows"
                            "oscompare"     "="
                            "useinstead"    "WinAppDataRoaming"
                            "addpath"       "Godot\\app_userdata\\The Roottrees are Dead"
                        }
                        "1"
                        {
                            "root"          "gameinstall"
                            "os"            "MacOS"
                            "oscompare"     "="
                            "useinstead"    "MacAppSupport"
                            "addpath"       "Godot/app_userdata/The Roottrees are Dead"
                        }
                        "2"
                        {
                            "root"          "gameinstall"
                            "os"            "Linux"
                            "oscompare"     "="
                            "useinstead"    "LinuxHome"
                            "addpath"       ".local/share/godot/app_userdata/The Roottrees are Dead"
                        }
                    }
                }
            }
        """.trimIndent()
        val kv = KeyValue.loadFromString(kvString)!!
        val steamApp = kv.generateSteamApp()

        val patterns = steamApp.ufs.saveFilePatterns
        assertEquals(1, patterns.size)
        assertEquals(PathType.WinAppDataRoaming, patterns[0].root)
        assertEquals("Godot/app_userdata/The Roottrees are Dead", patterns[0].path)
        assertEquals("*.save", patterns[0].pattern)
        assertEquals(0, patterns[0].recursive)
        assertEquals(PathType.GameInstall, patterns[0].uploadRoot)
        assertEquals("", patterns[0].uploadPath)
    }

    /**
     * Stellar Blade (App ID 3489700) has a save pattern with `root: WindowsHome`, which is the
     * Windows user home directory (C:\users\xuser\ in Wine). It must be recognized as PathType.Root
     * and pass the isWindows filter so the pattern is not silently dropped.
     */
    @Test
    fun stellarBladeWindowsHomeRootIsRecognizedAndPassesIsWindowsFilter() {
        val kvString = """
            "appinfo"
            {
                "appid"     "3489700"
                "ufs"
                {
                    "quota"         "600000000"
                    "maxnumfiles"   "20"
                    "savefiles"
                    {
                        "0"
                        {
                            "root"      "WindowsHome"
                            "path"      "Documents/StellarBlade/{64BitSteamID}"
                            "pattern"   "*.sav"
                        }
                    }
                }
            }
        """.trimIndent()
        val kv = KeyValue.loadFromString(kvString)!!
        val steamApp = kv.generateSteamApp()

        val patterns = steamApp.ufs.saveFilePatterns
        assertEquals(1, patterns.size)
        assertEquals(PathType.Root, patterns[0].root)
        assertEquals(true, patterns[0].root.isWindows)
        assertEquals("Documents/StellarBlade/{64BitSteamID}", patterns[0].path)
        assertEquals("*.sav", patterns[0].pattern)
        assertEquals(0, patterns[0].recursive)
        assertEquals(PathType.Root, patterns[0].uploadRoot)
    }

    /**
     * Sonic Mania (App ID 584400) has a save pattern with `root: SteamCloudDocuments`, which is
     * Steam's name for the user's Documents folder (WinMyDocuments in Wine). It must be recognized
     * and pass the isWindows filter so the pattern is not silently dropped.
     */
    @Test
    fun sonicManiaSteamCloudDocumentsRootIsRecognizedAsWinMyDocuments() {
        val kvString = """
            "appinfo"
            {
                "appid"     "584400"
                "ufs"
                {
                    "quota"         "67108864"
                    "maxnumfiles"   "64"
                    "savefiles"
                    {
                        "0"
                        {
                            "root"      "SteamCloudDocuments"
                            "path"      "SavesDir"
                            "pattern"   "*.sav"
                        }
                    }
                }
            }
        """.trimIndent()
        val kv = KeyValue.loadFromString(kvString)!!
        val steamApp = kv.generateSteamApp()

        val patterns = steamApp.ufs.saveFilePatterns
        assertEquals(1, patterns.size)
        assertEquals(PathType.WinMyDocuments, patterns[0].root)
        assertEquals(true, patterns[0].root.isWindows)
        assertEquals("SavesDir", patterns[0].path)
        assertEquals("*.sav", patterns[0].pattern)
        assertEquals(PathType.WinMyDocuments, patterns[0].uploadRoot)
    }

    /**
     * Spiritfarer (App ID 972660) has `path: .` with a Windows rootoverride that adds
     * `addpath: Thunder Lotus Games/Spiritfarer`. The dot means "no subdirectory" and must not
     * be appended to the addpath (producing "Thunder Lotus Games/Spiritfarer/."), nor stored as
     * uploadPath (producing cloud key "%GameInstall%.") — both break download path resolution.
     */
    @Test
    fun spiritfarerDotPathWithAddpathIsNormalizedToEmpty() {
        val kvString = """
            "appinfo"
            {
                "appid"     "972660"
                "ufs"
                {
                    "quota"         "100000000"
                    "maxnumfiles"   "20"
                    "savefiles"
                    {
                        "0"
                        {
                            "root"      "gameinstall"
                            "path"      "."
                            "pattern"   "*.sav"
                        }
                    }
                    "rootoverrides"
                    {
                        "0"
                        {
                            "root"          "gameinstall"
                            "os"            "Windows"
                            "oscompare"     "="
                            "useinstead"    "WinAppDataLocalLow"
                            "addpath"       "Thunder Lotus Games/Spiritfarer"
                        }
                    }
                }
            }
        """.trimIndent()
        val kv = KeyValue.loadFromString(kvString)!!
        val steamApp = kv.generateSteamApp()

        val patterns = steamApp.ufs.saveFilePatterns
        assertEquals(1, patterns.size)
        assertEquals(PathType.WinAppDataLocalLow, patterns[0].root)
        assertEquals("Thunder Lotus Games/Spiritfarer", patterns[0].path)
        assertEquals("*.sav", patterns[0].pattern)
        assertEquals(PathType.GameInstall, patterns[0].uploadRoot)
        assertEquals("", patterns[0].uploadPath)
    }

    /**
     * CrossCode (App ID 368340) has `path: .` with a Windows rootoverride that adds
     * `addpath: CrossCode`. Same class of bug as Spiritfarer — dot must not be appended to
     * the addpath or stored as uploadPath.
     */
    @Test
    fun crossCodeDotPathWithAddpathIsNormalizedToEmpty() {
        val kvString = """
            "appinfo"
            {
                "appid"     "368340"
                "ufs"
                {
                    "quota"         "100000000"
                    "maxnumfiles"   "5"
                    "savefiles"
                    {
                        "0"
                        {
                            "root"      "WinAppDataLocal"
                            "path"      "."
                            "pattern"   "cc.sav*"
                        }
                    }
                    "rootoverrides"
                    {
                        "0"
                        {
                            "root"          "WinAppDataLocal"
                            "os"            "Windows"
                            "oscompare"     "="
                            "useinstead"    "WinAppDataLocal"
                            "addpath"       "CrossCode"
                        }
                    }
                }
            }
        """.trimIndent()
        val kv = KeyValue.loadFromString(kvString)!!
        val steamApp = kv.generateSteamApp()

        val patterns = steamApp.ufs.saveFilePatterns
        assertEquals(1, patterns.size)
        assertEquals(PathType.WinAppDataLocal, patterns[0].root)
        assertEquals("CrossCode", patterns[0].path)
        assertEquals("cc.sav*", patterns[0].pattern)
        assertEquals(PathType.WinAppDataLocal, patterns[0].uploadRoot)
        assertEquals("", patterns[0].uploadPath)
    }

    /**
     * Danganronpa 2: Goodbye Despair (App ID 413420) has savefiles with `path: /` (a lone
     * forward-slash) and a Windows rootoverride mapping gameinstall → WinMyDocuments with
     * addpath="My Games/Danganronpa2/". The "/" is semantically identical to "" (root of this
     * path type) and must be normalised to empty so that:
     *   1. The computed local path is "My Games/Danganronpa2" (no spurious trailing slash that
     *      would break file-system lookups).
     *   2. uploadPath is "" (not "/"), keeping the cloud key as "%GameInstall%" instead of
     *      "%GameInstall%/" which would cause a lookup miss in cloudPrefixToLocalPath.
     */
    @Test
    fun danganronpa2SlashPathWithWindowsRootOverrideIsNormalizedToEmpty() {
        val kvString = """
            "appinfo"
            {
                "appid"     "413420"
                "ufs"
                {
                    "quota"         "1000000000"
                    "maxnumfiles"   "5"
                    "savefiles"
                    {
                        "0"
                        {
                            "root"      "gameinstall"
                            "path"      "/"
                            "pattern"   "savedata.vfs"
                        }
                        "1"
                        {
                            "root"      "gameinstall"
                            "path"      "/"
                            "pattern"   "savedata_jp.vfs"
                        }
                        "2"
                        {
                            "root"      "gameinstall"
                            "path"      "/"
                            "pattern"   "savedata_ch.vfs"
                        }
                    }
                    "rootoverrides"
                    {
                        "0"
                        {
                            "root"          "gameinstall"
                            "os"            "Windows"
                            "oscompare"     "="
                            "useinstead"    "WinMyDocuments"
                            "addpath"       "My Games/Danganronpa2/"
                        }
                        "1"
                        {
                            "root"          "gameinstall"
                            "os"            "MacOS"
                            "oscompare"     "="
                            "useinstead"    "MacAppSupport"
                            "addpath"       "Danganronpa2"
                        }
                        "2"
                        {
                            "root"          "gameinstall"
                            "os"            "Linux"
                            "oscompare"     "="
                            "useinstead"    "LinuxXdgDataHome"
                            "addpath"       "/Danganronpa2Save"
                        }
                    }
                }
            }
        """.trimIndent()
        val kv = KeyValue.loadFromString(kvString)!!
        val steamApp = kv.generateSteamApp()

        val patterns = steamApp.ufs.saveFilePatterns
        assertEquals(3, patterns.size)

        // All three patterns share the same resolved root and path
        patterns.forEach { p ->
            assertEquals(PathType.WinMyDocuments, p.root)
            assertEquals("My Games/Danganronpa2", p.path)  // no trailing slash
            assertEquals(PathType.GameInstall, p.uploadRoot)
            assertEquals("", p.uploadPath)  // "/" normalised to "" → cloud key has no trailing slash
        }
        assertEquals("savedata.vfs", patterns[0].pattern)
        assertEquals("savedata_jp.vfs", patterns[1].pattern)
        assertEquals("savedata_ch.vfs", patterns[2].pattern)
    }

    @Test
    fun generateSteamAppStampsCurrentUfsParseVersion() {
        val kvString = """
            "appinfo"
            {
                "appid"     "439490"
            }
        """.trimIndent()

        val kv = KeyValue.loadFromString(kvString)!!
        val steamApp = kv.generateSteamApp()

        assertEquals(CURRENT_UFS_PARSE_VERSION, steamApp.ufsParseVersion)
    }

    @Test
    fun winProgramDataRootIsRecognized() {
        val kvString = """
            "appinfo"
            {
                "appid"     "99999"
                "ufs"
                {
                    "quota"         "10000000"
                    "maxnumfiles"   "10"
                    "savefiles"
                    {
                        "0"
                        {
                            "root"      "winprogramdata"
                            "path"      "MyPublisher/MyGame"
                            "pattern"   "*.sav"
                        }
                    }
                }
            }
        """.trimIndent()

        val kv = KeyValue.loadFromString(kvString)!!
        val steamApp = kv.generateSteamApp()

        val patterns = steamApp.ufs.saveFilePatterns
        assertEquals(1, patterns.size)
        assertEquals(PathType.WinProgramData, patterns[0].root)
        assertEquals(true, patterns[0].root.isWindows)
        assertEquals("MyPublisher/MyGame", patterns[0].path)
        assertEquals("*.sav", patterns[0].pattern)
        assertEquals(PathType.WinProgramData, patterns[0].uploadRoot)
    }

    @Test
    fun steamUserBaseStorageRootIsRecognizedAsSteamUserData() {
        val kvString = """
            "appinfo"
            {
                "appid"     "88888"
                "ufs"
                {
                    "quota"         "10000000"
                    "maxnumfiles"   "10"
                    "savefiles"
                    {
                        "0"
                        {
                            "root"      "SteamUserBaseStorage"
                            "path"      "saves"
                            "pattern"   "*.dat"
                        }
                    }
                }
            }
        """.trimIndent()

        val kv = KeyValue.loadFromString(kvString)!!
        val steamApp = kv.generateSteamApp()

        val patterns = steamApp.ufs.saveFilePatterns
        assertEquals(1, patterns.size)
        assertEquals(PathType.SteamUserData, patterns[0].root)
        assertEquals(true, patterns[0].root.isWindows)
        assertEquals("saves", patterns[0].path)
        assertEquals("*.dat", patterns[0].pattern)
        assertEquals(PathType.SteamUserData, patterns[0].uploadRoot)
    }

    /**
     * A rootoverride using `oslist: "windows,linux"` instead of `os: "Windows"` must still
     * be applied. Previously this was silently skipped because only the `os` field was checked.
     */
    @Test
    fun rootOverrideWithOslistWindowsIsApplied() {
        val kvString = """
            "appinfo"
            {
                "appid"     "77777"
                "ufs"
                {
                    "quota"         "10000000"
                    "maxnumfiles"   "10"
                    "savefiles"
                    {
                        "0"
                        {
                            "root"      "gameinstall"
                            "path"      "saves"
                            "pattern"   "*.sav"
                        }
                    }
                    "rootoverrides"
                    {
                        "0"
                        {
                            "root"          "gameinstall"
                            "oslist"        "windows,linux"
                            "oscompare"     "="
                            "useinstead"    "WinAppDataRoaming"
                            "addpath"       "MyGame"
                        }
                    }
                }
            }
        """.trimIndent()

        val kv = KeyValue.loadFromString(kvString)!!
        val steamApp = kv.generateSteamApp()

        val patterns = steamApp.ufs.saveFilePatterns
        assertEquals(1, patterns.size)
        assertEquals(PathType.WinAppDataRoaming, patterns[0].root)
        assertEquals("MyGame/saves", patterns[0].path)
        assertEquals("*.sav", patterns[0].pattern)
        assertEquals(PathType.GameInstall, patterns[0].uploadRoot)
        assertEquals("saves", patterns[0].uploadPath)
    }

    @Test
    fun rootOverrideWithOslistOnlyLinuxIsNotApplied() {
        val kvString = """
            "appinfo"
            {
                "appid"     "66666"
                "ufs"
                {
                    "quota"         "10000000"
                    "maxnumfiles"   "10"
                    "savefiles"
                    {
                        "0"
                        {
                            "root"      "WinMyDocuments"
                            "path"      "saves"
                            "pattern"   "*.sav"
                        }
                    }
                    "rootoverrides"
                    {
                        "0"
                        {
                            "root"          "WinMyDocuments"
                            "oslist"        "linux"
                            "oscompare"     "="
                            "useinstead"    "LinuxHome"
                            "addpath"       ".local/share/mygame"
                        }
                    }
                }
            }
        """.trimIndent()

        val kv = KeyValue.loadFromString(kvString)!!
        val steamApp = kv.generateSteamApp()

        val patterns = steamApp.ufs.saveFilePatterns
        assertEquals(1, patterns.size)
        assertEquals(PathType.WinMyDocuments, patterns[0].root)
        assertEquals("saves", patterns[0].path)
        assertEquals("*.sav", patterns[0].pattern)
        assertEquals(PathType.WinMyDocuments, patterns[0].uploadRoot)
    }

}
