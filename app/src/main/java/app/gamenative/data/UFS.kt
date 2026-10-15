package app.gamenative.data

import kotlinx.serialization.Serializable

@Serializable
data class UFS(
    val quota: Int = 0,
    val maxNumFiles: Int = 0,
    val saveFilePatterns: List<SaveFilePattern> = emptyList(),
    val ignoreExternalFiles: Boolean = false,
)
