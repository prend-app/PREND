package app.prend.ride

import android.content.Context
import android.os.SystemClock
import app.prend.Prend
import app.prend.car.CarState
import app.prend.ui.Ask
import app.prend.ui.Scene
import app.prend.ui.Stage
import app.prend.voice.Speaker
import kotlinx.coroutines.flow.update

/**
 * 듣고 보기(11/02, 검토-방향노인초보 3-4·3-5). '노인 모드' 없이 쓰는 모습으로 맞춘다. 나이를 묻지 않는다.
 * 근거: 70대의 청력·반응 속도 자기평가가 젊은 층보다 낮다(도로교통공단 2016), 70세부터 반응 속도와 정확도가 함께 떨어진다(교통과학연구원).
 * - 천천히 다시 말하기: "뭐라고?"·"다시 말해 줘"·"못 들었어"면 방금 한 말을 음높이 그대로 0.85배로 한 번 더(voice/Speaker.replay).
 *   마이크를 새로 열지 않는다: PREND가 물은 뒤의 대답 창 안이거나, [프렌드]를 누르고 말했는데 PREND가 1분 안에 말한 것이 있을 때만.
 *   서로 다른 때(30분 넘게 떨어져) 두 번 되물으면 세워 둔 동안 소리 없는 카드 "앞으로 천천히 말할게요 [좋아요][원래대로]".
 *   켜면 모든 말이 0.9배. "천천히 말해 줘"·"원래대로 말해"로도 켜고 끈다. [원래대로]를 두 번 고르면 다시 묻지 않는다
 * - 넉넉히 기다리기: 대답 창(5초)이 말없이 닫힌 뒤 10초 안에 그 카드 버튼으로 대답한 일이 두 번이면 대답 창을 8초로 늘린다.
 *   눈에 띄지 않는 변화라 묻지 않고 하되, 바꿀 때 화면 한 줄로 한 번 알린다(검토 11/07)
 * - 기억 화면 '듣고 보기'(3-9)에서 둘 다 켜고 끈다. 켜진 까닭(PREND가 권했거나 말로 켬)을 한 줄로 보인다.
 *   기억 화면에서 끈 넉넉히 기다리기는 늦은 대답을 더 세지 않는다(끄자마자 한 번 더 늦어 다시 켜지지 않게. [모두 잊기]면 다시 센다)
 * 기억하는 것은 되물은 횟수·때, 늦게 대답한 횟수, 켬·끔과 그 까닭뿐이다. 들은 말은 남기지 않는다
 */
class SlowTalk(context: Context, private val speaker: Speaker?, private val floor: Floor?, private val idle: () -> Boolean) {

    private val prefs = context.getSharedPreferences("slow_talk", Context.MODE_PRIVATE)
    private var pending = false
    private var cardAt = 0L

    init {
        Stage.sideAsks["SLOW"] = ::answer
        speaker?.slow = prefs.getBoolean("on", false)
        if (prefs.getBoolean("wait", false)) Stage.answerMs = LONG_WAIT_MS
        publish()
    }

    private fun now() = SystemClock.elapsedRealtime()

    /** 기억 화면 '듣고 보기'에 켬·끔과 켜진 까닭을 넣는다 */
    private fun publish() {
        val slow = prefs.getBoolean("on", false)
        val wait = prefs.getBoolean("wait", false)
        Stage.ui.update { it.copy(slowTalk = slow, slowWhy = if (slow) prefs.getString("slowWhy", null) else null, longWait = wait, waitWhy = if (wait) prefs.getString("waitWhy", null) else null) }
    }

    /** "뭐라고?"를 받았다. answer가 있으면 물음의 대답 창 안이었다(다시 말한 뒤 같은 창을 다시 연다) */
    fun again(answer: ((String) -> Unit)?) {
        val inWindow = answer != null
        if (!inWindow && speaker?.spokeWithin(AGAIN_MS) != true) { Stage.toast("방금 드린 말이 없어요", 2_000); return }
        // 대답 창을 다시 여는 동안도 다른 음악을 멈춰 둔다(넉넉히 기다리기면 8초)
        val ok = speaker?.replay(answerMs = if (inWindow) Stage.answerMs else 0L) { if (answer != null) Stage.call(answer = answer, windowMs = Stage.answerMs) } == true
        Prend.log("· 듣고 보기: 다시 말해 달라고 함(${if (inWindow) "대답 창" else "프렌드"}) → ${if (ok) "천천히 다시" else "할 말 없음"}")
        if (!ok) { Stage.toast("방금 드린 말이 없어요", 2_000); return }
        count()
    }

    /** 서로 다른 때 두 번 되물었으면 다음에 세워 둔 동안 카드 */
    private fun count() {
        if (prefs.getBoolean("on", false) || prefs.getInt("no", 0) >= NO_MAX) return
        val wall = System.currentTimeMillis()
        if (wall - prefs.getLong("lastAt", 0L) < APART_MS) return
        val n = prefs.getInt("count", 0) + 1
        prefs.edit().putInt("count", n).putLong("lastAt", wall).apply()
        if (n >= ASK_AFTER) { pending = true; Prend.log("· 듣고 보기: 서로 다른 때 ${n}번 되물음 → 세워 두면 천천히 말하기 카드") }
    }

