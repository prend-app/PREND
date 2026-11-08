package app.prend.ride

import android.car.VehicleGear
import android.media.AudioAttributes
import android.os.SystemClock
import app.prend.Prend
import app.prend.car.CarLink
import app.prend.car.CarState
import app.prend.car.Navi
import app.prend.ui.Ask
import app.prend.ui.NapPhase
import app.prend.ui.Scene
import app.prend.ui.Stage
import app.prend.voice.Speaker

/**
 * 초보 운전을 돕는 차의 한마디들(사용자 결정 10/27, 외부 제안 11개 중 차가 값을 주고 현대차에 없는 것만).
 * - 주차 코치: "주차 도와줘", 또는 초보 운전이고 2분 안에 R↔D를 세 번 넘게 오가면 켠다. 핸들 각도(Pleos SteeringWheel)로
 *   "바퀴가 왼쪽 끝까지 꺾였어요"·"바퀴가 똑바로예요"만 말한다(차가 주차 칸 어디에 있는지는 모르니 어디로 돌리라고는 하지 않는다).
 *   (P에 넣을 때 바퀴 꺾임을 짚던 말은 11/01에 뺐다: 운전 평가). "주차 코치 꺼"면 끄고 그 운행엔 저절로 켜지 않는다(꺼져 있으면 그렇다고만, 검토 11/07)
 *   인사도 바퀴 안내도 차가 멈춰 있을 때만 하고(움직이는 중에 켜지면 인사는 처음 멈춰 설 때), 말하다 차가 구르면 멈춘다(11/02·11/03)
 * - 좁은 주차 뒤 문 조심(초보): 3분 안에 R↔D를 세 번 넘게 오가다 P에 넣으면 "내리실 때 문을 천천히 여세요. 옆 차에 닿지 않게요."
 *   (대사 원칙: 칭찬·운전 평가 없이 두 문장까지. 고쳐 쓴 줄은 tools/voice/ride4.json, 감사 F110)
 * - 회전교차로(초보): 내비의 다음 회전 안내가 회전교차로이고 300m 안이면 "곧 회전교차로예요. 돌고 있는 차가 먼저니 천천히 들어가세요."
 *   (비보호 좌회전은 내비가 알려 주지 않아 넣지 못했다)
 * - (출구를 놓쳤을 때 한마디는 11/01에 뺐다: 내비의 새 경로 안내와 겹침)
 * - 하이패스(초보 자동·누구나 말로): 요금소 150m 안에서 급히 줄이면(거의 멈춰 선 시속 10km 아래는 빼고), 또는 "하이패스 그냥 지나왔어"면 "멈추지 말고 그대로 가세요.
 *   통행료는 나중에 내면 돼요"(한국도로공사 안내: 그대로 통과하고 영업소·누리집·콜센터 등에서 미납 통행료를 낸다)
 * - 유종·주유구(누구나): "주유구 어느 쪽이야"·"기름 뭐 넣어"면, 또 목적지가 주유소·충전소이고 1분 30초 안이면
 *   "이 차는 휘발유 차예요. 주유구는 오른쪽에 있어요"(AAOS INFO_FUEL_TYPE·INFO_FUEL_DOOR_LOCATION, 혼유 막기)
 * - 김서림: 비가 오거나(와이퍼) 바깥이 5°C 이하인데 안쪽 공기만 10분 넘게 돌리면 바깥 공기로 바꿀지 한 번 묻는다(Care).
 *   "앞이 뿌얘"·"김 서렸어"면 앞유리 성에 제거를 바로 켠다(바깥 공기·에어컨 함께). 3분 안의 "되돌려"면 켜기 전으로, "성에 제거 꺼"면 끈다(검토 11/07)
 * 길 한마디(회전교차로·하이패스)는 깜빡이·후진 동안 미뤘다가 15초 안에 편해지면 하고, 동승자가 잘래요로 쉬는 동안은 글로만 알린다(별첨 2 V9)
 * 뺀 것: 주차장 사전 경고(기계식·진입로 폭 자료가 없다), 동승자 고성 중재(마이크를 늘 켜야 해 '엿듣지 않기'와 부딪힘),
 * 전조등 꺼짐 알림(전조등 상태는 일반 앱이 못 읽는다, CAR_EXTERIOR_LIGHTS).
 * 11/01 말 줄이기(사용자 "말이 많으면 거슬리는 앱"): 출구 놓침 한마디는 뺐다(내비의 새 경로 안내와 같은 순간·같은 뜻으로 겹친다, 별첨 2 V17).
 * P에서 바퀴 꺾임 짚기도 뺐다(운전 평가이고 문 조심과 같은 순간에 나온다). 먼저 꺼내는 한마디(회전교차로·하이패스·주유·문 조심)는
 * 말 차례(ride/Floor)를 지나고 다른 말을 끊지 않으며, 조용히 가기·다른 앱 음악·잘래요면 글로만 알린다. 회전교차로는 같은 회전교차로에 한 번
 * - 우회전(11/02·11/03, 검토-방향노인초보 3-8): 다음 회전이 우회전이고 150~800m 앞이면 그 전 신호 대기에서. 운전 도움이면 목소리와 카드,
 *   그 밖의 사람은 소리 없는 카드 한 번. [알고 있어요]면 다시 하지 않는다. 이 차에 남기는 것은 목소리 횟수·카드를 띄웠나·[알고 있어요]
 */
