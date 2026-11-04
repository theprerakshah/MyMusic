package com.prerak.mymusic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Foreground service that keeps the process alive while audio plays in the
 * background, AND mirrors the web player into a real [MediaSessionCompat].
 *
 * The session carries the current song's title/artist/album art and a playback
 * state with transport actions, so the system shows a premium media card on the
 * lock screen and in the notification shade. Pressing a transport button routes
 * through the session callback to [commandListener], which the Activity uses to
 * drive the actual YouTube Music web player.
 */
class PlaybackService : Service() {

    /**
     * Lets the hosting Activity push web-player state in and receive commands.
     *
     * NB: these are deliberately NOT named onPlay/onPause/etc. An Activity that
     * implements this interface would otherwise accidentally override
     * Activity.onPause() and skip super, crashing on every backgrounding.
     */
    interface CommandListener {
        fun onPlayCommand()
        fun onPauseCommand()
        fun onNextCommand()
        fun onPreviousCommand()
        fun onSeekToCommand(positionMs: Long)
    }

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    private val binder = LocalBinder()
    var commandListener: CommandListener? = null

    private lateinit var mediaSession: MediaSessionCompat
    private val mainHandler = Handler(Looper.getMainLooper())
    private val artExecutor = Executors.newSingleThreadExecutor()

    // Last reported state from the web player, used to rebuild the notification.
    private var title: String = "MyMusic"
    private var artist: String = ""
    private var album: String = ""
    private var playing: Boolean = false
    private var positionMs: Long = 0L
    private var durationMs: Long = 0L

    // Album art: cache by URL so we don't re-download the same image repeatedly.
    private var artUrl: String? = null
    private var art: Bitmap? = null

    private var started = false
    private var destroyed = false

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createChannel()
        mediaSession = MediaSessionCompat(this, "MyMusic").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { commandListener?.onPlayCommand() }
                override fun onPause() { commandListener?.onPauseCommand() }
                override fun onSkipToNext() { commandListener?.onNextCommand() }
                override fun onSkipToPrevious() { commandListener?.onPreviousCommand() }
                override fun onSeekTo(pos: Long) { commandListener?.onSeekToCommand(pos) }
            })
            isActive = true
        }
        applyPlaybackState()
        applyMetadata()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Route hardware/notification media-button presses to the session callback.
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        if (!started) {
            try {
                startForeground(NOTIFICATION_ID, buildNotification())
                started = true
            } catch (e: Exception) {
                // Foreground start isn't allowed here (e.g. a background restart
                // with a null intent). Don't crash — there's nothing to keep
                // alive from the background anyway, so just stop.
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // Don't let the system resurrect us with a null intent from the
        // background; that path can't legally call startForeground().
        return START_NOT_STICKY
    }

    /**
     * Push the latest player state from the web page. Called on the main thread
     * from the Activity's JS bridge.
     */
    fun updateFromWeb(
        title: String,
        artist: String,
        album: String,
        artUrl: String,
        playing: Boolean,
        positionMs: Long,
        durationMs: Long,
    ) {
        if (destroyed) return
        this.title = title.ifBlank { "MyMusic" }
        this.artist = artist
        this.album = album
        this.playing = playing
        this.positionMs = positionMs
        this.durationMs = durationMs

        applyPlaybackState()
        applyMetadata()
        refreshNotification()

        if (artUrl.isNotBlank() && artUrl != this.artUrl) {
            this.artUrl = artUrl
            fetchArt(artUrl)
        } else if (artUrl.isBlank()) {
            this.artUrl = null
            this.art = null
        }
    }

    /** The web player is gone (e.g. its process died), so stop showing "playing". */
    fun markStopped() {
        if (destroyed) return
        playing = false
        applyPlaybackState()
        refreshNotification()
    }

    /**
     * Session and notification updates cross into system_server and can throw
     * (e.g. TransactionTooLargeException). A failed cosmetic update must never
     * take down the music.
     */
    private inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            Log.w(TAG, "$what failed", e)
        }
    }

    private fun applyPlaybackState() = safely("setPlaybackState") {
        val state = if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(state, positionMs, if (playing) 1f else 0f)
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO
                )
                .build()
        )
    }

    private fun applyMetadata() = safely("setMetadata") {
        val b = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
        art?.let {
            b.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
            b.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, it)
        }
        mediaSession.setMetadata(b.build())
    }

    private fun fetchArt(url: String) {
        artExecutor.execute {
            val bitmap = try {
                downloadArt(url)
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                // Album art is optional; the song keeps playing without it.
                null
            }
            if (bitmap != null) {
                mainHandler.post {
                    // Ignore if the song changed again before the download finished.
                    if (!destroyed && url == artUrl) {
                        art = bitmap
                        applyMetadata()
                        refreshNotification()
                    }
                }
            }
        }
    }

    /** Downloads and downsamples, so an oversized image can't exhaust memory. */
    private fun downloadArt(url: String): Bitmap? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            instanceFollowRedirects = true
        }
        try {
            if (conn.contentLength > MAX_ART_BYTES) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_ART_PX) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } finally {
            conn.disconnect()
        }
    }

    private fun refreshNotification() {
        if (!started) return
        safely("notify") {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val prev = NotificationCompat.Action(
            android.R.drawable.ic_media_previous,
            "Previous",
            MediaButtonReceiver.buildMediaButtonPendingIntent(
                this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS,
            ),
        )
        val playPause = if (playing) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause,
                "Pause",
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this, PlaybackStateCompat.ACTION_PLAY_PAUSE,
                ),
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play,
                "Play",
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this, PlaybackStateCompat.ACTION_PLAY_PAUSE,
                ),
            )
        }
        val next = NotificationCompat.Action(
            android.R.drawable.ic_media_next,
            "Next",
            MediaButtonReceiver.buildMediaButtonPendingIntent(
                this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT,
            ),
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(if (artist.isBlank()) "YouTube Music" else artist)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setLargeIcon(art)
            .setContentIntent(openApp)
            .setOngoing(playing)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(prev)
            .addAction(playPause)
            .addAction(next)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .build()
    }

    private fun createChannel() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Playback",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        mgr.createNotificationChannel(channel)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // If the user swipes the app away, stop keeping the process alive.
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        destroyed = true
        commandListener = null
        mainHandler.removeCallbacksAndMessages(null)
        artExecutor.shutdownNow()
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PlaybackService"
        const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
        private const val MAX_ART_BYTES = 10 * 1024 * 1024
        private const val MAX_ART_PX = 1024

        fun start(context: Context) {
            val intent = Intent(context, PlaybackService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: IllegalStateException) {
                // Not allowed from the background (Android 12+). The activity's
                // bind still connects the session; retried on next launch.
                Log.w(TAG, "Could not start foreground service", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}
