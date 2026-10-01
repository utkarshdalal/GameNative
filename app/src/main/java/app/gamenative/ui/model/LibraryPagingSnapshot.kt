package app.gamenative.ui.model

import java.util.RandomAccess

/**
 * Owns an already filtered/sorted list that must not be mutated after publication.
 * Pages are read-only views: appending does not copy the entire loaded prefix on the UI thread.
 */
internal class LibraryPagingSnapshot<T>(
    items: List<T>,
    private val pageSize: Int,
    private val hasHero: Boolean = false,
) {
    init {
        require(pageSize > 0)
    }

    // Prefixes own only the required chunk references, not the entire backing library.
    private val chunks = items.chunked(CHUNK_SIZE)
    private val itemCount = items.size
    val totalGames = (items.size - if (hasHero) 1 else 0).coerceAtLeast(0)
    val lastPage = ((totalGames - 1).coerceAtLeast(0)) / pageSize
    fun through(page: Int): List<T> {
        val games = ((page.coerceIn(0, lastPage).toLong() + 1) * pageSize).coerceAtMost(totalGames.toLong()).toInt()
        val count = (games + if (hasHero && itemCount > 0) 1 else 0).coerceAtMost(itemCount)
        return if (count == 0) emptyList() else Prefix(chunks.take((count - 1) / CHUNK_SIZE + 1), count)
    }

    private class Prefix<T>(private val chunks: List<List<T>>, override val size: Int) : AbstractList<T>(), RandomAccess {
        override fun get(index: Int): T {
            if (index < 0 || index >= size) throw IndexOutOfBoundsException("index=$index, size=$size")
            return chunks[index / CHUNK_SIZE][index % CHUNK_SIZE]
        }
    }

    private companion object {
        const val CHUNK_SIZE = 128
    }
}

internal fun shouldPrefetchLibraryPage(lastVisible: Int, loaded: Int, currentPage: Int, lastPage: Int, loading: Boolean): Boolean =
    !loading && loaded > 0 && currentPage < lastPage && lastVisible >= (loaded - 12).coerceAtLeast(0)
