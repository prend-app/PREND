package app.prend.ride

import android.os.SystemClock
import app.prend.Prend
import app.prend.car.CarLink
import app.prend.car.CarState

/**
 * 말 차례(11/01 사용자 "프렌드 앱이 말이 많다면 운전자를 거슬리게 하는 앱"). PREND가 먼저 꺼내는 목소리(물음·한마디)가 모두 여기를 지난다.
 * 시험(11/01)에서 바쁜 구간 뒤 첫 신호 대기 94초에 미뤄 둔 물음 넷이 27~30초 간격으로 줄줄이 나왔다. 그래서:
 * - 한 번 설 때(신호 대기·P·안정 주행 한 번) 먼저 꺼내는 목소리는 하나. 나머지는 다음에 설 때
 * - 말·카드·대답 듣기·[프렌드]가 끝난 뒤 20초는 새로 먼저 말하지 않는다(별첨 2 V7의 숨)
 * - 물음은 앞 물음 뒤 30분 안(V13)이거나 한 운행 두 번(V14)을 넘으면 목소리 없이 카드만. 한마디(초보·길·주차)는 한 운행 세 번까지
 * - 먼저 꺼내는 것은 목소리(물음·한마디)와 카드를 합쳐 한 운행 여섯 번까지(11/03, 검토-방향노인초보 2-1: 노인은 카드를 읽느라 눈을 더 오래 뗀다).
 *   넘으면 그 운행에는 더 꺼내지 않는다. 그 자리·그때뿐인 카드(정비소 도착·골목 목적지·고속도로 정차·출발 전 한 장)는 막지 않고 세기만 한다
 * 혹시 사고·쉼터·사람이 부른 말의 대답·쪽잠 깨우기는 막지 않는다(부른 쪽이 여기를 거치지 않는다).
 * 카드만 띄운 물음(목소리 없음)도 한 번 설 때 하나·앞 말 뒤 20초·한 운행 여섯 번에는 센다(물음 수·30분 간격에는 세지 않는다).
 * 세는 값은 운행 범위라 기억에 남기지 않는다
 */
class Floor(private val timing: Timing, private val busy: () -> Boolean) {

    enum class Kind { ASK, TIP }

    /** 지금 선 때의 이름(P·신호 대기·안정 주행, 아니면 null)과 그 회차, 마지막으로 먼저 말한 회차 */
    private var stopKind: String? = null
    private var episode = 0
    private var spokeEpisode = -1
    /** 마지막 말·카드·듣기가 끝난 때 */
    private var quietSince = 0L
    private var wasBusy = false
    /** 이번 운행의 목소리 물음 수와 마지막 물음 때, 한마디 수 */
    private var asks = 0
    private var lastAskAt = 0L
    private var tips = 0
    /** 이번 운행에 먼저 꺼낸 것의 수(목소리 물음·한마디·카드를 합쳐) */
    private var firsts = 0
    /** 막은 까닭을 한 번만 적으려고 */
    private var lastWhy: String? = null

    private fun now() = SystemClock.elapsedRealtime()

    fun newRide() {
        asks = 0; lastAskAt = 0L; tips = 0; firsts = 0; spokeEpisode = -1
        // 지난 운행의 '앞 말 뒤 20초'와 선 때는 잇지 않는다(시동 직후 주차 자리·영하 한마디가 지난 운행의 말에 막히지 않게)
        quietSince = 0L; wasBusy = false; stopKind = null; lastLight = false
        Prend.log("· 말 차례: 새 운행(물음·한마디·먼저 꺼낸 수 비움)")
    }

    /**
     * 같은 빨간불에서 앞차를 따라 조금 굴러갔다 다시 선 것은 새로 선 것으로 치지 않으려고: 마지막으로 선 때가 신호 대기였나,
     * 그 뒤 가장 빨랐던 속도와 그때부터 흐른 시간
     */
    private var lastLight = false
    private var rollMax = 0f
    private var rollSince = 0L

    /** 지금 차 상태로 선 때를 맞춘다(1초 틱과, P에 넣는 순간처럼 틱 사이에 말을 꺼낼 때) */
    private fun sync(car: CarState) {
        val k = if (car.parked) Timing.PARK else timing.comfy(car)
        if (k == null && stopKind != null) { rollMax = 0f; rollSince = now() }
        if (k == null) rollMax = maxOf(rollMax, car.speedKmh)
        if (k == stopKind) return
        val rolled = k == Timing.LIGHT && lastLight && rollMax < ROLL_KMH && now() - rollSince < ROLL_MS
        stopKind = k
        if (k != null) { if (!rolled) episode++; lastLight = k == Timing.LIGHT }
    }

    /** 1초마다(다른 모듈이 말하기 전에) */
    fun tick(car: CarState) {
        sync(car)
        val b = busy()
        if (wasBusy && !b) quietSince = now()
        wasBusy = b
    }

    /**
     * 지금 먼저 목소리를 꺼내면 안 되는 까닭. null이면 된다.
     * bound면 그 자리·그때뿐인 카드(정비소 도착·골목 목적지·고속도로 정차·출발 전 한 장)라 한 운행 여섯 번에 막지 않는다
     */
    fun why(kind: Kind, bound: Boolean = false): String? {
        sync(CarLink.state)
        val t = now()
        val w = when {
            busy() -> BUSY
            quietSince > 0 && t - quietSince < GAP_MS -> "앞 말 뒤 ${GAP_MS / 1000}초 안"
            stopKind != null && spokeEpisode == episode -> STOP_SPOKEN
            kind == Kind.TIP && tips >= TIP_MAX && !Timing.ptValues -> "한마디 한 운행 ${TIP_MAX}번"
            !bound && full -> FIRST_CAP
            else -> null
        }
        if (w != null && w != lastWhy) Prend.log("· 말 차례 막음(${kind.name}: $w)")
        lastWhy = w
        return w
    }

