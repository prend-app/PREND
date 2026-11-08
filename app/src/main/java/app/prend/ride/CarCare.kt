package app.prend.ride

import android.content.Context
import android.os.SystemClock
import app.prend.Prend
import app.prend.car.CarHub
import app.prend.car.CarState
import app.prend.car.Navi
import app.prend.net.Relay
import app.prend.ui.Ask
import app.prend.ui.CareRow
import app.prend.ui.Pick
import app.prend.ui.Place
import app.prend.ui.Scene
import app.prend.ui.Stage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 차 돌봄(11/01 사용자 "초보운전자들이 뭘 수리해야 할지도 모르고 수리했을 때 어디 가야 하는지, 비용이 얼마나 드는지를 잘 모르잖아 … 와이퍼, 엔진오일, 필터 등등.
 * 기존에 교체 주기를 알려 주지만 디테일한 건 알려 준다고 생각하지 않아서").
 * - 무엇·언제: 항목마다 현대차 공식 주기(ride/CareBook)와 이 차의 누적 주행거리(Pleos Odometer)로 '갈 때예요'·'약 ○km 뒤'를 센다.
 *   간 때를 모르면 새 차부터 센다(Pleos Connect는 2026년 새 차부터 들어간다). 다만 PREND가 처음 본 거리가 1,000km를 넘었으면 거리 항목은
 *   '간 때를 몰라요'로 두고 [갈았어요]를 권한다(검토 11/07). "와이퍼 갈았어"·[갈았어요]로 그때 거리와 날짜를 적고, 말로 적었으면 15초 동안 [되돌리기]
 * - 어디서·얼마: 항목을 펼치면 이럴 때 가는지, 직접 할 수 있는지, 맡길 때 무엇을 말하는지, 값을 어디서 보는지, 견적서·명세서 같은 권리를 보여 준다.
 *   [가까운 블루핸즈]는 지금 동네 이름으로 네이버 지역 검색(중계 서버 /places kind=service)을 해 가까운 세 곳을 보이고, 고르면 내비에 넣는다
 * - 먼저 알리기: 갈 때가 된 항목이 있으면, 달려 와서 P에 넣고 20초 뒤 다른 말·카드가 없을 때 카드 한 장만(목소리 없이, 말 차례 ride/Floor).
 *   하루 한 번, [나중에]면 그 항목은 1,000km나 2주 뒤에 다시. 대리 운행·조용히 가기면 띄우지 않는다
 * 기억에는 항목마다 간 때의 거리(km)와 날짜만 남긴다
 */
class CarCare(context: Context, private val hub: () -> CarHub?, private val navi: Navi?, private val floor: Floor?, private val idle: () -> Boolean) {

    private val prefs = context.getSharedPreferences("care", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    /** 마지막으로 읽은 누적 주행거리(km). 시험 값(fakeKm)이 있으면 그것 */
    private var km: Float? = null
    /** 지금 아는 누적 주행거리(정비한 날 적기, ride/ShopVisit) */
    val kmNow: Float? get() = km
    private var fakeKm: Float? = null
    /** 이번 운행에 시속 10km 넘게 달렸나(달려 와서 세웠을 때만 카드를 본다) */
    private var drove = false
    private var parkedAt = 0L
    /** 이번에 세운 동안 카드를 이미 봤나(띄웠거나 띄울 것이 없었다) */
    private var parkChecked = false
    private var cardAt = 0L
    private var cardKey: String? = null
    /** 시험: 하루 한 번·나중에를 보지 않고 다음 정차에 띄운다 */
    private var forceCard = false

    init {
        Stage.onCare = { what, arg -> on(what, arg) }
        if (!prefs.contains("seenAt")) prefs.edit().putLong("seenAt", System.currentTimeMillis()).apply()
    }

    private fun on(what: String, arg: String) {
        when (what) {
            "open" -> {
                // 카드가 떠 있는 채로 사람이 다른 항목을 열면 카드를 거둔다(다음 틱에 카드 항목으로 바뀌지 않게)
                if (Stage.ui.value.ask?.id == "CARE") Stage.ask(null)
                set { it.copy(open = arg.ifBlank { null }?.takeIf { k -> CareBook.page(k) != null }, shops = null) }
                read { refresh() }
            }
            "item" -> set { it.copy(open = arg, shops = null) }
            "list" -> set { it.copy(open = null, shops = null) }
            "back" -> set { it.copy(shops = null) }
            "shops" -> shops()
            "choose" -> arg.toIntOrNull()?.let { n -> set { c -> c.copy(shops = c.shops?.copy(chosen = n)) } }
            "go" -> go()
            "done", "done_said" -> done(arg, said = what == "done_said")
            "later" -> later(arg)
            "answer" -> answer(arg == "yes")
        }
    }

    private fun set(block: (app.prend.ui.CareUi) -> app.prend.ui.CareUi) = Stage.ui.update { it.copy(care = block(it.care)) }

    private fun now() = SystemClock.elapsedRealtime()

    /** 누적 주행거리를 읽고 다음 일을 한다(차가 알려 주지 않으면 그대로). 처음 읽은 거리는 '처음 본 거리'로 적어 둔다 */
    private fun read(then: () -> Unit) {
        var called = false
        val done = {
            if (!called) {
                called = true
                if (km != null && !prefs.contains("seenKm")) prefs.edit().putFloat("seenKm", km!!).apply()
                then()
            }
        }
        if (fakeKm != null) { km = fakeKm; return done() }
        val h = hub() ?: return done()
        // 차가 답하지 않으면(권한·서비스) 2초 뒤 아는 값으로 잇는다(버튼이 아무 반응 없이 사라지지 않게)
        scope.launch { kotlinx.coroutines.delay(READ_WAIT_MS); done() }
        // 시험 값을 넣는 사이에 늦게 온 차의 값은 쓰지 않는다
        h.readOdometer { v -> scope.launch { if (fakeKm == null && v != null && v > 0f) km = v; done() } }
    }

    /** 이 차의 동력(전기·하이브리드·엔진). 차가 알려 주지 않으면 UNKNOWN */
    private fun power(): CareBook.Power {
        val h = hub() ?: return CareBook.Power.UNKNOWN
        return when (h.electric) {
            true -> CareBook.Power.EV
            // 그랜저 하이브리드만 엔진오일 주기가 다르다(1만km). 그 밖의 하이브리드는 공식 주기를 몰라 세지 않는다
            false -> if (!h.hybrid) CareBook.Power.GAS
                else if (h.model?.uppercase()?.let { "GRANDEUR" in it || "그랜저" in it } == true) CareBook.Power.HYBRID_GRANDEUR else CareBook.Power.HYBRID
            null -> CareBook.Power.UNKNOWN
        }
    }

    /** 화면의 줄을 다시 센다 */
    private fun refresh() {
        val p = power()
        if (p != CareBook.power) { CareBook.power = p; Prend.log("· 차 돌봄: 동력 ${p.name}(모델 ${hub()?.model})") }
        val wall = System.currentTimeMillis()
        val rows = CareBook.pages(p).map { row(it, km, wall) }
        // 간 때를 모르는 항목이 있으면 목록 아래 한 줄이 [갈았어요]를 권한다(CareBook.FOOT)
        CareBook.unknown = rows.any { it.state == UNKNOWN }
        set { it.copy(km = km?.roundToInt(), electric = p == CareBook.Power.EV, rows = rows) }
    }

    /**
     * 이 항목을 마지막으로 간 거리. 적어 둔 것이 없으면 새 차(0km)부터 센다. 다만 PREND가 처음 본 거리가 1,000km를 넘었으면
     * (출고 몇 달 뒤에 깔았거나 정비를 먼저 받은 차, 중고차) 모른다(null): 새 차부터 세면 이미 간 부품도 '새 차부터 1만km 달렸어요'로
     * '갈 때'가 되어 버린다(검토 11/07 멋대로 판단. 전에는 처음 본 거리가 그 주기를 넘었을 때만 몰랐다)
     */
    private fun lastKm(p: CareBook.Page): Float? {
        if (prefs.contains("km_${p.key}")) return prefs.getFloat("km_${p.key}", 0f)
        val seen = prefs.getFloat("seenKm", km ?: 0f)
        return if (p.km != null && seen > UNKNOWN_FROM_KM) null else 0f
    }

    /** 이 항목을 마지막으로 간 날. 적어 둔 것이 없으면 PREND가 이 차를 처음 본 날부터 센다 */
    private fun lastAt(key: String) = prefs.getLong("at_$key", prefs.getLong("seenAt", System.currentTimeMillis()))

    private fun row(p: CareBook.Page, now: Float?, wall: Long): CareRow {
        if (p.yearly) return yearlyRow(p, wall)
        val base = lastKm(p)
        // 거리 주기가 있는데 간 때의 거리를 모르면 개월도 세지 않는다(중고차에 '약 12달 뒤'·'갈 때'가 나오지 않게)
        if (p.km != null && base == null) return CareRow(p.key, p.label, UNKNOWN, diy = p.diy != null)
        val leftKm = p.km?.let { k -> if (now != null && base != null) base + k - now else null }
        val leftDays = p.months?.let { m -> ((lastAt(p.key) + m * MONTH_MS - wall) / DAY_MS).toLong() }
        val due = (leftKm != null && leftKm <= 0f) || (leftDays != null && leftDays <= 0)
        val soon = !due && ((leftKm != null && leftKm <= SOON_KM) || (leftDays != null && leftDays <= SOON_DAYS))
        val state = when {
            due -> p.dueState
            leftKm != null && (leftDays == null || leftDays * DAY_KM_GUESS > leftKm) -> "약 ${km(leftKm)} 뒤"
            leftDays != null -> if (leftDays < 45) "약 ${leftDays}일 뒤" else "약 ${(leftDays / 30.4).roundToInt()}달 뒤"
            else -> p.check
        }
        return CareRow(p.key, p.label, state, due, soon, p.diy != null)
    }

    /** 해마다 3월 1일에 열리는 회차(무료 점검): 이번 회차에 받았다고 적었는가 */
    private fun yearlyRow(p: CareBook.Page, wall: Long): CareRow {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = wall }
        val year = if (c.get(java.util.Calendar.MONTH) >= java.util.Calendar.MARCH) c.get(java.util.Calendar.YEAR) else c.get(java.util.Calendar.YEAR) - 1
        val start = java.util.Calendar.getInstance().apply { clear(); set(year, java.util.Calendar.MARCH, 1) }.timeInMillis
        val got = prefs.getLong("at_${p.key}", 0L) >= start
        return CareRow(p.key, p.label, if (got) "올해 받았어요" else "1년에 한 번", diy = false)
    }

    /** 1,234.5 → "1,200km"(백 단위로 둥글게) */
    private fun km(v: Float): String = "%,dkm".format(((v / 100f).roundToInt() * 100).coerceAtLeast(0))

    /** 갈 때가 된 항목(카드에 띄울 것). [나중에] 한 항목은 1,000km나 2주가 지나기 전에는 건너뛴다 */
    private fun dueNow(): CareBook.Page? {
        val wall = System.currentTimeMillis()
        return CareBook.pages(power()).firstOrNull { p ->
            val r = row(p, km, wall)
            val snoozedKm = prefs.getFloat("laterKm_${p.key}", -1f)
            val snoozedAt = prefs.getLong("laterAt_${p.key}", 0L)
            val snoozed = snoozedAt > 0 && wall - snoozedAt < LATER_MS && (km ?: 0f) - snoozedKm < LATER_KM
            // 대답 없이 거둔 카드는 그 항목을 사흘 쉰다(아무도 못 본 카드가 날마다 다시 뜨지 않게)
            val quiet = wall < prefs.getLong("skipUntil_${p.key}", 0L)
            r.due && (forceCard || !snoozed && !quiet)
        }
    }

    // ── 먼저 알리기: 달려 와서 P에 넣고 20초 뒤, 카드 한 장 ──

    fun newRide() { drove = false; parkedAt = 0L; parkChecked = false; read { refresh() } }

    fun onCar(old: CarState, car: CarState) {
        if (car.speedKmh > 10f) drove = true
        if (old.parked && !car.parked) { parkedAt = 0L; parkChecked = false }
    }

    /** 1초마다 */
    fun tick(car: CarState) {
        val ui = Stage.ui.value
        // 화면을 열어 둔 채 출발하면 닫는다(차 화면의 긴 글은 세워 둔 동안만)
        if (ui.scene == Scene.CARE && !car.parked) { Stage.go(Scene.HOME); Stage.toast("세우면 다시 볼 수 있어요") }
        if (ui.ask?.id == "CARE") {
            when {
                // 배너의 [자세히]는 화면을 바로 연다(대답이 오지 않는다)
                ui.scene == Scene.CARE -> { Stage.ask(null); on("open", cardKey.orEmpty()) }
                !car.parked || now() - cardAt > CARD_MS -> {
                    Stage.ask(null)
                    cardKey?.let { prefs.edit().putLong("skipUntil_$it", System.currentTimeMillis() + SKIP_MS).apply() }
                    Prend.log("· 차 돌봄 카드 거둠(${if (car.parked) "30초" else "출발"}, 대답 없음 → 그 항목 사흘 쉼)")
                }
            }
        }
        if (!car.parked) return
        if (parkedAt == 0L) { parkedAt = now(); read { refresh() }; return }
        if (parkChecked || now() - parkedAt < WAIT_MS) return
        // 세운 지 3분이 지나도 차례가 오지 않으면 이번에는 그만둔다
        if (now() - parkedAt > GIVE_UP_MS) { parkChecked = true; return }
        if (!drove && !forceCard) { parkChecked = true; return }
        val p = dueNow() ?: run { parkChecked = true; return }
        val today = day()
        if (!forceCard && prefs.getString("cardDay", null) == today) { parkChecked = true; return }
        if (ui.daeri || ui.quietRide) { parkChecked = true; Prend.log("· 차 돌봄 카드 안 띄움(${if (ui.daeri) "대리 운행" else "조용히 가기"})"); return }
        if (ui.ask != null || !idle() || ui.scene != Scene.HOME) return
        if (floor?.why(Floor.Kind.ASK) != null) return
        parkChecked = true
        forceCard = false
        cardKey = p.key
        cardAt = now()
        prefs.edit().putString("cardDay", today).apply()
        floor?.shown("차 돌봄 · ${p.label}")
        val byKm = p.km != null && lastKm(p)?.let { b -> km?.let { it - b >= p.km } } == true
        val since = when {
            prefs.contains("km_${p.key}") || prefs.contains("at_${p.key}") -> "지난번 뒤로"
            byKm -> "새 차부터"
            else -> "처음 본 뒤로"
        }
        Stage.ask(Ask("CARE", "${p.label} ${p.dueTitle}", "$since ${whyDue(p)} · 할 일과 맡길 곳을 정리해 뒀어요", "자세히", "나중에", opens = Scene.CARE))
        Prend.log("◆ 차 돌봄 카드: ${p.label}(카드만)")
    }

    /** 왜 갈 때인가: 달린 거리가 주기를 넘었으면 거리, 아니면 지난 달 수 */
    private fun whyDue(p: CareBook.Page): String {
        val driven = lastKm(p)?.let { b -> km?.let { it - b } }
        return if (p.km != null && driven != null && driven >= p.km) "${km(driven)} 달렸어요"
        else "${((System.currentTimeMillis() - lastAt(p.key)) / MONTH_MS).roundToInt()}달이 지났어요"
    }

    private fun answer(yes: Boolean) {
        val key = cardKey
        Stage.ask(null)
        if (yes) Stage.openCare(key.orEmpty()) else key?.let { later(it) }
    }

    private fun later(key: String) {
        val p = CareBook.page(key) ?: return
        prefs.edit().putFloat("laterKm_$key", km ?: 0f).putLong("laterAt_$key", System.currentTimeMillis()).apply()
        Stage.toast("${p.label}, 1,000km나 2주 뒤에 다시 알려 드릴게요", 3_000)
        Prend.log("· 차 돌봄: ${p.label} 나중에")
    }

    /**
     * 갈았다(버튼·"와이퍼 갈았어"): 그때의 거리와 날짜를 적는다. 말로 적었으면 지난 기록을 남겨 두고 15초 동안 [되돌리기] 카드를 띄운다
     * (검토 11/07 멋대로 판단: 잘못 알아들은 말 한마디가 지난 기록을 덮어, 정말 갈 때가 와도 알리지 못했다. 운전 중에는 적기 전에 묻기보다 되돌리기가 덜 번거롭다)
     */
    private fun done(key: String, said: Boolean) {
        val p = CareBook.page(key) ?: return Stage.toast("무엇을 가셨는지 말씀해 주세요. 예: “와이퍼 갈았어”", 3_000)
        val before = if (said) was(key) else null
        prefs.edit().putLong("at_$key", System.currentTimeMillis()).remove("laterKm_$key").remove("laterAt_$key").remove("skipUntil_$key").apply()
        read {
            km?.let { prefs.edit().putFloat("km_$key", it).apply() }
            refresh()
            val at = km?.let { " ${"%,d".format(it.roundToInt())}km에서" } ?: ""
            Prend.log("· 차 돌봄: ${p.label} ${doneWord(p)} 것으로 적음(${if (said) "말" else "버튼"})")
            // 다른 카드가 떠 있으면 그 카드를 덮지 않는다(전처럼 한 줄만). 확인 카드는 첫 화면에만 그리므로 차 돌봄 화면 같은 다른 화면에서도 한 줄만
            if (before == null || Stage.ui.value.ask != null || Stage.ui.value.scene != Scene.HOME) {
                if (before != null) Prend.log("· 차 돌봄: 다른 카드가 떠 있거나 첫 화면이 아니라 되돌리기 카드는 띄우지 않음")
                Stage.toast("${p.label}, 오늘$at ${doneWord(p)} 것으로 적어 둘게요", 3_000)
                return@read
            }
            Stage.confirm("CAREUNDO", "${p.label} 오늘 ${doneWord(p)} 것으로 적었어요", "${if (at.isNotEmpty()) "${at.trim()} 적었어요 · " else ""}잘못 적었으면 되돌릴게요",
                "그대로", "되돌리기", listen = false, timeoutMs = UNDO_MS) { a ->
                if (a != false) return@confirm
                putBack(key, before)
                refresh()
                Stage.toast("${p.label} 기록을 되돌렸어요", 2_500)
                Prend.log("· 차 돌봄: ${p.label} 말로 적은 것을 되돌림")
            }
        }
    }

    /** 한 항목의 기록(간 날·거리, [나중에]·대답 없이 쉬던 때). 없는 값은 null */
    private data class Was(val at: Long?, val km: Float?, val laterKm: Float?, val laterAt: Long?, val skipUntil: Long?)

    private fun was(key: String) = Was(
        prefs.getLong("at_$key", 0L).takeIf { prefs.contains("at_$key") },
        prefs.getFloat("km_$key", 0f).takeIf { prefs.contains("km_$key") },
        prefs.getFloat("laterKm_$key", 0f).takeIf { prefs.contains("laterKm_$key") },
        prefs.getLong("laterAt_$key", 0L).takeIf { prefs.contains("laterAt_$key") },
        prefs.getLong("skipUntil_$key", 0L).takeIf { prefs.contains("skipUntil_$key") },
    )

    /** 말로 적기 전의 기록으로 되돌린다(없던 값은 지운다) */
    private fun putBack(key: String, w: Was) {
        val e = prefs.edit()
        if (w.at != null) e.putLong("at_$key", w.at) else e.remove("at_$key")
        if (w.km != null) e.putFloat("km_$key", w.km) else e.remove("km_$key")
        if (w.laterKm != null) e.putFloat("laterKm_$key", w.laterKm) else e.remove("laterKm_$key")
        if (w.laterAt != null) e.putLong("laterAt_$key", w.laterAt) else e.remove("laterAt_$key")
        if (w.skipUntil != null) e.putLong("skipUntil_$key", w.skipUntil) else e.remove("skipUntil_$key")
        e.apply()
    }

    /** [갈았어요]·[채웠어요]… → "간"·"채운"… */
    private fun doneWord(p: CareBook.Page) = when (p.doneLabel) { "바꿨어요" -> "바꾼"; "채웠어요" -> "채운"; "점검했어요" -> "점검한"; "받았어요" -> "받은"; else -> "간" }

    // ── 가까운 블루핸즈 ──

    private fun shops() {
        // 위치 보내기를 껐으면(ride/GeoShare, 사용자 결정 11/06) 동네 이름도 보내지 않는다
        if (!GeoShare.on) { set { it.copy(shops = Pick("service", error = GeoShare.OFF_LINE)) }; return }
        val w = navi?.last
        val area = w?.address?.takeIf { it.isNotBlank() }
        if (w == null || area == null) { set { it.copy(shops = Pick("service", error = "지금 위치를 아직 몰라요")) }; return }
        set { it.copy(shops = Pick("service", area, loading = true)) }
        job?.cancel()
        job = scope.launch {
            val arr = Relay.get("/places", mapOf("area" to area, "kind" to "service"))?.optJSONArray("items")
            if (arr == null) { set { it.copy(shops = it.shops?.copy(loading = false, error = "인터넷이 안 되거나 지금은 찾지 못했어요")) }; return@launch }
            val all = (0 until arr.length()).map { arr.getJSONObject(it) }
                // 이름에 블루핸즈가 든 곳만(중계 서버가 정비소 갈래를 모르는 옛판이면 밥집이 올 수 있다)
                .filter { "블루핸즈" in it.optString("name") }
                .map { o ->
                    val lat = o.optDouble("lat"); val lon = o.optDouble("lon")
                    Place(o.optString("name"), o.optString("category"), o.optString("address"), lat, lon, null, Together.meters(w.lat, w.lon, lat, lon))
                }
            val near = all.filter { (it.meters ?: 0) <= SHOP_MAX_M }.sortedBy { it.meters ?: Int.MAX_VALUE }.take(3)
            Prend.log("· 차 돌봄: 블루핸즈 ${near.size}곳(찾은 ${all.size}곳 중 ${SHOP_MAX_M / 1000}km 안)")
            set { it.copy(shops = it.shops?.copy(loading = false, items = near, chosen = if (near.isEmpty()) -1 else 0, error = if (near.isEmpty()) "가까운 블루핸즈를 찾지 못했어요" else null)) }
        }
    }

    private fun go() {
        val s = Stage.ui.value.care.shops ?: return
        val place = s.items.getOrNull(s.chosen) ?: return
        when (navi?.goVia(place.lat, place.lon, place.name, place.address)) {
            "waypoint" -> { Stage.go(Scene.HOME); Stage.toast("${place.name}, 경유지로 넣었어요", 3_000) }
            "route" -> { Stage.go(Scene.HOME); Stage.toast("${place.name}, 길 안내를 시작해요", 3_000) }
            else -> Stage.toast("내비에 넣지 못했어요")
        }
    }

    private fun day() = SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(Date())

    // ── 시험 ──

    /** 누적 주행거리를 이 값으로 본다(null이면 차의 값) */
    fun pretendKm(v: Float?) {
        fakeKm = v
        Prend.log("· 시험: 차 돌봄 주행거리 ${v?.let { "${it.roundToInt()}km" } ?: "차의 값"}")
        if (v != null) { km = v; refresh() } else { km = null; read { refresh() } }
    }

    /** 다음 정차(또는 지금 P면 바로)에 하루 한 번·나중에를 보지 않고 카드를 본다 */
    fun forceNext() { forceCard = true; parkChecked = false; parkedAt = 0L; drove = true; Prend.log("· 시험: 차 돌봄 카드 다음 정차에") }

    /** 적어 둔 것을 모두 지운다(처음 본 날은 오늘, 처음 본 거리는 지금 거리로) */
    fun reset() {
        prefs.edit().clear().putLong("seenAt", System.currentTimeMillis()).apply { km?.let { putFloat("seenKm", it) } }.apply()
        refresh(); Prend.log("· 시험: 차 돌봄 기억 지움(처음 본 거리 ${km?.roundToInt() ?: "-"}km)")
    }

    /** [모두 잊기]: 정비한 때를 모두 지운다(처음 본 날·거리는 지금으로) */
    fun forget() {
        prefs.edit().clear().putLong("seenAt", System.currentTimeMillis()).apply { km?.let { putFloat("seenKm", it) } }.apply()
        refresh(); Prend.log("· 차 돌봄: 모두 잊기로 정비한 때를 지움")
    }

    fun dump() {
        val wall = System.currentTimeMillis()
        Prend.log("· 시험: 차 돌봄 · ${km?.roundToInt() ?: "-"}km · ${power().name} · " +
            CareBook.pages(power()).joinToString(" / ") { p -> row(p, km, wall).let { "${p.label} ${it.state}" } })
    }

    fun release() { scope.cancel(); Stage.onCare = null }

    companion object {
        const val SOON_KM = 1_000f
        const val SOON_DAYS = 30L
        /** 남은 날과 남은 거리 가운데 먼저 올 쪽을 고를 때 쓰는 하루 거리 어림(화면에 나오지 않는 내부 값) */
        const val DAY_KM_GUESS = 35f
        const val DAY_MS = 24 * 60 * 60_000L
        const val MONTH_MS = 30.44 * DAY_MS
        const val LATER_KM = 1_000f
        const val LATER_MS = 14 * DAY_MS
        const val WAIT_MS = 20_000L
        const val GIVE_UP_MS = 3 * 60_000L
        const val CARD_MS = 30_000L
        const val SKIP_MS = 3 * DAY_MS
        const val READ_WAIT_MS = 2_000L
        const val SHOP_MAX_M = 10_000
        /** 처음 본 거리가 이만큼을 넘으면 기록 없는 거리 항목을 새 차부터 세지 않고 '간 때를 몰라요'로 둔다(검토 11/07) */
        const val UNKNOWN_FROM_KM = 1_000f
        const val UNKNOWN = "간 때를 몰라요"
        /** 말로 적은 뒤 [되돌리기] 카드를 두는 시간 */
        const val UNDO_MS = 15_000L
    }
}
