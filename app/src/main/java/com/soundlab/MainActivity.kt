package com.soundlab

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas as ACanvas
import android.graphics.Color as AColor
import android.graphics.Paint as APaint
import android.graphics.Rect
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.log10
import kotlin.math.max

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { App() }
    }
}

fun heat(v: Float): Int = AColor.HSVToColor(floatArrayOf(250f * (1f - v), 1f, 0.25f + 0.75f * v))

class Engine(private val scope: CoroutineScope) {
    var running by mutableStateOf(false)
    var mode by mutableStateOf(Mode.FFT)
    var live by mutableStateOf(FloatArray(NB))
    var db by mutableStateOf(-90f)
    var peak by mutableStateOf(0f)
    val history = mutableStateListOf<Rec>()
    private var job: Job? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        job = scope.launch(Dispatchers.Default) {
            val hop = 1024
            val minBuf = AudioRecord.getMinBufferSize(FS, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(MediaRecorder.AudioSource.MIC, FS, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minBuf, hop * 8))
            val buf = ShortArray(hop)
            val win = FloatArray(N)
            var frame = 0
            try {
                rec.startRecording()
                while (isActive) {
                    val r = rec.read(buf, 0, hop)
                    if (r <= 0) continue
                    System.arraycopy(win, hop, win, 0, N - hop)
                    for (i in 0 until hop) win[N - hop + i] = if (i < r) buf[i] / 32768f else 0f
                    val m = mode
                    val (v, pk) = if (m == Mode.FFT) Dsp.fft(win) else Dsp.wavelet(win)
                    var s = 0.0
                    for (i in N - hop until N) s += win[i] * win[i]
                    val d = (10 * log10(s / hop + 1e-12)).toFloat()
                    frame++
                    withContext(Dispatchers.Main) {
                        live = v; db = d; peak = pk
                        if (frame % 5 == 0) {
                            history.add(Rec(System.currentTimeMillis(), m, d, pk, v))
                            if (history.size > 3000) history.removeAt(0)
                        }
                    }
                }
            } finally {
                runCatching { rec.stop() }; rec.release()
            }
        }
    }

    fun stop() { job?.cancel(); running = false }
}

fun spectro(h: List<Rec>): Bitmap {
    val w = max(h.size, 1)
    val px = IntArray(w * NB) { AColor.BLACK }
    h.forEachIndexed { x, r ->
        for (y in 0 until NB) px[(NB - 1 - y) * w + x] = heat(r.v[y * r.v.size / NB])
    }
    return Bitmap.createBitmap(px, w, NB, Bitmap.Config.ARGB_8888)
}

fun exportImage(h: List<Rec>): Bitmap {
    val sp = spectro(h)
    val w = max(h.size * 4, 600); val ih = 480
    val out = Bitmap.createBitmap(w, ih + 60, Bitmap.Config.ARGB_8888)
    val c = ACanvas(out)
    c.drawColor(0xFF0E1116.toInt())
    val p = APaint(APaint.ANTI_ALIAS_FLAG).apply { color = AColor.WHITE; textSize = 26f }
    val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    c.drawText("SoundLab spectrogram  ${f.format(Date(h.first().t))} - ${f.format(Date(h.last().t))}", 16f, 38f, p)
    c.drawBitmap(sp, Rect(0, 0, sp.width, sp.height), Rect(0, 60, w, 60 + ih), null)
    return out
}

fun save(c: Context, name: String, mime: String, dir: String, col: Uri, write: (OutputStream) -> Unit): Boolean =
    runCatching {
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
        }
        val uri = c.contentResolver.insert(col, v)!!
        c.contentResolver.openOutputStream(uri)!!.use(write)
    }.isSuccess

