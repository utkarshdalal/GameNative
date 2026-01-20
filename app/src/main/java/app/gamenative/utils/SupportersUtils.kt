package app.gamenative.utils

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import timber.log.Timber

@Serializable
data class KofiSupporter(
    @SerialName("Name") val name: String? = null,
    @SerialName("OneOff") val oneOff: Boolean? = null,
    @SerialName("Total") val total: Double? = null,
)

/**
 * Fetch supporters from Supabase `kofi_supporters` table.
 * Only fetches fields needed for display.
 */
suspend fun fetchKofiSupporters(supabase: SupabaseClient?): List<KofiSupporter> {
    if (supabase == null) {
        Timber.w("fetchKofiSupporters: Supabase client is null")
        return emptyList()
    }
    return try {
        Timber.d("Fetching Ko-fi supporters from Supabase")
        supabase
            .from("kofi_supporters")
            .select(columns = Columns.list("Name", "OneOff", "Total"))
            .decodeList<KofiSupporter>()
    } catch (e: Exception) {
        Timber.e(e, "Failed to fetch Ko-fi supporters: ${e.message}")
        emptyList()
    }
}
