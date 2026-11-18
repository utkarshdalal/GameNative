package app.gamenative.data

import kotlinx.serialization.Serializable

@Serializable
data class EulaInfo(
    val id: String,
    val name: String = "",
    val url: String = "",
    val version: String = "",
    val countries: List<String> = emptyList(),
) {
    val acceptanceKey: String
        get() = "$id:$version"

    fun appliesTo(country: String?): Boolean =
        countries.isEmpty() ||
            (!country.isNullOrBlank() && countries.any { it.equals(country.trim(), ignoreCase = true) })
}

fun List<EulaInfo>.filterForCountry(country: String?): List<EulaInfo> {
    if (country.isNullOrBlank()) return this
    return filter { it.appliesTo(country) }
}
