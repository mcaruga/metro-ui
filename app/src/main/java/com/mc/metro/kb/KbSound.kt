package com.mc.metro.kb

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Short synthesized "tock" sounds, one per key family, in the spirit of the WP keyboard. */
object KbSound {
    const val KEY = 0
    const val DEL = 1
    const val SPACE = 2

    private var pool: SoundPool? = null
    private val ids = IntArray(3)
    private var audio: AudioManager? = null

    fun init(c: Context) {
        if (pool != null) return
        audio = c.getSystemService(AudioManager::class.java)
        val p = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        ids[KEY] = p.load(wav(c, "kb_key", 2100f, 0.024f), 1)
        ids[DEL] = p.load(wav(c, "kb_del", 1350f, 0.028f), 1)
        ids[SPACE] = p.load(wav(c, "kb_space", 950f, 0.032f), 1)
        pool = p
    }

    fun play(kind: Int) {
        val a = audio
        if (a != null && a.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        pool?.play(ids[kind], 0.55f, 0.55f, 1, 0, 1f)
    }

    private fun wav(c: Context, name: String, freq: Float, dur: Float): String {
        val f = File(c.cacheDir, "$name.wav")
        if (f.exists()) return f.absolutePath
        val rate = 44100
        val n = (rate * dur).toInt()
        val rnd = Random(7)
        val pcm = ShortArray(n) { i ->
            val t = i.toFloat() / rate
            val env = exp(-t / (dur / 5f))
            val click = if (i < 40) (1f - i / 40f) * (rnd.nextFloat() * 2 - 1) else 0f
            val v = (sin(2 * PI * freq * t).toFloat() * 0.7f + click * 0.5f) * env
            (v * 0.6f * Short.MAX_VALUE).toInt().toShort()
        }
        DataOutputStream(FileOutputStream(f)).use { o ->
            fun i32(v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF); o.write((v shr 16) and 0xFF); o.write((v shr 24) and 0xFF) }
            fun i16(v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF) }
            o.writeBytes("RIFF"); i32(36 + n * 2); o.writeBytes("WAVE")
            o.writeBytes("fmt "); i32(16); i16(1); i16(1); i32(rate); i32(rate * 2); i16(2); i16(16)
            o.writeBytes("data"); i32(n * 2)
            for (s in pcm) i16(s.toInt())
        }
        return f.absolutePath
    }
}