    /** 이번에 선 동안 이미 먼저 말했나(그 자리에서만 쓸모 있는 말은 카드·글로 돌린다) */
    val spokeThisStop get() = stopKind != null && spokeEpisode == episode

    /** 이번 운행에 먼저 꺼낸 것이 여섯 번에 닿았나(그 운행에는 다시 열리지 않는다. 시연 값이면 보지 않는다) */
    val full get() = firsts >= FIRST_MAX && !Timing.ptValues

    /** 물음을 목소리 없이 카드로만 둘 까닭(V13·V14). 시연 값이면 보지 않는다 */
    fun cardWhy(): String? = when {
        Timing.ptValues -> null
        asks >= ASK_MAX -> "목소리 물음 한 운행 ${ASK_MAX}번"
        lastAskAt > 0 && now() - lastAskAt < askGapMs -> "앞 물음 뒤 ${askGapMs / 60_000}분 안"
        else -> null
    }

    /** 먼저 꺼낸 목소리가 나간다. 1초 틱 사이에 끝나거나 끊긴 말도 '앞 말 뒤 20초'를 세도록 바쁨으로 적어 둔다 */
    fun spoke(kind: Kind, label: String) {
        sync(CarLink.state)
        if (stopKind != null) spokeEpisode = episode
        if (kind == Kind.ASK) { prevAskAt = lastAskAt; asks++; lastAskAt = now() } else tips++
        firsts++
        wasBusy = true
        lastWhy = null
        Prend.log("◆ 말 차례 허락(${kind.name}: $label · 이번 운행 물음 ${asks}번, 한마디 ${tips}번, 먼저 꺼냄 ${firsts}/$FIRST_MAX)")
    }
    private var prevAskAt = 0L

    /**
     * 먼저 꺼낸 물음이 끊겨(또는 "졸려"에 비켜 서) 줄에 되돌아갔다: 삼킨 말은 물음 수·30분 간격·한 운행 여섯 번에 세지 않는다(별첨 2 §2-2 '셈').
     * 같은 정차라면 20초 뒤 다시 할 수 있게 이번 정차의 차례도 돌려준다. voice가 false면(카드만 띄운 물음) 먼저 꺼낸 수만 뺀다
     */
    fun unspoke(label: String, voice: Boolean = true) {
        if (voice && asks > 0) { asks--; lastAskAt = prevAskAt }
        if (firsts > 0) firsts--
        if (spokeEpisode == episode) spokeEpisode = -1
        Prend.log("· 말 차례: 되돌린 물음은 세지 않음($label${if (voice) "" else ", 카드만"} · 물음 ${asks}번, 먼저 꺼냄 ${firsts}/$FIRST_MAX)")
    }

    /** 목소리 없이 카드만 띄웠다: 이번에 선 동안의 차례와 한 운행 여섯 번에 센다(물음 수·30분 간격에는 세지 않는다) */
    fun shown(label: String) {
        sync(CarLink.state)
        if (stopKind != null) spokeEpisode = episode
        firsts++
        wasBusy = true
        lastWhy = null
        Prend.log("· 말 차례: 카드만($label · 먼저 꺼냄 ${firsts}/$FIRST_MAX)")
    }

    /** 시험: 30분 간격을 1분으로(되돌리면 30분) */
    fun fast(on: Boolean) { askGapMs = if (on) 60_000L else ASK_GAP_MS; Prend.log("· 시험: 물음 간격 ${askGapMs / 1000}초") }
    private var askGapMs = ASK_GAP_MS

    fun dump() {
        val t = now()
        Prend.log("· 시험: 말 차례 · 선 때 ${stopKind ?: "아님"}(${episode}회차, 먼저 말함 ${if (spokeThisStop) "예" else "아니오"})" +
            " · 조용해진 지 ${if (quietSince > 0) "${(t - quietSince) / 1000}초" else "-"} · 물음 ${asks}번(마지막 ${if (lastAskAt > 0) "${(t - lastAskAt) / 1000}초 전" else "-"}) · 한마디 ${tips}번" +
            " · 먼저 꺼냄 ${firsts}/$FIRST_MAX${if (full) "(닿음)" else ""}")
    }

    /** 시험: 이번 운행에 먼저 꺼낸 수를 n으로(상한 바로 앞을 만들어 볼 때) */
    fun firstsTo(n: Int) { firsts = n.coerceAtLeast(0); lastWhy = null; Prend.log("· 시험: 먼저 꺼냄 ${firsts}/$FIRST_MAX") }

    companion object {
        const val GAP_MS = 20_000L
        const val BUSY = "다른 말 중"
        /** 이번에 선 동안 이미 먼저 말했다(그 자리에서만 쓸모 있는 카드는 이것만이면 띄운다: 주차 자리·골목 목적지) */
        const val STOP_SPOKEN = "이번에 선 동안 이미 한마디"
        const val ASK_GAP_MS = 30 * 60_000L
        const val ASK_MAX = 2
        const val TIP_MAX = 3
        /** 한 운행에 먼저 꺼내는 것(목소리 물음·한마디·카드를 합쳐)의 상한(11/03) */
        const val FIRST_MAX = 6
        const val FIRST_CAP = "먼저 꺼낸 말·카드 한 운행 ${FIRST_MAX}번"
        /** 신호 대기 사이에 이보다 느리게, 이 시간 안에 다시 서면 같은 빨간불로 본다 */
        const val ROLL_KMH = 15f
        const val ROLL_MS = 30_000L
    }
}
