package avalon.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import avalon.server.AvalonServer
import avalon.server.GameKind

/**
 * Keeps the Avalon server alive while the host phone's screen is off.
 * A foreground service with a persistent notification plus a partial wake lock
 * so the CPU keeps serving players.
 */
class HostService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat(buildNotification(getString(R.string.starting)))
        if (server == null) {
            try {
                val s = AvalonServer(port = PORT)
                s.selectedGame = selectedGame(this)
                s.start(wait = false)
                server = s
                Log.i(TAG, "Server started on port $PORT, host url ${s.hostUrl()}")
            } catch (e: Exception) {
                Log.e(TAG, "Could not start server", e)
                lastError = e.message ?: e.toString()
            }
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "avalon:host").also { it.acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val game = selectedGame(this)
        val url = server?.joinUrlFor(AvalonServer.bestLanAddress(), game)
        val text = if (url != null) getString(R.string.notification_text, url, game.displayName) else getString(R.string.server_running, PORT)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
        return START_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        try { server?.stop() } catch (_: Exception) {}
        server = null
        super.onDestroy()
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val TAG = "AvalonHost"
        const val PORT = 8080
        private const val CHANNEL = "avalon_host"
        private const val NOTIFICATION_ID = 1

        /** The running server, shared with the activities. */
        @Volatile var server: AvalonServer? = null
        @Volatile var lastError: String? = null

        private const val PREFS = "host"
        private const val KEY_GAME = "game"

        /** The game picked on the host screen (remembered between launches). */
        fun selectedGame(context: Context): GameKind {
            val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_GAME, null)
            return GameKind.values().firstOrNull { it.name == name } ?: GameKind.AVALON
        }

        fun setSelectedGame(context: Context, game: GameKind) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_GAME, game.name).apply()
            server?.selectedGame = game
        }

        fun start(context: Context) {
            val i = Intent(context, HostService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HostService::class.java))
        }
    }
}
