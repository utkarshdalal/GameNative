package app.gamenative.db.converters

import androidx.room.TypeConverter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Room TypeConverter for Epic-specific data types
 * Handles conversion of List<String> fields (genres, tags) to/from JSON strings
 */
class EpicConverter {

    @TypeConverter
    fun fromStringList(value: List<String>): String {
        return Json.encodeToString(value)
    }

    @TypeConverter
    fun toStringList(value: String): List<String> {
        if (value.isEmpty()) {
            return emptyList()
        }
        return Json.decodeFromString<List<String>>(value)
    }
}
