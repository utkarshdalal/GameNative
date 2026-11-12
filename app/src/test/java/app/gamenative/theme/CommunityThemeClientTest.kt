package app.gamenative.theme

import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CommunityThemeClientTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class Fake(var respond: (CommunityThemeClient.Request) -> CommunityThemeClient.Response?) : CommunityThemeClient.Transport {
        val requests = ArrayList<CommunityThemeClient.Request>()
        override suspend fun send(request: CommunityThemeClient.Request): CommunityThemeClient.Response? {
            requests.add(request)
            return respond(request)
        }
    }

    private fun ok(body: String, code: Int = 200) = CommunityThemeClient.Response(code, body.toByteArray())

    private fun client(fake: Fake) = CommunityThemeClient(fake, baseUrl = { "https://api.test" }, boundary = { "BOUNDARY" })

    private fun argb(hex: String) = CssValues.parseColor(hex)!!.toArgb()

    private fun fails(code: String, block: suspend () -> Unit): CommunityException {
        try {
            runBlocking { block() }
        } catch (e: CommunityException) {
            assertEquals(code, e.code)
            return e
        }
        fail("expected $code")
        throw AssertionError()
    }

    private val listBody = """
        {"themes":[
          {"id":"a1","name":"Blue","description":"Calm","author_name":"Sam","version":3,"downloads":42,"size_bytes":1234,
           "preview":{"primary":"#FF4FC3F7","panelOpacity":0.5,"layout":{"cardScale":1.25,"hideBadges":true},"hasFont":true,"futureKey":{"x":1}},
           "mine":true,"hidden":false,"created_at":"2026-11-01T00:00:00Z","updated_at":"2026-11-02T00:00:00Z"},
          {"id":"b2","name":"Plain","description":null,"author_name":null,"version":1,"downloads":0,"size_bytes":10,"preview":null,"mine":false},
          {"name":"no id"}
        ],"page":2,"has_more":true}
    """.trimIndent()

    @Test
    fun listSendsTheQueryAndParsesThemes() = runBlocking {
        val fake = Fake { ok(listBody) }
        val page = client(fake).list(" neon blue ", 2, CommunitySort.NEW, mineOnly = true)
        val r = fake.requests.single()
        assertEquals("GET", r.method)
        assertEquals("https://api.test/api/themes?q=neon+blue&page=2&per_page=20&sort=new&mine=1", r.url)
        assertEquals(CommunityThemeClient.Auth.REQUIRED, r.auth)
        assertTrue(page.hasMore)
        assertEquals(2, page.themes.size)
        val a = page.themes[0]
        assertEquals(
            CommunityTheme(
                id = "a1", name = "Blue", description = "Calm", authorName = "Sam", version = 3, downloads = 42, sizeBytes = 1234,
                preview = ThemeSpec(primary = argb("#4fc3f7"), panelOpacity = 0.5f, layout = ThemeLayout(cardScale = 1.25f, hideBadges = true)),
                mine = true, updatedAt = "2026-11-02T00:00:00Z",
            ),
            a,
        )
        assertEquals(CommunityTheme(id = "b2", name = "Plain", version = 1, sizeBytes = 10), page.themes[1])
    }

    @Test
    fun hiddenUploadsCarryTheirReason() {
        val page = CommunityThemeClient.parsePage(
            """
            {"themes":[
              {"id":"h1","name":"Gone","mine":true,"hidden":true,"hidden_reason":"copyright"},
              {"id":"h2","name":"Quiet","mine":true,"hidden":true},
              {"id":"h3","name":"Shown","mine":true,"hidden":false,"hidden_reason":"reports"}
            ],"has_more":false}
            """.trimIndent(),
        )
        val (gone, quiet, shown) = page.themes
        assertTrue(gone.hidden)
        assertEquals("copyright", gone.hiddenReason)
        assertTrue(quiet.hidden)
        assertNull(quiet.hiddenReason)
        assertFalse(shown.hidden)
        assertNull(shown.hiddenReason)
    }

    @Test
    fun termsArePublicAndParsed() = runBlocking {
        val fake = Fake {
            ok("""{"version":"2026-11-12","upload_terms":"Be nice.\nOnly your own work.","copyright_policy":"Send notices to us.","contact":"legal@example.test"}""")
        }
        val terms = client(fake).terms()
        val r = fake.requests.single()
        assertEquals("GET", r.method)
        assertEquals("https://api.test/api/themes/terms", r.url)
        assertEquals(CommunityThemeClient.Auth.NONE, r.auth)
        assertEquals(CommunityTerms("2026-11-12", "Be nice.\nOnly your own work.", "Send notices to us.", "legal@example.test"), terms)

        fake.respond = { ok("""{"upload_terms":"T"}""") }
        fails("server") { client(fake).terms() }
        fake.respond = { ok("""{"version":"  "}""") }
        fails("server") { client(fake).terms() }
        assertNull(CommunityThemeClient.parseTerms("""{"version":"v2","upload_terms":"T","copyright_policy":"C","contact":null}""").contact)
        assertNull(CommunityThemeClient.parseTerms("""{"version":"v2","upload_terms":"T","copyright_policy":"C","contact":"  "}""").contact)
        assertEquals("", CommunityThemeClient.parseTerms("""{"version":"v2"}""").uploadTerms)
    }

    @Test
    fun publicListIsOptionalAuthWithoutEmptyQuery() = runBlocking {
        val fake = Fake { ok("""{"themes":[],"page":1,"has_more":false}""") }
        val page = client(fake).list("", 0, CommunitySort.POPULAR, mineOnly = false)
        assertEquals("https://api.test/api/themes?page=1&per_page=20&sort=popular", fake.requests.single().url)
        assertEquals(CommunityThemeClient.Auth.OPTIONAL, fake.requests.single().auth)
        assertTrue(page.themes.isEmpty())
        assertFalse(page.hasMore)
    }

    @Test
    fun errorsMapToCodes() {
        val fake = Fake { ok("""{"error":"too_large","message":"Zip is too big"}""", 413) }
        val c = client(fake)
        assertEquals("Zip is too big", fails("too_large") { c.delete("x") }.message)
        fake.respond = { ok("""{"error":"text_rejected","message":"Name not allowed"}""", 422) }
        fails("text_rejected") { c.delete("x") }
        fake.respond = { ok("""{"error":"invalid_theme","message":"Bad css"}""", 422) }
        fails("invalid_theme") { c.delete("x") }
        fake.respond = { ok("", 429) }
        fails("rate_limited") { c.delete("x") }
        fake.respond = { ok("""{"error":"not_found"}""", 404) }
        assertEquals("not_found", fails("not_found") { c.delete("x") }.message)
        fake.respond = { ok("""{"error":"terms_required","message":"Accept the terms"}""", 422) }
        assertEquals("Accept the terms", fails("terms_required") { c.delete("x") }.message)
        fake.respond = { ok("""{"error":"terms_outdated"}""", 422) }
        fails("terms_outdated") { c.delete("x") }
        fake.respond = { ok("""{"error":"forbidden"}""", 403) }
        fails("forbidden") { c.delete("x") }
        fake.respond = { ok("", 403) }
        fails("forbidden") { c.delete("x") }
        fake.respond = { ok("<html>oops</html>", 502) }
        fails("server") { c.delete("x") }
        fake.respond = { ok("""{"error":"something_new"}""", 500) }
        fails("server") { c.delete("x") }
        fake.respond = { ok("", 401) }
        fails("unauthorized") { c.delete("x") }
        fake.respond = { null }
        fails("unauthorized") { c.delete("x") }
        fake.respond = { throw IOException("timeout") }
        fails("network") { c.list("", 1, CommunitySort.POPULAR, false) }
        fake.respond = { ok("not json") }
        fails("server") { c.list("", 1, CommunitySort.POPULAR, false) }
    }

    @Test
    fun uploadIsMultipartWithMetaAndZip() = runBlocking {
        val fake = Fake { ok("""{"id":"new-id","version":2}""", 201) }
        val zip = byteArrayOf(0x50, 0x4b, 3, 4, 0, -1, 10, 13)
        val preview = ThemeSpec(primary = argb("#123456"), fontFile = "/abs/font.ttf")
        val meta = CommunityThemeClient.uploadMeta("Blue", "Calm", "Sam", preview, "old-id", "2026-11-12")
        val up = client(fake).upload(meta, zip)
        assertEquals(CommunityThemeClient.Upload("new-id", 2), up)
        val r = fake.requests.single()
        assertEquals("POST", r.method)
        assertEquals("https://api.test/api/themes", r.url)
        assertEquals(CommunityThemeClient.Auth.REQUIRED, r.auth)
        assertEquals("multipart/form-data; boundary=BOUNDARY", r.contentType)
        val body = r.body!!
        val head = "--BOUNDARY\r\nContent-Disposition: form-data; name=\"meta\"\r\n\r\n$meta\r\n" +
            "--BOUNDARY\r\nContent-Disposition: form-data; name=\"file\"; filename=\"theme.zip\"\r\nContent-Type: application/zip\r\n\r\n"
        val tail = "\r\n--BOUNDARY--\r\n"
        assertArrayEquals(head.toByteArray() + zip + tail.toByteArray(), body)

        val m = Json.parseToJsonElement(meta.toString()).jsonObject
        assertEquals(setOf("name", "description", "author_name", "preview", "replaces_id", "terms_version"), m.keys)
        assertEquals("old-id", m["replaces_id"]!!.jsonPrimitive.content)
        assertEquals("2026-11-12", m["terms_version"]!!.jsonPrimitive.content)
        val p = m["preview"]!!.jsonObject
        assertEquals("#FF123456", p["primary"]!!.jsonPrimitive.content)
        assertEquals("true", p["hasFont"]!!.jsonPrimitive.content)
        assertFalse(p.toString().contains("/abs/"))
        assertEquals(JsonNull, CommunityThemeClient.uploadMeta("B", "", "S", null, null, "v")["replaces_id"])
        assertEquals(JsonNull, CommunityThemeClient.uploadMeta("B", "", "S", null, null, "v")["preview"])
    }

    @Test
    fun reportDeleteAndDownloadRequests() = runBlocking {
        val fake = Fake { r -> if (r.url.endsWith("/download")) ok("PKzip") else CommunityThemeClient.Response(204, ByteArray(0)) }
        val c = client(fake)
        c.report("t-1", "copyright", "Copied from somewhere")
        c.report("t-1", "broken", "  ")
        c.delete("t-1")
        val dest = File(tmp.root, "dl.zip")
        c.download("t-1", dest)
        val (rep, repNull, del, dl) = fake.requests
        assertEquals("POST", rep.method)
        assertEquals("https://api.test/api/themes/t-1/report", rep.url)
        assertEquals("application/json", rep.contentType)
        val o = Json.parseToJsonElement(rep.body!!.decodeToString()) as JsonObject
        assertEquals("copyright", o["reason"]!!.jsonPrimitive.content)
        assertEquals("Copied from somewhere", o["details"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, (Json.parseToJsonElement(repNull.body!!.decodeToString()) as JsonObject)["details"])
        assertEquals("DELETE", del.method)
        assertEquals("https://api.test/api/themes/t-1", del.url)
        assertEquals(CommunityThemeClient.Auth.REQUIRED, del.auth)
        assertEquals("https://api.test/api/themes/t-1/download", dl.url)
        assertEquals(CommunityThemeClient.MAX_DOWNLOAD_BYTES, dl.maxResponseBytes)
        assertEquals("PKzip", dest.readText())
    }

    @Test
    fun idsAreKeptToSafeCharacters() {
        assertEquals("abc-1_2", CommunityThemeClient.encodeId("abc-1_2"))
        assertEquals("etcpasswd", CommunityThemeClient.encodeId("../etc/passwd"))
    }

    @Test
    fun previewRoundTrips() {
        val spec = ThemeSpec(
            background = argb("#10141c"),
            surface = 0x80182030.toInt(),
            surfaceVariant = argb("#202a3c"),
            primary = argb("#4fc3f7"),
            onPrimary = argb("#000000"),
            text = argb("#e8edf5"),
            textMuted = argb("#9aa6b8"),
            outline = argb("#334455"),
            focus = argb("#ff8800"),
            bar = 0x00000000,
            cornerRadiusDp = 12f,
            backgroundGradient = ThemeGradient(135f, listOf(argb("#000000"), argb("#ffffff")), listOf(0f, 1f)),
            panelOpacity = 0.85f,
            blurDp = 6f,
            card = argb("#222c40"),
            button = argb("#2a3448"),
            onButton = argb("#f0f0f0"),
            toggle = argb("#00c853"),
            menu = argb("#182030"),
            onMenu = argb("#dddddd"),
            accentGradient = ThemeGradient(90f, listOf(argb("#ff0000"), argb("#0000ff"))),
            focusGlowDp = 9f,
            layout = ThemeLayout(
                cardScale = 1.25f, libraryLayout = "grid", titleAlign = "center", titleStrokeDp = 1.5f, titleStrokeColor = argb("#101010"),
                hideBadges = true, hideFooterHints = false, heroScale = 0.75f, cardTiltDeg = -7.5f, cardCornerRadiusDp = 3f,
            ),
        )
        val json = CommunityThemeClient.previewJson(spec.copy(fontFile = "/a/f.ttf", backgroundImage = "/a/b.png"))
        assertEquals("#80182030", json["surface"]!!.jsonPrimitive.content)
        assertEquals("#00000000", json["bar"]!!.jsonPrimitive.content)
        assertEquals("true", json["hasBackgroundImage"]!!.jsonPrimitive.content)
        val g = json["backgroundGradient"]!!.jsonObject
        assertEquals(setOf("angle", "colors", "stops"), g.keys)
        assertTrue(json["layout"] is JsonObject)
        val back = CommunityThemeClient.parsePreview(Json.parseToJsonElement(json.toString()) as JsonObject)
        assertEquals(spec, back)
    }

    @Test
    fun previewToleratesGarbage() {
        val o = Json.parseToJsonElement(
            """{"background":5,"primary":"zz","text":"#FF010203","surface":"#112233","cornerRadiusDp":"12","layout":"x",
               "accentGradient":{"colors":["#GG"]},"backgroundGradient":{"angle":45,"colors":"no"},"panelOpacity":7,"unknown":[1,2]}""",
        ) as JsonObject
        assertEquals(
            ThemeSpec(text = 0xFF010203.toInt(), surface = argb("#112233"), panelOpacity = 1f),
            CommunityThemeClient.parsePreview(o),
        )
        assertNull(CommunityThemeClient.parsePreview(JsonObject(emptyMap())))
        val page = CommunityThemeClient.parsePage("""{"themes":[{"id":"x","name":"N","preview":"bad","version":"2"}]}""")
        assertNull(page.themes.single().preview)
        assertFalse(page.hasMore)
    }

    @Test
    fun listWithoutAThemeArrayIsAServerError() {
        val fake = Fake { CommunityThemeClient.Response(200, """{"updateAvailable":true,"versionCode":8}""".toByteArray()) }
        fails("server") { client(fake).list("", 1, CommunitySort.POPULAR, mineOnly = false) }
        assertTrue(CommunityThemeClient.parsePage("""{"themes":[]}""").themes.isEmpty())
    }

    @Test
    fun communityVersionComparison() {
        assertEquals("3", CommunityThemeClient.updateVersion(3, 2))
        assertNull(CommunityThemeClient.updateVersion(2, 2))
        assertNull(CommunityThemeClient.updateVersion(1, 2))
        assertEquals("1", CommunityThemeClient.updateVersion(1, null))
    }
}
