package com.example.videodl

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
            i.getStringExtra(Intent.EXTRA_TEXT)?.let { vm.setUrl(it, auto = true) }
        }
    }
}

private fun openFile(ctx: Context, d: Done) {
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(d.uri, d.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
        Toast.makeText(ctx, "لا يوجد تطبيق لفتح الملف", Toast.LENGTH_SHORT).show()
    }
}

private fun fmtDuration(sec: Int): String {
    if (sec <= 0) return ""
    val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Screen(vm: MainViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column {
                Text("محمّل الفيديو", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "الصق الرابط وحمّل من أي موقع تواصل",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }

            OutlinedTextField(
                value = s.url,
                onValueChange = { vm.setUrl(it, false) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("رابط الفيديو") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                trailingIcon = {
                    if (s.url.isEmpty()) {
                        IconButton(onClick = { clip.getText()?.text?.let { vm.setUrl(it, true) } }) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "لصق")
                        }
                    } else {
                        IconButton(onClick = { vm.clear() }) {
                            Icon(Icons.Default.Close, contentDescription = "مسح")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { vm.analyze() })
            )

            if (s.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("جاري تحليل الرابط…", style = MaterialTheme.typography.bodySmall)
            }

            s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            s.info?.let { info ->
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            model = info.thumb,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(112.dp)
                                .height(72.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(info.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            val sub = listOfNotNull(info.uploader, fmtDuration(info.duration).ifEmpty { null }).joinToString(" • ")
                            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Text("الصيغة", fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Fmt.entries.forEach { f ->
                    FilterChip(
                        selected = s.fmt == f,
                        onClick = { vm.setFmt(f) },
                        label = { Text(if (f.audio) "🎵 ${f.label}" else "🎬 ${f.label}") }
                    )
                }
            }

            if (!s.fmt.audio) {
                Text("الجودة", fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Quality.entries.forEach { q ->
                        FilterChip(selected = s.quality == q, onClick = { vm.setQuality(q) }, label = { Text(q.label) })
                    }
                }
            }

            if (s.downloading) {
                if (s.progress > 0f) LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                if (s.progress > 0f) Text("${(s.progress * 100).toInt()}%", fontWeight = FontWeight.Bold)
                if (s.status.isNotEmpty()) {
                    Text(s.status, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = { vm.cancel() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("إلغاء")
                }
            } else {
                Button(
                    onClick = { vm.download() },
                    enabled = vm.isUrl(s.url),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("تحميل", style = MaterialTheme.typography.titleMedium)
                }
            }

            if (s.history.isNotEmpty()) {
                Text("تم تحميله", fontWeight = FontWeight.SemiBold)
                s.history.forEach { d ->
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(d.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(d.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                            }
                            TextButton(onClick = { openFile(ctx, d) }) { Text("فتح") }
                        }
                    }
                }
                Text(
                    "الملفات في: التنزيلات ‹ VideoDownloader",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            TextButton(onClick = { vm.updateEngine() }, enabled = !s.updatingEngine) {
                Text("تحديث المحرك (إذا فشل موقع معيّن)")
            }
            s.engineMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
