package app.prend.ride

import android.content.Context
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 졸음쉼터 한 곳. sign은 그 쉼터를 쓰는 진행 방향이 이정이 늘어나는 쪽(+1)인지 줄어드는 쪽(-1)인지,
 * travel은 그 쉼터 앞을 지나는 차의 진행 방위
 */
/** 졸음쉼터 또는 휴게소(stop). 휴게소면 주유소·LPG·전기차 충전소 유무(11/02, rest_stops.csv), 수유실 유무(11/07, 화장실 찾기) */
data class RestArea(val name: String, val route: String, val group: String, val dir: String, val km: Double, val sign: Int, val lat: Double, val lon: Double, val travel: Double,
    val stop: Boolean = false, val fuel: Boolean = false, val lpg: Boolean = false, val ev: Boolean = false, val nursing: Boolean = false)

/** 차가 지금 고속도로 어디에 있는가(노선 무리, 이정, 진행 방향). road는 맞춘 도로 선분의 방위(이정이 늘어나는 쪽) */
data class RoadSpot(val group: String, val km: Double, val sign: Int, val offM: Double, val road: Double = 0.0)

/** 쉼터 고르기 결과. alongKm는 같은 노선이면 이정 차이(도로를 따라 간 거리), 아니면 직선 거리 */
data class RestPick(val area: RestArea, val alongKm: Double, val onRoute: Boolean)

/** 고속도로 위인가(검토 11/07 멋대로 판단): 확실·애매·아님. 애매면 119에 말할 위치에 동네 주소와 고속도로 이정을 둘 다 보인다 */
enum class HighwayState(val label: String) { SURE("확실"), MAYBE("애매"), NO("아님") }

/**
 * 고속도로 판단과 그 까닭(기록용, 좌표 없이). grade는 판단에 쓴 도로 등급 답(true 고속국도, false 다른 길, null 모름),
 * along은 차가 가는 방향이 노선 방향과 맞는가(맞지 않으면 '○○ 방향'을 붙이지 않는다: 방향이 어긋난 차의 진행 쪽은 짐작일 뿐이다),
 * spot은 맞춘 고속도로 자리(노선 선에서 150m 안일 때만)
 */
data class HighwayCall(val state: HighwayState, val why: String, val grade: Boolean? = null, val along: Boolean = true, val spot: RoadSpot? = null)

/**
 * 졸음쉼터 좌표(앱에 넣어 둔 공공데이터, 인터넷 없이 돈다).
 * 출처: 한국도로공사 '졸음쉼터 설치현황_20260801'(노선·이정·방향·명칭)과 '고속도로 암거 좌표_20260514'(노선·이정·위경도)를
 * 이정으로 끼워 맞춰 붙였다(tools/build_rest.js). 공공데이터포털, 이용허락범위 제한 없음.
 * 고르는 법: 현재 위치를 가장 가까운 도로 점(1.5km 안)의 앞뒤 선분에 맞춰(150m 안일 때만) 노선·이정을 알고, 진행 방향으로 이정이 늘어나는지 줄어드는지 가린 뒤,
 * 같은 노선·같은 방향 앞쪽의 가장 가까운 쉼터를 고른다. 고속도로 밖이면 앞쪽(±45°) 40km 안의 가장 가까운 쉼터.
 */
class RestAreas(context: Context) {

    private data class Pt(val group: String, val km: Double, val lat: Double, val lon: Double)

    private val areas: List<RestArea>
    /** 고속도로 휴게소(한국도로공사_휴게소정보) */
    private val stops: List<RestArea>
    private val points: List<Pt>
    private val byGroup: Map<String, List<Pt>>

    init {
        val a = context.assets
        areas = a.open("rest/rest_areas.csv").bufferedReader().readLines().drop(1).mapNotNull { line ->
            val f = line.split(',')
            if (f.size < 9) null else RestArea(f[0], f[1], f[2], f[3], f[4].toDouble(), f[5].toInt(), f[6].toDouble(), f[7].toDouble(), f[8].toDouble())
        }
        // 휴게소(11/02): 졸음쉼터와 같은 줄에 두고 고른다. 파일이 없으면 졸음쉼터만
        stops = runCatching { a.open("rest/rest_stops.csv").bufferedReader().readLines().drop(1).mapNotNull { line ->
            val f = line.split(',')
            if (f.size < 12) null else RestArea(f[0], f[1], f[2], f[3], f[4].toDouble(), f[5].toInt(), f[6].toDouble(), f[7].toDouble(), f[8].toDouble(), true, f[9] == "1", f[10] == "1", f[11] == "1", f.getOrNull(12) == "1")
        } }.getOrDefault(emptyList())
        points = a.open("rest/route_points.csv").bufferedReader().readLines().drop(1).mapNotNull { line ->
            val f = line.split(',')
            if (f.size < 4) null else Pt(f[0], f[1].toDouble(), f[2].toDouble(), f[3].toDouble())
        }
        byGroup = points.groupBy { it.group }.mapValues { (_, v) -> v.sortedBy { it.km } }
    }

