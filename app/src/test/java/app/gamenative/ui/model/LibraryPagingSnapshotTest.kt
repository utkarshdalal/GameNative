package app.gamenative.ui.model

import org.junit.Assert.*
import org.junit.Test

class LibraryPagingSnapshotTest {
    @Test fun appendingDoesNotReadOrCopyPreviouslyLoadedItems() {
        var reads = 0
        val source = object : AbstractList<Int>() {
            override val size = 50_003
            override fun get(index: Int): Int {
                reads++
                return index
            }
        }
        val snapshot = LibraryPagingSnapshot(source, 50)
        assertEquals(source.size, reads) // One ownership copy, off the UI thread.
        reads = 0
        val pages = (0..snapshot.lastPage).map(snapshot::through)
        assertEquals(0, reads)
        assertEquals(50, pages.first().size)
        assertEquals(50_003, pages.last().size)
        assertEquals(49, pages.first().last())
        assertEquals(50_002, pages.last().last())
        assertEquals(0, reads)
        assertTrue(pages.first() is java.util.RandomAccess)
        assertFalse(pages.first() is MutableList<*>)
    }

    @Test fun prefixViewsKeepFixedBoundsAndCannotExposeUnloadedItems() {
        val snapshot = LibraryPagingSnapshot((0..200).toList(), 50)
        val first = snapshot.through(0)
        val last = snapshot.through(4)
        assertEquals((0..49).toList(), first)
        assertEquals((0..200).toList(), last)
        assertEquals(listOf(48, 49), first.subList(48, 50))
        assertThrows(IndexOutOfBoundsException::class.java) { first[-1] }
        assertThrows(IndexOutOfBoundsException::class.java) { first[50] }
        assertThrows(IndexOutOfBoundsException::class.java) { first.subList(0, 51) }
        assertEquals(50, first.size)
    }

    @Test fun largePageSizesDoNotOverflowAndEmptyHeroInputStaysEmpty() {
        val snapshot = LibraryPagingSnapshot((0 until 50_003).toList(), Int.MAX_VALUE / 2 + 1)
        assertEquals(50_003, snapshot.through(Int.MAX_VALUE).size)
        assertTrue(LibraryPagingSnapshot<Int>(emptyList(), 50, true).through(0).isEmpty())
    }

    @Test fun heroDoesNotHideTheLastGameOrCreateAnExtraPage() {
        val snapshot = LibraryPagingSnapshot(listOf("hero") + (1..101).map(Int::toString), 50, true)
        assertEquals(101, snapshot.totalGames)
        assertEquals(51, snapshot.through(0).size)
        assertEquals(102, snapshot.through(2).size)
        assertEquals("101", snapshot.through(2).last())
        assertEquals(0, LibraryPagingSnapshot(listOf("hero"), 50, true).lastPage)
        assertEquals(0, LibraryPagingSnapshot<Int>(emptyList(), 50).through(0).size)
    }

    @Test fun prefetchesBeforeTheBoundaryAndRetriesWhenFilteringFinishes() {
        assertFalse(shouldPrefetchLibraryPage(30, 50, 1, 4, false))
        assertTrue(shouldPrefetchLibraryPage(38, 50, 1, 4, false))
        assertFalse(shouldPrefetchLibraryPage(49, 50, 1, 4, true))
        assertTrue(shouldPrefetchLibraryPage(49, 50, 1, 4, false))
        assertFalse(shouldPrefetchLibraryPage(49, 50, 4, 4, false))
        assertTrue(shouldPrefetchLibraryPage(49, 51, 1, 2, false))
    }

    @Test fun stationaryViewportDoesNotPrefetchTheWholeLargeLibraryEvenWithTinyPages() {
        for (pageSize in listOf(1, 10, 50, 100)) {
            val snapshot = LibraryPagingSnapshot((0 until 50_000).toList(), pageSize)
            var page = 0
            while (shouldPrefetchLibraryPage(23, snapshot.through(page).size, page + 1, snapshot.lastPage + 1, false)) {
                page++
                assertTrue("Runaway prefetch at page size $pageSize", page < 40)
            }
            assertTrue(snapshot.through(page).size <= 100)
            assertFalse(shouldPrefetchLibraryPage(-1, snapshot.through(page).size, page + 1, snapshot.lastPage + 1, false))
        }
    }

    @Test fun deepPageFilterShrinkAndPageSizeChangesStayBoundedWithoutMutatingOlderSnapshots() {
        val large = LibraryPagingSnapshot((0 until 50_000).toList(), 50)
        val oldPage = large.through(800)
        val filtered = LibraryPagingSnapshot(listOf(40_010, 40_014, 45_000), 50)
        assertEquals(listOf(40_010, 40_014, 45_000), filtered.through(800))
        assertEquals(0, filtered.lastPage)
        assertEquals(40_050, oldPage.size)
        assertEquals(40_049, oldPage.last())
        assertEquals(0, LibraryPagingSnapshot<Int>(emptyList(), 50).through(800).size)
        assertEquals(50_000, LibraryPagingSnapshot((0 until 50_000).toList(), Int.MAX_VALUE).through(Int.MAX_VALUE).size)
    }

    @Test fun oldVisiblePrefixDoesNotRetainUnloadedLibraryMetadata() {
        val source = (0 until 50_000).toList()
        val first = LibraryPagingSnapshot(source, 50).through(0)
        val chunksField = first.javaClass.getDeclaredField("chunks").apply { isAccessible = true }
        val chunks = chunksField.get(first) as List<*>
        assertEquals(1, chunks.size)
        assertEquals(128, (chunks.single() as List<*>).size)
        assertEquals((0 until 50).toList(), first)
    }
}
