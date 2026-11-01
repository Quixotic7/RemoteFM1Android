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
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Keeps the engine and the FM-1 link running with the app in the background or the screen off (PLAN.md section 6,
 * Lifecycle): a foreground service of type mediaPlayback (and connectedDevice when the app may use Bluetooth), with
 * the notification "RemoteFM1 is playing <core>" and a Stop action. It holds nothing itself: AppModel does.
 */
class PlaybackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AppModel.peek()?.shutdown()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "RemoteFM1 is playing"
        val n = notification(this, text)
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

    companion object {
        private const val TAG = "RFMService"
        private const val CHANNEL = "playing"
        private const val ID = 1
        const val ACTION_STOP = "com.quixotic7.remotefm1.STOP"
        const val EXTRA_TEXT = "text"

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

        private fun notification(ctx: Context, text: String): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Playing", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(ctx, 1, Intent(ctx, PlaybackService::class.java).setAction(ACTION_STOP),
                                                PendingIntent.FLAG_IMMUTABLE)
            return Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(text)
                .setContentText("Tap to open; Stop ends the session")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, "Stop", stop).build())
                .build()
        }
    }
}
