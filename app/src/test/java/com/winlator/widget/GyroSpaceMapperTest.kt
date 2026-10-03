package com.winlator.widget

import android.view.Surface
import app.gamenative.data.GyroSettings
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GyroSpaceMapperTest {
    private fun map(style: Int, gyro: FloatArray, up: FloatArray = floatArrayOf(0f, 1f, 0f), rotation: Int = 0) =
        GyroSpaceMapper.map(gyro[0], gyro[1], gyro[2], rotation, style, up[0], up[1], up[2])

    @Test
    fun flatTurnsKeepTheirDirectionAcrossDisplayRotations() {
        val gyro = floatArrayOf(0f, 0f, 1f)
        val up = floatArrayOf(0f, 0f, 1f)
        for (rotation in Surface.ROTATION_0..Surface.ROTATION_270) {
            for (style in GyroSettings.CONVERSION_LOCAL_ROLL..GyroSettings.CONVERSION_WORLD_SPACE) {
                assertArrayEquals(
                    "style $style, rotation $rotation", floatArrayOf(-1f, 0f),
                    map(style, gyro, up, rotation), 0f,
                )
            }
        }
    }

    @Test
    fun localYawPreservesExistingMappingForEveryDisplayRotation() {
        val expected = listOf(
            floatArrayOf(-2f, -1f), floatArrayOf(-1f, 2f),
            floatArrayOf(2f, 1f), floatArrayOf(1f, -2f),
        )
        expected.forEachIndexed { rotation, rates ->
            assertArrayEquals(rates, map(GyroSettings.CONVERSION_LOCAL_YAW, floatArrayOf(1f, 2f, 3f), rotation = rotation), 0f)
        }
    }

    @Test
    fun rollAndCombinedUseThirdAxisWhilePreservingPitch() {
        val gyro = floatArrayOf(0.4f, 0.3f, 0.2f)
        assertArrayEquals(floatArrayOf(-0.2f, -0.4f), map(GyroSettings.CONVERSION_LOCAL_ROLL, gyro), 0.0001f)
        assertArrayEquals(floatArrayOf(-0.5f, -0.4f), map(GyroSettings.CONVERSION_LOCAL_YAW_ROLL, gyro), 0.0001f)
        // Opposing axes cancel smoothly, including the boundary where dominance changes.
        for (roll in listOf(-0.299f, -0.3f, -0.301f)) {
            val rates = map(GyroSettings.CONVERSION_LOCAL_YAW_ROLL, floatArrayOf(0f, 0.3f, roll))
            assertTrue(kotlin.math.abs(rates[0]) < 0.002f)
        }
    }

    @Test
    fun gravitySpacesPreserveWorldTurnsFromFlatThroughUprightAndUpsideDown() {
        for (style in listOf(GyroSettings.CONVERSION_PLAYER_SPACE, GyroSettings.CONVERSION_WORLD_SPACE)) {
            for (degrees in -180..180 step 15) {
                val angle = Math.toRadians(degrees.toDouble())
                val up = floatArrayOf(0f, cos(angle).toFloat(), sin(angle).toFloat())
                val gyro = floatArrayOf(0.25f, up[1], up[2])
                assertArrayEquals("style $style, pitch $degrees", floatArrayOf(-1f, -0.25f), map(style, gyro, up), 0.0001f)
            }
        }
    }

    @Test
    fun playerSpaceRelaxesHorizontalAxisButKeepsLocalPitch() {
        val gyro = floatArrayOf(0.3f, 0.8f, 0.6f)
        assertArrayEquals(floatArrayOf(-1f, -0.3f), map(GyroSettings.CONVERSION_PLAYER_SPACE, gyro), 0.0001f)
        assertArrayEquals(floatArrayOf(-0.8f, -0.3f), map(GyroSettings.CONVERSION_WORLD_SPACE, gyro), 0.0001f)
        val nearSidewaysTurn = map(GyroSettings.CONVERSION_PLAYER_SPACE, floatArrayOf(0f, 0.01f, 1f))
        assertEquals(-0.0141f, nearSidewaysTurn[0], 0.0001f)
    }

    @Test
    fun worldSpaceSeparatesYawAndPitchWhenDeviceIsLeaning() {
        val diagonal = kotlin.math.sqrt(0.5f)
        val up = floatArrayOf(diagonal, diagonal, 0f)
        assertArrayEquals(floatArrayOf(-1f, 0f), map(GyroSettings.CONVERSION_WORLD_SPACE, up, up), 0.0001f)
        val worldPitch = floatArrayOf(diagonal, -diagonal, 0f)
        assertArrayEquals(floatArrayOf(0f, -1f), map(GyroSettings.CONVERSION_WORLD_SPACE, worldPitch, up), 0.0001f)
    }

    @Test
    fun worldPitchFadesSmoothlyAtSidewaysSingularity() {
        for (side in listOf(-1f, 1f)) {
            for ((y, pitch) in listOf(0f to 0f, 0.01f to 0f, 0.125f to 0f, 0.2f to 0.5878775f, 0.25f to 0.9682458f)) {
                val up = floatArrayOf(side * kotlin.math.sqrt(1f - y * y), y, 0f)
                val rates = map(GyroSettings.CONVERSION_WORLD_SPACE, floatArrayOf(0f, 1f, 0f), up)
                assertTrue(rates.all { it.isFinite() })
                assertEquals(side * pitch, rates[1], 0.0001f)
            }
        }
    }
}
