package app.prend.ride

import android.content.Context
import android.os.SystemClock
import app.prend.Prend
import app.prend.car.CarState
import app.prend.ui.Ask
import app.prend.ui.Scene
import app.prend.ui.Stage
import app.prend.voice.openVoice
import java.util.Calendar
import kotlinx.coroutines.flow.update

/**
 * 운전 도움(옛 초보 운전, 사용자 결정 10/27, 11/02 이름 바꿈). 달리는 중에 가르치는 말은 오히려 방해가 되어, 당황하는 순간을 줄이는 쪽만 한다.
 * - 말 줄이기: 달리는 동안은 정체·같이 고르기 같은 말을 차가 설 때(신호 대기·P)까지 미룬다(Care). 쉼터 권하기는 그대로
 * - 젖은 길 한 줄(11/03, 검토-방향노인초보 3-6): 달리는 동안 와이퍼가 1분 넘게 이어 돌면 그날 한 번, 다음 편한 때(신호 대기·P).
 *   젖은 길은 제한속도의 20% 줄이기(도로교통법 시행규칙 제19조②), 앞차와는 평소의 두 배(한국도로공사 권고).
 *   신호 대기면 내비 제한속도에서 20%를 빼고 10 단위로 내린 숫자를 붙인다(80→60, 100→80, 50→40). 제한속도를 못 받거나 P면 숫자 없이. 지금 속도는 짚지 않는다.
 *   운전 도움이면 목소리(숫자마다 따로 녹음한 말)와 글 한 줄, 그 밖의 사람은 소리 없는 카드 [알겠어요][그만 알려요]. 대리 운행이면 하지 않는다.
 *   영하 한마디는 뺐다(11/02): 차의 도로 결빙 경고가 약 4℃에서 이미 알린다(아반떼 2027 설명서)
 * 경고등 알려 주기는 넣지 못했다: 일반 앱은 경고등을 읽을 수 없다(PRIVATE_CAR_SAFETY, 2주차 시험 2)
 * "운전 도움 켜 줘"·"초보예요"·기억 화면의 [운전 도움]으로 켜고 끈다. 이 차에 남기는 것은 켬·끔과 젖은 길 한 줄을 한 날, [그만 알려요]
 */
