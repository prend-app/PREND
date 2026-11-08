package app.prend.ride

import android.content.Context
import android.os.SystemClock
import app.prend.Prend
import app.prend.car.CarState
import app.prend.car.Navi
import app.prend.net.Relay
import app.prend.ui.Ask
import app.prend.ui.Place
import app.prend.ui.Scene
import app.prend.ui.Stage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 목적지가 골목 안이면 출발 전에 큰길가 공영주차장을 한 번 권한다(11/01 사용자 "골목에서 초보운전자에게 도움되는 기능 추가해줘",
 * 골목 후보 1을 운전자·구현 두 관점 검토대로 줄였다: 검토/검토-골목초보-11-01.md).
 * - 언제: 초보 운전이 켜져 있고, P에 세워 둔 채 길 안내 목적지가 생겼을 때 한 번(출발 전에 고르게). 달리는 중에 정한 목적지는 보지 않는다.
 *   대리 운행·조용히 가기면 보지 않고, 다른 말·카드가 없고 말 차례(Floor)를 지날 때 목소리 없이 카드만
 * - 무엇을 보나: 목적지 한 점의 도로(TMAP 가까운 도로 찾기)가 이면도로·세도로이고 1차로 이하. 단지 안 도로(11)는 단지 주차장이 있을 것으로 보고 뺀다
 * - 무엇을 권하나: 목적지 동네의 공영주차장(네이버 지역 검색, 중계 서버 /places kind=parking) 가운데 목적지에서 곧은 거리 400m 안이고
 *   그 자리의 도로가 큰길(0~7)인 가장 가까운 한 곳. 없으면 아무것도 하지 않는다
 * - [주차장으로]면 경유지로 넣고 글로만 알린다(내비의 새 경로 안내와 겹치지 않게). [그대로 갈게요]를 두 번 이어 고르면 초보 운전을 다시 켤 때까지 보지 않는다
 * 목적지 좌표·주소는 메모리에만 두고 기록에 남기지 않는다(목적지 한 점이 중계 서버·TMAP으로, 동네 이름이 네이버로 간다). 위치 보내기를 껐으면(ride/GeoShare) 보지 않는다
 */
class AlleyDest(context: Context, private val navi: Navi?, private val road: () -> Road?, private val floor: Floor?, private val idle: () -> Boolean) {

