package com.prerak.mymusic

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.webkit.WebView

/**
 * A WebView that keeps media playing while the screen is off / the app is in
 * the background.
 *
 * Chromium normally pauses HTML5 audio and JS timers when the hosting window
 * stops being visible (which is exactly what happens on screen lock). We keep
 * reporting VISIBLE + focused to the engine while [backgroundPlaybackEnabled]
 * is on, so a song keeps playing with the phone locked.
 */
class MyMusicWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : WebView(context, attrs) {

    var backgroundPlaybackEnabled: Boolean = true

    override fun onWindowVisibilityChanged(visibility: Int) {
        // Pretend the window is always visible so playback isn't suspended.
        if (backgroundPlaybackEnabled) {
            super.onWindowVisibilityChanged(View.VISIBLE)
        } else {
            super.onWindowVisibilityChanged(visibility)
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        if (backgroundPlaybackEnabled) {
            super.onWindowFocusChanged(true)
        } else {
            super.onWindowFocusChanged(hasWindowFocus)
        }
    }
}
