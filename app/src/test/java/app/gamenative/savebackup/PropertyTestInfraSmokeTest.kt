package app.gamenative.savebackup

import com.pholser.junit.quickcheck.Property
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith

/**
 * Smoke test proving the property-based testing infrastructure (junit-quickcheck) runs under the
 * existing JUnit 4 runner, with no JUnit Platform migration and no Robolectric dependency.
 *
 * This is the trivial property test called for by game-save-backup Task 1. It exists only to
 * validate that the test task executes property tests (100+ iterations) alongside the existing
 * JUnit 4 + Robolectric suite. It will be superseded by the real property tests in later tasks.
 */
@RunWith(JUnitQuickcheck::class)
class PropertyTestInfraSmokeTest {

    // Feature: game-save-backup, Property (infrastructure): integer addition is commutative.
    // 100 trials satisfies the "minimum 100 iterations" rule for every property test.
    @Property(trials = 100)
    fun additionIsCommutative(a: Int, b: Int) {
        assertEquals(a + b, b + a)
    }

    // Feature: game-save-backup, Property (infrastructure): string concatenation length is additive.
    @Property(trials = 100)
    fun concatenationLengthIsAdditive(s1: String, s2: String) {
        assertEquals((s1.length + s2.length).toLong(), (s1 + s2).length.toLong())
    }
}
