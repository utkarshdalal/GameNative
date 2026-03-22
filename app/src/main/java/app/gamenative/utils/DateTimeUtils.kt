package app.gamenative.utils

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

object DateTimeUtils {

    /** Parse common storefront release-date formats to epoch seconds. */
    fun parseStoreReleaseDateToEpochSeconds(dateStr: String): Long {
        if (dateStr.isBlank()) return 0L

        return runCatching {
            when {
                dateStr.endsWith("Z") -> Instant.parse(dateStr).epochSecond

                dateStr.contains('T') &&
                    (dateStr.contains('+') || dateStr.substringAfterLast('T').contains('-')) -> {
                    runCatching {
                        ZonedDateTime.parse(dateStr, DateTimeFormatter.ISO_DATE_TIME)
                    }.getOrElse {
                        ZonedDateTime.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ"))
                    }.toInstant().epochSecond
                }

                dateStr.contains('T') -> {
                    ZonedDateTime.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ"))
                        .toInstant()
                        .epochSecond
                }

                dateStr.length == 10 && dateStr[4] == '-' -> {
                    LocalDate.parse(dateStr)
                        .atStartOfDay()
                        .toInstant(ZoneOffset.UTC)
                        .epochSecond
                }

                dateStr.length == 4 -> {
                    LocalDate.of(dateStr.toInt(), 1, 1)
                        .atStartOfDay()
                        .toInstant(ZoneOffset.UTC)
                        .epochSecond
                }

                else -> 0L
            }
        }.getOrDefault(0L)
    }

    fun formatRuntimeHours(hours: Double): String {
        val totalMinutes = (hours * 60.0).roundToInt().coerceAtLeast(1)
        val wholeHours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            wholeHours > 0 && minutes > 0 -> "${wholeHours}h ${minutes}m"
            wholeHours > 0 -> "${wholeHours}h"
            else -> "${minutes}m"
        }
    }
}
