package app.gamenative.store

import app.gamenative.data.GameSource

sealed interface StorePageTarget {
    val source: GameSource
    val canonicalWebUrl: String

    data class WebOnly(
        override val source: GameSource,
        override val canonicalWebUrl: String,
    ) : StorePageTarget

    data class NativeWithWebFallback(
        override val source: GameSource,
        override val canonicalWebUrl: String,
        val nativeCandidates: List<NativeStoreTarget>,
    ) : StorePageTarget
}

data class NativeStoreTarget(
    val uri: String,
    val packageName: String,
)

sealed interface StorePageLaunchResult {
    data object NativeLaunched : StorePageLaunchResult
    data object WebLaunched : StorePageLaunchResult
    data class Failed(val canonicalWebUrl: String) : StorePageLaunchResult
}
