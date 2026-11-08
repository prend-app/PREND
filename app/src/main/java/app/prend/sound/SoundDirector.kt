package app.prend.sound

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.Player
import app.prend.Prend
import app.prend.car.CarState
import app.prend.music.Library
import kotlin.math.ln

/** 탄 사람 구성. 관계는 짐작하지 않고 자리만 본다 */
enum class Occupancy(val label: String) { SOLO("혼자"), PAIR("둘"), GROUP("셋 이상"), CHAUFFEURED("모시는 길") }

/**
 * 차와 사람의 상태를 숨 쉬는 음악 엔진의 목표 값으로 옮긴다(메인 스레드).
 * 값은 기획안 v6 §4-0·§6-0과 사용자 결정(10/24)을 따른다.
 * - 운전: 신호 대기에는 곡을 바꾸지 않는다(차가 말해도 되는 창으로만 쓴다)
 * - 날씨: 와이퍼 단계별 빗소리(간헐 −20, 연속 1~2·자동 −16, 연속 3 이상 걷기, MIST 무시), 창이 열리면 걷기, 눈(1°C 이하)엔 빗소리 없음.
 *   [프렌드] "빗소리 깔아 줘"면 와이퍼 없이도 −16으로 깔고, "빗소리 꺼 줘"면 와이퍼가 돌아도 걷는다(그 운행만)
 * - 사람: 대화가 시작되면 목소리 길을 서서히 비우고, 10초 넘게 이어지면 알아채지 못할 만큼 천천히 줄이고(최대 −8dB, 이미 작으면 그대로),
 *   대화가 끊기고 10초 뒤 서서히 원래대로. 잘 때는 잔잔하게. 설정 '풍성하게'를 켜면 혼자일 때 넓게 + 작은 음량 보정
 * - 처음과 끝: 시동 뒤 첫 재생은 5초에 걸쳐 들어오고, P에서 운전석 문이 열리면 마디 끝에서 내려앉는다.
 *   사람이 정한 차 음량은 기억하거나 맞추지 않는다(소리 원칙: 평소 음량에 맞추기는 하지 않는다, 감사 F06)
 * - PREND가 말하는 동안: 곡을 −8dB 낮췄다가 말이 끝나면 돌린다(speaking)
 */
class SoundDirector(private val context: Context, private val engine: BreathEngine, private val player: Player) {

    private val ui = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val prefs = context.getSharedPreferences("sound", Context.MODE_PRIVATE)

    var car = CarState()
        private set

    // ── 사람·운행 입력 ──
    var talking = false
        private set
    var sleep = false
        set(v) { field = v; Prend.log(if (v) "◆ 잘래요: 얕은 숨" else "◆ 잘래요 끝"); apply() }
    var baby = false
        set(v) { field = v; apply() }
    var manualRain = false
        set(v) { field = v; Prend.log(if (v) "◆ 말로 켠 빗소리" else "◆ 말로 끈 빗소리"); apply() }
    /** 말로 뺀 빗소리("빗소리 꺼 줘"): 와이퍼가 돌아도 걷는다. 새 운행에 되돌린다 */
    var rainOff = false
        set(v) { field = v; apply() }
    /** 대리 운행(별첨 4 §1): 곡을 6dB 낮게 깔고, 와이퍼 빗소리 결과 풍성하게를 끈다. 이야기에 비켜 서기는 그대로 */
    var daeri = false
        set(v) { if (field == v) return; field = v; Prend.log(if (v) "◆ 대리 운행: 곡 −6dB, 빗소리 결·풍성하게 끔" else "· 대리 운행 끝: 소리 원래대로"); apply() }
    /** 멀미("애가 멀미해"): 그 운행 동안 곡을 한 단(−3dB) 낮춘다 */
    var motion = false
        set(v) { if (field == v) return; field = v; Prend.log(if (v) "◆ 멀미: 곡 −3dB" else "· 멀미 끝: 곡 원래대로"); apply() }

    /** 설정 '작게 들을 때 풍성하게'(기본 꺼짐): 혼자일 때 넓게 + 작은 음량 보정. 앱은 차 음량을 몰라 사람이 켠다(2주차 시험 1) */
    var rich: Boolean
        get() = prefs.getBoolean("rich", false)
        set(v) { prefs.edit().putBoolean("rich", v).apply(); Prend.log("· 설정 풍성하게=$v"); apply() }

