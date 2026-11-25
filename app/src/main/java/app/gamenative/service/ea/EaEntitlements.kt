package app.gamenative.service.ea

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/** The EA account's SDK entitlements, as the EA app serves them to games over LSX QueryEntitlements. */
object EaEntitlements {
    data class Entitlement(
        val id: String,
        val tag: String,
        val type: String,
        val grantDate: String,
        val group: String,
        val productId: String,
        val terminationDate: String?,
        val useCount: Int,
        val version: Int,
    )

    private const val QUERY = "query sdkEntitlements(\$productIds: [String!]!, \$includeChildGroups: Boolean!, \$groupNames: [String!]!, " +
        "\$entitlementTag: String!, \$pageSize: Int!, \$pageNumber: Int!) { me { sdkEntitlements(productIds: \$productIds, " +
        "includeChildGroups: \$includeChildGroups, groupNames: \$groupNames, entitlementTag: \$entitlementTag, " +
        "paging: { pageSize: \$pageSize, pageNumber: \$pageNumber }) { entitlements { entitlementTag entitlementType grantDate " +
        "groupName id productId terminationDate useCount version } } } }"

    suspend fun query(
        context: Context,
        groups: List<String>,
        productIds: List<String> = emptyList(),
        includeChildGroups: Boolean = false,
        tag: String = "",
    ): List<Entitlement> = withContext(Dispatchers.IO) {
        val token = EaAuthManager.accessToken(context)
        val variables = JSONObject()
            .put("productIds", JSONArray(productIds))
            .put("includeChildGroups", includeChildGroups)
            .put("groupNames", JSONArray(groups))
            .put("entitlementTag", tag)
            .put("pageSize", 500)
            .put("pageNumber", 1)
        val arr = EaGraphQl.post(token, "sdkEntitlements", QUERY, variables)
            .optJSONObject("me")?.optJSONObject("sdkEntitlements")?.optJSONArray("entitlements")
            ?: run { Timber.w("EA sdkEntitlements: no entitlements in the response"); return@withContext emptyList() }
        (0 until arr.length()).map { i ->
            val e = arr.getJSONObject(i)
            Entitlement(
                id = e.optString("id"),
                tag = e.optString("entitlementTag"),
                type = e.optString("entitlementType", "DEFAULT"),
                grantDate = e.optString("grantDate"),
                group = e.optString("groupName"),
                productId = e.optString("productId"),
                terminationDate = e.optString("terminationDate").takeIf { it.isNotEmpty() && it != "null" },
                useCount = e.optInt("useCount", 0),
                version = e.optInt("version", 0),
            )
        }.also { Timber.i("EA sdkEntitlements groups=$groups products=${productIds.size}: ${it.size} entitlement(s) ${it.map { e -> e.tag }.take(12)}") }
    }
}
