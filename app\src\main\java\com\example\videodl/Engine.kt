package com.example.videodl

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** تهيئة yt-dlp + ffmpeg مرة واحدة (أول تشغيل يستغرق بضع ثوانٍ). */
object Engine {
    private val mutex = Mutex()
    @Volatile private var ready = false

    suspend fun ensure(ctx: Context) = mutex.withLock {
        if (!ready) {
            withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().init(ctx.applicationContext)
                FFmpeg.getInstance().init(ctx.applicationContext)
            }
            ready = true
        }
    }
}

/** حفظ الملف النهائي في مجلد Download/VideoDownloader عبر MediaStore (بدون صلاحيات تخزين). */
object Saver {
    fun mimeFor(name: String): String = when (name.substringAfterLast('.').lowercase()) {
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/x-wav"
        "opus", "ogg" -> "audio/ogg"
        else -> "application/octet-stream"
    }

    fun save(ctx: Context, file: File): Uri {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, mimeFor(file.name))
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/VideoDownloader")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("تعذّر إنشاء الملف")
        resolver.openOutputStream(uri)!!.use { out ->
            file.inputStream().use { it.copyTo(out) }
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }
}
