package app.prend.ride

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.prend.Prend
import app.prend.net.Relay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * 화장실을 들를 곳 한 곳. km는 고속도로면 이정 차이(길을 따라 간 거리), 일반 도로면 곧은 거리.
 * open24는 중계 서버(TMAP)가 24시간 영업이라고 알려 준 곳만 true, 아니라고 하면 false, 모르면 null
 */
data class ToiletPick(
    val name: String, val lat: Double, val lon: Double, val kind: Toilets.Kind, val km: Double,
    val highway: Boolean, val nursing: Boolean = false, val open24: Boolean? = null, val address: String = "",
    /** 고속도로 쉼터면 그 노선 무리·이정·진행 방향 부호(넣기 직전에 지나쳤는지 본다) */
    val group: String = "", val areaKm: Double = 0.0, val sign: Int = 0,
)

/**
 * "화장실 급해"·"애가 쉬 마렵대"(사용자 11/07, 조사 기획/자료조사/R19): 앞쪽에서 들르기 쉬운 화장실 한 곳을 고른다.
 * - 고속도로 위면 앞쪽 휴게소·졸음쉼터(졸음쉼터 설치 및 관리지침이 화장실을 기본시설로 둔다). 같은 노선·같은 방향 앞쪽 결과끼리만 견준다.
 *   급한 일이라 휴게소가 졸음쉼터보다 2km 안쪽으로만 더 멀 때 휴게소를 고른다(쉬어 가기는 5km, 휴게소엔 수유실이 있는 곳이 많다).
 *   고속도로 선분과 진행 방향이 나란할 때만 고속도로로 본다(곁의 일반 도로·나들목 램프·고가 아래 교차로를 고속도로로 보지 않게).
 *   도로 점 자료가 없는 고속도로 구간에서 시속 90 넘게 달리면 길가 주유소를 고르지 않는다(들어갈 수 없다). 쉼터 안에 서 있으면 그곳 화장실을 쓰라고만.
 *   대답을 기다리는 동안 지나치지 않게 지금 속도로 25초 거리(0.5km 이상) 넘게 앞인 쉼터만 고른다
 * - 일반 도로면 주유소: 등록할 때 공중화장실을 한 곳 이상 두어야 한다(석유사업법 시행령 별표 2. 공영 노외주차장 안이거나 20m 안에 국가·지자체 공중화장실이 있으면
 *   두지 않아도 되는데, 그때는 그 곁의 화장실을 쓰면 된다). LPG 충전소는 화장실을 두어야 한다는 근거를 찾지 못해 고르지 않는다(주유소를 함께 하는 곳은 주유소로 남는다).
 *   앱에 넣은 공공데이터(rest/gas_stations.csv, 인터넷 없이)에서 진행 방향 ±60° 앞쪽 6km 안. 고속도로 휴게소 안 주유소(휴게소 400m 안, 이름에 '휴게소'·'상행' 등)는 뺀다.
 *   1km 안에서는 길 왼쪽으로 40m 넘게 비켜난 곳을 중앙선 건너편으로 보고 1.6배로 친다(그 너머는 길이 굽어 쪽을 믿을 수 없어 같게)
 * - 위치 보내기를 켠 사람(ride/GeoShare)은 중계 서버(TMAP 주변 주유소)로 빠진 곳을 채운다(공공데이터에 좌표가 있는 주유소는 전국의 절반쯤).
 *   2.5초 안에 답이 없으면 앱 안 자료로만 고른다. 밤(22~6시)에는 24시간이 아니라고 알려진 곳을 뒤로 미룬다
 * - 위치는 내비가 몇 초마다 알려 주는 값이라, 그 사이 달린 만큼 앞당겨 고른다(일반 도로는 진행 방향으로, 고속도로는 이정으로: 굽은 길에서 곧게 앞당기면 도로를 벗어난다).
 *   지금 속도로 20초 안에 닿을 곳은 대답을 기다리는 사이 지나칠 곳으로 본다
 * 공중화장실 공공데이터는 2025년 2월부터 좌표가 빠져 쓰지 않는다(R19). 위치·고른 곳 이름은 기록에 남기지 않는다
 */
