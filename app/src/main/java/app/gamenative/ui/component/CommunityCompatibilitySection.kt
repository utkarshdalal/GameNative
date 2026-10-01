package app.gamenative.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.data.CommunityCompatibilitySummary
import app.gamenative.data.CommunityCompatibilityVerdict
import app.gamenative.data.CommunityConfidenceCaution
import app.gamenative.data.CommunityEvidenceTier
import app.gamenative.data.CommunityVerdictCaution
import app.gamenative.data.CommunityVerdictSource

@Composable
fun CommunityCompatibilitySection(
    gameKey: String,
    summary: CommunityCompatibilitySummary,
    loading: Boolean,
    loadError: Boolean,
    onRetry: () -> Unit,
    onViewReports: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var detailsExpanded by rememberSaveable(gameKey) { mutableStateOf(false) }
    val basePresentation = getCommunityBadgeStyle(summary.verdict, compact = false)
    val presentation = if (!summary.verdictLoaded) {
        basePresentation.copy(
            icon = Icons.Rounded.HelpOutline,
            iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
            backgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            labelResId = if (loading &&
                !summary.loadFailed
            ) {
                R.string.community_compatibility_checking
            } else {
                R.string.community_compatibility_unavailable
            },
        )
    } else {
        basePresentation
    }
    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.community_compatibility_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (loading) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                }
            } else {
                IconButton(onClick = onRetry) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.community_compatibility_refresh),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 2.dp,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = presentation.backgroundColor,
                    ) {
                        Icon(
                            imageVector = presentation.icon,
                            contentDescription = null,
                            tint = presentation.iconTint,
                            modifier = Modifier.padding(8.dp).size(22.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(presentation.labelResId),
                        style = MaterialTheme.typography.titleMedium,
                        color = presentation.iconTint,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                sessionBasisText(summary)?.let { SupportingText(it) }

                if (summary.verdictSource == CommunityVerdictSource.SERVER) {
                    when (summary.scopeCaution) {
                        CommunityVerdictCaution.NO_MATCHING_SCOPE -> Unit
                        CommunityVerdictCaution.BROAD_NEGATIVE ->
                            SupportingText(stringResource(R.string.community_compatibility_family_negative))
                        CommunityVerdictCaution.MISSING_TIER ->
                            SupportingText(stringResource(R.string.community_compatibility_missing_tier))
                        CommunityVerdictCaution.NONE -> Unit
                    }
                    if (summary.verdict == CommunityCompatibilityVerdict.MIXED &&
                        summary.confidenceCaution == CommunityConfidenceCaution.NONE
                    ) {
                        SupportingText(stringResource(R.string.community_compatibility_unreliable_short))
                    }
                }
                if (summary.isCachedResultStale) {
                    SupportingText(stringResource(R.string.community_compatibility_cached_result))
                }
                if (summary.ratingCaution) {
                    SupportingText(stringResource(R.string.community_compatibility_rating_caution_short))
                } else if (summary.limitedRatingFeedback) {
                    SupportingText(stringResource(R.string.community_compatibility_limited_rating_feedback))
                }
                val confidenceMessage = when (summary.confidenceCaution) {
                    CommunityConfidenceCaution.NONE -> null
                    CommunityConfidenceCaution.LIMITED_EVIDENCE ->
                        R.string.community_compatibility_limited_evidence.takeUnless {
                            summary.ratingCaution ||
                                summary.limitedRatingFeedback
                        }
                    CommunityConfidenceCaution.CONFLICTING_FEEDBACK -> R.string.community_compatibility_mixed_feedback
                    CommunityConfidenceCaution.MODEL_UNCONFIRMED -> R.string.community_compatibility_model_unconfirmed
                    CommunityConfidenceCaution.NEGATIVE_FEEDBACK -> R.string.community_compatibility_negative_feedback
                    CommunityConfidenceCaution.OLDER_EVIDENCE -> R.string.community_compatibility_older_activity
                }
                confidenceMessage?.let { SupportingText(stringResource(it)) }
                if (summary.performanceCaution) {
                    SupportingText(stringResource(R.string.community_compatibility_performance_caution))
                }

                if (!loading && loadError) {
                    ConfigLoadError(onRetry)
                }

                Text(
                    text = stringResource(R.string.community_compatibility_short_disclaimer),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                )

                if (detailsExpanded) {
                    CompatibilityDetails(summary)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val expandedDescription = stringResource(
                        if (detailsExpanded) R.string.community_compatibility_expanded else R.string.community_compatibility_collapsed,
                    )
                    TextButton(
                        onClick = { detailsExpanded = !detailsExpanded },
                        modifier = Modifier.semantics { stateDescription = expandedDescription },
                    ) {
                        Text(stringResource(R.string.community_compatibility_details))
                        Icon(
                            if (detailsExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.padding(start = 4.dp).size(18.dp),
                        )
                    }
                    TextButton(onClick = onViewReports) {
                        Text(stringResource(R.string.community_compatibility_view_reports))
                    }
                }
            }
        }
    }
}

