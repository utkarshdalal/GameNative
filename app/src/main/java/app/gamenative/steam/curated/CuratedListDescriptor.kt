package app.gamenative.steam.curated

import androidx.annotation.StringRes
import app.gamenative.R

internal const val CURATOR_CLAN_ID_4_3 = 43078746L

private const val CURATED_LIST_ID_PREFIX = "curated:"

private const val CURATED_LIST_ID_FOUR_THREE = "${CURATED_LIST_ID_PREFIX}4-3"

internal enum class CuratedListDescriptor(
    val id: String,
    @param:StringRes val nameRes: Int,
) {
    FOUR_THREE(
        id = CURATED_LIST_ID_FOUR_THREE,
        nameRes = R.string.curated_list_4_3_name,
    ),
    ;

    companion object {
        val byId: Map<String, CuratedListDescriptor> = entries.associateBy { it.id }
    }
}
