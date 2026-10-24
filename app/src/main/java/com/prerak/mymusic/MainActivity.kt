package com.prerak.mymusic

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Phase 1: a full-screen WebView that loads YouTube Music.
 *
 * This is intentionally minimal. Background playback (foreground service +
 * MediaSession) and ad blocking come in later phases; for now this just proves
 * "YouTube Music in an app" works and runs on the device.
 */
class MainActivity : ComponentActivity(), PlaybackService.CommandListener {

    private lateinit var root: FrameLayout
    private lateinit var webView: MyMusicWebView
    private lateinit var insetsController: WindowInsetsControllerCompat

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    // Bound connection to the playback service, so we can push the web player's
    // state into its MediaSession and receive transport-button commands back.
    private var playbackService: PlaybackService? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            playbackService = (binder as? PlaybackService.LocalBinder)?.getService()
            playbackService?.commandListener = this@MainActivity
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
        }
    }

    // Holds the fullscreen video view handed to us by the page's WebChromeClient.
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    // Latest system-bar insets, re-applied as root padding outside fullscreen.
    private var barInsets: Insets = Insets.NONE

    // Persisted user options (audio-only vs. video). Battery-saver on screen-off
    // is always on — it only suppresses *video* while the display is off.
    private lateinit var prefs: SharedPreferences

    // While the screen is off we suppress video rendering/decoding (audio keeps
    // playing). This mirrors the lock-screen state into the web player.
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> pushScreenOff(true)
                Intent.ACTION_SCREEN_ON -> pushScreenOff(false)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // We draw edge-to-edge (forced on targetSdk 35+) and apply the system-bar
        // insets ourselves, so the page never hides under the status/nav bars.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        insetsController = WindowInsetsControllerCompat(window, root)

        webView = MyMusicWebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.BLACK)
        }
        root.addView(webView)

        // Pad the root by the system bars so the page sits below the status bar
        // and above the nav bar. We drop the padding while a video is fullscreen
        // so it can fill the whole screen.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            barInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            applyRootInsets()
            WindowInsetsCompat.CONSUMED
        }

        configureWebView()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(HOME_URL)
        }

        // Ask for the notification permission (Android 13+) so the foreground
        // service can show its ongoing notification, then start the service that
        // keeps audio alive while the screen is locked.
        requestNotificationPermissionIfNeeded()
        PlaybackService.start(this)
        bindService(
            Intent(this, PlaybackService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE,
        )

        // Listen for the display turning on/off so we can stop rendering video
        // (but not audio) while the phone is in your pocket.
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // Back button/gesture: navigate within the page history, then leave the app.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreenView != null) {
                    webView.webChromeClient?.onHideCustomView()
                } else if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /** Apply the system-bar insets as root padding, except while fullscreen. */
    private fun applyRootInsets() {
        if (fullscreenView == null) {
            root.setPadding(barInsets.left, barInsets.top, barInsets.right, barInsets.bottom)
        } else {
            root.setPadding(0, 0, 0, 0)
        }
    }

    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Let music/video start playing without requiring a manual tap.
            mediaPlaybackRequiresUserGesture = false
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        // Bridge the YouTube Music web player to our native MediaSession.
        webView.addJavascriptInterface(WebMediaBridge(), "AndroidMedia")

        // Keep navigation inside our WebView instead of bouncing to a browser.
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val url = request.url?.toString() ?: return false
                return !url.startsWith("http")
            }

            // Phase 3 (network layer): drop requests to known ad / tracking
            // endpoints. Returning an empty response makes the load "succeed"
            // with nothing, so the page doesn't error out or retry.
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val url = request.url?.toString() ?: return null
                return if (isAdRequest(url)) {
                    WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        ByteArrayInputStream(ByteArray(0)),
                    )
                } else {
                    null
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                // Install the player-reporting bridge. It self-guards against
                // double-install and keeps reporting across in-page navigation.
                view.evaluateJavascript(BRIDGE_JS, null)
                // Phase 3 (JS layer): skip in-stream audio/video ads and hide
                // banner/upsell ad containers.
                view.evaluateJavascript(ADBLOCK_JS, null)
                // Phase 4 (optimization): audio-only mode, video-quality cap, and
                // the floating audio/video toggle. Reads its initial state from
                // the native bridge (AndroidMedia.getAudioOnly()).
                view.evaluateJavascript(OPTIMIZE_JS, null)
            }
        }

        // Handle fullscreen video (the page calls onShowCustomView for fullscreen).
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (fullscreenView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                fullscreenView = view
                fullscreenCallback = callback
                view.setBackgroundColor(Color.BLACK)
                root.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                webView.visibility = View.GONE
                applyRootInsets() // drop padding so video fills the screen
                // Go truly immersive while the video is fullscreen.
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
                insetsController.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }

            override fun onHideCustomView() {
                val view = fullscreenView ?: return
                root.removeView(view)
                fullscreenView = null
                webView.visibility = View.VISIBLE
                fullscreenCallback?.onCustomViewHidden()
                fullscreenCallback = null
                applyRootInsets() // restore padding below the status bar
                // Restore the normal system bars.
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // --- PlaybackService.CommandListener: drive the web player from the
    // notification / lock-screen transport buttons. ---

    override fun onPlayCommand() = runControl("play")
    override fun onPauseCommand() = runControl("pause")
    override fun onNextCommand() = runControl("next")
    override fun onPreviousCommand() = runControl("previous")

    override fun onSeekToCommand(positionMs: Long) {
        runOnUiThread {
            webView.evaluateJavascript("window.__myMusicSeek($positionMs);", null)
        }
    }

    private fun runControl(command: String) {
        runOnUiThread {
            webView.evaluateJavascript("window.__myMusicControl('$command');", null)
        }
    }

    /** Tell the web player whether the display is currently off. */
    private fun pushScreenOff(off: Boolean) {
        runOnUiThread {
            webView.evaluateJavascript("window.__myMusicSetScreenOff($off);", null)
        }
    }

    /**
     * JavaScript-facing bridge. The injected script calls [onState] whenever the
     * player's song or play/pause state changes; we forward it to the service's
     * MediaSession on the main thread.
     */
    private inner class WebMediaBridge {
        @JavascriptInterface
        fun onState(
            title: String,
            artist: String,
            album: String,
            artUrl: String,
            playing: Boolean,
            positionMs: Long,
            durationMs: Long,
        ) {
            webView.post {
                playbackService?.updateFromWeb(
                    title, artist, album, artUrl, playing, positionMs, durationMs,
                )
            }
        }

        /** Initial audio-only state for the injected script to read on load. */
        @JavascriptInterface
        fun getAudioOnly(): Boolean = prefs.getBoolean(KEY_AUDIO_ONLY, true)

        /** Persist the user's audio-only choice when they tap the toggle. */
        @JavascriptInterface
        fun setAudioOnly(enabled: Boolean) {
            prefs.edit().putBoolean(KEY_AUDIO_ONLY, enabled).apply()
        }
    }

    // Set while the activity is fully hidden (screen off / app switch), when the
    // window's drawing surface is destroyed. Consumed on the next focus-regain.
    private var pendingSurfaceResync = false

    override fun onStop() {
        super.onStop()
        // The activity is no longer visible, so the system tears down our
        // window's GPU surface. Flag that we must force it to re-acquire one
        // when we come back. (This does NOT fire for transient focus loss such
        // as a notification-shade pull, so we only resync when genuinely needed.)
        pendingSurfaceResync = true
        android.util.Log.d(TAG, "onStop -> pendingSurfaceResync=true")
    }

    override fun onStart() {
        super.onStart()
        android.util.Log.d(TAG, "onStart")
    }

    override fun onResume() {
        super.onResume()
        android.util.Log.d(TAG, "onResume pending=$pendingSurfaceResync")
        // PRIMARY trigger. onResume is guaranteed on unlock; this window does
        // NOT emit onWindowFocusChanged(hasFocus=true) on keyguard dismissal
        // (verified via logcat), so relying on focus meant the resync never ran.
        if (pendingSurfaceResync) {
            pendingSurfaceResync = false
            resyncWindowSurface()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        android.util.Log.d(TAG, "onWindowFocusChanged hasFocus=$hasFocus pending=$pendingSurfaceResync")
        // BACKUP trigger, in case onResume ever runs before the new surface is
        // ready (and for app-switch returns, which do fire focus).
        if (hasFocus && pendingSurfaceResync) {
            pendingSurfaceResync = false
            resyncWindowSurface()
        }
    }

    /**
     * Recover the WebView's renderer after the window has been hidden and shown
     * again (screen lock/unlock, app switch).
     *
     * To keep music playing while the screen is locked, [MyMusicWebView] fakes
     * "window visible" so Chromium never suspends the page. The side effect is
     * that when the screen really turns off, the system destroys the *window's*
     * drawing surface, but — because we keep reporting "visible" — Chromium
     * never releases its reference to it. On unlock a fresh surface exists, yet
     * the compositor is still wedged on the dead one: it can't repaint or
     * hit-test touches, so the whole web UI appears frozen even though audio (a
     * separate pipeline) keeps playing. Rotating the phone fixes it because
     * rotation makes WindowManager allocate a brand-new window surface.
     *
     * Rotation recovers the view by doing a full relayout that (a) reallocates
     * the window Surface and (b) rebuilds the WebView's hardware render node /
     * GPU draw functor against it. We reproduce BOTH halves here, since a single
     * one has not been enough on its own:
     *
     *   1. window.setFormat() toggle -> WindowManager drops the current Surface
     *      and hands back a fresh one on the next relayout (the window-level
     *      half of what rotation does).
     *   2. setLayerType(SOFTWARE -> HARDWARE) toggle -> the WebView tears down
     *      its GPU render node and rebuilds it against the current surface (the
     *      view-level half — the compositor rebinds and starts repainting).
     *   3. webView.onResume()/resumeTimers() -> make sure Chromium itself isn't
     *      in a paused state when we kick it.
     *
     * All of this is imperceptible and leaves audio untouched: nothing about
     * window visibility or the <video>/<audio> element changes.
     */
    private fun resyncWindowSurface() {
        android.util.Log.d(TAG, "resyncWindowSurface: kick start (onResume + format + layer toggle)")

        // (3) Ensure the engine isn't paused.
        webView.onResume()
        webView.resumeTimers()

        // (1) Reallocate the window surface and (2) drop the GPU render node.
        window.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        webView.requestLayout()
        webView.invalidate()

        // Restore on a later frame so the teardown actually takes effect first.
        webView.postDelayed({
            window.setFormat(android.graphics.PixelFormat.OPAQUE)
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            webView.requestLayout()
            webView.invalidate()
            android.util.Log.d(TAG, "resyncWindowSurface: kick done (restored OPAQUE + HARDWARE)")
        }, 120)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        // The activity is going away for good, so stop the background-keep-alive
        // service and tear down the WebView.
        playbackService?.commandListener = null
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: IllegalArgumentException) {
            // Was never registered; ignore.
        }
        try {
            unbindService(serviceConnection)
        } catch (_: IllegalArgumentException) {
            // Was never bound; ignore.
        }
        PlaybackService.stop(this)
        root.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MyMusicFreeze"
        private const val HOME_URL = "https://music.youtube.com"

        private const val PREFS = "mymusic_prefs"
        private const val KEY_AUDIO_ONLY = "audio_only"

        /**
         * Injected into the YouTube Music page. It reads the current song's
         * metadata (via the page's own W3C Media Session + the <video> element)
         * and reports play/pause + song changes to native code through the
         * `AndroidMedia` bridge. It also exposes control hooks the native
         * transport buttons call back into.
         */
        private val BRIDGE_JS = """
            (function () {
              if (window.__myMusicBridgeInstalled) return;
              window.__myMusicBridgeInstalled = true;

              function video() { return document.querySelector('video'); }

              var lastSig = '';

              // Ask for a bigger copy of a Google-hosted thumbnail. YouTube Music
              // serves the player-bar image tiny (=w60-h60 / =s60); rewrite the
              // size params so the lock screen gets crisp album art.
              function upscale(u) {
                if (!u) return '';
                try {
                  return u
                    .replace(/=w\d+-h\d+/, '=w544-h544')
                    .replace(/=s\d+/, '=s544');
                } catch (e) { return u; }
              }

              // Read title/artist/album/art straight from YouTube Music's own
              // on-screen player bar. This is the reliable source: the W3C
              // mediaSession metadata is often empty inside a WebView.
              function fromDom() {
                var out = { title: '', artist: '', album: '', art: '' };
                var bar = document.querySelector('ytmusic-player-bar');
                if (bar) {
                  var t = bar.querySelector('.title');
                  if (t) out.title = (t.getAttribute('title') || t.textContent || '').trim();
                  var by = bar.querySelector('.byline');
                  if (by) {
                    var line = (by.getAttribute('title') || by.textContent || '').trim();
                    // Byline is usually "Artist • Album • Year".
                    var parts = line.split('•');
                    out.artist = (parts[0] || '').trim();
                    if (parts.length > 1) out.album = (parts[1] || '').trim();
                  }
                  var img = bar.querySelector('img');
                  if (img && img.src) out.art = img.src;
                }
                return out;
              }

              function report(force) {
                try {
                  var v = video();
                  var ms = (navigator.mediaSession && navigator.mediaSession.metadata) || null;
                  var dom = fromDom();

                  var title = (ms && ms.title) || dom.title || document.title || '';
                  var artist = (ms && ms.artist) || dom.artist || '';
                  var album = (ms && ms.album) || dom.album || '';

                  var art = '';
                  if (ms && ms.artwork && ms.artwork.length) {
                    art = ms.artwork[ms.artwork.length - 1].src || '';
                  }
                  if (!art) art = dom.art || '';
                  art = upscale(art);

                  var playing = v ? (!v.paused && !v.ended) : false;
                  var pos = v ? Math.floor((v.currentTime || 0) * 1000) : 0;
                  var dur = (v && isFinite(v.duration)) ? Math.floor(v.duration * 1000) : 0;

                  // Include artist + art so a late-arriving byline or thumbnail
                  // (same title) still gets pushed to the notification.
                  var sig = title + '|' + artist + '|' + album + '|' + playing + '|' + dur + '|' + art;
                  if (!force && sig === lastSig) return;
                  lastSig = sig;

                  AndroidMedia.onState(title, artist, album, art, playing, pos, dur);
                } catch (e) {}
              }

              function attach() {
                var v = video();
                if (v && !v.__myMusicHooked) {
                  v.__myMusicHooked = true;
                  ['play', 'pause', 'ended', 'loadedmetadata', 'seeked'].forEach(function (ev) {
                    v.addEventListener(ev, function () { report(true); });
                  });
                }
              }

              setInterval(function () { attach(); report(false); }, 1000);
              attach();
              report(true);

              window.__myMusicControl = function (cmd) {
                try {
                  var v = video();
                  if (cmd === 'play') { if (v) v.play(); }
                  else if (cmd === 'pause') { if (v) v.pause(); }
                  else if (cmd === 'next') {
                    var n = document.querySelector('.next-button');
                    if (n) n.click();
                  } else if (cmd === 'previous') {
                    var p = document.querySelector('.previous-button');
                    if (p) p.click();
                  }
                  setTimeout(function () { report(true); }, 300);
                } catch (e) {}
              };

              window.__myMusicSeek = function (ms) {
                try { var v = video(); if (v) v.currentTime = ms / 1000; } catch (e) {}
              };
            })();
        """.trimIndent()

        // --- Phase 3: ad blocking ---

        // Hosts that only ever serve ads/tracking. Matched as a substring of the
        // request host, so "doubleclick.net" also covers static.doubleclick.net.
        // Deliberately does NOT include googlevideo.com (media), ytimg.com /
        // googleusercontent.com / gstatic.com (thumbnails, album art, assets).
        private val AD_HOSTS = listOf(
            "doubleclick.net",
            "googleadservices.com",
            "googlesyndication.com",
            "google-analytics.com",
            "googletagservices.com",
            "googletagmanager.com",
            "adservice.google.",
            "pagead2.googlesyndication.com",
        )

        // Path fragments that mark an ad / ad-telemetry request even on an
        // otherwise-allowed host (e.g. music.youtube.com, googleads.g...).
        private val AD_PATHS = listOf(
            "/pagead/",
            "/ptracking",
            "/api/stats/ads",
            "/api/stats/atr",
            "/get_midroll_",
            "/log_interaction",
            "googleads.g.doubleclick",
        )

        fun isAdRequest(url: String): Boolean {
            val lower = url.lowercase()
            val host = try { java.net.URI(url).host?.lowercase() ?: "" } catch (e: Exception) { "" }
            if (host.isNotEmpty() && AD_HOSTS.any { host.contains(it) }) return true
            return AD_PATHS.any { lower.contains(it) }
        }

        /**
         * Injected ad-skipper. Two jobs:
         *  1. Fast-forward through in-stream audio/video ads (the player tags the
         *     `#movie_player` with `ad-showing` / `ad-interrupting`; jumping the
         *     <video> to its end makes the player move on to the real song), and
         *     click any "Skip ad" button the instant it appears.
         *  2. Hide banner / "Premium" upsell / display-ad containers via CSS.
         */
        private val ADBLOCK_JS = """
            (function () {
              if (window.__myMusicAdblockInstalled) return;
              window.__myMusicAdblockInstalled = true;

              function video() { return document.querySelector('video'); }

              try {
                var css = document.createElement('style');
                css.textContent =
                  'ytmusic-mealbar-promo-renderer,' +
                  'ytmusic-statement-banner-renderer,' +
                  'ytmusic-ad-renderer,' +
                  'ytmusic-popup-container tp-yt-paper-dialog:has(ytmusic-mealbar-promo-renderer),' +
                  '#player-ads,' +
                  '.ytp-ad-module,' +
                  '.ad-container,' +
                  'ytmusic-you-there-renderer' +
                  '{display:none !important;}';
                (document.head || document.documentElement).appendChild(css);
              } catch (e) {}

              // When we hide a YTM dialog (the "Are you still there?" / Premium
              // upsell popups) with display:none, the dialog is still "open" as
              // far as YTM is concerned, so its modal lock stays in place:
              //  - a full-screen backdrop scrim appended to <body>, and
              //  - the `inert` attribute on ytmusic-app-layout (+ overflow:hidden
              //    on <body>), which makes the browser ignore EVERY touch on the
              //    page.
              // Either way the whole app feels frozen even though audio + the
              // notification controls keep working. The Premium promo pops up
              // as soon as a song starts, so this hit on every playback.
              // When no dialog is actually visible, properly dismiss the hidden
              // promo and strip whatever lock it left behind.
              function clearOrphanBackdrops() {
                try {
                  // Is any modal dialog genuinely on screen (not display:none)?
                  var dialogs = document.querySelectorAll(
                    'tp-yt-paper-dialog[opened],' +
                    'ytmusic-popup-container tp-yt-paper-dialog,' +
                    'tp-yt-paper-dialog.ytmusic-popup-container'
                  );
                  var anyVisible = false;
                  for (var i = 0; i < dialogs.length; i++) {
                    var d = dialogs[i];
                    var cs = window.getComputedStyle(d);
                    if (cs.display !== 'none' &&
                        cs.visibility !== 'hidden' &&
                        d.getClientRects().length > 0) {
                      anyVisible = true;
                      break;
                    }
                  }

                  if (anyVisible) return;

                  // Close the hidden promo the "proper" way first, so YTM's own
                  // overlay bookkeeping releases its lock.
                  var dismiss = document.querySelector(
                    'ytmusic-mealbar-promo-renderer .dismiss-button button,' +
                    'ytmusic-mealbar-promo-renderer .dismiss-button'
                  );
                  if (dismiss && document.querySelector('[inert]')) dismiss.click();

                  var backdrops = document.querySelectorAll(
                    'tp-yt-paper-dialog-backdrop,' +
                    'tp-yt-iron-overlay-backdrop,' +
                    '.iron-overlay-backdrop,' +
                    '#scrim'
                  );
                  for (var j = 0; j < backdrops.length; j++) backdrops[j].remove();

                  // Belt and braces: release the modal lock directly.
                  var inert = document.querySelectorAll('[inert]');
                  for (var k = 0; k < inert.length; k++) inert[k].removeAttribute('inert');
                  // Polymer's overlay manager can lock scroll / pointer-events
                  // on <body> while a modal is open; release it (only when we
                  // actually found a lock, so YTM's normal styling is untouched).
                  if ((inert.length || backdrops.length) && document.body) {
                    document.body.style.pointerEvents = '';
                    document.body.style.overflow = '';
                  }
                } catch (e) {}
              }

              function skipAds() {
                try {
                  var player = document.querySelector('#movie_player') ||
                               document.querySelector('.html5-video-player');
                  if (player &&
                      (player.classList.contains('ad-showing') ||
                       player.classList.contains('ad-interrupting'))) {
                    var v = video();
                    if (v && isFinite(v.duration) && v.duration > 0) {
                      v.currentTime = v.duration;  // jump to the end of the ad
                    }
                  }
                  var skip = document.querySelector(
                    '.ytp-ad-skip-button,' +
                    '.ytp-ad-skip-button-modern,' +
                    '.ytp-skip-ad-button,' +
                    '.ytp-ad-skip-button-container button'
                  );
                  if (skip) skip.click();

                  // Dismiss "Keep watching?" / "Are you still there?" dialogs that
                  // pause playback after a while.
                  var stillThere = document.querySelector(
                    'ytmusic-you-there-renderer #button a,' +
                    'ytmusic-you-there-renderer button'
                  );
                  if (stillThere) stillThere.click();

                  // Always sweep up any leftover modal scrim that would
                  // otherwise swallow all touches and freeze the UI.
                  clearOrphanBackdrops();
                } catch (e) {}
              }

              setInterval(skipAds, 500);
              skipAds();
            })();
        """.trimIndent()

        /**
         * Phase 4: playback optimization.
         *
         * YouTube Music streams audio and video as SEPARATE adaptive (DASH)
         * tracks, so throttling or hiding the video never degrades the audio.
         * This script:
         *  - keeps an "audio only" flag (persisted natively) + a transient
         *    "screen off" flag pushed from the Activity;
         *  - whenever either is set, hides the <video> and caps its resolution to
         *    the lowest level so almost no battery goes to video decode/render,
         *    while audio continues at full quality;
         *  - when audio-only, also tries to select the "Song" (art-only) version
         *    of a track so no video stream is fetched at all — the biggest saving;
         *  - adds a floating 🎧/🎬 button to flip between audio-only and video,
         *    independent of YouTube Music's own (frequently-changing) layout.
         */
        private val OPTIMIZE_JS = """
            (function () {
              if (window.__myMusicOptimizeInstalled) return;
              window.__myMusicOptimizeInstalled = true;

              var audioOnly = true;
              try { audioOnly = !!AndroidMedia.getAudioOnly(); } catch (e) {}
              var screenOff = false;

              function player() {
                return document.getElementById('movie_player') ||
                       document.querySelector('.html5-video-player');
              }

              // Stop painting the video frames (audio is untouched).
              function applyStyle(suppress) {
                var s = document.getElementById('__mm_style');
                if (!s) {
                  s = document.createElement('style');
                  s.id = '__mm_style';
                  (document.head || document.documentElement).appendChild(s);
                }
                s.textContent = suppress ? 'video{visibility:hidden !important;}' : '';
              }

              // Cap VIDEO resolution to the smallest. Audio is a separate DASH
              // stream, so this cuts decode cost without lowering sound quality.
              function applyQuality(suppress) {
                try {
                  var p = player();
                  if (!p) return;
                  if (suppress) {
                    if (p.setPlaybackQualityRange) p.setPlaybackQualityRange('tiny', 'tiny');
                    if (p.setPlaybackQuality) p.setPlaybackQuality('tiny');
                  } else if (p.setPlaybackQualityRange) {
                    p.setPlaybackQualityRange('tiny', 'highres'); // let it auto again
                  }
                } catch (e) {}
              }

              // Best-effort: prefer the audio-only "Song" version of a track so no
              // video stream is even requested. Only clicks a clearly-unselected
              // "Song" control, so we never accidentally switch TO video.
              function preferSong() {
                try {
                  var tabs = document.querySelectorAll(
                    'ytmusic-player-page tp-yt-paper-tab,' +
                    'ytmusic-player-page tp-yt-paper-button,' +
                    '.song-video-toggle tp-yt-paper-tab'
                  );
                  for (var i = 0; i < tabs.length; i++) {
                    var t = tabs[i];
                    if ((t.textContent || '').trim().toLowerCase() === 'song') {
                      var sel = t.classList.contains('iron-selected') ||
                                t.getAttribute('aria-selected') === 'true';
                      if (!sel) t.click();
                      return;
                    }
                  }
                } catch (e) {}
              }

              function apply() {
                var suppress = audioOnly || screenOff;
                applyStyle(suppress);
                applyQuality(suppress);
                if (audioOnly) preferSong();
                updateButton();
              }

              window.__myMusicSetAudioOnly = function (b) { audioOnly = !!b; apply(); };
              window.__myMusicSetScreenOff = function (b) { screenOff = !!b; apply(); };

              function ensureButton() {
                var btn = document.getElementById('__mm_toggle');
                if (!btn) {
                  btn = document.createElement('div');
                  btn.id = '__mm_toggle';
                  btn.style.cssText =
                    'position:fixed;z-index:2147483647;right:12px;top:50%;' +
                    'transform:translateY(-50%);width:44px;height:44px;' +
                    'border-radius:22px;display:flex;align-items:center;' +
                    'justify-content:center;cursor:pointer;font-size:20px;' +
                    'background:rgba(0,0,0,0.55);backdrop-filter:blur(6px);' +
                    '-webkit-backdrop-filter:blur(6px);' +
                    'border:1px solid rgba(255,255,255,0.18);' +
                    'box-shadow:0 2px 8px rgba(0,0,0,0.4);' +
                    '-webkit-user-select:none;user-select:none;';
                  btn.addEventListener('click', function () {
                    audioOnly = !audioOnly;
                    try { AndroidMedia.setAudioOnly(audioOnly); } catch (e) {}
                    apply();
                  });
                  if (document.body) document.body.appendChild(btn);
                }
                return btn;
              }

              function updateButton() {
                var btn = document.getElementById('__mm_toggle');
                if (!btn) return;
                btn.textContent = audioOnly ? '🎧' : '🎬';
                btn.title = audioOnly
                  ? 'Audio only — tap for video'
                  : 'Video on — tap for audio only';
                btn.style.opacity = audioOnly ? '1' : '0.6';
              }

              // Re-apply on song change / in-page navigation.
              setInterval(function () { ensureButton(); apply(); }, 1500);
              ensureButton();
              apply();
            })();
        """.trimIndent()
    }
}