    val count get() = areas.size

    /** 주유소가 있는 고속도로 휴게소가 이 거리(m) 안에 있나(화장실 찾기가 일반 도로에서 휴게소 안 주유소를 고르지 않게, ride/Toilets) */
    fun fuelStopNear(lat: Double, lon: Double, m: Double): Boolean =
        stops.any { it.fuel && kotlin.math.abs(it.lat - lat) < 0.01 && kotlin.math.abs(it.lon - lon) < 0.012 && dist(lat, lon, it.lat, it.lon) < m }

    /** 가장 가까운 졸음쉼터까지 직선 거리(km). 고속도로에 선 차가 쉼터 안인지 거를 때(ride/HighwayStop) */
    fun nearestKm(lat: Double, lon: Double): Double? = (areas + stops).mapNotNull { a -> Together.meters(lat, lon, a.lat, a.lon)?.let { it / 1000.0 } }.minOrNull()

    /**
     * 위치를 고속도로 노선·이정에 맞춘다. 가까운 도로 점 앞뒤의 선분까지 거리가 150m 안일 때만(고속도로 가까이),
     * 아니면 null. 이정은 선분 위로 내린 자리에서 끼워 맞춘다.
     * 맞았다고 고속도로 위인 것은 아니다(방음벽 옆 아파트도 150m 안이다): 고속도로인지는 judge()가 근거를 더해 가린다(검토 11/07 멋대로 판단)
     */
    fun match(lat: Double, lon: Double, heading: Double): RoadSpot? {
        val near = points.minByOrNull { dist(lat, lon, it.lat, it.lon) } ?: return null
        if (dist(lat, lon, near.lat, near.lon) > 1_500) return null
        val list = byGroup[near.group] ?: return null
        val i = list.indexOf(near)
        var best: Triple<Double, Double, Double>? = null // (거리 m, 이정, 도로 방위)
        for ((a, b) in listOf(list.getOrNull(i - 1) to near, near to list.getOrNull(i + 1))) {
            if (a == null || b == null) continue
            // 평면 근사(수백 m 안): 위도 1° ≈ 111.32km, 경도 1° ≈ 111.32km × cos(위도)
            val kx = 111_320 * cos(rad(lat)); val ky = 111_320.0
            val ax = (a.lon - lon) * kx; val ay = (a.lat - lat) * ky
            val bx = (b.lon - lon) * kx; val by = (b.lat - lat) * ky
            val dx = bx - ax; val dy = by - ay
            val t = if (dx == 0.0 && dy == 0.0) 0.0 else (-(ax * dx + ay * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
            val px = ax + dx * t; val py = ay + dy * t
            val d = sqrt(px * px + py * py)
            if (best == null || d < best.first) best = Triple(d, a.km + (b.km - a.km) * t, bearing(a.lat, a.lon, b.lat, b.lon))
        }
        val (off, km, road) = best ?: return null
        if (off > 150) return null
        val sign = if (angle(heading, road) <= 90) 1 else -1
        return RoadSpot(near.group, km, sign, off, road)
    }

    /** 앞쪽의 가장 가까운 졸음쉼터 */
    /** 그 지점에서 가는 쪽의 방향 이름(같은 노선·같은 쪽 쉼터들이 가장 많이 쓰는 이름, "부산"). 쉼터 자료가 없으면 null */
    fun dirOf(spot: RoadSpot): String? =
        areas.filter { it.group == spot.group && it.sign == spot.sign }.groupingBy { it.dir }.eachCount().maxByOrNull { it.value }?.key

    /** spot은 같은 위치로 이미 맞춘 고속도로 자리(있으면 도로 점 4천여 개를 다시 훑지 않는다) */
    /**
     * 앞쪽에서 쉬어 갈 곳(11/02 휴게소 더함): 졸음쉼터와 휴게소를 각각 고르고, 휴게소가 졸음쉼터보다 5km 넘게 멀지 않으면 휴게소를 권한다
     * (화장실·먹을 것·주유가 있어 쉬어 가기 좋다). 둘 중 하나만 있으면 그것.
     * 화장실 찾기(ride/Toilets)는 휴게소를 2km 안쪽으로만 더 멀 때 고르고(preferKm), 같은 노선 앞쪽 결과끼리만 견준다(routeOnly: 직선으로 고른 다른 노선 쉼터를 섞지 않는다)
     */
    fun next(lat: Double, lon: Double, heading: Double, maxKm: Double = 60.0, spot: RoadSpot? = match(lat, lon, heading), preferKm: Double = STOP_PREFER_KM, routeOnly: Boolean = false, minKm: Double = 0.3): RestPick? {
        val nap = pick(areas, lat, lon, heading, maxKm, spot, routeOnly, minKm)
        val stop = pick(stops, lat, lon, heading, maxKm, spot, routeOnly, minKm)
        return when {
            stop == null -> nap
            nap == null -> stop
            stop.alongKm <= nap.alongKm + preferKm -> stop
            else -> nap
        }
    }

    private fun pick(areas: List<RestArea>, lat: Double, lon: Double, heading: Double, maxKm: Double, spot: RoadSpot?, routeOnly: Boolean = false, minKm: Double = 0.3): RestPick? {
        if (spot != null) {
            val ahead = areas.filter { it.group == spot.group && it.sign == spot.sign && (it.km - spot.km) * spot.sign > minKm }
                .minByOrNull { abs(it.km - spot.km) }
            if (ahead != null && abs(ahead.km - spot.km) <= maxKm) return RestPick(ahead, abs(ahead.km - spot.km), true)
            if (routeOnly) return null
        }
        // 고속도로 밖(또는 같은 노선 앞쪽에 없음): 앞쪽 ±45°에 있고, 그 쉼터 앞을 지나는 방향이 내 진행 방향과 60° 안인 곳 중
        // 40km 안에서 가장 가까운 곳(반대 차로의 쉼터를 고르지 않게)
        return areas.filter { angle(heading, bearing(lat, lon, it.lat, it.lon)) <= 45 && angle(heading, it.travel) <= 60 }
            .map { it to dist(lat, lon, it.lat, it.lon) / 1000 }
            .filter { it.second <= 40 }
            .minByOrNull { it.second }
            ?.let { (area, km) -> RestPick(area, km, false) }
    }

    companion object {
        /** 휴게소가 졸음쉼터보다 이만큼까지 멀어도 휴게소를 권한다 */
        const val STOP_PREFER_KM = 5.0
        /** 차가 가는 방향이 노선 방향과 이 각도 안이면(어느 쪽으로 가든) 노선을 따라 가는 것으로 본다 */
        const val ALONG_DEG = 45.0
        private fun rad(d: Double) = Math.toRadians(d)

        /**
         * 고속도로 판단(검토 11/07 멋대로 판단: 노선 선에서 150m 안인 것만으로는 모자랐다. 방음벽 옆 아파트·나란한 일반 도로가 고속도로로 잡혔다).
         * spot은 match()로 맞춘 자리(150m 안일 때만, 아니면 null), grade는 도로 등급 답(ride/Road.highwayAt), fast는 고속도로 속도로 달려와 섰나(ride/HighwayStop.cameFast).
         * - 확실: 도로 등급이 고속국도, 또는 고속도로 속도와 노선 방향 둘 다
         * - 애매: 선 가까이에서 속도·방향 가운데 하나만. 도로 등급이 다른 길이라고 했는데 속도·방향이 둘 다 맞으면 애매까지만(나란한 길에서 맞춤이 어긋날 수 있다)
         * - 아님: 그 밖(선 밖, 선 가까이지만 근거 없음, 도로 등급이 다른 길)
         */
        fun judge(spot: RoadSpot?, heading: Double, grade: Boolean?, fast: Boolean): HighwayCall {
            spot ?: return HighwayCall(HighwayState.NO, "노선 밖", grade)
            val along = angle(heading, spot.road).let { it <= ALONG_DEG || it >= 180 - ALONG_DEG }
            val ev = listOfNotNull("속도".takeIf { fast }, "방향".takeIf { along }).joinToString("·")
            return when {
                grade == true -> HighwayCall(HighwayState.SURE, "도로 등급 고속국도", true, along, spot)
                grade == false && fast && along -> HighwayCall(HighwayState.MAYBE, "도로 등급은 다른 길, 속도·방향은 고속도로", false, along, spot)
                grade == false -> HighwayCall(HighwayState.NO, "도로 등급 다른 길", false, along, spot)
                fast && along -> HighwayCall(HighwayState.SURE, "속도·방향", null, along, spot)
                fast || along -> HighwayCall(HighwayState.MAYBE, "${ev}만", null, along, spot)
                else -> HighwayCall(HighwayState.NO, "선 가까이지만 속도·방향 아님", null, along, spot)
            }
        }

        fun dist(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
            val dLat = rad(bLat - aLat); val dLon = rad(bLon - aLon)
            val h = sin(dLat / 2) * sin(dLat / 2) + cos(rad(aLat)) * cos(rad(bLat)) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * 6_371_000 * asin(sqrt(h))
        }

        fun bearing(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
            val y = sin(rad(bLon - aLon)) * cos(rad(bLat))
            val x = cos(rad(aLat)) * sin(rad(bLat)) - sin(rad(aLat)) * cos(rad(bLat)) * cos(rad(bLon - aLon))
            return (Math.toDegrees(atan2(y, x)) + 360) % 360
        }

        /** 두 방위의 차이(0~180) */
        fun angle(a: Double, b: Double): Double { val d = abs(a - b) % 360; return if (d > 180) 360 - d else d }
    }
}
