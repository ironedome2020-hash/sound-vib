package com.soundlab

import kotlin.math.*

enum class Mode { FFT, WAVELET }

const val FS = 44100
const val N = 2048
const val NB = 48      // FFT display bands (log-spaced 40 Hz .. 16 kHz)
const val WL = 9       // wavelet levels

class Rec(val t: Long, val mode: Mode, val db: Float, val peak: Float, val v: FloatArray)

object Dsp {
    private val win = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / (N - 1))).toFloat() }
    private val edges = IntArray(NB + 1) { (40.0 * (16000.0 / 40.0).pow(it.toDouble() / NB) * N / FS).roundToInt() }
    private val h = floatArrayOf(0.4829629f, 0.8365163f, 0.2241439f, -0.1294095f) // Daubechies-4
    private val g = floatArrayOf(h[3], -h[2], h[1], -h[0])

    private fun lvl(db: Double) = ((db + 90) / 90).coerceIn(0.0, 1.0).toFloat()

    /** Returns (normalized band levels 0..1, peak frequency Hz). 0 = -90 dB, 1 = 0 dBFS. */
    fun fft(x: FloatArray): Pair<FloatArray, Float> {
        val re = FloatArray(N) { x[it] * win[it] }
        val im = FloatArray(N)
        var j = 0
        for (i in 1 until N) {
            var bit = N shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val t = re[i]; re[i] = re[j]; re[j] = t }
        }
        var len = 2
        while (len <= N) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            for (i in 0 until N step len) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val tr = (re[b] * cr - im[b] * ci).toFloat()
                    val ti = (re[b] * ci + im[b] * cr).toFloat()
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
            }
            len = len shl 1
        }
        val mag = FloatArray(N / 2) { 4f * hypot(re[it], im[it]) / N }
        var pk = 1
        for (k in 2 until N / 2) if (mag[k] > mag[pk]) pk = k
        val out = FloatArray(NB) { b ->
            val lo = edges[b]; val hi = max(edges[b + 1], lo + 1)
            var m = 0f
            for (k in lo until min(hi, N / 2)) m = max(m, mag[k])
            lvl(20 * log10(m + 1e-9))
        }
        return out to pk * FS.toFloat() / N
    }

    /** Multilevel DWT (db4). Index 0 = lowest-frequency level (9) ... index 8 = highest (level 1). */
    fun wavelet(x: FloatArray): Pair<FloatArray, Float> {
        var a = FloatArray(N) { x[it] * win[it] }
        var n = N
        val out = FloatArray(WL)
        for (lv in 0 until WL) {
            val half = n / 2
            val na = FloatArray(half)
            var e = 0.0
            for (i in 0 until half) {
                var s = 0f; var d = 0f
                for (k in 0..3) { val v = a[(2 * i + k) % n]; s += h[k] * v; d += g[k] * v }
                na[i] = s; e += d * d
            }
            out[WL - 1 - lv] = lvl(10 * log10(e / N + 1e-12))
            a = na; n = half
        }
        var best = 0
        for (i in 1 until WL) if (out[i] > out[best]) best = i
        val level = WL - best                       // 1 = highest band
        val hi = FS / 2.0.pow(level); val lo = hi / 2
        return out to sqrt(lo * hi).toFloat()
    }
}
