package com.danielealbano.androidremotecontrolmcp.probejuez

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that runs the probe, like the real app's server service: the run keeps going while
 * another app is on screen, which is the condition the plan judge must work under.
 */
class ProbeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        val request = intent?.let { RunRequest.from(it) }
        if (request == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        scope.launch {
            try {
                ProbeRunner(applicationContext).run(request)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "JUEZ ERROR tag=${request.tag} ${t.javaClass.simpleName}: ${t.message}", t)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Probe juez", NotificationManager.IMPORTANCE_LOW),
        )
        return Notification
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Probe juez local")
            .setContentText("Corriendo la prueba del juez")
            .setOngoing(true)
            .build()
    }

    companion object {
        const val TAG = "JUEZ"
        private const val CHANNEL_ID = "probe_juez"
        private const val NOTIFICATION_ID = 7301
    }
}
