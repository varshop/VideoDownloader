package com.example.videodl

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            AppTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Screen(vm)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            i.getStringExtra(Intent.EXTRA_TEXT)?.let { vm.add(it) }
        }
    }
}

// ------------------------------------------------------------------ helpers

private fun openFile(ctx: Context, i: Item) {
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(i.uri), i.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
        Toast.makeText(ctx, "لا يوجد تطبيق لفتح الملف", Toast.LENGTH_SHORT).show()
    }
}

private fun fmtDuration(sec: Int): String? {
    if (sec <= 0) return null
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun host(url: String): String? =
    try { Uri.parse(url).host?.removePrefix("www.") } catch (_: Exception) { null }

private fun stateText(i: Item): String = when (i.st) {
    St.ANALYZING -> "جاري التحليل…"
    St.ANALYZE_FAILED -> "تعذّر التحليل"
    St.READY -> "جاهز للتحميل"
    St.QUEUED -> "في الانتظار"
    St.DOWNLOADING -> "جاري التحميل ${(i.progress * 100).toInt()}%"
    St.WAITING_NET -> "في انتظار الشبكة…"
    St.PAUSED -> "متوقف مؤقتاً — يمكن الاستكمال"
    St.FAILED -> "فشل التحميل"
    St.DONE -> "تم التحميل ✅"
}

// ------------------------------------------------------------------ screen

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Screen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val list by Downloader.items.collectAsStateWithLifecycle()
    val input by vm.input.collectAsStateWithLifecycle()
    val msg by vm.message.collectAsStateWithLifecycle()
    val engineMsg by vm.engineMsg.collectAsStateWithLifecycle()
    val updating by vm.updating.collectAsStateWithLifecycle()
    val defFmt by Downloader.defFmt.collectAsStateWithLifecycle()
    val defQ by Downloader.defQuality.collectAsStateWithLifecycle()

    LaunchedEffect(msg) {
        msg?.let {
            Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show()
            vm.message.value = null
        }
    }

    val startable = list.count { it.st == St.READY || it.st == St.PAUSED || it.st == St.FAILED }
    val activeCount = list.count { it.isActive }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            Modifier.fillMaxSize().safeDrawingPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---- الترويسة
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(52.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Videodownloader", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "الصق رابطاً أو عدة روابط من أي موقع",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                        )
                    }
                }
            }

            // ---- إدخال الروابط + زر (+)
            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { vm.setInput(it) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("رابط أو عدة روابط (كل رابط في سطر)") },
                            minLines = 2,
                            maxLines = 6,
                            shape = RoundedCornerShape(14.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            trailingIcon = {
                                if (input.isNotEmpty()) {
                                    IconButton(onClick = { vm.setInput("") }) {
                                        Icon(Icons.Default.Close, contentDescription = "مسح")
                                    }
                                }
                            }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = { clip.getText()?.text?.let { vm.add(it) } },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("لصق وإضافة")
                            }
                            Button(
                                onClick = { vm.addFromInput() },
                                enabled = Downloader.extractUrls(input).isNotEmpty(),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("إضافة (+)")
                            }
                        }
                    }
                }
            }

            // ---- الصيغة الافتراضية + تحميل الكل
            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("الصيغة والجودة للكل", fontWeight = FontWeight.SemiBold)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Fmt.entries.forEach { f ->
                                FilterChip(
                                    selected = defFmt == f,
                                    onClick = { Downloader.defFmt.value = f },
                                    label = { Text(if (f.audio) "🎵 ${f.label}" else "🎬 ${f.label}") }
                                )
                            }
                        }
                        if (!defFmt.audio) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Quality.entries.forEach { q ->
                                    FilterChip(
                                        selected = defQ == q,
                                        onClick = { Downloader.defQuality.value = q },
                                        label = { Text(q.label) }
                                    )
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = { Downloader.applyDefaultsToAll() },
                            enabled = startable > 0,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("تطبيق هذه الصيغة على كل القائمة") }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { Downloader.startAll() },
                                enabled = startable > 0,
                                modifier = Modifier.weight(1f).height(52.dp),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("تحميل الكل ($startable)")
                            }
                            if (activeCount > 0) {
                                OutlinedButton(
                                    onClick = { Downloader.pauseAll() },
                                    modifier = Modifier.height(52.dp),
                                    shape = RoundedCornerShape(14.dp)
                                ) { Text("إيقاف الكل") }
                            }
                        }
                    }
                }
            }

            // ---- عنوان القائمة
            if (list.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("القائمة (${list.size})", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (list.any { it.st == St.DONE }) {
                            TextButton(onClick = { Downloader.clearFinished() }) { Text("مسح المكتمل") }
                        }
                    }
                }
            }

            items(list, key = { it.id }) { item -> ItemCard(item) }

            // ---- تذييل
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "الملفات تُحفظ في: التنزيلات ‹ Videodownloader",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    TextButton(onClick = { vm.updateEngine() }, enabled = !updating) {
                        Text("تحديث المحرك (إذا فشل موقع معيّن)")
                    }
                    engineMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ item card