/** Expanding this section is presentation only: it never triggers another request. */
@Composable
private fun CompatibilityDetails(summary: CommunityCompatibilitySummary) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    if (summary.detailsLoaded || summary.hasDetailedReports) {
        SupportingText(configEvidenceText(summary))
    }
    fpsText(summary)?.let { SupportingText(it) }
}

internal fun communitySessionBasisResource(summary: CommunityCompatibilitySummary): Int? {
    if (!summary.verdictLoaded || summary.sessionCount <= 0 || summary.serverState == null) return null
    return when (summary.evidenceTier) {
        CommunityEvidenceTier.SAME_DEVICE -> R.plurals.community_compatibility_model_sessions
        CommunityEvidenceTier.SAME_SOC -> R.plurals.community_compatibility_soc_sessions
        CommunityEvidenceTier.SAME_GPU -> R.plurals.community_compatibility_gpu_sessions
        CommunityEvidenceTier.COMPATIBLE_GPU_FAMILY -> R.plurals.community_compatibility_family_sessions
        CommunityEvidenceTier.NONE -> null
    }
}

@Composable
private fun sessionBasisText(summary: CommunityCompatibilitySummary): String? {
    val resource = communitySessionBasisResource(summary) ?: return null
    return pluralStringResource(resource, summary.sessionCount, summary.sessionCount)
}

@Composable
private fun ConfigLoadError(
    onRetry: () -> Unit,
) {
    Column {
        SupportingText(
            stringResource(R.string.community_compatibility_configs_unavailable),
        )
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.community_compatibility_retry))
        }
    }
}

@Composable
private fun SupportingText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun configEvidenceText(summary: CommunityCompatibilitySummary): String {
    if (!summary.detailsLoaded && !summary.hasDetailedReports) {
        return stringResource(R.string.community_compatibility_configs_unavailable)
    }
    if (summary.reportCount <= 0 || summary.reportEvidenceTier == CommunityEvidenceTier.NONE) {
        return stringResource(R.string.community_compatibility_no_reports)
    }
    return when (summary.reportEvidenceTier) {
        CommunityEvidenceTier.SAME_DEVICE -> pluralStringResource(
            R.plurals.community_compatibility_device_evidence,
            summary.reportCount,
            summary.reportCount,
        )
        CommunityEvidenceTier.SAME_GPU -> pluralStringResource(
            R.plurals.community_compatibility_gpu_evidence,
            summary.reportCount,
            summary.reportCount,
        )
        CommunityEvidenceTier.COMPATIBLE_GPU_FAMILY -> pluralStringResource(
            R.plurals.community_compatibility_family_evidence,
            summary.reportCount,
            summary.reportCount,
        )
        // Shared-config aggregates have no SoC bucket; session scope is kept separate.
        CommunityEvidenceTier.SAME_SOC, CommunityEvidenceTier.NONE -> stringResource(R.string.community_compatibility_no_reports)
    }
}

@Composable
private fun fpsText(summary: CommunityCompatibilitySummary): String? {
    val fps = summary.medianFps ?: return null
    val text = stringResource(R.string.community_compatibility_fps_median, fps)
    val tier = summary.evidenceTier
    return when (tier) {
        CommunityEvidenceTier.SAME_DEVICE -> stringResource(R.string.community_compatibility_fps_device, text)
        CommunityEvidenceTier.SAME_SOC -> stringResource(R.string.community_compatibility_fps_soc, text)
        CommunityEvidenceTier.SAME_GPU -> stringResource(R.string.community_compatibility_fps_gpu, text)
        CommunityEvidenceTier.COMPATIBLE_GPU_FAMILY -> stringResource(R.string.community_compatibility_fps_family, text)
        CommunityEvidenceTier.NONE -> text
    }
}