class Toilets(
    private val context: Context,
    /**
     * 운행 서비스의 고속도로 판단(RestAreas.judge: 도로 등급·고속도로 속도로 달려와 섬·방향). 주지 않으면 null(11/08 오류 점검 F4).
     * 확실이면 서 있어도 고속도로로, 아님(도로 등급이 다른 길)이면 노선 선 가까이여도 고속도로로 보지 않는다
     */
    private val highway: () -> HighwayCall? = { null },
    private val rests: () -> RestAreas?,
) {

    enum class Kind { STOP, NAP, GAS }

    private data class Spot(val name: String, val lat: Double, val lon: Double, val kind: Kind, val open24: Boolean? = null)

    @Volatile private var gas: List<Spot>? = null
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    /** 운행 서비스가 켜질 때 배경에서 미리 읽는다(약 0.36MB, 그중 주유소 5천여 곳만 남긴다) */
    fun preload() { io.launch { load() } }

    val count get() = gas?.size ?: 0

    private fun load(): List<Spot> = gas ?: synchronized(this) { gas ?: read().also { gas = it } }

    private fun read(): List<Spot> = runCatching {
        context.assets.open("rest/gas_stations.csv").bufferedReader().readLines().drop(1).mapNotNull { line ->
            val f = line.split(',')
            val lat = f.getOrNull(1)?.toDoubleOrNull()
            val lon = f.getOrNull(2)?.toDoubleOrNull()
            if (f.size < 4 || lat == null || lon == null || f[3] != "gas" || highwayName(f[0])) null else Spot(clean(f[0]), lat, lon, Kind.GAS)
        }
    }.onFailure { Prend.log("✗ 주유소 자료를 읽지 못함 $it") }.getOrDefault(emptyList())

    /**
     * 지금 자리에서 고른다. done은 메인 스레드에서 (고른 곳, 어디서 골랐는지)로 불린다.
     * ageMs는 위치를 받은 뒤 지난 시간, remoteOk면(위치 보내기 켬) 고속도로 밖에서 중계 서버도 함께 본다
     */
    fun find(lat0: Double, lon0: Double, heading: Double, ageMs: Long, speedKmh: Float, parked: Boolean, remoteOk: Boolean, done: (ToiletPick?, String) -> Unit) {
        val r = rests()
        // 고속도로 휴게소·졸음쉼터 안에 서 있다
        if (parked && r != null && (r.nearestKm(lat0, lon0) ?: 99.0) < IN_REST_KM) { done(null, IN_REST); return }
        // 고속도로 판정은 받은 자리 그대로. 서 있거나 천천히 가도 방향이 노선과 나란해야 고속도로로 본다: 전에는 시속 10 아래면 방향을 보지 않아
        // 고가 아래·곁의 일반 도로 빨간불에서 들어갈 수 없는 휴게소를 골랐다. 방향을 믿기 어려운 정체는 고속도로 판단이 확실(도로 등급·고속도로 속도로 달려와 섬)일 때 그대로 고속도로로.
        // 판단이 아님(도로 등급이 다른 길)이면 나란해도 고속도로로 보지 않는다(11/08 오류 점검 F4)
        val spot = r?.match(lat0, lon0, heading)
        val call = highway()?.state
        val parallel = spot != null && RestAreas.angle(heading, spot.road).let { it <= ALONG_DEG || it >= 180 - ALONG_DEG }
        val along = spot != null && call != HighwayState.NO && (parallel || speedKmh < 10f && call == HighwayState.SURE)
        if (r != null && spot != null && along) {
            val moved = moved(speedKmh, ageMs) / 1000
            val at = spot.copy(km = spot.km + spot.sign * moved)
            val minKm = maxOf(HW_MIN_KM, speedKmh / 3600.0 * HW_PASS_S)
            val p = r.next(lat0, lon0, heading, spot = at, preferKm = STOP_PREFER_KM, routeOnly = true, minKm = minKm)
            done(p?.let { ToiletPick(it.area.name, it.area.lat, it.area.lon, if (it.area.stop) Kind.STOP else Kind.NAP, it.alongKm, true, nursing = it.area.nursing,
                address = "${it.area.route} ${it.area.dir} 방향", group = it.area.group, areaKm = it.area.km, sign = it.area.sign) }, "고속도로")
            return
        }
        if (speedKmh >= HIGHWAY_KMH) { done(null, FAST); return }
        val (lat, lon) = ahead(lat0, lon0, heading, speedKmh, ageMs)
        io.launch {
            val local = load()
            val remote = if (remoteOk) fetch(lat, lon, heading) else null
            val all = merge(local, remote.orEmpty())
            val pick = best(all, lat, lon, heading, speedKmh)
            val how = when {
                !remoteOk -> "앱 안 주유소 자료(위치 보내기 꺼짐)"
                remote == null -> "앱 안 주유소 자료(중계 서버 답 없음)"
                else -> "앱 안 자료 + 중계 서버 ${remote.size}곳"
            }
            main.post { done(pick, how) }
        }
    }

    /**
     * 고른 곳이 아직 앞에 있나(경유지로 넣기 직전에 본다). 일반 도로만: 지금 속도로 5초 안 거리보다 멀고 진행 방향 ±90° 안.
     * 고속도로 쉼터는 이정으로 0.3km 넘게 앞인 곳을 골라 대답을 기다리는 동안 지나치지 않는다
     */
    fun stillAhead(p: ToiletPick, lat0: Double, lon0: Double, heading: Double, ageMs: Long, speedKmh: Float): Boolean {
        if (p.highway) {
            // 지금 자리를 다시 맞춰 이정으로 본다(감속 차로에 들어설 여유 0.3km + 5초 거리). 맞추지 못하면 판단하지 않는다
            val spot = rests()?.match(lat0, lon0, heading) ?: return true
            if (spot.group != p.group || spot.sign != p.sign) return true
            val left = (p.areaKm - spot.km) * p.sign - moved(speedKmh, ageMs) / 1000
            return left > HW_ENTER_KM + speedKmh / 3600.0 * 5
        }
        val (lat, lon) = ahead(lat0, lon0, heading, speedKmh, ageMs)
        val m = RestAreas.dist(lat, lon, p.lat, p.lon)
        val rel = RestAreas.angle(heading, RestAreas.bearing(lat, lon, p.lat, p.lon))
        return m > maxOf(MIN_M / 2, speedKmh / 3.6 * 5) && rel <= 90
    }

    /** 위치를 받은 뒤 달린 거리(m, 최대 15초) */
    private fun moved(speedKmh: Float, ageMs: Long): Double = speedKmh / 3.6 * (ageMs.coerceIn(0L, AGE_MAX_MS) / 1000.0)

    /** 위치를 받은 뒤 달린 만큼 진행 방향으로 앞당긴 자리(일반 도로) */
    private fun ahead(lat: Double, lon: Double, heading: Double, speedKmh: Float, ageMs: Long): Pair<Double, Double> {
        val m = moved(speedKmh, ageMs)
        if (m < 1) return lat to lon
        val h = Math.toRadians(heading)
        return (lat + m * cos(h) / 111_320.0) to (lon + m * sin(h) / (111_320.0 * cos(Math.toRadians(lat))))
    }

    /** 중계 서버(TMAP 주변 주유소). 위치는 이 요청에만 쓴다. 실패하면 null */
    private suspend fun fetch(lat: Double, lon: Double, heading: Double): List<Spot>? {
        val body = JSONObject()
            .put("lat", "%.5f".format(Locale.US, lat).toDouble())
            .put("lon", "%.5f".format(Locale.US, lon).toDouble())
            .put("heading", heading.toInt())
        val j = Relay.post("/toilet", body, waitMs = REMOTE_WAIT_MS) ?: return null
        val a = j.optJSONArray("items") ?: return null
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val la = o.optDouble("lat", Double.NaN); val lo = o.optDouble("lon", Double.NaN)
            val name = o.optString("name").replace(",", " ").trim()
            // 가스 충전소(LPG)·고속도로 휴게소 안 주유소는 고르지 않는다(위)
            if (la.isNaN() || lo.isNaN() || name.isEmpty() || o.optString("kind") == "lpg" || highwayName(name)) null
            else Spot(clean(name), la, lo, Kind.GAS, if (o.has("open24") && !o.isNull("open24")) o.optBoolean("open24") else null)
        }
    }

    /** 앱 안 자료와 서버 결과를 합친다. 60m 안의 같은 곳은 하나만(서버가 24시간 여부를 알면 그 값을 붙인다) */
    private fun merge(local: List<Spot>, remote: List<Spot>): List<Spot> {
        if (remote.isEmpty()) return local
        val extra = mutableListOf<Spot>()
        val known = mutableMapOf<Spot, Boolean>()
        for (s in remote) {
            val same = local.firstOrNull { abs(it.lat - s.lat) < 0.001 && abs(it.lon - s.lon) < 0.001 && RestAreas.dist(it.lat, it.lon, s.lat, s.lon) < 60 }
            if (same == null) extra += s else if (s.open24 != null) known[same] = s.open24
        }
        return local.map { l -> known[l]?.let { l.copy(open24 = it) } ?: l } + extra
    }

    private fun best(all: List<Spot>, lat: Double, lon: Double, heading: Double, speedKmh: Float): ToiletPick? {
        val night = Calendar.getInstance().get(Calendar.HOUR_OF_DAY).let { it >= 22 || it < 6 }
        // 지금 속도로 20초 안에 닿을 곳은 대답을 기다리고 들어설 틈이 없어 뺀다(서 있으면 80m)
        val minM = maxOf(MIN_M, speedKmh / 3.6 * PASS_S)
        val r = rests()
        // 위도 0.06°(약 6.7km) 상자로 먼저 거른다(5천여 곳 모두 거리를 재지 않게)
        return all.asSequence()
            .filter { abs(it.lat - lat) < 0.06 && abs(it.lon - lon) < 0.075 }
            .mapNotNull { s ->
                val m = RestAreas.dist(lat, lon, s.lat, s.lon)
                if (m < minM || m > MAX_KM * 1000) return@mapNotNull null
                val rel = (RestAreas.bearing(lat, lon, s.lat, s.lon) - heading + 360) % 360
                // 앞쪽 ±60°만
                if (rel > AHEAD_DEG && rel < 360 - AHEAD_DEG) return@mapNotNull null
                // 고속도로 휴게소 안 주유소(일반 도로에서 들어갈 수 없다)
                if (r?.fuelStopNear(s.lat, s.lon, STOP_NEAR_M) == true) return@mapNotNull null
                // 길에서 옆으로 비켜난 거리(오른쪽 +, 왼쪽 -). 왼쪽(중앙선 건너편)은 돌아가는 몫을 더한다: 1km까지는 거리의 0.6배, 그 너머는 0.6km로 고정
                // (멀면 길이 굽어 쪽을 믿기 어렵고, 1km에서 벌점이 끊겨 더 먼 왼쪽을 고르지 않게)
                val side = m * sin(Math.toRadians(rel))
                val left = ((-side) / SIDE_FULL_M).coerceIn(0.0, 1.0)
                var cost = m / 1000 + (LEFT_COST - 1) * minOf(m, SIDE_M) / 1000 * left
                if (night && s.open24 == false) cost += NIGHT_COST_KM
                s to (m / 1000 to cost)
            }
            .minByOrNull { it.second.second }
            ?.let { (s, d) -> ToiletPick(s.name, s.lat, s.lon, s.kind, d.first, false, open24 = s.open24, address = "주유소") }
    }

    private fun abs(d: Double) = kotlin.math.abs(d)

    /** 카드에 보일 이름: 회사 꼴("(주)"·"㈜"·"주식회사")을 떼고 빈칸을 하나로("㈜한미석유 진흥주유소" → "한미석유 진흥주유소") */
    private fun clean(name: String): String =
        CORP.fold(name) { n, c -> n.replace(c, " ") }.replace(Regex("\\s+"), " ").trim().ifEmpty { name.trim() }

    /** 고속도로 휴게소 안 주유소 이름("○○휴게소(서울방향)주유소", "(상)", "하행"). 방향 표시 없는 "○○휴게소주유소"는 국도 휴게소일 수 있어 남긴다 */
    private fun highwayName(name: String): Boolean = UPDOWN.any { it in name } || "휴게소" in name && DIRS.any { it in name }

    companion object {
        /** 고속도로: 휴게소가 졸음쉼터보다 이만큼까지 멀어도 휴게소(쉬어 가기는 RestAreas.STOP_PREFER_KM 5km) */
        const val STOP_PREFER_KM = 2.0
        /** 고속도로 선분과 진행 방향이 이만큼 안으로 나란해야 고속도로로 본다(도로 점 간격이 1km 안팎이라 굽은 길 오차를 넉넉히) */
        const val ALONG_DEG = 35.0
        /** 도로 점이 없는 고속도로 구간으로 볼 속도 */
        const val HIGHWAY_KMH = 90f
        const val MAX_KM = 6.0
        /** 서 있을 때 바로 곁은 고르지 않는다. 달리면 PASS_S초 안에 닿을 곳까지 뺀다 */
        const val MIN_M = 80.0
        const val PASS_S = 20.0
        const val AHEAD_DEG = 60.0
        const val LEFT_COST = 1.6
        /** 왼쪽 벌점은 이 거리 안에서만, 옆으로 이만큼 비켜나면 다 붙인다 */
        const val SIDE_M = 1_000.0
        const val SIDE_FULL_M = 40.0
        const val STOP_NEAR_M = 400.0
        const val NIGHT_COST_KM = 2.0
        const val REMOTE_WAIT_MS = 2_500
        const val AGE_MAX_MS = 15_000L
        /** 고속도로: 쉼터는 지금 속도로 HW_PASS_S초 거리(HW_MIN_KM 이상) 넘게 앞인 곳만. 넣기 직전엔 감속 차로 여유 HW_ENTER_KM + 5초 거리 */
        const val HW_MIN_KM = 0.5
        const val HW_PASS_S = 25.0
        const val HW_ENTER_KM = 0.3
        /** 쉼터 좌표에서 이만큼 안에 서 있으면 쉼터 안 */
        const val IN_REST_KM = 0.3
        /** find의 '없음' 까닭(ride/Care.toiletNone이 알릴 말을 고른다) */
        const val IN_REST = "쉼터 안에 서 있음"
        const val FAST = "빠르게 달리는 중(쉼터 자료가 없는 고속도로로 보임)"
        private val CORP = listOf("(주)", "( 주 )", "(주 )", "㈜", "주식회사", "(유)", "㈲", "유한회사", "(합)", "합자회사")
        private val UPDOWN = listOf("(상)", "(하)", "상행", "하행")
        private val DIRS = listOf("방향", "방면")
    }
}