class Novice(
    context: Context, private val outside: () -> Float?, private val timing: Timing, private val floor: Floor,
    private val limit: () -> Int?, private val cardFree: () -> Boolean,
    /** 지금 목소리를 내지 않는 때인가(다른 앱 음악·조용히 가기). 그때는 운전 도움이어도 소리 없는 카드로 */
    private val silent: () -> Boolean,
    private val tip: (asset: String, label: String) -> Boolean,
) {

    private val app = context.applicationContext
    private val prefs = context.getSharedPreferences("novice", Context.MODE_PRIVATE)
    /** 젖은 길 한 줄이 생긴 때(10분 지나면 버린다. 와이퍼가 아직 돌면 다시 생긴다) */
    private var pending: Long? = null
    /** 와이퍼가 이어 돌기 시작한 때(0이면 아님)와 마지막으로 돈 때 */
    private var wipeSince = 0L
    private var wipeLast = 0L
    private var cardAt = 0L
    /** 와이퍼가 이만큼 이어 돌면 한 줄(시험 wet_fast면 5초) */
    private var wipeMs = WIPE_MS

    val on get() = Stage.ui.value.novice

    init {
        Stage.ui.update { it.copy(novice = prefs.getBoolean("on", false)) }
        Stage.sideAsks["WET"] = ::answer
    }

    private fun now() = SystemClock.elapsedRealtime()
    private fun today() = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)

    fun set(on: Boolean) {
        prefs.edit().putBoolean("on", on).apply()
        Stage.ui.update { it.copy(novice = on) }
        Prend.log("· 운전 도움 ${if (on) "켬" else "끔"}")
    }

    fun newRide() {
        pending = null
        wipeSince = 0L
    }

    /** 모두 잊기: 젖은 길 한 줄을 한 날과 [그만 알려요]를 지운다(운전 도움 켬·끔은 그대로) */
    fun forget() { prefs.edit().remove("wetDay").remove("wetOff").apply(); Prend.log("· 젖은 길: 모두 잊기") }

    fun release() { Stage.sideAsks.remove("WET") }

    /**
     * 와이퍼를 센다: P가 아닌 동안 이어 돈 시간. 15초까지의 멈춤(간헐 와이퍼가 꺼짐·켜짐을 오갈 수 있다)은 이어 보고,
     * 안개 한 번 닦기(MIST)는 비로 치지 않는다(SoundDirector와 같다). 1분이 차면 오늘 아직 안 했을 때 한 줄을 미뤄 둔다
     */
    private fun watchWiper(car: CarState) {
        val t = now()
        if (car.wiperOn && car.wiperSwitch != "MIST" && !car.parked) { if (wipeSince == 0L) wipeSince = t; wipeLast = t }
        else if (wipeSince > 0 && t - wipeLast > GAP_MS) wipeSince = 0L
        if (wipeSince == 0L || t - wipeSince < wipeMs || pending != null) return
        if (prefs.getInt("wetDay", -1) == today() || !on && prefs.getBoolean("wetOff", false) || Stage.ui.value.daeri) return
        pending = t
        Prend.log("· 젖은 길: 와이퍼가 ${wipeMs / 1000}초 넘게 돌아 다음 편한 때 한 줄")
    }

    /** 1초마다: 신호 대기·P면 미뤄 둔 한 줄을 한다. tip은 말했으면 true(다른 말이 오가는 중이면 false) */
    fun tick(car: CarState) {
        val ui = Stage.ui.value
        // 카드는 출발(시속 3km 넘게)하거나 40초면 거둔다. Care가 제 물음으로 바꿨으면 두지 않는다
        if (ui.ask?.id == "WET" && (car.speedKmh > 3f || now() - cardAt > CARD_MS)) { Stage.ask(null); Prend.log("· 젖은 길 카드 거둠(${if (car.speedKmh > 3f) "출발" else "40초"})") }
        watchWiper(car)
        val at = pending ?: return
        if (now() - at > PENDING_MS) { pending = null; Prend.log("· 젖은 길: 10분 동안 편한 때가 없어 한 줄 버림"); return }
        if (car.speedKmh >= 1f) return
        val comfy = timing.comfy(car)
        if (comfy != Timing.LIGHT && comfy != Timing.PARK) return
        // 숫자는 신호 대기에서만(P에서는 '이 길'이 없다). 제한속도는 30~130만 믿는다
        val n = if (comfy == Timing.LIGHT) limit()?.takeIf { it in 30..130 }?.let { it * 8 / 10 / 10 * 10 } else null
        // 운전 도움이어도 다른 앱 음악·조용히 가기면 목소리를 내지 않는다: 전에는 그대로 기다리다 10분 뒤 버려 안내가 사라졌다(11/04 사람 시험) → 카드로
        if (on && !silent()) {
            // 숫자마다 녹음한 말(20~90). 그 밖의 숫자나 숫자가 없으면 숫자 없는 말. 다른 말이 오가는 중이면 다음 틱에 다시
            val clip = n?.let { "voice/novice_wet_$it.wav" }?.takeIf { a -> runCatching { openVoice(app, a).close() }.isSuccess } ?: "voice/novice_wet.wav"
            if (!tip(clip, "운전 도움 · 비")) return
            said(n, "목소리")
            Stage.toast(n?.let { "젖은 길 · 이 길은 시속 $it 아래 · 거리 두 배" } ?: "젖은 길 · 20% 천천히 · 거리 두 배", 6_000)
            return
        }
        if (!on && prefs.getBoolean("wetOff", false) || ui.daeri) { pending = null; return }
        // 말 차례: 이번에 선 동안 다른 말·카드가 없을 때만, 다른 말 중·말 뒤 20초는 기다린다.
        // 깜빡이를 켜고 돌려는 때·차로를 막 바꾼 때는 띄우지 않는다(목소리와 같은 눈금, 미뤄 둔 한 줄은 다음에 다시)
        if (ui.ask != null || ui.scene != Scene.HOME || !cardFree()) return
        if (timing.block(car, starting = true) != null) return
        if (floor.why(Floor.Kind.ASK) != null) return
        cardAt = now()
        said(n, "카드만")
        floor.shown("젖은 길")
        Stage.ask(Ask("WET", n?.let { "젖은 길 · 시속 $it 아래 · 거리 두 배" } ?: "젖은 길 · 20% 천천히 · 거리 두 배", "도로교통법 시행규칙 제19조 · 한국도로공사", "알겠어요", if (on) "" else "그만 알려요"))
    }

    /** 했다: 오늘은 다시 하지 않는다(말하거나 띄웠을 때만 적는다) */
    private fun said(n: Int?, how: String) {
        pending = null
        prefs.edit().putInt("wetDay", today()).apply()
        Prend.log("◆ 젖은 길 한 줄($how, ${n?.let { "시속 $it 아래" } ?: "숫자 없음"})")
    }

    private fun answer(yes: Boolean) {
        Stage.ask(null)
        if (!yes) {
            prefs.edit().putBoolean("wetOff", true).apply()
            Stage.toast("젖은 길 한 줄은 이제 알리지 않을게요", 2_500)
            Prend.log("· 젖은 길: 그만 알려요 → 끔")
        } else Prend.log("· 젖은 길: 알겠어요")
    }

    /** 시험: 와이퍼 1분을 5초로(되돌리면 1분) */
    fun fast(quick: Boolean) { wipeMs = if (quick) 5_000L else WIPE_MS; Prend.log("· 시험: 젖은 길 와이퍼 ${wipeMs / 1000}초") }

    /** 시험: 오늘 한 날·[그만 알려요]·미뤄 둔 한 줄·와이퍼 셈을 지운다 */
    fun reset() {
        prefs.edit().remove("wetDay").remove("wetOff").apply()
        pending = null; wipeSince = 0L
        Prend.log("· 젖은 길: 시험 되돌림(와이퍼 ${wipeMs / 1000}초, 운전 도움 ${if (on) "켬" else "끔"})")
    }

    companion object {
        const val WIPE_MS = 60_000L
        /** 이보다 짧게 멈춘 와이퍼는 이어 돈 것으로 본다 */
        const val GAP_MS = 15_000L
        const val PENDING_MS = 10 * 60_000L
        const val CARD_MS = 40_000L
    }
}
