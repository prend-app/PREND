package app.prend.ride

import android.content.Context
import android.os.SystemClock
import app.prend.Prend
import app.prend.car.CarState
import app.prend.car.Navi
import app.prend.ui.Ask
import app.prend.ui.Scene
import app.prend.ui.Stage
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.floor
import app.prend.net.Relay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update

/**
 * 해 지기 전에(11/02, 검토-방향노인초보 3-3). P에서 길 안내를 시작했는데 도착이 해 진 뒤면 출발 전에 소리 없는 카드 한 장.
 * 근거: 75세 이상의 52%가 10개 상황 가운데 밤 주행을 가장 어려워한다(한국형사·법무정책연구원 2021 조사), 경찰청은 야간 운전을 제한하는 조건부 면허를 추진 중.
 * - 켜는 조건: 밤 운전을 스스로 줄이는 사람(최근 운행 10번 이상 가운데 해 진 뒤에 달린 운행이 30번 중 2번 이하), "밤 운전 불편해"라고 말함, 또는 운전 도움 켬.
 *   나이는 묻지 않는다. [그만 알려요]면 끈다(말로 다시 켤 수 있다)
 * - 기억 화면 '듣고 보기'(3-9)에서 켜고 끈다. 거기서 끄면 운전 도움을 켜 둬도 끈 쪽이 이긴다. 켜진 까닭을 한 줄로 보인다(날씨 줄은 따로 둔다)
 * - 띄우는 때: 지금은 해 지기 전, 남은 시간 40분 넘음, 도착 예정이 해 지는 시각보다 30분 넘게 늦음. 하루 한 장, D로 바꾸면 띄우지 않는다.
 *   집이 아닌 곳에 도착해 "해는 몇 시에 져요"나, 전조등을 켜라는 말은 하지 않는다(전조등은 일반 앱이 못 읽음)
 * - 해 지는 시각은 내비의 지금 위치와 날짜로 기기 안에서 계산한다(NOAA 근사식, 분 단위). 위치는 보내지도 남기지도 않는다.
 *   날씨 줄만 목적지의 5km 격자 번호를 중계 서버로 보낸다. 위치 보내기를 껐으면(ride/GeoShare) 날씨 줄은 쉬고 해 지는 시각 줄만 둔다.
 *   기억하는 것은 운행마다 '해 진 뒤에 달렸나' 예·아니오(최근 30번)와 켬·끔, 카드를 띄운 날짜
 */
class Dusk(context: Context, private val navi: Navi?, private val floor: Floor?, private val idle: () -> Boolean) {

    private val prefs = context.getSharedPreferences("dusk", Context.MODE_PRIVATE)
    /** 이번에 본 목적지(메모리에만) */
    private var checked: Navi.Dest? = null
    private var checkedAt = 0L
    /** 이번 운행에 해 진 뒤 달렸나 */
    private var nightThisRide = false
    private var cardAt = 0L
    /** 시험: 해 지는 시각을 지금부터 이만큼 뒤로(분). null이면 계산 */
    @Volatile var fakeSunsetMin: Int? = null

    init { Stage.sideAsks["DUSK"] = ::answer; publish() }

    private fun now() = SystemClock.elapsedRealtime()

    /** 켜져 있나: [그만 알려요]·기억 화면으로 끄지 않았고, 운전 도움·말로 켬·기억 화면으로 켬·밤에 드문 사람 */
    fun wanted(): Boolean {
        if (prefs.getBoolean("off", false)) return false
        if (Stage.ui.value.novice || prefs.getBoolean("said", false)) return true
        val h = prefs.getString("rides", "").orEmpty()
        return h.length >= MIN_RIDES && h.count { it == '1' } <= NIGHT_MAX
    }

    /** 기억 화면 '듣고 보기'에 켬·끔과 켜진 까닭을 넣는다(바뀔 때만). 운전 도움을 켜고 끄면 함께 바뀌어 1초마다 맞춰 본다 */
    private fun publish() {
        val on = wanted()
        val why = when {
            !on -> null
            Stage.ui.value.novice -> "운전 도움을 켜 둬서"
            prefs.getBoolean("said", false) -> if (prefs.getBoolean("byMemo", false)) null else "밤 운전이 불편하다고 하셔서"
            else -> "쓰시는 모습에 맞춰 켜 뒀어요"
        }
        if (Stage.ui.value.let { it.dusk != on || it.duskWhy != why }) Stage.ui.update { it.copy(dusk = on, duskWhy = why) }
    }

