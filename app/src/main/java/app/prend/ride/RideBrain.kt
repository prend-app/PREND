package app.prend.ride

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import app.prend.Prend
import app.prend.car.CarState
import app.prend.sound.Occupancy
import app.prend.sound.SoundDirector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Calendar

/** 화면에 띄우는 차의 물음. 두 갈래 + 괜찮아요 */
data class Question(val lineId: String, val text: String, val options: List<String>)

/** 화면이 보는 운행 상태 */
data class RideUi(
    val showCard: Boolean = false,
    val needConsent: Boolean = false,
    val session: Boolean = false,
    val baby: Boolean = false,
    val occupancy: String = "혼자",
    val riding: Boolean = false,
    val rideMinutes: Int = 0,
    val quiet: Boolean = false,
    val sleeping: Boolean = false,
    val sleepLabel: String? = null,
    val talking: Boolean = false,
    val caption: String? = null,
    val tone: Tone = Tone.SOLO,
    val question: Question? = null,
    val status: String = "",
    val playing: Boolean = false,
    val title: String = "",
    val artist: String = "",
)

/**
 * 운행 판단(기획안 v6 §3). 누가 탔는지, 운행의 시작과 끝, 출발 카드, '괜찮아요', 잘래요,
 * 그리고 차가 먼저 말하는 때를 정한다. 사용자 규칙(10/24 밤):
 * - 음악이 흐르는 동안 차는 먼저 말하지 않는다. 예외 하나: 둘 이상(모시는 길 제외)이 60분 넘게 달리는 동안
 *   10초 넘게 이어진 대화가 한 번도 없으면, 신호 대기에 한 번만 '가볍게/진하게'를 묻는다(바텐더의 개입).
 * - 음악이 꺼져 있고 둘 이상이 3분 넘게 조용하면 "한 곡 깔아 둘까요?"(30분에 한 번).
 * - 혼자일 때 먼저 하는 말은 없다. 카드도 없다(마이크 없이 숨 쉬는 음악만).
 * - 깜빡이·대화·잠·급가속 직후에는 말하지 않는다.
 */
