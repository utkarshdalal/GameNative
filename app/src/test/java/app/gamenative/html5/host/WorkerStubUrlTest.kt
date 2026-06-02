package app.gamenative.html5.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkerStubUrlTest {
    @Test fun build_encodesOriginalUrl() {
        val url = WorkerStubUrl.build("https://example.test/c3/workermain.js?v=1")
        assertEquals("/_worker_stub?orig=https%3A%2F%2Fexample.test%2Fc3%2Fworkermain.js%3Fv%3D1", url)
    }

    @Test fun parseOrig_returnsDecoded() {
        assertEquals(
            "https://example.test/c3/workermain.js",
            WorkerStubUrl.parseOrig("orig=https%3A%2F%2Fexample.test%2Fc3%2Fworkermain.js"),
        )
    }

    @Test fun parseOrig_handlesAdditionalParams() {
        assertEquals("/scripts/main.js", WorkerStubUrl.parseOrig("orig=%2Fscripts%2Fmain.js&debug=1"))
    }

    @Test fun parseOrig_returnsNullWhenMissing() {
        assertNull(WorkerStubUrl.parseOrig(null))
        assertNull(WorkerStubUrl.parseOrig(""))
        assertNull(WorkerStubUrl.parseOrig("foo=bar"))
    }

    @Test fun roundtrip_preservesOriginal() {
        val src = "/scripts/c3runtime.js"
        assertEquals(src, WorkerStubUrl.parseOrig(WorkerStubUrl.build(src).removePrefix("/_worker_stub?")))
    }
}
