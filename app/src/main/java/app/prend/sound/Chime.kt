package app.prend.sound

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * [프렌드]를 부르면 나는 짧은 두 음(라5 → 미6). 삑 소리 대신 맑게 사라지는 종소리 결.
 * 다른 앱 음악 위에 한마디를 얹기 직전에는 거꾸로 내려오는 두 음(미6 → 라5, 0.5초)을 낸다(alert, 별첨 4 §5 ①):
 * 말이 끝나고 대답을 들을 때의 차임과 소리를 나눠, 앞뒤 두 소리가 같은 신호로 겹쳐 들리지 않게.
 * 미리 한 번 합성해 두고 재생만 한다.
 */
object Chime {
    private const val RATE = 44_100

    /** seconds 길이에 (음높이 Hz, 시작 초, 크기) 음들을 종소리 결로 합성한다 */
    private fun synth(seconds: Double, vararg notes: Triple<Double, Double, Double>): ShortArray {
        val n = (RATE * seconds).toInt()
        val out = ShortArray(n)
        for ((f, start, amp) in notes) {
            val s0 = (start * RATE).toInt()
            for (i in s0 until n) {
                val t = (i - s0).toDouble() / RATE
                val env = (1 - exp(-t * 900)) * exp(-t * 7.5)
                val v = amp * env * (sin(2 * PI * f * t) + .18 * sin(2 * PI * f * 2 * t) + .06 * sin(2 * PI * f * 3 * t))
                out[i] = (out[i] + v * Short.MAX_VALUE).coerceIn(-32767.0, 32767.0).toInt().toShort()
            }
        }
        return out
    }

    private val pcm: ShortArray by lazy { synth(0.62, Triple(880.0, 0.0, .16), Triple(1318.5, 0.085, .14)) }
    /** 음악 위 한마디 앞 알림음: 내려오는 두 음, 0.5초(대답 차임은 올라가는 두 음) */
    private val alertPcm: ShortArray by lazy { synth(0.5, Triple(1318.5, 0.0, .15), Triple(880.0, 0.11, .15)) }

    /** 두 소리를 미리 합성해 둔다(운행 서비스가 배경에서 부른다). 처음 [프렌드]를 누를 때 메인에서 5만 번 계산하지 않게(11/04 점검) */
    fun warm() { pcm; alertPcm }

    /**
     * 트랙은 한 번 만들어 두고 되감아 다시 튼다. 부를 때마다 만들면 [프렌드]를 누른 순간(고리가 민트로 터질 때)
     * 메인 스레드가 오디오 서버에 트랙을 만드는 동안 기다린다
     */
    private var track: AudioTrack? = null
    private var alertTrack: AudioTrack? = null

    /** soft면 약 16dB 작게: 말하기 전 기척(별첨 2 §2-2 ①, 무드등을 올릴 수 없을 때). 같은 트랙의 음량만 바꾼다 */
    fun play(soft: Boolean = false) {
        track = replay(track, pcm, if (soft) SOFT else 1f)
    }

    /** 다른 앱 음악 위에 한마디를 얹기 직전의 알림음(0.5초). 음악에 묻히지 않게 기척 차임처럼 줄이지 않는다 */
    fun alert() {
        alertTrack = replay(alertTrack, alertPcm, 1f)
    }

    /** 만들어 둔 트랙을 되감아 틀고, 처음이거나 다시 쓸 수 없게 됐으면(오디오 서버가 다시 뜸 등) 새로 만든다 */
    private fun replay(t: AudioTrack?, data: ShortArray, gain: Float): AudioTrack {
        val again = t != null && t.state == AudioTrack.STATE_INITIALIZED && runCatching {
            t.stop()
            // 끝 표시는 한 번 닿으면 다시 알리지 않아 되감을 때마다 다시 단다
            t.reloadStaticData() == AudioTrack.SUCCESS && run { t.setNotificationMarkerPosition(data.size - 1); t.setVolume(gain); t.play(); true }
        }.getOrDefault(false)
        if (again) return t!!
        t?.let { runCatching { it.release() } }
        return build(data).also { it.setVolume(gain); it.play() }
    }

    /** 기척 차임의 음량(약 −16dB) */
    private const val SOFT = .16f

    private fun build(data: ShortArray): AudioTrack {
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(data.size * 2)
            .build()
        track.write(data, 0, data.size)
        // 다 울리면 멈춰 둔다: 정적 트랙은 끝까지 내도 '재생 중'으로 남아, 차가 보는 활성 재생 목록에 PREND 효과음이 계속 잡힌다.
        // 그사이 다시 틀었으면(위치가 처음 쪽) 멈추지 않는다
        track.setNotificationMarkerPosition(data.size - 1)
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(a: AudioTrack) { runCatching { if (a.playbackHeadPosition >= data.size - 1) a.stop() } }
            override fun onPeriodicNotification(a: AudioTrack) = Unit
        })
        return track
    }
}
