package app.prend.ride

import android.os.SystemClock
import app.prend.Prend
import app.prend.car.CarState
import app.prend.car.Navi
import app.prend.net.Relay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 골목 판정(11/01 사용자 "티맵을 쓰면 전국에 있는 골목 다 알 수 있는 거 아니야?", "위치를 보내지 않는다를 수정하면 되지").
 * 차가 서는 순간(D·시속 1km 아래, 브레이크는 보지 않는다) 지나온 자리(내비 현재 위치, 최대 100개)를 중계 서버를 거쳐 TMAP '이동한 도로 찾기'·'가까운 도로 찾기'에
 * 보내 지금 길의 원도로등급을 받는다(relay /road). 9 이면도로·11 단지내도로·12 이면도로2(세도로)면 골목, 0~7(고속국도~주요도로3)이면 큰길,
 * 8 기타도로나 모르면 차 신호(큰길 증거)로 가른다(ride/Timing). 지나온 길의 마지막 다섯 점 가운데 많은 쪽을 따르고, 맞춘 점이 없으면 마지막 한 점.
 * 판정은 그 정차 동안만 쓰고(시속 10km를 넘거나 D가 아니면 지움) 기억에 남기지 않는다. 기록에는 등급만 남기고 좌표·도로명은 남기지 않는다.
 * 통신이 끊기거나 4초 안에 답이 없으면 모름(차 신호로 돌아간다).
 * 위치 보내기를 끈 사람(ride/GeoShare, 사용자 결정 11/06)은 어느 자리도 보내지 않고 늘 차 신호로 가른다(골목 목적지의 한 점도 보내지 않는다)
 * 같은 답의 고속국도(0) 여부는 고속도로 판단의 첫째 근거로도 쓴다(highwayAt, 검토 11/07 멋대로 판단). 물음을 더 내지 않는다
 */
class Road(private val navi: Navi?, private val scope: CoroutineScope) {

    enum class Kind(val label: String) { ALLEY("골목"), MAIN("큰길") }

    /** 이번 정차의 판정(모르면 null) */
    @Volatile private var kind: Kind? = null
    private var askedAt = 0L
    private var stopped = false
    /** 이번 정차에 낸 물음의 번호(답이 늦게 와서 다음 정차의 판정을 덮지 않게) */
    private var asking = 0
    private var fake: Kind? = null
    private var emptyLogged = false
    /** 위치 보내기를 꺼서 묻지 않았다고 기록에 남겼나(정차마다 남기지 않게) */
    private var offLogged = false

    /** 지금 정차의 판정(시험 흉내가 있으면 그것) */
    fun now(): Kind? = fake ?: kind

    /**
     * 마지막 등급 답이 고속국도(0)였나와 그 정차의 때·자리(고속도로 판단의 근거, 검토 11/07 멋대로 판단).
     * 골목 판정과 같은 물음의 답을 함께 쓴다(TMAP을 더 부르지 않는다). 메모리에만 두고 기록에는 등급만 남긴다
     */
    private data class Grade(val at: Long, val lat: Double, val lon: Double, val highway: Boolean)
    @Volatile private var grade: Grade? = null
    /** 시험: 도로 등급을 고속국도로 흉내 내는 중인가(road_highway) */
    @Volatile private var fakeHighway = false

    /**
     * 이 자리의 도로 등급이 고속국도인가: true 고속국도, false 다른 길, null 모름(위치 보내기를 꺼 묻지 않았거나, 답을 받은 자리에서 멀어졌거나 오래됨).
     * 답을 받은 자리에서 40m 안이면(그대로 서 있음) 시간이 지나도 쓰고, 300m 안이면 10분까지 쓴다(막히는 길을 조금씩 굴러감). 시험 흉내가 있으면 그것
     */
    fun highwayAt(lat: Double, lon: Double): Boolean? {
        if (fakeHighway) return true
        if (fake != null) return false
        val g = grade ?: return null
        val d = Together.meters(g.lat, g.lon, lat, lon) ?: return null
        val fresh = SystemClock.elapsedRealtime() - g.at < HISTORY_MS
        return g.highway.takeIf { d < SAME_SPOT_M || d < MAIN_SPOT_M && fresh }
    }

    /** 최근 판정(받은 때, 골목·큰길, 단지 안 도로인가) 10분치. 정차 판정이 지워진 뒤에도(출발·R) '방금 골목이었나'를 보려고 둔다. 좌표는 넣지 않는다 */
    private val history = ArrayDeque<Triple<Long, Kind, Boolean>>()

