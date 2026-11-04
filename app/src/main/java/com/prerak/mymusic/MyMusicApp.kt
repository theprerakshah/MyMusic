package com.prerak.mymusic

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlin.system.exitProcess

/**
 * Last line of defence against abrupt exits. A process can't safely carry on
 * after an uncaught exception, so instead of the system's "app keeps stopping"
 * dialog we reopen the app if it was on screen, or leave a tap-to-reopen
 * notification if it wasn't (Android blocks background activity starts).
 *
 * [CrashLoopGuard] hands repeated crashes back to the system, so a bug that
 * fires on every launch can't trap the user in an endless relaunch.
 */
class MyMusicApp : Application() {

    private var resumedActivities = 0

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumedActivities++
                showRecoveredMessageIfNeeded(activity)
            }
            override fun onActivityPaused(activity: Activity) { resumedActivities-- }
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        val systemHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // The system's handler has already logged the stack trace to logcat.
            val recovered = try {
                recover()
            } catch (e: Exception) {
                Log.e(TAG, "Crash recovery failed", e)
                false
            }
            if (recovered) {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
            systemHandler?.uncaughtException(thread, error)
        }
    }

    /** Returns false if the crash should go to the system's handler instead. */
    private fun recover(): Boolean {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val recent = CrashLoopGuard.decode(prefs.getString(KEY_CRASH_TIMES, null))
        if (!CrashLoopGuard.shouldRecover(recent, now)) return false

        // commit(), not apply(): the process is about to die.
        prefs.edit()
            .putString(KEY_CRASH_TIMES, CrashLoopGuard.encode(CrashLoopGuard.recentTimes(recent, now) + now))
            .putBoolean(KEY_SHOW_RECOVERED, resumedActivities > 0)
            .commit()

        if (resumedActivities > 0) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        } else {
            postStoppedNotification()
        }
        return true
    }

    private fun postStoppedNotification() {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, PlaybackService.CHANNEL_ID)
            .setContentTitle("MyMusic stopped unexpectedly")
            .setContentText("Tap to open it again")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(STOPPED_NOTIFICATION_ID, notification)
    }

    private fun showRecoveredMessageIfNeeded(activity: Activity) {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SHOW_RECOVERED, false)) {
            prefs.edit().putBoolean(KEY_SHOW_RECOVERED, false).apply()
            Toast.makeText(activity, "MyMusic recovered from an error", Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val TAG = "MyMusicApp"
        const val PREFS = "mymusic_crash_guard"
        const val KEY_CRASH_TIMES = "crash_times"
        const val KEY_SHOW_RECOVERED = "show_recovered"
        const val STOPPED_NOTIFICATION_ID = 2
    }
}

/** Decides whether a failure may be recovered from automatically, or is a loop. */
object CrashLoopGuard {
    const val WINDOW_MS = 60_000L
    const val MAX_RECOVERIES = 3

    fun recentTimes(times: List<Long>, now: Long): List<Long> =
        times.filter { now - it in 0 until WINDOW_MS }

    fun shouldRecover(times: List<Long>, now: Long): Boolean =
        recentTimes(times, now).size < MAX_RECOVERIES

    fun encode(times: List<Long>): String = times.joinToString(",")

    fun decode(raw: String?): List<Long> =
        raw?.split(",")?.mapNotNull { it.toLongOrNull() } ?: emptyList()
}