class Coach(
    private val speaker: Speaker,
    private val navi: Navi?,
    private val onHighway: () -> Boolean,
    private val outside: () -> Float?,
    /** 김서림이 걱정될 때 Care가 묻는다 */
    private val askFog: () -> Unit,
    /** Care가 다른 말을 건네거나 묻는 중이 아닌가 */
    private val careIdle: () -> Boolean,
    /** 말 차례(먼저 꺼내는 한마디는 한 번 설 때 하나, 앞 말 뒤 20초, 한 운행 세 번) */
    private val floor: Floor? = null,
    /** 다른 앱 음악이 흐르는가(그 위에는 한마디를 얹지 않고 글로만) */
    private val otherMusic: () -> Boolean = { false },
    /** 말할 때 고르기(길 안내 음성 중·막 끝남, 급가속·급제동 뒤, 이야기 중이면 한마디를 미룬다) */
    private val timing: Timing? = null,
    /** 우회전 한 줄을 모두 몇 번 했나(11/02, 세 번까지), 소리 없는 카드를 띄웠나, [알고 있어요] */
    private val prefs: android.content.SharedPreferences? = null,
    /** 소리 없는 카드를 띄워도 되나(다른 말·카드·쪽잠·혹시 사고 살핌이 없음, 해 지기 전에와 같은 조건) */
    private val cardFree: () -> Boolean = { true },
    /** 지금 R↔D는 골목에서 돌려 나오기·비켜 주기로 보이는가(주차 코치를 저절로 켜지 않는다, RideService가 골목 판정·도착·남은 시간으로 정한다) */
    private val turning: () -> Boolean = { false },
) {

    private val hub get() = CarLink.hubOrNull
    /**
     * 초보 운전 도움을 지금 쓰나. 대리 운행이면 쉰다: 운전하는 사람이 주인이 아니고, 먼저 말하지 않는다(별첨 2 V18, 감사 F12 ③).
     * 그래서 저절로 켜는 주차 코치·문 조심·하이패스·회전교차로·출구 놓침 한마디가 대리 중에는 나오지 않는다
     */
    private val novice get() = Stage.ui.value.let { it.novice && !it.daeri }

    // 주차
    /** 주차 코치 중(초보 한마디는 이때 말하지 않는다) */
    var coaching = false
        private set
    private var coachSince = 0L
    private val swaps = ArrayDeque<Long>()
    /** 이번 운행에 골목 돌려 나오기로 보고 코치를 켜지 않았다고 적었나(같은 줄을 R↔D마다 적지 않게) */
    private var turnLogged = false
    /** 이번 운행에 "주차 코치 꺼"라고 했다: R↔D를 세 번 넘게 오가도 저절로 다시 켜지 않는다("주차 도와줘"면 켠다, 검토 11/07) */
    private var parkOffSaid = false
    /** 이번 운행에 조용히 가기·잘래요라 코치를 저절로 켜지 않았다고 적었나(같은 줄을 R↔D마다 적지 않게) */
    private var hushLogged = false
    private var zone: String? = null
    private var zoneSince = 0L
    private var said: String? = null
    private var lastCue = 0L
    /** 첫 인사("주차 도와드릴게요…")가 끝났다. 끝나기 전에는 바퀴 안내를 미룬다(인사를 끊지 않게) */
    private var introDone = true
    /** 인사를 시작한 때(못 틀었거나 끊겼으면 8초 뒤부터 바퀴 안내), 움직이는 중에 켜져 처음 멈춰 설 때로 미룬 인사, 그 인사가 다른 말을 끊어도 되나(말로 청함) */
    private var introAt = 0L
    private var introWait = false
    private var introCut = false

    // 길
    private var roundAt = 0L
    /** 이번 회전교차로에 이미 말했나, 마지막으로 본 회전교차로 안내의 종류와 남은 거리(종류가 바뀌거나 거리가 늘면 다음 회전교차로) */
    private var roundSaid = false
    private var roundType: String? = null
    private var roundM = 0.0
    /** 이번 운행에 우회전 한 줄을 했나 */
    private var rightSaid = false
    /** 이번 우회전에는 버렸나(깜빡이·차로 변경 직후·길 안내 음성), 마지막으로 본 우회전까지 남은 거리(늘면 다음 우회전), 카드를 띄운 때 */
    private var rightDropped = false
    private var rightM = 0.0
    private var rightCardAt = 0L
    private var tollNear = 0L
    private val speeds = ArrayDeque<Pair<Long, Float>>()
    private var tollAt = 0L
    private var fuelSaid = false
    /** 마지막으로 본 목적지 이름과 그것이 PREND가 넣은 곳인가(화장실로 넣은 주유소에 유종·주유구를 말하지 않게, 11/08 오류 점검 F7) */
    private var fuelDest: String? = null
    private var fuelDestSelf = false
    /** 미뤄 둔 길 한마디(하이패스·출구 놓침): 목소리, 이름, 사람이 청한 것처럼 할지(force), 생긴 때. 15초 안에 말할 때가 오면 한다 */
    private data class RoadTip(val asset: String, val label: String, val text: String, val at: Long)
    private var roadTip: RoadTip? = null
    /** 길 한마디를 미룬 까닭(같은 까닭이면 기록을 한 번만) */
    private var roadHeld: String? = null

    // 공기
    private var recircSince = 0L
    private var fogAsked = false
    private var airRead = 0L
    /**
     * 말로 켠 앞유리 성에 제거(검토 11/07 멋대로 판단: 알아서 한 일만 "되돌려"로 끌 수 있었다). 켠 때(elapsedRealtime, 0이면 없음)와
     * 켜기 전의 공조 전원·성에 제거·내기 순환(모르면 null). 3분 안의 "되돌려"로 켜기 전으로 돌린다
     */
    private var defrostAt = 0L
    private var powerWas: Boolean? = null
    private var defrostWas: Boolean? = null
    private var recircWas: Boolean? = null
    /**
     * 켜는 중인 성에 제거 요청의 번호와 시작한 때(0이면 없음), 그 전에 공조가 켜져 있었나(모르면 null).
     * 공조가 꺼져 있으면 공조를 켜고 2분까지 다시 보내는데, 그사이 "성에 제거 꺼"·"되돌려"가 오면 거둔다(끄라고 한 뒤에 켜지 않게, 11/08 오류 점검 D2)
     */
    private var fogGen = 0
    private var fogPendingAt = 0L
    private var fogPowerBefore: Boolean? = null
    /** Care.undo가 부를 되돌리기(이 Coach의 것일 때만 release에서 뗀다) */
    private val undoHook: () -> Boolean = ::undoFog

    init {
        navi?.onReroute = { reroute() }
        Stage.onCoach = { what -> request(what) }
        Stage.sideAsks["RIGHT"] = ::rightAnswer
        undoDefrost = undoHook
    }

    fun newRide() { rightSaid = false; rightDropped = false; fuelSaid = false; fogAsked = false; recircSince = 0L; swaps.clear(); roadTip = null; turnLogged = false; parkOffSaid = false; defrostAt = 0L; hushLogged = false; fogGen++; fogPendingAt = 0L }

    /** 말로 청한 것: park(주차 도와줘), park_off(주차 코치 꺼), fuel(주유구·유종), toll(하이패스), fog(김 서림), fog_off(성에 제거 꺼) */
    private fun request(what: String) {
        when (what) {
            "park" -> startCoach("말로 청함")
            "park_off" -> parkOff()
            "fuel" -> if (!fuel(asked = true)) Stage.toast("이 차는 유종과 주유구 위치를 알려 주지 않아요")
            "toll" -> say(listOf("voice/toll_tip.wav"), "하이패스", force = true)
            "fog" -> fogOn()
            "fog_off" -> fogOff()
        }
    }

    /** "주차 코치 꺼": 켜져 있으면 끄고, 꺼져 있으면 꺼져 있다고만 한다. 끄는 말로 켜지 않는다(검토 11/07 멋대로 판단) */
    private fun parkOff() {
        // 이번 운행엔 R↔D로 저절로 다시 켜지 않는다(오간 수를 비우지 않으면 다음 R↔D에 곧바로 다시 켜진다)
        parkOffSaid = true
        swaps.clear()
        if (!coaching) { Stage.toast("주차 코치는 꺼져 있어요", 2_500); Prend.log("· 주차 코치: 꺼 달라고 했는데 이미 꺼져 있음"); return }
        if (speaker.saying == COACH) speaker.stop()
        stopCoach("말로 끔")
        Stage.toast("주차 코치를 껐어요", 2_500)
    }

    /** 앞유리 성에 제거를 켠다(말로 청함·김서림 물음의 대답). 켜기 전의 공조를 읽어 두어 3분 안의 "되돌려"에 켜기 전으로 돌린다 */
    private fun fogOn() {
        val h = hub ?: return
        // 켜는 중이면 또 보내지 않는다(다시 시도가 둘 돌아 알림·목소리가 두 번 나오지 않게, 11/08 오류 점검 D2)
        if (fogPending()) { Stage.toast("앞유리 성에 제거를 켜는 중이에요", 2_500); return }
        val g = ++fogGen
        fogPendingAt = SystemClock.elapsedRealtime()
        fogPowerBefore = null
        // 3분 안에 다시 켜면 처음 켜기 전의 값을 그대로 둔다(덮어쓰면 "되돌려"가 켜진 채로 두었다, 11/08 오류 점검 F8)
        val first = defrostAt == 0L || SystemClock.elapsedRealtime() - defrostAt > UNDO_MS
        val recirc = h.state.recirc
        h.readClimate { c ->
            if (g != fogGen) return@readClimate
            fogPowerBefore = c.power
            h.defrost(live = { g == fogGen }) { ok ->
                if (g != fogGen) return@defrost
                fogPendingAt = 0L
                Stage.toast(if (ok) "앞유리 성에 제거를 켰어요" else "성에 제거를 켜지 못했어요. 공조의 앞유리 버튼을 눌러 주세요", 3_500)
                if (!ok) return@defrost
                defrostAt = SystemClock.elapsedRealtime()
                if (first) {
                    powerWas = c.power
                    // 공조가 꺼져 있었으면 성에 제거도 꺼져 있던 것이다
                    defrostWas = if (c.power == false) false else c.defrost
                    recircWas = recirc ?: c.recirc
                }
                say(listOf("voice/fog_on.wav"), "성에 제거", force = true)
            }
        }
    }

    /** 성에 제거를 켜는 중인가(공조를 켜고 다시 보내는 중). 대답이 끝내 오지 않아도 3분이면 놓는다 */
    private fun fogPending() = fogPendingAt != 0L && SystemClock.elapsedRealtime() - fogPendingAt < FOG_PENDING_MS

    /** 켜는 중인 성에 제거를 거둔다("성에 제거 꺼"·"되돌려"). 공조가 꺼져 있었으면 PREND가 켠 공조도 끈다. 거뒀으면 true(11/08 오류 점검 D2) */
    private fun dropFog(): Boolean {
        if (!fogPending()) return false
        fogGen++
        fogPendingAt = 0L
        Prend.log("· 켜던 성에 제거를 거둠(공조 ${fogPowerBefore.on()})")
        if (fogPowerBefore == false) hub?.setHvacPower(false) { }
        Stage.toast("켜던 앞유리 성에 제거를 그만뒀어요", 3_000)
        return true
    }

    /** "성에 제거 꺼": 앞유리 성에 제거만 끈다(함께 켠 에어컨·바깥 공기는 그대로). 끄는 말로 켜지 않는다(검토 11/07) */
    private fun fogOff() {
        defrostAt = 0L
        // 켜는 중이던 것을 거둔다: 지금 읽으면 아직 꺼져 있어 "꺼져 있어요"라 하고는 나중에 켜졌다(11/08 오류 점검 D2)
        if (dropFog()) return
        val h = hub ?: return Stage.toast(FOG_OFF_HAND, 3_500)
        h.readClimate { c ->
            if (c.power == false || c.defrost == false) { Stage.toast("앞유리 성에 제거는 꺼져 있어요", 2_500); return@readClimate }
            h.defrostOff { ok -> Stage.toast(if (ok) "앞유리 성에 제거를 껐어요" else FOG_OFF_HAND, 3_500) }
        }
    }

    /**
     * "되돌려"(ride/Care.undo가 부른다): 말로 켠 앞유리 성에 제거를 3분 안이면 켜기 전으로 돌린다. 공조가 꺼져 있었으면 공조를 끄고,
     * 아니면 성에 제거를 끄고(켜기 전부터 켜져 있었으면 그대로) 안쪽 공기였으면 안쪽 공기로. 함께 켠 에어컨은 켜기 전을 몰라 그대로 둔다.
     * 되돌렸으면 true(3분이 지났거나 말로 켠 것이 없으면 false)
     */
    private fun undoFog(): Boolean {
        // 켜는 중이던 것도 거둔다("되돌릴 일이 없어요" 뒤에 켜지지 않게, 11/08 오류 점검 D2)
        if (dropFog()) return true
        if (defrostAt == 0L || SystemClock.elapsedRealtime() - defrostAt > UNDO_MS) return false
        defrostAt = 0L
        val h = hub ?: return false
        Prend.log("◆ 말로 켠 성에 제거 되돌림(공조 ${powerWas.on()}, 성에 제거 ${defrostWas.on()}, 안쪽 공기 ${recircWas.on()})")
        fun inside(done: (String?) -> Unit) { if (recircWas == true) h.setRecirc(true) { ok -> done(if (ok) "안쪽 공기로 되돌렸어요" else null) } else done(null) }
        when {
            powerWas == false -> h.setHvacPower(false) { ok -> Stage.toast(if (ok) "앞유리 성에 제거를 끄고 공조도 켜기 전처럼 껐어요" else FOG_OFF_HAND, 3_500) }
            defrostWas == true -> inside { line -> Stage.toast(line ?: "성에 제거는 켜기 전부터 켜져 있었어요", 3_000) }
            else -> h.defrostOff { ok ->
                if (!ok) Stage.toast(FOG_OFF_HAND, 3_500)
                else inside { line -> Stage.toast(listOfNotNull("앞유리 성에 제거를 껐어요", line).joinToString(" · "), 3_500) }
            }
        }
        return true
    }

    private fun Boolean?.on() = when (this) { true -> "켬"; false -> "끔"; null -> "모름" }

    fun onCar(old: CarState, now: CarState) {
        val t = SystemClock.elapsedRealtime()
        // 멈춰 서서 시작한 주차 코치의 인사·바퀴 안내가 나오는 중에 차가 구르면 1초 틱을 기다리지 않고 멈춘다(11/03): 페달과 주차 거리 경고음을 들어야 하는 때다
        if (now.speedKmh >= 1f && speaker.saying == COACH) { speaker.stop(); Prend.log("· 주차 코치: 차가 움직여 말을 멈춤") }
        // R↔D를 오간 때를 센다(좁은 데 주차하느라 애쓰는 중)
        val r = { g: Int -> g == VehicleGear.GEAR_REVERSE }
        val fwd = { g: Int -> g == VehicleGear.GEAR_DRIVE || g == VehicleGear.GEAR_NEUTRAL }
        if ((r(old.gear) && fwd(now.gear)) || (fwd(old.gear) && r(now.gear))) {
            swaps.addLast(t)
            while (swaps.isNotEmpty() && t - swaps.first() > 3 * 60_000) swaps.removeFirst()
            // 골목(단지 안 도로 말고)에서 도착 전이면 막다른 길에서 돌려 나오거나 마주 오는 차에 비켜 주는 것일 수 있다: "주차 도와드릴게요"를 하지 않는다(11/01 골목 후보 2).
            // "주차 도와줘"라고 하면 언제든 켠다.
            // 조용히 가기·잘래요면 저절로 켜지 않는다: 저절로 켠 코치도 인사·바퀴 안내를 목소리로 해 잠든 아이·동승자를 깨웠다("주차 도와줘"면 켠다, 11/08 오류 점검 F2)
            if (novice && !coaching && !parkOffSaid && swaps.count { t - it < 2 * 60_000 } >= 3) {
                if (turning()) { if (!turnLogged) { turnLogged = true; Prend.log("· 주차 코치: 골목에서 R↔D, 돌려 나오기로 보고 켜지 않음") } }
                else if (Stage.ui.value.let { it.quietRide || it.sleeper != null }) { if (!hushLogged) { hushLogged = true; Prend.log("· 주차 코치: 조용히 가기·잘래요라 저절로 켜지 않음") } }
                else startCoach("R↔D 세 번", now)
            }
        }
        if (old.gear != now.gear && now.parked) parked(t)
        // 요금소 앞 급제동(초보): 하이패스 차로를 잘못 들어와 놀란 것일 수 있다.
        // 거의 멈춰 섰으면(시속 10km 아래) 하지 않는다: 현금 차로에서 돈을 내려고 선 것이고, 하이패스 차로에서 놀란 사람은 서지 않고 속도만 줄인다(검토 11/07)
        speeds.addLast(t to now.speedKmh)
        while (speeds.size > 2 && t - speeds.first().first > 1_200) speeds.removeFirst()
        val ref = speeds.firstOrNull { t - it.first >= 900 }
        if (novice && tollNear > 0 && t - tollNear < 60_000 && ref != null && ref.second - now.speedKmh > 15f && now.speedKmh < 50f && now.speedKmh >= TOLL_MIN_KMH && t - tollAt > 5 * 60_000) {
            tollAt = t
            Prend.log("◆ 요금소 앞 급제동(초보): 하이패스 한마디")
            roadSay(RoadTip("voice/toll_tip.wav", "하이패스", TOLL_TEXT, at = t), now)
        }
    }

    /** 1초마다 */
    fun tick(car: CarState) {
        val t = SystemClock.elapsedRealtime()
        if (coaching) coachTick(car, t)
        // 우회전 카드는 출발(시속 3km 넘게)하거나 D가 아니거나 40초면 거둔다. Care가 제 물음으로 바꿨으면 두지 않는다
        if (Stage.ui.value.ask?.id == "RIGHT" && (car.speedKmh > 3f || !car.driving || t - rightCardAt > CARD_MS)) {
            Stage.ask(null); Prend.log("· 우회전 카드 거둠(${if (car.speedKmh > 3f) "출발" else if (!car.driving) "D 아님" else "40초"})")
        }
        // 버린 우회전은 다음 회전이 우회전이 아니게 되거나, 경로가 끝나거나, 남은 거리가 늘면(다음 우회전) 다시 연다. 오래 서서 안내가 묵은 동안은 그대로 둔다
        navi?.nextTurn.let { raw -> if (raw == null || raw.type != "TURN_RIGHT" || raw.meters > rightM + 50) rightDropped = false; if (raw?.type == "TURN_RIGHT") rightM = raw.meters }
        // 회전교차로 한 줄은 다음 회전이 회전교차로가 아니게 되거나(종류가 바뀜·경로 끝), 남은 거리가 늘면(다음 회전교차로) 다시 연다.
        // 막혀 서 있느라 안내가 묵은 동안은 그대로 둔다(묵었다 다시 온 같은 회전교차로에 두 번 하지 않게)
        navi?.nextTurn?.takeIf { it.type.startsWith("TURN_ENTER_ROUNDABOUT") }.let { r ->
            if (r == null || r.type != roundType || r.meters > roundM + 50) roundSaid = false
            roundType = r?.type; if (r != null) roundM = r.meters
        }
        // 다음 회전 안내: 회전교차로·요금소
        val turn = navi?.nextTurn?.takeIf { System.currentTimeMillis() - it.at < 60_000 }
        if (turn != null && car.driving) {
            // 깜빡이·후진 동안은 건너뛰고 다음 틱에 다시 본다(300m 안에 있는 동안). 같은 회전교차로에는 한 번만(막혀 서 있는 동안 되풀이하지 않게)
            val round = turn.type.startsWith("TURN_ENTER_ROUNDABOUT") && turn.meters <= 300
            if (novice && round && !roundSaid && t - roundAt > 60_000 && careIdle() && roadQuiet(car) == null &&
                tip(listOf("voice/round_tip.wav"), "회전교차로", ROUND_TEXT)) {
                roundAt = t
                roundSaid = true
                Prend.log("◆ 회전교차로 ${turn.meters.toInt()}m 앞(초보): 한마디")
            }
            // 우회전: 길 안내의 다음 회전이 우회전이고 150~800m 앞이면 그 전 신호 대기에서. 우회전할 교차로 150m 안에서는 하지 않는다(횡단보도를 봐야 한다)
            if (turn.type == "TURN_RIGHT" && turn.meters in 150.0..800.0) right(car, turn)
            if (turn.type == "TURN_TOLL" && turn.meters <= 150) tollNear = t
        }
        // 목적지가 주유소·충전소이고 곧 도착: 유종·주유구(먼저 건네는 말이라 대리 운행에는 하지 않는다. "주유구 어느 쪽이야"에는 답한다)
        val dest = navi?.destName
        // 목적지가 바뀐 때 PREND가 막 길을 넣었으면(화장실로 고른 주유소 등) 그 목적지에는 말하지 않는다(AlleyDest·Dusk와 같은 1분, 11/08 오류 점검 F7)
        if (dest != fuelDest) { fuelDest = dest; fuelDestSelf = dest != null && System.currentTimeMillis() - (navi?.selfRouteAt ?: 0L) < 60_000 }
        val eta = navi?.etaSec()
        val fuelTold = prefs?.getInt("fuelTold", 0) ?: 0
        if (!fuelSaid && fuelTold < (if (novice) 3 else 1) && !Stage.ui.value.daeri && dest != null && !fuelDestSelf && eta != null && eta <= 90 && FUEL_PLACE.any { it in dest } && careIdle()) {
            fuelSaid = true
            // 주유소에 갈 때마다 말하지 않게 센다(11/04 사람 시험)
            prefs?.edit()?.putInt("fuelTold", fuelTold + 1)?.apply()
            Prend.log("◆ 주유소 도착 전: 유종·주유구")
            fuel(asked = false)
        }
        roadTick(car, t)
        airTick(car, t)
    }

    // ── 주차 코치 ──

    private fun startCoach(why: String, car: CarState = CarLink.state) {
        // 이미 켜져 있을 때 다시 청하면 그렇다고 알린다(말없이 넘겨 받아들였는지 몰랐다, 11/08 오류 점검 F5)
        if (coaching) { if (why == "말로 청함") Stage.toast("주차 도와드리는 중이에요", 2_500); return }
        coaching = true
        coachSince = SystemClock.elapsedRealtime()
        zone = null; said = null
        Prend.log("◆ 주차 코치 켬($why)")
        hub?.steerWatch(true)
        introDone = false
        introCut = why == "말로 청함"
        // 차가 움직이는 동안은 인사도 하지 않는다(11/03): 처음 멈춰 설 때 한다
        introWait = car.speedKmh >= 1f
        if (introWait) {
            Prend.log("· 주차 코치: 움직이는 중이라 인사는 멈춰 설 때")
            // 말로 청했으면 받아들였다고 글로 알린다(목소리도 글도 없어 받아들였는지 몰랐다, 11/08 오류 점검 F5)
            if (introCut) Stage.toast("세우시면 주차를 도와드릴게요", 3_000)
        } else intro()
    }

    /** 첫 인사. 저절로 켤 때는 다른 말을 끊지 않는다(못 하면 인사 없이 8초 뒤부터 바퀴 안내) */
    private fun intro() {
        introAt = SystemClock.elapsedRealtime()
        // 저절로 켰는데 동승자가 있으면 인사는 글로만(사용자 결정 11/04: 손주·아내 앞에서 "주차 도와드릴게요"는 민망하다). 바퀴 안내는 그대로.
        // [프렌드]·대답 창이 열려 있을 때도 그 위로 말하지 않고 글로만(제 목소리가 대답으로 들리지 않게, 11/08 오류 점검 F6)
        if (!introCut && (guests() || Stage.ui.value.listen != null)) { introDone = true; Stage.toast("주차를 도와드려요 · 바퀴 방향을 알려 드려요", 3_500); return }
        say(listOf("voice/coach_on.wav"), COACH, force = true, interrupt = introCut) { introDone = true }
    }

    /** 운전석 말고 다른 자리에 사람이 있나 */
    private fun guests() = CarLink.state.seats.any { (k, v) -> v && k != "ROW_1_LEFT" }

    private fun stopCoach(why: String) {
        if (!coaching) return
        coaching = false
        introWait = false
        hub?.steerWatch(false)
        Prend.log("· 주차 코치 끝($why)")
    }

    /** 핸들 각도의 구역이 1.2초 넘게 이어지면 한 번 말한다. 끝까지 꺾임과 똑바로만 말하고, 같은 말은 되풀이하지 않는다 */
    private fun coachTick(car: CarState, t: Long) {
        // 저절로 켠 코치는 출발해 빨리 달리거나 5분이 지나면 끝. 말로 청한 코치는 달려도 끄지 않고 5분(달리며 청했으면 처음 멈춰 선 때부터 5분)을 켜 둔다
        // (주차장에 들기 전 시속 20~30에 "주차 도와줘"라고 하면 1초 만에 말없이 꺼졌고, 가는 길 빨간불에 한 번 섰다 다시 달려도 꺼졌다, 11/08 오류 점검 F5·사용자 결정).
        // P에 넣거나 "주차 코치 꺼"라고 하면 그때 끝
        val gone = car.speedKmh > 15f && !introCut
        if (gone || t - coachSince > 5 * 60_000) { stopCoach(if (gone) "출발" else "5분"); return }
        // 움직이는 중에 켜졌으면 처음 멈춰 설 때 인사부터(말로 청한 코치는 그때부터 5분)
        if (introWait) { if (car.speedKmh < 1f) { introWait = false; if (introCut) coachSince = t; intro() }; return }
        val a = car.steer ?: return
        // 인사가 끝날 때까지 기다린다(못 틀었거나 끊겼으면 8초 뒤부터)
        if (!introDone && t - introAt < 8_000) return
        // Pleos SDK의 핸들 각도는 오른쪽이 음수다: 콘솔에서 오른쪽으로 민 +456°가 앱에는 −456°로 온다(0주차 시험). 실차에서 다시 확인한다
        val z = when {
            a <= -LOCK -> "right_full"
            a >= LOCK -> "left_full"
            kotlin.math.abs(a) <= STRAIGHT -> "straight"
            a < 0 -> "right"
            else -> "left"
        }
        if (z != zone) { zone = z; zoneSince = t; return }
        if (t - zoneSince < 1_200 || z == said || t - lastCue < 2_500) return
        // 차가 움직이는 동안은 말하지 않는다(11/02): 페달을 번갈아 밟고 주차 거리 경고음을 들어야 하는 때다. 멈춰 서서 핸들을 돌릴 때만
        if (car.speedKmh >= 1f) return
        // [프렌드]·대답 창이 열려 있으면 그 위로 말하지 않는다. 구역은 그대로 두어 창이 닫히면 다음 틱에 말한다(11/08 오류 점검 F6)
        if (Stage.ui.value.listen != null) return
        // 처음 한 번은 지금 바퀴 방향을, 그 뒤로는 끝까지 꺾임과 똑바로만
        val asset = when {
            z == "left_full" -> "voice/wheel_left_full.wav"
            z == "right_full" -> "voice/wheel_right_full.wav"
            z == "straight" && said != null -> "voice/wheel_straight.wav"
            said == null -> when (z) { "left" -> "voice/wheel_left.wav"; "right" -> "voice/wheel_right.wav"; else -> "voice/wheel_straight.wav" }
            else -> null
        } ?: return
        said = z
        lastCue = t
        Prend.log("◆ 주차 코치: 바퀴 $z(${a.toInt()}°)")
        say(listOf(asset), COACH, force = true)
    }

    /** P에 넣었다: 좁은 데서 애썼으면(초보) 문 조심. 바퀴 꺾임 짚기는 뺐다(운전 평가, 11/01) */
    private fun parked(t: Long) {
        val tight = swaps.count { t - it < 3 * 60_000 } >= 3
        swaps.clear()
        stopCoach("P")
        // 바퀴 안내 바로 뒤라 말 차례에 막히면 글로라도 남긴다(내리기 전 그때뿐인 말)
        if (novice && tight) { Prend.log("◆ 주차 끝: 문 조심"); if (guests() || !tip(listOf("voice/door_slow.wav"), "문 조심", DOOR_TEXT)) Stage.toast(DOOR_TEXT, 5_000) }
    }

    // ── 길 ──

    /** 경로를 다시 찾았다. 출구 놓침 한마디는 뺐다: 내비가 새 경로를 안내하는 바로 그 순간이라 말이 겹친다(11/01) */
    private fun reroute() = Prend.log("· 경로 재탐색: 한마디 안 함(내비 안내와 겹침)")

    /**
     * 우회전 한 줄(11/02·11/03, 검토-방향노인초보 3-8). 우회전 방법을 정확히 아는 운전자는 400명 중 1명(경기연구원).
     * - 운전 도움: 목소리 한마디와 같은 뜻의 카드(한 운행 한 번, 모두 세 번까지). 조용히 가기·잘래요·다른 앱 음악이면 카드만(세 번에 세지 않는다)
     * - 그 밖의 사람: 소리 없는 카드 한 번(대리 운행이면 하지 않는다)
     * 신호 대기에서 깜빡이·후진, 깜빡이를 끈 지 10초 안, 길 안내 음성 중·막 끝남이면 그 우회전에는 삼키고 버린다(기록은 한 번).
     * 다른 말 중·말 차례에 막히면 다음 틱에 다시 본다
     */
    private fun right(car: CarState, turn: Navi.Turn) {
        val p = prefs ?: return
        val tm = timing ?: return
        val ui = Stage.ui.value
        if (rightSaid || rightDropped || p.getBoolean("rightKnown", false)) return
        val n = p.getInt("right", 0)
        val voice = novice && n < RIGHT_MAX
        val card = !ui.novice && !ui.daeri && !p.getBoolean("rightCard", false)
        if (!voice && !card || !tm.atLight(car)) return
        val drop = roadQuiet(car) ?: tm.laneWhy() ?: tm.guideWhy()
        if (drop != null) { rightDropped = true; Prend.log("· 우회전 한 줄 버림(이번 우회전, $drop)"); return }
        if (!careIdle()) return
        val m = turn.meters.toInt()
        val hush = if (voice) hushWhy() else null
        if (voice && hush == null) {
            if (!tip(listOf("voice/right_tip.wav"), "우회전", null)) return
            rightSaid = true
            p.edit().putInt("right", n + 1).apply()
            // 목소리 옆에 같은 뜻의 카드(이번에 선 동안의 차례는 방금 목소리가 썼다). 다른 카드가 떠 있거나 첫 화면이 아니면 두지 않는다
            val shown = Stage.ui.value.let { it.ask == null && it.scene == Scene.HOME }
            if (shown) showRight()
            Prend.log("◆ 우회전 ${m}m 앞(운전 도움, 신호 대기): 한마디 ${n + 1}/$RIGHT_MAX${if (shown) " · 카드" else ""}")
            return
        }
        if (!rightCardOk(car)) return
        rightSaid = true
        if (!voice) p.edit().putBoolean("rightCard", true).apply()
        floor?.shown("우회전")
        showRight()
        Prend.log("◆ 우회전 ${m}m 앞(신호 대기): ${if (voice) "운전 도움이지만 $hush → 카드만(세 번에 세지 않음)" else "소리 없는 카드 한 번"}")
    }

    /** 소리 없는 우회전 카드를 지금 띄워도 되나: 다른 카드가 없는 첫 화면, 다른 말·카드·주차 코치가 없음, 말할 때 막음과 말 차례(이번에 선 동안 하나·앞 말 뒤 20초)를 지남 */
    private fun rightCardOk(car: CarState): Boolean {
        val ui = Stage.ui.value
        if (ui.ask != null || ui.scene != Scene.HOME || coaching || !cardFree()) return false
        if (timing?.block(car) != null) return false
        return floor?.why(Floor.Kind.ASK) == null
    }

    private fun showRight() {
        rightCardAt = SystemClock.elapsedRealtime()
        Stage.ask(Ask("RIGHT", RIGHT_CARD, "도로교통법 시행규칙 별표 2", "알고 있어요", ""))
    }

    /** [알고 있어요]: 우회전 한 줄은 다시 하지 않는다(목소리도 카드도) */
    private fun rightAnswer(yes: Boolean) {
        Stage.ask(null)
        if (!yes) return
        prefs?.edit()?.putBoolean("rightKnown", true)?.apply()
        Stage.toast("우회전 한 줄은 이제 알리지 않을게요", 2_500)
        Prend.log("· 우회전: 알고 있어요 → 다시 하지 않음")
    }

    /**
     * 길 한마디를 지금 하지 않을 까닭: 동승자가 잘래요로 쉬는 중(먼저 말 걸지 않기, 별첨 2 V9), 깜빡이·후진(Timing.block과 같은 눈금).
     * 급제동 뒤 4초는 보지 않는다: 하이패스 한마디가 바로 그 급제동 때라
     */
    private fun roadQuiet(car: CarState): String? = when {
        car.gear == VehicleGear.GEAR_REVERSE -> "후진 중"
        car.turnSignal != "OFF" -> "깜빡이"
        else -> null
    }

    /** 길 한마디를 줄에 둔다(지금 말할 때면 바로) */
    private fun roadSay(tip: RoadTip, car: CarState) {
        roadTip = tip
        roadHeld = null
        roadTick(car, tip.at)
    }

    /** 미뤄 둔 길 한마디: 말할 때면 하고, 15초가 지나면 버린다(그 자리를 지나 쓸모가 없다) */
    private fun roadTick(car: CarState, t: Long) {
        val tip = roadTip ?: return
        if (t - tip.at > ROAD_WAIT_MS) { roadTip = null; Prend.log("· ${tip.label} 한마디 버림: 15초 동안 말할 때가 없음(${roadHeld ?: "다른 말"})"); return }
        // 미루는 사이 요금소 앞에 거의 멈춰 섰으면 하이패스 한마디는 버린다(서서 돈을 내는 사람에게 "멈추지 말고"가 되지 않게)
        if (tip.text == TOLL_TEXT && car.speedKmh < TOLL_MIN_KMH) { roadTip = null; Prend.log("· ${tip.label} 한마디 버림: 거의 멈춰 섬"); return }
        val why = roadQuiet(car)
        if (why == null && tip(listOf(tip.asset), tip.label, tip.text, hard = false)) { roadTip = null; return }
        if (why != null && why != roadHeld) { roadHeld = why; Prend.log("· ${tip.label} 한마디 미룸($why)") }
    }

    /** 유종과 주유구(충전구) 위치를 말한다. asked면 사람이 물은 것(대답), 아니면 먼저 꺼내는 한마디. 차가 알려 주지 않으면 false */
    private fun fuel(asked: Boolean): Boolean {
        val h = hub ?: return false
        val types = h.fuelTypes
        val kind = when {
            types.isEmpty() -> null
            types.all { it == ELECTRIC } -> "ev"
            HYDROGEN in types -> "h2"
            types.any { it in DIESEL } -> "diesel"
            LPG in types -> "lpg"
            types.any { it in GASOLINE } -> "gas"
            else -> null
        }
        val ev = kind == "ev"
        val side = sideOf(if (ev) h.evPort else h.fuelDoor)
        val lines = listOfNotNull(kind?.let { "voice/fuel_$it.wav" }, side?.let { "voice/${if (ev) "port" else "door"}_$it.wav" })
        if (lines.isEmpty()) return false
        Stage.toast(listOfNotNull(
            when (kind) { "gas" -> "휘발유 차"; "diesel" -> "경유 차"; "lpg" -> "LPG 차"; "ev" -> "전기차"; "h2" -> "수소차"; else -> null },
            side?.let { "${if (ev) "충전구" else "주유구"} ${SIDE_WORD[it]}" },
        ).joinToString(" · "), 5_000)
        if (asked) say(lines, "유종·주유구", force = true) else tip(lines, "유종·주유구", null)
        return true
    }

    private fun sideOf(loc: Int?) = when (loc) { 1, 4 -> "left"; 2, 3 -> "right"; 5 -> "front"; 6 -> "rear"; else -> null }

    // ── 공기 ──

    private fun airTick(car: CarState, t: Long) {
        if (t - airRead > 10_000) { airRead = t; hub?.readAir() }
        val risky = car.wiperOn || (outside()?.let { it <= 5f } ?: false)
        if (!car.driving || car.recirc != true || !risky) { recircSince = 0L; return }
        if (recircSince == 0L) recircSince = t
        if (!fogAsked && t - recircSince > FOG_MS) {
            fogAsked = true
            Prend.log("◆ 김서림 걱정: ${if (car.wiperOn) "비" else "추운 날"} + 안쪽 공기 ${(t - recircSince) / 60_000}분 → 물음")
            askFog()
        }
    }

    // ── 말하기 ──

    /**
     * 차례로 말한다. force가 아니면 다른 말이 나오거나 대답을 기다리는 중엔 하지 않는다. 급할 때·쪽잠(자는 동안·깨우는 동안)에는 하지 않는다.
     * 말을 시작했으면 true(미뤄 둔 길 한마디가 다음 틱에 다시 볼지 정한다)
     */
    private fun say(assets: List<String>, label: String, force: Boolean = false, interrupt: Boolean = true, onDone: () -> Unit = {}): Boolean {
        val ui = Stage.ui.value
        if (ui.scene.isE || ui.napPhase == NapPhase.SLEEP || ui.napPhase == NapPhase.WAKE) return false
        // 스스로 꺼내는 말은 다른 말이 나오거나 PREND가 대답을 기다리는 동안엔 하지 않는다(제 목소리를 대답으로 듣지 않게)
        if (!force && (speaker.speaking || !careIdle())) return false
        // 대리 운행·조용히 가기면 사람이 부른 주차 코치 말고는 먼저 말하지 않는다
        if (!force && (ui.daeri || ui.quietRide) && !coaching) return false
        val next = assets.drop(1)
        return speaker.speak(assets.first(), AudioAttributes.USAGE_ASSISTANT, label, interrupt = interrupt) { if (next.isNotEmpty()) say(next, label, force = true, onDone = onDone) else onDone() }
    }

    /**
     * 먼저 꺼내는 한마디(회전교차로·하이패스·주유·문 조심, 11/01 말 차례): 대리 운행이면 하지 않고, 조용히 가기·잘래요·다른 앱 음악이면 글(text)로만 알린다.
     * 아니면 말 차례(한 번 설 때 하나·앞 말 뒤 20초·한 운행 세 번)를 지나 다른 말을 끊지 않고 말한다. 말했거나 글로 알렸으면 true(아니면 부른 쪽이 다시 본다)
     */
    private fun tip(assets: List<String>, label: String, text: String?, hard: Boolean = true): Boolean {
        val ui = Stage.ui.value
        if (ui.scene.isE || ui.napPhase == NapPhase.SLEEP || ui.napPhase == NapPhase.WAKE) return false
        if (ui.daeri) { Prend.log("· $label: 대리 운행이라 하지 않음"); return true }
        val silent = hushWhy()
        if (silent != null) { text?.let { Stage.toast(it, 4_000) }; Prend.log("· $label: $silent → 글로만"); return true }
        if (speaker.speaking || !careIdle()) return false
        // 길 안내 음성과 겹치지 않게(V17). hard면 급가속·급제동 뒤·이야기 중 같은 말할 때 막음도 본다(하이패스는 바로 그 급제동 때라 보지 않는다)
        val busyWhy = timing?.guideWhy() ?: if (hard) timing?.block(hub?.state ?: CarState()) else null
        if (busyWhy != null) { if (busyWhy != tipHeld) { tipHeld = busyWhy; Prend.log("· $label 한마디 미룸($busyWhy)") }; return false }
        if (floor?.why(Floor.Kind.TIP) != null) return false
        tipHeld = null
        val ok = say(assets, label, force = true, interrupt = false)
        if (ok) floor?.spoke(Floor.Kind.TIP, label)
        return ok
    }

    /** 한마디를 미룬 까닭(같은 까닭이면 기록을 한 번만) */
    private var tipHeld: String? = null

    /** 먼저 꺼내는 한마디를 목소리 없이 글·카드로만 알릴 까닭: 조용히 가기, 잘래요, 다른 앱 음악 */
    private fun hushWhy(): String? = Stage.ui.value.let { ui ->
        when { ui.quietRide -> "조용히 가는 중"; ui.sleeper != null -> "잘래요"; otherMusic() -> "다른 앱 음악이 흐름"; else -> null }
    }

    /** 시험: 김서림 판단을 10분에서 10초로 */
    fun fogFast() { fogMs = 10_000L; fogAsked = false; Prend.log("· 시험: 김서림 판단 10초") }
    private var fogMs = 10 * 60_000L
    private val FOG_MS get() = fogMs

    /** 모두 잊기: 우회전 한 줄의 목소리 횟수·카드를 띄웠나·[알고 있어요]를 지운다 */
    fun forget() { prefs?.edit()?.remove("right")?.remove("rightKnown")?.remove("rightCard")?.remove("fuelTold")?.apply(); Prend.log("· 우회전·주유구: 모두 잊기") }

    /** 시험: 우회전 한 줄을 처음처럼(남긴 값과 이번 운행·이번 우회전에 한 것을 지운다) */
    fun rightReset() {
        prefs?.edit()?.remove("right")?.remove("rightKnown")?.remove("rightCard")?.apply()
        rightSaid = false; rightDropped = false
        Prend.log("· 우회전: 시험 되돌림(운전 도움 ${if (Stage.ui.value.novice) "켬" else "끔"})")
    }

    fun release() { fogGen++; hub?.steerWatch(false); navi?.onReroute = null; Stage.onCoach = null; Stage.sideAsks.remove("RIGHT"); if (undoDefrost === undoHook) undoDefrost = null }

    companion object {
        /** 끝까지 꺾임으로 보는 각도(이 차는 −470~500°), 똑바로로 보는 각도 */
        const val LOCK = 420f
        const val STRAIGHT = 20f
        /** 주차 코치 말의 이름(차가 구르면 이 이름의 말을 멈춘다) */
        const val COACH = "주차 코치"
        const val ELECTRIC = 10
        const val HYDROGEN = 11
        const val LPG = 7
        val DIESEL = setOf(3, 4, 5)
        val GASOLINE = setOf(1, 2, 6)
        /** 주유소로 볼 목적지 낱말. "셀프"·"에너지"는 셀프빨래방·○○에너지 본사에도 걸려 뺐다(11/08 오류 점검 F7) */
        val FUEL_PLACE = listOf("주유소", "충전소", "오일뱅크", "셀프주유")
        val SIDE_WORD = mapOf("left" to "왼쪽", "right" to "오른쪽", "front" to "앞쪽", "rear" to "뒤쪽")
        /** 미뤄 둔 길 한마디를 기다리는 시간(요금소·출구를 지나면 쓸모가 없다) */
        const val ROAD_WAIT_MS = 15_000L
        /** 한마디를 글로만 알릴 때의 문장(목소리 대사와 같은 뜻, tools/voice/ride4.json·coach.json) */
        const val ROUND_TEXT = "곧 회전교차로예요. 돌고 있는 차가 먼저니 천천히 들어가세요"
        /**
         * 앞 신호가 빨간불이면 일시정지(도로교통법 시행규칙 별표 2, 2023), 돈 뒤 횡단보도에 건너거나 건너려는 사람이 있으면 일시정지(도로교통법 제27조).
         * 카드 한 줄은 빨간불 멈춤이라 출처도 별표 2(달리는 동안의 카드는 제목 한 줄만 보여 짧게. 돈 뒤 횡단보도는 목소리 대사 tools/voice/right.json에)
         */
        const val RIGHT_CARD = "우회전: 앞 신호 빨간불이면 한 번 멈춤"
        const val RIGHT_MAX = 3
        /** 우회전 카드를 두는 시간(출발하면 그 전에 거둔다) */
        const val CARD_MS = 40_000L
        const val TOLL_TEXT = "하이패스를 그냥 지나쳤어도 멈추지 말고 그대로 가세요. 통행료는 나중에 내면 돼요"
        const val DOOR_TEXT = "내리실 때 문을 천천히 여세요. 옆 차에 닿지 않게요"
        /** 이 속도 아래로 거의 멈춰 섰으면 요금소 앞 급제동에도 하이패스 한마디를 하지 않는다(검토 11/07) */
        const val TOLL_MIN_KMH = 10f
        /** 말로 켠 성에 제거를 "되돌려"로 끌 수 있는 시간(알아서 한 일과 같은 3분) */
        const val UNDO_MS = 3 * 60_000L
        /** 성에 제거를 켜는 중으로 보는 가장 긴 시간(공조를 켜고 다시 보내기 2분 + 여유) */
        const val FOG_PENDING_MS = 3 * 60_000L
        const val FOG_OFF_HAND = "앞유리 성에 제거는 공조 단추로 꺼 주세요"

        /**
         * "되돌려"에 말로 켠 성에 제거를 되돌린다(ride/Care.undo가 알림 카드들을 본 뒤 부른다). 되돌렸으면 true.
         * 운행 서비스가 Care를 Coach보다 먼저 만들어 Care가 Coach를 받지 못해, 지금 Coach가 여기에 붙는다(release에서 뗀다)
         */
        var undoDefrost: (() -> Boolean)? = null
    }
}
