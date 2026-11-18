package com.winlator.contents

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ContentsManagerInstallDirTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val type = ContentProfile.ContentType.CONTENT_TYPE_BOX64
    private val typeDir get() = ContentsManager.getContentTypeDir(context, type)

    @Before
    fun setUp() {
        ContentsManager.getContentDir(context).deleteRecursively()
        ContentsManager.getTmpDir(context).deleteRecursively()
    }

    @After
    fun tearDown() = setUp()

    private fun writeProfile(dir: File, verName: String, verCode: Int) {
        dir.mkdirs()
        val json = JSONObject()
            .put(ContentProfile.MARK_TYPE, type.toString())
            .put(ContentProfile.MARK_VERSION_NAME, verName)
            .put(ContentProfile.MARK_VERSION_CODE, verCode)
            .put(ContentProfile.MARK_DESC, "")
            .put(ContentProfile.MARK_FILE_LIST, JSONArray())
        File(dir, ContentsManager.PROFILE_NAME).writeText(json.toString())
    }

    private fun finishInstall(mgr: ContentsManager, verName: String, verCode: Int): ContentsManager.InstallFailedReason? {
        val tmp = ContentsManager.getTmpDir(context)
        writeProfile(tmp, verName, verCode)
        val profile = mgr.readProfile(File(tmp, ContentsManager.PROFILE_NAME))
        var failure: ContentsManager.InstallFailedReason? = null
        mgr.finishInstallContent(profile, object : ContentsManager.OnInstallFinishedCallback {
            override fun onFailed(reason: ContentsManager.InstallFailedReason, e: Exception?) {
                failure = reason
            }

            override fun onSucceed(profile: ContentProfile) {}
        })
        tmp.deleteRecursively()
        return failure
    }

    @Test
    fun newInstallLandsInVersionedFolder() {
        val mgr = ContentsManager(context)
        mgr.syncContents()

        assertNull(finishInstall(mgr, "0.4.2", 6))
        assertTrue(File(typeDir, "0.4.2-6/${ContentsManager.PROFILE_NAME}").isFile)
        assertEquals(ContentsManager.InstallFailedReason.ERROR_EXIST, finishInstall(mgr, "0.4.2", 6))
    }

    @Test
    fun legacyFolderStillResolves() {
        writeProfile(File(typeDir, "0.4.2"), "0.4.2", 1)
        val mgr = ContentsManager(context)
        mgr.syncContents()

        val profile = mgr.getProfileByEntryName("box64-0.4.2-1")!!
        assertEquals(File(typeDir, "0.4.2"), ContentsManager.getInstallDir(context, profile))
    }

    @Test
    fun twoVersionCodesCoexist() {
        writeProfile(File(typeDir, "0.4.2"), "0.4.2", 1)
        val mgr = ContentsManager(context)
        mgr.syncContents()

        assertNull(finishInstall(mgr, "0.4.2", 6))
        mgr.syncContents()

        val old = mgr.getProfileByEntryName("box64-0.4.2-1")!!
        val new = mgr.getProfileByEntryName("box64-0.4.2-6")!!
        assertEquals(1, old.verCode)
        assertEquals(6, new.verCode)
        assertEquals(File(typeDir, "0.4.2"), ContentsManager.getInstallDir(context, old))
        assertEquals(File(typeDir, "0.4.2-6"), ContentsManager.getInstallDir(context, new))
    }
}