    /** "밤 운전 불편해": 켠다 */
    fun sayOn() {
        prefs.edit().putBoolean("said", true).putBoolean("off", false).putBoolean("byMemo", false).apply()
        publish()
        Prend.log("· 해 지기 전에: 말로 켬")
        Stage.toast("출발할 때 도착이 해 진 뒤면 카드로 알려 드릴게요", 3_500)
    }

    /** 기억 화면에서 켜고 끈다. 끄면 운전 도움을 켜 둬도 알리지 않는다(날씨 줄은 그대로) */
    fun set(on: Boolean, why: String) {
        prefs.edit().apply { if (on) putBoolean("said", true).putBoolean("off", false).putBoolean("byMemo", true) else putBoolean("off", true).putBoolean("said", false) }.apply()
        publish()
        Prend.log("· 해 지기 전에: ${if (on) "켬" else "끔"}($why)")
    }

    /** 새 운행: 지난 운행이 해 진 뒤였나를 적는다(최근 30번) */
    fun newRide() {
        val h = (prefs.getString("rides", "").orEmpty() + if (nightThisRide) "1" else "0").takeLast(KEEP)
        prefs.edit().putString("rides", h).apply()
        nightThisRide = false
        checked = null
        publish()
    }

    fun forget() { prefs.edit().clear().apply(); publish(); Prend.log("· 해 지기 전에: 모두 잊기") }

