package app.gamenative.utils

import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageUtilsTest {

    private val defaultStorageUuid = UUID.fromString("41217664-9172-527a-b3d5-edabb50a7d69")

    @Test
    fun `built-in primary storage is not a separate install target`() {
        assertFalse(
            StorageUtils.isNonDefaultPrimaryStorage(
                resolvedStorageUuid = defaultStorageUuid,
                legacyVolumeUuid = null,
                isPhysicalPrimary = false,
                allowLegacyUuidFallback = false,
                defaultStorageUuid = defaultStorageUuid,
            ),
        )
    }

    @Test
    fun `default UUID overrides weaker physical and legacy signals`() {
        assertFalse(
            StorageUtils.isNonDefaultPrimaryStorage(
                resolvedStorageUuid = defaultStorageUuid,
                legacyVolumeUuid = "ABCD-1234",
                isPhysicalPrimary = true,
                allowLegacyUuidFallback = true,
                defaultStorageUuid = defaultStorageUuid,
            ),
        )
    }

    @Test
    fun `adopted primary storage is an install target`() {
        assertTrue(
            StorageUtils.isNonDefaultPrimaryStorage(
                resolvedStorageUuid = UUID.fromString("12345678-1234-1234-1234-123456789abc"),
                legacyVolumeUuid = null,
                isPhysicalPrimary = false,
                allowLegacyUuidFallback = false,
                defaultStorageUuid = defaultStorageUuid,
            ),
        )
    }

    @Test
    fun `legacy adopted primary falls back to its volume UUID`() {
        assertTrue(
            StorageUtils.isNonDefaultPrimaryStorage(
                resolvedStorageUuid = null,
                legacyVolumeUuid = "ABCD-1234",
                isPhysicalPrimary = false,
                allowLegacyUuidFallback = true,
                defaultStorageUuid = defaultStorageUuid,
            ),
        )
    }

    @Test
    fun `modern primary does not use a raw UUID when its typed UUID is unavailable`() {
        assertFalse(
            StorageUtils.isNonDefaultPrimaryStorage(
                resolvedStorageUuid = null,
                legacyVolumeUuid = "ABCD-1234",
                isPhysicalPrimary = false,
                allowLegacyUuidFallback = false,
                defaultStorageUuid = defaultStorageUuid,
            ),
        )
    }

    @Test
    fun `physical primary storage remains an install target when UUID lookup fails`() {
        assertTrue(
            StorageUtils.isNonDefaultPrimaryStorage(
                resolvedStorageUuid = null,
                legacyVolumeUuid = null,
                isPhysicalPrimary = true,
                allowLegacyUuidFallback = false,
                defaultStorageUuid = defaultStorageUuid,
            ),
        )
    }

    @Test
    fun `unidentified emulated primary storage is not an install target`() {
        assertFalse(
            StorageUtils.isNonDefaultPrimaryStorage(
                resolvedStorageUuid = null,
                legacyVolumeUuid = null,
                isPhysicalPrimary = false,
                allowLegacyUuidFallback = true,
                defaultStorageUuid = defaultStorageUuid,
            ),
        )
    }
}
