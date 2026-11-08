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
import kotlinx.coroutines.flow.update

/**
 * 정비소에 간 날(11/02, 검토-방향노인초보 10-2. 사용자 "오늘 날짜 바로 띄워 주면서 기록하기 버튼만 누르면 오늘 정비했다고 되게").
 * - 내비로 찾아간 목적지 이름이 정비소(블루핸즈·오토큐·카센터·공업사·스피드메이트·타이어·정비소)이고, 도착해 P에 넣으면 소리 없는 카드 한 장:
 *   "오늘 11월 2일 정비, 기록해 둘까요? / 맡기기 전에 견적서를 받아 두세요 · 견적에 없는 수리는 내가 동의해야 해요 [기록하기][괜찮아요]".
 *   견적서 줄은 정비소에서의 권리(자동차관리법 시행규칙 제134조, 한국소비자원 정비 피해: 2024년 피해구제 40.3% 늘어남)를 맡기기 전에 한 줄로.
 *   하루 한 번, [괜찮아요]를 두 번 이어 고르면 다시 묻지 않는다. "오늘 정비했어"라고 말해도 적는다
 * - [기록하기]: 오늘 날짜와 그때 누적 주행거리만 적는다. 무엇을 고쳤는지는 차 돌봄에서 항목별로 고른다
 * - 90일 안에 "고친 데가 또 이상해"면 그 날짜와 무료 재정비 기간(시행규칙 제134조①2: 1년·2만km 안의 차 90일, 3년·6만km 안 60일, 그 밖 30일)을 알린다.
 *   앱은 차령을 몰라 거리로만 말한다
 * 기억: 정비한 날짜·거리, 카드를 띄운 날, [괜찮아요] 횟수. 정비소 이름·위치는 남기지 않는다
 */
class ShopVisit(context: Context, private val navi: Navi?, private val floor: Floor?, private val km: () -> Float?, private val idle: () -> Boolean) {

    private val prefs = context.getSharedPreferences("shop_visit", Context.MODE_PRIVATE)
    /** 길 안내 중이던 목적지 이름(경로가 끝나면 내비가 지우므로 도착할 때까지 메모리에만 들고 있는다) */
    private var lastName: String? = null
    /** 이 도착에 카드를 이미 봤나(도착 시각) */
    private var seenArrival = 0L
    private var cardAt = 0L

    init { Stage.sideAsks["SHOP"] = ::answer; publish() }

