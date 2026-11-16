/**
 * Sonara (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The phone's scanner for Sonara TV's code, inside the app and upright like the rest of it: the
 * camera fills the screen and a rounded frame with Sonara's corners shows where to aim; no laser
 * line or moving light. The scanning library's own screen (sideways, a red laser, a grey mask) is
 * not used.
 */

package com.metrolist.music.tvlink

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.metrolist.music.R

/** Full-screen scanner; [onResult] gets the first code read, [onClose] leaves without one. */
@Composable
fun QrScanner(
    onResult: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val result by rememberUpdatedState(onResult)
    var allowed by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted
        denied = !granted
    }
    LaunchedEffect(Unit) { if (!allowed) ask.launch(Manifest.permission.CAMERA) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (allowed) {
            val view =
                remember {
                    BarcodeView(context).apply {
                        decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
                        var done = false
                        decodeContinuous { scanned ->
                            val text = scanned.text ?: return@decodeContinuous
                            if (!done) {
                                done = true
                                result(text)
                            }
                        }
                    }
                }
            DisposableEffect(lifecycle, view) {
                val observer =
                    LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_RESUME -> view.resume()
                            Lifecycle.Event.ON_PAUSE -> view.pause()
                            else -> Unit
                        }
                    }
                lifecycle.addObserver(observer)
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.resume()
                onDispose {
                    lifecycle.removeObserver(observer)
                    view.pause()
                }
            }
            AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
            ScanFrame()
        }
        Text(
            stringResource(if (denied) R.string.er_tv_camera else R.string.er_tv_aim),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 32.dp, vertical = 56.dp),
        )
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)) {
            Icon(painterResource(R.drawable.close), contentDescription = stringResource(android.R.string.cancel), tint = Color.White)
        }
    }
}

/** The dimmed screen with a clear rounded window and Sonara-coloured corners; nothing moves in it. */
@Composable
private fun ScanFrame() {
    val accent = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
        val side = size.minDimension * WINDOW_SHARE
        val topLeft = Offset((size.width - side) / 2, (size.height - side) / 2 - size.height * 0.05f)
        val radius = CornerRadius(side * 0.09f)
        drawRect(Color.Black.copy(alpha = 0.55f))
        drawRoundRect(Color.Transparent, topLeft, Size(side, side), radius, blendMode = BlendMode.Clear)
        // Corners: four short rounded strokes, like the TV code's corner marks.
        val arm = side * 0.16f
        val stroke = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
        val r = radius.x
        val left = topLeft.x
        val top = topLeft.y
        val right = left + side
        val bottom = top + side
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(accent, Offset(x + dx * r, y), Offset(x + dx * (r + arm), y), stroke.width, StrokeCap.Round)
            drawLine(accent, Offset(x, y + dy * r), Offset(x, y + dy * (r + arm)), stroke.width, StrokeCap.Round)
            drawArc(accent, startAngle = arcStart(dx, dy), sweepAngle = 90f, useCenter = false, topLeft = Offset(if (dx > 0) x else x - 2 * r, if (dy > 0) y else y - 2 * r), size = Size(2 * r, 2 * r), style = stroke)
        }
        corner(left, top, 1f, 1f)
        corner(right, top, -1f, 1f)
        corner(left, bottom, 1f, -1f)
        corner(right, bottom, -1f, -1f)
    }
}

private fun arcStart(dx: Float, dy: Float): Float =
    when {
        dx > 0 && dy > 0 -> 180f
        dx < 0 && dy > 0 -> 270f
        dx > 0 && dy < 0 -> 90f
        else -> 0f
    }

/** How much of the screen's short side the window takes. */
private const val WINDOW_SHARE = 0.68f
