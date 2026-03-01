package app.gamenative.utils

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Test

class DateTimeUtilsTest {

    @Test
    fun parseStoreReleaseDateToEpochSeconds_parsesIsoInstant() {
        val input = "2026-02-21T16:12:31Z"
        val expected = Instant.parse(input).epochSecond

        val actual = DateTimeUtils.parseStoreReleaseDateToEpochSeconds(input)

        assertEquals(expected, actual)
    }

    @Test
    fun parseStoreReleaseDateToEpochSeconds_parsesRfc822OffsetWithoutColon() {
        val input = "2026-02-21T16:12:31+0000"
        val expected = ZonedDateTime.parse(
            input,
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ"),
        ).toInstant().epochSecond

        val actual = DateTimeUtils.parseStoreReleaseDateToEpochSeconds(input)

        assertEquals(expected, actual)
    }

    @Test
    fun parseStoreReleaseDateToEpochSeconds_parsesNegativeRfc822OffsetWithoutColon() {
        val input = "2026-02-21T09:12:31-0700"
        val expected = ZonedDateTime.parse(
            input,
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ"),
        ).toInstant().epochSecond

        val actual = DateTimeUtils.parseStoreReleaseDateToEpochSeconds(input)

        assertEquals(expected, actual)
    }

    @Test
    fun parseStoreReleaseDateToEpochSeconds_returnsZeroForInvalidInput() {
        val input = "not-a-date"

        val actual = DateTimeUtils.parseStoreReleaseDateToEpochSeconds(input)

        assertEquals(0L, actual)
    }

    @Test
    fun parseStoreReleaseDateToEpochSeconds_parsesDateOnly() {
        val input = "2026-02-21"
        val expected = LocalDate.of(2026, 2, 21)
            .atStartOfDay()
            .toInstant(ZoneOffset.UTC)
            .epochSecond

        val actual = DateTimeUtils.parseStoreReleaseDateToEpochSeconds(input)

        assertEquals(expected, actual)
    }
}
