package app.gamenative.mods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FomodAutoSelectorTest {
    @Test
    fun selectDeterministic_selectsSingleRequiredOption() {
        val installer = FomodInstaller(
            moduleName = "Test",
            requiredFiles = emptyList(),
            steps = listOf(
                FomodStep(
                    name = "Install",
                    groups = listOf(
                        FomodGroup(
                            name = "Main",
                            type = FomodGroupType.SELECT_EXACTLY_ONE,
                            plugins = listOf(
                                FomodPlugin(
                                    name = "Required",
                                    description = "",
                                    imagePath = "",
                                    type = FomodPluginType.REQUIRED,
                                    files = listOf(FomodFileMapping("Data/meshes/a.nif", "meshes/a.nif", 0, false)),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val result = FomodAutoSelector.selectDeterministic("install", installer)

        assertNotNull(result)
        assertEquals(1, result!!.recipes.size)
        assertEquals("Data/meshes/a.nif", result.recipes.single().sourceSubpath)
    }

    @Test
    fun selectDeterministic_rejectsAmbiguousExactlyOneGroup() {
        val installer = FomodInstaller(
            moduleName = "Test",
            requiredFiles = emptyList(),
            steps = listOf(
                FomodStep(
                    name = "Install",
                    groups = listOf(
                        FomodGroup(
                            name = "Choice",
                            type = FomodGroupType.SELECT_EXACTLY_ONE,
                            plugins = listOf(
                                FomodPlugin("A", "", "", FomodPluginType.OPTIONAL, listOf(FomodFileMapping("A", "", 0, true))),
                                FomodPlugin("B", "", "", FomodPluginType.OPTIONAL, listOf(FomodFileMapping("B", "", 0, true))),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertNull(FomodAutoSelector.selectDeterministic("install", installer))
    }

    @Test
    fun selectDeterministic_selectsSingleAtLeastOneOption() {
        val installer = FomodInstaller(
            moduleName = "Test",
            requiredFiles = emptyList(),
            steps = listOf(
                FomodStep(
                    name = "Install",
                    groups = listOf(
                        FomodGroup(
                            name = "Optional files",
                            type = FomodGroupType.SELECT_AT_LEAST_ONE,
                            plugins = listOf(
                                FomodPlugin("Only", "", "", FomodPluginType.OPTIONAL, listOf(FomodFileMapping("Only", "", 0, true))),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val result = FomodAutoSelector.selectDeterministic("install", installer)

        assertNotNull(result)
        assertEquals(listOf("Only"), result!!.recipes.map { it.sourceSubpath })
    }
}
