package app.gamenative.service.store

import org.junit.Assert.assertEquals
import org.junit.Test

class StoreLaunchTest {
    @Test
    fun mergeDllOverrides_appendsToExistingList() {
        assertEquals("d3d11=n,b;xgameruntime=n", StoreLaunch.mergeDllOverrides("d3d11=n,b", "xgameruntime=n"))
    }

    @Test
    fun mergeDllOverrides_storeModeWinsForSameDll() {
        assertEquals(
            "d3d11=b;dxgi=n;xgameruntime=n",
            StoreLaunch.mergeDllOverrides("d3d11,xgameruntime.dll=b;dxgi=n", "xgameruntime=n"),
        )
    }

    @Test
    fun mergeDllOverrides_emptyCurrent() {
        assertEquals("xgameruntime=n", StoreLaunch.mergeDllOverrides("", "xgameruntime=n"))
    }
}
