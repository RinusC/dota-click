package com.dotaidle.ancientwar.services

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Music loops + sound effects. Uses res/raw files if present (music_menu, sfx_click, ...),
 * otherwise synthesizes placeholder WAVs into the cache directory on first launch.
 */
class AudioService(private val context: Context) {
    enum class Sfx(val raw: String) { CLICK("sfx_click"), GOLD("sfx_gold"), KILL("sfx_kill"), CAST("sfx_cast"), BIG("sfx_big") }
    enum class Track(val raw: String) { MENU("music_menu"), BATTLE("music_battle") }

    var soundOn = true
    var musicOn = true
        set(value) {
            field = value
            refreshMusic()
        }

    private val pool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        ).build()
    private val sfxIds = HashMap<Sfx, Int>()
    private val lastPlayed = HashMap<Sfx, Long>()
    private val trackFiles = HashMap<Track, File>()
    @Volatile private var ready = false
    private var player: MediaPlayer? = null
    private var playing: Track? = null
    private var wanted: Track = Track.MENU
    private var resumed = false

    init {
        thread(name = "audio-prepare") {
            try {
                prepare()
                ready = true
                android.os.Handler(context.mainLooper).post { refreshMusic() }
            } catch (e: Exception) {
                ready = false // audio is optional; never crash the game because of it
            }
        }
    }

    fun play(sfx: Sfx, minIntervalMs: Long = 60) {
        if (!soundOn || !ready) return
        val now = SystemClock.elapsedRealtime()
        if (now - (lastPlayed[sfx] ?: 0L) < minIntervalMs) return
        lastPlayed[sfx] = now
        sfxIds[sfx]?.let { pool.play(it, 0.6f, 0.6f, 1, 0, 1f) }
    }

    fun setTrack(track: Track) {
        wanted = track
        refreshMusic()
    }

    fun onResume() {
        resumed = true
        refreshMusic()
    }

    fun onPause() {
        resumed = false
        stopMusic()
    }

    fun release() {
        stopMusic()
        pool.release()
    }

    private fun refreshMusic() {
        if (!ready || !musicOn || !resumed) {
            stopMusic()
            return
        }
        if (playing == wanted && player != null) return
        stopMusic()
        val mp = rawId(wanted.raw).takeIf { it != 0 }?.let { MediaPlayer.create(context, it) }
            ?: trackFiles[wanted]?.let { f -> MediaPlayer().apply { setDataSource(f.absolutePath); prepare() } }
            ?: return
        mp.isLooping = true
        mp.setVolume(0.35f, 0.35f)
        mp.start()
        player = mp
        playing = wanted
    }

    private fun stopMusic() {
        player?.run {
            try { stop() } catch (_: Exception) {}
            release()
        }
        player = null
        playing = null
    }

    private fun rawId(name: String): Int = context.resources.getIdentifier(name, "raw", context.packageName)

    private fun prepare() {
        val dir = File(context.cacheDir, "audio").apply { mkdirs() }
        fun file(name: String, gen: () -> FloatArray): File {
            val f = File(dir, "$name.wav")
            if (!f.exists() || f.length() < 100) writeWav(f, gen())
            return f
        }
        for (s in Sfx.entries) {
            val raw = rawId(s.raw)
            sfxIds[s] = if (raw != 0) pool.load(context, raw, 1) else pool.load(file(s.raw) { Synth.sfx(s) }.absolutePath, 1)
        }
        for (t in Track.entries) {
            if (rawId(t.raw) == 0) trackFiles[t] = file(t.raw) { Synth.music(t) }
        }
    }

    private fun writeWav(file: File, samples: FloatArray) {
        val rate = Synth.RATE
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in samples) data.putShort((v.coerceIn(-1f, 1f) * 32000).toInt().toShort())
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples.size * 2); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2)
            putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples.size * 2)
        }
        FileOutputStream(file).use { it.write(header.array()); it.write(data.array()) }
    }
}

/** Tiny procedural synthesizer for placeholder audio (no external assets needed). */
private object Synth {
    const val RATE = 22050

    private fun note(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)

    private fun add(buf: FloatArray, start: Double, dur: Double, freq: Double, vol: Double, square: Boolean = false, slide: Double = 0.0) {
        val s0 = (start * RATE).toInt()
        val n = (dur * RATE).toInt()
        var phase = 0.0
        for (i in 0 until n) {
            val idx = s0 + i
            if (idx >= buf.size) break
            val t = i.toDouble() / RATE
            val f = freq + slide * t
            phase += 2 * PI * f / RATE
            val env = minOf(1.0, t * 200) * exp(-3.0 * t / dur)
            val wave = if (square) (if (sin(phase) >= 0) 0.6 else -0.6) else sin(phase)
            buf[idx] += (wave * vol * env).toFloat()
        }
    }

    fun sfx(s: AudioService.Sfx): FloatArray = when (s) {
        AudioService.Sfx.CLICK -> FloatArray(RATE / 20).also { add(it, 0.0, 0.05, 1200.0, 0.5) }
        AudioService.Sfx.GOLD -> FloatArray(RATE / 6).also { add(it, 0.0, 0.07, 1320.0, 0.35); add(it, 0.06, 0.1, 1760.0, 0.35) }
        AudioService.Sfx.KILL -> FloatArray(RATE / 5).also { buf ->
            val r = Random(1)
            for (i in buf.indices) buf[i] += ((r.nextFloat() * 2 - 1) * 0.4 * exp(-i / (RATE * 0.04))).toFloat()
            add(buf, 0.0, 0.18, 110.0, 0.6, slide = -200.0)
        }
        AudioService.Sfx.CAST -> FloatArray(RATE / 4).also { add(it, 0.0, 0.25, 400.0, 0.4, square = true, slide = 2400.0) }
        AudioService.Sfx.BIG -> FloatArray(RATE).also { b -> listOf(57, 60, 64, 69).forEach { m -> add(b, 0.0, 1.0, note(m), 0.22) } }
    }

    /** 8-bar loops in A minor: calm arpeggio for menus, driving bass + lead for battle. */
    fun music(t: AudioService.Track): FloatArray {
        val bpm = if (t == AudioService.Track.BATTLE) 132.0 else 84.0
        val beat = 60.0 / bpm
        val bars = 8
        val buf = FloatArray((bars * 4 * beat * RATE).toInt())
        val chords = listOf(listOf(57, 60, 64), listOf(53, 57, 60), listOf(55, 59, 62), listOf(52, 56, 59))
        for (bar in 0 until bars) {
            val chord = chords[bar % 4]
            val barStart = bar * 4 * beat
            if (t == AudioService.Track.MENU) {
                for (step in 0 until 8) {
                    val m = chord[step % 3] + if (step >= 4) 12 else 0
                    add(buf, barStart + step * beat / 2, beat * 1.2, note(m), 0.12)
                }
                add(buf, barStart, beat * 4, note(chord[0] - 12), 0.15)
            } else {
                for (step in 0 until 8) add(buf, barStart + step * beat / 2, beat / 2.2, note(chord[0] - 24), 0.22, square = true)
                for (step in 0 until 4) {
                    val m = chord[(step + bar) % 3] + 12
                    add(buf, barStart + step * beat, beat * 0.8, note(m), 0.1, square = true)
                }
                for (step in 0 until 4) { // kick-ish thump
                    add(buf, barStart + step * beat, 0.12, 90.0, 0.35, slide = -300.0)
                }
            }
        }
        return buf
    }
}
