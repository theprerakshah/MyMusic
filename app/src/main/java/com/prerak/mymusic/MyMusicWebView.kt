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
 * stops being *visible* (which is exactly what happens on screen lock). We keep
 * reporting VISIBLE to the engine while [backgroundPlaybackEnabled] is on, so a
 * song keeps playing with the phone locked.
 *
 * IMPORTANT: we deliberately do NOT fake window *focus*. Faking focus caused a
 * nasty freeze — Chromium re-arms its touch/gesture input pipeline on the
 * focus-lost -> focus-gained transition. If we always report "focused", then
 * when the window really loses focus (screen lock, notification shade, task
 * switch) and later regains it, Chromium never sees that transition, so touch
 * handling never wakes up: the whole web UI appears frozen (audio keeps playing
 * because that's gated on visibility, not focus) until a rotation forces a
 * surface/layout re-init. Letting the real focus events through fixes that, and
 * does not pause playback, because playback continuation depends on visibility.
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

    // NOTE: onWindowFocusChanged is intentionally left to the default WebView
    // implementation. See the class doc above — faking focus breaks touch input
    // after the window loses and regains focus (screen lock / app switch).
}
