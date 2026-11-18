package app.gamenative.powercontrol

import timber.log.Timber

/**
 * sched_setaffinity for single tasks of this app's uid (its own threads and the processes it
 * spawned), which Linux allows without privilege. Backed by libgnaffinity.so.
 */
object TaskAffinity {
    private var libraryLoaded: Boolean? = null

    @JvmStatic
    private external fun nativeSetTaskAffinity(tid: Int, mask: Int): Int

    /**
     * @return 0 on success, the errno on failure, or -1 when the library is unavailable
     */
    @Synchronized
    fun set(tid: Int, mask: Int): Int {
        val loaded = libraryLoaded ?: runCatching { System.loadLibrary("gnaffinity") }
            .onFailure { Timber.tag("PowerManager").w(it, "libgnaffinity unavailable") }
            .isSuccess.also { libraryLoaded = it }
        return if (loaded) nativeSetTaskAffinity(tid, mask) else -1
    }
}