    /** 최근 ms 안의 판정들(오래된 것부터). 시험 흉내가 있으면 그것 하나 */
    fun recent(ms: Long): List<Kind> {
        fake?.let { return listOf(it) }
        val t = SystemClock.elapsedRealtime()
        synchronized(history) { return history.filter { t - it.first <= ms }.map { it.second } }
    }

    /**
     * ms 안의 마지막 판정이 골목이고 단지 안 도로(11)가 아닌가: 이때 R↔D를 오가는 것은 주차보다 돌려 나오기·비켜 주기일 수 있다(ride/Coach).
     * 아파트 단지 안은 주차하러 들어가는 곳이라 넣지 않는다
     */
    fun turningAlley(ms: Long): Boolean {
        if (fake != null) return fake == Kind.ALLEY
        val t = SystemClock.elapsedRealtime()
        val last = synchronized(history) { history.lastOrNull() } ?: return false
        return t - last.first <= ms && last.second == Kind.ALLEY && !last.third
    }

    /** 최근 ms 안의 판정(모름은 빼고) 가운데 골목이 둘 이상이고 절반 넘는가(골목을 천천히 지난 것을 정체로 치지 않으려고). 기록용 "골목 n/m"도 준다 */
    fun mostlyAlley(ms: Long): Pair<Boolean, String> = recent(ms).let { r ->
        val a = r.count { it == Kind.ALLEY }
        ((a >= 2 || fake == Kind.ALLEY) && a * 2 > r.size) to "골목 $a/${r.size}"
    }

    fun onCar(old: CarState, car: CarState) {
        if (car.speedKmh > 10f || !car.driving) { kind = null; asking++ }
        // 브레이크는 보지 않는다: i-Pedal로 서는 전기차는 페달을 밟지 않는다(검토 11/06, ride/Timing의 신호 대기와 같다). 오토홀드도 서는 순간에는 밟는다
        val stop = car.driving && car.speedKmh < 1f
        if (stop && !stopped) ask()
        stopped = stop
    }

    /**
     * 차가 막 섰다: 지나온 자리로 등급을 묻는다(같은 줄에서 조금씩 굴러가 다시 서면 10초 안에는 묻지 않는다).
     * 위치 보내기를 끈 사람(ride/GeoShare)은 묻지 않는다: 판정은 모름으로 두고 차 신호(큰길 증거)로 가른다
     */
    private fun ask() {
        if (!GeoShare.on) { if (!offLogged) { offLogged = true; Prend.log("· 도로 등급: 위치 보내기를 꺼 묻지 않음(차 신호로 가름)") }; return }
        offLogged = false
        val t = SystemClock.elapsedRealtime()
        if (t - askedAt < MIN_GAP_MS && kind != null) return
        // 10초 안에 다시 섰고(R↔D로 돌려 나오는 중 등) 앞 물음의 답이 이미 왔으면 그 판정을 이어 쓴다(R을 거치면 정차 판정이 지워진다)
        val prev = synchronized(history) { history.lastOrNull() }
        if (t - askedAt < MIN_GAP_MS && prev != null && prev.first >= askedAt) { kind = prev.second; return }
        val pts = navi?.trail().orEmpty()
        if (pts.isEmpty()) { if (!emptyLogged) { emptyLogged = true; Prend.log("· 도로 등급: 지나온 자리가 없어 묻지 않음(차 신호로 가름)") }; return }
        emptyLogged = false
        // 지난번 답을 받은 자리에서 40m 안에서 다시 섰으면(막히는 길을 조금씩 굴러감) 묻지 않고 그 판정을 잇는다.
        // TMAP 호출 수를 줄이려고(Free 하루 1,000건). 자리는 메모리에만 둔다
        val here = pts.last()
        val spot = answeredAt
        if (prev != null && spot != null && t - prev.first < HISTORY_MS && (Together.meters(spot.first, spot.second, here.first, here.second) ?: Int.MAX_VALUE) < SAME_SPOT_M) {
            kind = prev.second
            synchronized(history) { history.addLast(Triple(t, prev.second, prev.third)) }
            // 고속도로 판단의 등급 답도 이어 쓴다(받은 자리는 그대로, 때만 새로)
            grade = grade?.copy(at = t)
            askedAt = t
            return
        }
        // 큰길로 판정받은 뒤 300m 안에서 같은 방향으로 다시 섰으면(신호마다 서는 큰길) 묻지 않고 잇는다(11/04 점검: 시내에서 정차마다 TMAP 두 건).
        // 방향이 25° 넘게 바뀌었으면 꺾어서 골목에 들어갔을 수 있어 다시 묻는다. 골목 판정은 잇지 않는다(골목에서 말하지 않는 쪽이 먼저)
        val hd = navi?.last?.heading
        val ah = answeredHeading
        if (prev != null && prev.second == Kind.MAIN && spot != null && hd != null && ah != null && t - prev.first < HISTORY_MS && turned(ah, hd) < MAIN_TURN_DEG &&
            (Together.meters(spot.first, spot.second, here.first, here.second) ?: Int.MAX_VALUE) < MAIN_SPOT_M && sideways(spot, here, ah) < MAIN_SIDE_M) {
            kind = prev.second
            synchronized(history) { history.addLast(Triple(t, prev.second, prev.third)) }
            grade = grade?.copy(at = t)
            askedAt = t
            return
        }
        askedAt = t
        send(pts, "정차")
    }

