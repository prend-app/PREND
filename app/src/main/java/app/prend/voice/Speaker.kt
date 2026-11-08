package app.prend.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.prend.Bridge
import app.prend.Prend

/**
 * 차의 한마디를 튼다. 다른 앱의 음악이 흐를 때도 말할 수 있도록 잠깐(transient) 포커스를 덕킹 허용으로 청한다.
 * Pleos 오디오 포커스 가이드: 덕킹은 포커스를 잃은 앱이 스스로 한다("Apply volume attenuation").
 * usage는 시험으로 고른다(시험 49): 어떤 용도로 청해야 다른 음악이 멈추지 않고 낮아지는가.
 * answerMs > 0이면 말이 끝난 뒤 그만큼 짧은 포커스(GAIN_TRANSIENT)를 잡아 대답을 듣는 동안 다른 음악을 멈췄다가 돌려준다(시험 49-2).
 * PREND 곡은 말하는 동안 소리 엔진이 직접 −8dB 낮춘다(덕킹을 차에 맡기면 에뮬레이터처럼 꺼진 차에서 곡이 말을 덮는다).
 * 말하는 동안 포커스를 잃으면(감사 F112): 통화·다른 음성 비서처럼 잠깐 가져가면 끊어서 부른 쪽이 줄에 되돌리게 하고(cut),
 * 길 안내처럼 낮춰 달라고 하면 이 말의 크기를 낮췄다가 돌려받으면 되돌린다.
 */
