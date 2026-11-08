package app.prend.ride

import android.content.SharedPreferences
import android.os.SystemClock
import app.prend.Prend
import app.prend.car.CarState
import app.prend.car.Navi
import app.prend.ui.Ask
import app.prend.ui.Scene
import app.prend.ui.Stage
import android.car.VehicleGear

/**
 * 고속도로 위에서 P에 넣으면 대피부터(11/02, 검토-방향노인초보 10-2·3-7 자동). 고장·펑크·기름 떨어짐으로 갓길에 서는 일은 경력과 상관없이 온다.
 * 고속도로 2차 사고 치사율은 일반 사고의 약 5배(기획안 2장, 한국도로공사).
 * - 조건(모두): 고속도로 판단이 '확실'이거나 '애매'(RestAreas.judge, 검토 11/07 멋대로 판단: 전에는 노선 선에서 150m 안이면 방음벽 옆 아파트에서도 떴다.
 *   도로 등급이 다른 길이라고 한 '애매'는 띄우지 않는다), 3분 안에 시속 60을 넘겨 달렸음, 혹시 사고가 지켜보는 중이 아님,
 *   막히는 길이 아님, 목적지 도착 10분 안이 아님, 가장 가까운 졸음쉼터·휴게소가 0.6km 넘게 떨어짐, [괜찮아요]를 두 번 받은 자리(200m 안)가 아님, 그 상태에서 P.
 * - [괜찮아요]를 받은 자리는 좌표가 아니라 고속도로 이정(노선·km)으로 이 차 안에만 다섯 곳까지 둔다(집 앞처럼 늘 서는 곳에서 되풀이하지 않게, 검토 11/07).
 *   [고장 났어요]면 그 자리를 지우고, [모두 잊기]로 모두 지운다. 시험 위치(geo_·시연)에서 받은 자리는 저장하지 않고 메모리에만 둔다
 * - 하는 일: 카드 "고속도로 위에 서셨어요 / 비상등을 켜고 차 뒤 말고 가드레일 밖으로 · 견인은 한국도로공사 1588-2504 [고장 났어요][괜찮아요]"와
 *   목소리 한 줄(e_hstop, 11/05: 휴게소 좌표가 들어와 휴게소에서는 뜨지 않으니 목소리를 낸다). [고장 났어요]면 고장 안내(고속도로 걸음)로
 * - 한 번 선 동안 한 번. 대리 운행·급할 때 화면이면 하지 않는다. 카드가 떠 있는 동안 급할 때가 되면 카드와 미뤄 둔 목소리를 함께 거둔다
 *   (11/06: 미뤄 둔 한마디가 사고 안내의 "괜찮으세요?" 위로 나와 그 안내를 멈췄고, 급할 때를 닫으면 지난 카드가 다시 보였다)
 * - 말 차례는 거치지 않고(그 자리의 안전 카드), 먼저 꺼낸 것 한 운행 여섯 번에 세기만 한다(ride/Floor)
 */
