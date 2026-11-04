package com.example.videodl

import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * مدير التحميل المركزي: قائمة الروابط، التحليل، التحميل المتوازي، الإيقاف/الاستكمال،
 * الانتظار عند انقطاع الشبكة، وحفظ القائمة على القرص.
 */
object Downloader {
    private lateinit var app: Application
    private var inited = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items.asStateFlow()

    val online = MutableStateFlow(true)
    val defFmt = MutableStateFlow(Fmt.MP4)
    val defQuality = MutableStateFlow(Quality.BEST)

    private val dlJobs = ConcurrentHashMap<Long, Job>()
    private val anJobs = ConcurrentHashMap<Long, Job>()
    private val userStopped: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val netDropped: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val dlSem = Semaphore(3)   // 3 تحميلات بالتوازي
    private val anSem = Semaphore(3)   // 3 تحليلات بالتوازي
    private val saveMutex = Mutex()
    private var seq = System.currentTimeMillis()

    // ---------------------------------------------------------------- init

    @Synchronized
    fun init(a: Application) {
        if (inited) return
        inited = true
        app = a
        load()
        watchNetwork()
        _items.value.filter { it.st == St.ANALYZING }.forEach { analyze(it.id) }
    }

    private fun watchNetwork() {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        fun capsOk(c: NetworkCapabilities?): Boolean =
            c != null &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        online.value = capsOk(cm.getNetworkCapabilities(cm.activeNetwork))
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    online.value = capsOk(caps)
                }
                override fun onLost(network: Network) {
                    online.value = false
                }
            })
        } catch (_: Exception) {}

        // إذا بقي الإنترنت مقطوعاً أكثر من 8 ثوانٍ نوقف العمليات الجارية لتنتظر الشبكة ثم تستكمل تلقائياً
        scope.launch {
            online.collectLatest { on ->
                if (!on) {
                    delay(8000)
                    if (!online.value) {
                        _items.value.filter { it.st == St.DOWNLOADING }.forEach {
                            netDropped.add(it.id)
                            kill(it.id)
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun find(id: Long): Item? = _items.value.firstOrNull { it.id == id }
    private fun pid(id: Long) = "dl_$id"
    private fun dirFor(id: Long): File =
        File(app.getExternalFilesDir("downloads") ?: app.filesDir, id.toString())

    private fun upd(id: Long, persist: Boolean = false, f: (Item) -> Item) {
        _items.update { list -> list.map { if (it.id == id) f(it) else it } }
        if (persist) save()
    }

    private fun kill(id: Long) {
        try { YoutubeDL.getInstance().destroyProcessById(pid(id)) } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- add / analyze

    private val urlRegex = Regex("https?://[^\\s<>\"']+")

    fun extractUrls(text: String): List<String> =
        urlRegex.findAll(text).map { it.value.trimEnd('.', ',', ')', ';', '،', '!') }.distinct().toList()

    /** يضيف كل الروابط الموجودة في النص (رابط أو عدة روابط) ويبدأ تحليلها. */
    fun addLinks(text: String): Int {
        val existing = _items.value.map { it.url }.toSet()
        val fresh = extractUrls(text).filter { it !in existing }
        if (fresh.isEmpty()) return 0
        val newItems = fresh.map {
            Item(id = ++seq, url = it, fmt = defFmt.value, quality = defQuality.value)
        }
        _items.update { newItems + it }
        save()
        newItems.forEach { analyze(it.id) }
        return newItems.size
    }

    fun analyze(id: Long) {
        val first = find(id) ?: return
        upd(id) { it.copy(st = St.ANALYZING, error = null) }
        anJobs[id] = scope.launch {
            anSem.withPermit {
                try {
                    Engine.ensure(app)
                    val res: Analyzer.Result = try {
                        Analyzer.run(first.url, first.referer, first.noExpand)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // الموقع غير مدعوم؟ نبحث عن روابط الفيديو داخل كود الصفحة
                        val found = if (first.noExpand) emptyList() else PageScanner.scan(first.url)
                        if (found.isEmpty()) throw e
                        Analyzer.Result.Many(found)
                    }
                    when (res) {
                        is Analyzer.Result.Single -> {
                            val m = res.meta
                            upd(id, true) {
                                it.copy(
                                    title = m.title ?: it.title, thumb = m.thumb ?: it.thumb,
                                    uploader = m.uploader, duration = m.duration,
                                    formats = m.formats, st = St.READY, error = null
                                )
                            }
                        }
                        is Analyzer.Result.Many -> expand(first, res.entries)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    upd(id, true) {
                        it.copy(st = St.ANALYZE_FAILED, error = "تعذّر قراءة الرابط. تأكد منه أو حدّث المحرك ثم أعد المحاولة.")
                    }
                }
            }
        }
    }

    /** استبدال الرابط بعدة عناصر (قائمة تشغيل، أو صفحة فيها عدة فيديوهات). */
    private fun expand(orig: Item, entries: List<Analyzer.Entry>) {
        val created = entries.map { e ->
            Item(
                id = ++seq, url = e.url, referer = e.referer, noExpand = true,
                title = e.title, thumb = e.thumb, duration = e.duration,
                fmt = orig.fmt, quality = orig.quality,
                st = if (e.direct) St.READY else St.ANALYZING
            )
        }
        _items.update { list -> list.flatMap { if (it.id == orig.id) created else listOf(it) } }
        save()
        created.filter { it.st == St.ANALYZING }.forEach { analyze(it.id) }
    }

    // ---------------------------------------------------------------- format selection

    fun setFmt(id: Long, f: Fmt) = upd(id, true) { it.copy(fmt = f, formatId = null) }
    fun setQuality(id: Long, q: Quality) = upd(id, true) { it.copy(quality = q, formatId = null) }
    fun setFormatId(id: Long, fid: String?) = upd(id, true) { it.copy(formatId = fid) }

    fun applyDefaultsToAll() {
        val f = defFmt.value
        val q = defQuality.value
        _items.update { list ->
            list.map {
                if (it.st == St.READY || it.st == St.PAUSED || it.st == St.FAILED)
                    it.copy(fmt = f, quality = q, formatId = null)
                else it
            }
        }
        save()
    }

    // ---------------------------------------------------------------- download control

    fun start(id: Long) {
        val cur = find(id) ?: return
        if (cur.st != St.READY && cur.st != St.PAUSED && cur.st != St.FAILED) return
        userStopped.remove(id)
        netDropped.remove(id)
        upd(id, true) { it.copy(st = St.QUEUED, error = null, line = "") }
        try {
            app.startForegroundService(Intent(app, DownloadService::class.java))
        } catch (_: Exception) {}
        dlJobs[id] = scope.launch { dlSem.withPermit { runDownload(id) } }
    }

    fun startAll() {
        _items.value
            .filter { it.st == St.READY || it.st == St.PAUSED || it.st == St.FAILED }
            .forEach { start(it.id) }
    }

    fun pause(id: Long) {
        val i = find(id) ?: return
        if (!i.isActive) return
        userStopped.add(id)
        kill(id)
        dlJobs.remove(id)?.cancel()
        Notifs.clear(app, id)
        upd(id, true) { it.copy(st = St.PAUSED, line = "") }
    }

    fun pauseAll() {
        _items.value.filter { it.isActive }.forEach { pause(it.id) }
    }

    /** إلغاء وحذف الجزء المحمّل (أو إزالة العنصر من القائمة إن كان مكتملاً). */
    fun cancel(id: Long) {
        val i = find(id)
        if (i != null && i.isActive) {
            userStopped.add(id)
            kill(id)
        }
        dlJobs.remove(id)?.cancel()
        anJobs.remove(id)?.cancel()
        Notifs.clear(app, id)
        scope.launch { dirFor(id).deleteRecursively() }
        _items.update { list -> list.filter { it.id != id } }
        save()
    }

    fun clearFinished() {
        _items.update { list -> list.filter { it.st != St.DONE } }
        save()
    }

    private fun friendlyError(e: Exception): String {
        val raw = e.message?.lineSequence()?.lastOrNull { it.isNotBlank() } ?: "خطأ غير معروف"
        return raw.removePrefix("ERROR:").trim().take(160)
    }

    private fun isHardError(msg: String): Boolean {
        val m = msg.lowercase()
        return listOf("unsupported url", "private", "sign in", "log in", "login", "not available", "removed", "copyright")
            .any { m.contains(it) }
    }

    private suspend fun runDownload(id: Long) {
        var attempts = 0
        val dir = dirFor(id).apply { mkdirs() }
        while (true) {
            val item = find(id) ?: return
            if (id in userStopped) return

            if (!online.value) {
                upd(id) { it.copy(st = St.WAITING_NET, line = "") }
                Notifs.waiting(app, item)
                online.first { it }          // ننتظر عودة الإنترنت ثم نستكمل تلقائياً
                if (id in userStopped) return
            }
            Notifs.clear(app, id)
            upd(id) { it.copy(st = St.DOWNLOADING, line = "جاري البدء…", error = null) }

            try {
                Engine.ensure(app)
                val req = Ytdlp.buildDownload(item, dir)
                YoutubeDL.getInstance().execute(req, pid(id)) { p, _, line ->
                    upd(id) { x -> x.copy(progress = p.coerceAtLeast(0f) / 100f, line = line.trim().take(90)) }
                }
                val file = dir.listFiles()
                    ?.filter {
                        it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") &&
                            !it.name.endsWith(".temp") && !it.name.endsWith(".tmp")
                    }
                    ?.maxByOrNull { it.length() }
                    ?: error("لم يتم العثور على الملف المحمّل")
                upd(id) { it.copy(line = "جاري الحفظ…") }
                val uri = Saver.save(app, file)
                val mime = Saver.mimeFor(file.name)
                dir.deleteRecursively()
                upd(id, true) {
                    it.copy(
                        st = St.DONE, progress = 1f, line = "", error = null,
                        uri = uri.toString(), mime = mime, fileName = file.name,
                        title = it.title ?: file.nameWithoutExtension
                    )
                }
                find(id)?.let { Notifs.done(app, it) }
                return
            } catch (e: Exception) {
                if (id in userStopped) return
                if (id in netDropped || !online.value) {
                    netDropped.remove(id)
                    continue          // أعلى الحلقة سيضع الحالة "في انتظار الشبكة"
                }
                val msg = friendlyError(e)
                attempts++
                if (attempts < 3 && !isHardError(msg)) {
                    delay(3000)
                    continue          // إعادة محاولة تلقائية (التحميل يُستكمل من الجزء المحفوظ)
                }
                upd(id, true) { it.copy(st = St.FAILED, error = msg) }
                find(id)?.let { Notifs.failed(app, it) }
                return
            }
        }
    }

    // ---------------------------------------------------------------- persistence

    private fun file() = File(app.filesDir, "items.json")

    private fun save() {
        scope.launch {
            saveMutex.withLock {
                try {
                    val arr = JSONArray()
                    _items.value.forEach { arr.put(toJson(it)) }
                    file().writeText(arr.toString())
                } catch (_: Exception) {}
            }
        }
    }

    private fun load() {
        try {
            val f = file()
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            val list = ArrayList<Item>()
            for (k in 0 until arr.length()) {
                val o = arr.optJSONObject(k) ?: continue
                list.add(fromJson(o))
            }
            _items.value = list
            seq = maxOf(seq, list.maxOfOrNull { it.id } ?: 0L)
        } catch (_: Exception) {}
    }

    private fun toJson(i: Item): JSONObject {
        val o = JSONObject()
        o.put("id", i.id)
        o.put("url", i.url)
        o.put("referer", i.referer ?: JSONObject.NULL)
        o.put("noExpand", i.noExpand)
        o.put("title", i.title ?: JSONObject.NULL)
        o.put("thumb", i.thumb ?: JSONObject.NULL)
        o.put("uploader", i.uploader ?: JSONObject.NULL)
        o.put("duration", i.duration)
        o.put("st", i.st.name)
        o.put("fmt", i.fmt.name)
        o.put("quality", i.quality.name)
        o.put("formatId", i.formatId ?: JSONObject.NULL)
        o.put("progress", i.progress.toDouble())
        o.put("error", i.error ?: JSONObject.NULL)
        o.put("uri", i.uri ?: JSONObject.NULL)
        o.put("mime", i.mime ?: JSONObject.NULL)
        o.put("fileName", i.fileName ?: JSONObject.NULL)
        val fa = JSONArray()
        i.formats.forEach {
            val fo = JSONObject()
            fo.put("id", it.id)
            fo.put("label", it.label)
            fo.put("v", it.hasVideo)
            fo.put("a", it.hasAudio)
            fa.put(fo)
        }
        o.put("formats", fa)
        return o
    }

    private fun fromJson(o: JSONObject): Item {
        val fa = o.optJSONArray("formats") ?: JSONArray()
        val formats = ArrayList<FormatOpt>()
        for (k in 0 until fa.length()) {
            val fo = fa.optJSONObject(k) ?: continue
            formats.add(
                FormatOpt(fo.optString("id"), fo.optString("label"), fo.optBoolean("v", true), fo.optBoolean("a", true))
            )
        }
        var st = try { St.valueOf(o.optString("st")) } catch (_: Exception) { St.READY }
        // ما كان يعمل قبل إغلاق التطبيق يصبح "متوقف" ويمكن استكماله بضغطة
        if (st == St.DOWNLOADING || st == St.QUEUED || st == St.WAITING_NET) st = St.PAUSED
        return Item(
            id = o.optLong("id"),
            url = o.optString("url"),
            referer = o.str("referer"),
            noExpand = o.optBoolean("noExpand", false),
            title = o.str("title"),
            thumb = o.str("thumb"),
            uploader = o.str("uploader"),
            duration = o.optInt("duration", 0),
            formats = formats,
            st = st,
            fmt = try { Fmt.valueOf(o.optString("fmt")) } catch (_: Exception) { Fmt.MP4 },
            quality = try { Quality.valueOf(o.optString("quality")) } catch (_: Exception) { Quality.BEST },
            formatId = o.str("formatId"),
            progress = o.optDouble("progress", 0.0).toFloat(),
            error = o.str("error"),
            uri = o.str("uri"),
            mime = o.str("mime"),
            fileName = o.str("fileName")
        )
    }
}
