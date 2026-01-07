package app.gamenative.db.converters

import androidx.room.TypeConverter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Room TypeConverter for GOG-specific data types
 */
class GOGConverter {

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
