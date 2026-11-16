package app.gamenative.service.ubisoft

import android.content.Context
import java.io.File
import java.util.Properties

/**
 * Values the helper archive supplies in ubihost.properties; none of them are part of the app.
 * Templates use `{name}` placeholders that the app fills in.
 */
object UbisoftHelperConfig {
    const val APP_ID = "ubi.app.id"
    const val WEB_APP_ID = "ubi.web.app.id"
    const val WEB_GENOME_ID = "ubi.web.genome.id"
    const val WEB_NEXT_URL = "ubi.web.next.url"
    const val WEB_LOGIN_URL = "ubi.web.login.url"
    const val SESSIONS_URL = "ubi.sessions.url"
    const val PROFILE_URL = "ubi.profiles.me.url"
    const val INSTALLER_URL = "ubi.installer.url"
    const val HTTP_HEADERS = "ubi.http.headers"
    const val HTTP_USER_AGENT = "ubi.http.user.agent"
    const val AUTH_TICKET = "ubi.auth.ticket"
    const val AUTH_REMEMBER = "ubi.auth.remember"
    const val SESSION_HEADER = "ubi.auth.session.header"
    const val CHALLENGE_HEADER = "ubi.auth.challenge.header"
    const val SESSION_BODY = "ubi.session.body"
    const val CLOUD_ENTITIES_URL = "ubi.cloud.entities.url"
    const val CLOUD_ENTITY_URL = "ubi.cloud.entity.url"
    const val CLOUD_ENTITY_CREATE_URL = "ubi.cloud.entity.create.url"
    const val CLOUD_ENTITY_NAME = "ubi.cloud.entity.name"
    const val CLOUD_ENTITY_TYPE = "ubi.cloud.entity.type"
    const val CLOUD_SPACE_ID = "ubi.cloud.space.id"
    const val CLOUD_SAVE_HOST = "ubi.cloud.save.host"
    const val CLOUD_SAVE_PATH = "ubi.cloud.save.path"
    const val DEMUX_HOST = "ubi.demux.host"
    const val DEMUX_PORT = "ubi.demux.port"
    const val DEMUX_CLIENT_VERSION = "ubi.demux.client.version"
    const val DEMUX_CLIENT_ID = "ubi.demux.client.id"
    const val DEMUX_SERVICE_OWNERSHIP = "ubi.demux.service.ownership"
    const val DEMUX_SERVICE_CLOUDSAVE = "ubi.demux.service.cloudsave"

    @Volatile private var cached: Properties? = null

    private fun properties(filesDir: File): Properties =
        cached ?: UbisoftHelperArchive.loadConfig(filesDir).also { cached = it }

    fun value(context: Context, name: String): String = value(properties(context.filesDir), name)

    fun optional(context: Context, name: String): String? = optional(properties(context.filesDir), name)

    internal fun value(properties: Properties, name: String): String =
        optional(properties, name) ?: error("Ubisoft helper configuration is missing $name")

    internal fun optional(properties: Properties, name: String): String? =
        properties.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }

    /** Replaces each `{key}` in [template]; unknown placeholders are left alone. */
    fun fill(template: String, values: Map<String, String>): String =
        values.entries.fold(template) { acc, (key, value) -> acc.replace("{$key}", value) }

    /** `Name: value` pairs separated by `|`, applied to every account request. */
    internal fun headers(raw: String?, values: Map<String, String>): List<Pair<String, String>> =
        raw.orEmpty().split('|').mapNotNull { part ->
            val name = part.substringBefore(':', "").trim()
            if (name.isEmpty()) return@mapNotNull null
            name to fill(part.substringAfter(':').trim(), values)
        }

    fun loginUrl(context: Context, language: String): String = loginUrl(properties(context.filesDir), language)

    internal fun loginUrl(p: Properties, language: String): String = fill(
        value(p, WEB_LOGIN_URL),
        mapOf(
            "appId" to encode(value(p, WEB_APP_ID)),
            "genomeId" to encode(value(p, WEB_GENOME_ID)),
            "lang" to encode(language),
            "nextUrl" to encode(value(p, WEB_NEXT_URL)),
        ),
    )

    private fun encode(value: String) = java.net.URLEncoder.encode(value, "UTF-8")

    internal fun reset() { cached = null }
}