    // ── 시연 패널의 덮어쓰기(비교 녹음용, 밝히고 쓴다) ──
    var occupancyOverride: Occupancy? = null
        set(v) { field = v; apply() }
    var widthOverride: Boolean? = null
        set(v) { field = v; apply() }
    var loudOverride: Boolean? = null
        set(v) { field = v; apply() }
    /** 시연 배속: 대화의 기다림(10초)을 4초로 줄인다(PT 시연용, 밝히고 쓴다). 줄이고 키우는 속도는 그대로 */
    var demoFast = false

    /** 손님이 막 타 인사하는 중(ride/Care가 조수석 문과 자리로 알린다) */
    private var greeting = false
    private val greetEnd = Runnable {
        greeting = false
        engine.carveDb.rateDown = 0.5
        if (!talking) { engine.carveDb.target = 0.0; Prend.log("· 손님 인사 끝: 목소리 길 원래대로"); apply() }
    }

    /**
     * 손님이 막 탔다: 인사하는 동안(ms) 목소리 길을 1초에 걸쳐 비운다(대화 때 비켜 서기와 같은 −4dB, 곡의 크기는 그대로).
     * PREND 곡이 흐를 때만 들린다(다른 앱 음악은 차 음량 권한이 막혀 낮출 수 없다). 그 사이 이야기가 이어지면 대화 때 비켜 서기가 이어받는다
     */
    fun greet(ms: Long) {
        if (!player.isPlaying) return
        Prend.log("◆ 손님 탐: ${ms / 1000}초 동안 목소리 길을 비운다")
        greeting = true
        engine.carveDb.rateDown = 4.0
        engine.carveDb.target = -4.0
        ui.removeCallbacks(greetEnd); ui.postDelayed(greetEnd, ms)
        apply()
    }

    /**
     * 마이크로 잰 '목소리가 배경(음악+도로 소음)보다 몇 dB 큰가'. 내용이 아니라 크기만 잰다(말소리 감지 모듈이 채운다).
     * 이미 목소리가 충분히 또렷하면(12dB 이상) 음악을 줄이지 않는다. 모르면 null.
     */
    var voiceOverBackgroundDb: Double? = null

    init {
        // 대화 때 비켜 서기는 사람이 알아채지 못할 만큼 곧고 천천히: 목소리 길 0.5dB/초, 음량 0.3dB/초로 내리고 0.5dB/초로 돌아온다
        engine.carveDb.rateDown = 0.5; engine.carveDb.rateUp = 0.5
        engine.duckDb.rateDown = 0.3; engine.duckDb.rateUp = 0.5
    }

    private var rearDoorHistory = false
    private var stopped = false
    private var pendingStop = false
    private var freezeUntil = 0L
    private val speeds = ArrayDeque<Pair<Long, Float>>()
    private var talkStart = 0L
    private var lastPlayEnd = 0L
    private var lastState = ""

    val occupancy: Occupancy
        get() {
            occupancyOverride?.let { return it }
            val s = car.seats
            val front = s["ROW_1_RIGHT"] == true
            val rear = listOf("ROW_2_LEFT", "ROW_2_CENTER", "ROW_2_RIGHT").count { s[it] == true }
            val others = (if (front) 1 else 0) + maxOf(rear, if (rearDoorHistory) 1 else 0)
            return when {
                others == 0 -> Occupancy.SOLO
                // 조수석이 비고 뒷자리에만 사람이면 몇 명이든 모시는 길(뒷자리 둘을 '셋 이상'으로 보지 않는다, 감사 F88)
                !front -> Occupancy.CHAUFFEURED
                others == 1 -> Occupancy.PAIR
                else -> Occupancy.GROUP
            }
        }

    // ── 차 ──

    fun onCar(old: CarState, now: CarState) {
        car = now
        val t = SystemClock.elapsedRealtime()
        speeds.addLast(t to now.speedKmh)
        while (speeds.size > 2 && t - speeds.first().first > 1200) speeds.removeFirst()
        val ref = speeds.firstOrNull { t - it.first >= 900 }
        if (ref != null && kotlin.math.abs(now.speedKmh - ref.second) > 10f && t > freezeUntil) {
            freezeUntil = t + 3000
            Prend.log("◆ 급가속·급제동: 3초 동안 소리 상태 고정")
            ui.postDelayed({ evaluate() }, 3050)
        }
        if (now.parked && now.doors.filterKeys { it.startsWith("ROW_2") }.values.any { it > 0 }) rearDoorHistory = true
        val driverOpened = (old.doors["ROW_1_LEFT"] ?: 0) == 0 && (now.doors["ROW_1_LEFT"] ?: 0) > 0
        if (now.parked && driverOpened) arrive()
        if (old.turnSignal != now.turnSignal) evaluate()
        evaluate()
        apply()
    }