class Speaker(private val context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    /** 다 쓴 플레이어를 놓는 곳(하나씩 차례로) */
    private val releaser = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "prend-voice-release") }
    private var request: AudioFocusRequest? = null

    /** 말하는 동안 길 안내가 낮춰 달라고 해 이 말을 낮춘 중인가(그러면 길 안내 음성이 나오는 것으로 보고 먼저 꺼낸 말을 멈춘다, ride/Timing) */
    val navDucked get() = player != null && ducked

    /** 말하는 중인가(대답을 기다리는 몇 초는 빼고) */
    val speaking get() = player != null

    /** 지금 나가는 말의 이름(말하지 않으면 null). 주차 코치가 차가 구르면 제 말만 멈추려고 본다(ride/Coach) */
    val saying get() = if (player != null) sayingLabel else null
    private var sayingLabel: String? = null

    /** 통화 중(전화·블루링크 상담원·벨 울림). 이때 한마디는 포커스를 받지 못하고, 뒤따르는 대답 듣기는 통화 중에 마이크를 연다 */
    val inCall get() = audio.mode in CALL_MODES

    /** 지금 나가는 말이 다른 말에 끊기면 부를 것(Care가 묻던 말을 줄에 되돌리려고). 스스로 stop()하면 부르지 않는다 */
    private var onCut: (() -> Unit)? = null

    /**
     * onDone은 말이 끝까지 나간 뒤에만 부른다(중간에 stop()하면 부르지 않는다). 대답 듣기는 그때 시작한다.
     * onCut은 이 말이 끝나기 전에 다른 speak()나 cut()에 끊겼을 때 부른다.
     * interrupt가 false면(PREND가 먼저 꺼내는 한마디) 다른 말이 나오는 중엔 끊지 않고 false를 돌려준다(11/01 말 차례)
     */
    /** 천천히 말하기(ride/SlowTalk): 모든 말을 음높이 그대로 0.9배 빠르기로 */
    @Volatile var slow = false
    /** 마지막으로 낸 말("뭐라고?"면 이것을 다시 한다)과 끝난 때 */
    private data class Last(val asset: String, val usage: Int, val label: String)
    private var last: Last? = null
    private var lastEnd = 0L

    /** 방금(within 안에) 한 말이 있나 */
    fun spokeWithin(ms: Long) = last != null && (player != null || SystemClock.elapsedRealtime() - lastEnd < ms)

    /** "뭐라고?": 방금 한 말을 음높이 그대로 0.85배 빠르기로 한 번 더 한다. 대답 창 안이었으면 answerMs만큼 다른 음악을 멈춘 채 듣는다. 할 말이 없으면 false */
    fun replay(answerMs: Long = 0L, onDone: () -> Unit = {}): Boolean {
        val l = last ?: return false
        return speak(l.asset, l.usage, "${l.label} 다시", answerMs = answerMs, rate = REPLAY_RATE, onDone = onDone)
    }

    /** remember가 false면 "뭐라고?"에 다시 할 말로 남기지 않는다(못 알아들어 다시 묻는 말: 되물으면 그 앞의 물음을 다시 한다) */
    fun speak(asset: String, usage: Int, label: String, answerMs: Long = 0L, onCut: (() -> Unit)? = null, interrupt: Boolean = true, rate: Float = 0f, remember: Boolean = true, onDone: () -> Unit = {}): Boolean {
        if (!interrupt && player != null) { Prend.log("· 한마디($label): 다른 말이 나오는 중이라 끊지 않음"); return false }
        cut()
        // 목소리 파일을 먼저 연다: 아직 만들지 않은 대사면 포커스를 청하기 전에 던진다(다른 음악을 멈추거나 낮춘 채 두지 않게)
        val afd = openVoice(context, asset)
        this.onCut = onCut
        val attrs = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val t0 = SystemClock.elapsedRealtime()
        lateinit var req: AudioFocusRequest
        req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            // 지난 말의 늦은 알림은 버린다(지금 청한 포커스의 변화만 본다)
            .setOnAudioFocusChangeListener({ if (request === req) onFocus(it, label) }, main)
            .build()
        request = req
        val result = audio.requestAudioFocus(req)
        Prend.log("◆ 한마디($label) 포커스 요청 → ${focusName(result)}")
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { this.onCut = null; request = null; afd.close(); return false }
        prepared = false
        ducked = false
        sayingLabel = label
        if (rate == 0f && remember) last = Last(asset, usage, label)
        val speed = if (rate > 0f) rate else if (slow) SLOW_RATE else 1f
        // 플레이어를 못 만들면(파일·해독기 문제) 포커스와 파일을 놓고 말하지 않은 것으로 돌린다(전에는 포커스가 남을 수 있었다, 11/04 점검)
        val mp = MediaPlayer()
        val ok = runCatching { mp.setAudioAttributes(attrs); mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length) }
            .onFailure { Prend.log("✗ 한마디($label) 플레이어를 만들지 못함 $it") }.isSuccess
        runCatching { afd.close() }
        if (!ok) { runCatching { mp.release() }; stop(); return false }
        player = mp.apply {
            setOnCompletionListener {
                Prend.log("· 한마디($label) 끝 ${SystemClock.elapsedRealtime() - t0}ms")
                this@Speaker.onCut = null
                lastEnd = SystemClock.elapsedRealtime()
                this@Speaker.stop() // apply 안이라 그냥 stop()은 MediaPlayer.stop()이 된다(포커스를 안 놓던 버그)
                if (answerMs > 0) listen(attrs, label, answerMs)
                onDone()
            }
            // 준비(파일 열기·해독기 준비)는 메인 밖에서 하고, 다 되면 바로 튼다. 카드를 띄운 첫 프레임이 준비만큼 늦지 않게.
            // 준비 중에 끊기면(stop의 release) 이 알림은 오지 않는다. 준비 중에 길 안내가 낮춰 달라고 했으면 낮춘 크기로 시작한다
            setOnPreparedListener {
                prepared = true
                if (ducked) it.setVolume(DUCK, DUCK)
                // 천천히 말하기·다시 말하기: 음높이는 그대로 두고 빠르기만 늦춘다
                if (speed != 1f) runCatching { it.playbackParams = it.playbackParams.setSpeed(speed).setPitch(1f); Prend.log("· 한마디($label) 빠르기 ${it.playbackParams.speed}배, 길이 ${it.duration}ms") }.onFailure { e -> Prend.log("✗ 한마디($label) 빠르기 못 바꿈 $e") }
                if (!it.isPlaying) it.start()
            }
            // 틀지 못하면 기록만 하고, 전처럼 끝난 것으로 넘긴다(false면 MediaPlayer가 onCompletion을 부른다)
            setOnErrorListener { _, what, extra -> Prend.log("✗ 한마디($label) 틀기 오류 $what/$extra"); false }
            prepareAsync()
        }
        // 준비하는 동안 곡이 먼저 내려가 말의 첫 음절이 묻히지 않게 지금 낮춘다(끝나거나 끊기면 stop()이 돌린다)
        duck(true)
        return true
    }

    /** 지금 말의 준비가 끝났나(준비 중에는 크기를 바꾸지 않는다), 길 안내 등이 낮춰 달라고 해 이 말을 낮췄나 */
    private var prepared = false
    private var ducked = false

    /** 지금 말이 얼마나 나갔나(0~1). 말하지 않거나 아직 준비 중이면 0. 끊긴 말을 다시 할지 정할 때 쓴다(Care) */
    fun progress(): Float {
        val p = player ?: return 0f
        if (!prepared) return 0f
        return runCatching { val d = p.duration; if (d > 0) p.currentPosition.toFloat() / d else 0f }.getOrDefault(0f)
    }

    /**
     * 말하는 동안의 포커스 변화(감사 F112). 잠깐 잃으면(통화·벨·다른 음성 비서) 끊어 부른 쪽이 줄에 되돌리고, 아주 잃으면 끊되
     * PREND 곡을 막 틀어 가져간 것이면 그대로 말한다(곡은 말하는 동안 소리 엔진이 낮춘다). 낮춰 달라면(길 안내) 낮췄다가 돌려받으면 되돌린다.
     * 대답을 듣는 몇 초(player 없음)는 기록만 한다: 그때의 통화는 Care가 보고 닫는다
     */
    private fun onFocus(change: Int, label: String) {
        val p = player ?: return Prend.log("· 한마디($label) 포커스 변화 $change")
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                // PREND가 후진·드라이브스루로 다른 음악을 멈추려고 막 잡은 포커스면 끊지 않는다(그때 멈출 말은 Care가 차 신호로 가른다)
                if (SystemClock.elapsedRealtime() - Bridge.ownFocusAt < OWN_FOCUS_MS) Prend.log("· 한마디($label): PREND가 다른 음악을 멈추려고 잡은 포커스 → 그대로 말함")
                else { Prend.log("◆ 한마디($label): 다른 소리가 잠깐 가져감 → 끊음"); cut() }
            AudioManager.AUDIOFOCUS_LOSS ->
                if (Bridge.director?.willPlay == true) Prend.log("· 한마디($label): PREND 곡이 포커스를 가져감 → 그대로 말함")
                else { Prend.log("◆ 한마디($label): 다른 소리가 가져감 → 끊음"); cut() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                ducked = true
                if (prepared) runCatching { p.setVolume(DUCK, DUCK) }
                Prend.log("· 한마디($label): 길 안내 등에 낮춤")
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (ducked) {
                ducked = false
                if (prepared) runCatching { p.setVolume(1f, 1f) }
                Prend.log("· 한마디($label): 원래 크기로")
            }
            else -> Prend.log("· 한마디($label) 포커스 변화 $change")
        }
    }

    /** 말하는 동안 PREND 곡을 낮췄나(감사 F107, sound/SoundDirector.speaking) */
    private var ducking = false

    private fun duck(on: Boolean) {
        if (ducking == on) return
        ducking = on
        Bridge.director?.speaking(this, on)
    }

    /** 대답을 듣는 동안만 다른 음악을 멈춘다(듣기는 Pleos STT가 따로 한다) */
    private fun listen(attrs: AudioAttributes, label: String, answerMs: Long) {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener({ Prend.log("· 대답 듣기($label) 포커스 변화 $it") }, main)
            .build()
        request = req
        Prend.log("◆ 대답 듣기($label) 포커스 요청 → ${focusName(audio.requestAudioFocus(req))}")
        main.postDelayed({
            Prend.log("· 대답 듣기($label) ${answerMs}ms 끝, 포커스 돌려줌")
            stop()
        }, answerMs)
    }

    /** 마지막으로 끊긴 말이 얼마나 나갔었나(0~1, onCut 안에서 본다) */
    var cutProgress = 0f
        private set

    /** 다른 일이 끼어들어 지금 말을 끊는다(끊긴 말의 onCut을 부른다). 프렌드를 부르거나 다른 한마디가 나갈 때 */
    fun cut() {
        val c = onCut
        cutProgress = progress()
        stop()
        c?.invoke()
    }

    fun stop() {
        onCut = null
        main.removeCallbacksAndMessages(null)
        // 놓기(release)는 mediaserver를 기다리므로 메인 밖에서 한다. 알림은 먼저 떼어 늦게 온 옛 말의 끝 알림이 새 말을 끊지 않게 한다
        player?.let { p ->
            p.setOnCompletionListener(null); p.setOnPreparedListener(null); p.setOnErrorListener(null)
            releaser.execute { runCatching { p.release() } }
        }
        player = null
        duck(false)
        request?.let { audio.abandonAudioFocusRequest(it) }
        request = null
    }

    private fun focusName(r: Int) = when (r) {
        AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> "허락"
        AudioManager.AUDIOFOCUS_REQUEST_FAILED -> "거절"
        AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> "보류"
        else -> "?$r"
    }

    companion object {
        /** "뭐라고?"에 다시 하는 빠르기, 천천히 말하기의 빠르기(검토-방향노인초보 3-4) */
        const val REPLAY_RATE = 0.85f
        const val SLOW_RATE = 0.9f
        /** 통화로 보는 오디오 모드(ride/Crash와 같다) */
        private val CALL_MODES = setOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_RINGTONE, AudioManager.MODE_CALL_SCREENING)

        /** 길 안내 등이 낮춰 달라고 할 때 이 말의 크기(약 −10dB) */
        private const val DUCK = .32f

        /** PREND가 스스로 잡은 포커스 뒤 이만큼 안에 온 '잠깐 잃음'은 그 포커스 때문으로 본다(알림은 메인 스레드로 곧 온다) */
        private const val OWN_FOCUS_MS = 1_000L

        /** 시험 명령 이름 → AudioAttributes usage */
        val USAGES = mapOf(
            "nav" to AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
            "assistant" to AudioAttributes.USAGE_ASSISTANT,
            "notif" to AudioAttributes.USAGE_NOTIFICATION,
            "sonif" to AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
            "media" to AudioAttributes.USAGE_MEDIA,
        )
    }
}

/**
 * 목소리 파일을 연다. 코드에는 "voice/이름.wav"로 적고, 앱에는 같은 이름의 OGG(Vorbis)로 넣어 APK를 줄였다
 * (다듬은 WAV 원본은 tools/voice/app-wav). OGG가 없으면 적힌 이름 그대로 연다
 */
fun openVoice(context: android.content.Context, asset: String): android.content.res.AssetFileDescriptor =
    runCatching { context.assets.openFd(asset.removeSuffix(".wav") + ".ogg") }.getOrElse { context.assets.openFd(asset) }
