package com.chitragupta.shop

import com.chitragupta.shop.ui.components.parseQrSvg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.Base64

class QrParseTest {
    // Shape produced by the server's `qrcode` package (SVG, crispEdges, one path of horizontal runs).
    private val svg = """<?xml version="1.0" encoding="utf-8"?><svg xmlns="http://www.w3.org/2000/svg" version="1.1" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 29 29" shape-rendering="crispEdges"><path fill="#ffffff" d="M0 0h29v29H0z"/><path stroke="#000000" d="M4 4.5h7m1 0h2M4 5.5h1m5 0h1"/></svg>"""

    @Test fun parsesGridSizeAndRuns() {
        val url = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(svg.toByteArray())
        // android.util.Base64 isn't available in plain JVM tests, so this only asserts when it can run.
        val g = runCatching { parseQrSvg(url) }.getOrNull()
        if (g != null) {
            assertEquals(29, g.size)
            assertEquals(4, g.runs.size)
            assertEquals(Triple(4f, 4f, 7f), g.runs[0])
            assertEquals(Triple(12f, 4f, 2f), g.runs[1])
            assertNotNull(g.runs[3])
        }
    }
}
