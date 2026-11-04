// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
package com.quixotic7.remotefm1

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * Keeps the engine and the FM-1 link running with the app in the background or the screen off (PLAN.md section 6,
 * Lifecycle): a foreground service of type mediaPlayback (and connectedDevice when the app may use Bluetooth). It
 * holds nothing itself: AppModel does. (G, RemoteFM1IOS dfbc827's lock screen) a media notification with a
 * MediaSession: the firmware's name; Pause stops it to save the battery (Battery.stop: saved, clock and sound off),
 * Play runs it again, Stop ends the session (the link too), as the notification's Stop always did.
 */
class PlaybackService : Service() {
    private var session: MediaSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    // the service stops (the sound off, Stop, the system): the flash saved (CorePlayer's stop())
    override fun onDestroy() {
        AppModel.peek()?.didEnterBackground()
        session?.release()
        session = null
        super.onDestroy()
    }

    private fun session(): MediaSession {
        session?.let { return it }
        val s = MediaSession(this, "RemoteFM1")
        s.setCallback(object : MediaSession.Callback() {
            override fun onPause() = act(ACTION_PAUSE)
            override fun onStop() = act(ACTION_STOP)
            override fun onPlay() = act(ACTION_PLAY)
        }, Handler(Looper.getMainLooper()))
        s.isActive = true
        session = s
        return s
    }

    private fun act(action: String) {
        startService(Intent(this, PlaybackService::class.java).setAction(action))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val m = AppModel.peek()
        when (intent?.action) {
            ACTION_STOP -> {
                m?.shutdown()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> m?.battery?.stop("stopped from the notification")
            ACTION_PLAY -> m?.battery?.play()
        }
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: lastText
        lastText = text
        val paused = m?.suspended == true
        val s = session()
        s.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, m?.let { nameOf(it) } ?: text)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "RemoteFM1").build())
        s.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_PLAY_PAUSE)
            .setState(if (paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
            .build())
        val n = notification(this, if (paused) "RemoteFM1 stopped to save the battery" else text, paused, s)
        var types = 0
        if (Build.VERSION.SDK_INT >= 29) types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, types) else startForeground(ID, n)
        } catch (e: Exception) {
            Log.w(TAG, "cannot start in the foreground: $e")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun nameOf(m: AppModel): String = m.playingText.removePrefix("RemoteFM1 is playing ")

    companion object {
        private const val TAG = "RFMService"
        private const val CHANNEL = "playing"
        private const val ID = 1
        const val ACTION_STOP = "com.quixotic7.remotefm1.STOP"
        const val ACTION_PAUSE = "com.quixotic7.remotefm1.PAUSE"
        const val ACTION_PLAY = "com.quixotic7.remotefm1.PLAY"
        const val EXTRA_TEXT = "text"
        @Volatile private var lastText = "RemoteFM1 is playing"

        /** On while [playing] (the app's model says so), with [text]; off otherwise. */
        fun update(ctx: Context, playing: Boolean, text: String) {
            val i = Intent(ctx, PlaybackService::class.java)
            if (playing) {
                i.putExtra(EXTRA_TEXT, text)
                runCatching { ctx.startForegroundService(i) }.onFailure { Log.w(TAG, "start: $it") }
            } else {
                ctx.stopService(i)
            }
        }

        private fun pending(ctx: Context, code: Int, action: String) =
            PendingIntent.getService(ctx, code, Intent(ctx, PlaybackService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)

        private fun notification(ctx: Context, text: String, paused: Boolean, s: MediaSession): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Playing", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
            val b = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(text)
                .setContentText(if (paused) "Play runs it again; Stop ends the session" else "Pause saves the battery; Stop ends the session")
                .setContentIntent(open)
                .setOngoing(true)
            if (paused) b.addAction(Notification.Action.Builder(null, "Play", pending(ctx, 3, ACTION_PLAY)).build())
            else b.addAction(Notification.Action.Builder(null, "Pause", pending(ctx, 2, ACTION_PAUSE)).build())
            b.addAction(Notification.Action.Builder(null, "Stop", pending(ctx, 1, ACTION_STOP)).build())
            b.style = Notification.MediaStyle().setMediaSession(s.sessionToken).setShowActionsInCompactView(0, 1)
            return b.build()
        }
    }
}