    /** 1초마다. 시연 중에는 화면에서 바꾼 켬·끔을 되돌리지 않는다(시연이 끝나면 다음 틱에 맞춘다) */
    fun tick(car: CarState) {
        if (!Stage.ui.value.demo) publish()
        val ui = Stage.ui.value
        if (ui.ask?.id == "DUSK" && (!car.parked || now() - cardAt > CARD_MS)) { Stage.ask(null); Prend.log("· 해 지기 전에 카드 거둠(${if (car.parked) "40초" else "출발"})") }
        val w = navi?.last
        // 해 진 뒤에 달렸는지(시속 10 넘게)
        if (!nightThisRide && car.speedKmh > 10f && w != null && dark(w.lat, w.lon)) { nightThisRide = true; Prend.log("· 해 지기 전에: 이번 운행은 해 진 뒤에 달림") }
        val d = navi?.dest ?: run { checked = null; return }
        if (d != checked) { checked = d; checkedAt = now(); seen = false; wx = WX_NONE }
        if (seen) return
        // 목적지를 정한 뒤 남은 시간이 오기를 10초까지 기다린다
        val eta = navi.etaSec()
        if (eta == null) { if (now() - checkedAt > 10_000) { seen = true; Prend.log("· 해 지기 전에: 남은 시간을 받지 못해 보지 않음") }; return }
        if (!car.parked) { seen = true; return }
        if (ui.daeri) { seen = true; return }
        // PREND가 넣은 목적지(병원·쉼터·블루핸즈·주차장)는 보지 않는다
        if (System.currentTimeMillis() - navi.selfRouteAt < 60_000) { seen = true; return }
        if (w == null) { seen = true; return }
        val nowMs = System.currentTimeMillis()
        val arrive = nowMs + eta * 1000L
        val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        val pre = when {
            eta < MIN_ETA_S -> "가까운 길(${eta / 60}분)"
            prefs.getInt("cardDay", -1) == today -> "오늘 이미 띄움"
            else -> null
        }
        if (pre != null) { seen = true; Prend.log("· 출발 전 한 장: 띄우지 않음($pre)"); return }
        // 해 지는 시각 줄: 해 지기 전에를 켠 사람만(밤에 드문 사람·말로 켬·운전 도움)
        val sunset = sunsetAt(w.lat, w.lon)
        val duskWhy = when {
            !wanted() -> "해 지기 전에 꺼짐"
            sunset == null -> "해 지는 시각 모름"
            nowMs >= sunset -> "이미 해 진 뒤"
            arrive - sunset < LATE_MS -> "해 지기 전에 도착"
            else -> null
        }
        // 날씨 줄(11/02, 베테랑도 쓰는 먼 길 한 장): 목적지 5km 격자의 도착 무렵 비·눈 예보(기상청). 지금 여기 와이퍼가 돌면(이미 비) 묻지 않는다.
        // 중계 서버에 격자 번호와 도착 시각만 보낸다. 답을 6초까지 기다린다. 위치 보내기를 껐으면(ride/GeoShare) 격자 번호도 보내지 않고 날씨 줄을 쉰다
        if (wx == WX_NONE) {
            if (prefs.getBoolean("wxOff", false) || car.wiperOn || !GeoShare.on && fakeWx == null) wx = WX_SKIP
            else {
                wx = WX_WAIT; wxAt = now(); wxKind = null
                val (nx, ny) = grid(d.lat, d.lon)
                val at = java.text.SimpleDateFormat("yyyyMMddHH", java.util.Locale.US).format(java.util.Date(arrive + 30 * 60_000L))
                scope.launch {
                    val j = fakeWx?.let { org.json.JSONObject().put("kind", it) } ?: Relay.get("/weather", mapOf("nx" to "$nx", "ny" to "$ny", "at" to at))
                    wxKind = j?.optString("kind")?.takeIf { it.isNotBlank() && it != "null" }
                    wx = WX_DONE
                    Prend.log("· 출발 전 한 장: 날씨 ${wxKind ?: if (j == null) "답 없음" else "비·눈 없음"}")
                }
            }
        }
        if (wx == WX_WAIT && now() - wxAt < WX_WAIT_MS) return
        val wxWord = when (wxKind) { "snow" -> "눈"; "rainsnow" -> "비나 눈"; "rain" -> "비"; else -> null }
        if (duskWhy != null && wxWord == null) { seen = true; Prend.log("· 출발 전 한 장: 띄우지 않음($duskWhy, 날씨 줄 없음)"); return }
        // 말 차례: 이번 정차에 다른 말·카드가 없을 때만(골목 목적지 카드가 먼저), 다른 말 중·말 뒤 20초는 기다린다.
        // 출발 전 그때뿐이라 한 운행 여섯 번에는 막히지 않고 세기만 한다
        if (ui.ask != null || !idle() || ui.scene != Scene.HOME) return
        if (floor?.why(Floor.Kind.ASK, bound = true) != null) return
        seen = true
        cardAt = now()
        prefs.edit().putInt("cardDay", today).apply()
        floor?.shown("출발 전 한 장")
        val dusk = duskWhy == null && sunset != null
        cardDusk = dusk; cardWx = wxWord != null
        val (title, text) = when {
            dusk && wxWord != null -> "도착하면 어둡고 $wxWord 예보가 있어요" to "해 지는 시각 ${hm(sunset!!)} · 도착 약 ${hm(arrive)} · 기상청 단기예보"
            dusk -> "도착하면 어두워요" to "해 지는 시각 ${hm(sunset!!)} · 도착 약 ${hm(arrive)}"
            else -> "도착 무렵 목적지에 $wxWord 예보가 있어요" to "도착 약 ${hm(arrive)} · 기상청 단기예보"
        }
        Stage.ask(Ask("DUSK", title, text, "알겠어요", "그만 알려요"))
        Prend.log("◆ 출발 전 한 장(카드만${if (dusk) ", 해 진 뒤 ${(arrive - sunset!!) / 60_000}분에 도착" else ""}${if (wxWord != null) ", $wxWord 예보" else ""})")
    }

    private var seen = false
    /** 이번 목적지의 날씨 묻기: 없음·기다림·끝·건너뜀 */
    private var wx = WX_NONE
    private var wxAt = 0L
    @Volatile private var wxKind: String? = null
    /** 띄운 카드에 든 줄([그만 알려요]는 그 줄들을 끈다) */
    private var cardDusk = false
    private var cardWx = false
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    /** 운행 서비스가 끝날 때: 묻던 날씨는 버리고 카드 대답 받기를 뗀다 */
    fun release() { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel(); Stage.sideAsks.remove("DUSK") }

    private fun answer(yes: Boolean) {
        Stage.ask(null)
        if (!yes) {
            prefs.edit().apply { if (cardDusk) putBoolean("off", true).putBoolean("said", false); if (cardWx) putBoolean("wxOff", true) }.apply()
            publish()
            Stage.toast(if (cardWx && !cardDusk) "목적지 날씨는 이제 알리지 않을게요" else if (cardWx) "해 지는 시각과 목적지 날씨는 이제 알리지 않을게요" else "해 지는 시각은 이제 알리지 않을게요", 2_500)
            Prend.log("· 출발 전 한 장: 그만 알려요 → 끔(해 ${cardDusk}, 날씨 $cardWx)")
        } else Prend.log("· 출발 전 한 장: 알겠어요")
    }

