package app.prend.voice

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.prend.Prend
import java.util.Locale

/**
 * [프렌드]를 부른 뒤 몇 초만 듣는 귀. Pleos STT 기기 안 모드(시험 9·9-2에서 확인).
 * 알아들은 글자는 화면에 잠깐 보이고 할 일을 고르는 데만 쓰며, 기록에는 남기지 않는다.
 */
class Ears(context: Context) {

    private val stt = SpeechToText(context.applicationContext, Mode.ON_DEVICE)
    private val main = Handler(Looper.getMainLooper())
    private var ready = false

    var onText: (text: String, completed: Boolean) -> Unit = { _, _ -> }
    var onLevel: (Float) -> Unit = {}
    var onEnd: () -> Unit = {}

    /**
     * stop()으로 멈춘 때(그 뒤 새 듣기가 시작되면 0). 멈춘 앞 듣기의 늦은 끝·글·오류 알림이 바로 이어 연 새 듣기를 빈 채로 닫거나
     * 앞 대답 글을 새 말로 넘기지 않게, 멈춘 뒤 새 듣기 시작 알림 전까지 온 것은 버린다(11/08 오류 점검 A10: [긴급]이 앞 물음의 듣기를 멈추고 바로 새로 듣는다).
     * 시작 알림이 오지 않아도 버리는 것은 멈춘 뒤 1.5초까지만이다
     */
    @Volatile private var stoppedAt = 0L

    private fun stale() = stoppedAt != 0L && SystemClock.elapsedRealtime() - stoppedAt < STALE_MS

    // 앞 듣기의 것인지는 알림이 온 그때 가른다: main.post 안에서 가르면 그사이 온 새 듣기 시작 알림이 stoppedAt을 0으로 돌려, 앞 듣기의 끝이 새 듣기를 닫았다(11/08 오류 점검 A10 검토)
    private val result = object : ResultListener {
        override fun onReady() = Unit
        override fun onStartedRecognition() { stoppedAt = 0L; Prend.log("· 귀: 듣기 시작") }
        override fun onUpdated(stt: String, completed: Boolean) { val old = stale(); main.post { if (!old) onText(stt, completed) } }
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) { main.post { onLevel((rms / 10.0).toFloat().coerceIn(0f, 1f)) } }
        override fun onEndedRecognition() { val old = stale(); main.post { if (old) Prend.log("· 귀: 멈춘 앞 듣기의 끝 알림 버림") else onEnd() } }
        override fun onError(errorType: ErrorType) { Prend.log("✗ 귀 오류 $errorType"); val old = stale(); main.post { if (!old) onEnd() } }
    }

    fun init() {
        stt.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                ready = true
                stt.setLocale(Locale.KOREA)
                stt.addListener(result)
                Prend.log("◆ 귀: 준비(한국어, 기기 안)")
            }
            override fun onFailure(errorType: ErrorType) = Prend.log("✗ 귀 초기화 실패 $errorType")
        })
    }

    fun listen() {
        if (!ready) { Prend.log("✗ 귀: 초기화 전"); main.post { onEnd() }; return }
        runCatching { stt.request() }.onFailure { Prend.log("✗ 귀 요청 실패 $it"); main.post { onEnd() } }
    }

    fun stop() { stoppedAt = SystemClock.elapsedRealtime(); runCatching { stt.stop() } }

    private companion object { const val STALE_MS = 1_500L }

    fun release() {
        runCatching { stt.removeListener(result); stt.release() }
        ready = false
    }
}