@Composable
private fun Thumb(url: String?) {
    Box(
        Modifier
            .width(120.dp)
            .height(72.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(Icons.Default.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun FormatRow(f: FormatOpt, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(f.label, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemCard(i: Item) {
    val ctx = LocalContext.current
    var showAll by remember { mutableStateOf(false) }
    val stateColor = when (i.st) {
        St.DONE -> MaterialTheme.colorScheme.secondary
        St.FAILED, St.ANALYZE_FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    }

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Thumb(i.thumb)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        i.title ?: host(i.url) ?: i.url,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold
                    )
                    val sub = listOfNotNull(i.uploader, fmtDuration(i.duration), host(i.url)).joinToString(" • ")
                    if (sub.isNotEmpty()) {
                        Text(
                            sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Text(stateText(i), style = MaterialTheme.typography.bodySmall, color = stateColor)
                }
                IconButton(onClick = { Downloader.cancel(i.id) }) {
                    Icon(Icons.Default.Close, contentDescription = "حذف")
                }
            }

            when (i.st) {
                St.ANALYZING -> LinearProgressIndicator(Modifier.fillMaxWidth())

                St.ANALYZE_FAILED -> {
                    i.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    OutlinedButton(onClick = { Downloader.analyze(i.id) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("إعادة التحليل")
                    }
                }

                St.READY -> {
                    Text("الصيغة", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Fmt.entries.forEach { f ->
                            FilterChip(
                                selected = i.formatId == null && i.fmt == f,
                                onClick = { Downloader.setFmt(i.id, f) },
                                label = { Text(if (f.audio) "🎵 ${f.label}" else "🎬 ${f.label}") }
                            )
                        }
                    }
                    if (i.formatId == null && !i.fmt.audio) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Quality.entries.forEach { q ->
                                FilterChip(
                                    selected = i.quality == q,
                                    onClick = { Downloader.setQuality(i.id, q) },
                                    label = { Text(q.label) }
                                )
                            }
                        }
                    }
                    if (i.formats.isNotEmpty()) {
                        TextButton(onClick = { showAll = !showAll }) {
                            Text(if (showAll) "إخفاء الصيغ المكتشفة" else "كل الصيغ المكتشفة (${i.formats.size})")
                        }
                        if (showAll) {
                            Column {
                                i.formats.forEach { f ->
                                    FormatRow(f, selected = i.formatId == f.id) { Downloader.setFormatId(i.id, f.id) }
                                }
                            }
                        }
                    }
                    if (i.formatId != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("تم اختيار صيغة محددة", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = { Downloader.setFormatId(i.id, null) }) { Text("إلغاء الاختيار") }
                        }
                    }
                    Button(onClick = { Downloader.start(i.id) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("تحميل")
                    }
                }

                St.QUEUED, St.WAITING_NET -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { Downloader.pause(i.id) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("إيقاف مؤقت")
                    }
                }

                St.DOWNLOADING -> {
                    if (i.progress > 0f) LinearProgressIndicator(progress = { i.progress }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (i.line.isNotEmpty()) {
                        Text(
                            i.line, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    OutlinedButton(onClick = { Downloader.pause(i.id) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("إيقاف مؤقت")
                    }
                }

                St.PAUSED -> {
                    if (i.progress > 0f) LinearProgressIndicator(progress = { i.progress }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { Downloader.start(i.id) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("استكمال التحميل")
                    }
                }

                St.FAILED -> {
                    i.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    Button(onClick = { Downloader.start(i.id) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("إعادة المحاولة (استكمال)")
                    }
                }

                St.DONE -> {
                    Button(onClick = { openFile(ctx, i) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text("فتح الملف")
                    }
                }
            }
        }
    }
}
