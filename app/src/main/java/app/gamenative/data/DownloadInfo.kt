package app.gamenative.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

data class DownloadInfo(
    val jobCount: Int = 1,
) {
    private var downloadJob: Job? = null
    private var progressMonitorJob: Job? = null
    private val downloadProgressListeners = mutableListOf<((Float) -> Unit)>()
    private val progresses: Array<Float> = Array(jobCount) { 0f }

    private val weights    = FloatArray(jobCount) { 1f }     // ⇐ new
    private var weightSum  = jobCount.toFloat()
    
    @Volatile
    private var isCancelled = false

    fun cancel() {
        isCancelled = true
        downloadJob?.cancel(CancellationException("Cancelled by user"))
        progressMonitorJob?.cancel(CancellationException("Progress monitoring cancelled by user"))
    }
    
    fun isCancelled(): Boolean = isCancelled

    fun setDownloadJob(job: Job) {
        downloadJob = job
    }
    
    fun setProgressMonitorJob(job: Job) {
        progressMonitorJob = job
    }

    fun getProgress(): Float {
        var total = 0f
        for (i in progresses.indices) {
            total += progresses[i] * weights[i]   // weight each depot
        }
        return if (weightSum == 0f) 0f else total / weightSum
    }


    fun setProgress(amount: Float, jobIndex: Int = 0) {
        progresses[jobIndex] = amount
        emitProgressChange()
    }

    fun setWeight(jobIndex: Int, weightBytes: Long) {        // tiny helper
        weights[jobIndex] = weightBytes.toFloat()
        weightSum = weights.sum()
    }

    fun addProgressListener(listener: (Float) -> Unit) {
        downloadProgressListeners.add(listener)
    }

    fun removeProgressListener(listener: (Float) -> Unit) {
        downloadProgressListeners.remove(listener)
    }

    fun emitProgressChange() {
        for (listener in downloadProgressListeners) {
            listener(getProgress())
        }
    }
}
