package com.example.videodl

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

fun JSONObject.str(k: String): String? =
    if (isNull(k)) null else optString(k).trim().ifEmpty { null }

fun fmtSize(bytes: Long): String {
    val mb = bytes / 1048576.0
    return if (mb >= 1024) String.format(Locale.US, "%.1f GB", mb / 1024)
    else String.format(Locale.US, "%.1f MB", mb)
}

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

    /** تحديث yt-dlp (عبر reflection حتى لا يتأثر البناء باختلاف إصدارات المكتبة). */
    fun update(app: Context): String {
        val yd = YoutubeDL.getInstance()
        val m = yd.javaClass.methods.firstOrNull { it.name == "updateYoutubeDL" }
            ?: return "التحديث غير مدعوم في هذا الإصدار"
        val args: Array<Any?> = if (m.parameterTypes.size == 1) {
            arrayOf(app)
        } else {
            arrayOf(app, defaultChannel(m.parameterTypes[1]))
        }
        val r = m.invoke(yd, *args)
        return if (r.toString().contains("ALREADY", ignoreCase = true))
            "المحرك محدّث بالفعل ✅" else "تم تحديث المحرك ✅"
    }

    private fun defaultChannel(cls: Class<*>): Any? {
        for (n in listOf("_STABLE", "STABLE")) {
            try { return cls.getField(n).get(null) } catch (_: Exception) {}
        }
        try {
            return Class.forName(cls.name + "\$STABLE").getField("INSTANCE").get(null)
        } catch (_: Exception) {}
        return null
    }
}

