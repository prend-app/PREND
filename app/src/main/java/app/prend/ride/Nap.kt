package app.prend.ride

import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.FanDirectionType
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.prend.Bridge
import app.prend.Prend
import app.prend.R
import app.prend.car.CarLink
import app.prend.car.CarState
import app.prend.car.Navi
import app.prend.sound.Chime
import app.prend.ui.NapPhase
import app.prend.ui.Scene
import app.prend.ui.Stage
import app.prend.voice.Speaker
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/**
 * 쪽잠과 잘래요(사용자 결정 10/27): 재우고, 깨우고, 깬 뒤 3분을 지킨다. 차는 사람이 자는지 모르고, 폰 알람은 차의 빛·바람·시트를
 * 움직이지 못한다. 그 사이를 PREND가 맡는다.
 *
 * 운전자 쪽잠(세워 둔 동안, 10·15·20분)
 * - 잠들 때: 운전석 열선이 2단 이상이면 1단으로 낮추고 자는 동안 올리지 않는다(그랜저 설명서: 과로한 사람은 저온화상 위험군, R5-C).
 *   무드등을 아주 어둡게 하고, 흐르던 음악은 잠깐 멈춘다(끝나면 그 앱이 다시 튼다)
 * - 깨우기(끝나기 2분 전부터, 기억에 따라 4분 전까지): ① 무드등이 새벽처럼 천천히 밝아진다 ② 열선을 끄고 통풍, 얼굴 쪽 바람
 *   ③ 끝나기 1분 전부터 잔잔한 곡이 점점 커진다 ④ 끝에 "잘 쉬셨어요. 천천히 일어나세요." 대답이 없으면 30초마다 두 번 더 부른다.
 *   근거가 가장 단단한 것은 빛이다(새벽 시뮬레이션·밝은 빛, R2-C). 시원한 바람은 세안처럼 효과가 짧아 소리보다 먼저 몸에 닿는 신호로만 쓴다
 * - 깬 줄 아는 신호: 대답(말·[일어났어요]), 브레이크, 기어, 운전석 문. 그러면 소리를 거두고 '깬 뒤 3분'으로
 * - 깬 뒤 3분: 깬 직후 몇 분이 가장 둔하다(Wertz 2006, R3-D). 그 사이 D로 바꾸면 "조금만 더 있다 가요" 한 번. 막지는 않는다.
 *   3분이 지나면 "이제 가셔도 좋아요. 빛과 바람은 처음대로 돌려 둘게요."와 함께 무드등·바람·통풍을 처음대로 돌린다(열선은 다시 켜지 않는다).
 *   PREND가 켠 시트 냉방은 끄고, 꺼 둔 열선은 "열선은 꺼 뒀어요" 한 줄로 알린다(검토 11/07)
 *   마지막 인사("조심히 가세요")는 대사 원칙에 따라 뺐다(감사 F110, 새 줄은 tools/voice/ride4.json)
 * - 시동을 끄면 PREND도 멈춰 깨울 수 없다(Pleos 서스펜드, R2-D). 화면에 적어 둔다
 *
 * 동승자 잘래요(달리는 중): "잘래"·[잘래요], 또는 동승석 등받이를 젖힌 채 30초(묻고 시작)
 * - 쉬는 동안: 음악을 6dB 낮추고(소리 엔진), PREND가 먼저 말을 걸지 않는다(졸음쉼터 권하기는 운전자의 일이라 그대로).
 *   그 자리 열선이 2단 이상이면 1단으로, 올리지 않는다. 무드등을 어둡게
 * - 깨우기: 내비에 목적지가 있으면 도착 5분 전에 무드등 → 그 자리 통풍 → "5분 남았어요", 없으면 P에 넣을 때 "다 왔어요"
 * - 등받이를 다시 세우면 조용히 끝낸다
 * - 쉬는 자리: 등받이로 시작했으면 젖힌 그 자리, 말·단추로 켰으면 운전석 말고 앉은 자리가 하나일 때 그 자리.
 *   둘 넘게 앉아 있으면 시트(열선·통풍)와 독서등은 건드리지 않고 무드등·음악만 바꾼다(검토 11/07 멋대로 판단)
 * - 쉬는 사람이 자기 독서등을 켜면 깬 것으로 보고 잘래요를 조용히 끝낸다(11/01 추천 4. 불을 끄는 것은 Pleos가 P에서만 받아 뺐다)
 *
 * 등받이: 전동 시트가 위치를 주는 차만(이 에뮬레이터 콘솔에는 없다). 탈 때보다 움직일 수 있는 범위의 3분의 1 넘게 젖히면 쉬려는 것으로 본다.
 * 위치 값은 작을수록 젖힌 것으로 본다(AAOS SEAT_BACKREST_ANGLE_1_POS: 가장 작은 값이 끝까지 젖힘)
 */
