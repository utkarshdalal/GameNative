package app.gamenative.theme.media

import app.gamenative.theme.model.Binding
import app.gamenative.theme.model.MediaSource
import app.gamenative.theme.model.StringOrBinding
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MediaResolverTest {

    private fun makeTempThemeDir(): File = createTempDir(prefix = "ThemeMediaTest_")

    private fun binding(varPath: String): StringOrBinding = StringOrBinding.Ref(Binding(varPath))
    private fun lit(s: String): StringOrBinding = StringOrBinding.Literal(s)

    @Test
    fun resolve_image_primaryFound_returnsPrimary() {
        val theme = makeTempThemeDir()
        try {
            val img = File(theme, "images\\card.png").apply {
                parentFile.mkdirs(); writeText("fake")
            }
            val manager = MediaSourceManager()
            val media = MediaSource.Image(src = lit("images/card.png"))
            val res = manager.resolve(media, allowVideo = false, bindingResolver = { null }, themeRoot = theme.absolutePath)
            assertTrue(res is ResolvedMedia.Image)
            val imgRes = res as ResolvedMedia.Image
            assertNotNull(imgRes.uri)
            assertTrue(imgRes.uri!!.startsWith("file://"))
            assertFalse(imgRes.usedFallback)
            assertTrue(imgRes.errors.isEmpty())
        } finally {
            theme.deleteRecursively()
        }
    }

    @Test
    fun resolve_image_primaryMissing_usesFallback() {
        val theme = makeTempThemeDir()
        try {
            val fb = File(theme, "images\\fallback.png").apply {
                parentFile.mkdirs(); writeText("fake")
            }
            val manager = MediaSourceManager()
            val media = MediaSource.Image(src = lit("images/missing.png"), fallback = lit("images/fallback.png"))
            val res = manager.resolve(media, allowVideo = false, bindingResolver = { null }, themeRoot = theme.absolutePath)
            val imgRes = res as ResolvedMedia.Image
            assertNotNull(imgRes.uri)
            assertTrue(imgRes.usedFallback)
            assertTrue(imgRes.errors.any { it.code == "FILE_NOT_FOUND" })
        } finally {
            theme.deleteRecursively()
        }
    }

    @Test
    fun resolve_video_notAllowed_returnsPosterImage() {
        val theme = makeTempThemeDir()
        try {
            val poster = File(theme, "images\\poster.jpg").apply { parentFile.mkdirs(); writeText("fake") }
            val manager = MediaSourceManager()
            val media = MediaSource.Video(
                src = lit("videos/preview.mp4"),
                poster = lit("images/poster.jpg"),
                autoplay = false,
                loop = true,
                muted = true,
            )
            val res = manager.resolve(media, allowVideo = false, bindingResolver = { null }, themeRoot = theme.absolutePath)
            assertTrue(res is ResolvedMedia.Image)
            val imgRes = res as ResolvedMedia.Image
            assertNotNull(imgRes.uri)
            assertTrue(imgRes.errors.any { it.code == "VIDEO_NOT_ALLOWED" })
        } finally {
            theme.deleteRecursively()
        }
    }

    @Test
    fun resolve_video_missing_usesFallbackImageElsePoster() {
        val theme = makeTempThemeDir()
        try {
            val fallback = File(theme, "images\\fb.jpg").apply { parentFile.mkdirs(); writeText("fake") }
            val manager = MediaSourceManager()
            val media = MediaSource.Video(
                src = lit("videos/missing.mp4"),
                poster = lit("images/also_missing.jpg"),
                fallbackImage = lit("images/fb.jpg"),
            )
            val res = manager.resolve(media, allowVideo = true, bindingResolver = { null }, themeRoot = theme.absolutePath)
            assertTrue(res is ResolvedMedia.Image)
            val imgRes = res as ResolvedMedia.Image
            assertNotNull(imgRes.uri)
            assertTrue(imgRes.usedFallback)
            assertTrue(imgRes.errors.any { it.code == "VIDEO_FALLBACK_IMAGE_USED" })
        } finally {
            theme.deleteRecursively()
        }
    }

    @Test
    fun resolve_video_found_returnsVideoWithOptionalPoster() {
        val theme = makeTempThemeDir()
        try {
            val video = File(theme, "videos\\preview.mp4").apply { parentFile.mkdirs(); writeText("fakebin") }
            val poster = File(theme, "images\\poster.jpg").apply { parentFile.mkdirs(); writeText("fake") }
            val manager = MediaSourceManager()
            val media = MediaSource.Video(
                src = lit("videos/preview.mp4"),
                poster = lit("images/poster.jpg"),
                autoplay = true,
                loop = false,
                muted = true,
            )
            val res = manager.resolve(media, allowVideo = true, bindingResolver = { null }, themeRoot = theme.absolutePath)
            assertTrue(res is ResolvedMedia.Video)
            val vRes = res as ResolvedMedia.Video
            assertNotNull(vRes.uri)
            assertNotNull(vRes.posterUri)
            assertTrue(vRes.autoplay)
            assertFalse(vRes.loop)
            assertTrue(vRes.muted)
            assertFalse(vRes.usedFallback)
        } finally {
            theme.deleteRecursively()
        }
    }
}