class RideBrain(
    context: Context,
    private val director: SoundDirector,
    private val player: Player,
    /** 곡 id로 재생할 수 있는 MediaItem을 돌려준다(서비스가 가진 목록) */
    private val playlist: () -> List<MediaItem>,
) {

    private val ui = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("ride", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(RideUi())
    val state: StateFlow<RideUi> = _state

    private var car = CarState()
    private var cardAnswered = false
    private var riding = false
    private var rideStart = 0L
    private var quiet = false
    private var icebreakDone = false
    private var longTalkSeen = false
    private var talkStart = 0L
    private var lastTalkEnd = 0L
    private var lastOffer = 0L
    private var babyAsked = false
    private var answers = mutableListOf<String>()
    private var answerTimer: Runnable? = null
    private var captionClear: Runnable? = null

    /** 시연 배속: 운행 시간을 이만큼 곱해 센다(1시간을 1분으로 = 60). PT에서 밝히고 쓴다 */
    var clockSpeed = 1.0

    private fun now() = SystemClock.elapsedRealtime()
    private fun rideMs() = if (riding) ((now() - rideStart) * clockSpeed).toLong() else 0L

    private fun push(change: (RideUi) -> RideUi = { it }) {
        val occ = director.occupancy
        _state.value = change(_state.value).copy(
            showCard = needCard(),
            needConsent = !prefs.getBoolean("consent", false),
            occupancy = occ.label,
            riding = riding,
            rideMinutes = (rideMs() / 60_000).toInt(),
            quiet = quiet,
            sleeping = director.sleep,
            baby = director.baby,
            talking = director.talking,
            tone = tone(),
            playing = player.isPlaying,
            title = player.mediaMetadata.title?.toString() ?: "",
            artist = player.mediaMetadata.artist?.toString() ?: "",
            status = status(),
        )
    }

    private fun needCard(): Boolean = !cardAnswered && !riding && director.occupancy != Occupancy.SOLO

    // ── 톤: 탄 사람과 대화의 결로 고른다(말소리 비율은 말소리 감지 모듈이 오면 그것으로) ──
    private fun tone(): Tone = when {
        director.sleep -> Tone.WHISPER
        director.baby -> Tone.BABY
        director.occupancy == Occupancy.SOLO -> Tone.SOLO
        longTalkSeen || director.talking -> Tone.BRIGHT
        else -> Tone.EASY
    }

    private fun status(): String = when {
        director.sleep -> sleepLabel()
        director.talking && director.occupancy != Occupancy.SOLO -> "대화 중 · 음악이 한 걸음 뒤로"
        car.wiperOn && !car.anyWindowOpen && player.isPlaying -> "빗소리를 조금 깔아 뒀어요"
        else -> ""
    }

    private fun sleepLabel(): String {
        val rear = listOf("ROW_2_LEFT", "ROW_2_CENTER", "ROW_2_RIGHT").any { car.seats[it] == true }
        return if (rear) "뒷자리 손님 쉬는 중" else "옆자리 손님 쉬는 중"
    }

    // ── 입력 ──

    fun onCar(old: CarState, now: CarState) {
        car = now
        if (!riding && old.parked && !now.parked) startRide()
        val driverOpened = (old.doors["ROW_1_LEFT"] ?: 0) == 0 && (now.doors["ROW_1_LEFT"] ?: 0) > 0
        if (riding && now.parked && driverOpened) endRide()
        push()
    }

    fun onPlaying(playing: Boolean) = push()

    fun onTalking(talking: Boolean) {
        val t = now()
        if (talking) talkStart = t
        else {
            if (talkStart > 0 && t - talkStart >= 10_000) {
                if (!longTalkSeen) Prend.log("· 이 운행에 10초 넘는 대화가 있었다")
                longTalkSeen = true
            }
            lastTalkEnd = t
        }
        push()
    }

    private fun startRide() {
        riding = true; rideStart = now()
        quiet = false; icebreakDone = false; longTalkSeen = false; lastTalkEnd = now(); lastOffer = 0L; babyAsked = false
        Prend.log("◆ 운행 시작(${director.occupancy.label}${if (director.baby) ", 아기" else ""}${if (_state.value.session) ", 세션" else ", 마이크 없음"})")
    }

    private fun endRide() {
        riding = false; cardAnswered = false
        // 잘래요의 잔잔한 소리는 잘래요를 쥔 쪽(ride/Nap, Bridge.sleep)을 따른다: 도착 전 휴게소에서 운전석 문을 열어도 동승자는 아직 자는 중이다
        // (11/08 오류 점검 F3·D3: 여기서 늘 풀어 남은 길 내내 곡이 평소 크기·밝기로 나왔다)
        if (director.sleep && !app.prend.Bridge.sleep) director.sleep = false
        _state.value = _state.value.copy(session = false, question = null)
        Prend.log("◆ 운행 끝")
    }

    // ── 출발 카드 ──

    fun consent(on: Boolean) {
        prefs.edit().putBoolean("consent", true).putBoolean("micOn", on).apply()
        Prend.log("· 말소리 감지 동의: $on")
        push()
    }

    /** 출발 카드의 답. 누르는 일이 곧 '사람이 시작한 운행'이다(먼저 말 걸기·마이크의 조건) */
    fun answerCard(baby: Boolean, withMusic: Boolean) {
        cardAnswered = true
        director.baby = baby
        _state.value = _state.value.copy(session = prefs.getBoolean("micOn", true))
        Prend.log("◆ 출발 카드: ${if (baby) "아기가 타고 있어요" else "아기가 타고 있지 않아요"}${if (withMusic) " · 음악과 함께" else ""}")
        if (withMusic) play()
        push()
    }

    // ── 차가 말하기(목소리는 대사집 음성이 들어오면 함께 낸다. 지금은 화면 자막) ──

    private fun say(lineId: String, slots: Map<String, String> = emptyMap(), options: List<String> = emptyList()) {
        val text = Lines.fill(lineId, slots)
        Prend.log("◆ 말하기[${tone().label}] $text")
        captionClear?.let { ui.removeCallbacks(it) }
        push { it.copy(caption = text, question = if (options.isEmpty()) null else Question(lineId, text, options + "괜찮아요")) }
        val clear = Runnable { push { it.copy(caption = null, question = null) } }
        captionClear = clear
        ui.postDelayed(clear, if (options.isEmpty()) 6_000 else 15_000)
    }

    /** 깜빡이·대화·잠·조용히·모시는 길 중에는 먼저 말하지 않는다 */
    private fun mayAsk(): Boolean = riding && _state.value.session && !quiet && !director.sleep && !director.talking &&
        car.turnSignal == "OFF" && _state.value.question == null &&
        (director.occupancy == Occupancy.PAIR || director.occupancy == Occupancy.GROUP)

    private val tick = object : Runnable {
        override fun run() {
            evaluate()
            push()
            ui.postDelayed(this, 1000)
        }
    }

    private fun evaluate() {
        if (!mayAsk()) return
        val t = now()
        val playing = player.isPlaying
        val silentMs = ((t - maxOf(lastTalkEnd, rideStart)) * clockSpeed).toLong()
        if (director.baby && !playing && !babyAsked && director.waitingAtLight) {
            babyAsked = true
            say(Lines.BABY_FIRST, options = listOf("신나는 노래", "자장 노래"))
            return
        }
        if (playing) {
            // 바텐더의 개입: 60분 넘게 10초 넘는 대화가 없으면, 신호 대기에 한 번만
            if (!icebreakDone && !longTalkSeen && rideMs() >= 60 * 60_000 && director.waitingAtLight) {
                icebreakDone = true
                val night = Calendar.getInstance().get(Calendar.HOUR_OF_DAY).let { it >= 21 || it < 5 }
                if (night) say(Lines.ICEBREAK_NIGHT, options = listOf("잔잔하게", "조금 신나게"))
                else say(Lines.ICEBREAK, slots = mapOf("날씨" to if (car.wiperOn) "비 오는 " else "", "요일" to weekday()), options = listOf("가볍게", "진하게"))
            }
            return
        }
        // 음악이 꺼져 있고 3분 넘게 조용하면 한 곡 권하기(30분에 한 번)
        if (silentMs >= 3 * 60_000 && (lastOffer == 0L || (t - lastOffer) * clockSpeed >= 30 * 60_000) &&
            (director.waitingAtLight || car.speedKmh > 30f)) {
            lastOffer = t
            when {
                director.occupancy == Occupancy.GROUP -> say(Lines.GROUP, options = listOf("다 같이 아는 노래", "잔잔하게"))
                car.wiperOn -> say(Lines.OFFER_RAIN, options = listOf("틀어 줘"))
                car.speedKmh in 1f..30f -> say(Lines.OFFER_JAM, options = listOf("틀어 줘"))
                else -> say(Lines.OFFER_OPEN, options = listOf("가볍게", "진하게"))
            }
        }
    }

    private fun weekday(): String = arrayOf("일요일", "월요일", "화요일", "수요일", "목요일", "금요일", "토요일")[Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1]

    // ── 대답(화면 버튼. 음성 인식은 시험 9 뒤에) ──

    fun answer(option: String) {
        val q = _state.value.question ?: return
        if (option == "괜찮아요") { sayOkay(); return }
        Prend.log("· 대답: $option")
        answers += option
        // 두 사람이 6초 안에 서로 다른 갈래를 고르면 '반반'
        answerTimer?.let { ui.removeCallbacks(it) }
        val decide = Runnable {
            val picked = answers.distinct()
            answers.clear()
            push { it.copy(question = null) }
            when {
                picked.size >= 2 -> say(Lines.HALF)
                q.lineId == Lines.OFFER_RAIN || q.lineId == Lines.OFFER_JAM || picked.first() == "틀어 줘" -> Unit
                q.lineId == Lines.BABY_FIRST && picked.first() == "자장 노래" -> say(Lines.BABY_SLEEP)
                else -> say(Lines.ONE_SIDE, mapOf("고른 것" to picked.first().removeSuffix(" 노래")))
            }
            play()
        }
        answerTimer = decide
        ui.postDelayed(decide, if (q.options.size > 2) 6_000 else 300)
    }

    /** "괜찮아요": 그 운행 끝까지 다시 권하지 않는다. 말 없이 확인 차임만 */
    fun sayOkay() {
        quiet = true
        Prend.log("◆ 괜찮아요: 이 운행에서는 다시 권하지 않는다")
        push { it.copy(question = null, caption = null) }
    }

    // ── 잘래요 ──

    fun toggleSleep() {
        val on = !director.sleep
        director.sleep = on
        if (on) say(Lines.SLEEP) else push { it.copy(caption = null) }
        push()
    }

    fun play() {
        if (player.mediaItemCount == 0) player.setMediaItems(playlist())
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.prepare(); player.play()
    }

    fun pause() = player.pause()

    /** 시연: 곡 정보를 물었을 때의 답 */
    fun songInfo() = say(Lines.SONG_INFO, mapOf("곡" to (player.mediaMetadata.title?.toString() ?: "")))

    fun start() { ui.post(tick) }
    fun stop() { ui.removeCallbacksAndMessages(null) }
}