    /** 마지막으로 등급 답을 받은 물음의 마지막 자리(메모리에만) */
    @Volatile private var answeredAt: Pair<Double, Double>? = null
    /** 그 답을 받을 때의 진행 방향(큰길 판정을 이어 쓸 때 꺾었는지 본다) */
    @Volatile private var answeredHeading: Double? = null

    /** 답한 자리에서 그때 방향으로 그은 선과 지금 자리 사이의 옆 거리(m). 두 번 꺾어 나란한 이면도로(30~80m 옆)에 든 것을 큰길로 잇지 않게 */
    private fun sideways(a: Pair<Double, Double>, b: Pair<Double, Double>, heading: Double): Double {
        val d = (Together.meters(a.first, a.second, b.first, b.second) ?: return Double.MAX_VALUE).toDouble()
        if (d < 15) return 0.0
        val la1 = Math.toRadians(a.first); val la2 = Math.toRadians(b.first); val dl = Math.toRadians(b.second - a.second)
        val brg = Math.toDegrees(Math.atan2(Math.sin(dl) * Math.cos(la2), Math.cos(la1) * Math.sin(la2) - Math.sin(la1) * Math.cos(la2) * Math.cos(dl)))
        return d * Math.abs(Math.sin(Math.toRadians(turned(heading, (brg + 360) % 360))))
    }

    /** 두 방위 사이의 각(0~180°) */
    private fun turned(a: Double, b: Double): Double { val d = Math.abs(a - b) % 360.0; return if (d > 180) 360 - d else d }

