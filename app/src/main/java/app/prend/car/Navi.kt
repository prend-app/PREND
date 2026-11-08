package app.prend.car

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import app.prend.Prend

/** 차 내비게이션이 아는 지금 위치. routeId가 있으면 안내 중인 경로가 있다 */
data class Where(val lat: Double, val lon: Double, val heading: Double, val address: String, val routing: Boolean, val at: Long)

/**
 * 차 내비게이션(Pleos NaviHelper 3.0.5) 잇기. 지금 위치를 읽고, 쉼터·병원을 경유지로 넣는다(시험 46에서 확인한 호출).
 * 경유지는 안내 중인 경로가 있을 때만 들어가므로, 경로가 없으면 그곳을 목적지로 안내한다.
 * 위치는 운행 서비스가 달리는 동안 10초마다 묻고, 마지막 답을 last에 둔다.
 * 연결에 실패하면 10·20·30초 뒤 다시 잇는다(다섯 번까지). 급할 때 위치를 모르면 그 자리에서 한 번 더 잇는다(retry, 11/06:
 * 전에는 깰 때 한 번만 이어, 차의 내비가 아직 준비 중이면 그 운행 내내 119 위치·고속도로 판단·병원 찾기가 막혔다)
 */
class Navi(context: Context) {

    private val app = context.applicationContext
    private var navi = NaviHelper(app)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var ready = false
    /** 잇는 중인가, 지금까지 이어 본 횟수, 서비스가 끝나 놓았나 */
    @Volatile private var connecting = false
    private var tries = 0
    @Volatile private var closed = false

    @Volatile private var real: Where? = null
    /** 시험·시연용 위치(에뮬레이터의 Pleos 내비 위치는 GPS 명령으로 움직이지 않는다, 10/25 시험). 시연 영상에서는 밝힌다 */
    @Volatile private var fake: Where? = null

    val last: Where? get() = fake ?: real

    /** 시험 위치를 쓰는 중인가(위 막대의 '시연' 표, 디버그 빌드만) */
    val pretending get() = fake != null

    /** 목적지까지 남은 시간(초)과 그 값을 받은 때(onDrivingInfo의 destination.duration, 10/27 시험). 경로가 없으면 null */
    @Volatile private var eta: Pair<Int, Long>? = null
    @Volatile private var fakeEta: Pair<Int, Long>? = null

    /** 지금 목적지까지 남은 초(받은 뒤 흐른 시간을 뺀다). 경로가 없거나 2분 넘게 소식이 없으면 null */
    fun etaSec(): Int? {
        val (sec, at) = fakeEta ?: eta ?: return null
        val gone = ((System.currentTimeMillis() - at) / 1000).toInt()
        if (fakeEta == null && gone > 120) return null
        return (sec - gone).coerceAtLeast(0)
    }

    /** 다음 회전 안내 하나(종류 이름 TURN_TOLL·TURN_ENTER_ROUNDABOUT_03 …, 남은 거리 m)와 받은 때. 경로가 없으면 null */
    data class Turn(val type: String, val meters: Double, val at: Long)
    @Volatile var nextTurn: Turn? = null
        private set
    /** 목적지 이름(주유소인지 볼 때). 경로가 없으면 null */
    @Volatile var destName: String? = null
        private set
    /** 목적지 자리(DestinationInfo, 공식 문서: 위도·경도·이름·주소). 목적지가 골목 안인지 볼 때만 쓰고(ride/AlleyDest) 메모리에만 둔다. 경로가 없으면 null */
    data class Dest(val lat: Double, val lon: Double, val name: String, val address: String)
    @Volatile var dest: Dest? = null
        private set
    /** PREND가 내비에 길을 넣은 때(벽시계). 그 뒤 곧 받은 목적지는 PREND가 넣은 것이다(ride/AlleyDest가 건너뛴다) */
    @Volatile var selfRouteAt = 0L
        private set
    /** 시험: 넣어 둔 목적지 자리를 지운다 */
    fun pretendDestOff() { dest = null; Prend.log("· 내비: 시험 목적지 자리 끔") }
    /** 시험: 목적지 자리를 넣는다(에뮬레이터 내비가 목적지 정보를 주지 않을 때) */
    fun pretendDestAt(lat: Double, lon: Double, name: String, address: String) { dest = Dest(lat, lon, name, address); Prend.log("· 내비: 시험 목적지 자리(${name.length}자)") }

    /** 경로를 벗어나 다시 찾았을 때(경로 재탐색 끝) */
    var onReroute: (() -> Unit)? = null

