/**
 * Sonara (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Settings -> Sign in on a TV (requested by the owner): scan the QR code Sonara TV shows, or type
 * its 6-digit code; after "Sign in on <TV>?" the phone sends its session to that TV, sealed for
 * it alone (TvLink). Opened by the TV page's "Open in Sonara" link too, with the TV already found.
 */

package com.metrolist.music.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.tvlink.TvAccount
import com.metrolist.music.tvlink.QrScanner
import com.metrolist.music.tvlink.TvLink
import com.metrolist.music.ui.theme.Spacing
import com.metrolist.music.ui.theme.backdropTopBarColors
import com.metrolist.music.ui.theme.topBarBackdrop
import kotlinx.coroutines.launch

private sealed interface LinkState {
    data object Idle : LinkState

    data object Looking : LinkState

    data class Confirm(val target: TvLink.Target) : LinkState

    data object Sending : LinkState

    data class Done(val name: String) : LinkState

    data class Failed(val message: Int) : LinkState
}

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
        state = LinkState.Sending
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

    var scanning by remember { mutableStateOf(false) }

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
            verticalArrangement = Arrangement.spacedBy(Spacing.M),
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Gutter, vertical = Spacing.L),
        ) {
            Icon(painterResource(R.drawable.tv), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = Spacing.L))
            when (val now = state) {
                is LinkState.Confirm -> {
                    Text(stringResource(R.string.er_tv_confirm, now.target.name), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    Button(onClick = { send(now.target) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.er_tv_sign_in_action)) }
                    OutlinedButton(onClick = { state = LinkState.Idle }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(android.R.string.cancel)) }
                }
                is LinkState.Done -> {
                    Text(stringResource(R.string.er_tv_done, now.name), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    Button(onClick = navController::navigateUp, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.er_done)) }
                }
                LinkState.Looking, LinkState.Sending -> Text(stringResource(R.string.er_tv_working), style = MaterialTheme.typography.titleMedium)
                else -> {
                    Text(stringResource(R.string.er_tv_steps), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    (now as? LinkState.Failed)?.let { Text(stringResource(it.message), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
                    Button(onClick = { scanning = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.er_tv_scan)) }
                    Spacer(Modifier.height(Spacing.S))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { typed ->
                            code = typed.filter(Char::isDigit).take(6)
                            if (code.length == 6) find(code = code)
                        },
                        label = { Text(stringResource(R.string.er_tv_code)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
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
