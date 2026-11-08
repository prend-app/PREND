package app.prend.ride

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.SystemClock
import app.prend.Bridge
import app.prend.Prend
import app.prend.car.CarLink
import app.prend.car.CarState

/**
 * 정체 챙김(사용자 결정 10/26 밤, "한마디로 작게"). 오래 막히면 "길이 많이 막히네요. 차 안 분위기를 좀 바꿔 드릴까요?"를 한 번 묻는다.
 * - 막힘: D로 10분 넘게 이어 달렸는데 그동안 평균 시속 15km 아래이고, 지금도 시속 20km 아래
 * - "응"이면 무드등을 따뜻한 호박색으로 바꾸고, 아무 음악도 흐르지 않을 때만 PREND의 잔잔한 곡을 튼다.
 *   음악(다른 앱이든 PREND든)이 흐르면 음악이 먼저라 소리는 건드리지 않는다(사용자 결정)
 * - 무드등이 없고 음악도 흐르면 바꿀 것이 없어 묻지 않는다
 * - 길이 풀리거나(2분 평균 시속 30km 넘음) P에 두면 무드등을 원래대로 돌린다. 그사이 사람이 무드등을 바꿨으면 그대로 둔다
 * - 한 번 물으면(대답과 상관없이) 60분 동안 다시 묻지 않는다
 * - 세 번 연달아 "응"이면 다음부터 묻지 않고 알리며 바꾸고(ride/Memory), 그때 "되돌리기"면 무드등과 PREND가 튼 곡을 되돌린다
 */
class JamHelp(context: Context, private val playCalm: () -> Unit, private val stopCalm: () -> Unit, private val alleyish: (windowMs: Long) -> Pair<Boolean, String>? = { null }) {

    private val audio = context.getSystemService(AudioManager::class.java)
    /** D로 달리는 동안의 (시각, 시속). D를 벗어나면 비운다 */
    private val speeds = ArrayDeque<Pair<Long, Float>>()
    private var askedAt = 0L
    /** 바꾸기 전 무드등(밝기, 색)과 PREND가 바꾼 값. 돌려놓을 때 쓴다 */
    private var before: Pair<Int?, Int?>? = null
    private var mine: Pair<Int, Int>? = null
    /** PREND가 무드등을 바꾼 때(elapsedRealtime). 그 뒤 40초 안에 다르게 읽혀도 사람이 바꾼 것으로 보지 않는다(CarLink.HANDS_GRACE_MS) */
    private var setAt = 0L
    /** 이번 정체 챙김에서 PREND가 곡을 틀었는가(되돌릴 때 멈춘다) */
    private var played = false
    /** 시험: jam_fast로 막힘 판단 창을 1분으로 줄인다 */
    var windowMs = WINDOW

    private fun now() = SystemClock.elapsedRealtime()

    /** 1초마다. 길이 풀렸거나 P면 바꾼 무드등을 돌려놓는다 */
    fun tick(car: CarState) {
        val t = now()
        if (car.driving) speeds.addLast(t to car.speedKmh) else speeds.clear()
        while (speeds.isNotEmpty() && t - speeds.first().first > windowMs) speeds.removeFirst()
        if (mine != null) {
            val recent = speeds.filter { t - it.first <= 2 * 60_000 }
            when {
                car.parked -> restore("P")
                recent.size > 100 && recent.map { it.second }.average() > 30.0 -> restore("길이 풀림")
            }
        }
    }

    /** 지금 막힌 길인가(물은 지 60분 안이거나, 바꿔 둔 빛·곡이 아직 남아 있으면 false: 이미 바꿔 둔 것을 다시 바꾸자고 묻지 않는다) */
    fun jammed(car: CarState): Boolean {
        val t = now()
        if (askedAt > 0 && t - askedAt < AGAIN) return false
        if (mine != null) return false
        return slowTraffic(car)
    }

