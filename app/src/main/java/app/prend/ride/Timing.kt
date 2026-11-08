package app.prend.ride

import android.car.VehicleGear
import android.os.SystemClock
import app.prend.Prend
import app.prend.car.CarState

/**
 * 말할 때 고르기(접수양식 3번 ND). 신호에 서 있거나 곧게 달리는 편한 때에만 말을 건넨다.
 * 깜빡이를 켰거나, 시속 100km를 넘거나, 급가속·급제동 직후이거나, 후진 중이면 하려던 말을 삼킨다.
 * 사람들이 이야기하는 중이거나 이야기가 끝난 지 20초가 안 됐으면(말소리 감지, voice/TalkSense) 끼어들지 않는다.
 * 쉼터 권하기(urgent)는 졸음과 이어져 있어 이야기가 5초만 멈춰도 건넨다(별첨 2 V7).
 * block()이 null이면 지금 말해도 되고, 아니면 삼키는 까닭을 돌려준다.
 *
 * '편한 때'(별첨 2 §2-1, 감사 F45): comfy()가 지금이 말해도 되는 세 때 가운데 어느 것인지 돌려준다(아니면 null).
 * - 신호 대기: 기어 D, 시속 0이 5초 넘게 이어짐. 브레이크는 보지 않는다(검토 11/06: 오토홀드·i-Pedal로 서면 발을 떼어 브레이크 값이 꺼진다.
 *   D에서 발을 떼면 차가 슬금슬금 구르니, D·시속 0이 5초면 브레이크를 밟고 있거나 오토홀드다)
 * - 안정 주행: 시속 30km 이상, 20초 동안 속도가 ±5km/h 띠 안, 브레이크를 밟지 않음, 깜빡이를 끈 지 10초, 20초 동안 핸들 ±10° 안,
 *   길 안내 음성이 없고 끝난 지 10초(핸들 각도를 읽지 못하는 차는 핸들을 보지 않는다)
 * - P: 문이 모두 닫히고 10초
 * 급하지 않은 말은 Care가 이때를 기다렸다가 건넨다. 소리 엔진의 '신호 대기'도 이 판정을 쓴다(sound/SoundDirector.waitingAtLight)
 *
 * 골목에서 선 것은 신호 대기로 치지 않는다(11/01 사용자 "골목 인식이 중요", "말이 많으면 거슬리는 앱"). 판정을 뒤집어 큰길이라는 증거가 있을 때만
 * 신호 대기로 본다: 서기 전 90초 안에 시속 30km 넘게 10초 넘게 달렸거나, 10분째 막히는 길이다. 아니면 마주 오는 차·사람을 기다리는 중일 수 있어 말을 미룬다.
 * 후진한 지 1분 안(주차·돌려 나오기), 내비가 알려 준 제한속도가 30 이하인 길도 신호 대기가 아니다. 틀려도 말이 늦어질 뿐이다(값은 제안값)
 * 지도가 답하면 그것이 먼저다(11/01 사용자 "티맵을 쓰면 전국 골목을 다 알 수 있잖아"): 차가 설 때 지나온 자리로 TMAP 도로 등급을 물어(ride/Road)
 * 이면도로·세도로·단지 안이면 골목, 주요도로 이상이면 큰길로 본다. 지도가 답하지 못하면(통신·기타도로) 위의 차 신호로 가른다
 * 새로 말을 꺼낼 때만 보는 것(block의 starting): 출발(P→D) 뒤 180초(V1), 후진한 지 1분 안, 길 안내 음성·다른 음성 비서의 말이 끝난 지 10초·5초 안(V17).
 * 하던 말도 멈추는 것: 신호에서 출발한 지 5초(V5), 길 안내 음성이 나오는 중. 급가속·급제동 뒤 10초(V6), 깜빡이를 끈 뒤 10초(V3)
 * 다른 음성 비서(Gleo 등)가 말하는 중이면 먼저 꺼내는 말을 미룬다(검토 11/06: 같은 음성 비서 용도로 포커스를 청하면 그 대답을 끊을 수 있다).
 * 재생 중인 소리에 음성 비서 용도가 있고 PREND는 말하지 않는 때로 본다(PREND의 말도 같은 용도라 PREND가 말하는 동안에는 가를 수 없다).
 * 급할 때 안내(ride/Crash)·혹시 사고·고속도로 정차는 이 판정을 거치지 않아 그대로 말한다. 마이크로 Gleo가 듣는 중인지는 보지 않는다(늘 막힐 수 있다)
 */
