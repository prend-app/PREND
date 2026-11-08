package app.prend.ride

import android.content.Context
import app.prend.Prend
import app.prend.ui.CrashNote
import app.prend.ui.Stage
import kotlinx.coroutines.flow.update

/**
 * 사고 뒤에 남겨 두는 것(사용자 결정 10/27). 모두 이 차의 앱 저장소에만 두고 밖으로 보내지 않는다.
 * - 내 보험사: 한 번 고르면 다음 사고 때 그 보험사의 접수 번호를 바로 띄운다
 * - 사고 메모: 사고 안내가 시작된 시각, 장소(119에 말할 위치 한 줄), 날씨(와이퍼·기온)를 적어 보험사·경찰에 말할 때 쓴다.
 *   [다 했어요]를 누를 때까지 첫 화면에 '사고 뒤 남은 일'로 둔다. 기록(로그)에는 장소를 남기지 않는다
 * - 다음 운행 때 한 번: "지난번 사고, 보험 접수는 끝내셨어요?"(사고 뒤 7일 안). 한 번에 하나만 묻는다(검토 11/07 멋대로 판단: 몸과 보험을 함께 물어
 *   "네, 괜찮아요"를 '보험까지 다 함'으로 받았다). 메모는 [다 했어요]나, 말로 다 했다고 한 뒤 확인 카드의 [지워요]로만 지운다(ride/Care.answerAfter)
 */
class AfterCrash(context: Context) {

    private val prefs = context.getSharedPreferences("after", Context.MODE_PRIVATE)

    init { publish() }

    fun insurer(name: String?) {
        prefs.edit().putString("insurer", name).apply()
        Prend.log("· 내 보험사: ${name ?: "지움"}")
        publish()
    }

    /** 사고 안내가 시작됐다. 장소를 아직 모르면 알게 될 때 채운다(place) */
    fun begin(place: String?, weather: String?) {
        prefs.edit().putLong("at", System.currentTimeMillis()).putString("place", place).putString("weather", weather)
            .putBoolean("asked", false).apply()
        Prend.log("· 사고 메모 적음(장소 ${if (place != null) "있음" else "모름"}, 날씨 ${weather ?: "모름"})")
        publish()
    }

    /** 사고 안내 중에 위치를 알게 되면 메모의 빈 장소를 채운다(사고 뒤 10분 안만) */
    fun place(place: String?) {
        if (place == null || prefs.getString("place", null) != null) return
        val at = prefs.getLong("at", 0L)
        if (at == 0L || System.currentTimeMillis() - at > 10 * 60_000) return
        prefs.edit().putString("place", place).apply()
        publish()
    }

    /** 다음 운행 때 물을 것이 있나: 사고 뒤 30분이 지났고 7일 안이며 아직 묻지 않았다 */
    fun shouldAsk(): Boolean {
        val at = prefs.getLong("at", 0L)
        if (at == 0L || prefs.getBoolean("asked", false)) return false
        val e = System.currentTimeMillis() - at
        return e in 30 * 60_000L..7 * 24 * 3_600_000L
    }

    fun asked() = prefs.edit().putBoolean("asked", true).apply()

    /** 지금 메모(연습으로 연 사고 화면을 취소할 때 앞 메모로 되돌리려고) */
    data class Snap(val at: Long, val place: String?, val weather: String?, val asked: Boolean)
    fun snapshot(): Snap? = prefs.getLong("at", 0L).takeIf { it > 0L }?.let { Snap(it, prefs.getString("place", null), prefs.getString("weather", null), prefs.getBoolean("asked", false)) }
    fun restore(s: Snap) {
        prefs.edit().putLong("at", s.at).putString("place", s.place).putString("weather", s.weather).putBoolean("asked", s.asked).apply()
        Prend.log("· 사고 메모: 앞 메모로 되돌림")
        publish()
    }

    /** [다 했어요]·확인 카드의 [지워요]: 메모를 지운다(보험사는 남긴다). 되살릴 수 없어 분명한 대답이나 단추로만 부른다 */
    fun done() {
        prefs.edit().remove("at").remove("place").remove("weather").remove("asked").apply()
        Prend.log("· 사고 메모 지움")
        publish()
    }

    /** 시험: 사고가 하루 전에 있었던 것처럼(다음 운행 때 묻는지 본다) */
    fun pretendYesterday() {
        prefs.edit().putLong("at", System.currentTimeMillis() - 24 * 3_600_000L).putString("weather", "맑음 · 12°C").putBoolean("asked", false).apply()
        Prend.log("· 시험: 사고 메모를 하루 전으로")
        publish()
    }

    private fun publish() {
        val at = prefs.getLong("at", 0L)
        val note = if (at == 0L) null else CrashNote(at, prefs.getString("place", null), prefs.getString("weather", null))
        Stage.ui.update { it.copy(insurer = prefs.getString("insurer", null), note = note) }
    }
}
