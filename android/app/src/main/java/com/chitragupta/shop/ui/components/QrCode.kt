package com.chitragupta.shop.ui.components

import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Dark modules of a QR code as (x, y, width) runs, plus the grid size. */
data class QrGrid(val size: Int, val runs: List<Triple<Float, Float, Float>>)

/**
 * The server returns the QR as a base64 SVG data URL from the `qrcode` package: a viewBox plus one path of horizontal
 * runs ("M4 4.5h7m1 0h2..."). Parsing that is cheaper and sharper than adding an SVG decoder.
 */
fun parseQrSvg(dataUrl: String): QrGrid? = runCatching {
    val svg = String(Base64.decode(dataUrl.substringAfter("base64,"), Base64.DEFAULT))
    val size = Regex("""viewBox="0 0 (\d+) \d+"""").find(svg)!!.groupValues[1].toInt()
    // the dark path is the one with a stroke colour, not the background rect's fill
    val d = Regex("""<path[^>]*stroke="#000[^"]*"[^>]*d="([^"]+)"""").find(svg)?.groupValues?.get(1)
        ?: Regex("""<path[^>]*d="([^"]+)"[^>]*stroke="#000""").find(svg)!!.groupValues[1]
    val runs = ArrayList<Triple<Float, Float, Float>>()
    var x = 0f; var y = 0f
    Regex("""([Mmh])\s*(-?[\d.]+)(?:[\s,]+(-?[\d.]+))?""").findAll(d).forEach { m ->
        val a = m.groupValues[2].toFloat(); val b = m.groupValues[3].takeIf { it.isNotEmpty() }?.toFloat()
        when (m.groupValues[1]) {
            "M" -> { x = a; y = b!! - 0.5f }
            "m" -> { x += a; y += b!! }
            "h" -> { runs.add(Triple(x, y, a)); x += a }
        }
    }
    QrGrid(size, runs)
}.getOrNull()

@Composable
fun UpiQr(dataUrl: String, modifier: Modifier = Modifier) {
    val grid = remember(dataUrl) { parseQrSvg(dataUrl) } ?: return
    Canvas(modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(8.dp)
        .semantics { contentDescription = "UPI payment QR code" }) {
        val u = size.width / grid.size
        grid.runs.forEach { (x, y, w) -> drawRect(Color.Black, Offset(x * u, y * u), Size(w * u + 0.5f, u + 0.5f)) }
    }
}
