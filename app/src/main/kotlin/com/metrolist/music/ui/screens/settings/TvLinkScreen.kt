/**
 * Sonara (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Settings -> Sign in on a TV (requested by the owner): type the 6-digit code Sonara TV shows, or
 * scan its QR code; after "Sign in on <TV>?" the phone sends its session to that TV, sealed for
 * it alone (TvLink). Opened by the TV page's "Open in Sonara" link too, with the TV already found.
 */

package com.metrolist.music.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.AccountNameKey
import com.metrolist.music.tvlink.QrScanner
import com.metrolist.music.tvlink.TvAccount
import com.metrolist.music.tvlink.TvLink
import com.metrolist.music.ui.theme.Spacing
import com.metrolist.music.ui.theme.backdropTopBarColors
import com.metrolist.music.ui.theme.topBarBackdrop
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.launch

private sealed interface LinkState {
    data object Idle : LinkState

    data object Looking : LinkState

    data class Confirm(val target: TvLink.Target) : LinkState

    data class Sending(val target: TvLink.Target) : LinkState

    data class Done(val name: String) : LinkState

    data class Failed(val message: Int) : LinkState
}

private const val CODE_LENGTH = 6

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvLinkScreen(
    navController: NavController,
    session: String?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<LinkState>(LinkState.Idle) }
    var code by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    val accountName by rememberPreference(AccountNameKey, "")

    fun find(session: String? = null, code: String? = null) {
        state = LinkState.Looking
        scope.launch {
            state =
                TvLink.lookup(context, session = session, code = code).fold(
                    onSuccess = { target -> target?.let { LinkState.Confirm(it) } ?: LinkState.Failed(R.string.er_tv_not_found) },
                    onFailure = { LinkState.Failed(R.string.er_tv_failed) },
                )
        }
    }

    fun send(target: TvLink.Target) {
        state = LinkState.Sending(target)
        scope.launch {
            val account = TvAccount.current(context)
            state =
                if (account == null) {
                    LinkState.Failed(R.string.er_tv_failed)
                } else {
                    TvLink.deliver(context, target, account).fold(
                        onSuccess = { LinkState.Done(target.name) },
                        onFailure = { LinkState.Failed(R.string.er_tv_failed) },
                    )
                }
        }
    }

    LaunchedEffect(session) { session?.let { find(session = it) } }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
    ) {
        TopAppBar(
            colors = backdropTopBarColors(),
            modifier = Modifier.topBarBackdrop(),
            title = { Text(stringResource(R.string.er_tv_sign_in)) },
            navigationIcon = {
                IconButton(onClick = navController::navigateUp) {
                    Icon(painterResource(R.drawable.arrow_back), contentDescription = stringResource(R.string.er_back))
                }
            },
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.XL)
                    .padding(top = Spacing.L, bottom = Spacing.XXL),
        ) {
            TvArt(done = state is LinkState.Done)
            Spacer(Modifier.height(Spacing.XL))
            when (val now = state) {
                is LinkState.Confirm, is LinkState.Sending -> {
                    val target = (now as? LinkState.Confirm)?.target ?: (now as LinkState.Sending).target
                    Title(stringResource(R.string.er_tv_confirm, target.name))
                    if (accountName.isNotBlank()) {
                        Spacer(Modifier.height(Spacing.S))
                        Line(stringResource(R.string.er_tv_as, accountName))
                    }
                    Spacer(Modifier.height(Spacing.XXL))
                    PillButton(stringResource(R.string.er_tv_sign_in_action), busy = now is LinkState.Sending) { send(target) }
                    Spacer(Modifier.height(Spacing.S))
                    TextButton(onClick = { state = LinkState.Idle }, enabled = now !is LinkState.Sending) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }
                is LinkState.Done -> {
                    Title(stringResource(R.string.er_tv_done, now.name))
                    Spacer(Modifier.height(Spacing.S))
                    Line(stringResource(R.string.er_tv_done_line))
                    Spacer(Modifier.height(Spacing.XXL))
                    PillButton(stringResource(R.string.er_done), onClick = navController::navigateUp)
                }
                else -> {
                    Title(stringResource(R.string.er_tv_code_title))
                    Spacer(Modifier.height(Spacing.S))
                    Line(stringResource(R.string.er_tv_steps))
                    Spacer(Modifier.height(Spacing.XXL))
                    CodeBoxes(
                        code = code,
                        busy = now is LinkState.Looking,
                        error = now is LinkState.Failed,
                        onChange = { typed ->
                            code = typed.filter(Char::isDigit).take(CODE_LENGTH)
                            if (state is LinkState.Failed) state = LinkState.Idle
                            if (code.length == CODE_LENGTH) find(code = code)
                        },
                    )
                    Box(Modifier.fillMaxWidth().height(Spacing.XXXL), contentAlignment = Alignment.Center) {
                        when (now) {
                            is LinkState.Failed ->
                                Text(
                                    stringResource(now.message),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center,
                                )
                            LinkState.Looking -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                            else -> Unit
                        }
                    }
                    OrLine()
                    Spacer(Modifier.height(Spacing.XL))
                    OutlinedButton(
                        onClick = { scanning = true },
                        shape = CircleShape,
                        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        Icon(painterResource(R.drawable.qr_code_scanner), contentDescription = null, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(Spacing.M))
                        Text(stringResource(R.string.er_tv_scan), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }

    // The scanner covers the page, upright, until a code is read or it is closed.
    if (scanning) {
        androidx.activity.compose.BackHandler { scanning = false }
        QrScanner(
            onResult = { text ->
                scanning = false
                val found = TvLink.sessionFrom(text)
                if (found != null) find(session = found) else state = LinkState.Failed(R.string.er_tv_not_sonara)
            },
            onClose = { scanning = false },
        )
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
}

@Composable
private fun Line(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}

@Composable
private fun PillButton(
    text: String,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, enabled = !busy, shape = CircleShape, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
        } else {
            Text(text, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** A TV drawn in the theme's colours over a soft glow; its screen shows a QR mark, then a tick. */
@Composable
private fun TvArt(done: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    val frame = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(width = 240.dp, height = 160.dp)) {
        Box(Modifier.matchParentSize().background(Brush.radialGradient(listOf(primary.copy(alpha = 0.3f), Color.Transparent))))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(width = 156.dp, height = 96.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .border(3.dp, frame, RoundedCornerShape(14.dp)),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(48.dp).clip(CircleShape).background(primary.copy(alpha = 0.16f)),
                ) {
                    Icon(
                        painterResource(if (done) R.drawable.check else R.drawable.qr_code_scanner),
                        contentDescription = null,
                        tint = primary,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
            Box(Modifier.padding(top = 6.dp).size(width = 60.dp, height = 4.dp).clip(CircleShape).background(frame))
        }
    }
}

/** The TV's code as six boxes: the one being typed in is lit, and all turn red on a wrong code. */
@Composable
private fun CodeBoxes(
    code: String,
    busy: Boolean,
    error: Boolean,
    onChange: (String) -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BasicTextField(
        value = code,
        onValueChange = onChange,
        enabled = !busy,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.focusRequester(focus),
        decorationBox = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
                repeat(CODE_LENGTH) { i ->
                    val digit = code.getOrNull(i)
                    val active = i == code.length && !busy
                    val edge =
                        when {
                            error -> MaterialTheme.colorScheme.error
                            active -> MaterialTheme.colorScheme.primary
                            else -> Color.Transparent
                        }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .size(width = 46.dp, height = 58.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .border(2.dp, edge, RoundedCornerShape(12.dp)),
                    ) {
                        if (digit != null) {
                            Text(digit.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        } else if (!active) {
                            Box(Modifier.size(width = 12.dp, height = 2.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)))
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun OrLine() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(Modifier.weight(1f))
        Text(
            stringResource(R.string.er_tv_or),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.L),
        )
        HorizontalDivider(Modifier.weight(1f))
    }
}
