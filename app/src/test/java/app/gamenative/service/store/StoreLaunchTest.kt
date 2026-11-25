package app.gamenative.service.store

import org.junit.Assert.assertEquals
import org.junit.Test

class StoreLaunchTest {
    @Test
    fun mergeDllOverrides_appendsToExistingList() {
        assertEquals("d3d11=n,b;dinput8=n", StoreLaunch.mergeDllOverrides("d3d11=n,b", "dinput8=n"))
    }

    @Test
    fun mergeDllOverrides_storeModeWinsForSameDll() {
        assertEquals(
            "d3d11=b;dxgi=n;dinput8=n",
            StoreLaunch.mergeDllOverrides("d3d11,dinput8.dll=b;dxgi=n", "dinput8=n"),
        )
    }

    @Test
    fun mergeDllOverrides_emptyCurrent() {
        assertEquals("dinput8=n", StoreLaunch.mergeDllOverrides("", "dinput8=n"))
    }
}