    // ── 신호 대기(소리는 바꾸지 않는다. 차가 말해도 되는 창을 고를 때 쓴다) ──

    private val enterStop = Runnable {
        pendingStop = false
        if (!stopped) { stopped = true; Prend.log("· 신호 대기") }
    }

    /** 급가속·급제동 직후 3초, 달리며 깜빡이를 켠 동안(차로 변경)은 판정을 바꾸지 않는다 */
    private fun evaluate() {
        val t = SystemClock.elapsedRealtime()
        if (t < freezeUntil) return
        if (car.speedKmh > 5f && car.turnSignal != "OFF") return
        // 브레이크는 보지 않는다(오토홀드·i-Pedal로 서면 페달에서 발을 뗀다, 검토 11/06. ride/Timing과 같다)
        val waiting = car.driving && car.speedKmh < 1f
        if (waiting) {
            if (!stopped && !pendingStop) { pendingStop = true; ui.postDelayed(enterStop, 600) }
        } else {
            if (pendingStop) { ui.removeCallbacks(enterStop); pendingStop = false }
            if (stopped) { stopped = false; Prend.log("· 출발") }
        }
    }

    /**
     * 지금 신호 대기인가(차가 말해도 되는 창). 운행 서비스의 '편한 때' 판정(ride/Timing: D·시속 0이 5초, 별첨 2 §2-1)을 함께 쓴다.
     * 운행 서비스가 아직 없을 때만 이 엔진의 짧은 판정(0.6초)으로 본다
     */
    val waitingAtLight get() = app.prend.ride.Timing.live?.atLight(car) ?: stopped

    private fun track() = Library.byId(player.currentMediaItem?.mediaId)

    /**
     * 지금 마디가 끝날 때까지(ms). 길어야 한 마디(별첨 4 §2-3). 마디 끝이 0.8초 안이면 너무 급하게 꺼지니 다음 마디의 박에서 멈춘다
     * (한 마디를 통째로 더하면 한 마디+0.8초까지 늘어났다)
     */
    private fun msToBarEnd(): Long {
        val tr = track() ?: return 2500
        val phase = ((player.currentPosition - tr.firstBeatMs) % tr.barMs + tr.barMs) % tr.barMs
        var left = tr.barMs - phase
        while (left < 800) left += tr.beatMs
        return left.coerceAtMost(tr.barMs).toLong()
    }

    // ── 말소리(내용은 듣지 않는다. 말소리 감지나 시연 스위치가 알려 준다) ──
    // 사용자 설계(10/24 밤): 대화가 시작되면 목소리 길만 서서히 비우고, 10초 넘게 이어지면 알아채지 못할 만큼 천천히 줄인다.
    // 꺼질 때까지 줄이지 않는다(최대 −8dB). 이미 음악이 충분히 작으면 줄이지 않는다. 대화가 끊기고 10초 뒤 서서히 원래대로.

    /** 줄일 양: 목소리가 배경보다 12dB 이상 크면 0, 차이가 작을수록 더(최대 −8dB). 모르면 −6dB */
    private fun duckAmount(): Double {
        val r = voiceOverBackgroundDb ?: return -6.0
        return (r - 12.0).coerceIn(-8.0, 0.0)
    }

    private val duckLong = Runnable {
        if (!talking) return@Runnable
        val amount = duckAmount()
        if (amount == 0.0) { Prend.log("◆ 대화 10초: 음악이 이미 충분히 작다(목소리 ${voiceOverBackgroundDb}dB 위) → 줄이지 않는다"); return@Runnable }
        Prend.log("◆ 대화 10초: 음악을 ${"%.1f".format(amount)}dB까지 초당 0.3dB씩 줄인다")
        engine.duckDb.target = amount
    }

    private val restore = Runnable {
        if (!talking) {
            Prend.log("◆ 대화 끊기고 10초: 초당 0.5dB씩 원래대로")
            if (!greeting) engine.carveDb.target = 0.0
            engine.duckDb.target = 0.0
            apply()
        }
    }

    /** 말소리가 시작·끝날 때 운행 판단(RideBrain)에도 알린다 */
    var onTalkingChanged: ((Boolean) -> Unit)? = null

