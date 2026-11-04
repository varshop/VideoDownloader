package com.example.videodl

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * خدمة أمامية (Foreground Service): تُبقي التحميل شغّالاً حتى لو أغلقت التطبيق
 * أو أطفأت الشاشة، وتعرض إشعار تقدّم دائم. تتوقف تلقائياً عند انتهاء كل التحميلات.
 */
class DownloadService : Service() {
    companion object {
        const val ACTION_PAUSE_ALL = "com.example.videodl.PAUSE_ALL"
        const val ACTION_RETRY = "com.example.videodl.RETRY"
        const val EXTRA_ID = "id"
        private const val NID = 1
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Downloader.init(application)
        Notifs.ensureChannels(this)
        startForeground(
            NID,
            Notifs.progress(this, Downloader.items.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        if (wake == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "videodl:download").apply {
                acquire(3 * 60 * 60 * 1000L)
            }
        }

        when (intent?.action) {
            ACTION_PAUSE_ALL -> Downloader.pauseAll()
            ACTION_RETRY -> Downloader.start(intent.getLongExtra(EXTRA_ID, 0L))
        }

        val nm = getSystemService(NotificationManager::class.java)
        job?.cancel()
        job = scope.launch {
            Downloader.items.conflate().collect { list ->
                if (list.none { it.isActive }) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    nm.notify(NID, Notifs.progress(this@DownloadService, list))
                    delay(700)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        try { wake?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        super.onDestroy()
    }
}
