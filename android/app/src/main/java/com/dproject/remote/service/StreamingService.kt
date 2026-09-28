package com.dproject.remote.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.dproject.remote.Config
import com.dproject.remote.webrtc.SignallingClient
import com.dproject.remote.webrtc.WebRTCManager

class StreamingService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var webRTCManager: WebRTCManager? = null
    private var signallingClient: SignallingClient? = null

    companion object {
        private const val TAG = "StreamingService"
        private const val CHANNEL_ID = "dproject_streaming"
        private const val NOTIF_ID = 1

        fun start(context: Context, sessionId: String, resultCode: Int, data: Intent) {
            val intent = Intent(context, StreamingService::class.java).apply {
                putExtra("sessionId", sessionId)
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StreamingService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sessionId = intent?.getStringExtra("sessionId") ?: run { stopSelf(); return START_NOT_STICKY }
        val resultCode = intent.getIntExtra("resultCode", -1)
        val data = intent.getParcelableExtra<Intent>("data") ?: run { stopSelf(); return START_NOT_STICKY }

        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = projectionManager.getMediaProjection(resultCode, data)

        val metrics = getDisplayMetrics()

        webRTCManager = WebRTCManager(applicationContext) { videoTrack ->
            // VirtualDisplay feeds frames directly into the WebRTC video source
            virtualDisplay = projection?.createVirtualDisplay(
                "DProjectCapture",
                metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                videoTrack.surfaceTextureHelper?.handler?.let { null }, // handled by WebRTC internally
                null, null
            )
        }

        val deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
        signallingClient = SignallingClient(
            sessionId, "phone", Config.SERVER_WS, webRTCManager!!,
            deviceName, metrics.widthPixels, metrics.heightPixels
        )
        signallingClient?.connect()

        Log.d(TAG, "Streaming started for session $sessionId")
        return START_STICKY
    }

    private fun getDisplayMetrics(): DisplayMetrics {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        return DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        projection?.stop()
        webRTCManager?.dispose()
        signallingClient?.disconnect()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "D.project Streaming", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Active remote session" }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("D.project")
            .setContentText("Screen streaming active")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .build()
}
