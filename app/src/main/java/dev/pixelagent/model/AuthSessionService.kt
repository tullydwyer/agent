package dev.pixelagent.model

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.pixelagent.MainActivity
import dev.pixelagent.R

/** Keep the loopback credential transfer alive while the system browser is foreground. */
class AuthSessionService : Service() {
    companion object { @Volatile var cancelAttempt: (() -> Unit)? = null }
    private val handler = Handler(Looper.getMainLooper())
    private val expire = Runnable { cancelAttempt?.invoke(); stopSelf() }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel" || cancelAttempt == null) {
            cancelAttempt?.invoke(); stopSelf(); return START_NOT_STICKY
        }
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("model-auth", "Account sign-in", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val cancel = PendingIntent.getService(this, 3, Intent(this, AuthSessionService::class.java).setAction("cancel"), PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, "model-auth").setSmallIcon(R.drawable.ic_agent)
                .setContentTitle("Pixel Agent sign-in in progress")
                .setContentText("Waiting for the browser callback · expires in five minutes")
                .setOngoing(true).setContentIntent(open).setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(Notification.Action.Builder(null, "Cancel", cancel).build()).build()
            startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            handler.removeCallbacks(expire)
            handler.postDelayed(expire, 300000)
        } catch (_: RuntimeException) { cancelAttempt?.invoke(); stopSelf() }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) { cancelAttempt?.invoke(); stopSelf() }
    override fun onDestroy() {
        handler.removeCallbacks(expire)
        cancelAttempt?.invoke()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