    fun setTalking(now: Boolean) {
        if (now == talking) return
        talking = now
        onTalkingChanged?.invoke(now)
        // 혼자면 비켜 서기를 시작하지 않는다(혼잣말·따라 부르기). 끝날 때는 혼자여도 되돌린다(동승자가 내린 뒤 비운 길이 남지 않게)
        if (now && occupancy == Occupancy.SOLO) { apply(); return }
        val wait = if (demoFast) 4_000L else 10_000L
        if (now) {
            talkStart = SystemClock.elapsedRealtime()
            ui.removeCallbacks(restore)
            Prend.log("◆ 대화 시작: 목소리 길을 서서히 비운다")
            engine.carveDb.target = -4.0
            ui.removeCallbacks(duckLong); ui.postDelayed(duckLong, wait)
        } else {
            ui.removeCallbacks(duckLong)
            ui.postDelayed(restore, wait)
        }
        apply()
    }

    // ── 시동 뒤 첫 곡 ──

    private fun musicIndex(): Int? = runCatching { audio.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()

    private var forceCold = false

    /** 시연: 다음 재생을 '시동 뒤 첫 곡'으로 다룬다 */
    fun simulateColdStart() { forceCold = true }

    /** 급할 때(E)가 끝났다: 다음 재생도 시동 뒤 첫 곡처럼 5초에 걸쳐 들인다(놀란 뒤에 음악이 확 들어오지 않게) */
    fun afterEmergency() {
        forceCold = true
        Prend.log("· 급할 때 끝: 다음 곡은 5초에 걸쳐 들어온다")
    }

    /** 시험: 다음 재생을 서서히 들일 차례인가 */
    val softNext get() = forceCold

    /** 시연: P에서 운전석 문이 열린 것처럼 내려앉는다 */
    fun arriveNow() = arrive()

    /** PREND 사운드가 지금 흐르는가 */
    val playing get() = player.isPlaying

    /** PREND 곡을 틀라고 했다(막 틀어 포커스를 청했을 수 있다. 한마디가 포커스를 잃었을 때 PREND 곡 때문인지 가린다) */
    val willPlay get() = player.playWhenReady

    /**
     * 물러서기(드라이브스루·후진·급제동, 운행 서비스의 Hush가 정한다). db까지 downPerSec(dB/초)로 내려가고,
     * 0으로 돌아올 때는 upPerSec로. 사람이 정한 차 음량은 건드리지 않고 PREND 소리 안에서만 움직인다
     */
    fun hush(db: Double, downPerSec: Double, upPerSec: Double) {
        engine.hushDb.rateDown = downPerSec
        engine.hushDb.rateUp = upPerSec
        engine.hushDb.target = db
    }

    /** 지금 말하는 PREND의 입(voice/Speaker). 하나라도 말하면 곡을 낮춘다 */
    private val voices = mutableSetOf<Any>()

    /**
     * PREND가 말을 시작·끝냈다(감사 F107). 한마디는 MAY_DUCK 포커스로 청하지만 덕킹은 차(HAL)의 몫이고 에뮬레이터는 꺼져 있어,
     * PREND 곡이 그대로 흘러 말을 덮는다. 그래서 곡 안에서 직접 SPEAK_DB만큼 낮춘다(다른 앱의 소리는 그 앱과 차가 맡는다)
     */
    fun speaking(who: Any, on: Boolean) {
        val was = voices.isNotEmpty()
        if (on) voices.add(who) else voices.remove(who)
        val now = voices.isNotEmpty()
        if (now == was) return
        engine.speakDb.target = if (now) SPEAK_DB else 0.0
        Prend.log(if (now) "◆ 말하는 동안: 곡 ${SPEAK_DB.toInt()}dB" else "· 말 끝: 곡 원래대로")
    }

    fun onPlaying(playing: Boolean) {
        val t = SystemClock.elapsedRealtime()
        if (!playing) { lastPlayEnd = t; return }
        val coldStart = forceCold || lastPlayEnd == 0L || t - lastPlayEnd > 10 * 60_000
        forceCold = false
        if (coldStart) {
            Prend.log("◆ 시동 뒤 첫 곡: 5초에 걸쳐 들어온다(음량 ${musicIndex()})")
            engine.fade(to = 0.0, seconds = 5.0, from = -40.0)
        } else {
            engine.fade(to = 0.0, seconds = 1.5)
        }
        apply()
    }

    // ── 도착(시동을 끌 때) ──

    private fun arrive() {
        if (!player.isPlaying) return
        val d = msToBarEnd()
        Prend.log("◆ 도착: 마디 끝까지 ${d}ms에 걸쳐 내려앉는다")
        engine.fade(to = -60.0, seconds = d / 1000.0)
        ui.postDelayed({ player.pause(); rearDoorHistory = false }, d + 60)
    }

    // ── 목표 값 ──

    /**
     * 원칙(사용자 승인 10/24 밤): 사람이 고른 곡의 소리는 건드리지 않는다. 바꾸는 것은 사람을 위할 때뿐이고(대화·둘 이상의 신호 대기·잠),
     * 날씨는 곡 위에 결을 얹을 뿐이다. 그래서 혼자일 때의 신호 대기, 밤, 눈에는 곡의 소리를 바꾸지 않는다.
     */
    fun apply() {
        val snow = car.wiperOn && (car.outsideC ?: 99f) <= 1f
        val occ = occupancy

        // 신호 대기에는 곡의 소리를 바꾸지 않는다(사용자 결정 10/24 밤). 잠들 때만 잔잔하게
        engine.logCutoff.target = if (sleep) ln(4000.0) else engine.openCutoffLog()

        val sw = car.wiperSwitch
        val heavy = sw.startsWith("CONTINUOUS_LEVEL_") && (sw.substringAfterLast('_').toIntOrNull() ?: 0) >= 3
        val rain = when {
            car.anyWindowOpen || rainOff -> BreathEngine.OFF_DB
            manualRain && !car.wiperOn -> -16.0
            // 대리 운행이면 와이퍼 빗소리 결을 끈다(사람이 말로 깐 것은 그대로)
            daeri && !manualRain -> BreathEngine.OFF_DB
            !car.wiperOn || sw == "MIST" || snow || heavy -> BreathEngine.OFF_DB
            sw.startsWith("INTERMITTENT") -> -20.0
            else -> -16.0
        }
        engine.rainDb.target = rain
        engine.nightDb.target = 0.0
        val wide = (widthOverride ?: rich) && occ == Occupancy.SOLO && !talking && !sleep && !daeri
        engine.width.target = if (wide) 1.3 else 1.0
        val loud = (loudOverride ?: rich) && !talking && !sleep && !daeri
        engine.lowBoostDb.target = if (loud) 4.0 else 0.0
        engine.highBoostDb.target = if (loud) 1.5 else 0.0
        // 셋 이상이라고 미리 줄이지 않는다(곡은 건드리지 않는다. 말이 오가면 대화 때 비켜 서기가 맡는다).
        // 잘래요와 대리 운행이 겹쳐도 −6dB 한 번만(주인이 쉬는 대리 운행에서 곡이 −12dB로 꺼지듯 작아지지 않게)
        val cap = (if (baby) -3.0 else 0.0) + (if (sleep || daeri) -6.0 else 0.0) + (if (motion) -3.0 else 0.0)
        engine.levelDb.target = cap

        val desc = "${occ.label}${if (talking) "·대화" else ""}${if (sleep) "·잠" else ""}${if (baby) "·아이" else ""}${if (daeri) "·대리" else ""}${if (motion) "·멀미" else ""} " +
            "${if (stopped) "신호대기" else "달림"} 비${if (rain <= BreathEngine.OFF_DB) "없음" else rain.toInt()}" +
            "${if (car.anyWindowOpen) "(창열림)" else ""}${if (heavy) "(센비)" else ""}${if (snow) "·눈" else ""}${if (wide) "·넓게" else ""}${if (loud) "·작은음량보정" else ""}"
        if (desc != lastState) { lastState = desc; Prend.log("· 상태: $desc") }
    }

    fun describe(): String = lastState

    fun start() {
        // 예전 판이 적어 둔 '평소 음악 크기'를 지운다(이제 기억하지도 맞추지도 않는다)
        if (prefs.contains("usualIndex")) prefs.edit().remove("usualIndex").apply()
        Prend.log("· 미디어 음량 ${musicIndex()}/${runCatching { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrNull()}")
        apply()
    }

    fun stop() {
        ui.removeCallbacksAndMessages(null)
    }

    companion object {
        /**
         * PREND가 말하는 동안 곡을 낮추는 양. 별첨 v9 소리 표('공통 · PREND가 말하는 동안 −8dB')와 틈 시험 24를 따른다.
         * 사람끼리 이야기할 때 비켜 서는 기본값 −6dB(duckAmount)와는 다른 값이다
         */
        const val SPEAK_DB = -8.0
    }
}
