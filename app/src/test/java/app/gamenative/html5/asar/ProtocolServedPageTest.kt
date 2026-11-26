package app.gamenative.html5.asar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// synthetic minified mains in the shape electron bundlers emit: short names reused across scopes, the url
// built from template literals, the entry page as a string in the https handler.
class ProtocolServedPageTest {

    private val main = """
        "use strict";const e=require("electron"),t=require("node:path");function q(){const x="";return x}
        const n=require("node:fs"),h="example.test",r=new Set([h,`www.${'$'}{h}`,`beta.${'$'}{h}`]),x="/__game__/",u=`https://${'$'}{h}${'$'}{x}index.html`;
        const w=t.join(process.resourcesPath,"web","root");e.app.userAgentFallback=`${'$'}{e.app.userAgentFallback} Game/1.0 (b1)`;
        async function f(i){const s=new URL(i.url);if(r.has(s.hostname)&&s.pathname.startsWith(x)){if(s.pathname===`${'$'}{x}index.html`)return new Response('<!DOCTYPE html><body><script>window.CFG={a:"b"}<\/script><script src="./js/main.js"><\/script></body>',{headers:{"content-type":"text/html"}});return e.net.fetch("file://"+t.join(w,s.pathname.slice(10)))}return e.net.fetch(i,{bypassCustomProtocolHandlers:!0})}
        function g(){let x="level";return x}
        e.app.whenReady().then(()=>{e.protocol.handle("https",f);new e.BrowserWindow({url:u}).loadURL(u)});
    """.trimIndent()

    @Test
    fun parsesEveryPieceFromMain() {
        val page = ProtocolServedPage.parse(main)
        assertNotNull(page)
        page!!
        assertEquals("example.test", page.host)
        assertEquals(setOf("www.example.test", "beta.example.test"), page.aliasHosts)
        assertEquals("/__game__/", page.prefix)
        assertEquals("index.html", page.entryName)
        assertEquals("web/root", page.resourceDir)
        assertEquals("Game/1.0 (b1)", page.userAgentSuffix)
        assertEquals("https://example.test/__game__/index.html", page.entryUrl)
    }

    @Test
    fun entryHtmlIsTheCookedStringValue() {
        val html = ProtocolServedPage.parse(main)!!.entryHtml!!
        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertTrue("escaped closing tags are unescaped", html.contains("""<script src="./js/main.js"></script>"""))
        assertFalse(html.contains("<\\/"))
    }

    @Test
    fun reusedNamesResolveToTheNearestEarlierBinding() {
        // x is bound to "" before and "level" after the declaration the url template reads.
        assertEquals("/__game__/", ProtocolServedPage.parse(main)!!.prefix)
    }

    @Test
    fun noHttpsHandlerMeansNoPage() {
        assertNull(ProtocolServedPage.parse(main.replace("""e.protocol.handle("https",f);""", "")))
    }

    @Test
    fun handlerInACommentDoesNotCount() {
        val commented = main.replace("""e.protocol.handle("https",f);""", """/* e.protocol.handle("https",f); */""")
        assertNull(ProtocolServedPage.parse(commented))
    }

    @Test
    fun customSchemeHandlerIsNotThisShape() {
        assertNull(ProtocolServedPage.parse(main.replace("""protocol.handle("https",f)""", """protocol.handle("app",f)""")))
    }

    @Test
    fun withoutAResourcesDirThereIsNothingToServe() {
        assertNull(ProtocolServedPage.parse(main.replace("""t.join(process.resourcesPath,"web","root")""", """t.join(__dirname,"web")""")))
    }

    @Test
    fun entryWithoutAnInlinePageComesFromDisk() {
        val noInline = main.replace(Regex("""return new Response\('[^']*',\{headers:\{"content-type":"text/html"\}\}\);"""), "")
        val page = ProtocolServedPage.parse(noInline)
        assertNotNull(page)
        assertNull(page!!.entryHtml)
    }

    @Test
    fun severalPageUrlsPickTheOneTheWindowLoads() {
        val two = main.replace(
            """function g(){let x="level";return x}""",
            """const help=`https://${'$'}{h}/help/faq.html`;function g(){let x="level";return x}""",
        )
        assertEquals("https://example.test/__game__/index.html", ProtocolServedPage.parse(two)!!.entryUrl)
    }

    @Test
    fun localPathMapsOnlyThePrefixOnServedHosts() {
        val page = ProtocolServedPage.parse(main)!!
        assertEquals("js/main.js", page.localPath("https", "example.test", "/__game__/js/main.js"))
        assertEquals("a b.png", page.localPath("https", "www.example.test", "/__game__/a b.png"))
        assertNull(page.localPath("https", "example.test", "/api/levels"))
        assertNull(page.localPath("https", "cdn.other.test", "/__game__/js/main.js"))
        assertNull(page.localPath("http", "example.test", "/__game__/js/main.js"))
    }

    @Test
    fun onlyThePrimaryHostPrefixIsAPageDocument() {
        val page = ProtocolServedPage.parse(main)!!
        assertTrue(page.isPageDocument("https", "example.test", "/__game__/index.html"))
        assertFalse(page.isPageDocument("https", "www.example.test", "/__game__/index.html"))
        assertFalse(page.isPageDocument("https", "example.test", "/account"))
    }

    @Test
    fun unescapeJsHandlesTheCommonEscapes() {
        assertEquals("a\"b'c\\d</e\né€", unescapeJs("""a\"b\'c\\d<\/e\n\xe9€"""))
        assertEquals("😀", unescapeJs("""\u{1F600}"""))
    }
}
