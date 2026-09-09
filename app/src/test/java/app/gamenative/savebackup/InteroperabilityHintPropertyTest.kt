package app.gamenative.savebackup

import app.gamenative.enums.PathType
import com.pholser.junit.quickcheck.Property
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Property test for [InteroperabilityHintClassifier.classify] — game-save-backup Task 12.3
 * (Property 14).
 *
 * Runs under the existing JUnit 4 setup via junit-quickcheck's [JUnitQuickcheck] runner (no JUnit
 * Platform migration, no Robolectric). [InteroperabilityHintClassifier.classify] is a pure function
 * with no Android dependency, so it is exercised directly with no fakes or mocks.
 *
 * junit-quickcheck cannot natively generate a [PathType] enum, so each trial is driven from a
 * generated `Int` selector mapped onto `PathType.values()` via `Math.floorMod`. The expected hint
 * is computed independently from the requirement's literal sets (never from the implementation's
 * own [InteroperabilityHintClassifier.INTEROPERABLE_PATH_TYPES] set), so the test does not merely
 * mirror the code under test.
 *
 * Every `@Property` runs a minimum of 100 iterations.
 */
@RunWith(JUnitQuickcheck::class)
class InteroperabilityHintPropertyTest {

    private companion object {
        /** The interoperable set stated literally in Requirement 12.1 (independent of the impl). */
        val INTEROPERABLE = setOf(
            PathType.WinMyDocuments,
            PathType.WinAppDataLocal,
            PathType.WinAppDataLocalLow,
            PathType.WinAppDataRoaming,
            PathType.WinSavedGames,
        )

        /** Expected hint per the requirement's literal classification (Req 12.1/12.2/12.3). */
        fun expectedHint(pathType: PathType): InteroperabilityHint = when {
            pathType in INTEROPERABLE -> InteroperabilityHint.INTEROPERABLE
            pathType == PathType.SteamUserData -> InteroperabilityHint.NOT_EXPECTED_TO_LINE_UP
            else -> InteroperabilityHint.NOT_GUARANTEED
        }

        /** Map an arbitrary Int selector onto a PathType value. */
        fun pathTypeFor(selector: Int): PathType {
            val values = PathType.values()
            return values[Math.floorMod(selector, values.size)]
        }
    }

    // Feature: game-save-backup, Property 14: Interoperability hint matches PathType classification —
    // for any PathType, classify returns INTEROPERABLE iff the PathType is in the interoperable set
    // {WinMyDocuments, WinAppDataLocal, WinAppDataLocalLow, WinAppDataRoaming, WinSavedGames},
    // NOT_EXPECTED_TO_LINE_UP iff SteamUserData, and NOT_GUARANTEED for any other PathType. The
    // expected hint is computed independently from the requirement's literal sets.
    @Property(trials = 200)
    fun classifyMatchesRequirementClassification(selector: Int) {
        val pathType = pathTypeFor(selector)

        val expected = expectedHint(pathType)
        val actual = InteroperabilityHintClassifier.classify(pathType)

        assertEquals(
            "classify($pathType) must match the requirement's literal classification",
            expected,
            actual,
        )

        // Reinforce the biconditional explicitly against the literal sets.
        assertEquals(
            "INTEROPERABLE iff PathType in interoperable set",
            pathType in INTEROPERABLE,
            actual == InteroperabilityHint.INTEROPERABLE,
        )
        assertEquals(
            "NOT_EXPECTED_TO_LINE_UP iff PathType == SteamUserData",
            pathType == PathType.SteamUserData,
            actual == InteroperabilityHint.NOT_EXPECTED_TO_LINE_UP,
        )
    }

    // Feature: game-save-backup, Property 14: Interoperability hint matches PathType classification —
    // the three-way partition is total and mutually exclusive: for any PathType, classify returns a
    // non-null hint and exactly one of the three classifications holds.
    @Property(trials = 200)
    fun classificationIsExhaustiveAndMutuallyExclusive(selector: Int) {
        val pathType = pathTypeFor(selector)

        val actual = InteroperabilityHintClassifier.classify(pathType)
        assertNotNull("classify must return a hint for every PathType", actual)

        val isInteroperable = actual == InteroperabilityHint.INTEROPERABLE
        val isNotExpected = actual == InteroperabilityHint.NOT_EXPECTED_TO_LINE_UP
        val isNotGuaranteed = actual == InteroperabilityHint.NOT_GUARANTEED

        val trueCount = listOf(isInteroperable, isNotExpected, isNotGuaranteed).count { it }
        assertEquals(
            "exactly one classification must hold for $pathType (got $actual)",
            1,
            trueCount,
        )

        // And that single classification must agree with the independent expected value.
        assertEquals(expectedHint(pathType), actual)
    }

    // Feature: game-save-backup, Property 14: Interoperability hint matches PathType classification —
    // belt-and-suspenders enumeration: assert the full mapping for every PathType constant exactly
    // once, so every enum value is covered regardless of the random selector distribution.
    @Test
    fun allPathTypesMapToTheExpectedHint() {
        for (pathType in PathType.values()) {
            assertEquals(
                "classify($pathType) mismatch",
                expectedHint(pathType),
                InteroperabilityHintClassifier.classify(pathType),
            )
        }

        // Spot-check the three representative buckets so the enumeration is not vacuous.
        assertEquals(
            InteroperabilityHint.INTEROPERABLE,
            InteroperabilityHintClassifier.classify(PathType.WinSavedGames),
        )
        assertEquals(
            InteroperabilityHint.NOT_EXPECTED_TO_LINE_UP,
            InteroperabilityHintClassifier.classify(PathType.SteamUserData),
        )
        assertEquals(
            InteroperabilityHint.NOT_GUARANTEED,
            InteroperabilityHintClassifier.classify(PathType.WinProgramData),
        )
        assertTrue(
            "WinProgramData must not be in the interoperable set",
            PathType.WinProgramData !in INTEROPERABLE,
        )
    }
}