class Timing(
    /** 길 안내 음성이 지금 나오는가(재생 구성의 용도로 본다). 모르면 false */
    private val guide: () -> Boolean = { false },
    /** 10분째 막히는 길인가(막히는 큰길의 신호 대기는 앞서 빨리 달리지 않았어도 신호 대기로 본다, ride/JamHelp) */
    private val slowTraffic: (CarState) -> Boolean = { false },
    /** 내비가 알려 준 지금 길의 제한속도(km/h). 모르거나 오래된 값이면 null */
    private val speedLimit: () -> Int? = { null },
    /** 이번 정차의 TMAP 도로 등급 판정(골목·큰길, 모르면 null, ride/Road) */
    private val road: () -> Road.Kind? = { null },
    /** 곧 도는 곳(내비 회전 안내, car/Navi.turnNear). 없으면 null */
    private val turnNear: () -> String? = { null },
    /** 5분 안에 경로를 두 번 넘게 다시 찾았나(길을 헤매는 중) */
    private val lost: () -> Boolean = { false },
    /** 다른 음성 비서가 지금 말하는가(재생 중인 음성 비서 용도의 소리, PREND가 말하지 않는 동안만). 모르면 false */
    private val assistant: () -> Boolean = { false },
) {

    private val speeds = ArrayDeque<Pair<Long, Float>>()
    private var quietUntil = 0L
    private var quietWhy = ""
    /** 지금 이야기 중인가, 마지막 이야기가 끝난 때(elapsedRealtime). 말소리 감지가 넣는다 */
    private var talking = false
    private var talkEnded = 0L

    /** D·시속 0이 시작된 때(아니면 0). 브레이크는 보지 않는다(오토홀드·i-Pedal) */
    private var stopSince = 0L
    /** 다른 음성 비서의 말을 마지막으로 본 때(0이면 오래전) */
    private var assistAt = 0L
    /** 안정 주행의 조건이 이어진 때(아니면 0)와 그동안의 가장 느린·빠른 속도 */
    private var cruiseSince = 0L
    private var bandMin = 0f
    private var bandMax = 0f
    /** 깜빡이를 끈 때, 문이 모두 닫힌 때, 길 안내 음성이 마지막으로 나온 때(0이면 오래전) */
    private var signalOffAt = 0L
    private var doorsClosedAt = 0L
    private var guideAt = 0L
    /** 핸들 각도를 모른다고 기록에 한 번 남겼나 */
    private var steerUnknownLogged = false
    /** 큰길 증거: 시속 30km 넘게 달리기 시작한 때와, 그렇게 10초 넘게 달린 마지막 때 */
    private var fastSince = 0L
    private var fastEnd = 0L
    /** 마지막으로 R이었던 때, P에서 D로 출발한 때, 신호 대기에서 출발한 때 */
    private var revAt = 0L
    private var driveAt = 0L
    private var lightLeftAt = 0L
    private var lightHeld = false
    /** P에 있다가 아직 D에 들지 않았나(출발 시각을 잡으려고), 브레이크를 뗀 때 */
    private var leftP = false
    private var brakeOffAt = 0L
    /** 서 있지만 신호 대기로 치지 않은 까닭(같은 까닭이면 기록을 한 번만) */
    private var sideLogged: String? = null
    /** 시험: 좁은 길로 본다 */
    private var narrowFake = false

    /** 시험: 이때까지 이 편한 때로 본다(신호 대기·안정 주행 흉내) */
    private var fake: Pair<String, Long>? = null

    init { live = this }

    private fun now() = SystemClock.elapsedRealtime()

    fun onCar(old: CarState, car: CarState) {
        val t = now()
        speeds.addLast(t to car.speedKmh)
        while (speeds.size > 2 && t - speeds.first().first > 5_000) speeds.removeFirst()
        // 1초 사이에 시속 10km 넘게 바뀌면 급가속·급제동: 10초 동안 말하지 않는다(V6, 시연 값 4초)
        val ref = speeds.lastOrNull { t - it.first >= 900 }
        if (ref != null && kotlin.math.abs(car.speedKmh - ref.second) > 10f) quiet(t + if (ptValues) PT_HARD_MS else HARD_MS, "급가속·급제동 직후")
        // 깜빡이를 끈 뒤 10초는 차로 변경을 마무리하는 중(V3, 시연 값 2초)
        if (old.turnSignal != "OFF" && car.turnSignal == "OFF") { quiet(t + if (ptValues) PT_SIGNAL_MS else SIGNAL_MS, "차로 변경 직후"); signalOffAt = t }
        // 후진: R인 동안과 R을 떠나는 순간을 적는다(R에 오래 서 있다 D로 바꿔도 '후진 뒤 1분'은 D로 바꾼 때부터)
        if (car.gear == VehicleGear.GEAR_REVERSE || old.gear == VehicleGear.GEAR_REVERSE) revAt = t
        // 출발(V1): P를 떠난 뒤 처음 D에 든 때(P→R→D처럼 사이에 R·N이 끼어도 된다)
        if (car.parked) leftP = true else if (leftP && car.driving) { driveAt = t; leftP = false }
        if (old.brake && !car.brake) brakeOffAt = t
        evidence(car, t)
        // 문: 하나라도 열려 있다가 모두 닫히면 그때부터 10초를 센다
        val closed = car.doors.values.all { it == 0 }
        if (closed && old.doors.values.any { it > 0 }) doorsClosedAt = t
        if (!closed) doorsClosedAt = t
        // 신호 대기: D·시속 0이 이어진 때부터(오토홀드로 페달에서 발을 떼도 이어진다)
        if (car.driving && car.speedKmh < 1f) { if (stopSince == 0L) stopSince = t } else stopSince = 0L
        held(car, t)
        // 안정 주행: 조건이 깨지거나 속도가 띠(±5km/h)를 벗어나면 그 자리에서 다시 센다
        val steer = car.steer
        if (steer == null && car.driving && car.speedKmh >= CRUISE_KMH && !steerUnknownLogged) {
            steerUnknownLogged = true
            Prend.log("· 편한 때: 핸들 각도를 몰라 안정 주행에서 핸들은 보지 않음")
        }
        val steady = car.driving && car.speedKmh >= CRUISE_KMH && !car.brake && car.turnSignal == "OFF" &&
            (steer == null || kotlin.math.abs(steer) <= STEER_DEG)
        val v = car.speedKmh
        when {
            !steady -> cruiseSince = 0L
            cruiseSince == 0L || maxOf(bandMax, v) - minOf(bandMin, v) > BAND_KMH * 2 -> { cruiseSince = t; bandMin = v; bandMax = v }
            else -> { bandMin = minOf(bandMin, v); bandMax = maxOf(bandMax, v) }
        }
    }

    /**
     * 1초마다: 길 안내 음성·다른 음성 비서의 말이 나오는지 본다(끝난 뒤 10초는 안정 주행으로 치지 않는다).
     * 차 값이 그대로면 onCar가 오지 않으므로(CarHub는 바뀐 값만 알린다) 큰길 증거와 신호 대기·출발도 여기서 다시 본다
     */
    fun tick(car: CarState) {
        val t = now()
        if (runCatching { guide() }.getOrDefault(false)) guideAt = t
        assist(t)
        evidence(car, t)
        held(car, t)
    }

    /**
     * 다른 음성 비서의 말. 1분 넘게 그치지 않는 음성 비서 소리는 대답이 아니라 늘 열어 둔 소리로 보고 막지 않는다
     * (실차에서 소리 없는 재생을 늘 쥐는 앱이 있으면 먼저 꺼내는 말이 영영 막히지 않게, 제안값)
     */
    private fun assist(t: Long) {
        if (!runCatching { assistant() }.getOrDefault(false)) { assistRun = 0L; assistStuck = false; return }
        if (assistRun == 0L) assistRun = t
        if (t - assistRun >= ASSIST_MAX_MS) {
            if (!assistStuck) { assistStuck = true; Prend.log("· 편한 때: 음성 비서 소리가 ${ASSIST_MAX_MS / 1000}초 넘게 이어져 말로 보지 않음") }
            return
        }
        if (assistAt == 0L || t - assistAt > ASSIST_MS) Prend.log("· 편한 때: 다른 음성 비서가 말하는 중")
        assistAt = t
    }
    /** 음성 비서 소리가 이어지기 시작한 때(0이면 없음), 너무 오래 이어져 말로 보지 않는 중인가 */
    private var assistRun = 0L
    private var assistStuck = false

    /** 큰길 증거: 시속 30km 넘게 10초 넘게 달린 마지막 때 */
    private fun evidence(car: CarState, t: Long) {
        if (car.driving && car.speedKmh >= FAST_KMH) { if (fastSince == 0L) fastSince = t; if (t - fastSince >= FAST_MS) fastEnd = t } else fastSince = 0L
    }

    /**
     * 신호 대기로 인정되면 큰길 증거를 이어 간다(같은 줄에서 조금 굴러갔다 다시 서도 신호 대기).
     * 신호 대기에서 시속 3km를 넘으면 그때부터 5초 동안 하던 말도 멈춘다(V5)
     */
    private fun held(car: CarState, t: Long) {
        if (atLight(car)) { lightHeld = true; fastEnd = t }
        else if (lightHeld && (car.speedKmh > 3f || !car.driving)) { if (car.speedKmh > 3f) lightLeftAt = t; lightHeld = false }
    }

    /** 출발 직후(V1)·후진 뒤 1분이라 새 말을 꺼내지 않는 중인가(편한 때를 기다리는 시계를 이동안은 세지 않는다, ride/Care) */
    fun departing(car: CarState): Boolean {
        val t = now()
        return car.driving && (driveAt > 0 && t - driveAt < (if (ptValues) PT_DEPART_MS else DEPART_MS) || revAt > 0 && t - revAt < REV_MS)
    }

    /** 길 안내 음성이 나오는 중이거나 끝난 지 10초 안이면 그 까닭(먼저 꺼내는 길 한마디가 내비 안내와 겹치지 않게, V17) */
    fun guideWhy(): String? {
        val t = now()
        return when {
            guideAt > 0 && t - guideAt < GUIDE_NOW_MS -> "길 안내 음성"
            guideAt > 0 && t - guideAt < GUIDE_MS -> "길 안내 음성이 막 끝남"
            else -> null
        }
    }

    /** 깜빡이를 끈 지 10초 안(차로 변경 직후)이면 그 까닭. 다른 까닭의 쉼에 가려지지 않게 따로 본다(우회전 한 줄을 그 회전에 버릴 때, ride/Coach) */
    fun laneWhy(): String? = if (signalOffAt > 0 && now() - signalOffAt < (if (ptValues) PT_SIGNAL_MS else SIGNAL_MS)) "차로 변경 직후" else null

    /** 말소리 감지가 이야기의 시작·끝을 알린다 */
    fun onTalking(on: Boolean) {
        if (!on && talking) talkEnded = now()
        talking = on
    }

    /** 손님이 막 탔다(ride/Care): 인사하는 동안(ms) 먼저 건넬 말을 꺼내지 않는다 */
    fun greet(ms: Long) = quiet(now() + ms, "손님 인사")

    private fun quiet(until: Long, why: String) {
        if (until > quietUntil) { quietUntil = until; quietWhy = why }
    }

    /**
     * 지금 말하면 안 되는 까닭. null이면 말해도 된다. urgent(쉼터)는 이야기가 잠깐 멈춘 틈에도 건넨다.
     * starting이면 새로 말을 꺼내려는 때라 출발 직후·후진 뒤 1분·길 안내가 막 끝남도 본다(하던 말을 멈출 때는 보지 않는다).
     * asked면 사람이 먼저 꺼낸 말("화장실 급해")의 대답이라 시속 100km 넘음·이야기 중으로는 막지 않는다(고속도로 순항에서 끊겨 사라지지 않게, 검토 11/07).
     * 깜빡이·후진·N·회전 직전·길 안내 음성·급가속 같은 순간은 그대로 피한다
     */
    fun block(car: CarState, urgent: Boolean = false, starting: Boolean = false, asked: Boolean = false): String? {
        val t = now()
        val recent = speeds.filter { t - it.first <= 4_000 }.map { it.second }
        return when {
            car.gear == VehicleGear.GEAR_REVERSE -> "후진 중"
            car.gear == VehicleGear.GEAR_NEUTRAL -> "기어 N"
            car.turnSignal != "OFF" -> "깜빡이"
            !asked && car.speedKmh > 100f -> "시속 100km 넘음"
            // 11/02: 회전 직전(좌·우회전·유턴 150m, 나들목·분기 300m)은 길을 봐야 하는 때. 길을 헤매는 동안은 급한 말(쉼터·혹시 사고) 말고는 미룬다
            !car.parked && turnNear() != null -> "곧 도는 곳(${turnNear()})"
            !urgent && starting && !car.parked && lost() -> "길을 다시 찾는 중"
            t < quietUntil -> quietWhy
            lightLeftAt > 0 && t - lightLeftAt < LEFT_MS -> "신호에서 출발"
            guideAt > 0 && t - guideAt < GUIDE_NOW_MS -> "길 안내 음성"
            starting && guideAt > 0 && t - guideAt < GUIDE_MS -> "길 안내 음성이 막 끝남"
            // 다른 음성 비서(Gleo 등)의 대답 위에 먼저 꺼내는 말을 얹지 않는다. 사람이 이어서 물을 수 있어 끝난 뒤 5초도 기다린다
            assistAt > 0 && t - assistAt < GUIDE_NOW_MS -> "다른 음성 비서가 말하는 중"
            starting && assistAt > 0 && t - assistAt < ASSIST_MS -> "다른 음성 비서가 막 끝남"
            starting && car.driving && driveAt > 0 && t - driveAt < (if (ptValues) PT_DEPART_MS else DEPART_MS) -> "출발 직후"
            starting && !car.parked && revAt > 0 && t - revAt < REV_MS -> "후진한 지 1분 안"
            // V5: 달리며 브레이크를 밟는 중이거나 뗀 지 5초 안(신호 대기는 예외)
            starting && car.driving && car.brake && !atLight(car) -> "브레이크"
            starting && car.driving && brakeOffAt > 0 && t - brakeOffAt < BRAKE_OFF_MS -> "브레이크를 막 뗌"
            // 신호 대기가 아니면서 4초 사이 속도가 시속 15km 넘게 오르내리면 합류·추월 중으로 본다
            car.speedKmh > 5f && recent.size >= 2 && recent.max() - recent.min() > 15f -> "속도가 크게 바뀌는 중"
            !asked && talking -> "이야기 중"
            !asked && talkEnded > 0 && t - talkEnded < if (urgent) TALK_GAP_URGENT else TALK_GAP -> "이야기가 막 끝남"
            else -> null
        }
    }

    /** 지금 신호 대기인가(D·시속 0이 5초, 브레이크는 보지 않는다. 그리고 골목·주차 중이 아님) */
    fun atLight(car: CarState): Boolean {
        fake?.let { (k, until) -> if (now() < until) return k == LIGHT }
        val held = car.driving && car.speedKmh < 1f && stopSince > 0 && now() - stopSince >= LIGHT_MS
        if (!held) { sideLogged = null; return false }
        val side = sideWhy(car) ?: return true
        if (side != sideLogged) { sideLogged = side; Prend.log("· 편한 때: 서 있지만 신호 대기로 치지 않음($side)") }
        return false
    }

    /**
     * 서 있는 곳이 큰길의 신호 대기가 아닐 까닭(골목·주차장에서 비켜 선 것일 수 있음). 큰길 증거가 있으면 null.
     * 시연 값이면 앞서 빨리 달렸는지는 보지 않는다(시연 차는 천천히 움직인다)
     */
    private fun sideWhy(car: CarState): String? {
        val t = now()
        return when {
            narrowFake -> "좁은 길(시험)"
            revAt > 0 && t - revAt < REV_MS -> "후진한 지 1분 안"
            // 지도(TMAP 원도로등급)가 알려 주면 그것을 따른다: 이면도로·세도로·단지 안이면 골목, 주요도로 이상이면 큰길(천천히 와서 선 시내 큰길도 신호 대기)
            road() == Road.Kind.ALLEY -> "골목(지도의 도로 등급)"
            road() == Road.Kind.MAIN -> null
            speedLimit()?.let { it in 1..NARROW_LIMIT } == true -> "제한속도 ${NARROW_LIMIT} 이하 길"
            ptValues -> null
            runCatching { slowTraffic(car) }.getOrDefault(false) -> null
            fastEnd == 0L || stopSince - fastEnd > MAIN_MS -> "앞서 큰길 속도로 달리지 않음"
            else -> null
        }
    }

    /** 시험: 좁은 길로 본다(지도 판정이 오기 전 골목 흉내) */
    fun narrow(on: Boolean) { narrowFake = on; Prend.log("· 시험: 좁은 길 ${if (on) "켬" else "끔"}") }

    /** 지금 안정 주행인가 */
    private fun cruising(car: CarState): Boolean {
        val t = now()
        return car.driving && car.speedKmh >= CRUISE_KMH && !car.brake && car.turnSignal == "OFF" &&
            cruiseSince > 0 && t - cruiseSince >= (if (ptValues) PT_CRUISE_MS else CRUISE_MS) &&
            (signalOffAt == 0L || t - signalOffAt >= (if (ptValues) PT_SIGNAL_MS else SIGNAL_MS)) && (guideAt == 0L || t - guideAt >= GUIDE_MS)
    }

    /** 지금이 편한 때면 그 이름(신호 대기·안정 주행·P), 아니면 null */
    fun comfy(car: CarState): String? {
        fake?.let { (k, until) -> if (now() < until) return k else fake = null }
        return when {
            car.parked -> if (car.doors.values.all { it == 0 } && (doorsClosedAt == 0L || now() - doorsClosedAt >= DOOR_MS)) PARK else null
            atLight(car) -> LIGHT
            cruising(car) -> CRUISE
            else -> null
        }
    }

    /**
     * 운행 서비스가 끝날 때: 소리 엔진이 더는 차 신호를 받지 않는 이 판정(멈춘 값)을 보지 않고 제 판정으로 돌아가게 하고,
     * 끝난 서비스(guide가 붙잡은)를 프로세스에 남기지 않는다
     */
    fun release() { if (live === this) live = null }

    /** 시험: ms 동안 이 편한 때(신호 대기·안정 주행)로 본다. null이면 끈다 */
    fun pretend(kind: String?, ms: Long = 60_000) {
        fake = kind?.let { it to now() + ms }
        Prend.log("· 시험: 편한 때 ${kind?.let { "$it 흉내 ${ms / 1000}초" } ?: "흉내 끔"}")
    }

    /** 시험: 지금 판정과 쌓인 값 */
    fun dump(car: CarState) {
        val t = now()
        Prend.log("· 시험: 편한 때 ${comfy(car) ?: "아님"} · 신호 대기 ${if (stopSince > 0) "${(t - stopSince) / 1000}초째" else "아님"}" +
            " · 안정 주행 ${if (cruiseSince > 0) "${(t - cruiseSince) / 1000}초째(띠 ${bandMin.toInt()}~${bandMax.toInt()}km/h)" else "아님"}" +
            " · 핸들 ${car.steer?.toInt()?.let { "$it°" } ?: "모름"} · 막는 까닭 ${block(car, starting = true) ?: "없음"}" +
            " · 큰길 증거 ${if (fastEnd > 0) "${(t - fastEnd) / 1000}초 전" else "없음"} · 제한속도 ${speedLimit() ?: "모름"}")
    }

    companion object {
        /** 지금 운행 서비스의 판정(같은 프로세스의 소리 엔진이 신호 대기를 볼 때 쓴다). 없으면 null */
        @Volatile var live: Timing? = null
            private set

        const val LIGHT = "신호 대기"
        const val CRUISE = "안정 주행"
        const val PARK = "P"

        private const val TALK_GAP = 20_000L
        private const val TALK_GAP_URGENT = 5_000L
        /** 별첨 2 §2-1의 값 */
        private const val LIGHT_MS = 5_000L
        private const val CRUISE_KMH = 30f
        private const val CRUISE_MS = 20_000L
        private const val BAND_KMH = 5f
        private const val STEER_DEG = 10f
        private const val SIGNAL_MS = 10_000L
        private const val GUIDE_MS = 10_000L
        /** 길 안내 음성을 1초마다 보므로, 마지막으로 본 지 이만큼 안이면 아직 나오는 중으로 본다 */
        private const val GUIDE_NOW_MS = 1_500L
        /** 다른 음성 비서의 말이 끝난 뒤 새 말을 꺼내지 않는 시간, 이보다 오래 이어지는 음성 비서 소리는 말로 보지 않는다(제안값) */
        private const val ASSIST_MS = 5_000L
        private const val ASSIST_MAX_MS = 60_000L
        private const val DOOR_MS = 10_000L
        /** 급가속·급제동 뒤 쉬는 시간(V6) */
        private const val HARD_MS = 10_000L
        private const val PT_HARD_MS = 4_000L
        /** 큰길 증거(제안값): 시속 30km 넘게 10초, 선 때로부터 90초 안 */
        private const val FAST_KMH = 30f
        private const val FAST_MS = 10_000L
        private const val MAIN_MS = 90_000L
        private const val NARROW_LIMIT = 30
        /** 후진 뒤 1분, 출발 뒤 180초(V1, 시연 값 10초), 신호에서 출발한 뒤 5초(V5) */
        private const val REV_MS = 60_000L
        private const val DEPART_MS = 180_000L
        private const val PT_DEPART_MS = 10_000L
        private const val LEFT_MS = 5_000L
        private const val BRAKE_OFF_MS = 5_000L

        /**
         * 시연 값(별첨 v9 시연과 PT §7 '시연 모드 값 표', 발표자 화면에 밝힌다): 안정 주행 판정 20초 → 5초, 깜빡이를 끈 뒤 10초 → 2초.
         * 디버그 빌드의 시연 패널만 켠다(운행 서비스가 바뀌어도 이어지게 여기 둔다)
         */
        @Volatile var ptValues = false
            set(v) { field = v; Prend.log("· 시연 값 ${if (v) "켬(안정 주행 5초, 깜빡이 뒤 2초)" else "끔(제품 값)"}") }
        private const val PT_CRUISE_MS = 5_000L
        private const val PT_SIGNAL_MS = 2_000L
    }
}
