package app.gamenative.utils

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

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
}
