package app.gamenative.utils.launchdependencies

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.GameSource
import app.gamenative.service.SteamService
import com.winlator.container.Container
import com.winlator.core.TarCompressorUtils
import com.winlator.xenvironment.ImageFs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class BionicDefaultProtonDependencyTest {
    private lateinit var context: Context
    private lateinit var container: Container

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        container = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun appliesTo_returnsFalse_whenContainerIsNotBionic() {
        every { container.containerVariant } returns "glibc"
        every { container.wineVersion } returns "proton-9.0-arm64ec"

        val result = BionicDefaultProtonDependency.appliesTo(container, GameSource.STEAM, 1)

        assertFalse(result)
    }

    @Test
    fun appliesTo_returnsTrue_whenBionicAndSupportedProtonVersion() {
        every { container.containerVariant } returns Container.BIONIC
        every { container.wineVersion } returns "proton-9.0-arm64ec"

        val armResult = BionicDefaultProtonDependency.appliesTo(container, GameSource.STEAM, 2)

        every { container.wineVersion } returns "proton-9.0-x86_64"
        val x64Result = BionicDefaultProtonDependency.appliesTo(container, GameSource.STEAM, 3)

        assertTrue(armResult)
        assertTrue(x64Result)
    }

    @Test
    fun isSatisfied_returnsTrue_whenProtonBinDirectoryExists() {
        every { container.wineVersion } returns "proton-9.0-arm64ec"

        val imageFsRoot = ImageFs.find(context).rootDir
        val binDir = File(imageFsRoot, "opt/proton-9.0-arm64ec/bin")
        binDir.mkdirs()

        val result = BionicDefaultProtonDependency.isSatisfied(context, container, GameSource.STEAM, 4)

        assertTrue(result)
    }

    @Test
    fun getLoadingMessage_returnsExpectedMessagePerVersion() {
        every { container.wineVersion } returns "proton-9.0-arm64ec"
        val armMessage = BionicDefaultProtonDependency.getLoadingMessage(context, container, GameSource.STEAM, 5)

        every { container.wineVersion } returns "proton-9.0-x86_64"
        val x64Message = BionicDefaultProtonDependency.getLoadingMessage(context, container, GameSource.STEAM, 6)

        every { container.wineVersion } returns "wine-ge-custom"
        val fallbackMessage = BionicDefaultProtonDependency.getLoadingMessage(context, container, GameSource.STEAM, 7)

        assertEquals("Downloading arm64ec Proton", armMessage)
        assertEquals("Downloading x86_64 Proton", x64Message)
        assertEquals("Extracting Proton", fallbackMessage)
    }

    @Test
    fun install_returnsEarly_whenProtonVersionIsUnsupported() = runBlocking {
        every { container.wineVersion } returns "wine-ge-custom"
        mockkObject(SteamService.Companion)
        mockkStatic(TarCompressorUtils::class)

        BionicDefaultProtonDependency.install(
            context = context,
            container = container,
            callbacks = LaunchDependencyCallbacks({}, {}),
            gameSource = GameSource.STEAM,
            gameId = 8,
        )

        verify(exactly = 0) {
            SteamService.downloadFile(
                onDownloadProgress = any(),
                parentScope = any(),
                context = any(),
                fileName = any(),
            )
        }
    }

    @Test
    fun install_downloadsArchive_andForwardsProgress() = runBlocking {
        every { container.wineVersion } returns "proton-9.0-arm64ec"
        mockkObject(SteamService.Companion)

        val imageFsRoot = ImageFs.find(context).rootDir
        val binDir = File(imageFsRoot, "opt/proton-9.0-arm64ec/bin")
        binDir.mkdirs()

        every { SteamService.isFileInstallable(context, "proton-9.0-arm64ec.txz") } returns false

        val onProgressSlot = slot<(Float) -> Unit>()
        val deferred = mockk<Deferred<Unit>>()
        every {
            SteamService.downloadFile(
                onDownloadProgress = capture(onProgressSlot),
                parentScope = any(),
                context = context,
                fileName = "proton-9.0-arm64ec.txz",
            )
        } returns deferred
        coEvery { deferred.await() } returns Unit

        val progressValues = mutableListOf<Float>()
        BionicDefaultProtonDependency.install(
            context = context,
            container = container,
            callbacks = LaunchDependencyCallbacks(
                setLoadingMessage = {},
                setLoadingProgress = { progressValues += it },
            ),
            gameSource = GameSource.STEAM,
            gameId = 9,
        )

        onProgressSlot.captured(0.42f)

        verify(exactly = 1) {
            SteamService.downloadFile(
                onDownloadProgress = any(),
                parentScope = any(),
                context = context,
                fileName = "proton-9.0-arm64ec.txz",
            )
        }
        assertEquals(listOf(0.42f), progressValues)
    }
}