    private fun send(pts: List<Pair<Double, Double>>, why: String) {
        if (!GeoShare.on) return Prend.log("· 도로 등급($why): 위치 보내기를 꺼 보내지 않음")
        val id = ++asking
        // 최근 판정에는 답이 온 때가 아니라 물은 때(선 때)로 넣는다: 늦게 온 답이 더 나중 정차의 판정처럼 보이지 않게
        val stopAt = SystemClock.elapsedRealtime()
        // 큰길 판정을 이어 쓸 때 견줄 방향은 선 때의 것(답이 오는 사이 꺾어 골목에 들어가면 그 방향이 적히던 것, 11/04 검토)
        val stopHeading = navi?.last?.heading
        val body = JSONObject().put("pts", JSONArray(pts.takeLast(MAX_PTS).map { (a, o) -> JSONArray().put(round(a)).put(round(o)) }))
        scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            val j = Relay.post("/road", body, waitMs = WAIT_MS)
            val (k, complex, what) = judge(j)
            // 시험 물음은 기록만 한다. 그사이 출발했거나 다음 정차가 됐으면 버린다
            if (why == "시험") return@launch Prend.log("· 도로 등급(시험, ${pts.size}점, ${SystemClock.elapsedRealtime() - t0}ms): $what → ${k?.label ?: "모름"}")
            // 고속도로 판단의 근거(검토 11/07 멋대로 판단): 선 뒤 바로 P에 넣어 늦게 온 답도 그 자리의 등급이다. 더 나중 정차의 답을 덮지만 않게
            highwayOf(j)?.let { hw -> synchronized(history) { if (stopAt >= (grade?.at ?: 0L)) grade = Grade(stopAt, pts.last().first, pts.last().second, hw) } }
            // 늦게 온 답도 최근 판정에는 남긴다(그 자리가 골목이었다는 사실은 그대로다)
            if (k != null) synchronized(history) {
                if (why != "시험" && id == asking) { answeredAt = pts.last(); answeredHeading = stopHeading }
                history.addLast(Triple(stopAt, k, complex))
                while (history.isNotEmpty() && SystemClock.elapsedRealtime() - history.first().first > HISTORY_MS) history.removeFirst()
            }
            if (id != asking) return@launch Prend.log("· 도로 등급($why): 늦게 와서 버림")
            kind = k
            Prend.log("· 도로 등급($why, ${pts.size.coerceAtMost(MAX_PTS)}점, ${SystemClock.elapsedRealtime() - t0}ms): $what → ${k?.label ?: "모름(차 신호로 가름)"}")
        }
    }

    /** TMAP 원도로등급 → 골목·큰길과 단지 안 도로인가(골목 가운데 11이 많거나 마지막 자리가 11). 지나온 길의 마지막 점들 가운데 많은 쪽, 맞춘 점이 없으면 마지막 한 점 */
    private fun judge(j: JSONObject?): Triple<Kind?, Boolean, String> {
        j ?: return Triple(null, false, "답 없음")
        val cats = j.optJSONArray("match")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optInt("cat", -1) } }.orEmpty().filter { it >= 0 }
        val nearObj = j.optJSONObject("near")
        val near = nearObj?.optInt("cat", -1)?.takeIf { it >= 0 }
        val use = cats.ifEmpty { listOfNotNull(near) }
        if (use.isEmpty()) {
            val status = listOfNotNull(j.optJSONObject("match")?.optInt("status", 0), nearObj?.optInt("status", 0)).firstOrNull { it > 0 }
            // TMAP이 거절한 까닭(오류 코드만, 예: INVALID_API_KEY)
            val code = Regex("\"code\"\\s*:\\s*\"([A-Z_]+)\"").find(j.optJSONObject("match")?.optString("why").orEmpty() + nearObj?.optString("why").orEmpty())?.groupValues?.get(1)
            return Triple(null, false, "등급 없음${status?.let { " (TMAP $it${code?.let { c -> " $c" } ?: ""})" } ?: ""}")
        }
        val alley = use.count { it in ALLEY_CATS }
        val main = use.count { it in MAIN_CATS }
        // 반반이면 지금 선 마지막 자리(가까운 도로 찾기)를 따른다
        val k = when {
            alley > main -> Kind.ALLEY
            main > alley -> Kind.MAIN
            near in ALLEY_CATS -> Kind.ALLEY
            near in MAIN_CATS -> Kind.MAIN
            else -> null
        }
        val complex = k == Kind.ALLEY && (use.count { it == COMPLEX_CAT } * 2 > alley || near == COMPLEX_CAT)
        return Triple(k, complex, "등급 ${use.joinToString(",")}${near?.let { " · 마지막 자리 $it" } ?: ""}${nearObj?.optInt("lane", 0)?.takeIf { it > 0 }?.let { " · ${it}차로" } ?: ""}")
    }

    /**
     * 등급 답이 고속국도인가(고속도로 판단, 검토 11/07 멋대로 판단). judge()와 같은 점들(지나온 길의 마지막 점들, 없으면 마지막 한 점)에서
     * 0이 절반 넘으면 true, 반반이면 지금 선 마지막 자리를 따르고, 등급이 없으면 null(모름)
     */
    private fun highwayOf(j: JSONObject?): Boolean? {
        j ?: return null
        val cats = j.optJSONArray("match")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optInt("cat", -1) } }.orEmpty().filter { it >= 0 }
        val near = j.optJSONObject("near")?.optInt("cat", -1)?.takeIf { it >= 0 }
        val use = cats.ifEmpty { listOfNotNull(near) }
        if (use.isEmpty()) return null
        val hw = use.count { it == HIGHWAY_CAT }
        return hw * 2 > use.size || hw * 2 == use.size && near == HIGHWAY_CAT
    }

    /**
     * 한 자리(목적지·주차장)의 도로를 정차 판정과 따로 묻는다(ride/AlleyDest). 가까운 도로 찾기의 등급과 차로 수로
     * 9 이면도로·12 세도로이고 1차로 이하면 골목, 0~7이면 큰길, 그 밖(11 단지 안·8 기타·모름)은 null. 남기지 않는다
     */
    fun checkSpot(lat: Double, lon: Double, done: (Kind?, String) -> Unit) {
        if (!GeoShare.on) { done(null, "위치 보내기 꺼짐"); return }
        val body =JSONObject().put("pts", JSONArray().put(JSONArray().put(round(lat)).put(round(lon))))
        scope.launch {
            val near = Relay.post("/road", body, waitMs = WAIT_MS)?.optJSONObject("near")
            val cat = near?.optInt("cat", -1)?.takeIf { it >= 0 }
            val lane = near?.optInt("lane", 0) ?: 0
            val k = when {
                cat == null -> null
                cat in MAIN_CATS -> Kind.MAIN
                (cat == 9 || cat == 12) && lane <= 1 -> Kind.ALLEY
                else -> null
            }
            done(k, "등급 ${cat ?: "-"} · ${lane}차로")
        }
    }

    /**
     * 한 자리의 도로 이름(급할 때 119에 말할 위치, 검토 11/07·별첨 C 16): 가까운 도로 찾기가 주는 이름("정조로"). 위치 보내기를 끈 사람은 묻지 않는다.
     * 이름은 화면에만 쓰고 기록에 남기지 않는다(길이만). 못 받으면 null, 이름 없는 길이면 ""
     */
    fun nameAt(lat: Double, lon: Double, done: (String?) -> Unit) {
        if (!GeoShare.on) { done(null); return }
        val body = JSONObject().put("name", true).put("pts", JSONArray().put(JSONArray().put(round(lat)).put(round(lon))))
        scope.launch {
            val got = Relay.post("/road", body, waitMs = WAIT_MS)?.optJSONObject("near")?.optString("name", "")?.trim()?.takeIf { it.isNotEmpty() }
            // 이름 없는 작은 길은 TMAP이 이름 대신 "일반도로" 같은 종류를 준다(11/08 실제 서버 시험). 119에 읽어 줄 이름이 아니므로 ""(이 자리는 이름 없음, 다시 묻지 않음)
            val name = got?.let { if (it.replace(" ", "") in KIND_NAMES) "" else it }
            Prend.log("· 위치 줄: 도로 이름 ${if (name == null) "못 받음" else if (name.isEmpty()) "종류만 옴(이름 없는 길)" else "받음(${name.length}자)"}")
            done(name)
        }
    }

    /** 시험: 이 판정으로 본다(null이면 끈다). 고속국도 흉내도 함께 끈다 */
    fun pretend(k: Kind?) { fake = k; fakeHighway = false; Prend.log("· 시험: 도로 등급 ${k?.label ?: "흉내 끔"}") }

    /** 시험: 도로 등급을 고속국도(큰길)로 본다(고속도로 판단의 '확실', road_off로 끈다) */
    fun pretendHighway() { fake = Kind.MAIN; fakeHighway = true; Prend.log("· 시험: 도로 등급 고속국도") }

    /** 시험: 두 자리 사이를 열 점으로 이어 지나온 길처럼 묻는다(좌표는 기록에 남기지 않는다) */
    fun probe(lat1: Double, lon1: Double, lat2: Double, lon2: Double) {
        val pts = (0..9).map { i -> (lat1 + (lat2 - lat1) * i / 9) to (lon1 + (lon2 - lon1) * i / 9) }
        send(pts, "시험")
    }

    private fun round(v: Double) = Math.round(v * 1e6) / 1e6

    companion object {
        /** 원도로등급(TMAP 이동한 도로 찾기 문서): 0 고속국도, 1 도시고속화도로, 2 국도, 3 국가지원지방도, 4 지방도, 5~7 주요도로1~3 / 9 이면도로, 11 단지내도로, 12 이면도로2(세도로) */
        val MAIN_CATS = setOf(0, 1, 2, 3, 4, 5, 6, 7)
        val ALLEY_CATS = setOf(9, 11, 12)
        const val COMPLEX_CAT = 11
        /** 도로 이름 자리에 오는 길의 종류(이름이 아니다). 빈칸을 뺀 모양 */
        val KIND_NAMES = setOf("일반도로", "이면도로", "세도로", "단지내도로", "도로", "골목길", "국도", "지방도")
        /** 고속국도(고속도로 판단에 쓴다) */
        const val HIGHWAY_CAT = 0
        const val MAX_PTS = 100
        const val WAIT_MS = 4_000
        const val MIN_GAP_MS = 10_000L
        const val HISTORY_MS = 10 * 60_000L
        const val SAME_SPOT_M = 40
        const val MAIN_SPOT_M = 300
        const val MAIN_TURN_DEG = 25.0
        const val MAIN_SIDE_M = 20.0
    }
}