class HighwayStop(
    private val navi: Navi?,
    /** 고속도로 판단(RideService.highwayCall: 확실·애매·아님과 까닭) */
    private val highway: () -> HighwayCall,
    private val restKm: () -> Double?,
    private val jam: (CarState) -> Boolean,
    private val bumpWatching: () -> Boolean,
    private val floor: Floor? = null,
    /** [괜찮아요]를 받은 자리(없으면 메모리에만) */
    private val prefs: SharedPreferences? = null,
    /** 카드와 함께 목소리 한 줄(11/05: 휴게소 자료가 들어와 휴게소에서는 뜨지 않으니 목소리를 낸다). 말했으면 true */
    private val voice: () -> Boolean = { false },
) {
    private val speeds = ArrayDeque<Pair<Long, Float>>()
    /** 마지막으로 시속 60을 넘긴 때와 마지막으로 움직이던(시속 5 넘게) 때(고속도로 판단의 근거, cameFast) */
    @Volatile private var fastAt = 0L
    @Volatile private var movingAt = 0L
    /** 카드를 띄운 자리(노선 무리, 이정 km, 시험 위치인가). [괜찮아요]면 이 자리를 센다 */
    private var cardSpot: Triple<String, Double, Boolean>? = null
    /** 시험 위치에서 [괜찮아요]를 받은 자리(노선 무리, 이정 km, 횟수. 저장하지 않는다) */
    private val testSpots = mutableListOf<Triple<String, Double, Int>>()
    private var lastGear = -1
    private var cardAt = 0L
    /** 혹시 사고에 비켜 선 정차의 때. 혹시 사고가 묻지 않고 손을 놓거나 "아니에요"로 끝나면 다시 본다(검토 11/05: 전에는 끝내 카드가 없었다) */
    private var deferredAt = 0L
    /** 다른 카드(화장실 등)가 떠 있어 띄우지 못한 정차의 때. 같은 정차가 이어지는 3분 안에 그 카드가 걷히면 한 번 더 본다(검토 11/07) */
    private var busyAt = 0L
    /** 말하는 중이거나 [프렌드]로 듣는 중이라 미룬 목소리(카드가 떠 있는 동안 한 번) */
    private var voicePending = false

    init { Stage.sideAsks["HSTOP"] = ::answer }

    private fun now() = SystemClock.elapsedRealtime()

    /** 1초마다 */
    fun tick(car: CarState) {
        val t = now()
        val ui = Stage.ui.value
        if (ui.ask?.id == "HSTOP" && (!car.parked || t - cardAt > CARD_MS || ui.scene.isE)) { Stage.ask(null); voicePending = false; Prend.log("· 고속도로 정차 카드 거둠${if (ui.scene.isE) "(급할 때)" else ""}") }
        if (voicePending && ui.scene.isE) { voicePending = false; Prend.log("· 고속도로 정차: 급할 때라 미뤘던 목소리 버림") }
        if (voicePending && ui.ask?.id == "HSTOP" && car.parked && ui.listen == null && !ui.scene.isE && voice()) { voicePending = false; Prend.log("· 고속도로 정차: 미뤘던 목소리") }
        speeds.addLast(t to car.speedKmh)
        while (speeds.isNotEmpty() && t - speeds.first().first > 3 * 60_000) speeds.removeFirst()
        if (car.speedKmh >= FAST_KMH) fastAt = t
        if (car.speedKmh > MOVING_KMH) movingAt = t
        val gear = car.gear
        val toPark = gear == VehicleGear.GEAR_PARK && lastGear != -1 && lastGear != VehicleGear.GEAR_PARK
        lastGear = gear
        if (!car.parked) { deferredAt = 0L; busyAt = 0L }
        val again = deferredAt > 0 && !bumpWatching() && t - deferredAt < 3 * 60_000
        val retry = busyAt > 0 && ui.ask == null && t - busyAt < 3 * 60_000
        if (!toPark && !again && !retry) return
        if (again) { deferredAt = 0L; Prend.log("· 고속도로에서 P: 혹시 사고가 손을 놓아 다시 봄") }
        if (retry) { busyAt = 0L; Prend.log("· 고속도로에서 P: 떠 있던 카드가 걷혀 다시 봄") }
        val hw = highway()
        val here = hw.spot
        val test = navi?.pretending == true
        val why = when {
            // 고속도로가 아니면 조용히 끝(판단과 까닭은 RideService가 바뀔 때마다 남긴다)
            hw.state == HighwayState.NO -> return
            ui.scene.isE || ui.daeri -> "급할 때·대리 운행"
            // 도로 등급이 다른 길이라고 했으면 속도·방향이 맞아도 카드는 띄우지 않는다(119 위치 줄에만 둘 다 보인다)
            hw.state == HighwayState.MAYBE && hw.grade == false -> "도로 등급이 고속도로가 아님"
            speeds.none { it.second >= 60f } -> "3분 안에 시속 60을 넘지 않음"
            here != null && quiet(here.group, here.km, test) -> "[괜찮아요]를 두 번 받은 자리"
            // 혹시 사고가 실제로 지켜보는 동안만 비켜 서고, 손을 놓으면 다시 본다
            bumpWatching() -> { deferredAt = t; "급제동 뒤(혹시 사고가 맡음, 손을 놓으면 다시 봄)" }
            jam(car) -> "막히는 길"
            navi?.arrivedAt?.let { it > 0 && System.currentTimeMillis() - it < 10 * 60_000L } == true -> "목적지 도착"
            // 쉼터·휴게소 점에서 0.6km 안(전에는 1km라 휴게소 근처 본선 갓길도 걸렀다, 검토 11/05)
            (restKm() ?: 99.0) <= REST_KM -> "졸음쉼터·휴게소 안"
            ui.ask != null -> { if (busyAt == 0L) busyAt = t; "다른 카드가 떠 있음(걷히면 다시 봄)" }
            else -> null
        }
        if (why != null) { Prend.log("· 고속도로에서 P: 카드 띄우지 않음($why)"); return }
        cardAt = t
        cardSpot = here?.let { Triple(it.group, it.km, test) }
        floor?.shown("고속도로 정차")
        Stage.ask(Ask("HSTOP", "고속도로 위에 서셨어요", "비상등을 켜고 차 뒤 말고 가드레일 밖으로 · 견인은 한국도로공사 1588-2504", "고장 났어요", "괜찮아요"))
        // "고속도로 위에 서셨어요. 내리실 거면 차 뒤로 가지 말고, 가드레일 밖으로 나가세요."(내릴지 모르니 조건을 붙인 말).
        // [프렌드]로 듣는 중이면 마이크에 섞이지 않게 끝난 뒤에, 다른 말이 나오는 중이면 그 뒤에(카드가 떠 있는 동안)
        val spoke = ui.listen == null && voice()
        voicePending = !spoke
        Prend.log("◆ 고속도로에서 P: 대피 카드(고속도로 ${hw.state.label})${if (spoke) " + 목소리" else "(목소리는 뒤에)"}")
    }

    private fun answer(yes: Boolean) {
        voicePending = false
        Stage.ask(null)
        // [괜찮아요]는 그 자리를 세고, [고장 났어요]는 그 자리를 지운다(두 번 이어 [괜찮아요]일 때만 그 자리에서 다시 띄우지 않는다)
        val n = cardSpot?.let { (group, km, test) -> count(group, km, test, if (yes) null else 1) }
        cardSpot = null
        Prend.log("· 고속도로 정차 카드: ${if (yes) "고장 났어요" else "괜찮아요"}${if (!yes && n != null) "(이 자리 ${n}번째${if (n >= QUIET_N) ", 이 자리에서는 다시 띄우지 않음" else ""})" else ""}")
        if (yes) Stage.breakdown()
    }

    /**
     * 고속도로 속도로 달려와 여기 섰나(고속도로 판단의 근거, 검토 11/07 멋대로 판단): 마지막으로 움직이던 때 3분 안에 시속 60을 넘겼고, 선 지 30분이 지나지 않았다.
     * 달리는 중이면 지금부터 3분 안과 같다. 선 때를 기준으로 세어, 사고 안내 도중 3분이 지났다고 119에 말할 위치 줄이 바뀌지 않게 한다
     */
    fun cameFast(): Boolean { val t = now(); return fastAt > 0 && movingAt - fastAt <= FAST_MS && t - movingAt <= HOLD_MS }

    /**
     * 저장해 둔 자리들(노선 무리, 이정 km, [괜찮아요] 횟수). "노선 무리,이정,횟수;…".
     * 좌표 대신 고속도로 이정으로 둔다(카드는 고속도로 노선 선 가까이에서만 뜨니 이정으로 같은 자리를 가를 수 있다. 집 좌표를 이 차에 남기지 않는다)
     */
    private fun saved(): List<Triple<String, Double, Int>> = prefs?.getString("spots", null).orEmpty().split(';').mapNotNull { s ->
        val f = s.split(',')
        val g = f.getOrNull(0)?.takeIf { it.isNotBlank() }; val km = f.getOrNull(1)?.toDoubleOrNull(); val n = f.getOrNull(2)?.toIntOrNull()
        if (g == null || km == null || n == null) null else Triple(g, km, n)
    }

    /** 같은 노선에서 이정이 200m 안이면 같은 자리 */
    private fun near(a: Triple<String, Double, Int>, group: String, km: Double) = a.first == group && kotlin.math.abs(a.second - km) <= SPOT_KM

    /** 여기서 [괜찮아요]를 두 번 받았나 */
    private fun quiet(group: String, km: Double, test: Boolean) = (if (test) testSpots else saved()).any { it.third >= QUIET_N && near(it, group, km) }

    /** 이 자리의 [괜찮아요]를 add만큼 센다(null이면 지운다). 센 뒤의 횟수 */
    private fun count(group: String, km: Double, test: Boolean, add: Int?): Int {
        val list = (if (test) testSpots.toList() else saved()).toMutableList()
        val i = list.indexOfFirst { near(it, group, km) }
        val n = if (add == null) 0 else (list.getOrNull(i)?.third ?: 0) + add
        if (i >= 0) list.removeAt(i)
        // 새로 센 자리를 뒤에 두고, 넘치면 오래된 것부터 버린다. 노선 이름에는 쉼표·쌍반점이 없다(쉼터 자료가 쉼표로 나뉜 표)
        if (n > 0) list.add(Triple(group, Math.round(km * 100) / 100.0, n))
        val keep = list.takeLast(SPOT_MAX)
        if (test) { testSpots.clear(); testSpots.addAll(keep) }
        else prefs?.edit()?.putString("spots", keep.joinToString(";") { "${it.first},${it.second},${it.third}" })?.apply()
        return n
    }

    /** [모두 잊기]: [괜찮아요]를 받은 자리를 모두 지운다 */
    fun forget() { prefs?.edit()?.remove("spots")?.apply(); testSpots.clear(); Prend.log("· 고속도로 정차: 모두 잊기") }

    /** 시험·시연: 시험 위치에서 받은 자리만 지운다(리허설에서 누른 [괜찮아요]가 시연 장면의 카드를 막지 않게) */
    fun forgetTest() { testSpots.clear() }

    companion object {
        const val CARD_MS = 90_000L; const val REST_KM = 0.6
        /** 고속도로 속도, 움직이는 중으로 보는 속도(km/h) */
        const val FAST_KMH = 60f; const val MOVING_KMH = 5f
        /** 움직이던 마지막 때부터 이만큼 앞까지 고속도로 속도였으면 고속도로 속도로 달려온 것, 선 뒤 이만큼까지 그 근거를 쓴다 */
        const val FAST_MS = 3 * 60_000L; const val HOLD_MS = 30 * 60_000L
        /** [괜찮아요]를 받은 자리: 같은 자리로 보는 이정 차이(km), 다시 띄우지 않는 횟수, 두는 곳 수 */
        const val SPOT_KM = 0.2; const val QUIET_N = 2; const val SPOT_MAX = 5
    }
}
