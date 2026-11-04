package com.example.videodl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri

/** كل إشعارات التطبيق: التقدّم، تم التحميل، فشل، في انتظار الشبكة. */
object Notifs {
    const val CH_PROGRESS = "progress"
    const val CH_RESULT = "result"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "التحميل الجاري", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_RESULT, "نتائج التحميل", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun nid(id: Long): Int = (id % 1_000_000L).toInt() + 1000

    private fun openApp(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun name(i: Item) = i.title ?: i.fileName ?: i.url

    /** الإشعار الدائم الخاص بالخدمة (Foreground). */
    fun progress(ctx: Context, list: List<Item>): Notification {
        val dl = list.filter { it.st == St.DOWNLOADING }
        val waiting = list.filter { it.st == St.WAITING_NET }
        val queued = list.filter { it.st == St.QUEUED }
        val title = when {
            dl.isNotEmpty() -> "جاري التحميل (${dl.size})"
            waiting.isNotEmpty() -> "في انتظار الشبكة…"
            else -> "في قائمة الانتظار"
        }
        val main = dl.firstOrNull() ?: waiting.firstOrNull() ?: queued.firstOrNull()
        val extra = ArrayList<String>()
        if (queued.isNotEmpty() && dl.isNotEmpty()) extra.add("${queued.size} في الانتظار")
        if (waiting.isNotEmpty() && dl.isNotEmpty()) extra.add("${waiting.size} بانتظار الشبكة")
        val pct = if (dl.isNotEmpty()) (dl.map { it.progress }.average() * 100).toInt() else 0

        val pauseAll = PendingIntent.getService(
            ctx, 1,
            Intent(ctx, DownloadService::class.java).setAction(DownloadService.ACTION_PAUSE_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = Notification.Builder(ctx, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(if (main != null) name(main) else "")
            .setContentIntent(openApp(ctx))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, dl.isEmpty() || pct <= 0)
            .addAction(android.R.drawable.ic_media_pause, "إيقاف مؤقت للكل", pauseAll)
        if (extra.isNotEmpty()) b.setSubText(extra.joinToString(" • "))
        return b.build()
    }

    fun done(ctx: Context, i: Item) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(i.uri), i.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            ctx, nid(i.id), view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        nm.notify(
            nid(i.id),
            Notification.Builder(ctx, CH_RESULT)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("تم التحميل ✅")
                .setContentText(name(i))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
        )
    }

    fun failed(ctx: Context, i: Item) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val retry = PendingIntent.getForegroundService(
            ctx, nid(i.id),
            Intent(ctx, DownloadService::class.java)
                .setAction(DownloadService.ACTION_RETRY)
                .putExtra(DownloadService.EXTRA_ID, i.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        nm.notify(
            nid(i.id),
            Notification.Builder(ctx, CH_RESULT)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("فشل التحميل ❌")
                .setContentText(name(i) + (i.error?.let { " — $it" } ?: ""))
                .setStyle(Notification.BigTextStyle().bigText(name(i) + "\n" + (i.error ?: "")))
                .setContentIntent(openApp(ctx))
                .addAction(android.R.drawable.ic_popup_sync, "إعادة المحاولة", retry)
                .setAutoCancel(true)
                .build()
        )
    }

    fun waiting(ctx: Context, i: Item) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.notify(
            nid(i.id),
            Notification.Builder(ctx, CH_PROGRESS)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("في انتظار الشبكة…")
                .setContentText(name(i))
                .setContentIntent(openApp(ctx))
                .setOnlyAlertOnce(true)
                .build()
        )
    }

    fun clear(ctx: Context, id: Long) {
        ctx.getSystemService(NotificationManager::class.java).cancel(nid(id))
    }
}