    /** 10분째 막히는 길인가(물었는지와 상관없이). 막히는 큰길의 신호 대기를 골목에서 선 것과 가를 때도 쓴다(ride/Timing) */
    fun slowTraffic(car: CarState): Boolean {
        val first = speeds.firstOrNull() ?: return false
        if (now() - first.first < windowMs - 5_000) return false
        val slow = car.speedKmh < 20f && speeds.map { it.second }.average() < 15.0
        // 주택가·골목을 천천히 다닌 것은 막힌 것이 아니다(11/01 골목 후보 5): 최근 10분 판정이 대부분 골목이면 정체로 치지 않는다
        // ("길이 많이 막히네요"가 골목을 빠져나온 첫 큰길 신호에서 틀린 채 나오지 않게, 골목에서 선 것을 신호 대기로 보지 않게)
        // 같은 창(D로 이어 달린 windowMs) 안의 판정만 센다
        val a = if (slow) alleyish(windowMs) else null
        val alley = a?.first == true
        if (alley != alleyLogged) { alleyLogged = alley; if (alley) Prend.log("· 정체로 보지 않음(${a?.second}): 천천히 다녔지만 최근 판정이 대부분 골목") }
        return slow && !alley
    }
    private var alleyLogged = false

    /** 바꿀 것이 있는가(무드등이 있거나, 음악이 흐르지 않아 곡을 틀 수 있다) */
    fun canHelp() = CarLink.hubOrNull?.ambientArea != null || !musicOn()

    fun asked() { prevAskedAt = askedAt; askedAt = now() }

    /** 물으려다 끊겨 줄에 되돌렸다(통화·포커스·깜빡이): 물은 것으로 치지 않는다(60분 쉬기에 걸려 편한 때 다시 못 묻지 않게) */
    fun unask() { askedAt = prevAskedAt }
    private var prevAskedAt = 0L

    /** 물은 때(벽시계, 묻지 않았으면 0). 같은 운행 중 프로세스가 다시 떠도 60분 쉬기를 잇는다(ride/Care.keep) */
    var askedWall: Long
        get() = RideKeep.wall(askedAt)
        set(w) { askedAt = RideKeep.elapsed(w) }

    /** 다른 앱이든 PREND든 음악이 흐르는가 */
    fun musicOn(): Boolean =
        Bridge.director?.playing == true ||
            runCatching { audio.activePlaybackConfigurations.any { it.audioAttributes.usage == AudioAttributes.USAGE_MEDIA } }.getOrDefault(false)

    /**
     * 다른 앱의 음악(멜론·유튜브 뮤직 등)이 흐르는가(별첨 2 V16). PREND 곡이 흐르면 아니다: 그 곡은 PREND가 말하는 동안 직접 낮춘다.
     * 일반 앱이 받는 재생 목록에는 지금 소리를 내는 것만 들어 있다
     */
    fun otherMusicOn(): Boolean = pretendOther ?: (
        Bridge.director?.playing != true &&
            runCatching { audio.activePlaybackConfigurations.any { it.audioAttributes.usage == AudioAttributes.USAGE_MEDIA } }.getOrDefault(false))

    /** 시험: 다른 앱 음악이 흐르는 것처럼(true)·없는 것처럼(false). null이면 실제 재생을 본다 */
    var pretendOther: Boolean? = null

