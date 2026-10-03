package app.gamenative.ui.screen.support

import android.content.res.Resources
import app.gamenative.R
import app.gamenative.api.SupportApi

object SupportProgressText {

    fun remainingSeconds(progress: SupportApi.Progress, nowElapsed: Long): Long? =
        progress.etaSeconds?.let { it - (nowElapsed - progress.receivedAt).coerceAtLeast(0L) / 1000L }

    fun elapsedMinutes(progress: SupportApi.Progress, nowWall: Long): Int? {
        if (progress.startedAt <= 0L) return null
        return ((nowWall - progress.startedAt).coerceAtLeast(0L) / 60_000L).toInt()
    }

    private fun etaText(res: Resources, remaining: Long?): String? =
        when {
            remaining == null -> null
            remaining <= 0L -> res.getString(R.string.support_progress_eta_soon)
            else -> {
                val minutes = ((remaining + 59L) / 60L).toInt().coerceAtLeast(1)
                res.getQuantityString(R.plurals.support_progress_eta_minutes, minutes, minutes)
            }
        }

    fun queuedLine(res: Resources, progress: SupportApi.Progress, nowElapsed: Long): String {
        val position = progress.position ?: 0
        val ahead = if (position <= 0) {
            res.getString(R.string.support_progress_next)
        } else {
            res.getQuantityString(R.plurals.support_progress_ahead, position, position)
        }
        val eta = etaText(res, remainingSeconds(progress, nowElapsed))
        return if (eta == null) ahead else "$ahead $eta"
    }

    fun analysingLine(res: Resources, progress: SupportApi.Progress, nowWall: Long): String {
        val minutes = elapsedMinutes(progress, nowWall)
        return if (minutes == null) {
            res.getString(R.string.support_progress_analysing)
        } else {
            res.getString(R.string.support_progress_analysing_elapsed, minutes)
        }
    }

    fun line(res: Resources, progress: SupportApi.Progress?, nowElapsed: Long, nowWall: Long): String? {
        if (progress == null) return null
        return when (progress.stage) {
            SupportApi.STAGE_QUEUED -> queuedLine(res, progress, nowElapsed)
            SupportApi.STAGE_ANALYSING -> analysingLine(res, progress, nowWall)
            SupportApi.STAGE_FAILED -> res.getString(R.string.support_progress_failed)
            else -> null
        }
    }

    fun shortLabel(res: Resources, conversation: SupportApi.Conversation, nowElapsed: Long, nowWall: Long): String? {
        if (conversation.state != SupportApi.STATE_WAITING) return null
        val progress = conversation.progress ?: return null
        return when (progress.stage) {
            SupportApi.STAGE_QUEUED -> {
                val remaining = remainingSeconds(progress, nowElapsed)
                if (remaining != null && remaining > 0L) {
                    val minutes = ((remaining + 59L) / 60L).toInt().coerceAtLeast(1)
                    res.getString(R.string.support_stage_queued_eta, minutes)
                } else {
                    res.getString(R.string.support_stage_queued)
                }
            }
            SupportApi.STAGE_ANALYSING -> {
                val minutes = elapsedMinutes(progress, nowWall)
                if (minutes == null) {
                    res.getString(R.string.support_stage_analysing)
                } else {
                    res.getString(R.string.support_stage_analysing_elapsed, minutes)
                }
            }
            SupportApi.STAGE_FAILED -> res.getString(R.string.support_stage_failed)
            else -> null
        }
    }
}
