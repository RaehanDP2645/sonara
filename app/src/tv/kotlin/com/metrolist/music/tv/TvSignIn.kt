/**
 * Sonara (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Signing Sonara TV in. The QR code and the 6-digit code are for Sonara on a phone (Settings ->
 * Sign in on a TV), which sends its session sealed for this TV (TvLink); a phone's own camera
 * opens the web page explaining that. The code changes every minute and the QR with it. Typing
 * Google's own sign-in on the TV (the phone's LoginSheet) stays as the other way.
 */

package com.metrolist.music.tv

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.metrolist.music.R
import com.metrolist.music.tvlink.TvAccount
import com.metrolist.music.tvlink.TvLink
import com.metrolist.music.ui.screens.LoginSheet
import kotlinx.coroutines.delay

/** How often the TV asks whether a phone has answered (the proxy allows 120 asks in 10 minutes). */
private const val POLL_MS = 5_000L

/** After a failed call, how long before trying again. */
private const val RETRY_MS = 5_000L

@Composable
fun TvSignIn() {
    val context = LocalContext.current
    var session by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf<String?>(null) }
    var offline by remember { mutableStateOf(false) }
    var google by remember { mutableStateOf(false) }
    val name =
        remember {
            Settings.Global.getString(context.contentResolver, "device_name")?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "TV"
        }

    // One sign-in at a time: start it, then ask every five seconds until a phone answers or it
    // runs out (10 minutes), then start a new one. The key pair never leaves this loop.
    LaunchedEffect(Unit) {
        while (true) {
            val keys = TvLink.newKeys()
            val started = TvLink.start(context, keys, name).getOrNull()
            if (started == null) {
                offline = true
                delay(RETRY_MS)
                continue
            }
            offline = false
            session = started.id
            code = started.code
            while (true) {
                delay(POLL_MS)
                val answer = TvLink.poll(context, started.id).getOrNull() ?: continue
                when (answer) {
                    is TvLink.Poll.Waiting -> code = answer.code.ifBlank { code }
                    is TvLink.Poll.Ready -> {
                        runCatching { TvLink.open(keys, started.id, answer) }.getOrNull()?.let { account ->
                            TvAccount.save(context, account)
                            return@LaunchedEffect
                        }
                        break
                    }
                    TvLink.Poll.Expired -> break
                }
            }
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Column(Modifier.widthIn(max = 520.dp)) {
                Icon(painterResource(R.drawable.logo_mark), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.tv_sign_in_title), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.tv_sign_in_steps), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(28.dp))
                Text(
                    code?.chunked(3)?.joinToString(" ") ?: "··· ···",
                    style = MaterialTheme.typography.displayMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 6.sp),
                    color = MaterialTheme.colorScheme.primary,
                )
                if (offline) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.tv_sign_in_retry), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(36.dp))
                TvButton(R.drawable.account, stringResource(R.string.tv_sign_in_google), onClick = { google = true })
            }
            Spacer(Modifier.width(80.dp))
            Box(
                Modifier
                    .size(320.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color.White)
                    .padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                session?.let { QrCode(TvLink.linkFor(it), Modifier.fillMaxSize()) }
            }
        }
    }
    if (google) LoginSheet(onClose = { google = false })
}

/** [text] as a QR code, dark modules on the white card. */
@Composable
private fun QrCode(
    text: String,
    modifier: Modifier = Modifier,
) {
    val matrix: BitMatrix =
        remember(text) {
            QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
        }
    Canvas(modifier) {
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) {
                    drawRect(Color(0xFF0B1414), topLeft = Offset(x * cell, y * cell), size = Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}