    private fun now() = SystemClock.elapsedRealtime()
    private fun today() = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)

    /** 1초마다 */
    fun tick(car: CarState) {
        val ui = Stage.ui.value
        if (ui.ask?.id == "SHOP" && (!car.parked || now() - cardAt > CARD_MS)) { Stage.ask(null); Prend.log("· 정비소 카드 거둠(${if (car.parked) "60초" else "출발"})") }
        val n = navi ?: return
        (n.destName ?: n.dest?.name)?.let { lastName = it }
        val arrived = n.arrivedAt
        if (arrived <= 0 || arrived == seenArrival || System.currentTimeMillis() - arrived > 10 * 60_000L) return
        if (!car.parked) return
        val name = lastName?.replace(" ", "") ?: return
        if (SHOP_WORDS.none { it in name }) { seenArrival = arrived; return }
        val why = when {
            ui.daeri -> "대리 운행"
            prefs.getInt("cardDay", -1) == today() -> "오늘 이미 띄움"
            prefs.getInt("no", 0) >= NO_MAX -> "두 번 이어 괜찮아요"
            else -> null
        }
        if (why != null) { seenArrival = arrived; Prend.log("· 정비소 도착: 카드 띄우지 않음($why)"); return }
        // 말 차례: 내리기 전 그때뿐이라 이번 정차에 다른 카드가 있었던 것만이면 띄운다(주차 자리·골목 목적지와 같다). 다른 말 중·말 뒤 20초는 기다린다.
        // 한 운행 여섯 번에는 막히지 않고 세기만 한다
        if (ui.ask != null || !idle() || ui.scene != Scene.HOME) return
        if (floor?.why(Floor.Kind.ASK, bound = true)?.let { it != Floor.STOP_SPOKEN } == true) return
        seenArrival = arrived
        cardAt = now()
        prefs.edit().putInt("cardDay", today()).apply()
        floor?.shown("정비소")
        Stage.ask(Ask("SHOP", "오늘 ${md(System.currentTimeMillis())} 정비, 기록해 둘까요?", "맡기기 전에 견적서를 받아 두세요 · 견적에 없는 수리는 내가 동의해야 해요", "기록하기", "괜찮아요"))
        Prend.log("◆ 정비소 도착 카드(카드만)")
    }

    private fun answer(yes: Boolean) {
        Stage.ask(null)
        if (yes) record("카드") else { prefs.edit().putInt("no", prefs.getInt("no", 0) + 1).apply(); Prend.log("· 정비소 카드: 괜찮아요(${prefs.getInt("no", 0)}번)") }
    }

    /** 오늘 정비했다고 적는다(카드 [기록하기]·"오늘 정비했어") */
    fun record(why: String) {
        val wall = System.currentTimeMillis()
        prefs.edit().putLong("at", wall).putInt("no", 0).apply { km()?.let { putFloat("km", it) } ?: remove("km") }.apply()
        publish()
        Prend.log("◆ 정비한 날 적음($why${if (km() != null) ", 거리 있음" else ""})")
        Stage.toast("${md(wall)} 정비를 적어 뒀어요 · 고친 항목은 차 돌봄에서 고를 수 있어요", 4_000)
    }

    /** "고친 데가 또 이상해": 90일 안이면 정비한 날과 무료 재정비 기간을 알린다 */
    fun again() {
        val at = prefs.getLong("at", 0L)
        val days = ((System.currentTimeMillis() - at) / 86_400_000L).toInt()
        if (at == 0L || days > 90) {
            Prend.log("· 고친 데가 또: 90일 안에 적은 정비 없음")
            Stage.toast("90일 안에 적어 둔 정비가 없어요 · 같은 고장이면 고친 정비소에 먼저 말하세요", 4_500)
            return
        }
        val k = prefs.getFloat("km", -1f).takeIf { it >= 0 }
        val limit = when {
            k != null && k <= 20_000 -> "이 차는 90일 안"
            k != null && k <= 60_000 -> "이 차는 60일 안(차가 1년이 안 됐으면 90일)"
            else -> "1년·2만km 안의 차 90일, 3년·6만km 안 60일, 그 밖 30일"
        }
        Prend.log("◆ 고친 데가 또: 정비한 지 ${days}일")
        Stage.toast("${md(at)}에 정비했어요(${if (days == 0) "오늘" else "${days}일 전"}) · 같은 고장이면 그 정비소에 무료 재정비를 먼저 말하세요 · $limit", 7_000)
        if (Stage.ui.value.parked) Stage.openCare("")
    }

    /** 차 돌봄 첫 화면의 한 줄 */
    private fun publish() {
        val at = prefs.getLong("at", 0L)
        Stage.ui.update { it.copy(care = it.care.copy(visit = if (at > 0) md(at) else null)) }
    }

    fun forget() { prefs.edit().clear().apply(); publish(); Prend.log("· 정비한 날: 모두 잊기") }

    /** 시험: 정비소 목적지에 막 도착한 것처럼 */
    fun pretendShop() { lastName = "블루핸즈 시험점"; prefs.edit().remove("cardDay").apply(); navi?.pretendArrived(); Prend.log("· 시험: 정비소 도착") }

    private fun md(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }.let { "${it.get(Calendar.MONTH) + 1}월 ${it.get(Calendar.DAY_OF_MONTH)}일" }

    companion object {
        /** 정비소 이름에 드는 말('서비스센터'만으로는 보지 않는다: 가전·통신 매장도 많다) */
        val SHOP_WORDS = listOf("블루핸즈", "오토큐", "카센터", "카센타", "공업사", "스피드메이트", "타이어", "정비소", "정비공장", "자동차정비", "오토서비스")
        const val NO_MAX = 2
        const val CARD_MS = 60_000L
    }
}
