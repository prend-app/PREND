package app.prend.ride

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import app.prend.Prend
import app.prend.car.CarState
import app.prend.car.Navi
import app.prend.net.Relay
import app.prend.ui.Pick
import app.prend.ui.Place
import app.prend.ui.PrendActivity
import app.prend.ui.Scene
import app.prend.ui.Stage
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 같이 고르기(사용자 결정 10/26 밤): 세워 둔 차의 큰 화면에서 같이 탄 사람들이 함께 보고 이야기하며 고른다.
 * 폰은 한 사람이 보고 돌려 보여 줘야 하지만, 차 화면은 앞자리 둘이 같이 보고 뒷자리는 말로 낀다.
 * - 지금 동네(내비 주소, 구·동)로 중계 서버를 거쳐 NAVER 지역 검색을 부르고, 블로그 글 수를 후기 수로 붙인다
 * - 결과 좌표로 지금 위치에서 거리를 재어 5km 넘는 곳(이름이 같은 다른 동네)은 버리고 가까운 순으로 다섯 곳까지
 * - 고르면 차 내비에 경유지(안내 중일 때) 또는 목적지로 넣는다
 * - 세워 둔 동안만 연다. 열어 둔 채 차가 움직이면 닫는다(주행 중에는 차 화면의 긴 목록이 막힌다)
 * - 먼저 묻기: 경로를 따라가다 막 세웠고, 밥때이고, 같이 탄 사람이 있으면 한 번 "가는 길에 같이 먹을 곳 골라 볼까요?"(3시간에 한 번)
 * 동네 이름은 검색에만 쓰고 앱 기록에는 남기지 않는다. 위치 보내기를 껐으면(ride/GeoShare) 찾지 않고 그 까닭을 한 줄로 보인다.
 */
class Together(private val context: Context, private val navi: Navi?) {

    private val scope = MainScope()
    private var job: Job? = null
    private var askedAt = 0L
    /** P에 둔 때(elapsedRealtime). 0이면 세워 두지 않음 */
    private var parkedAt = 0L

    init { Stage.onPick = { what, arg -> on(what, arg) } }

    private fun now() = SystemClock.elapsedRealtime()

    private fun on(what: String, arg: String) {
        when (what) {
            "open" -> { load(arg.ifBlank { "food" }); showScreen() }
            "kind" -> load(arg)
            "retry" -> load(Stage.ui.value.pick.kind)
            "choose" -> Stage.ui.update { it.copy(pick = it.pick.copy(chosen = arg.toIntOrNull() ?: -1)) }
            "go" -> go()
        }
    }

