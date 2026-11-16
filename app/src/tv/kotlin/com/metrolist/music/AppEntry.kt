/**
 * Sonara (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music

import android.app.Activity
import com.metrolist.music.tv.TvActivity

/** The screen the app opens on (the notification, the media session): Sonara TV's. */
object AppEntry {
    val activity: Class<out Activity> = TvActivity::class.java
}