    /** 시험: 날씨 답을 흉내 낸다(snow·rainsnow·rain, off면 실제로 묻기) */
    @Volatile var fakeWx: String? = null

    fun dump() = Prend.log("· 해 지기 전에: 켬 ${wanted()}, 최근 운행 ${prefs.getString("rides", "")?.length ?: 0}번 중 해 진 뒤 ${prefs.getString("rides", "")?.count { it == '1' } ?: 0}번, 말로 켬 ${prefs.getBoolean("said", false)}(기억 화면 ${prefs.getBoolean("byMemo", false)}), 끔 ${prefs.getBoolean("off", false)}")

    /** 시험: 최근 운행 기록을 낮 운행 n번으로 채우고 오늘 띄운 기록을 지운다 */
    fun pretendDayRides(n: Int) { prefs.edit().putString("rides", "0".repeat(n.coerceIn(0, KEEP))).remove("cardDay").remove("off").apply(); Prend.log("· 해 지기 전에: 시험 낮 운행 ${n}번") }

    private fun hm(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }.let { "%d:%02d".format(it.get(Calendar.HOUR_OF_DAY).let { h -> if (h > 12) h - 12 else h }, it.get(Calendar.MINUTE)) }

    /** 지금 해가 져 있나(해 진 뒤이거나 해 뜨기 전) */
    private fun dark(lat: Double, lon: Double): Boolean {
        val t = System.currentTimeMillis()
        val set = sunsetAt(lat, lon) ?: return false
        val rise = sunAt(lat, lon, rise = true) ?: return false
        return t >= set || t < rise
    }

    private fun sunsetAt(lat: Double, lon: Double): Long? =
        fakeSunsetMin?.let { System.currentTimeMillis() + it * 60_000L } ?: sunAt(lat, lon, rise = false)

    /** 오늘(이 기기의 날짜) 해 뜨는·지는 시각(NOAA 근사식). 극지처럼 해가 안 지면 null */
    private fun sunAt(lat: Double, lon: Double, rise: Boolean): Long? {
        val local = Calendar.getInstance()
        val n = local.get(Calendar.DAY_OF_YEAR)
        val g = 2 * PI / 365 * (n - 1)
        val eq = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) - 0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) + 0.000907 * sin(2 * g) - 0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val la = Math.toRadians(lat)
        val cosH = (cos(Math.toRadians(90.833)) - sin(la) * sin(decl)) / (cos(la) * cos(decl))
        if (cosH < -1 || cosH > 1) return null
        val ha = Math.toDegrees(acos(cosH))
        val minUtc = 720 - 4 * (lon + if (rise) ha else -ha) - eq
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear(); set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
        }
        return utc.timeInMillis + (minUtc * 60_000).toLong()
    }

    /** 위경도 → 기상청 5km 격자(nx, ny). 기상청 단기예보 활용가이드의 람베르트 정각원추 변환(예시 37.488201, 126.929810 → 59, 125 확인) */
    private fun grid(lat: Double, lon: Double): Pair<Int, Int> {
        val d = PI / 180
        val re = 6371.00877 / 5.0
        val s1 = 30.0 * d; val s2 = 60.0 * d; val olon = 126.0 * d; val olat = 38.0 * d
        val sn = ln(cos(s1) / cos(s2)) / ln(tan(PI * 0.25 + s2 * 0.5) / tan(PI * 0.25 + s1 * 0.5))
        val sf = tan(PI * 0.25 + s1 * 0.5).pow(sn) * cos(s1) / sn
        val ro = re * sf / tan(PI * 0.25 + olat * 0.5).pow(sn)
        val ra = re * sf / tan(PI * 0.25 + lat * d * 0.5).pow(sn)
        var th = lon * d - olon
        if (th > PI) th -= 2 * PI
        if (th < -PI) th += 2 * PI
        th *= sn
        return floor(ra * sin(th) + 43 + 0.5).toInt() to floor(ro - ra * cos(th) + 136 + 0.5).toInt()
    }

    companion object {
        const val WX_NONE = 0
        const val WX_WAIT = 1
        const val WX_DONE = 2
        const val WX_SKIP = 3
        const val WX_WAIT_MS = 6_000L
        const val KEEP = 30
        const val MIN_RIDES = 10
        const val NIGHT_MAX = 2
        const val MIN_ETA_S = 40 * 60
        const val LATE_MS = 30 * 60_000L
        const val CARD_MS = 40_000L
    }
}