    /** 화면이 안 보이면 먼저 열어 본다(이번 운행에 PREND를 연 적이 있으면 Pleos 홈 화면이 띄운다). 막히면 배너 [열기] */
    private fun showScreen() {
        if (Stage.ui.value.visible) return
        runCatching { context.startActivity(Intent(context, PrendActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun load(kind: String) {
        // 위치 보내기를 껐으면(ride/GeoShare, 사용자 결정 11/06) 동네 이름도 보내지 않는다
        if (!GeoShare.on) { Stage.ui.update { it.copy(pick = Pick(kind, error = GeoShare.OFF_LINE)) }; return }
        val w = navi?.last
        val area = w?.address?.takeIf { it.isNotBlank() }
        if (w == null || area == null) {
            Stage.ui.update { it.copy(pick = Pick(kind, error = "지금 위치를 아직 몰라요")) }
            return
        }
        Stage.ui.update { it.copy(pick = Pick(kind, area, loading = true)) }
        job?.cancel()
        job = scope.launch {
            val arr = Relay.get("/places", mapOf("area" to area, "kind" to kind))?.optJSONArray("items")
            if (arr == null) {
                Stage.ui.update { it.copy(pick = it.pick.copy(loading = false, error = "인터넷이 안 되거나 지금은 찾지 못했어요")) }
                return@launch
            }
            val all = (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
                val lat = o.optDouble("lat"); val lon = o.optDouble("lon")
                Place(o.optString("name"), o.optString("category"), o.optString("address"), lat, lon,
                    if (o.isNull("reviews")) null else o.optInt("reviews"), meters(w.lat, w.lon, lat, lon))
            }
            val near = all.filter { (it.meters ?: 0) <= 5_000 }.sortedBy { it.meters ?: Int.MAX_VALUE }.take(5)
            Prend.log("· 같이 고르기: ${if (kind == "cafe") "카페" else "밥집"} ${near.size}곳(찾은 ${all.size}곳 중 5km 안)")
            Stage.ui.update { it.copy(pick = it.pick.copy(loading = false, items = near, chosen = -1, error = if (near.isEmpty()) "가까운 곳을 찾지 못했어요" else null)) }
        }
    }

    private fun go() {
        val p = Stage.ui.value.pick
        val place = p.items.getOrNull(p.chosen) ?: return
        when (navi?.goVia(place.lat, place.lon, place.name, place.address)) {
            "waypoint" -> { Stage.go(Scene.HOME); Stage.toast("${place.name}, 경유지로 넣었어요", 3_000) }
            "route" -> { Stage.go(Scene.HOME); Stage.toast("${place.name}, 길 안내를 시작해요", 3_000) }
            else -> Stage.toast("내비에 넣지 못했어요")
        }
    }

    /** 1초마다: 세운 때를 적고, 같이 고르기를 열어 둔 채 차가 움직이면 닫는다 */
    fun tick(car: CarState) {
        if (car.parked) { if (parkedAt == 0L) parkedAt = now() } else parkedAt = 0L
        if (Stage.ui.value.scene == Scene.PICK && !car.parked) {
            Stage.go(Scene.HOME)
            Stage.toast("세우면 다시 같이 골라요")
        }
    }

    /**
     * 먼저 물을 때인가: 경로를 따라가다 막 세웠고(20초~2분), 밥때이고, 조수석에 같이 탄 사람이 있고, 3시간 안에 묻지 않았다.
     * 조수석이 비고 뒷자리에만 사람이 있으면 모시는 길이라 묻지 않는다(별첨 2 §5-4, 감사 F88: 손님에게 같이 먹을 곳을 고르자고 하지 않게)
     */
    fun shouldAsk(car: CarState): Boolean {
        if (!car.parked || parkedAt == 0L) return false
        val stopped = now() - parkedAt
        if (stopped < 20_000 || stopped > 120_000) return false
        if (askedAt > 0 && now() - askedAt < 3 * 60 * 60_000L) return false
        if (navi?.last?.routing != true) return false
        val c = Calendar.getInstance()
        val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val meal = m in 11 * 60..13 * 60 + 30 || m in 17 * 60 + 30..19 * 60 + 30
        val together = car.seats["ROW_1_RIGHT"] == true
        return meal && together
    }

    fun asked() { prevAskedAt = askedAt; askedAt = now() }

    /** 물으려다 끊겨 줄에 되돌렸다(통화·포커스·깜빡이): 물은 것으로 치지 않는다(3시간 쉬기에 걸려 편한 때 다시 못 묻지 않게) */
    fun unask() { askedAt = prevAskedAt }
    private var prevAskedAt = 0L

    /** 물은 때(벽시계, 묻지 않았으면 0). 같은 운행 중 프로세스가 다시 떠도 3시간 쉬기를 잇는다(ride/Care.keep) */
    var askedWall: Long
        get() = RideKeep.wall(askedAt)
        set(w) { askedAt = RideKeep.elapsed(w) }

    fun release() {
        scope.cancel()
        Stage.onPick = null
    }

    companion object {
        /** 두 점 사이의 곧은 거리(m). 차 돌봄(ride/CarCare)의 가까운 블루핸즈도 쓴다 */
        fun meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int? {
            if (lat2.isNaN() || lon2.isNaN()) return null
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
            return (2 * r * asin(sqrt(a))).toInt()
        }
    }
}