/** حفظ الملف النهائي في Download/Videodownloader عبر MediaStore (بدون صلاحيات تخزين). */
object Saver {
    fun mimeFor(name: String): String = when (name.substringAfterLast('.').lowercase()) {
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
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
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Videodownloader")
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

/** تحليل الرابط: عنوان + صورة مصغّرة + كل الصيغ المتاحة (أو قائمة فيديوهات إن كانت الصفحة تحتوي عدة فيديوهات). */
object Analyzer {
    data class Meta(
        val title: String?,
        val thumb: String?,
        val uploader: String?,
        val duration: Int,
        val formats: List<FormatOpt>
    )

    data class Entry(
        val url: String,
        val title: String?,
        val thumb: String?,
        val duration: Int,
        val direct: Boolean,
        val referer: String?
    )

    sealed class Result {
        class Single(val meta: Meta) : Result()
        class Many(val entries: List<Entry>) : Result()
    }

    private class Row(val opt: FormatOpt, val h: Int, val score: Double)

    fun run(url: String, referer: String?, noExpand: Boolean): Result {
        val r = YoutubeDLRequest(url)
        r.addOption("-J")
        r.addOption("--flat-playlist")
        r.addOption("--no-warnings")
        r.addOption("--socket-timeout", "20")
        if (noExpand || url.contains("watch?v=") || url.contains("youtu.be/")) r.addOption("--no-playlist")
        if (referer != null) r.addOption("--referer", referer)

        val out: String = YoutubeDL.getInstance().execute(r).out ?: ""
        val s = out.indexOf('{')
        val e = out.lastIndexOf('}')
        if (s < 0 || e <= s) error("لم يتم العثور على بيانات")
        val j = JSONObject(out.substring(s, e + 1))

        if (j.str("_type") == "playlist") {
            val arr = j.optJSONArray("entries") ?: JSONArray()
            if (noExpand) {
                // لا نكرّر التوسعة: نتعامل معه كفيديو واحد يُحمَّل بالصيغة الافتراضية
                return Result.Single(Meta(j.str("title"), thumbOf(j), j.str("uploader"), 0, emptyList()))
            }
            val entries = ArrayList<Entry>()
            for (k in 0 until minOf(arr.length(), 100)) {
                val o = arr.optJSONObject(k) ?: continue
                val u = o.str("url")?.takeIf { it.startsWith("http") }
                    ?: o.str("webpage_url")?.takeIf { it.startsWith("http") }
                    ?: continue
                entries.add(
                    Entry(u, o.str("title"), thumbOf(o), o.optDouble("duration", 0.0).toInt(), false, referer)
                )
            }
            if (entries.isEmpty()) error("لا توجد فيديوهات في هذا الرابط")
            return Result.Many(entries)
        }

        val title = j.str("title")
        return Result.Single(
            Meta(
                title = title,
                thumb = thumbOf(j),
                uploader = j.str("uploader") ?: j.str("channel"),
                duration = j.optDouble("duration", 0.0).toInt(),
                formats = parseFormats(j.optJSONArray("formats"))
            )
        )
    }

    private fun thumbOf(o: JSONObject): String? {
        o.str("thumbnail")?.let { return it }
        val arr = o.optJSONArray("thumbnails") ?: return null
        for (k in arr.length() - 1 downTo 0) {
            arr.optJSONObject(k)?.str("url")?.let { return it }
        }
        return null
    }

    private fun parseFormats(arr: JSONArray?): List<FormatOpt> {
        if (arr == null) return emptyList()
        val rows = ArrayList<Row>()
        for (k in 0 until arr.length()) {
            val f = arr.optJSONObject(k) ?: continue
            val id = f.str("format_id") ?: continue
            val ext = f.str("ext") ?: ""
            if (ext == "mhtml" || f.str("format_note") == "storyboard") continue
            val vc = f.str("vcodec")
            val ac = f.str("acodec")
            val unknown = vc == null && ac == null
            val hasV = unknown || (vc != null && vc != "none")
            val hasA = unknown || (ac != null && ac != "none")
            val h = f.optInt("height", 0)
            val fps = f.optInt("fps", 0)
            val abr = f.optInt("abr", 0)
            val tbr = f.optDouble("tbr", 0.0)
            val size = f.optLong("filesize", 0L).takeIf { it > 0 } ?: f.optLong("filesize_approx", 0L)

            val parts = ArrayList<String>()
            if (hasV) {
                parts.add(if (h > 0) (if (fps > 30) "${h}p$fps" else "${h}p") else "فيديو")
            } else {
                parts.add(if (abr > 0) "صوت فقط ${abr}kbps" else "صوت فقط")
            }
            if (ext.isNotEmpty()) parts.add(ext.uppercase(Locale.US))
            if (hasV && !hasA) parts.add("بدون صوت")
            if (vc != null && vc != "none") parts.add(vc.substringBefore('.'))
            if (size > 0) parts.add(fmtSize(size))
            rows.add(Row(FormatOpt(id, parts.joinToString(" • "), hasV, hasA), h, if (hasV) tbr else abr.toDouble()))
        }
        return rows
            .sortedWith(
                compareByDescending<Row> { it.opt.hasVideo }
                    .thenByDescending { it.h }
                    .thenByDescending { it.score }
            )
            .map { it.opt }
            .take(40)
    }
}

/**
 * خطة بديلة للمواقع التي لا يدعمها yt-dlp: نقرأ كود الصفحة ونبحث عن روابط الفيديو
 * (mp4 / m3u8 / webm / mpd ... داخل وسوم video و og:video والسكربتات).
 */
object PageScanner {
    private val mediaRe = Regex(
        """https?://[^\s"'<>\\]+?\.(?:mp4|m3u8|webm|mov|mkv|mpd)(?:\?[^\s"'<>\\]*)?""",
        RegexOption.IGNORE_CASE
    )
    private val metaVideoRe = Regex(
        """<meta[^>]+(?:property|name)=["'](?:og:video(?::secure_url|:url)?|twitter:player:stream)["'][^>]*content=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    )
    private val srcRe = Regex("""<(?:video|source)[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val ogImageRe = Regex(
        """<meta[^>]+property=["']og:image["'][^>]*content=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    )

    fun scan(pageUrl: String): List<Analyzer.Entry> {
        val html = fetch(pageUrl) ?: return emptyList()
        val t = html.replace("\\/", "/").replace("&amp;", "&").replace("\\u0026", "&")
        val found = LinkedHashSet<String>()
        fun add(raw: String) {
            try {
                val abs = URI(pageUrl).resolve(raw.trim()).toString()
                if (abs.startsWith("http")) found.add(abs)
            } catch (_: Exception) {}
        }
        metaVideoRe.findAll(t).forEach { add(it.groupValues[1]) }
        srcRe.findAll(t).forEach { add(it.groupValues[1]) }
        mediaRe.findAll(t).forEach { add(it.value) }
        if (found.isEmpty()) return emptyList()

        val thumb = ogImageRe.find(t)?.groupValues?.get(1)?.let {
            try { URI(pageUrl).resolve(it).toString() } catch (_: Exception) { null }
        }
        return found.take(30).map { u ->
            val name = try {
                java.net.URLDecoder.decode(
                    URI(u).path.substringAfterLast('/').substringBeforeLast('.'), "UTF-8"
                )
            } catch (_: Exception) { null }
            Analyzer.Entry(u, name?.ifBlank { null }, thumb, 0, true, pageUrl)
        }
    }

    private fun fetch(u: String): String? {
        return try {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 12000
            c.readTimeout = 15000
            c.instanceFollowRedirects = true
            c.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
            )
            val type = c.contentType ?: ""
            if (!(type.contains("text") || type.contains("json") || type.contains("xml") || type.isEmpty())) {
                c.disconnect(); return null
            }
            val bos = ByteArrayOutputStream()
            c.inputStream.use { ins ->
                val buf = ByteArray(16384)
                var total = 0
                while (total < 3_000_000) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    bos.write(buf, 0, n)
                    total += n
                }
            }
            c.disconnect()
            bos.toString("UTF-8")
        } catch (_: Exception) {
            null
        }
    }
}

object Ytdlp {
    /** بناء أمر التحميل حسب اختيار المستخدم (صيغة/جودة/صيغة محددة من القائمة). */
    fun buildDownload(i: Item, dir: File): YoutubeDLRequest {
        val r = YoutubeDLRequest(i.url)
        r.addOption("-o", dir.absolutePath + "/%(title).80s.%(ext)s")
        r.addOption("--no-playlist")
        r.addOption("--no-mtime")
        r.addOption("--continue")                 // استكمال التحميل من حيث توقف
        r.addOption("-N", "8")                    // تحميل سريع: 8 اتصالات متوازية للأجزاء
        r.addOption("--retries", "10")
        r.addOption("--fragment-retries", "10")
        r.addOption("--socket-timeout", "30")
        if (i.referer != null) r.addOption("--referer", i.referer)

        val chosen = i.formats.firstOrNull { it.id == i.formatId }
        if (chosen != null) {
            // صيغة محددة من قائمة "كل الصيغ المكتشفة"
            val f = if (chosen.hasVideo && !chosen.hasAudio) "${chosen.id}+ba/${chosen.id}" else chosen.id
            r.addOption("-f", f)
            r.addOption("--merge-output-format", "mp4/mkv")
        } else if (i.fmt.audio) {
            r.addOption("-f", "bestaudio/best")
            r.addOption("-x")
            r.addOption("--audio-format", i.fmt.ext)
            r.addOption("--audio-quality", "0")
        } else {
            val h = i.quality.height
            val f = if (h == null) "bv*+ba/b" else "bv*[height<=$h]+ba/b[height<=$h]/b"
            r.addOption("-f", f)
            r.addOption("--merge-output-format", i.fmt.ext)
            when (i.fmt) {
                Fmt.MP4 -> r.addOption("-S", "res,vcodec:h264,acodec:m4a")
                Fmt.WEBM -> r.addOption("-S", "res,vcodec:vp9,acodec:opus")
                else -> {}
            }
        }
        return r
    }
}