    private val prefs = context.getSharedPreferences("alley_dest", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /** 이번에 본 목적지(같은 목적지를 다시 보지 않게, 메모리에만) */
    private var checked: Navi.Dest? = null
    /** 권할 주차장(찾았는데 아직 띄우지 못했으면 남아 있다) */
    private var offer: Place? = null
    private var cardAt = 0L

    init { Stage.sideAsks["ALLEY"] = ::answer }

    private fun now() = SystemClock.elapsedRealtime()

    /** 1초마다 */
    fun tick(car: CarState) {
        val ui = Stage.ui.value
        if (ui.ask?.id == "ALLEY" && (!car.parked || now() - cardAt > CARD_MS)) { Stage.ask(null); Prend.log("· 골목 목적지 카드 거둠(${if (car.parked) "40초" else "출발"})") }
        val d = navi?.dest
        if (d == null) { offer = null; return }
        if (d != checked) {
            checked = d
            offer = null
            when {
                // PREND가 넣은 목적지(가까운 블루핸즈·권한 주차장 등)는 보지 않는다
                System.currentTimeMillis() - (navi?.selfRouteAt ?: 0L) < SELF_MS -> Prend.log("· 골목 목적지: PREND가 넣은 목적지라 보지 않음")
                // 집·회사는 늘 가는 곳이라 묻지 않는다(내비 즐겨찾기 이름)
                d.name.replace(" ", "") in HOME_WORDS -> Prend.log("· 골목 목적지: 집·회사라 보지 않음")
                // 달리는 중에 정한 목적지는 보지 않는다(묻는 때는 출발 전 P 하나)
                !car.parked -> Prend.log("· 골목 목적지: 달리는 중에 정한 목적지라 보지 않음")
                !ui.novice || ui.daeri || ui.quietRide -> Unit
                // 위치 보내기를 껐으면(ride/GeoShare, 사용자 결정 11/06) 목적지 한 점도 동네 이름도 보내지 않는다
                !GeoShare.on -> Prend.log("· 골목 목적지: 위치 보내기를 꺼 보지 않음")
                prefs.getInt("no", 0) >= NO_MAX -> Prend.log("· 골목 목적지: 두 번 이어 ‘그대로’라 보지 않음")
                else -> check(d)
            }
            return
        }
        val o = offer ?: return
        if (!car.parked) { offer = null; Prend.log("· 골목 목적지: 띄우기 전에 출발해 버림"); return }
        // 묻는 사이 바뀐 것: 초보 운전을 껐거나 대리 운행·조용히 가기가 됐으면 버린다
        if (!ui.novice || ui.daeri || ui.quietRide) { offer = null; Prend.log("· 골목 목적지: 띄우기 전에 초보 꺼짐·대리 운행·조용히 가기라 버림"); return }
        // 말 차례: 이번 정차에 이미 다른 카드가 있었던 것만이면 띄운다(출발 전 그때만 쓸모 있다, 주차 자리와 같다). 다른 말 중·말 뒤 20초는 기다린다.
        // 한 운행 여섯 번에는 막히지 않고 세기만 한다
        if (ui.ask != null || !idle() || ui.scene != Scene.HOME) return
        if (floor?.why(Floor.Kind.ASK, bound = true)?.let { it != Floor.STOP_SPOKEN } == true) return
        offer = null
        cardAt = now()
        floor?.shown("골목 목적지")
        Stage.ask(Ask("ALLEY", "목적지가 골목 안이에요", "큰길가 ${o.name} · 목적지에서 약 ${o.meters ?: 0}m", "주차장으로", "그대로 갈게요"))
        pending = o
        Prend.log("◆ 골목 목적지 카드(카드만, 주차장까지 ${o.meters}m)")
    }

    /** 카드에 띄운 주차장 */
    private var pending: Place? = null

    /** 이번 목적지를 보는 중이거나 권할 주차장을 들고 있다(차 돌봄 카드가 이 정차의 차례를 먼저 쓰지 않게) */
    val busy get() = offer != null || checking
    private var checking = false

    private fun check(d: Navi.Dest) {
        val r = road() ?: return
        checking = true
        r.checkSpot(d.lat, d.lon) { k, what ->
            Prend.log("· 골목 목적지: 목적지 도로 $what → ${k?.label ?: "모름"}")
            if (k != Road.Kind.ALLEY || checked != d) { checking = false; return@checkSpot }
            val area = area(d.address) ?: run { checking = false; return@checkSpot Prend.log("· 골목 목적지: 주소에서 동네를 찾지 못함") }
            scope.launch {
                val arr = Relay.get("/places", mapOf("area" to area, "kind" to "parking"))?.optJSONArray("items")
                    ?: run { checking = false; return@launch Prend.log("· 골목 목적지: 주차장을 찾지 못함(인터넷)") }
                val lots = (0 until arr.length()).map { arr.getJSONObject(it) }
                    // 이름이 주차장인 곳만(중계 서버가 주차장 갈래를 모르는 옛판이면 밥집이 온다)
                    .filter { "주차장" in it.optString("name") }
                    .map { o ->
                        val lat = o.optDouble("lat"); val lon = o.optDouble("lon")
                        Place(o.optString("name"), o.optString("category"), o.optString("address"), lat, lon, null, Together.meters(d.lat, d.lon, lat, lon))
                    }
                    .filter { (it.meters ?: Int.MAX_VALUE) <= LOT_MAX_M }.sortedBy { it.meters }.take(3)
                Prend.log("· 골목 목적지: ${LOT_MAX_M}m 안 공영주차장 ${lots.size}곳")
                pick(d, lots, 0)
            }
        }
    }

    /** 가까운 것부터 그 자리 도로가 큰길인지 본다. 큰길가가 없으면 권하지 않는다 */
    private fun pick(d: Navi.Dest, lots: List<Place>, i: Int) {
        val lot = lots.getOrNull(i) ?: run { checking = false; return Prend.log("· 골목 목적지: 큰길가 주차장이 없어 권하지 않음") }
        val r = road() ?: run { checking = false; return }
        r.checkSpot(lot.lat, lot.lon) { k, what ->
            Prend.log("· 골목 목적지: 주차장 ${i + 1} 도로 $what → ${k?.label ?: "모름"}")
            if (checked != d) { checking = false; return@checkSpot }
            if (k == Road.Kind.MAIN) { offer = lot; checking = false } else pick(d, lots, i + 1)
        }
    }

    private fun answer(yes: Boolean) {
        val lot = pending
        pending = null
        Stage.ask(null)
        if (!yes) {
            val n = prefs.getInt("no", 0) + 1
            prefs.edit().putInt("no", n).apply()
            Prend.log("· 골목 목적지: 그대로(${n}번 이어)")
            return
        }
        prefs.edit().putInt("no", 0).apply()
        lot ?: return
        when (navi?.goVia(lot.lat, lot.lon, lot.name, lot.address)) {
            "waypoint" -> Stage.toast("${lot.name}, 경유지로 넣었어요", 3_000)
            "route" -> Stage.toast("${lot.name}, 길 안내를 시작해요", 3_000)
            else -> Stage.toast("내비에 넣지 못했어요")
        }
    }

    /** 초보 운전을 다시 켜면 '그대로' 셈을 지운다 */
    fun reset() { prefs.edit().putInt("no", 0).apply() }

    /** 시험: 같은 목적지를 다시 본다 */
    fun again() { checked = null; offer = null; checking = false; Prend.log("· 시험: 골목 목적지 다시 봄") }

    fun release() { scope.cancel(); Stage.sideAsks.remove("ALLEY") }

    companion object {
        const val LOT_MAX_M = 400
        const val CARD_MS = 40_000L
        const val NO_MAX = 2
        /** PREND가 길을 넣은 뒤 이만큼 안에 받은 목적지는 PREND가 넣은 것으로 본다 */
        const val SELF_MS = 60_000L
        /** 내비 즐겨찾기의 집·회사 이름 */
        val HOME_WORDS = listOf("집", "우리집", "회사", "직장")

        /** 주소에서 검색에 쓸 동네: 구(군·시) 이름과 동(읍·면·가) 이름. "서울 종로구 익선동 166" → "종로구 익선동". 동이 없으면 앞의 두 낱말 */
        fun area(address: String): String? {
            val w = address.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            if (w.isEmpty()) return null
            val gu = w.firstOrNull { it.endsWith("구") || it.endsWith("군") }
            val dong = w.firstOrNull { it.length >= 2 && (it.endsWith("동") || it.endsWith("읍") || it.endsWith("면") || Regex(".+[0-9]*가$").matches(it)) }
            return when {
                dong != null -> listOfNotNull(gu, dong).joinToString(" ")
                else -> w.take(2).joinToString(" ")
            }
        }
    }
}
