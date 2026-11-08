package app.prend.ride

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 기기 안에서만 세는 숫자 셋(11/05, 기획안 6장 '세는 방법'). 위치·말·시각은 남기지 않고 횟수만, 전체와 달마다.
 * - ride: 지킨 운행(시동과 함께 깨어나 혹시 사고·에어백을 지켜본 운행)
 * - e: 급할 때 안내가 열린 횟수(사고·고장·연기·아플 때·응급처치 카드)
 * - asked: PREND가 먼저 묻거나 카드를 띄워 대답(버튼·말)을 받은 횟수
 * 시험·시연 운행 동안과 시험 값(에어백·통화·위치·시간 줄임 등)이 켜진 동안은 셋 다 세지 않는다(검토 11/06 18: 리허설로 숫자가 부풀었다).
 * 밖으로 보내지 않는다. 출시판에서 보낼 때는 동의한 사람만 하루에 숫자 하나로(기획안 6장)
 */
object Stats {
    private var prefs: SharedPreferences? = null

    fun init(context: Context) { if (prefs == null) prefs = context.getSharedPreferences("stats", Context.MODE_PRIVATE) }

    /**
     * 이번 운행이 시험·시연 운행이다(ride/Care.newRide가 넣는다). 같은 운행 중 프로세스가 다시 떠도 이어지게 예·아니오 하나로 적어 둔다
     */
    var demoRide: Boolean
        get() = prefs?.getBoolean("demo_ride", false) ?: false
        set(v) { prefs?.edit()?.putBoolean("demo_ride", v)?.apply() }

    /** 지금 시험 값이 켜져 있나(디버그·PT 빌드에서 운행 서비스가 넣는다. 출시판은 늘 아니오) */
    @Volatile var testing: () -> Boolean = { false }

    /** 지금은 세지 않는 때인가 */
    val paused get() = demoRide || testing()

    private fun month() = SimpleDateFormat("yyyyMM", Locale.US).format(Date())

    fun add(key: String) {
        val p = prefs ?: return
        if (paused) return
        val m = "$key@${month()}"
        p.edit().putInt(key, p.getInt(key, 0) + 1).putInt(m, p.getInt(m, 0) + 1).apply()
    }

    /** 시연 패널의 한 줄: 이번 달(전체) */
    fun line(): String {
        val p = prefs ?: return "운영 숫자: 아직 없음"
        val m = month()
        fun n(k: String) = "${p.getInt("$k@$m", 0)}(${p.getInt(k, 0)})"
        return "운영 숫자(기기 안, 이번 달·전체): 지킨 운행 ${n("ride")} · 급할 때 안내 ${n("e")} · 먼저 묻고 대답 받음 ${n("asked")}" +
            if (paused) " · 지금은 세지 않음(시험·시연)" else ""
    }
}