class Nap(
    private val context: Context,
    private val speaker: Speaker,
    private val memory: Memory,
    private val navi: Navi?,
    /** 등받이로 알아챘을 때 물어볼 곳(Care): 운전자 쪽잠, 동승자 잘래요 */
    private val askNap: () -> Unit,
    private val askSleep: () -> Unit,
) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val hub get() = CarLink.hubOrNull

    /** 쪽잠·잘래요 전의 차 상태(끝나면 돌린다). 모르면 null. 잘래요는 무드등만 바꾸므로 바람·운전석 통풍은 null로 둔다 */
    private data class Saved(val brightness: Int?, val color: Int?, val fan: Int?, val vent: Int?, val dir: FanDirectionType? = null)
    private var napSaved: Saved? = null
    private var sleepSaved: Saved? = null
    /** 쪽잠 전에 PREND 곡이 흐르고 있었다(끝나면 다시 튼다) */
    private var brainWasPlaying = false
    /** 깨울 때 공조가 꺼져 있어 PREND가 켰다(처음대로 돌릴 때 끈다) */
    private var poweredOn = false
    /** 깨우기 회차. 끝내거나 다시 재우면 늘려, 늦게 돌아온 바람 다시 시도(CarHub.fanOn)가 poweredOn을 다시 세우지 않게 한다 */
    private var wakeGen = 0
    /**
     * 깨울 때 PREND가 시트 냉방(통풍 없이 시트 온도를 음수로 하는 차)을 켠 때(elapsedRealtime, 0이면 아님). 깬 뒤 3분이 끝날 때 꼭 끈다
     * (검토 11/07 멋대로 판단: 통풍은 처음대로 돌렸지만 시트 냉방은 적어 두지 않아 켜진 채 남았다)
     */
    private var coolAt = 0L
    /** 깨울 때 PREND가 열선을 껐다. 저온화상 때문에 다시 켜지는 않고, 끝날 때 꺼 둔 것을 한 줄로 알린다(출발한 뒤 등이 차가워져서야 알지 않게) */
    private var heatOff = false

    // 쪽잠
    private var endAt = 0L
    private var wakeAt = 0L
    /** 깨우기 단계: -1 아직, 0 빛, 1 바람, 2 곡, 3 목소리, 4 다시 부름, 5 한 번 더 */
    private var stage = -1
    private var lastLight = 0L
    private var afterUntil = 0L
    private var waited = false
    private var focus: AudioFocusRequest? = null
    private var melody: MediaPlayer? = null
    private var melodyVol = 0f
    /** 포커스가 정한 쪽잠 곡의 몫: 1 그대로, MELODY_DUCK 낮춤(길 안내), 0 잠깐 비킴(통화·한마디·대답 듣기) */
    private var melodyRoom = 1f

    // 잘래요
    private var sleepSeat: SeatValue? = null
    private var sleepByEta = false
    private var sleepWaking = false
    /** 등받이를 젖혀 시작했다(다시 세우면 끝낸다) */
    private var sleepByLean = false
    /**
     * 쉬는 자리를 안다(등받이로 시작했거나, 운전석 말고 앉은 자리가 하나뿐). 모르면 그 자리 열선·통풍·독서등은 건드리지 않고 무드등·음악만 바꾼다
     * (검토 11/07 멋대로 판단: 앉은 차례로 옆자리부터 골라, 깨어 있는 사람의 열선을 내리고 통풍을 켰다)
     */
    private var sleepSeatSure = true
    /** 등받이를 젖혀 잘래요를 물은 자리. 그 물음에 대답이 오면 이 자리가 쉬는 자리다 */
    private var leanAskSeat: String? = null
    /**
     * 잘래요 전 그 자리 통풍 단계(모르면 null)와 깨울 때 PREND가 보낸 단계(보내지 않았으면 null). 깨울 때 원래보다 낮추지 않고, 끝날 때 원래 단계로 돌린다
     * (11/08 오류 점검 C7: 3단이던 통풍을 깨울 때 1단으로 내리고 끝날 때 0으로 꺼 버렸다)
     */
    private var sleepVentWas: Int? = null
    private var sleepVentSent: Int? = null

    /**
     * 쪽잠·잘래요를 프로세스 밖에 적어 둔다(11/08 오류 점검 C3): Pleos는 창을 닫으면 프로세스를 끄고 서비스만 다시 깨운다(ride/RideKeep).
     * 그때 깨우기가 사라지지 않게 같은 운행이면 restoreNap()으로 잇는다. 적는 것은 시각·밝기·색·세기·자리 이름뿐이다
     */
    private val napKeep = context.getSharedPreferences("nap_keep", Context.MODE_PRIVATE)
    /** 지난번에 적은 글(같으면 다시 쓰지 않는다). null이면 이 프로세스에서 아직 적지 않았다(그때는 지우지도 않는다) */
    private var napKept: String? = null

    // 등받이: 이번 운행에서 가장 세운 위치, 젖힌 채 시작한 때
    private val upright = mutableMapOf<String, Int>()
    private val leanSince = mutableMapOf<String, Long>()
    private var leanAskedAt = mutableMapOf<String, Long>()

    // 사람이 바꾼 무드등·바람은 덮지 않기(감사 F109)
    /** PREND가 보낸 무드등(밝기·색)·바람 세기·운전석 통풍. 읽은 값이 이 가운데 있으면 PREND 것, 없으면 사람이 바꾼 것 */
    private val lightSent = Sent<Pair<Int, Int>>()
    private val fanSent = Sent<Int>()
    private val ventSent = Sent<Int>()
    /** 이번 운행에 사람이 무드등·바람을 직접 바꿨다: 그 운행이 끝날 때까지 쪽잠·잘래요는 그것을 건드리지 않는다 */
    private var lightHands = false
    private var fanHands = false

    /**
     * PREND가 보낸 값들. 마지막 값과, 다음 값으로 바뀐 지 40초가 안 된 값(처음 보내기 직전의 원래 값 포함)은 PREND 것으로 본다:
     * 에뮬레이터는 앱이 바꾼 값을 25초쯤 늦게 반영해 그동안 옛 값이 읽힌다(CarLink.HANDS_GRACE_MS). 그 밖의 값이면 사람이 바꾼 것.
     * 보낸 때가 아니라 바뀐 때로 센다: 쪽잠 내내 둔 어두운 빛은 깨우기 첫 걸음 뒤 40초 동안 아직 읽힐 수 있다
     */
    private class Sent<T> {
        private val list = ArrayDeque<Pair<T, Long>>()
        val empty get() = list.isEmpty()
        fun add(v: T, was: T? = null) {
            val t = SystemClock.elapsedRealtime()
            if (list.isEmpty() && was != null) list.addLast(was to t)
            list.addLast(v to t)
        }
        fun mine(v: T?): Boolean {
            if (v == null || list.isEmpty()) return true
            val t = SystemClock.elapsedRealtime()
            // 맨 앞 값은 그다음 값을 보낸 때(바뀐 때)부터 40초가 지나야 버린다
            while (list.size > 1 && t - list[1].second > CarLink.HANDS_GRACE_MS) list.removeFirst()
            return list.any { it.first == v }
        }
        fun clear() = list.clear()
    }

    init {
        Stage.onNap = { what, min ->
            when (what) {
                "start" -> start(min)
                "stop" -> stop()
                "woke" -> woke("[일어났어요]")
                "more" -> more(min)
                "sleep_on" -> sleepOn(fromAsk = false)
                // 대리 운행을 켤 때 함께(대리 한마디가 대신 알리니 잘래요 한마디·알림은 내지 않는다)
                "sleep_on_quiet" -> sleepOn(fromAsk = false, quiet = true)
                "sleep_off" -> sleepOff("사람이 끔")
            }
        }
    }

    val napping get() = Stage.ui.value.napPhase != null
    val sleeping get() = sleepSeat != null
    /** 잘래요로 쉬는 자리 이름(ROW_1_RIGHT 등). 쉬지 않거나 어느 자리인지 모르면 null */
    val sleepSeatName get() = sleepSeat?.takeIf { sleepSeatSure }?.name

    /**
     * 새 운행. 쪽잠·잘래요 중에 시동을 껐다가(서스펜드) 다음 시동에 깨어났으면 조용히 끝낸다:
     * 지난 끝 시각이 그대로 남으면 깨우기(바람·곡·부름)가 몇 초 안에 한꺼번에 터진다. 목소리·곡 없이 처음대로 돌리기만 한다
     */
    fun newRide() {
        upright.clear(); leanSince.clear(); leanAskedAt.clear(); leanAskSeat = null
        if (napping) { Prend.log("· 쪽잠: 새 운행이라 끝냄"); finish(said = false, emergency = true) }
        if (sleeping) sleepOff("새 운행")
        // 사람이 바꾼 무드등·바람을 건드리지 않던 것은 그 운행만
        lightHands = false; fanHands = false
        lightSent.clear(); fanSent.clear(); ventSent.clear()
        // 지난 운행에 적어 둔 쪽잠·잘래요는 버린다(11/08 오류 점검 C3)
        napKept = null; napKeep.edit().remove("state").apply()
    }

    /**
     * 차 화면이 잠들었다 30분 안에 깨어 같은 운행으로 잇는다(검토 11/07 멋대로 판단, RideService). 잘래요는 그대로 재운다(휴게소에 잠깐 선 것).
     * 운전자 쪽잠만 조용히 끝낸다: 시동을 다시 켰으면 깬 것이고, 지난 끝 시각이 그대로 남으면 깨우기(바람·곡·부름)가 한꺼번에 터진다
     */
    fun resumed() {
        if (napping) { Prend.log("· 쪽잠: 차가 잠들었다 깨어 끝냄"); finish(said = false, emergency = true) }
    }

    /**
     * 같은 운행 중 프로세스가 다시 떴다(창을 닫음, RideService): 적어 둔 쪽잠·잘래요를 잇는다(11/08 오류 점검 C3: 전에는 15분이 지나도 깨우지 않고
     * 무드등 밤빛·열선 1단이 그대로 남았다).
     * - 쪽잠은 끝 시각을 그대로 써서 깨운다. 이미 지났으면 1분 뒤로 당겨 곧바로 깨우기를 시작한다(빛·바람·곡·부름이 한꺼번에 터지지 않게)
     * - 깬 뒤 3분이었거나, 차 화면이 잠들었다 깨어 이은 것이면(afterSleep, resumed와 같다) 쪽잠은 처음대로 돌리기만 하고 끝낸다
     * - 잘래요는 그대로 재운다(깨우던 중이었으면 3분 뒤 끝낸다)
     */
    fun restoreNap(afterSleep: Boolean) {
        // 이은 뒤로는 1초 틱이 적고 지운다(이을 것이 없거나 여기서 끝냈으면 다음 틱에 지운다)
        napKept = ""
        val j = runCatching { JSONObject(napKeep.getString("state", null) ?: return) }.getOrNull() ?: return
        // 이 Nap은 막 만들어져 끝 시각이 없다. 같은 프로세스에서 운행 서비스만 다시 떴으면 화면의 쪽잠 단계(napping)는 남아 있어도 잇는다
        // (11/08 검토: !napping이면 끝 시각 0으로 곧바로 깨우기·부름이 터지고 처음대로 돌릴 값도 없었다)
        if (j.has("endAt") && endAt == 0L) {
            napSaved = j.optJSONObject("saved")?.let { savedOf(it) }
            brainWasPlaying = j.optBoolean("playing")
            heatOff = j.optBoolean("heatOff")
            poweredOn = j.optBoolean("powered")
            // 깨울 때 켠 시트 냉방은 켠 때를 모르니 오래전에 켠 것으로 둔다(끝날 때 그대로 -1이면 끈다)
            coolAt = if (j.optBoolean("cool")) 1L else 0L
            // 깨울 때 바꾼 바람·운전석 통풍도 PREND가 보낸 값으로 다시 적는다(11/08 검토: 새 프로세스는 보낸 값 목록이 비어
            // 깬 뒤 3분에 이어 끝내면 바람 3단·얼굴 쪽과 통풍 1단이 그대로 남았다)
            if (j.optBoolean("fan")) { fanSent.add(3, was = napSaved?.fan); dirWas = napSaved?.dir }
            if (j.optBoolean("vent")) ventSent.add(1, was = napSaved?.vent ?: 0)
            val min = j.optInt("min", 15)
            val now = System.currentTimeMillis()
            endAt = maxOf(j.optLong("endAt"), now + 60_000L)
            stage = -1; waited = false
            Stage.ui.update { it.copy(scene = Scene.NAP, napMinutes = min, napEndAt = endAt, napPhase = NapPhase.SLEEP, napAfterUntil = 0L) }
            if (afterSleep || j.optString("phase") == NapPhase.AFTER.name) {
                Prend.log("· 쪽잠: 프로세스가 다시 떠 처음대로 돌리고 끝냄(${if (afterSleep) "차가 잠들었다 깸" else "깬 뒤 3분이었음"})")
                finish(said = false, emergency = afterSleep)
            } else {
                // 다른 음악을 멈춘 포커스(깨우기 곡의 포커스)는 프로세스와 함께 풀렸다: 다시 잡는다
                pauseOthers()
                relight(napSaved)
                Prend.log("◆ 쪽잠 이어 감(프로세스가 다시 뜸, 끝까지 ${(endAt - now) / 1000}초)")
            }
        }
        val seatName = j.optString("seat")
        val seat = if (seatName.isEmpty() || sleeping) null else runCatching { SeatValue.valueOf(seatName) }.getOrNull()
        if (seat != null) {
            sleepSeat = seat
            sleepSeatSure = j.optBoolean("sure", true)
            sleepByEta = j.optBoolean("byEta")
            sleepByLean = j.optBoolean("byLean")
            sleepWaking = j.optBoolean("waking")
            sleepVentWas = if (j.has("ventWas")) j.optInt("ventWas") else null
            sleepVentSent = if (j.has("ventSent")) j.optInt("ventSent") else null
            sleepSaved = j.optJSONObject("sleepSaved")?.let { savedOf(it) }
            val label = when { !sleepSeatSure -> "동승자"; seat == SeatValue.ROW_1_RIGHT -> "옆자리"; else -> "뒷자리" }
            Stage.ui.update { it.copy(sleeper = label, sleepWake = if (sleepWaking) "곧 도착해요" else if (sleepByEta) "도착 5분 전에 깨워요" else "도착해서 세우면 깨워요") }
            if (sleepWaking) main.postDelayed({ if (sleeping && sleepWaking) sleepOff("깨운 뒤 3분") }, 3 * 60_000L)
            else { quietMusic(true); relight(sleepSaved); if (sleepSeatSure) dimLamps(seat, label) }
            Prend.log("◆ 잘래요 이어 감(프로세스가 다시 뜸${if (sleepWaking) ", 깨우던 중" else ""})")
        }
    }

    /**
     * 이어 재울 때: 앞 프로세스가 보낸 밤빛(과 지금 켜진 빛)을 PREND 것으로 다시 적는다(11/08 검토: 보낸 값 목록이 비어, 차가 늦게 알리는
     * 옛 밤빛을 사람이 바꾼 빛으로 보고 깨우기 빛과 처음대로 돌리기를 그 운행 내내 멈췄다). s는 쪽잠·잘래요 전의 무드등, 모르면 빛을 바꾸지 않았으니 적지 않는다
     */
    private fun relight(s: Saved?) {
        val saved = s ?: return
        val b = saved.brightness ?: return
        val c = saved.color ?: return
        lightSent.add(DIM to NIGHT_COLOR, was = b to c)
        // 지금 빛이 밤빛과 다르면(깨우던 중에 다시 뜸) 그 빛도 PREND 것으로. 같으면 더하지 않는다(밤빛이 먼저 버려지지 않게)
        hub?.readAmbient { nb, nc -> if (nb != null && nc != null && (nb to nc) != (DIM to NIGHT_COLOR)) lightSent.add(nb to nc) }
    }

    /** 1초마다: 지금 쪽잠·잘래요를 적어 둔다(바뀌었을 때만). 둘 다 끝났으면 지운다(11/08 오류 점검 C3) */
    private fun keepNap() {
        val ui = Stage.ui.value
        // 시연의 화면만 쪽잠은 적지 않는다
        val phase = ui.napPhase?.takeIf { !ui.demo }
        val seat = sleepSeat
        if (phase == null && seat == null) {
            if (napKept != null) { napKept = null; napKeep.edit().remove("state").apply() }
            return
        }
        val j = JSONObject()
        if (phase != null) {
            j.put("phase", phase.name).put("endAt", endAt).put("min", ui.napMinutes).put("playing", brainWasPlaying)
                .put("heatOff", heatOff).put("powered", poweredOn).put("cool", coolAt > 0L).put("fan", !fanSent.empty).put("vent", !ventSent.empty)
            napSaved?.let { j.put("saved", savedJson(it)) }
        }
        if (seat != null) {
            j.put("seat", seat.name).put("sure", sleepSeatSure).put("byEta", sleepByEta).put("byLean", sleepByLean).put("waking", sleepWaking)
            sleepVentWas?.let { j.put("ventWas", it) }
            sleepVentSent?.let { j.put("ventSent", it) }
            sleepSaved?.let { j.put("sleepSaved", savedJson(it)) }
        }
        val s = j.toString()
        if (s == napKept) return
        napKept = s
        napKeep.edit().putString("state", s).apply()
    }

    private fun savedJson(s: Saved): JSONObject = JSONObject().also { o ->
        s.brightness?.let { o.put("b", it) }
        s.color?.let { o.put("c", it) }
        s.fan?.let { o.put("f", it) }
        s.vent?.let { o.put("v", it) }
        s.dir?.let { o.put("d", it.name) }
    }

    private fun savedOf(o: JSONObject): Saved = Saved(
        if (o.has("b")) o.optInt("b") else null,
        if (o.has("c")) o.optInt("c") else null,
        if (o.has("f")) o.optInt("f") else null,
        if (o.has("v")) o.optInt("v") else null,
        o.optString("d").takeIf { it.isNotEmpty() }?.let { n -> runCatching { FanDirectionType.valueOf(n) }.getOrNull() },
    )

    /** 무드등을 바꾼다. 이번 운행에 사람이 무드등을 직접 바꿨으면 건드리지 않는다. was는 처음 바꿀 때의 원래 값 */
    private fun light(b: Int, c: Int, was: Pair<Int, Int>? = null) {
        if (lightHands) return
        val h = hub ?: return
        lightSent.add(b to c, was)
        h.setAmbient(b, c) { }
    }

    /** 쪽잠 전의 무드등(밝기·색)을 아나. 모르면 쪽잠은 빛을 바꾸지 않는다(끝나고 되돌릴 수 없다, 11/08 오류 점검 C8) */
    private fun napLightKnown() = napSaved?.let { it.brightness != null && it.color != null } == true

    /** 사람이 무드등을 바꿨는지 읽어 보고, 아니면 then. 바꿨으면 이번 운행 동안 무드등은 건드리지 않는다 */
    private fun ifLightMine(why: String, then: () -> Unit) {
        if (lightHands) return
        val h = hub ?: return
        if (lightSent.empty) return then()
        h.readAmbient { b, c ->
            if (lightHands) return@readAmbient
            if (b != null && c != null && !lightSent.mine(b to c)) { lightHands = true; Prend.log("◆ $why: 사람이 무드등을 바꿔 이번 운행엔 건드리지 않음"); return@readAmbient }
            then()
        }
    }

    /** 사람이 바람(세기·방향)을 바꿨는지 읽어 보고, 아니면 then. PREND가 바람 세기를 바꾼 적이 없으면 읽지 않고 then(공조 전원만 볼 일) */
    private fun ifFanMine(why: String, then: () -> Unit) {
        if (fanHands) return
        if (fanSent.empty) return then()
        val h = hub ?: return
        h.readFan { f ->
            h.readFanDirection { d ->
                if (fanHands) return@readFanDirection
                // 방향은 얼굴 쪽(PREND가 돌린 것)이나 처음 방향(차가 받아들이지 않았거나 늦게 반영)이면 사람이 바꾼 것으로 보지 않는다
                val settled = fanSent.mine(f) && (d == null || d == FanDirectionType.FACE || d == dirWas || !fanSettled())
                // 사람이 바람을 맡았으니 PREND가 켠 공조도 끄지 않는다
                if (!settled) { fanHands = true; poweredOn = false; Prend.log("◆ $why: 사람이 바람을 바꿔 이번 운행엔 건드리지 않음"); return@readFanDirection }
                then()
            }
        }
    }

    /** 얼굴 쪽으로 돌린 지 40초가 지났나(그 전에는 방향이 옛 값으로 읽혀도 사람이 바꾼 것으로 보지 않는다) */
    private fun fanSettled() = SystemClock.elapsedRealtime() - fanAt >= CarLink.HANDS_GRACE_MS
    private var fanAt = 0L
    /** 얼굴 쪽으로 돌리기 전의 바람 방향 */
    private var dirWas: FanDirectionType? = null

    // ── 운전자 쪽잠 ──

    private fun start(min: Int) {
        when (Stage.ui.value.napPhase) {
            // 자는 중에 길이(10·15·20분)를 바꾸면 끝 시각만 옮긴다. 끝냈다 다시 시작하면 막 되돌린 무드등이 아직 어둡게 읽혀
            // 그 값이 쪽잠 전의 값으로 남고, 멈춘 곡도 다시 틀 줄 모른다
            NapPhase.SLEEP -> {
                endAt = System.currentTimeMillis() + min * 60_000L
                memory.napChosen(min)
                Prend.log("◆ 쪽잠 ${min}분으로 바꿈")
                Stage.ui.update { it.copy(scene = Scene.NAP, napMinutes = min, napEndAt = endAt) }
                return
            }
            // 깨우는 중·깬 뒤 3분에 다시 "쪽잠"이면 다시 재운다(쪽잠 전의 상태는 처음 것을 그대로 쓴다)
            NapPhase.WAKE, NapPhase.AFTER -> {
                memory.napChosen(min)
                Stage.ui.update { it.copy(scene = Scene.NAP, napMinutes = min) }
                resleep(min)
                return
            }
            null -> Unit
        }
        // 잘래요 중이었으면 그 전의 무드등을 이어받는다(되돌린 값을 바로 읽으면 아직 어두운 빛이 읽힌다)
        val carried = if (sleeping) sleepOff("쪽잠으로", light = false) else null
        val now = System.currentTimeMillis()
        endAt = now + min * 60_000L
        stage = -1
        waited = false
        heatOff = false; coolAt = 0L
        memory.napChosen(min)
        Prend.log("◆ 쪽잠 ${min}분: 재우기(깨우기는 끝나기 ${memory.habits.napLead}초 전부터)")
        Stage.ui.update { it.copy(scene = Scene.NAP, napMinutes = min, napEndAt = endAt, napPhase = NapPhase.SLEEP, napAfterUntil = 0L) }
        read { b, c, fan ->
            napSaved = Saved(carried?.brightness ?: b, carried?.color ?: c, fan, hub?.state?.vent)
            hub?.readFanDirection { d -> napSaved = napSaved?.copy(dir = d) }
            val h = hub ?: return@read
            // 열선은 1단까지만(자는 동안 올리지 않는다)
            val heat = h.state.heat ?: 0
            if (heat > 1) h.setSeatHeat(1) { ok -> Prend.log("· 쪽잠: 열선 ${heat}단 → 1단 ${if (ok) "" else "실패"}") }
            // 원래 무드등을 모르면(읽기 실패·1초 넘게 늦음) 빛은 바꾸지 않는다: 끝나고 되돌릴 수 없어 밤길에 낮빛이 남았다(11/08 오류 점검 C8)
            if (napLightKnown()) light(DIM, NIGHT_COLOR, was = if (b != null && c != null) b to c else null)
            else Prend.log("· 쪽잠: 원래 무드등을 읽지 못해 빛은 바꾸지 않음(곡·바람·목소리로 깨움)")
        }
        pauseOthers()
        brainWasPlaying = Bridge.brain?.state?.value?.playing == true
        Bridge.brain?.pause()
        speaker.speak("voice/nap_start.wav", AudioAttributes.USAGE_ASSISTANT, "쪽잠 시작")
    }

    /** 1초마다 */
    fun tick(car: CarState) {
        // 지난 1초 사이 바뀐 쪽잠·잘래요를 적어 둔다(프로세스가 다시 떠도 깨우기를 잇게, 11/08 오류 점검 C3)
        keepNap()
        val now = System.currentTimeMillis()
        val ui = Stage.ui.value
        // 급할 때(사고·급병)는 재우기를 바로 거둔다: 실내를 처음 밝기로, 곡·목소리 없이. 잘래요 깨우기 말도 하지 않는다.
        // 사고 안내가 대답을 듣는 중일 수 있어 듣기는 건드리지 않는다
        if (ui.scene.isE) {
            if (napping) { Prend.log("· 쪽잠: 급할 때라 끝냄"); finish(said = false, emergency = true) }
            if (sleeping) sleepOff("급할 때")
            return
        }
        when (ui.napPhase) {
            NapPhase.SLEEP -> if (endAt - now <= memory.habits.napLead * 1000L) beginWake()
            NapPhase.WAKE -> wakeStep(now)
            NapPhase.AFTER -> if (now >= afterUntil) finish(said = true)
            null -> Unit
        }
        if (sleeping) sleepTick(car)
        watchLean(car)
    }

    private fun beginWake() {
        stage = 0
        wakeAt = System.currentTimeMillis()
        lastLight = 0L
        Prend.log("◆ 쪽잠 깨우기 시작: 빛")
        Stage.ui.update { it.copy(napPhase = NapPhase.WAKE) }
        // 바람 단계 전에 공조가 켜져 있게(꺼져 있으면 지금 켜 둔다. 켜진 것이 반영되기까지 시간이 걸린다).
        // 이번 운행에 사람이 바람을 직접 바꿨으면 바람은 쓰지 않아 공조도 켜지 않는다
        val g = ++wakeGen
        if (!fanHands) hub?.fanReady { powered ->
            // 그사이 쪽잠이 끝났으면 방금 켠 공조를 다시 끈다(처음대로 돌리기는 이미 지나갔다)
            if (g != wakeGen) { if (powered) hub?.setHvacPower(false) { }; return@fanReady }
            if (powered) { poweredOn = true; Prend.log("· 쪽잠: 바람을 위해 공조를 미리 켬") }
        }
    }

    private fun wakeStep(now: Long) {
        val left = (endAt - now) / 1000
        val lead = memory.habits.napLead
        // ① 빛: 깨우기의 앞 60% 동안 아주 어두운 데서 밝은 낮빛까지, 10초마다 한 걸음. 사람이 무드등을 바꿨으면 그 빛을 둔다(감사 F109)
        // 원래 무드등을 모르면 밝히지 않는다(끝나고 되돌릴 수 없다, 11/08 오류 점검 C8)
        if (now - lastLight >= 10_000 && stage <= 3 && !lightHands && napLightKnown()) {
            lastLight = now
            val f = ((now - wakeAt) / (lead * 600.0)).coerceIn(0.0, 1.0)
            val g = wakeGen
            ifLightMine("쪽잠 깨우기") { if (g == wakeGen && Stage.ui.value.napPhase == NapPhase.WAKE) light((DIM + (BRIGHT - DIM) * f).toInt(), if (f < .5) DAWN_COLOR else DAY_COLOR) }
        }
        // ② 바람: 열선을 끄고 통풍, 얼굴 쪽으로 바람
        if (stage < 1 && left <= lead * 2 / 3) {
            stage = 1
            Prend.log("◆ 쪽잠 깨우기: 바람${if (fanHands) "(사람이 바꾼 바람은 그대로, 통풍만)" else ""}")
            val h = hub
            if (h != null) {
                // 끈 열선과 켠 시트 냉방은 적어 둔다: 끝날 때 냉방은 끄고, 열선은 꺼 둔 것을 알린다(검토 11/07 멋대로 판단)
                if ((h.state.heat ?: 0) > 0) { h.setSeatHeat(0) { }; heatOff = true }
                if ((h.ventRange?.last ?: 0) > 0) { ventSent.add(1, was = h.state.vent ?: 0); h.setSeatVent(1) { } } else if ((h.heatRange?.first ?: 0) < 0) { coolAt = SystemClock.elapsedRealtime(); h.setSeatHeat(-1) { } }
                // 공조 켜짐이 늦게 반영되면 2분까지 다시 시도한다. 그사이 쪽잠을 끝냈거나 다시 재웠으면 그만두고 결과도 버린다
                val g = wakeGen
                if (!fanHands) h.fanOn(3, live = { g == wakeGen }) { ok, powered ->
                    // 그사이 쪽잠이 끝나 다시 시도를 그만뒀으면, 그 시도가 켠 공조를 끈다(처음대로 돌리기는 이미 지나갔다)
                    if (g != wakeGen) { if (powered) { hub?.setHvacPower(false) { }; Prend.log("· 쪽잠: 끝난 뒤라 바람을 위해 켠 공조를 끔") }; return@fanOn }
                    poweredOn = poweredOn || powered
                    // 바꾼 세기와 그때를 적는다(처음대로 돌릴 때 사람이 바꾼 바람인지 가린다)
                    if (ok) { fanSent.add(3, was = napSaved?.fan); fanAt = SystemClock.elapsedRealtime(); dirWas = napSaved?.dir }
                    if (!ok) Prend.log("· 쪽잠: 바람을 바꾸지 못함")
                }
            }
        }
        // ③ 곡: 끝나기 1분 전부터 아주 작게 시작해 점점 크게
        if (stage < 2 && left <= 60) { stage = 2; Prend.log("◆ 쪽잠 깨우기: 곡"); startMelody() }
        if (melody != null && stage <= 3) setMelody((0.05f + 0.55f * ((60 - left) / 60f)).coerceIn(0.05f, 0.6f))
        // ④ 목소리, 대답이 없으면 30초마다 두 번 더(차임과 함께, 곡은 조금 더 크게)
        when {
            stage < 3 && left <= 0 -> { stage = 3; call("voice/nap_wake.wav", "쪽잠 깨우기") }
            stage == 3 && left <= -30 -> { stage = 4; Chime.play(); setMelody(.8f); call("voice/nap_wake2.wav", "쪽잠 다시 부름") }
            stage == 4 && left <= -60 -> { stage = 5; Chime.play(); call("voice/nap_wake2.wav", "쪽잠 한 번 더") }
            // 그래도 대답이 없으면 곡을 거두고 빛만 남긴다(깬 뒤 3분은 사람이 움직이면 시작)
            stage == 5 && left <= -120 && melody != null -> { Prend.log("· 쪽잠: 대답 없이 2분, 곡을 거둠"); stopMelody() }
        }
    }

    /** 부르고 5초 듣는다. 무엇이든 말하면 깬 것으로 본다 */
    private fun call(asset: String, label: String) {
        Prend.log("◆ $label")
        setMelody((melodyVol * .4f).coerceAtLeast(.05f))
        val listen = { Stage.call(answer = { heard -> if (heard.isNotBlank()) woke("말") else setMelody(if (stage >= 4) .8f else .6f) }, windowMs = 5_000) }
        val ok = runCatching { speaker.speak(asset, AudioAttributes.USAGE_ASSISTANT, label) { listen() } }.getOrDefault(false)
        if (!ok) listen()
    }

    /** 깬 줄 아는 차 신호: 브레이크, 기어, 운전석 문 */
    fun onCar(old: CarState, now: CarState) {
        val phase = Stage.ui.value.napPhase
        if (phase == NapPhase.WAKE) {
            when {
                !old.brake && now.brake -> woke("브레이크")
                old.gear != now.gear -> woke("기어")
                (old.doors["ROW_1_LEFT"] ?: 0) == 0 && (now.doors["ROW_1_LEFT"] ?: 0) > 0 -> woke("운전석 문")
            }
        }
        if (phase == NapPhase.AFTER && old.gear != now.gear && now.driving && !waited) {
            waited = true
            Prend.log("◆ 깬 뒤 3분 안에 출발: 한마디")
            speaker.speak("voice/nap_wait.wav", AudioAttributes.USAGE_ASSISTANT, "깬 뒤 3분")
        }
        // 쪽잠 중(자는 동안)에 출발하면 쪽잠을 끝낸다
        if (phase == NapPhase.SLEEP && !now.parked) { Prend.log("· 쪽잠: 출발해서 끝냄"); finish(said = false) }
        if (sleeping) sleepCar(old, now)
    }

    private fun woke(why: String) {
        if (Stage.ui.value.napPhase != NapPhase.WAKE) return
        val at = stage.coerceAtLeast(0)
        Prend.log("◆ 쪽잠: 깸($why, ${Memory.STAGES[at.coerceAtMost(4)]} 단계)")
        speaker.stop()
        Stage.endListen()
        stopMelody()
        releaseFocus()
        afterUntil = System.currentTimeMillis() + AFTER_MS
        Stage.ui.update { it.copy(napPhase = NapPhase.AFTER, napAfterUntil = afterUntil) }
        memory.napWoke(at.coerceAtMost(4))?.let { l ->
            main.postDelayed({
                if (Stage.ui.value.scene.isE) return@postDelayed
                // 배운 것은 글로만(목소리로 한 문장 더 하지 않는다, 11/01 말 줄이기)
                Stage.toast(l.text, 4_000)
            }, 1_500)
        }
    }

    /** [5분만 더]: 다시 재운다(깨우기를 처음부터) */
    private fun more(min: Int) {
        if (Stage.ui.value.napPhase != NapPhase.WAKE) return
        resleep(min)
    }

    /** 깨우는 중·깬 뒤 3분에 다시 재운다(깨우기를 처음부터). 쪽잠 전의 상태(napSaved)와 곡을 다시 틀지는 처음 것을 그대로 쓴다 */
    private fun resleep(min: Int) {
        speaker.stop(); Stage.endListen(); stopMelody()
        wakeGen++
        endAt = System.currentTimeMillis() + min * 60_000L
        stage = -1
        waited = false
        Prend.log("◆ 쪽잠 ${min}분 더")
        // 깬 뒤 3분에는 다른 음악에 포커스를 돌려줬다: 다시 멈춘다
        if (focus == null) pauseOthers()
        Stage.ui.update { it.copy(napEndAt = endAt, napPhase = NapPhase.SLEEP, napAfterUntil = 0L) }
        // 깨울 때 켠 시트 냉방은 다시 잠드는 동안 두지 않는다
        coolOff("다시 재우기")
        // 사람이 그사이 바꾼 무드등·바람은 덮지 않는다(감사 F109). 원래 무드등을 모르면 바꾸지 않는다(11/08 오류 점검 C8)
        if (napLightKnown()) ifLightMine("다시 재우기") { light(DIM, NIGHT_COLOR) }
        ifFanMine("다시 재우기") {
            if (poweredOn) { hub?.setHvacPower(false) { }; poweredOn = false }
            else if (!fanSent.empty) napSaved?.fan?.let { f -> fanSent.add(f); hub?.setFan(f, face = false) { } }
        }
    }

    /** [그만 쉬기]: 깨우기 전이면 바로 끝내고, 깨우는 중이면 깬 것으로, 깬 뒤 3분이면 바로 끝낸다 */
    private fun stop() {
        when (Stage.ui.value.napPhase) {
            NapPhase.WAKE -> woke("[그만 쉬기]")
            null -> Unit
            else -> finish(said = false)
        }
    }

    /**
     * 쪽잠을 끝내고 차를 처음대로 돌린다. 쪽잠 전의 상태를 돌려준다(잘래요로 이어받으려고).
     * emergency: 급할 때(사고 안내의 듣기를 건드리지 않고, PREND 곡도 다시 틀지 않는다). light=false: 무드등은 그대로 둔다(잘래요가 이어 씀)
     */
    private fun finish(said: Boolean, emergency: Boolean = false, light: Boolean = true): Saved? {
        Prend.log("◆ 쪽잠 끝${if (said) "(깬 뒤 3분 지남)" else ""}")
        wakeGen++
        speaker.stop(); stopMelody(); releaseFocus()
        if (!emergency) Stage.endListen()
        // 멈춰 둔 PREND 곡은 "이제 가셔도 좋아요"가 끝난 뒤에 다시 튼다(곡이 먼저 포커스를 잡아 그 말을 끊지 않게)
        val resume = brainWasPlaying && !emergency
        brainWasPlaying = false
        var resumed = false
        val play = { if (resume && !resumed) { resumed = true; Bridge.brain?.play() } }
        val talked = said && runCatching { speaker.speak("voice/nap_go.wav", AudioAttributes.USAGE_ASSISTANT, "쪽잠 끝", onCut = play) { play() } }.getOrDefault(false)
        if (!talked) play()
        val s = napSaved
        napSaved = null
        restore(s, light)
        // PREND가 켠 시트 냉방은 꼭 끈다. 열선은 다시 켜지 않고(저온화상) 꺼 둔 것만 한 줄로 알린다(검토 11/07 멋대로 판단).
        // 급할 때와 잘래요로 이어 쓸 때(그 알림이 먼저 뜬다)는 알리지 않고, 사람이 그사이 다시 켰으면 알리지 않는다
        coolOff("쪽잠 끝")
        if (heatOff) {
            heatOff = false
            if (light && !Stage.ui.value.scene.isE && (hub?.state?.heat ?: 0) <= 0) Stage.toast("열선은 꺼 뒀어요 · 필요하면 다시 켜 주세요", 4_000)
        }
        Stage.ui.update { it.copy(scene = if (it.scene == Scene.NAP) Scene.HOME else it.scene, napEndAt = 0L, napPhase = null, napAfterUntil = 0L) }
        return s
    }

    /** PREND가 깨울 때 켠 시트 냉방을 끈다. 켠 지 40초 안이면 차가 아직 옛 값을 줄 수 있어 그냥 끄고, 그 뒤에 사람이 다른 세기로 바꿨으면 그대로 둔다 */
    private fun coolOff(why: String) {
        if (coolAt == 0L) return
        val recent = SystemClock.elapsedRealtime() - coolAt < CarLink.HANDS_GRACE_MS
        coolAt = 0L
        val h = hub ?: return
        val cur = h.state.heat
        when {
            recent || cur == null || cur == -1 -> h.setSeatHeat(0) { ok -> Prend.log("· 쪽잠: PREND가 켠 시트 냉방 끔($why)${if (ok) "" else " 실패"}") }
            cur == 0 -> Unit
            else -> Prend.log("· 쪽잠: 사람이 시트를 바꿔 냉방을 그대로 둠($why)")
        }
    }

    // ── 동승자 잘래요 ──

    private fun sleepOn(fromAsk: Boolean, quiet: Boolean = false) {
        if (sleeping) return
        // 세워 둔 차는 운전자의 쪽잠으로. 대리 운행이면 세워 둬도 주인 자리(조수석이나 뒷자리)의 잘래요(별첨 2 §5-9, 감사 F52 ⑤)
        if (Stage.ui.value.parked && !Stage.ui.value.daeri) { Stage.napStart(memory.habits.napMin ?: 15); return }
        val car = hub?.state ?: CarState()
        // 쉬는 자리(검토 11/07 멋대로 판단): 등받이로 시작했으면 젖힌 그 자리. 말·단추로 켰으면 운전석 말고 앉은 자리가 하나일 때 그 자리이고,
        // 둘 넘게 앉아 있으면 누가 쉬는지 몰라 시트는 건드리지 않는다(앉은 차례로 고르면 깨어 있는 사람의 열선·통풍을 바꿨다).
        // 아무도 앉지 않은 것으로 읽히면(자리 값을 모르는 차) 전처럼 옆자리
        val leaned = if (fromAsk) leanAskSeat?.let { n -> runCatching { SeatValue.valueOf(n) }.getOrNull() } else null
        val others = car.seats.filter { (k, v) -> v && k != "ROW_1_LEFT" }.keys.mapNotNull { n -> runCatching { SeatValue.valueOf(n) }.getOrNull() }
        val seat = leaned ?: others.singleOrNull() ?: SeatValue.ROW_1_RIGHT
        sleepSeatSure = leaned != null || others.size <= 1
        sleepSeat = seat
        sleepWaking = false
        sleepByLean = fromAsk
        val eta = navi?.etaSec()
        sleepByEta = eta != null && eta > WAKE_BEFORE_S
        val label = when { !sleepSeatSure -> "동승자"; seat == SeatValue.ROW_1_RIGHT -> "옆자리"; else -> "뒷자리" }
        Prend.log("◆ 잘래요: $label${if (leaned != null) "(젖힌 자리)" else if (!sleepSeatSure) "(${others.size}자리에 사람, 시트는 그대로)" else ""}(${if (sleepByEta) "도착 5분 전에 깨움, 지금 도착까지 ${eta!! / 60}분" else "세우면 깨움"})")
        Stage.ui.update { it.copy(sleeper = label, sleepWake = if (sleepByEta) "도착 5분 전에 깨워요" else "도착해서 세우면 깨워요") }
        // 켤 때 한 번, 부탁이 아니라 상태로(별첨 3 §4: PREND 화면·토스트·곡 정보 부제의 '쉬는 중'. 곡 부제는 PlaybackService가 단다)
        if (!quiet) Stage.toast(if (sleepSeatSure) "$label 손님 쉬는 중" else "동승자 쉬는 중", 3_000)
        quietMusic(true)
        // 운전자가 쪽잠에서 깬 뒤 3분 안에 출발했으면 쪽잠을 마치고(바람·통풍은 처음대로) 쪽잠 전의 무드등을 이어받는다
        val carried = if (napping) finish(said = false, light = false) else null
        val sure = sleepSeatSure
        sleepVentWas = null; sleepVentSent = null
        read { b, c, _ ->
            val saved = Saved(carried?.brightness ?: b, carried?.color ?: c, null, null)
            sleepSaved = saved
            // 원래 무드등을 모르면 빛은 바꾸지 않는다(끝나고 되돌릴 수 없어 밤빛이 남았다, 11/08 오류 점검 C8)
            if (saved.brightness != null && saved.color != null) light(DIM, NIGHT_COLOR, was = if (b != null && c != null) b to c else null)
            if (sure) hub?.readSeatAt(seat) { heat -> if ((heat ?: 0) > 1) hub?.setSeatAt(seat, heat = true, level = 1) { } }
            // 그 자리 통풍의 원래 단계를 적어 둔다(깨울 때 낮추지 않고 끝날 때 돌린다, 11/08 오류 점검 C7)
            if (sure) hub?.readSeatVentAt(seat) { v -> if (sleepSeat == seat) sleepVentWas = v }
        }
        if (sure) dimLamps(seat, label)
        if (!quiet) speaker.speak(if (sleepByEta) "voice/sleep_on_eta.wav" else "voice/sleep_on_p.wav", AudioAttributes.USAGE_ASSISTANT, "잘래요") {
            // 등받이를 젖혀 시작했으면 벨트를 한 번 짚는다(젖힌 채 달리면 벨트가 몸을 잘 잡지 못한다, R5-C)
            if (fromAsk) main.postDelayed({ speaker.speak("voice/sleep_belt.wav", AudioAttributes.USAGE_ASSISTANT, "벨트") }, 600)
        }
    }

    private fun sleepTick(car: CarState) {
        if (sleepWaking) return
        watchLamp()
        val eta = navi?.etaSec()
        if (sleepByEta && eta != null && eta <= WAKE_BEFORE_S) wakeSleeper(p = false)
    }

    private fun sleepCar(old: CarState, now: CarState) {
        // 세우면 깨운다. 다만 목적지가 아직 5분 넘게 남았으면(주유소·쉼터에 잠깐 섬) 그대로 재운다
        if (!sleepWaking && !old.parked && now.parked) {
            val eta = navi?.etaSec()
            if (!sleepByEta || eta == null || eta <= WAKE_BEFORE_S) wakeSleeper(p = true)
            else Prend.log("· 잘래요: 도착 전에 잠깐 세움(도착까지 ${eta / 60}분), 깨우지 않음")
        }
        // 깨운 뒤 문이 열리면(내림) 끝
        if (sleepWaking && now.doors.any { (k, v) -> v > 0 && (old.doors[k] ?: 0) == 0 }) sleepOff("문이 열림")
    }

    /** 도착 5분 전(또는 도착): 무드등을 밝히고 그 자리 통풍, 음악을 돌리고 말한다 */
    private fun wakeSleeper(p: Boolean) {
        val seat = sleepSeat ?: return
        sleepWaking = true
        Prend.log("◆ 잘래요 깨우기(${if (p) "세움" else "도착 5분 전"})")
        quietMusic(false)
        // 사람이 그사이 무드등을 바꿨으면 그 빛을 둔다(감사 F109). 원래 무드등을 모르면 밝히지 않는다(되돌릴 수 없다, 11/08 오류 점검 C8)
        val lightKnown = sleepSaved?.let { it.brightness != null && it.color != null } == true
        if (lightKnown) listOf(30, 60, 90).forEachIndexed { i, b -> main.postDelayed({ if (sleeping) ifLightMine("잘래요 깨우기") { if (sleeping) light(b, if (i < 1) DAWN_COLOR else DAY_COLOR) } }, i * 6_000L) }
        // 쉬는 자리를 모르면 통풍은 켜지 않는다(깨어 있는 사람 자리일 수 있다). 빛과 목소리로만 깨운다.
        // 원래 통풍보다 낮추지 않는다(11/08 오류 점검 C7: 3단이던 통풍을 1단으로 내렸다)
        if (sleepSeatSure) {
            val level = maxOf(1, sleepVentWas ?: 0)
            sleepVentSent = level
            hub?.setSeatAt(seat, heat = false, level = level) { ok -> if (!ok) Prend.log("· 잘래요: 그 자리 통풍은 바꾸지 못함") }
        }
        main.postDelayed({ if (sleeping) speaker.speak(if (p) "voice/wake_p.wav" else "voice/wake_5.wav", AudioAttributes.USAGE_ASSISTANT, "잘래요 깨우기") }, 12_000)
        Stage.ui.update { it.copy(sleepWake = if (p) "다 왔어요" else "곧 도착해요") }
        // 3분 뒤 처음대로(그사이 문이 열리면 그때)
        main.postDelayed({ if (sleeping && sleepWaking) sleepOff("깨운 뒤 3분") }, 3 * 60_000L)
    }

    /** 잘래요를 끝낸다. light=false면 무드등은 그대로 두고(쪽잠이 이어 씀) 잘래요 전의 상태를 돌려준다 */
    private fun sleepOff(why: String, light: Boolean = true): Saved? {
        val seat = sleepSeat ?: return null
        Prend.log("◆ 잘래요 끝($why)")
        sleepSeat = null
        quietMusic(false)
        // 깨울 때 바꾼 그 자리 통풍은 잘래요 전 단계로 돌린다(모르면 0). 그사이 사람이 다른 단계로 바꿨으면 그대로 둔다
        // (11/08 오류 점검 C7: 늘 0으로 꺼 원래 통풍이 사라졌다). 차가 바꾼 값을 늦게 알리는 동안의 옛 값(원래 단계)도 PREND 것으로 본다
        val ventSentNow = sleepVentSent
        if (sleepWaking && sleepSeatSure && ventSentNow != null) {
            val ventWas = sleepVentWas ?: 0
            if (ventSentNow != ventWas) hub?.let { h ->
                h.readSeatVentAt(seat) { v ->
                    if (v == null || v == ventSentNow || v == ventWas) h.setSeatAt(seat, heat = false, level = ventWas) { }
                    else Prend.log("· 잘래요: 사람이 그 자리 통풍을 바꿔 그대로 둠")
                }
            }
        }
        sleepVentWas = null; sleepVentSent = null
        sleepWaking = false
        sleepSeatSure = true
        readingSeenOff = false
        val s = sleepSaved
        sleepSaved = null
        restore(s, light)
        Stage.ui.update { it.copy(sleeper = null, sleepWake = null) }
        return s
    }

    // ── 잘래요의 독서등: 쉬던 사람이 자기 독서등을 켜면 깬 것으로 본다 ──
    // 독서등·실내등을 끄는 것은 뺐다(11/01 시험): Pleos 차량 서비스가 READING_LIGHTS_SWITCH·CABIN_LIGHTS_SWITCH 쓰기 요청을 받으면
    // 기어를 보고 P가 아니면 거절한다(IllegalArgumentException 50001). 잘래요는 달리는 동안의 일이라 일반 앱이 끌 수 없다. 읽기는 된다

    /** 그 자리 독서등이 꺼진 것을 읽었다(꺼진 것을 본 뒤에 켜져야 사람이 켠 것. 켜 둔 채 잠들었으면 한 번 꺼진 뒤부터 본다) */
    private var readingSeenOff = false
    private var lampReadAt = 0L

    /** 잘래요를 켤 때: 그 자리 독서등의 지금 모습을 읽어 둔다 */
    private fun dimLamps(seat: SeatValue, label: String) {
        val h = hub ?: return
        readingSeenOff = false; lampReadAt = SystemClock.elapsedRealtime()
        h.readReading(seat) { on ->
            if (!sleeping || sleepSeat != seat) return@readReading
            when (on) {
                true -> Prend.log("· 잘래요: $label 독서등이 켜져 있음(꺼진 뒤 다시 켜지면 깬 것으로 봄)")
                false -> readingSeenOff = true
                null -> Prend.log("· 잘래요: $label 독서등을 읽지 못함")
            }
        }
    }

    /** 잘래요 동안 5초마다 그 자리 독서등을 읽는다: 꺼진 것을 본 뒤에 켜지면 쉬던 사람이 깬 것으로 보고 조용히 끝낸다 */
    private fun watchLamp() {
        // 쉬는 자리를 모르면 어느 독서등이 그 사람 것인지 몰라 보지 않는다
        if (!sleepSeatSure) return
        val seat = sleepSeat ?: return
        val h = hub ?: return
        val t = SystemClock.elapsedRealtime()
        if (t - lampReadAt < LAMP_READ_MS) return
        lampReadAt = t
        h.readReading(seat) { on ->
            if (!sleeping || sleepSeat != seat || sleepWaking) return@readReading
            when {
                on == false -> readingSeenOff = true
                on == true && readingSeenOff -> {
                    Prend.log("◆ 잘래요: 그 자리 독서등이 다시 켜짐 → 깬 것으로 봄")
                    sleepOff("독서등을 켬")
                    Stage.toast("독서등이 켜져서 쉬는 중을 끝냈어요", 3_000)
                }
            }
        }
    }

    // ── 등받이 ──

    /** 젖힌 채 30초면 묻는다: 세워 둔 운전석은 쪽잠, 달리는 중 동승석은 잘래요. 다시 세우면 잘래요를 끝낸다 */
    private fun watchLean(car: CarState) {
        val h = hub ?: return
        val t = SystemClock.elapsedRealtime()
        car.recline.forEach { (seat, pos) ->
            val range = h.reclineRange[seat] ?: return@forEach
            val up = maxOf(upright[seat] ?: pos, pos)
            upright[seat] = up
            val leaning = up - pos > (range.last - range.first) / 3
            if (!leaning) {
                val was = leanSince.remove(seat) != null
                if (was && sleeping && sleepByLean && seat == sleepSeat?.name && !sleepWaking) sleepOff("등받이를 세움")
                return@forEach
            }
            val since = leanSince.getOrPut(seat) { t }
            if (t - since < LEAN_MS || t - (leanAskedAt[seat] ?: 0L) < 20 * 60_000) return@forEach
            when {
                seat == "ROW_1_LEFT" && car.parked && !napping -> { leanAskedAt[seat] = t; Prend.log("◆ 운전석 등받이 젖힘(P): 쪽잠 물음"); askNap() }
                // 젖힌 자리를 적어 둔다: 대답이 오면 그 자리가 쉬는 자리(앉은 차례로 고르지 않는다, 검토 11/07)
                seat != "ROW_1_LEFT" && car.driving && !sleeping -> { leanAskedAt[seat] = t; leanAskSeat = seat; Prend.log("◆ ${seat} 등받이 젖힘(달리는 중): 잘래요 물음"); askSleep() }
            }
        }
    }

    /** 등받이로 물은 것에 "네"(또는 대답 없음). 쉬는 자리는 젖힌 그 자리(leanAskSeat) */
    fun sleepFromAsk() = sleepOn(fromAsk = true)

    // ── 차 상태 저장·되돌리기, 소리 ──

    /** 무드등(밝기·색)과 바람 세기를 읽고 then을 한다(읽기를 기다리지 않고 1초 뒤에는 한다) */
    private fun read(then: (Int?, Int?, Int?) -> Unit) {
        val h = hub ?: return then(null, null, null)
        var b: Int? = null; var c: Int? = null; var fan: Int? = null
        var left = 2
        var ran = false
        val go = { if (!ran) { ran = true; then(b, c, fan) } }
        h.readAmbient { br, co -> b = br; c = co; if (--left == 0) go() }
        h.readFan { f -> fan = f; if (--left == 0) go() }
        main.postDelayed({ go() }, 1_000)
    }

    /**
     * 바꾼 것만 처음대로 돌린다(잘래요는 무드등만, 쪽잠은 무드등·바람·운전석 통풍). light=false면 무드등은 그대로.
     * 사람이 그사이 직접 바꾼 무드등·바람·통풍은 덮지 않는다(감사 F109: PREND가 보낸 값이 아니면 사람이 바꾼 것)
     */
    private fun restore(s: Saved?, light: Boolean) {
        val h = hub
        val powered = poweredOn
        poweredOn = false
        if (s == null || h == null) return
        Prend.log("· 처음대로: ${if (light) "무드등" else "무드등은 이어 씀"}${if (!fanSent.empty || powered) "·바람" else ""}${if (!ventSent.empty) "·통풍" else ""}")
        if (light && s.brightness != null && s.color != null) ifLightMine("처음대로") { h.setAmbient(s.brightness, s.color) { }; lightSent.clear() }
        ifFanMine("처음대로") {
            if (powered) h.setHvacPower(false) { }
            else if (!fanSent.empty) { s.fan?.let { h.setFan(it, face = false) { } }; s.dir?.let { d -> h.setFanDirection(d) } }
            fanSent.clear()
        }
        if (s.vent != null && !ventSent.empty) {
            val now = h.state.vent
            when {
                !ventSent.mine(now) -> Prend.log("· 처음대로: 사람이 통풍을 바꿔 그대로 둠")
                (now ?: 0) != s.vent -> h.setSeatVent(s.vent) { }
            }
            ventSent.clear()
        }
    }

    /** 흐르던 다른 음악을 잠깐 멈춘다(쪽잠이 끝나 포커스를 돌려주면 그 앱이 다시 튼다). 이 포커스가 깨우기 곡의 포커스도 된다 */
    private fun pauseOthers() {
        lateinit var req: AudioFocusRequest
        req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener({ if (focus === req) melodyFocus(it) }, main)
            .build()
        focus = req
        melodyRoom = 1f
        Prend.log("· 쪽잠: 다른 음악 멈춤(포커스 ${audio.requestAudioFocus(req)})")
    }

    /**
     * 깨우기 곡도 한마디와 같은 원칙(감사 F112): 통화·한마디·대답 듣기가 잠깐 가져가면 곡을 비켰다가 돌려받으면 잇고,
     * 길 안내가 낮춰 달라면 낮춘다. 다른 앱이 아주 가져가면(사람이 음악을 틂) 곡을 거둔다
     */
    private fun melodyFocus(change: Int) {
        val room = when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> 0f
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> MELODY_DUCK
            AudioManager.AUDIOFOCUS_GAIN -> 1f
            AudioManager.AUDIOFOCUS_LOSS -> { Prend.log("· 쪽잠: 다른 소리가 포커스를 가져감 → 곡을 거둠"); stopMelody(); return }
            else -> return
        }
        if (room == melodyRoom) return
        melodyRoom = room
        if (melody != null) Prend.log("· 쪽잠 곡: ${when (room) { 0f -> "잠깐 비킴"; 1f -> "다시 원래대로"; else -> "낮춤" }}")
        melody?.let { p -> val v = melodyVol * room; runCatching { p.setVolume(v, v) } }
    }

    private fun releaseFocus() { focus?.let { audio.abandonAudioFocusRequest(it) }; focus = null }

    /** 잘래요의 잔잔한 음악(소리 엔진 −6dB). 음악 서비스가 아직 없거나 나중에 다시 떠도 이어받게 Bridge에도 적어 둔다 */
    private fun quietMusic(on: Boolean) { Bridge.sleep = on; Bridge.director?.sleep = on }

    private fun startMelody() {
        stopMelody()
        melody = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                val afd = context.resources.openRawResourceFd(R.raw.lofi_a)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                isLooping = true
                setVolume(.05f * melodyRoom, .05f * melodyRoom)
                // 준비는 메인 밖에서. 그사이 곡을 거뒀으면(stopMelody·release) 틀지 않는다
                setOnPreparedListener { mp -> if (melody === mp) mp.start() }
                setOnErrorListener { mp, what, _ ->
                    Prend.log("✗ 쪽잠 곡 틀기 실패 $what")
                    if (melody === mp) melody = null
                    mp.release()
                    true
                }
                prepareAsync()
            }
        }.onFailure { Prend.log("✗ 쪽잠 곡 틀기 실패 $it") }.getOrNull()
        melodyVol = .05f
    }

    private fun setMelody(v: Float) {
        val p = melody ?: return
        if (kotlin.math.abs(v - melodyVol) < .01f) return
        melodyVol = v
        // 다른 소리가 포커스를 가져간 동안은 그 몫만큼(비켰으면 0)
        runCatching { p.setVolume(v * melodyRoom, v * melodyRoom) }
    }

    private fun stopMelody() {
        val p = melody ?: return
        melody = null
        fading = p
        // 2초에 걸쳐 줄이고 멈춘다(그사이 운행 서비스가 끝나면 release가 바로 놓는다)
        val from = melodyVol * melodyRoom
        (1..4).forEach { i -> main.postDelayed({ runCatching { p.setVolume(from * (1 - i / 4f), from * (1 - i / 4f)) } }, i * 500L) }
        main.postDelayed({ runCatching { p.stop() }; runCatching { p.release() }; if (fading === p) fading = null }, 2_100)
    }

    /** 줄이는 중인 쪽잠 곡(멈추기 전) */
    private var fading: android.media.MediaPlayer? = null

    private fun dropFading() {
        fading?.let { p -> runCatching { p.stop() }; runCatching { p.release() } }
        fading = null
    }

    /** 시험: 사람이 바꾼 무드등·바람을 이번 운행에 건드리지 않는 중인가 */
    fun handsDump() = Prend.log("· 시험: 사람이 바꾼 무드등 ${if (lightHands) "있음(건드리지 않음)" else "없음"} · 바람 ${if (fanHands) "있음(건드리지 않음)" else "없음"}")

    /** 시험: 쪽잠이 끝나기 sec초 전으로 당긴다 */
    fun skipTo(sec: Int) {
        if (!napping) return
        endAt = System.currentTimeMillis() + sec * 1000L
        Stage.ui.update { it.copy(napEndAt = endAt) }
        Prend.log("· 시험: 쪽잠 끝나기 ${sec}초 전으로")
    }

    fun release() {
        wakeGen++
        // 운행 서비스가 끝나면 잘래요의 잔잔한 소리도 거둔다: 음악 서비스가 남아 Bridge.sleep을 따르므로, 이것을 두면 잘래요 없이 소리만 잠 모드로 남는다
        // (11/08 오류 점검 D3. 같은 운행으로 다시 뜨면 restoreNap이 다시 켠다)
        if (sleeping) quietMusic(false)
        speaker.stop(); releaseFocus()
        // 천천히 줄이는 예약은 아래에서 지워지므로 곡은 바로 멈춘다
        // 아직 준비 중이면 stop이 던질 수 있어 release는 따로
        melody?.let { p -> runCatching { p.stop() }; runCatching { p.release() } }
        melody = null
        // 줄이며 멈추던 곡도 놓는다(그 예약은 아래에서 지워져, 전에는 반복 재생 플레이어가 남았다, 11/04 점검)
        dropFading()
        main.removeCallbacksAndMessages(null)
        Stage.onNap = null
    }

    companion object {
        /** 잘래요 동안 그 자리 독서등을 읽는 간격 */
        const val LAMP_READ_MS = 5_000L
        const val DIM = 3
        const val BRIGHT = 100
        const val NIGHT_COLOR = 0x2A1F5C
        const val DAWN_COLOR = 0xFFB37A
        const val DAY_COLOR = 0xFFF1DC
        const val AFTER_MS = 3 * 60_000L
        const val WAKE_BEFORE_S = 300
        const val LEAN_MS = 30_000L
        /** 길 안내가 낮춰 달라고 할 때 깨우기 곡의 몫(약 −10dB) */
        const val MELODY_DUCK = .32f
    }
}
