/**
 * Sonara (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.tvlink

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * A QR code in Sonara's look: round dots, the three corner marks as rounded squares in the
 * accent colour, and [logo] in the middle. It is made at the highest error correction (H, 30%),
 * so the logo, which covers well under that, never stops it from being read.
 */
@Composable
fun SonaraQrCode(
    text: String,
    logo: Painter,
    modifier: Modifier = Modifier,
    dot: Color = Color(0xFF0B1414),
    accent: Color = Color(0xFF13A88E),
) {
    val matrix =
        remember(text) {
            QRCodeWriter().encode(
                text,
                BarcodeFormat.QR_CODE,
                0,
                0,
                mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H),
            )
        }
    Canvas(modifier) {
        val n = matrix.width
        val cell = size.minDimension / n
        // The logo's square in the middle, in whole modules, kept clear of dots.
        val logoModules = (n * LOGO_SHARE).toInt().let { if (it % 2 == n % 2) it else it + 1 }
        val logoStart = (n - logoModules) / 2
        fun inFinder(x: Int, y: Int) = (x < 7 && y < 7) || (x >= n - 7 && y < 7) || (x < 7 && y >= n - 7)
        fun inLogo(x: Int, y: Int) = x in logoStart until logoStart + logoModules && y in logoStart until logoStart + logoModules
        for (y in 0 until n) {
            for (x in 0 until n) {
                if (!matrix[x, y] || inFinder(x, y) || inLogo(x, y)) continue
                drawCircle(dot, radius = cell * DOT_SHARE, center = Offset((x + 0.5f) * cell, (y + 0.5f) * cell))
            }
        }
        // The corner marks: a rounded ring of 7 modules and a rounded block of 3 inside it.
        listOf(Offset(0f, 0f), Offset((n - 7) * cell, 0f), Offset(0f, (n - 7) * cell)).forEach { at ->
            translate(at.x, at.y) {
                drawRoundRect(
                    accent,
                    topLeft = Offset(cell / 2, cell / 2),
                    size = Size(cell * 6, cell * 6),
                    cornerRadius = CornerRadius(cell * 2f),
                    style = Stroke(width = cell),
                )
                drawRoundRect(dot, topLeft = Offset(cell * 2, cell * 2), size = Size(cell * 3, cell * 3), cornerRadius = CornerRadius(cell))
            }
        }
        // The logo on a white rounded tile.
        val tile = logoModules * cell
        val tileAt = Offset(logoStart * cell, logoStart * cell)
        drawRoundRect(Color.White, topLeft = tileAt, size = Size(tile, tile), cornerRadius = CornerRadius(cell * 1.5f))
        val inset = cell * 0.8f
        translate(tileAt.x + inset, tileAt.y + inset) {
            with(logo) { draw(Size(tile - inset * 2, tile - inset * 2)) }
        }
    }
}

/** How much of the code the logo takes (well under the 30% error correction H recovers). */
private const val LOGO_SHARE = 0.22f

/** A dot's radius as a share of a module. */
private const val DOT_SHARE = 0.42f
