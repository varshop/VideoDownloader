package com.example.videodl

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

enum class St {
    ANALYZING, ANALYZE_FAILED, READY,
    QUEUED, DOWNLOADING, WAITING_NET, PAUSED, FAILED, DONE
}

/** صيغة مكتشفة من الموقع (تظهر في قائمة "كل الصيغ"). */
data class FormatOpt(
    val id: String,
    val label: String,
    val hasVideo: Boolean,
    val hasAudio: Boolean
)

/** عنصر واحد في القائمة = رابط واحد. */
data class Item(
    val id: Long,
    val url: String,
    val referer: String? = null,
    val noExpand: Boolean = false,
    val title: String? = null,
    val thumb: String? = null,
    val uploader: String? = null,
    val duration: Int = 0,
    val formats: List<FormatOpt> = emptyList(),
    val st: St = St.ANALYZING,
    val fmt: Fmt = Fmt.MP4,
    val quality: Quality = Quality.BEST,
    val formatId: String? = null,
    val progress: Float = 0f,
    val line: String = "",
    val error: String? = null,
    val uri: String? = null,
    val mime: String? = null,
    val fileName: String? = null
) {
    val isActive: Boolean
        get() = st == St.QUEUED || st == St.DOWNLOADING || st == St.WAITING_NET
}
