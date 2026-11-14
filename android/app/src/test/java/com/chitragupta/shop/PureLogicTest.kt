package com.chitragupta.shop

import com.chitragupta.shop.data.AppJson
import com.chitragupta.shop.data.Order
import com.chitragupta.shop.data.TurnResponse
import com.chitragupta.shop.data.normalizeServerUrl
import com.chitragupta.shop.ui.fmtQty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PureLogicTest {
    @Test fun serverUrlGetsSchemeAndLosesTrailingSlash() {
        assertEquals("http://192.168.1.5:3000", normalizeServerUrl(" 192.168.1.5:3000/ "))
        assertEquals("https://shop.example.com", normalizeServerUrl("https://shop.example.com"))
    }
    @Test fun blankServerUrlIsRejected() { assertNull(normalizeServerUrl("   ")) }

    @Test fun quantitiesShrinkLikeTheServer() {
        assertEquals("500 g", fmtQty(0.5, "kg"))
        assertEquals("1.5 kg", fmtQty(1.5, "kg"))
        assertEquals("250 ml", fmtQty(0.25, "litre"))
        assertEquals("1 packet", fmtQty(1.0, "packet"))
        assertEquals("2 packets", fmtQty(2.0, "packet"))
        assertEquals("3", fmtQty(3.0, "plate"))
    }

    @Test fun turnResponseDecodesWithUnknownFields() {
        val json = """{"heard":"two dosa","reply":"Added","lang":"en-IN","cart":[{"id":"dosa","qty":2}],
            "lines":[{"id":"dosa","name":"Dosa","qty":2,"price":40,"unit":"plate","amount":80}],"total":80,"audio":null,"action":"none","timings":{"stt":10}}"""
        val r = AppJson.decodeFromString(TurnResponse.serializer(), json)
        assertEquals(80, r.total); assertEquals("Dosa", r.lines.single().name); assertEquals(2.0, r.cart.single().qty, 0.0)
    }

    @Test fun orderDecodesWithMissingOptionalFields() {
        val o = AppJson.decodeFromString(Order.serializer(), """{"id":"ORD1","number":7,"lines":[],"total":0,"status":"cooking"}""")
        assertEquals(7, o.number); assertNull(o.paidAt)
    }
}