    fun accept(done: (String) -> Unit) {
        val music = musicOn()
        val hub = CarLink.hubOrNull
        played = !music
        if (!music) { Prend.log("· 정체: 음악이 없어 PREND의 잔잔한 곡을 튼다"); playCalm() }
        else Prend.log("· 정체: 음악이 흐르는 중이라 소리는 그대로")
        if (hub?.ambientArea == null) { done(if (music) "바꿀 수 있는 게 없어요" else "잔잔한 곡을 틀었어요"); return }
        hub.readAmbient { b, c ->
            // 원래 밝기·색을 읽지 못했으면 무드등은 바꾸지 않는다: 되돌릴 수 없어 호박색이 남는데 "원래대로 돌렸어요"라고 했다(11/08 오류 점검 F11)
            if (b == null || c == null) {
                Prend.log("· 정체: 원래 무드등 값을 몰라 무드등은 그대로")
                done(if (music) "바꿀 수 있는 게 없어요" else "잔잔한 곡을 틀었어요")
                return@readAmbient
            }
            before = b to c
            setAt = now()
            hub.setAmbient(BRIGHT, AMBER) { ok ->
                if (ok) mine = BRIGHT to AMBER
                Prend.log("· 정체: 무드등 바꿈 ${if (ok) "받아들여짐" else "실패"}(전 밝기 $b 색 ${c?.let { "#%06X".format(it) }})")
                done(when {
                    ok && !music -> "잔잔한 곡을 틀고 무드등을 따뜻하게 바꿨어요"
                    ok -> "무드등을 따뜻하게 바꿨어요"
                    !music -> "잔잔한 곡을 틀었어요"
                    else -> "무드등을 바꾸지 못했어요"
                })
            }
        }
    }

    /** 알아서 바꾼 것을 곧바로 되돌린다: 무드등은 원래대로, PREND가 튼 곡은 멈춘다 */
    fun undo(done: (String) -> Unit) {
        val light = mine != null
        val song = played
        if (played) { played = false; stopCalm() }
        restore("되돌리기", force = true)
        done(when {
            light && song -> "무드등을 돌리고 곡을 멈췄어요"
            light -> "무드등을 원래대로 돌렸어요"
            song -> "잔잔한 곡을 멈췄어요"
            else -> "원래대로 돌렸어요"
        })
    }

    /**
     * 바꿔 둔 무드등을 사람이 직접 바꿨나(감사 F77: 3분 안이면 되돌림으로 센다). 바꾼 지 40초 전이면 차가 늦게 반영한 것일 수 있어 보지 않는다.
     * 바꾼 게 없으면 false
     */
    fun touchedByHand(done: (Boolean) -> Unit) {
        val set = mine ?: return done(false)
        if (now() - setAt < CarLink.HANDS_GRACE_MS) return done(false)
        val hub = CarLink.hubOrNull ?: return done(false)
        hub.readAmbient { b, c -> done(mine === set && ((b != null && b != set.first) || (c != null && c != set.second))) }
    }

    /** 사람이 바꾼 무드등은 그대로 둔다(길이 풀리거나 P에 둬도 되돌리지 않는다) */
    fun keepHands() { mine = null; before = null }

    /** force면 사람이 바꿨는지 보지 않고 되돌린다(사람이 "되돌리기"라고 한 때. 앱이 바꾼 무드등은 차에 늦게 반영되어 옛 값이 읽힐 수 있다) */
    private fun restore(why: String, force: Boolean = false) {
        val set = mine ?: return
        val ob = before?.first
        val oc = before?.second
        mine = null
        before = null
        val hub = CarLink.hubOrNull ?: return
        // 바꾼 지 40초 안에 다르게 읽힌 값은 차가 늦게 반영한 옛 값일 수 있다: 그때는 사람이 바꾼 것으로 보지 않는다
        val settled = now() - setAt >= CarLink.HANDS_GRACE_MS
        hub.readAmbient { b, c ->
            when {
                !force && settled && ((b != null && b != set.first) || (c != null && c != set.second)) -> Prend.log("· 정체 끝($why): 사람이 무드등을 바꿔 그대로 둠")
                ob == null || oc == null -> Prend.log("· 정체 끝($why): 원래 무드등 값을 몰라 그대로 둠")
                else -> hub.setAmbient(ob, oc) { ok -> Prend.log("· 정체 끝($why): 무드등 되돌림 ${if (ok) "받아들여짐" else "실패"}") }
            }
        }
    }

    companion object {
        const val WINDOW = 10 * 60_000L
        const val AGAIN = 60 * 60_000L
        /** 따뜻한 호박색, 밝기는 눈부시지 않게 */
        const val AMBER = 0xFFB347
        const val BRIGHT = 30
    }
}