fun saveCsv(c: Context, h: List<Rec>): Boolean {
    val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    val sb = StringBuilder("time,mode,level_dbfs,peak_hz," + (0 until NB).joinToString(",") { "b$it" } + "\n")
    for (r in h) {
        sb.append(f.format(Date(r.t))).append(',').append(r.mode).append(',')
            .append("%.1f".format(Locale.US, r.db)).append(',').append("%.0f".format(Locale.US, r.peak))
        for (i in 0 until NB) { sb.append(','); if (i < r.v.size) sb.append("%.1f".format(Locale.US, r.v[i] * 90 - 90)) }
        sb.append('\n')
    }
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    return save(c, "soundlab_$stamp.csv", "text/csv", "Download/SoundLab", MediaStore.Downloads.EXTERNAL_CONTENT_URI) {
        it.write(sb.toString().toByteArray())
    }
}

fun saveImage(c: Context, h: List<Rec>): Boolean {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    return save(c, "soundlab_$stamp.png", "image/png", "Pictures/SoundLab", MediaStore.Images.Media.EXTERNAL_CONTENT_URI) {
        exportImage(h).compress(Bitmap.CompressFormat.PNG, 100, it)
    }
}

@Composable
fun Stat(label: String, value: String, mod: Modifier) {
    Card(mod, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp)) {
            Text(label, fontSize = 12.sp, color = Color(0xFF8A94A6))
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val e = remember { Engine(scope) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) e.start() else Toast.makeText(ctx, "Microphone permission is required", Toast.LENGTH_LONG).show()
    }
    DisposableEffect(Unit) { onDispose { e.stop() } }
    val toast = { ok: Boolean, what: String ->
        Toast.makeText(ctx, if (ok) "Saved $what" else "Could not save $what", Toast.LENGTH_LONG).show()
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFF7CC4FF), background = Color(0xFF0E1116), surface = Color(0xFF171B22),
        onBackground = Color(0xFFE6EAF2), onSurface = Color(0xFFE6EAF2))) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("SoundLab", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(e.mode == Mode.FFT, { e.mode = Mode.FFT }, { Text("FFT") })
                FilterChip(e.mode == Mode.WAVELET, { e.mode = Mode.WAVELET }, { Text("Wavelet (db4)") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("LEVEL", "%.1f dBFS".format(e.db), Modifier.weight(1f))
                Stat("PEAK", "%.0f Hz".format(e.peak), Modifier.weight(1f))
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Live", fontSize = 12.sp, color = Color(0xFF8A94A6))
                    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
                        val n = e.live.size; val bw = size.width / n
                        e.live.forEachIndexed { i, v ->
                            val hh = v * size.height
                            drawRect(Color(heat(v)), Offset(i * bw + 1f, size.height - hh), Size(bw - 2f, hh))
                        }
                    }
                    Text(if (e.mode == Mode.FFT) "40 Hz  ···  log scale  ···  16 kHz"
                         else "level 9 (low)  ···  level 1 (high)", fontSize = 11.sp, color = Color(0xFF8A94A6))
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(12.dp)) {
                    Text("History · ${e.history.size} records", fontSize = 12.sp, color = Color(0xFF8A94A6))
                    val last = e.history.lastOrNull()?.t
                    val bmp = remember(last, e.history.size) { spectro(e.history.takeLast(400)).asImageBitmap() }
                    Image(bmp, "History spectrogram", Modifier.fillMaxWidth().height(160.dp),
                        contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.None)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button({
                    if (e.running) e.stop()
                    else if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) e.start()
                    else perm.launch(Manifest.permission.RECORD_AUDIO)
                }, Modifier.weight(1f)) { Text(if (e.running) "Stop" else "Start") }
                OutlinedButton({ e.history.clear() }, Modifier.weight(1f)) { Text("Clear") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton({ toast(saveCsv(ctx, e.history.toList()), "CSV to Downloads/SoundLab") },
                    Modifier.weight(1f), enabled = e.history.isNotEmpty()) { Text("Save CSV") }
                FilledTonalButton({ toast(saveImage(ctx, e.history.toList()), "image to Pictures/SoundLab") },
                    Modifier.weight(1f), enabled = e.history.isNotEmpty()) { Text("Save image") }
            }
        }
    }
}