    /** 대답 창이 닫힌 뒤 10초 안에 카드 버튼으로 대답했다 */
    fun late() {
        // 기억 화면에서 끈 뒤로는 세지 않는다
        if (prefs.getBoolean("wait", false) || prefs.getBoolean("waitOff", false)) return
        val n = prefs.getInt("late", 0) + 1
        prefs.edit().putInt("late", n).apply()
        Prend.log("· 듣고 보기: 대답 창이 닫힌 뒤 버튼으로 대답 ${n}번")
        if (n >= LATE_AFTER) {
            prefs.edit().putBoolean("wait", true).putString("waitWhy", "쓰시는 모습에 맞춰 켜 뒀어요").apply()
            Stage.answerMs = LONG_WAIT_MS
            publish()
            Prend.log("◆ 듣고 보기: 넉넉히 기다리기 켬(대답 창 ${LONG_WAIT_MS / 1000}초)")
            // 바뀐 것을 한 번 알린다(검토 11/07 멋대로 판단: 말없이 바뀌어 물을 때마다 음악이 8초씩 멈추는 까닭을 알 길이 없었다)
            Stage.toast("다음부턴 대답을 조금 더 기다릴게요 · 설정에서 바꿀 수 있어요", 4_000)
        }
    }

    /** 천천히 말하기를 켜고 끈다(말·카드·기억 화면). shown은 기억 화면에 보일 켜진 까닭(기억 화면에서 켰으면 null) */
    fun set(on: Boolean, why: String, shown: String? = null) {
        prefs.edit().putBoolean("on", on).apply { if (!on) putInt("count", 0); if (on && shown != null) putString("slowWhy", shown) else remove("slowWhy") }.apply()
        speaker?.slow = on
        pending = false
        publish()
        Prend.log("· 듣고 보기: 천천히 말하기 ${if (on) "켬" else "끔"}($why)")
    }

    /** 넉넉히 기다리기를 켜고 끈다(기억 화면). 끄면 늦은 대답을 더 세지 않는다 */
    fun setWait(on: Boolean, why: String) {
        prefs.edit().putBoolean("wait", on).putBoolean("waitOff", !on).putInt("late", 0).remove("waitWhy").apply()
        Stage.answerMs = if (on) LONG_WAIT_MS else Stage.ANSWER_MS
        publish()
        Prend.log("· 듣고 보기: 넉넉히 기다리기 ${if (on) "켬" else "끔"}($why, 대답 창 ${Stage.answerMs / 1000}초)")
    }

    /** 1초마다: 카드를 거두거나 띄운다 */
    fun tick(car: CarState) {
        val ui = Stage.ui.value
        if (ui.ask?.id == "SLOW" && (!car.parked || now() - cardAt > CARD_MS)) { Stage.ask(null); Prend.log("· 천천히 말하기 카드 거둠") }
        if (!pending || !car.parked) return
        if (ui.ask != null || !idle() || ui.scene != Scene.HOME || ui.daeri) return
        if (floor?.why(Floor.Kind.ASK) != null) return
        pending = false
        cardAt = now()
        floor?.shown("천천히 말하기")
        Stage.ask(Ask("SLOW", "앞으로 천천히 말할게요", "다시 말해 달라고 하신 적이 있어서요 · 설정에서 바꿀 수 있어요", "좋아요", "원래대로"))
        Prend.log("◆ 천천히 말하기 카드(카드만)")
    }

    private fun answer(yes: Boolean) {
        Stage.ask(null)
        if (yes) { set(true, "카드에서 좋아요", "다시 말해 달라고 하신 적이 있어서"); Stage.toast("앞으로 천천히 말할게요", 2_000) }
        else { prefs.edit().putInt("no", prefs.getInt("no", 0) + 1).putInt("count", 0).apply(); Prend.log("· 듣고 보기: 원래대로(${prefs.getInt("no", 0)}번)") }
    }

    fun forget() {
        prefs.edit().clear().apply()
        speaker?.slow = false
        Stage.answerMs = Stage.ANSWER_MS
        pending = false
        publish()
        Prend.log("· 듣고 보기: 모두 잊기")
    }

    fun dump() = Prend.log("· 듣고 보기: 천천히 ${prefs.getBoolean("on", false)}, 되물음 ${prefs.getInt("count", 0)}번, 늦은 대답 ${prefs.getInt("late", 0)}번, 넉넉히 ${prefs.getBoolean("wait", false)}(손으로 끔 ${prefs.getBoolean("waitOff", false)}), 원래대로 ${prefs.getInt("no", 0)}번")

    /** 시험: 되물은 때를 지운다(다음 되물음을 '다른 때'로 센다) */
    fun pretendApart() { prefs.edit().putLong("lastAt", 0L).apply() }

    companion object {
        const val AGAIN_MS = 60_000L
        const val APART_MS = 30 * 60_000L
        const val ASK_AFTER = 2
        const val LATE_AFTER = 2
        const val NO_MAX = 2
        const val LONG_WAIT_MS = 8_000L
        const val CARD_MS = 40_000L
    }
}
