package com.example.videodl

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Fmt(val label: String, val ext: String, val audio: Boolean) {
    MP4("MP4", "mp4", false),
    MKV("MKV", "mkv", false),
    WEBM("WEBM", "webm", false),
    MP3("MP3", "mp3", true),
    M4A("M4A", "m4a", true),
    WAV("WAV", "wav", true)
}

enum class Quality(val label: String, val height: Int?) {
    BEST("الأعلى", null),
    P1080("1080p", 1080),
    P720("720p", 720),
    P480("480p", 480),
    P360("360p", 360)
}

data class Info(val title: String, val thumb: String?, val uploader: String?, val duration: Int)
data class Done(val title: String, val label: String, val uri: Uri, val mime: String)

data class UiState(
    val url: String = "",
    val loading: Boolean = false,
    val info: Info? = null,
    val fmt: Fmt = Fmt.MP4,
    val quality: Quality = Quality.BEST,
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val status: String = "",
    val error: String? = null,
    val history: List<Done> = emptyList(),
    val engineMsg: String? = null,
    val updatingEngine: Boolean = false
)

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var infoJob: Job? = null
    private var dlJob: Job? = null
    private var processId: String = ""

    private val urlRegex = Regex("https?://\\S+")
    fun isUrl(s: String) = urlRegex.containsMatchIn(s)
    private fun extract(s: String) = urlRegex.find(s)?.value ?: s.trim()

    /** auto=true عند اللصق أو المشاركة: يحلّل الرابط تلقائياً. */
    fun setUrl(text: String, auto: Boolean) {
        val old = _state.value.url
        val clean = if (auto) extract(text) else text
        _state.update { it.copy(url = clean, info = null, error = null) }
        val pasted = !auto && clean.length - old.length > 5
        if ((auto || pasted) && isUrl(clean)) analyze()
    }

    fun clear() {
        infoJob?.cancel()
        _state.update { it.copy(url = "", info = null, error = null, loading = false) }
    }

    fun setFmt(f: Fmt) = _state.update { it.copy(fmt = f) }
    fun setQuality(q: Quality) = _state.update { it.copy(quality = q) }

    fun analyze() {
        val url = extract(_state.value.url)
        if (!isUrl(url)) {
            _state.update { it.copy(error = "الرابط غير صالح") }
            return
        }
        infoJob?.cancel()
        infoJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, info = null, error = null, url = url) }
            try {
                val i = withContext(Dispatchers.IO) {
                    Engine.ensure(app)
                    YoutubeDL.getInstance().getInfo(
                        YoutubeDLRequest(url).apply { addOption("--no-playlist") }
                    )
                }
                _state.update {
                    it.copy(
                        loading = false,
                        info = Info(i.title ?: "", i.thumbnail, i.uploader, i.duration)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(loading = false, error = "تعذّر قراءة الرابط. جرّب تحديث المحرك بالأسفل ثم أعد المحاولة.")
                }
            }
        }
    }

    private fun buildRequest(url: String, fmt: Fmt, q: Quality, dir: File): YoutubeDLRequest {
        val r = YoutubeDLRequest(url)
        r.addOption("-o", "${dir.absolutePath}/%(title).80s.%(ext)s")
        r.addOption("--no-playlist")
        r.addOption("--no-mtime")
        r.addOption("--retries", "3")
        r.addOption("--socket-timeout", "30")
        if (fmt.audio) {
            r.addOption("-f", "bestaudio/best")
            r.addOption("-x")
            r.addOption("--audio-format", fmt.ext)
            r.addOption("--audio-quality", "0")
        } else {
            val h = q.height
            val f = if (h == null) "bv*+ba/b" else "bv*[height<=$h]+ba/b[height<=$h]/b"
            r.addOption("-f", f)
            r.addOption("--merge-output-format", fmt.ext)
            when (fmt) {
                Fmt.MP4 -> r.addOption("-S", "vcodec:h264,res,acodec:m4a")
                Fmt.WEBM -> r.addOption("-S", "vcodec:vp9,res,acodec:opus")
                else -> {}
            }
        }
        return r
    }

    fun download() {
        val s = _state.value
        val url = extract(s.url)
        if (!isUrl(url) || s.downloading) return
        processId = "dl_${System.currentTimeMillis()}"
        dlJob = viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(downloading = true, progress = 0f, status = "جاري التحضير…", error = null) }
            val dir = File(app.cacheDir, "dl/${System.currentTimeMillis()}").apply { mkdirs() }
            try {
                Engine.ensure(app)
                val req = buildRequest(url, s.fmt, s.quality, dir)
                YoutubeDL.getInstance().execute(req, processId) { p, _, line ->
                    _state.update {
                        it.copy(progress = (p.coerceAtLeast(0f)) / 100f, status = line.trim().take(90))
                    }
                }
                _state.update { it.copy(status = "جاري الحفظ…") }
                val file = dir.listFiles()
                    ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                    ?.maxByOrNull { it.length() }
                    ?: error("لم يتم العثور على الملف المحمّل")
                val uri = Saver.save(app, file)
                val done = Done(file.nameWithoutExtension, file.extension.uppercase(), uri, Saver.mimeFor(file.name))
                _state.update { it.copy(history = listOf(done) + it.history, status = "", progress = 0f) }
            } catch (e: CancellationException) {
                _state.update { it.copy(error = "تم إلغاء التحميل") }
            } catch (e: Exception) {
                val msg = e.message?.lineSequence()?.lastOrNull { it.isNotBlank() }?.take(200) ?: "خطأ غير معروف"
                _state.update { it.copy(error = "فشل التحميل: $msg") }
            } finally {
                dir.deleteRecursively()
                _state.update { it.copy(downloading = false) }
            }
        }
    }

    fun cancel() {
        try { YoutubeDL.getInstance().destroyProcessById(processId) } catch (_: Exception) {}
        dlJob?.cancel()
    }

    /** المواقع تغيّر أنظمتها باستمرار، فتحديث yt-dlp يُصلح معظم أعطال التحميل. */
    fun updateEngine() {
        if (_state.value.updatingEngine) return
        viewModelScope.launch {
            _state.update { it.copy(updatingEngine = true, engineMsg = "جاري تحديث المحرك…") }
            val msg = try {
                withContext(Dispatchers.IO) {
                    Engine.ensure(app)
                    val st = YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel._STABLE)
                    if (st == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) "المحرك محدّث بالفعل ✅" else "تم تحديث المحرك ✅"
                }
            } catch (e: Exception) {
                "فشل التحديث، تحقق من الإنترنت"
            }
            _state.update { it.copy(updatingEngine = false, engineMsg = msg) }
        }
    }
}
