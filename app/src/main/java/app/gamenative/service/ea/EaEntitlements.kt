package app.gamenative.service.ea

import android.content.Context
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

    private val client = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .build()

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
        val body = JSONObject()
            .put("operationName", "sdkEntitlements")
            .put("query", QUERY)
            .put("variables", variables)
            .put("extensions", JSONObject().put("persistedQuery", JSONObject().put("version", 1).put("sha256Hash", EaCrypto.hex(EaCrypto.sha256(QUERY.toByteArray())))))
        val req = Request.Builder().url(EaConstants.SERVICE_AGGREGATION_ENDPOINT)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", "EADesktop/13.778.0")
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("EA sdkEntitlements HTTP ${resp.code}: ${text.take(300)}")
            val arr = JSONObject(text).optJSONObject("data")?.optJSONObject("me")?.optJSONObject("sdkEntitlements")?.optJSONArray("entitlements")
                ?: run { Timber.w("EA sdkEntitlements: no data (${text.take(300)})"); return@use emptyList() }
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
}
