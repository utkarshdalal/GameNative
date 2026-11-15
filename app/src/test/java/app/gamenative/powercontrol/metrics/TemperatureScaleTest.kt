package app.gamenative.powercontrol.metrics

import org.junit.Assert.assertEquals
import org.junit.Test

class TemperatureScaleTest {

    @Test
    fun scalesEachUnit() {
        val cases = listOf(
            42500 to 43, // milli-degrees
            42000 to 42,
            425 to 43, // deci-degrees
            420 to 42,
            151 to 15,
            1500 to 150,
            42 to 42, // plain degrees
            150 to 150,
            1 to 1,
            0 to null,
            -5 to null,
            200000 to null, // 200 C, out of range
        )
        for ((raw, expected) in cases) {
            assertEquals("raw=$raw", expected, SystemMetricsSources.scaleTemperatureC(raw))
        }
    }
}
