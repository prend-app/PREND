package app.prend.voice

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import app.prend.Prend
import java.util.Locale

/**
 * 시험 9: 버튼을 누른 뒤나 PREND가 물은 뒤 몇 초의 짧은 한국어를 Pleos STT로 알아듣는가.
 * 기기 안 모드(ON_DEVICE)는 인터넷과 앱 등록 없이 돈다. 알아들은 글자는 기록에 길이만 남긴다.
 */
class SttProbe(context: Context, mode: Mode = Mode.ON_DEVICE) {

    private val stt = SpeechToText(context.applicationContext, mode)
    private var ready = false

    private val result = object : ResultListener {
        override fun onReady() = Prend.log("· STT: 준비")
        override fun onStartedRecognition() = Prend.log("· STT: 듣기 시작")
        override fun onUpdated(stt: String, completed: Boolean) {
            if (completed) Prend.log("◆ STT: 알아들음 ${stt.length}자")
        }
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onEndedRecognition() = Prend.log("· STT: 듣기 끝")
        override fun onError(errorType: ErrorType) = Prend.log("✗ STT 오류 $errorType")
    }

    fun init() {
        stt.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                ready = true
                stt.setLocale(Locale.KOREA)
                stt.addListener(result)
                Prend.log("◆ STT: 초기화 성공(한국어)")
            }
            override fun onFailure(errorType: ErrorType) = Prend.log("✗ STT 초기화 실패 $errorType")
        })
    }

    fun listen() {
        if (!ready) { Prend.log("✗ STT: 초기화 전"); return }
        runCatching { stt.request() }.onFailure { Prend.log("✗ STT 요청 실패 $it") }
    }

    fun stop() { runCatching { stt.stop() } }

    /** 마이크 대신 녹음 파일(16kHz 모노 16비트 PCM)을 보내 알아듣는지 본다. 사람 목소리 없이 시험하기 위함 */
    fun feed(pcm: ByteArray) {
        if (!ready) { Prend.log("✗ STT: 초기화 전"); return }
        Prend.log("· STT: 파일 보냄 ${pcm.size}바이트")
        runCatching { stt.sendAudio(pcm); stt.completeAudioSend() }.onFailure { Prend.log("✗ STT 파일 보내기 실패 $it") }
    }

    fun release() {
        runCatching { stt.removeListener(result); stt.release() }
        ready = false
    }
}
