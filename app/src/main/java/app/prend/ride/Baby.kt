package app.prend.ride

import android.content.Context
import app.prend.Bridge
import app.prend.Prend
import app.prend.ui.Stage
import kotlinx.coroutines.flow.update

/**
 * 아이(사용자 결정 10/27, 11/01). 가족 정보에 "함께 타는 아이가 있어요"를 체크해 두면 사고 때 맨 위에 "뒷좌석 아이 확인"을 두고
 * "뒷좌석에 아이가 있는지 꼭 살펴보세요"를 먼저 말하며, 대답이 없으면 구조하러 온 사람에게 뒷좌석을 살펴 달라고 알린다(Crash).
 * 차의 좌석 센서로는 카시트의 아이를 알 수 없어(R9-K) 사람이 정해 둔 가족 정보로 챙긴다. 운행마다 뒷문으로 묻지 않는다
 * (11/01 사용자 결정: "차에 탔을 때 뒷문이 열린다고 계속 물어보는 게 어디 있어")
 * - 가족 정보는 처음 한 번 세워 둔 동안 묻는다(대답이 없으면 다음 운행에 한 번 더, 그 뒤로는 묻지 않는다). 첫 화면과 기억 화면에서 바꾼다
 * - "아이 탔어"·"아이 내렸어"는 이번 운행만의 아이 탑승이다. 켜 두면 사고 때 "뒷좌석에 아이가 있어요"로 분명히 알린다. 새 운행이면 끈다
 * 남기는 것은 가족 정보(있음·없음)와 물은 횟수뿐이다
 */
class Baby(context: Context) {

    private val prefs = context.getSharedPreferences("baby", Context.MODE_PRIVATE)
    /** 이번 운행에 가족 정보를 물으려고 줄에 세웠다(다시 세우지 않는다) */
    var asked = false
        private set
    /** 이번 운행에 건넨 것을 셌다(끊겨 다시 건네도 한 번만 센다) */
    private var counted = false

    init {
        // 예전의 '평소 아이를 태우던 때' 기록은 더 쓰지 않는다
        if (prefs.contains("history")) prefs.edit().remove("history").apply()
        publish()
    }

    /** 가족 정보: 함께 타는 아이가 있다(true)·없다(false)·아직 정하지 않았다(null) */
    val kids: Boolean? get() = if (prefs.contains("kids")) prefs.getBoolean("kids", false) else null

    /** 새 운행: 이번 운행만의 아이 탑승을 끈다 */
    fun newRide() {
        asked = false
        counted = false
        set(false, "새 운행")
    }

    /** 가족 정보를 물을 때인가: 아직 정하지 않았고, 이번 운행에 묻지 않았고, 건넨 것이 두 번이 안 된다 */
    fun shouldAsk(): Boolean = kids == null && !asked && prefs.getInt("asks", 0) < MAX_ASKS

    /** 줄에 세웠다 */
    fun queued() { asked = true }

    /** 실제로 건넸다(운행마다 한 번, 두 번까지 센다) */
    fun delivered() {
        if (counted) return
        counted = true
        prefs.edit().putInt("asks", prefs.getInt("asks", 0) + 1).apply()
    }

    /** 말하는 중·대답을 듣는 중에 끊겨 줄에 되돌렸다(R·깜빡이·통화): 끝까지 묻지 못했으니 세지 않는다 */
    fun undelivered() {
        if (!counted) return
        counted = false
        prefs.edit().putInt("asks", (prefs.getInt("asks", 0) - 1).coerceAtLeast(0)).apply()
    }

    /** 가족 정보를 정했다(물음의 대답·첫 화면·기억 화면) */
    fun setKids(yes: Boolean, why: String) {
        if (kids != yes) Prend.log("· 가족 정보: 함께 타는 아이 ${if (yes) "있음" else "없음"}($why)")
        prefs.edit().putBoolean("kids", yes).apply()
        publish()
    }

    /** 같은 운행 중 프로세스가 다시 떴다: 이번 운행의 아이 탑승과 가족 정보를 물었는지를 잇는다(ride/Care.restore). 소리 엔진의 −3dB도 함께 */
    fun restore(on: Boolean, wasAsked: Boolean, why: String = "같은 운행을 이어 감") {
        asked = wasAsked
        set(on, why)
    }

    /** "아이 탔어"·"아이 내렸어": 이번 운행만. 가족 정보를 아직 정하지 않았으면 아이가 있다고 함께 적는다 */
    fun toggle(yes: Boolean) {
        set(yes, if (yes) "사람이 켬" else "사람이 끔")
        if (yes && kids == null) setKids(true, "아이 탔다고 함")
    }

    private fun set(yes: Boolean, why: String) {
        if (Stage.ui.value.baby != yes) Prend.log("· 아이 탑승 ${if (yes) "켬" else "끔"}($why)")
        Stage.ui.update { it.copy(baby = yes) }
        // 아이가 타면 음악의 최대 크기를 3dB 낮춘다(소리 엔진)
        Bridge.director?.baby = yes
    }

    private fun publish() { Stage.ui.update { it.copy(kids = kids) } }

    /** 기억 화면의 [모두 잊기]: 가족 정보를 지우고 다음 운행에 처음처럼 한 번 묻는다(지금 운행에는 다시 묻지 않는다) */
    fun forget() {
        prefs.edit().remove("kids").remove("asks").apply()
        asked = true
        Prend.log("· 가족 정보 지움(다음 운행에 처음처럼 한 번 묻는다)")
        publish()
    }

    companion object {
        /** 가족 정보 물음은 대답이 없어도 두 번까지만 건넨다 */
        const val MAX_ASKS = 2
    }
}
