package app.prend.sound

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import app.prend.Prend
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * 심폐소생술 가슴압박 박자 소리(외부 검토 11/10): 1분에 110번 짧은 '똑'. 가이드라인의 100~120번 한가운데.
 * 두 손으로 누르는 동안 화면을 보지 않아도 빠르기를 맞추게 한다. 한 박(약 0.55초) 길이의 소리를 미리 만들어 되풀이(정적 트랙의 되감기 구간)하므로
 * 박이 밀리지 않는다. 응급처치 화면이 켜고 끈다(ui/Scenes AidFront)
 */
object Beat {
    private const val RATE = 44_100
    const val BPM = 110

    private var track: AudioTrack? = null
    /** 켠 곳의 수(장면이 섞이는 0.45초 동안 옛 화면과 새 화면이 함께 있을 수 있다): 마지막이 끌 때 멈춘다 */
    private var users = 0

    /** 한 박: 맨 앞에 18ms '똑'(1.6kHz, 빨리 사라지는 소리), 나머지는 조용히 */
    private val pcm: ShortArray by lazy {
        val frames = RATE * 60 / BPM
        val out = ShortArray(frames)
        val n = RATE * 18 / 1000
        for (i in 0 until n) {
            val t = i.toDouble() / RATE
            val env = (1 - exp(-t * 4000)) * exp(-t * 260)
            out[i] = (.55 * env * (sin(2 * PI * 1600 * t) + .3 * sin(2 * PI * 3200 * t)) * Short.MAX_VALUE).coerceIn(-32767.0, 32767.0).toInt().toShort()
        }
        out
    }

    val on get() = track != null

    fun start() {
        users++
        if (track != null) return
        val data = pcm
        val t = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(data.size * 2)
                .build().also {
                    it.write(data, 0, data.size)
                    // 되풀이를 걸지 못하면 '똑' 한 번만 나고 멈춘다: 켠 것으로 보지 않는다
                    if (it.setLoopPoints(0, data.size, -1) != AudioTrack.SUCCESS) { it.release(); error("loop") }
                    it.play()
                }
        }.getOrNull() ?: run { Prend.log("✗ 박자 소리를 낼 수 없음"); return }
        track = t
        Prend.log("· 박자 소리 켬(1분에 ${BPM}번)")
    }

    fun stop() {
        if (users > 0) users--
        if (users > 0) return
        val t = track ?: return
        track = null
        runCatching { t.stop() }
        runCatching { t.release() }
        Prend.log("· 박자 소리 끔")
    }
}