    /** 시험: 다음 회전 안내를 넣는다(에뮬레이터 차는 경로를 따라 움직이지 않는다) */
    fun pretendTurn(type: String, meters: Double) { fakeTurnUntil = System.currentTimeMillis() + 60_000; nextTurn = Turn(type, meters, System.currentTimeMillis()); Prend.log("· 내비: 시험 회전 안내 $type ${meters.toInt()}m") }
    /** 시험 회전 안내를 넣은 뒤 1분은 실제 안내로 덮지 않는다 */
    @Volatile private var fakeTurnUntil = 0L
    fun pretendDest(name: String?) { destName = name; Prend.log("· 내비: 시험 목적지 ${name ?: "없음"}") }
    fun pretendReroute() { Prend.log("· 내비: 시험 경로 재탐색"); main.post { reroutes.addLast(System.currentTimeMillis()); onReroute?.invoke() } }

    /** 경로 재탐색이 끝난 때들(10분, 메모리에만). 길을 헤매는 중인지 볼 때(ride/Timing) */
    private val reroutes = ArrayDeque<Long>()
    /** ms 안에 경로를 다시 찾은 횟수 */
    fun reroutesWithin(ms: Long): Int { val t = System.currentTimeMillis(); while (reroutes.isNotEmpty() && t - reroutes.first() > 10 * 60_000) reroutes.removeFirst(); return reroutes.count { t - it <= ms } }

    /** 곧 도는 곳: 좌·우회전·유턴·회전교차로는 150m, 나들목·분기·합류는 300m 안이면 그 종류 이름. 소식이 10초 넘게 낡았으면 null */
    fun turnNear(): String? {
        val t = nextTurn ?: return null
        if (System.currentTimeMillis() - t.at > 10_000) return null
        val wide = "_IC_" in t.type || "DEVIDE" in t.type || "DIVIDE" in t.type || "MERGE" in t.type
        val turn = t.type.startsWith("TURN_LEFT") || t.type.startsWith("TURN_RIGHT") || "UTURN" in t.type || "ROUNDABOUT" in t.type
        return when {
            wide && t.meters <= 300 -> t.type
            turn && t.meters <= 150 -> t.type
            else -> null
        }
    }

    /** 내비 안내로 목적지에 닿은 때(벽시계, 없으면 0). 주차 자리는 길 안내로 찾아온 날에만 알린다(ride/Care.spotWanted). 어디인지는 남기지 않는다 */
    @Volatile var arrivedAt = 0L
        private set

    /** 시험: 내비 안내로 지금 목적지에 닿은 것처럼 */
    fun pretendArrived() { arrivedAt = System.currentTimeMillis(); Prend.log("· 내비: 시험 목적지 도착") }

    /** 시험: 도착까지 sec초 남은 것처럼(에뮬레이터 차는 경로를 따라 움직이지 않는다). null이면 끈다 */
    fun pretendEta(sec: Int?) { fakeEta = sec?.let { it to System.currentTimeMillis() }; Prend.log("· 내비: 시험 도착 ${sec?.let { "${it}초 뒤" } ?: "끔"}") }

    fun pretend(lat: Double, lon: Double, heading: Double) {
        fake = Where(lat, lon, heading, "시험 위치", real?.routing == true, System.currentTimeMillis())
        // 위치는 기록에 남기지 않는다(시험 위치도 같다)
        // 시험 위치로 옮기면 지나온 자리를 그 한 점부터 다시 쌓는다(멀리 떨어진 두 점이 한 길로 맞춰지지 않게)
        trail.clear(); keepTrail(lat, lon)
        Prend.log("· 내비: 시험 위치 켬")
    }

    /**
     * 지금 길의 제한속도(km/h, onCurrentRoadSpeedLimit "Received every second after the event listener registration.").
     * 3초 넘게 소식이 없으면 null(길 안내 없이도 오는지는 실차 확인 전). 골목에서 선 것을 신호 대기로 보지 않을 때 쓴다(ride/Timing)
     */
    @Volatile private var limit: Pair<Int, Long>? = null
    @Volatile private var fakeLimit: Int? = null
    private var limitLogged = false
    fun speedLimit(): Int? {
        fakeLimit?.let { return it.takeIf { v -> v > 0 } }
        val (v, at) = limit ?: return null
        return v.takeIf { System.currentTimeMillis() - at < 3_000 && it > 0 }
    }
    /**
     * 지나온 자리(골목 판정에 쓴다, ride/Road). 메모리에만 두고 3분 지난 것과 100개 넘는 것은 버린다. 기록·파일에는 남기지 않는다.
     * 같은 자리가 되풀이되면(서 있음) 하나만 둔다
     */
    private val trail = ArrayDeque<Triple<Double, Double, Long>>()
    private fun keepTrail(lat: Double, lon: Double) {
        val t = System.currentTimeMillis()
        val last = trail.lastOrNull()
        if (last == null || last.first != lat || last.second != lon) trail.addLast(Triple(lat, lon, t))
        while (trail.size > 100 || trail.isNotEmpty() && t - trail.first().third > TRAIL_MS) trail.removeFirst()
    }
    fun trail(): List<Pair<Double, Double>> {
        val t = System.currentTimeMillis()
        while (trail.isNotEmpty() && t - trail.first().third > TRAIL_MS) trail.removeFirst()
        return trail.map { it.first to it.second }
    }

