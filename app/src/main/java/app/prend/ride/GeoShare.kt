package app.prend.ride

import android.content.Context
import android.content.SharedPreferences
import app.prend.Prend
import app.prend.ui.Stage
import kotlinx.coroutines.flow.update

/**
 * 위치 보내기(사용자 결정 11/06, 검토 9). 위치는 이때만 중계 서버로 보내고 저장하지 않는다:
 * 차가 설 때 골목인지 큰길인지 가리기(ride/Road, 지나온 자리 → TMAP), 아플 때 가까운 병원 찾기(ride/Crash), 출발 전 날씨(ride/Dusk, 목적지 5km 격자),
 * 같이 고르기·가까운 블루핸즈·골목 목적지 주차장(동네 이름·목적지 한 점, ride/Together·CarCare·AlleyDest), "화장실 급해"의 주변 주유소(ride/Toilets).
 * - 처음 설치하면 가족 정보 물음 곁에 카드 한 장으로 알린다(ride/Care의 GEO: 세워 둔 동안, 문이 모두 닫히고 10초, 대답이 없으면 다음 운행에 한 번 더).
 *   소리 없는 카드라 보일 때만 띄우고(첫 화면이 보이고 허락 판이 없거나, 화면이 닫혀 있고 배너가 뜰 때), 보인 채로 5초가 지나야 건넨 것으로 센다
 *   (검토 11/06: 보이지 않은 채 두 번 세어져 한 번도 못 본 사람의 위치를 계속 보냈다). 한 운행 여섯 번 몫에는 세지 않는다(운행 중 물음이 아니라 처음 한 번의 알림이다)
 * - [보내지 않기]면 어느 것도 보내지 않는다. 병원은 앱 안 목록(ride/Hospitals)으로, 골목은 차 신호(ride/Timing의 큰길 증거)만으로 가르고,
 *   날씨 줄·같이 고르기·블루핸즈·골목 목적지는 쉬고, 화장실은 앱 안 주유소 자료로만 고른다. 기억 화면 맨 아래 [위치 보내기]에서 바꾼다
 * - 정하기 전에는 보낸다(알리는 카드다). 운행 서비스가 읽기 전(init 전)에는 보내지 않는다
 * 남기는 것은 보낼지(예·아니오), 정했는지, 카드를 건넨 수뿐이다
 */
object GeoShare {

    private var prefs: SharedPreferences? = null
    /** 이번 운행에 카드를 줄에 세웠나(다시 세우지 않는다), 건넨 것을 셌나(끊겨 다시 건네도 한 번만) */
    var asked = false
        private set
    private var counted = false

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("geo", Context.MODE_PRIVATE)
        publish()
    }

    /** 위치를 보내도 되나 */
    val on: Boolean get() = prefs?.getBoolean("send", true) ?: false

    /** 카드에 대답했거나 기억 화면에서 바꿨다 */
    val decided: Boolean get() = prefs?.getBoolean("decided", false) ?: true

    /** 카드를 띄울 때인가: 아직 정하지 않았고, 이번 운행에 세우지 않았고, 건넨 것이 두 번이 안 된다 */
    fun shouldAsk(): Boolean = prefs != null && !decided && !asked && (prefs?.getInt("asks", 0) ?: 0) < MAX_ASKS

    fun queued() { asked = true }

    /** 카드가 보인 채로 몇 초가 지났다(ride/Care가 부른다. 운행마다 한 번, 두 번까지 센다) */
    fun delivered() {
        val p = prefs ?: return
        if (counted) return
        counted = true
        p.edit().putInt("asks", p.getInt("asks", 0) + 1).apply()
    }

    /** 새 운행: 이번 운행에 세웠는지를 비운다 */
    fun newRide() { asked = false; counted = false }

    /** 같은 운행 중 프로세스가 다시 떴다(ride/Care.restore) */
    fun restore(wasAsked: Boolean) { asked = wasAsked }

    /** 정했다(카드의 대답·기억 화면). why는 기록에만 */
    fun set(send: Boolean, why: String) {
        val p = prefs ?: return
        if (on != send || !decided) Prend.log("◆ 위치 보내기 ${if (send) "켬" else "끔"}($why)")
        p.edit().putBoolean("send", send).putBoolean("decided", true).apply()
        publish()
    }

    /**
     * PT 장면 준비(pt_e_ready, 검토 11/06 14): '보내기'로 정한 것으로 둔다. 시연 도중 카드가 뜨지 않고,
     * 골목 판정과 병원 찾기가 제품의 기본 길(중계 서버)로 돈다. 리허설에서 [보내지 않기]를 눌러 두었어도 되돌린다
     */
    fun settleForDemo() {
        val p = prefs ?: return
        if (decided && on) return
        p.edit().putBoolean("send", true).putBoolean("decided", true).apply()
        Prend.log("· 위치 보내기: 시연 장면 준비라 카드 없이 '보내기'로 둠")
        publish()
    }

    /** 시험: 처음 설치한 것처럼(카드를 다시 띄운다) */
    fun reset() {
        prefs?.edit()?.clear()?.apply()
        asked = false; counted = false
        Prend.log("· 시험: 위치 보내기 처음 상태")
        publish()
    }

    private fun publish() { val send = on; Stage.ui.update { if (it.geoSend == send) it else it.copy(geoSend = send) } }

    /** 대답이 없어도 두 번까지만 띄운다(가족 정보 물음과 같다) */
    const val MAX_ASKS = 2

    /** 위치를 보내야 찾을 수 있는 화면(같이 고르기·블루핸즈)에 둘 한 줄 */
    const val OFF_LINE = "위치를 보내지 않게 해 두셨어요 · 설정에서 바꿀 수 있어요"
}
