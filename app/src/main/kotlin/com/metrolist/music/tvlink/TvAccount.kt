/**
 * Sonara (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.tvlink

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.metrolist.innertube.YouTube
import com.metrolist.music.constants.AccountChannelHandleKey
import com.metrolist.music.constants.AccountEmailKey
import com.metrolist.music.constants.AccountImageUrlKey
import com.metrolist.music.constants.AccountNameKey
import com.metrolist.music.constants.DataSyncIdKey
import com.metrolist.music.constants.InnerTubeAuthUserKey
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.VisitorDataKey
import com.metrolist.music.utils.dataStore
import kotlinx.coroutines.flow.first

/** The signed-in session as the app keeps it (LoginScreen saves it), for sending to a TV. */
object TvAccount {
    suspend fun current(context: Context): TvLink.Account? {
        val prefs = context.dataStore.data.first()
        val cookie = prefs[InnerTubeCookieKey].orEmpty()
        if ("SAPISID" !in cookie) return null
        return TvLink.Account(
            cookie = cookie,
            visitorData = prefs[VisitorDataKey].orEmpty(),
            dataSyncId = prefs[DataSyncIdKey].orEmpty(),
            authUser = prefs[InnerTubeAuthUserKey] ?: "0",
            accountName = prefs[AccountNameKey].orEmpty(),
            accountEmail = prefs[AccountEmailKey].orEmpty(),
            channelHandle = prefs[AccountChannelHandleKey].orEmpty(),
            imageUrl = prefs[AccountImageUrlKey].orEmpty(),
        )
    }

    /** Signs this device in with [account], as a sign-in here would (App applies the cookie). */
    suspend fun save(
        context: Context,
        account: TvLink.Account,
    ) {
        YouTube.visitorData = account.visitorData
        YouTube.dataSyncId = account.dataSyncId
        context.dataStore.edit { settings ->
            settings[InnerTubeCookieKey] = account.cookie
            settings[VisitorDataKey] = account.visitorData
            settings[DataSyncIdKey] = account.dataSyncId
            settings[InnerTubeAuthUserKey] = account.authUser
            settings[AccountNameKey] = account.accountName
            settings[AccountEmailKey] = account.accountEmail
            settings[AccountChannelHandleKey] = account.channelHandle
            settings[AccountImageUrlKey] = account.imageUrl
        }
    }
}