    /** 시험: 제한속도를 넣는다(0 이하면 끈다) */
    fun pretendLimit(kmh: Int) { fakeLimit = kmh.takeIf { it > 0 }; Prend.log("· 내비: 시험 제한속도 ${if (kmh > 0) "${kmh}km/h" else "끔"}") }

    fun pretendOff() { fake = null; Prend.log("· 내비: 시험 위치 끔") }

    private companion object {
        /** 지나온 자리를 이만큼(ms) 둔다 */
        const val TRAIL_MS = 3 * 60_000L
        /** 연결에 실패하면 이만큼 뒤 다시 잇는다(횟수만큼) */
        val RETRY_MS = longArrayOf(10_000L, 20_000L, 30_000L, 30_000L, 30_000L)
    }

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) { Prend.log("· 내비: 경로 시작"); askDest() }
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            val d = destinationInfo
            // 좌표·주소는 기록에 남기지 않는다
            main.post { dest = Dest(d.latitude, d.longitude, d.name ?: "", d.address ?: ""); Prend.log("· 내비: 목적지 자리 받음") }
        }
        override fun onRoutingCompleted() = Prend.log("· 내비: 경로 탐색 끝")
        override fun onRouteCancelled() { Prend.log("· 내비: 경로 취소"); main.post { dest = null } }
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            val c = currentLocationInfo
            val w = Where(c.latitude, c.longitude, c.heading.toDouble(), c.address ?: "", !c.routeId.isNullOrBlank(), System.currentTimeMillis())
            // 시험 위치를 쓰는 동안은 실제 위치를 지나온 자리에 섞지 않는다
            main.post { real = w; if (fake == null) keepTrail(w.lat, w.lon) }
        }
        override fun onWayPointInfo(waypointInfo: List<WaypointInfo>) = Prend.log("· 내비: 경유지 ${waypointInfo.size}곳")
        override fun onDrivingInfo(drivingInfo: DrivingInfo) {
            val sec = drivingInfo.destination?.duration
            val name = drivingInfo.destination?.name
            main.post { eta = sec?.let { it to System.currentTimeMillis() }; if (name != null) destName = name }
        }
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) { Prend.log("· 내비: 목적지 도착"); main.post { eta = null; arrivedAt = System.currentTimeMillis() } }
        override fun onTBTInfo(tbtInfo: List<TBTInfo>) {
            val t = tbtInfo.firstOrNull()
            main.post { if (System.currentTimeMillis() > fakeTurnUntil) nextTurn = t?.let { Turn(it.type.name, it.distance, System.currentTimeMillis()) } }
        }
        override fun onReRouteEnded() { Prend.log("· 내비: 경로 재탐색 끝"); main.post { reroutes.addLast(System.currentTimeMillis()); onReroute?.invoke() } }
        override fun onCurrentRoadSpeedLimit(speedLimit: Int) {
            if (!limitLogged) { limitLogged = true; Prend.log("· 내비: 제한속도 이벤트가 옴(처음 값 ${speedLimit}km/h)") }
            limit = speedLimit to System.currentTimeMillis()
        }
        override fun onRouteEnded() { main.post { nextTurn = null; destName = null; eta = null; dest = null } }
        override fun onError(errorCode: NaviErrorCode) = Prend.log("✗ 내비 오류 $errorCode")
    }

    fun init() = connect()

    private fun connect() {
        if (ready || connecting || closed) return
        connecting = true
        tries++
        val h = navi
        runCatching {
            h.initialize(
                onConnected = {
                    connecting = false
                    // 그사이 새로 만든 연결이 있으면 이 답은 버린다
                    if (h === navi) { ready = true; h.addListener(listener); Prend.log("◆ 내비: 연결됨${if (tries > 1) "(${tries}번째)" else ""}"); ask() }
                },
                onFailed = { t -> connecting = false; main.post { failed(t) } },
            )
        }.onFailure { connecting = false; failed(it) }
    }

    /** 연결 실패: 10·20·30·30·30초 뒤 다시 잇는다(새 NaviHelper로) */
    private fun failed(t: Any?) {
        Prend.log("✗ 내비 연결 실패 $t")
        if (tries > RETRY_MS.size) return Prend.log("· 내비: 다시 잇기를 그만둠(${tries}번 실패)")
        val wait = RETRY_MS[tries - 1]
        Prend.log("· 내비: ${wait / 1000}초 뒤 다시 이음")
        main.postDelayed({ renew("다시 잇기") }, wait)
    }

    /** 급할 때 위치를 모름(ride/Crash): 이어져 있지 않으면 지금 한 번 더 잇는다(다섯 번을 넘겼어도 한 번은) */
    fun retry(why: String) {
        if (ready || connecting) return
        if (tries > RETRY_MS.size) tries = RETRY_MS.size
        renew(why)
    }

    private fun renew(why: String) {
        if (ready || connecting || closed) return
        Prend.log("· 내비: 다시 이음($why)")
        runCatching { navi.release() }
        navi = NaviHelper(app)
        connect()
    }

    /** 목적지 자리를 묻는다(답은 dest로 온다) */
    fun askDest() {
        if (!ready) return
        runCatching { navi.getDestinationInfo() }.onFailure { Prend.log("✗ 내비 목적지 요청 실패 $it") }
    }

    /** 지금 위치를 묻는다(답은 last로 온다) */
    fun ask() {
        if (!ready) return
        runCatching { navi.getCurrentLocationInfo() }.onFailure { Prend.log("✗ 내비 위치 요청 실패 $it") }
    }

    /**
     * 그곳을 경유지로 넣는다. 안내 중인 경로가 없으면 목적지로 안내한다.
     * 돌려주는 값: "waypoint"(경유지), "route"(목적지), null(실패)
     */
    fun goVia(lat: Double, lon: Double, name: String, address: String): String? {
        if (!ready) { Prend.log("✗ 내비: 연결 전"); return null }
        // 위치는 세워 두면 1분마다만 묻는다: 방금 경로를 시작했으면(목적지 자리를 받음) 그것도 경로가 있는 것으로 본다
        val routing = last?.routing == true || dest != null
        selfRouteAt = System.currentTimeMillis()
        return runCatching {
            if (routing) navi.addWaypoint(RequestWaypointInfo(latitude = lat, longitude = lon, poiName = name, address = address, waypointIndex = WaypointIndex.FIRST, poiId = "", poiSubId = ""))
            else navi.requestRoute(RouteInfo(latitude = lat, longitude = lon, poiName = name, address = address, poiId = "", poiSubId = ""))
            Prend.log("◆ 내비: ${if (routing) "경유지" else "목적지"}로 넣음")
            if (routing) "waypoint" else "route"
        }.onFailure { Prend.log("✗ 내비 넣기 실패 $it") }.getOrNull()
    }

    /**
     * 시험: 리스너의 모든 이벤트를 기록한다(도착까지 남은 시간을 주는 이벤트가 있는지 본다). seconds 뒤 떼어 낸다.
     * 이벤트 이름과 값의 앞 300자만 남긴다
     */
    fun probe(seconds: Int = 30) {
        if (!ready) return Prend.log("✗ 내비 시험: 연결 전")
        val type = NaviHelperEventListener::class.java
        type.methods.filter { !it.name.startsWith("access$") }.forEach { Prend.log("· 내비 리스너 메서드 ${it.name}(${it.parameterTypes.joinToString { p -> p.simpleName }})") }
        // 회전 안내 종류(회전교차로·요금소 등이 있는지)
        runCatching {
            val t = Class.forName("ai.pleos.playground.navi.data.TBTInfo").declaredFields.firstOrNull { it.name == "type" }?.type
            Prend.log("· 회전 안내 종류(${t?.simpleName}): ${t?.enumConstants?.joinToString()}")
        }.onFailure { Prend.log("✗ 회전 안내 종류 읽기 실패 $it") }
        val seen = mutableMapOf<String, Int>()
        val spy = java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { p, m, a ->
            when (m.name) {
                "toString" -> "spy"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === a?.getOrNull(0)
                else -> {
                    val n = (seen[m.name] ?: 0) + 1
                    seen[m.name] = n
                    // 위치 이벤트는 위치를 남기지 않으려고 이름만
                    if (n <= 3) Prend.log("· 내비 이벤트 ${m.name}${if (m.name == "onCurrentLocationInfo") "" else ": " + (a?.joinToString()?.take(300) ?: "")}")
                    null
                }
            }
        } as NaviHelperEventListener
        navi.addListener(spy)
        main.postDelayed({ runCatching { navi.removeListener(spy) }; Prend.log("· 내비 시험 끝: ${seen}") }, seconds * 1000L)
    }

    fun release() {
        // 기다리는 다시 잇기도 거둔다(서비스가 끝난 뒤 새로 잇지 않게)
        closed = true
        main.removeCallbacksAndMessages(null)
        runCatching { navi.removeListener(listener); navi.release() }
        ready = false
    }
}
