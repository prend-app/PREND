package app.prend.ride

import ai.pleos.playground.vehicle.constant.control.FanDirectionType
import android.car.VehicleGear
import android.media.AudioAttributes
import android.os.SystemClock
import java.util.Locale
import app.prend.Bridge
import app.prend.Prend
import app.prend.car.CarLink
import app.prend.car.CarState
import app.prend.car.Navi
import app.prend.sound.Chime
import app.prend.ui.Ask
import app.prend.ui.NapPhase
import app.prend.ui.Scene
import app.prend.ui.Stage
import app.prend.voice.Speaker
import org.json.JSONArray
import org.json.JSONObject

/**
 * PREND가 먼저 건네는 말(접수양식 3번): 두 시간에 쉼터 권하기, 쉼터에 세우면 쪽잠 권하기, 오래 막힐 때 분위기(무드등·잔잔한 곡) 바꾸기(JamHelp),
 * 같이 고르기(Together), 가족 정보(Baby, 처음 한 번), 혹시 사고(Bump), 사고 뒤 한 번(AfterCrash), 등받이를 젖히면 쪽잠·잘래요(Nap).
 * - 운전 도중 세우고 손님이 타면(조수석 문이 열렸다 닫히고 그 자리에 사람) 인사하는 30초 동안 먼저 건넬 말을 꺼내지 않고 PREND 곡의 목소리 길을 비운다.
 *   "친구 탔어"라고 말하지 않아도 문과 자리로 안다(목소리·알림·물음 없이)
 * - 혹시 사고: 고속도로면 묻기 전에 대피 문장부터. 대답이 없으면 10분 동안 배너에 한 줄을 두고, 밖을 살피고 운전석에 다시 앉으면 "확인해 보셨어요?"를 한 번 더(놀란 사람은 부르지 못한다)
 * - 할 말은 줄에 세우고, 말할 때(Timing)가 오면 화면 카드와 목소리로 건넨다. 화면이 안 보이면 배너가 같은 질문을 띄운다.
 * - 말하는 도중 깜빡이가 켜지면 말을 멈추고 줄에 되돌렸다가, 편해지면 처음부터 다시 건넨다(말 삼키기).
 *   깜빡이·후진은 차 신호가 바뀌는 순간 멈춘다(onCar, 1초 틱을 기다리지 않는다). 통화·길 안내가 포커스를 가져가면 Speaker가 끊는다
 * - 말이 끝나면 대답을 5초 듣는다. 대답을 기다리는 동안은 다른 말을 꺼내지 않는다. 그 5초에 후진·깜빡이·통화가 생기면 듣기와 카드를 닫고 줄에 되돌린다.
 *   끊겨 되돌린 정체·같이 고르기는 물은 것으로 치지 않는다(편한 때 다시)
 * - 대답을 못 알아들으면(예도 아니오도 아님) 한 번만 "'응'이나 '괜찮아'로 말씀해 주세요"라고 하고 다시 듣는다. 그래도 모르면 듣기를 닫고 카드 버튼만(검토-방향노인초보 3-5).
 *   되물은 "네?"·"응?"·'어'·'엉' 한마디도 어느 물음에서든 예로 보지 않고 이 길로 간다(검토 11/07 멋대로 판단, ride/Answer). 그런 대답은 기억에도 세지 않는다
 * - 운행 범위 값(다음 쉼터 분, 이번 운행에 물은 것·거절한 것, 아이 탑승·대리 운행·조용히 가기·말로 켠 빗소리)은 1초마다 keep()으로 적어 두고(ride/RideKeep),
 *   같은 운행 중 프로세스가 다시 뜨면 restore()로 잇는다. 묻던 중에 꺼진 물음은 대답 없이 넘긴 것으로 친다
 * - 쪽잠·잘래요 동안은 정체·같이 고르기를 묻지 않는다(쉼터 권하기는 운전자의 일이라 그대로). 잘래요 동안에도 알아서 하는 김서림 막기는 그대로 하고(목소리가 없다),
 *   먼저 여쭙기로 바뀐 사람에게는 목소리 없이 카드로만 묻는다(검토 11/07). 운전자가 자는 동안·깨우는 동안은 혹시 사고 말고는 묻지 않는다
 * - 확인 카드(Stage.confirm, id "Q_")가 떠 있는 동안은 제 말을 꺼내지도 카드를 덮지도 않고 줄에 둔다(혹시 사고만 기다리지 않는다, 검토 11/07)
 * - 줄 앞의 말을 지금 건넬 수 없으면(세워 둔 동안의 쉼터 등) 건너뛰고 뒤의 말을 먼저 건넨다. 다른 한마디에 끊긴 말은 줄 앞에 되돌린다
 * - 대답은 순간마다 기억한다(ride/Memory): 정체에 세 번 연달아 "응"이면 다음부터 묻지 않고 알리며 하고, 두 번 연달아 "괜찮아요"면 묻지 않는다.
 *   알아서 한 일은 그 자리에서 "되돌려"나 [되돌리기]로 되돌릴 수 있고, 그러면 다음엔 다시 묻는다. 쉼터는 기억으로 줄이지 않는다
 * - 곁들임 물음 다섯(한 시간·밤 대리 운행·이야기에 비켜 서기·오래 막힘·같이 고르기)은 기억 화면 '먼저 묻기'에서 켜 둔 것만 묻는다
 *   (사용자 결정 11/06: 새로 깐 앱은 꺼 둔다, ride/Memory.asks). 쉼터 권하기(두 시간·졸음)·혹시 사고·고속도로 정차·급할 때는 늘 묻는다
 * - 처음 설치하면 가족 정보 물음 곁에 위치를 어디에 쓰는지 알리는 카드 한 장(GEO, ride/GeoShare. 한 운행 여섯 번 몫에 세지 않는다).
 *   둘 다 세워 둔 채 문이 모두 닫히고 10초를 기다린다(검토 11/06: 뒷문을 열고 아이 벨트를 매는 동안 묻지 않게).
 *   위치 카드는 소리가 없어 보일 때만 띄우고(첫 화면이 보이고 허락 판이 없거나, 화면이 닫혀 있고 알림 허락이 있어 배너가 뜰 때), 보인 채로 5초가 지나야 건넨 것으로 센다
 * - 한 시간 달리면 "필요한 것 있으세요?"(대답은 [프렌드]에 한 말처럼 고른다: 바깥 공기·열선·음악·쉼터)
 * - 낮인데 차가 어두운 곳(지하·실내 주차장)에서 P에 넣으면 내리기 전에 주차 자리를 확인하라고 한 번
 * - 밤에 시동 뒤 조수석에 사람이 있으면 대리 운행인지 한 번 묻고, 대리면 그 운행은 먼저 말을 걸지 않는다(잘래요·혹시 사고·급할 때만)
 * - 세워 둔 차에서 화면을 보는 동안 동승자가 있으면 이야기에 비켜 서기를 켤지 한 번 묻는다(켜기 전에는 마이크를 쓰지 않는다)
 * - 말하기 전 기척(별첨 2 §2-2 ①): 줄의 말을 건네기 2초 전에 무드등을 5%p 올리거나(15% 이하일 때, 색은 그대로) 작은 차임을 내고,
 *   그 2초 동안 말할 때가 닫히면(깜빡이 등) 빛을 되돌리고 말을 삼킨다. 급할 때·쪽잠·잘래요·대리 운행·혹시 사고에는 기척 없이
 * - "괜찮아요"나 대답 없이 넘긴 물음(정체·같이 고르기·한 시간·쪽잠·잘래요·김서림)은 그 운행에서 다시 묻지 않는다(때 되면 다시 권하는 것은 쉼터뿐, 별첨 2 V11·V12).
 *   주차 자리는 한 운행에 한 번만 알린다
 * - 조용히 가기("오늘은 조용히 가자", 그 운행만, 별첨 2 V10): 쉼터·혹시 사고만 목소리로 묻고, 나머지 물음은 목소리 없이 카드(배너)로만 30초.
 *   혼자 탄 운행에서 같은 칸(평일·주말 × 시간대)에 두 번 부르면 다음부터 그 칸의 혼자 운행은 출발할 때 먼저 조용히 두고 카드 한 장으로 알린다(QUIET)
 * - 한 시간 물음에서 바깥 공기를 세 번 연달아 고르면 다음부터 한 시간이 되면 묻지 않고 바깥 공기를 넣어 두고 알린다(HAUTO, [되돌리기]면 다시 묻는다)
 * - 동승자가 잘래요로 쉬어 깨어 있는 사람이 운전자뿐이면 한 시간 물음을 살린다: 목소리·기척 없이 운전자에게만 카드 한 장
 *   (잘래요 중 먼저 말 걸지 않기(별첨 2 V9)의 예외, 11/01 사용자 "너의 추천대로")
 * - 한 시간 물음의 첫 버튼은 그때 차에 맞게(바깥 공기·열선·통풍·잔잔한 곡, 지난번 고른 것을 먼저)
 * - 급하지 않은 말(한 시간·같이 고르기·정체·김서림·이야기 비켜 서기·주차 자리 등)은 편한 때(신호 대기·안정 주행·P, ride/Timing.comfy)를 기다렸다 건넨다.
 *   5분 넘게 편한 때가 오지 않으면 전처럼 막는 까닭만 본다. 쉼터·혹시 사고·잘래요는 기다리지 않는다(감사 F45)
 * - 다른 앱 음악이 흐르면 먼저 말하지 않는다(별첨 2 V16): 쉼터·혹시 사고·주차 자리만 목소리로, 나머지는 카드로만.
 *   그 음악 위에 얹는 말 앞에는 0.5초 알림음을 낸다(별첨 4 §5 ①, 대답 차임과 다른 소리). 기척 차임은 그때 내지 않는다(두 번 울리지 않게)
 * - "졸려"·"쉼터"는 곧바로 넣지 않고 쉼터 카드로 권해 "응"·[경유지로]일 때만 넣는다(sleepy, 별첨 2 §5-2 ④). 앞쪽에 쉼터가 없으면 세우고 쉬자고 권한다
 * - 주차 자리는 내비 안내로 찾아와 도착한 날에만 알리고, 알리기만 하는 말이라 대답을 듣지 않는다(다른 음악을 멈추지 않는다)
 * 시동 때 열선·통풍 묻기는 뺐다(차의 공조 연동 자동 제어와 겹침, 사용자 결정 10/27).
 * 들은 말은 할 일을 고르는 데만 쓰고 기록에는 길이만 남긴다.
 */
class Care(
    private val speaker: Speaker,
    private val timing: Timing,
    /** 쉼터 자료(운행 서비스가 배경에서 읽는다. 다 읽기 전에는 null) */
    private val rests: () -> RestAreas?,
    private val navi: Navi?,
    private val jam: JamHelp,
    private val together: Together,
    private val memory: Memory,
    private val baby: Baby,
    private val after: AfterCrash,
    private val nap: Nap,
    private val novice: Novice,
    /** 새 운행 때 함께 처음으로 돌릴 것(주차 코치·주유 안내 등, ride/Coach) */
    private val onNewRide: () -> Unit = {},
    /** 주차 코치 중인가(그동안은 물음을 미룬다. 바퀴 안내가 대답 듣기와 겹치지 않게) */
    private val coaching: () -> Boolean = { false },
    /** 이야기에 비켜 서기를 지금 물어도 되나(아직 정하지 않음·화면이 보임·동승자), 대답(켬·끔) */
    private val talkAskable: () -> Boolean = { false },
    private val onTalkAnswer: (Boolean) -> Unit = {},
    /** 운행 범위 값을 적어 두는 곳(없으면 적지 않는다) */
    private val kept: RideKeep? = null,
    /** 지금 고속도로 위인가(혹시 사고 물음 앞에 대피 문장을 붙일지) */
    private val onHighway: () -> Boolean = { false },
    /** 말 차례(먼저 꺼내는 물음은 한 번 설 때 하나, 앞 말 뒤 20초, V13·V14). 없으면 보지 않는다 */
    private val floor: Floor? = null,
    /**
     * 위치 알림 카드가 지금 사람에게 보이나: 화면이 보이면 첫 화면이고 허락 판이 없을 때, 화면이 닫혀 있으면 알림 허락이 있어 배너가 뜰 때.
     * 보이지 않으면 카드를 띄우지 않고 기다리고, 건넨 수에도 세지 않는다(검토 11/06)
     */
    private val geoShown: () -> Boolean = { true },
    /** 화장실 들를 곳 고르기("화장실 급해", ride/Toilets). 없으면 찾지 못했다고만 */
    private val toilets: Toilets? = null,
) {

    /**
     * REST: 두 시간 쉼터, NAP: 쪽잠 주무실래요(쉼터에 세움·운전석 젖힘), JAM: 정체, PICK: 같이 고르기,
     * KIDS: 가족 중에 함께 타는 아이가 있나요(가족 정보, 처음 한 번·세워 둔 동안), BUMP: 혹시 사고 나셨어요(급제동 → 멈춤 → 운전석 문),
     * BUMPBACK: 혹시 사고에 대답이 없던 사람이 운전석에 다시 앉음 → 확인해 보셨어요?,
     * AFTER: 다음 운행 때 사고 뒤 한 번 묻기, SLEEP: 주무시게요(동승석 젖힘), FOG: 김서림,
     * HOUR: 한 시간 "필요한 것 있으세요?", SPOT: 주차 자리 확인, DAERI: 대리 운행이세요?, TALK: 이야기에 비켜 서기를 켤까요?,
     * QUIET: 오늘도 조용히 갈게요(배운 칸, 카드만), GEO: 위치를 이렇게 써요(처음 한 번, 가족 정보 물음 곁에 카드만),
     * TOILET: "화장실 급해"의 대답으로 가까운 화장실을 경유지로 넣을까요(사람이 먼저 꺼낸 말, 11/07)
     */
    private enum class Line { REST, NAP, JAM, PICK, KIDS, BUMP, BUMPBACK, AFTER, SLEEP, FOG, HOUR, SPOT, DAERI, TALK, QUIET, GEO, TOILET }

    /** 할 말 줄. 앞의 것부터 건넨다 */
    private val queue = ArrayDeque<Line>()
    private var speakingLine: Line? = null
    /** 건넨 뒤 대답(말·버튼)을 기다리는 말 */
    private var asking: Line? = null
    /** 못 알아들어 한 번 다시 물은 말(건넬 때 비운다)과 그 대답을 받을 곳(다시 묻기가 멈추면 대답이 없던 것으로 넘긴다) */
    private var reasked: Line? = null
    private var reaskAnswer: ((String) -> Unit)? = null
    private var swallowed: String? = null
    /** 한마디가 포커스를 받지 못했으면 이때(elapsedRealtime)까지 다시 건네지 않는다(매 초 카드가 떴다 사라지지 않게) */
    private var heldUntil = 0L
    /** 다음에 쉼터를 권할 연속 운전 시간(분). 쉬어서 0이 되면 다시 120 */
    private var restAt = REST_FIRST
    private var lastMinutes = 0
    private var cardTimeout: Runnable? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** 이번에 권하는 쉼터(앞쪽의 가장 가까운 곳). 권할 때 고르고 "응" 하면 그곳을 넣는다 */
    private var pick: RestPick? = null
    private var lastCar = CarState()
    /** 쉼터를 경유지로 넣은 때(elapsedRealtime). 그 뒤 40분 안에 세우면 쪽잠을 권한다 */
    private var restAcceptedAt = 0L
    /** 지금 묻는 말이 끊기면 줄에 되돌리나(이미 한 일을 알리는 말은 false: 끊겨도 다시 하지 않는다) */
    private var requeueAsk = true
    /** 지금 묻는 말이 목소리로 나갔나(카드만이면 false). "졸려"에 비켜 서 줄에 되돌릴 때 말 차례에서 빼는 셈이 다르다 */
    private var askVoiced = false
    /** 지난 틱에 급할 때(E)였나(막 E가 됐을 때 한 번만 거두려고) */
    private var wasE = false
    /** 이번 연속 운전에 한 시간 물음을 줄 세웠나(쉬어서 0이 되면 다시) */
    private var hourAsked = false
    /** 이번 운행이 시작된 때(대리 운행은 시동 뒤 3분 안에만 묻는다), 대리 운행·이야기 비켜 서기를 이번 운행에 물었나 */
    private var rideStartAt = 0L
    private var daeriAsked = false
    private var talkAsked = false
    /** 지난번 P 뒤로 달렸나(주차 자리는 달려서 온 뒤 P에 넣을 때만) */
    private var moved = false
    /** 이번 운행에 주차 자리를 알렸나(한 운행에 한 번) */
    private var spotDone = false
    /** 이번 운행에 "괜찮아요"나 대답 없이 넘긴 물음. 그 운행에서는 다시 묻지 않는다 */
    private val declined = mutableSetOf<Line>()
    /** 기척 중인 말, 기척을 시작한 때, 그때의 연속 운전(분). 2초 동안 말할 때가 닫히지 않으면 건넨다 */
    private var presenceLine: Line? = null
    private var presenceAt = 0L
    private var presenceMinutes = 0
    /** 기척으로 올린 무드등(원래 밝기, 올린 밝기, 색). 말이 끝나거나 삼키면 되돌린다 */
    private var lit: Triple<Int, Int, Int>? = null
    /** 이번 운행에 기척 중 삼킨 말 수(기록용) */
    private var presenceHeld = 0
    private val presenceCheck = Runnable { presenceStep() }

    /** 안쪽 공기만 돌리기 시작한 때(한 시간 물음의 첫 버튼을 고를 때 본다). 0이면 바깥 공기 */
    private var recircSince = 0L
    /** 이번 한 시간 물음의 첫 버튼 */
    private var hourOffer = Offer.AIR

    /** 편한 때를 기다리기 시작한 때(줄의 말마다), 기다린다고 기록에 남긴 말, 너무 오래 기다려 전처럼 건네는 말(감사 F45) */
    private val comfyWaitSince = mutableMapOf<Line, Long>()
    private val comfyLogged = mutableSetOf<Line>()
    private val comfyLate = mutableSetOf<Line>()
    /** 편한 때를 이만큼 기다려도 오지 않으면 전처럼 건넨다(시험 comfy_fast로 줄인다) */
    var comfyMaxMs = COMFY_MAX_MS

    /**
     * 달리는 중에 목소리 포커스를 받지 못해 신호 대기나 P까지 미룬 말(감사 F94①). 이 미루기는 스위치이고 기본은 꺼져 있다:
     * 설명회 Q2(주행 중 짧은 음성 질문이 '자동 재생 금지'에 걸리는가)의 답이 '막힌다'이면 켠다(별첨 2 §2-4 사다리 ②). 시험 voice_defer_on
     */
    var deferRefusedVoice = false
    private val deferred = mutableSetOf<Line>()

    /** 지금 묻는 쉼터가 "졸려"에서 온 것인가, 그때 다음 쉼터 권하기가 몇 분이었나(괜찮아요면 그보다 늦추지 않는다) */
    private var restSleepy = false
    private var restBefore = REST_FIRST
    /** 두 시간 쉼터를 거절·무응답 뒤 1시간 뒤에 한 번 더 권했나(그것도 넘기면 쉬기 전까지 다시 권하지 않는다, 별첨 2 §5-2 ③) */
    private var restReoffered = false
    /** 쉼터를 물을 때의 restReoffered·restSleepy. 확인 카드가 그 물음을 덮으면 이 값으로 되돌리고 다시 묻는다(restCovered) */
    private var restAskWas = false to false

    /** 이번 운행에 한 번 끊겨 줄에 되돌린 말(두 번째로 끊기면 버린다), 이번에 선 동안 이미 말해 카드로만 건넬 말 */
    private val cutOnce = mutableSetOf<Line>()
    private var cardFor: Line? = null

    val seats = SeatHelp()

    /** 기억대로 묻지 않고 한 일(정체). 3분 안에 "되돌려"·[되돌리기]면 다음엔 다시 묻는다 */
    private data class Auto(val kind: Memory.Kind, val at: Long)
    private var auto: Auto? = null
    /** 알아서 바꾼 무드등을 사람이 직접 바꿨는지 마지막으로 본 때(10초마다 읽는다) */
    private var handsAt = 0L

    init {
        Stage.onAsk = { id, yes ->
            when (id) {
                "REST" -> answerRest(if (yes) "응" else "괜찮아", byButton = true)
                "TOILET" -> answerToilet(if (yes) "응" else "괜찮아", byButton = true)
                "JAM" -> answerJam(if (yes) "응" else "괜찮아", byButton = true)
                "PICK" -> answerPick(if (yes) "응" else "괜찮아", byButton = true)
                "AUTO" -> answerAuto(if (yes) "좋아" else "되돌려", byButton = true)
                "KIDS" -> answerKids(if (yes) "응" else "아니", byButton = true)
                "BUMP" -> answerBump(if (yes) "응" else "아니", byButton = true)
                "BUMPBACK" -> answerBumpBack(if (yes) "응" else "아니", byButton = true)
                "AFTER" -> answerAfter(if (yes) "다 했어" else "아직", byButton = true)
                "NAP" -> answerNap(if (yes) "응" else "괜찮아", byButton = true)
                "SLEEP" -> answerSleep(if (yes) "응" else "아니", byButton = true)
                "FOG" -> answerFog(if (yes) "응" else "괜찮아", byButton = true)
                "HOUR" -> answerHour(if (yes) hourOffer.want else "괜찮아", byButton = true)
                "SPOT" -> answerSpot(if (yes) "알겠어" else "그만", byButton = true)
                "DAERI" -> answerDaeri(if (yes) "응" else "아니", byButton = true)
                "TALK" -> answerTalk(if (yes) "응" else "괜찮아", byButton = true)
                "QUIET" -> answerQuiet(if (yes) "좋아" else "평소대로", byButton = true)
                "HAUTO" -> answerHourAuto(if (yes) "좋아" else "되돌려", byButton = true)
                "GEO" -> answerGeo(yes)
            }
        }
        Stage.onSeat = { kind -> seats.ask(kind) { Stage.toast(it) } }
        Stage.onSeatAt = { kind, who -> seats.at(kind, who) { Stage.toast(it, 3_500) } }
        Stage.onMotion = { motion() }
        Stage.onUndo = { undo() }
        Stage.onForget = { k ->
            when (k) {
                null -> { memory.forget(null); baby.forget() }
                "KIDS" -> baby.forget()
                else -> memory.forget(k)
            }
            Stage.toast(if (k == null) "모두 잊었어요. 처음처럼 물을게요" else "잊었어요. 처음처럼 할게요", 3_000)
        }
        Stage.onBaby = { on -> baby.toggle(on) }
        Stage.onKids = { on -> baby.setKids(on, if (on) "사람이 체크" else "사람이 뺌") }
        Stage.onDaeri = { on -> setDaeri(on, byAsk = false) }
        Stage.onQuietRide = { on, asleep, kid -> setQuietRide(on, asleep, kid) }
        // 늘 조용히(사용자 결정 11/04): 켜면 이번 운행도 바로 조용히(받는 말 없이), 끄면 이번 운행도 평소대로
        Stage.onAlwaysQuiet = { on ->
            memory.alwaysQuiet = on
            Prend.log("◆ 늘 조용히 ${if (on) "켬" else "끔"}")
            Stage.ui.value = Stage.ui.value.copy(quietRide = on)
            Stage.toast(if (on) "앞으로 늘 조용히 갈게요 · 쉼터와 급한 일만 말해요" else "다시 평소대로 말할게요", 3_000)
        }
        Stage.onQuiet = { k ->
            if (k == "SPOT") { memory.quiet(Memory.Kind.SPOT, "“그만 알려” 하셔서"); Stage.toast("다음부턴 주차 자리를 알리지 않을게요", 3_000) }
        }
        // 대답 창이 닫힌 뒤 10초 안에 [프렌드]로 한 "네": 사고 뒤 물음은 단추 [다 했어요]가 아니라 말 대답으로 받는다(바로 지우지 않고 한 번 더 묻는다, 검토 11/07)
        Stage.onLateSaid = { id, text -> if (id == "AFTER" && asking == Line.AFTER) { answerAfter(text, byButton = false); true } else false }
        Stage.onRest = { sleepy() }
        Stage.onToilet = { toilet(it) }
        Stage.onToiletNo = { toiletOff() }
        Stage.onRestNo = { restNo() }
        // 배너의 [쉬어 갈 곳](한 시간 물음): "쉼터"라고 대답한 것처럼 받아 쉼터 카드로 잇는다
        Stage.onAlt = { id -> if (id == "HOUR" && asking == Line.HOUR) answerHour("쉼터", byButton = true) }
    }

    /** 새 운행(시동). offMin은 시동을 끈 지 몇 분(모르면 null) */
    fun newRide(why: String, offMin: Int? = null) {
        cardWaitSince.clear()
        // 지난 운행에서 묻던 말·카드·시간 끝 예약은 거둔다(11/08 오류 점검 C5: 지난 카드가 새 운행에 남아 줄을 막고, 잠든 뒤 늦게 돈 시간 끝이
        // 그 물음을 새 운행의 '거절'로 적었다). 확인 카드(Q_)와 사람이 [프렌드]로 말하는 듣기는 그대로 둔다
        if (asking != null || speakingLine != null) {
            cardTimeout?.let { main.removeCallbacks(it) }
            speaker.stop()
            if (Stage.answering && !confirmUp()) Stage.endListen()
            speakingLine = null; asking = null; reasked = null
            clearAsk()
            // 묻던 쉼터는 대답이 없던 것처럼 30분 뒤 다시(멈춰 둔 다음 권하기가 그대로 남지 않게, quiet와 같다)
            if (restAt == Int.MAX_VALUE) restAt = lastMinutes + REST_AGAIN
        }
        // 지난 운행의 기록(이야기 시작·끝 시각 등)은 새 운행에서 지운다(디버그 빌드는 하나 앞까지 둔다)
        Prend.newRide()
        Prend.log("◆ 새 운행($why${offMin?.let { ", 시동 끈 지 ${it}분" } ?: ""})")
        // 지킨 운행 수(기기 안, ride/Stats). 시험·시연 운행은 이 운행 동안 셋 다 세지 않는다(급할 때 안내·대답 받음도, 검토 11/06)
        Stats.demoRide = why == "시험" || why == "시연"
        Stats.add("ride")
        // 지난 운행에 적어 둔 상태는 버리고 운행 id를 새로 매긴다(아래에서 비운 값은 다음 틱에 다시 적는다)
        kept?.newRide()
        // '방금 15분 쉬셨어요'는 운행마다 처음부터
        endBumpWatch("새 운행")
        if (Stage.ui.value.restedAt > 0) Stage.ui.value = Stage.ui.value.copy(restedAt = 0L)
        auto = null
        restAcceptedAt = 0L
        rideStartAt = SystemClock.elapsedRealtime()
        daeriAsked = false
        talkAsked = false
        spotDone = false
        if (declined.isNotEmpty()) { Prend.log("· 지난 운행에 거절한 물음을 비움: ${declined.joinToString { it.name }}"); declined.clear() }
        comfyWaitSince.clear(); comfyLogged.clear(); comfyLate.clear()
        deferred.clear()
        cutOnce.clear(); cardFor = null
        restSleepy = false
        cancelPresence("새 운행", drop = true)
        presenceOff("새 운행")
        presenceHeld = 0
        if (memory.alwaysQuiet) { if (!Stage.ui.value.quietRide) { Stage.ui.value = Stage.ui.value.copy(quietRide = true); Prend.log("· 조용히 가기(늘 조용히)") } }
        else if (Stage.ui.value.quietRide) { Stage.ui.value = Stage.ui.value.copy(quietRide = false); Prend.log("· 조용히 가기 끝(새 운행)") }
        quietCounted = false
        quietChecked = false
        quietSlot = null
        drove = false
        guestDoorAt = 0L
        // 같은 연속 운전이 이어지면(잠깐 시동을 껐다 켬) 한 시간 물음을 다시 하지 않는다
        if (lastMinutes < HOUR_AT) hourAsked = false
        moved = false
        // 운행마다 정하는 말은 지난 운행 것을 버린다. 쪽잠 권하기·혹시 사고 다시 묻기도 그 정차에 딸린 말이라 버린다
        // (11/08 오류 점검 C4: 쉼터에서 10초 안에 시동을 끄고 내리면 다음 출발 때 '15분 주무실래요?'가 나왔다)
        queue.removeAll(listOf(Line.HOUR, Line.SPOT, Line.TALK, Line.DAERI, Line.KIDS, Line.GEO, Line.NAP, Line.BUMPBACK))
        GeoShare.newRide()
        if (Stage.ui.value.daeri) { Stage.ui.value = Stage.ui.value.copy(daeri = false); Prend.log("· 대리 운행 끝(새 운행)") }
        // 멀미로 한 단 낮춘 곡은 그 운행만
        if (Bridge.motion) { Bridge.motion = false; Bridge.director?.motion = false }
        handsAt = 0L
        // 급할 때·같이 고르기·기억 화면을 연 채 시동을 껐으면 닫는다(급할 때 화면이 남으면 이번 운행 내내 먼저 건네는 말이 막히고 사고 배너가 다시 뜬다)
        Stage.ui.value.scene.let { s -> if (s.isE || s == Scene.PICK || s == Scene.MEMO) { Prend.log("· 지난 운행의 ${s.name} 화면 닫음"); Stage.go(Scene.HOME) } }
        // E가 끝난 뒷정리는 여기서 먼저 한다: 다음 틱이 E 끝으로 보고 아래에서 세울 이번 운행의 물음(사고 뒤 한 번)까지 버리지 않게
        if (wasE) {
            val late = queue.filter { it != Line.REST }
            if (late.isNotEmpty()) { queue.removeAll(late); Prend.log("· 급할 때가 끝나 그동안 줄에 든 말 버림: ${late.joinToString { it.name }}") }
            wasE = false
        }
        heldUntil = 0L
        nap.newRide()
        novice.newRide()
        onNewRide()
        // 이번 운행만의 아이 탑승은 끈다(가족 정보는 그대로). 지난번 사고가 있었으면 몸과 보험 접수를 한 번 묻는다
        baby.newRide()
        if (after.shouldAsk() && Line.AFTER !in queue) { queue.addLast(Line.AFTER); Prend.log("· 권할 말 줄 세움: 사고 뒤 한 번") }
    }

    /**
     * 가족 정보를 아직 정하지 않았다: 세워 둔 동안 "가족 중에 차에 함께 타는 아이가 있나요?"를 한 번 묻는다
     * (운행마다 한 번, 건넨 것이 두 번이면 그만. 차의 좌석 센서로는 카시트의 아이를 알 수 없어 운행마다 뒷문으로 묻던 것을 11/01에 뺐다)
     */
    fun queueKids() {
        if (Line.KIDS in queue || asking == Line.KIDS || presenceLine == Line.KIDS || speakingLine == Line.KIDS) return
        baby.queued()
        // 위치 알림 카드보다 앞에(가족 정보를 먼저 묻고 그 곁에 위치 카드)
        val geo = queue.indexOf(Line.GEO)
        if (geo >= 0) queue.add(geo, Line.KIDS) else queue.addLast(Line.KIDS)
        Prend.log("· 권할 말 줄 세움: 가족 정보(처음 한 번)")
    }

    /**
     * 위치를 어디에 쓰는지 아직 알리지 않았다(처음 설치한 뒤, 사용자 결정 11/06): 가족 정보 물음 곁에 카드 한 장.
     * "골목·큰길을 가리고 병원·화장실·날씨·가게를 찾을 때만 위치를 서버로 보내요 · 저장하지 않아요 [괜찮아요] [보내지 않기]"(ride/GeoShare)
     */
    fun queueGeo() {
        if (Line.GEO in queue || asking == Line.GEO) return
        GeoShare.queued()
        geoWaitLogged = false
        queue.addLast(Line.GEO)
        Prend.log("· 권할 말 줄 세움: 위치 알림(처음 한 번)")
    }

    /** 접촉 사고일 수 있다(Bump): 다른 말보다 먼저, 말할 때를 기다리지 않고 묻는다 */
    fun queueBump() {
        if (Stage.ui.value.scene.isE) return
        endBumpWatch("새 급제동")
        queue.remove(Line.BUMPBACK)
        cancelPresence("혹시 사고")
        if (speakingLine != null || asking != null) { speaker.stop(); Stage.endListen(); closeAny() }
        queue.remove(Line.BUMP); queue.addFirst(Line.BUMP)
        Prend.log("· 권할 말 줄 세움: 혹시 사고(맨 앞)")
    }

    /** 쪽잠을 권한다(쉼터에 세움·운전석 등받이를 젖힘) */
    fun queueNap() {
        if (nap.napping || Line.NAP in queue || asking == Line.NAP || presenceLine == Line.NAP || Line.NAP in declined) return
        // 고속도로 갓길·막힌 차로에서는 쪽잠을 권하지 않는다(대피 안내가 먼저, 11/08 오류 점검 C2)
        if (highwayStopped()) { Prend.log("· 쪽잠: 고속도로 위 정차라 권하지 않음"); return }
        queue.addLast(Line.NAP)
        Prend.log("· 권할 말 줄 세움: 쪽잠")
    }

    /** 비·추운 날 안쪽 공기만 오래 돌린다(ride/Coach): 바깥 공기로 바꿀지 한 번 묻는다 */
    fun queueFog() {
        if (Line.FOG in queue || asking == Line.FOG || presenceLine == Line.FOG || Line.FOG in declined) return
        queue.addLast(Line.FOG)
        Prend.log("· 권할 말 줄 세움: 김서림")
    }

    /** 동승석 등받이를 젖혔다: 잘래요를 묻는다 */
    fun queueSleep() {
        if (nap.sleeping || Line.SLEEP in queue || asking == Line.SLEEP || presenceLine == Line.SLEEP || Line.SLEEP in declined) return
        queue.addLast(Line.SLEEP)
        Prend.log("· 권할 말 줄 세움: 잘래요")
    }

    /** 1초마다 부른다 */
    fun tick(car: CarState, minutes: Int) {
        // 지난 1초 사이 바뀐 운행 범위 값을 적어 둔다(바뀌지 않았으면 쓰지 않는다)
        keep()
        val wasParked = lastCar.parked
        lastCar = car
        if (!car.parked) toiletFromPark = false
        // 화장실을 묻는 중에 P에 넣었다(그 주유소에 들어섰거나 고속도로에 섰다): 물음을 거둔다(고속도로 대피 카드가 뜰 자리를 막지 않게, 검토 11/07)
        if (car.parked && !wasParked && (asking == Line.TOILET || speakingLine == Line.TOILET)) {
            // 고른 곳 가까이(150m 안)에 섰으면 그곳에 들어선 것이라 말없이 거두고, 다른 곳에 섰으면 사람이 청한 일이 말없이 사라지지 않게 한 줄로 알린다(검토 11/07)
            val near = toiletNear()
            Prend.log("· 화장실: 세워서 물음을 거둠(${if (near) "고른 곳 가까이" else "다른 곳"})")
            if (speakingLine == Line.TOILET) speaker.stop()
            // 화장실 대답 창만 닫는다(사람이 [프렌드]로 말하는 중이면 그 듣기는 두고)
            if (Stage.answering) Stage.endListen()
            cardTimeout?.let { main.removeCallbacks(it) }
            closeToilet(); toiletPick = null
            if (!near && !Stage.ui.value.scene.isE) Stage.toast(TOILET_STOPPED, 4_000)
        }
        if (car.speedKmh > 5f) { moved = true; drove = true }
        if (car.recirc == true) { if (recircSince == 0L) recircSince = SystemClock.elapsedRealtime() } else recircSince = 0L
        // 알아서 바깥 공기로 바꾼 뒤 3분 안에 사람이 공조 버튼으로 안쪽 공기로 되돌렸다: [되돌리기]처럼 다음엔 먼저 묻는다(11/04 사람 시험).
        // 바깥 공기가 실제로 들어온 것을 본 뒤의 되돌림만 센다(차가 바뀐 값을 늦게 알리는 동안의 옛 값은 세지 않는다)
        if (fogAutoAt > 0) {
            val e = SystemClock.elapsedRealtime() - fogAutoAt
            if (e > 3 * 60_000) { fogAutoAt = 0L; fogSawOutside = false }
            else if (car.recirc == false) fogSawOutside = true
            else if (car.recirc == true && fogSawOutside) {
                fogAutoAt = 0L; fogSawOutside = false
                memory.fogAsk = true
                Prend.log("◆ 알아서 김서림: 사람이 공조 버튼으로 안쪽 공기로 되돌림 → 다음엔 먼저 물음")
                Stage.toast("다음에는 먼저 여쭐게요", 3_000)
            }
        }
        // 기척의 빛·점은 말이 끝나면 거둔다(대답·끊김·포커스 실패 어느 길로 끝나도 1초 안에)
        if (presenceLine == null && speakingLine == null && (lit != null || Stage.ui.value.presence)) presenceOff("말 끝")
        auto?.let { if (SystemClock.elapsedRealtime() - it.at > UNDO_MS) auto = null }
        // 알아서 바꾼 무드등을 3분 안에 사람이 직접 바꿨으면 되돌린 것으로 센다(감사 F77, 다음엔 다시 묻는다). 바꾼 빛은 그대로 둔다
        auto?.takeIf { it.kind == Memory.Kind.JAM }?.let { a ->
            val t = SystemClock.elapsedRealtime()
            if (t - handsAt >= 10_000) { handsAt = t; jam.touchedByHand { hand -> if (hand && auto === a) handUndo() } }
        }
        jam.tick(car)
        together.tick(car)
        // 배너의 [골라 볼게요]가 같이 고르기 화면을 바로 열었으면 묻던 것을 거둔다
        if (asking == Line.PICK && Stage.ui.value.scene == Scene.PICK) closePick()
        // 기억 화면은 세워 둔 동안만(긴 글). 출발하면 닫는다
        if (Stage.ui.value.scene == Scene.MEMO && !car.parked) Stage.go(Scene.HOME)
        // 위치 알림 카드도 세워 둔 동안 읽는 긴 글이라 출발하면 거둔다(정하지 않은 채로 두고 다음 운행에 한 번 더)
        if (asking == Line.GEO && !car.parked) { Prend.log("· 위치 알림 카드 거둠(출발)"); closeSimple() }
        // 위치 알림 카드는 보이는 채로 몇 초가 지나야 건넨 것으로 센다(두 번이면 다시 묻지 않는다). 가려졌거나 배너가 뜨지 않는 동안은 세지 않는다
        if (asking == Line.GEO && geoShown() && ++geoSeenS == GEO_SEEN_S) { GeoShare.delivered(); Prend.log("· 위치 알림 카드: 보이는 채로 ${GEO_SEEN_S}초 → 건넨 것으로 셈") }
        // 쉼터를 경유지로 넣은 뒤 40분 안에 세우면 쪽잠을 권한다. 쉼터에 닿기 전 고속도로 갓길·막힌 차로에 선 것은 쉼터가 아니라
        // 권하지 않고 쉼터에 설 때까지 기다린다(11/08 오류 점검 C2: 그 자리에서 '15분 주무실래요?'가 대피 카드를 덮었다)
        if (restAcceptedAt > 0 && car.parked) {
            val fresh = SystemClock.elapsedRealtime() - restAcceptedAt < 40 * 60_000
            if (fresh && highwayStopped()) { if (!wasParked) Prend.log("· 쪽잠: 고속도로 위에 서서 권하지 않음(쉼터에 서면 권함)") }
            else { if (fresh) queueNap(); restAcceptedAt = 0L }
        }
        // 쉬어서 연속 운전이 0으로 돌아가면 처음부터
        if (minutes < lastMinutes) { restAt = REST_FIRST; restReoffered = false; queue.remove(Line.REST); restSleepy = false; hourAsked = false; queue.remove(Line.HOUR) }
        lastMinutes = minutes
        // 급할 때(E)는 평소의 말과 카드를 모두 거두고 새로 건네지 않는다(별첨 12 §4). 다른 한마디(주차 코치 등)도 멈춘다.
        // 줄에는 쉼터 권하기만 남겨 E가 끝나면 이어 가고, 가족 정보·김서림 같은 물음은 때가 지났으니 버린다
        val e = Stage.ui.value.scene.isE
        if (e && !wasE) {
            speaker.stop()
            val dropped = queue.filter { it != Line.REST }
            if (dropped.isNotEmpty()) { queue.removeAll(dropped); Prend.log("· 급할 때라 줄의 말 버림: ${dropped.joinToString { it.name }}") }
        }
        // E가 끝날 때 한 번 더: E 동안에도 김서림·쉼터 쪽잠 물음은 줄에 든다. 때가 지난 물음을 E 뒤에 꺼내지 않게
        if (!e && wasE) {
            val late = queue.filter { it != Line.REST }
            if (late.isNotEmpty()) { queue.removeAll(late); Prend.log("· 급할 때가 끝나 그동안 줄에 든 말 버림: ${late.joinToString { it.name }}") }
        }
        wasE = e
        if (e) { presenceLine?.let { cancelPresence("급할 때", drop = it != Line.REST) }; if (speakingLine != null || asking != null) quiet(); return }
        val resting = nap.napping || nap.sleeping
        // 대리 운행이면 먼저 건네는 말(쉼터·정체·같이 고르기…)은 줄에 세우지도 않는다
        val daeri = Stage.ui.value.daeri
        // 쉼터를 묻던 카드를 [프렌드]에서 온 확인 카드가 덮었다: 대답 없음으로 1시간 미루지 않고, 카드가 닫히면 다시 묻는다(11/08 사용자 결정)
        if (asking == Line.REST && speakingLine == null && confirmUp()) restCovered()
        if (!daeri && minutes >= restAt && Line.REST !in queue && speakingLine != Line.REST && asking != Line.REST && presenceLine != Line.REST) {
            // 목적지가 15분 안이면 권하지 않는다(도착 10분 전에 "두 시간 넘게 달리셨어요"가 나오지 않게). 길이 늘거나 지나치면 다시 본다
            if (arrivingSoon()) { if (!soonLogged) { soonLogged = true; Prend.log("· 쉼터: 목적지가 15분 안이라 권하지 않음(연속 운전 ${minutes}분)") } }
            else { soonLogged = false; queue.addLast(Line.REST); Prend.log("· 권할 말 줄 세움: 쉼터(연속 운전 ${minutes}분)") }
        }
        // 이 칸(평일·주말 × 시간대)에서 두 번 조용히 가자고 했던 사람의 혼자 운행: 출발하면 먼저 조용히 두고, 편한 때 목소리 없이 카드 한 장
        if (!quietChecked && car.driving && car.speedKmh > 5f) {
            quietChecked = true
            val slot = Memory.slot()
            if (!daeri && alone(car) && !Stage.ui.value.quietRide && !memory.alwaysQuiet && memory.quietLearned(slot)) {
                quietSlot = slot
                Stage.ui.value = Stage.ui.value.copy(quietRide = true)
                queue.remove(Line.QUIET); queue.addLast(Line.QUIET)
                Prend.log("◆ 알아서: 조용히 가기(기억, $slot)")
            }
        }
        // 혼자 한 시간 이어 달리면 "필요한 것 있으세요?"(두 시간 쉼터보다 앞이고, 쉬어서 0이 되면 다시. 동승자가 있으면 대답 창에 사람들끼리의 말이 섞인다).
        // 동승자가 잘래요로 쉬어 깨어 있는 사람이 운전자뿐이면 살린다(카드만). 곁들임 물음 다섯은 먼저 묻기에서 켜 둔 것만(사용자 결정 11/06, 아래 넷도 같다)
        if (memory.asks("HOUR") && (!resting && alone(car) || onlyDriverAwake(car)) && !daeri && !hourAsked && Line.HOUR !in declined && minutes >= HOUR_AT && minutes < restAt && !memory.habits.hour.quiet && !arrivingSoon() &&
            Line.HOUR !in queue && asking != Line.HOUR && speakingLine != Line.HOUR) {
            hourAsked = true
            queue.addLast(Line.HOUR)
            Prend.log("· 권할 말 줄 세움: 한 시간(연속 운전 ${minutes}분)")
        }
        // 달려서 와 P에 넣었는데 낮인데도 차가 어둡다(지하·실내 주차장): 내리기 전에 주차 자리를 확인하라고 한 번
        if (car.parked && !wasParked) {
            if (moved && !spotDone && spotWanted(car)) { queue.remove(Line.SPOT); queue.addLast(Line.SPOT); Prend.log("· 권할 말 줄 세움: 주차 자리(낮인데 어두움)") }
            moved = false
        }
        // 밤에 시동 뒤 3분 안, 조수석이나 뒷자리에 사람이 있으면 대리 운행인지 한 번(주인이 뒷자리에 타는 대리도 있다, 감사 F10 ⑧)
        if (!daeriAsked && !daeri && memory.asks("DAERI") && rideStartAt > 0 && SystemClock.elapsedRealtime() - rideStartAt < DAERI_WINDOW_MS &&
            car.seats.any { (k, v) -> v && k != "ROW_1_LEFT" } && nightHour() && !memory.habits.daeri.quiet) {
            daeriAsked = true
            // 대리 기사에게 사고 뒤·가족 정보 물음을 먼저 하지 않게 맨 앞에(혹시 사고 다음)
            queue.remove(Line.DAERI)
            queue.add(if (queue.firstOrNull() == Line.BUMP) 1 else 0, Line.DAERI)
            Prend.log("· 권할 말 줄 세움: 대리 운행(맨 앞)")
        }
        // 가족 정보를 아직 정하지 않았으면 세워 둔 동안 한 번(처음 설치한 뒤. 대리 기사 앞에서는 묻지 않는다). 위치 알림 카드도 그 곁에 한 번
        if (!daeri && car.parked && baby.shouldAsk()) queueKids()
        if (!daeri && car.parked && GeoShare.shouldAsk()) queueGeo()
        // 세워 두고 화면을 보는 동안 동승자가 있으면 이야기에 비켜 서기를 켤지 한 번(정하기 전에는 마이크를 쓰지 않는다)
        if (!talkAsked && !daeri && car.parked && memory.asks("TALK") && talkAskable()) {
            talkAsked = true
            queue.addLast(Line.TALK)
            Prend.log("· 권할 말 줄 세움: 이야기에 비켜 서기")
        }
        // 오래 막히면 분위기(무드등·잔잔한 곡)를 바꿔 드릴지 한 번 묻는다(바꿀 것이 있을 때만, 물은 뒤 60분은 다시 묻지 않는다)
        if (!resting && !daeri && memory.asks("JAM") && Line.JAM !in queue && Line.JAM !in declined && speakingLine != Line.JAM && asking != Line.JAM && presenceLine != Line.JAM && !memory.habits.jam.quiet && jam.jammed(car) && jam.canHelp()) {
            queue.addLast(Line.JAM)
            Prend.log("· 권할 말 줄 세움: 정체")
        }
        // 경로를 따라가다 막 세웠고, 밥때이고, 같이 탄 사람이 있으면 같이 고를지 한 번 묻는다
        // 위치 보내기를 껐으면 찾을 수 없으니 묻지 않는다(같이 고르기는 동네 이름을 보낸다, ride/GeoShare)
        if (!resting && !daeri && memory.asks("PICK") && GeoShare.on && Line.PICK !in queue && Line.PICK !in declined && speakingLine != Line.PICK && asking != Line.PICK && presenceLine != Line.PICK && !memory.habits.pick.quiet && together.shouldAsk(car)) {
            queue.addLast(Line.PICK)
            Prend.log("· 권할 말 줄 세움: 같이 고르기")
        }
        // 말하는 도중 깜빡이가 켜지면 멈추고 다시 줄 앞에. 통화가 걸려 와도(혹시 사고 물음까지) 멈춘다: 끝나고 대답 듣기가 통화 중에 마이크를 연다
        if (speakingLine != null) { stopSpeaking(car); return }
        // 대답을 기다리는 5초에 후진·깜빡이·통화가 생기면 듣기와 카드를 닫고 줄 앞에(감사 F14)
        if (stopAnswer(car)) return
        // 기척 중이면 2초가 끝날 때까지 기다린다(그사이의 판단은 presenceStep이 0.25초마다 한다)
        if (presenceLine != null) return
        if (asking != null || queue.isEmpty()) return
        // 다른 한마디(쪽잠·초보·기억 알림)가 나오는 중이거나 사람이 [프렌드]로 말하는 중이면 끝날 때까지 기다린다
        if (speaker.speaking || Stage.ui.value.listen != null) return
        // 통화 중이면 줄에 둔 채 끝날 때까지 기다린다(말은 포커스를 받지 못하고, 대답 듣기가 통화에 차임과 마이크를 연다). 포커스를 받지 못한 뒤에도 조금 쉰다
        if (speaker.inCall) { if (swallowed != CALL) { swallowed = CALL; Prend.log("◆ 말 삼킴: ${queue.first().name}($CALL)") }; return }
        if (SystemClock.elapsedRealtime() < heldUntil) return
        // 줄 앞에서부터 보며, 때가 지난 말은 버리고 지금 건넬 수 없는 말은 건너뛰어 뒤의 말을 먼저 건넨다
        for (line in queue.toList()) {
            val drop = dropWhy(line, car, resting)
            if (drop != null) {
                queue.remove(line); comfyDone(line); Prend.log("· ${line.name} 말 버림: $drop")
                if (line == Line.TOILET) toiletGone(drop)
                // 건네다 끊긴 쉼터는 다음 권하기가 멈춰 있다(Int.MAX_VALUE): 버리면 30분 뒤로 되돌린다(아니면 그 연속 운전 내내 다시 서지 못한다)
                if (line == Line.REST && restAt == Int.MAX_VALUE) { restAt = lastMinutes + REST_AGAIN; restSleepy = false }
                continue
            }
            // 확인 카드(Stage.confirm, "Q_")가 떠 있는 동안은 제 말을 꺼내지 않고 줄에 둔다(그 카드를 덮어 대답을 잃지 않게, 검토 11/07 멋대로 판단).
            // 혹시 사고만은 기다리지 않는다(확인 카드는 대답이 없으면 아무것도 바꾸지 않는다)
            if (line !in BUMPS && confirmUp()) { if (swallowed != CONFIRM) { swallowed = CONFIRM; Prend.log("◆ 말 삼킴: ${line.name}($CONFIRM)") }; continue }
            // 고속도로 정차 카드(대피 안내, 길어야 90초)도 덮지 않고 기다린다(11/08 오류 점검 C2: 쪽잠 물음이 그 카드를 덮어 다시 뜨지 않았다)
            if (line !in BUMPS && hstopUp()) { if (swallowed != HSTOP_UP) { swallowed = HSTOP_UP; Prend.log("◆ 말 삼킴: ${line.name}($HSTOP_UP)") }; continue }
            // 먼저 꺼낸 말·카드가 이번 운행 여섯 번에 닿았다(말 차례, 11/03): 그 운행에는 다시 열리지 않으니 물음은 버리고 물은 것으로 친다.
            // 줄에 두면 버리지 않는 한 시간·김서림이 운행 내내 남아 다른 카드를 막는다. 주차 자리도 카드로 돌리지 않고 버린다.
            // 묻지 않고 공조를 바꾸는 김서림·한 시간은 버리지 않는다: 바꾸기는 하고 글 한 줄만(김이 서리는 채로 두지 않게)
            if (line in FLOOR_ASK && floor?.full == true && !selfActs(line, car)) {
                queue.remove(line); comfyDone(line); cardWaitSince.remove(line); expired(line)
                Prend.log("· ${line.name} 말 버림: ${Floor.FIRST_CAP}")
                continue
            }
            // 건너뛰는 동안(P에서의 한 시간 물음·주차 코치 중 등)은 편한 때 기다림 시계를 세지 않는다
            if (waits(line, car)) { if (comfyWaitSince.containsKey(line)) comfyWaitSince[line] = SystemClock.elapsedRealtime(); continue }
            // 편한 때를 5분 넘게 기다린 말은 버린다(전에는 아무 데서나 건네 골목·주차장에서 말이 나왔다, 11/01)
            if (comfyExpired(line, car)) {
                queue.remove(line); comfyDone(line); expired(line)
                Prend.log("· ${line.name} 말 버림: 편한 때가 ${comfyMaxMs / 1000}초 넘게 오지 않음")
                continue
            }
            // 급하지 않은 말은 편한 때(신호 대기·안정 주행·P)를 기다린다. 뒤의 쉼터·혹시 사고는 먼저 건넬 수 있다
            if (waitsComfy(line, car)) continue
            // 목소리 대신 카드로 갈 물음은 달리는 동안(안정 주행) 띄우지 않고 설 때(신호 대기·P)를 기다린다. 5분 안에 서지 않으면 버린다(11/02 노인·초보:
            // 55~75세는 달리며 차 화면을 볼 때 눈을 8초 넘게 더 뗀다, AAA 재단·유타대 2019). 한 시간·김서림·오늘도 조용히는 버리지 않는다.
            // 여섯 번에 닿은 뒤의 알아서 김서림·한 시간은 카드 없이 글 한 줄이라 기다리지 않는다.
            // 오늘도 조용히(늘 카드만)도 말 차례 밖이지만 같은 규칙으로 설 때만 띄운다(검토 11/06: 전에는 이 규칙에서 빠져 시속 42km에서도 떴다).
            // 기다리는 동안 다른 카드를 막지 않고(idle), 신호 대기 없이 P에 닿으면 버린다(dropWhy).
            // 알아서 하는 김서림은 달리는 중이면 카드 없이 글 한 줄이라 설 때를 기다리지 않는다(11/08 오류 점검 C1: 동승자·다른 앱 음악 등
            // 카드로 갈 까닭이 있으면 고속도로에서 첫 정차까지 미루다 P에서 버려, 그 운행 내내 바깥 공기로 바꾸지 않았다)
            if ((line in FLOOR_ASK && floor?.full != true || line == Line.QUIET) && car.speedKmh >= 1f && cardOnly(line) != null &&
                !(line == Line.FOG && selfActs(line, car))) {
                val since = cardWaitSince.getOrPut(line) { SystemClock.elapsedRealtime().also { Prend.log("· ${line.name}: 카드로 갈 말이라 설 때를 기다림") } }
                if (line !in NO_EXPIRE && SystemClock.elapsedRealtime() - since > comfyMaxMs) {
                    queue.remove(line); comfyDone(line); cardWaitSince.remove(line); expired(line)
                    Prend.log("· ${line.name} 말 버림: 카드로 띄울 정차가 ${comfyMaxMs / 1000}초 넘게 오지 않음")
                }
                continue
            }
            cardWaitSince.remove(line)
            // 목소리 포커스를 받지 못해 미룬 말은 신호 대기나 P에서(감사 F94①, 스위치가 켜졌을 때만 든다)
            if (line in deferred && !car.parked && !timing.atLight(car)) continue
            // 접촉 사고 물음은 차가 선 채 문을 열 때라 말할 때를 기다리지 않는다. 대리 운행 물음은 시동 뒤 3분 안에만 물어 출발 직후도 묻는다
            // 화장실은 사람이 먼저 꺼낸 말의 대답이라 출발 직후(3분)에도 건넨다(출발하자마자 "화장실 급해"가 흔하다. 깜빡이·급가속 같은 순간만 피한다)
            val why = if (line in BUMPS) null else blockFor(car, line, starting = true)
            if (why != null) {
                if (swallowed != why) { swallowed = why; Prend.log("◆ 말 삼킴: ${line.name}($why)") }
                // 이야기 틈(20초)만 모자란 비긴급 말이면 뒤의 쉼터·화장실(5초 틈이면 건넨다)을 본다
                if (!urgentLine(line) && queue.any { it != line && urgentLine(it) && blockFor(car, it, starting = true) == null }) continue
                return
            }
            // 말 차례(11/01): 먼저 꺼내는 물음은 한 번 설 때 하나(목소리든 카드든), 앞 말·카드·[프렌드] 뒤 20초. 쉼터·혹시 사고·쪽잠·잘래요는 보지 않는다.
            // 그 자리에서만 쓸모 있는 주차 자리는 이번에 선 동안 이미 말했으면 카드로만.
            // 여섯 번에 닿은 뒤 여기까지 오는 말은 알아서 바꾸는 김서림·한 시간뿐이라(위) 그 까닭으로는 막지 않는다
            val fw = if (line in FLOOR_ASK) floor?.why(Floor.Kind.ASK)?.takeUnless { it == Floor.FIRST_CAP } else null
            if (fw != null) {
                // 주차 자리는 내리기 전 그때뿐이라 기다리지 않고 카드로(다른 말이 나오는 중이면 끝나길 기다린다). 한 운행 여섯 번은 넘지 않는다
                if (line == Line.SPOT && fw != Floor.BUSY && fw != Floor.FIRST_CAP) cardFor = line else continue
            }
            swallowed = null
            queue.remove(line)
            if (line in COMFY_WAIT) timing.comfy(car)?.let { Prend.log("· ${line.name}: 편한 때($it)") }
            if (presenceFirst(line)) startPresence(line, minutes) else deliver(line, minutes)
            return
        }
    }

    /**
     * 편한 때를 기다려야 하는가(감사 F45). 급하지 않은 말만 기다린다. 기다린 지 comfyMaxMs(5분)가 지나도 오지 않으면 버린다(comfyExpired, 11/01:
     * 전에는 전처럼 건네서 골목·주차장에서 말이 나왔다. 별첨 2 §2-1의 '세 때에만 말한다'에 맞춤)
     */
    private fun waitsComfy(line: Line, car: CarState): Boolean {
        if (line !in COMFY_WAIT) return false
        // 줄에서 빠진 말(거절·급할 때·대리 운행으로 버림)의 기다림은 지운다: 나중에 다시 줄에 들 때 처음부터 센다
        val live = { l: Line -> l in queue || l == presenceLine }
        comfyWaitSince.keys.retainAll(live); comfyLogged.retainAll(live); comfyLate.retainAll(live)
        // 출발 직후(V1)·후진 뒤 1분은 일부러 말을 꺼내지 않는 때라 기다림 시계에 세지 않는다
        if (timing.departing(car)) comfyWaitSince[line] = SystemClock.elapsedRealtime() else comfyWaitSince.getOrPut(line) { SystemClock.elapsedRealtime() }
        if (timing.comfy(car) != null) return false
        if (comfyLogged.add(line)) Prend.log("· ${line.name}: 편한 때(신호 대기·안정 주행·P)를 기다림")
        return true
    }

    /**
     * 편한 때를 기다리다 상한(5분)을 넘겼고 지금도 편한 때가 아니다: 버린다. 한 시간 물음·김서림·오늘도 조용히 카드는 버리지 않고 첫 편한 때까지 기다린다
     * (버리면 그 연속 운전에 다시 서지 않거나, 조용히 가기가 알림 없이 켜진 채 남는다. 고속도로 V19도 같은 뜻)
     */
    private fun comfyExpired(line: Line, car: CarState): Boolean =
        line in COMFY_WAIT && line !in NO_EXPIRE && comfyOverdue(line) && timing.comfy(car) == null

    /** 건네지 못하고 버린 말의 뒷정리: 정체·같이 고르기는 물은 것으로 쳐서(60분·3시간 쉬기) 버리자마자 다시 줄에 서지 않게 한다 */
    private fun expired(line: Line) {
        when (line) {
            Line.JAM -> jam.asked()
            Line.PICK -> together.asked()
            Line.SPOT -> spotDone = true
            else -> Unit
        }
    }

    /** 편한 때를 너무 오래 기다렸나 */
    private fun comfyOverdue(line: Line): Boolean =
        comfyWaitSince[line]?.let { SystemClock.elapsedRealtime() - it >= comfyMaxMs } ?: false

    /** 건넸거나 버린 말: 편한 때 기다림을 지운다 */
    private fun comfyDone(line: Line) {
        comfyWaitSince.remove(line); comfyLogged.remove(line); comfyLate.remove(line)
    }

    /** 이 말을 버릴 까닭(때가 지났거나 묻지 않기로 함). 아직 건넬 만하면 null */
    private fun dropWhy(line: Line, car: CarState, resting: Boolean): String? = when {
        // 줄에 선 뒤 먼저 묻기에서 끈 물음은 버린다(시험으로 넣은 것은 그대로)
        offAsk(line) -> "먼저 묻기에서 꺼 둠"
        // 쪽잠은 세워 둔 동안만, 잘래요는 달리는 동안만
        line == Line.NAP && (!car.parked || nap.napping) -> "때가 지남"
        line == Line.SLEEP && (car.parked || nap.sleeping) -> "때가 지남"
        // 김서림은 달리는 동안의 일이라 세우면 버린다. 그사이 바깥 공기로 바뀌었으면(한 시간 바깥 공기·사람이 바꿈) 물을 까닭이 없다
        line == Line.FOG && car.parked -> "세움"
        line == Line.FOG && car.recirc == false -> "바깥 공기로 바뀜"
        // 대리 운행이면 먼저 건네는 말은 하지 않는다(혹시 사고·잘래요만)
        Stage.ui.value.daeri && line in DAERI_QUIET -> "대리 운행"
        // 주차 자리는 내리기 전에만, 한 시간은 쉬는 동안 버린다
        line == Line.SPOT && (!car.parked || (car.doors["ROW_1_LEFT"] ?: 0) > 0) -> "출발했거나 내림"
        // 두 시간 쉼터는 건네기 직전에도 목적지를 본다("졸려"에서 온 물음은 그대로)
        line == Line.REST && !restSleepy && arrivingSoon() -> "목적지가 15분 안"
        // 화장실은 끊겨 줄에 되돌아간 뒤 세웠거나(그 주유소·휴게소에 들어섬) 고른 곳 150m 안을 천천히 지나면(들어서는 중) 버리고, 처음 말한 때부터 3분이 지나도 버린다
        line == Line.TOILET && car.parked && !toiletFromPark -> TOILET_PARKED
        line == Line.TOILET && toiletReached() -> "고른 곳에 닿음"
        line == Line.TOILET && SystemClock.elapsedRealtime() - toiletAt > TOILET_STALE_MS -> TOILET_LATE
        line == Line.HOUR && resting && !onlyDriverAwake(car) -> "쉬는 중"
        // 설 때를 기다리던 한 시간 물음이 두 시간 쉼터 때까지 남았으면 버린다(11/08 오류 점검 C6: 130분이 넘어 '한 시간' 카드가 떴다. 쉼터 권하기가 잇는다)
        line == Line.HOUR && lastMinutes >= restAt -> "쉼터 때가 됨"
        line == Line.HOUR && !forceHour && !alone(car) && !onlyDriverAwake(car) -> "동승자 있음"
        line == Line.QUIET && !Stage.ui.value.quietRide -> "조용히 가기 꺼짐"
        // 오늘도 조용히 카드는 달리는 중 첫 신호 대기에 띄운다. 그 전에 P에 넣었으면(도착) 버린다: 다 와서 '조용히 갈게요'가 뜨지 않게.
        // 조용히 가는 것은 위 막대 '· 조용히'로 이미 보인다(검토 11/06)
        line == Line.QUIET && car.parked -> "도착함"
        line == Line.DAERI && Stage.ui.value.daeri -> "이미 대리 운행"
        // 대리 운행은 시동 뒤 3분 안에만 묻는다: 앞의 물음에 밀려 한참 달린 뒤 "대리 운행이세요?"가 나오지 않게(감사 F10 ①)
        line == Line.DAERI && !forceDaeri && rideStartAt > 0 && SystemClock.elapsedRealtime() - rideStartAt > DAERI_WINDOW_MS -> "시동 뒤 3분 지남"
        line in declined -> "이번 운행에 거절함"
        line == Line.SPOT && spotDone -> "이번 운행에 알림"
        line == Line.TALK && !car.parked -> "출발함"
        // 가족 정보는 세워 둔 동안만 묻는다: 출발하면 이번 운행에는 더 묻지 않는다(줄에 남으면 길 한마디가 운행 내내 막힌다). 그사이 사람이 정했으면 버린다
        line == Line.KIDS && !car.parked -> "출발함"
        line == Line.KIDS && baby.kids != null -> "이미 정함"
        line == Line.GEO && !car.parked -> "출발함"
        line == Line.GEO && GeoShare.decided -> "이미 정함"
        line == Line.BUMPBACK && !car.parked -> "출발함"
        // 그사이 기억이 '묻지 않기'로 바뀌었거나 쪽잠·잘래요가 시작됐으면 버린다
        (line == Line.JAM || line == Line.PICK) && resting -> "쉬는 중"
        line == Line.JAM && memory.habits.jam.quiet || line == Line.PICK && memory.habits.pick.quiet -> "묻지 않기로 함(기억)"
        // 정체는 건네기 직전에 다시 본다(그사이 길이 풀렸거나 세웠으면 버린다)
        line == Line.JAM && !forceJam && !jam.jammed(car) -> "길이 풀렸거나 세움"
        line == Line.PICK && !forcePick && !together.shouldAsk(car) -> "출발했거나 때가 지남"
        line == Line.PICK && !GeoShare.on -> "위치 보내기 꺼짐"
        else -> null
    }

    /** 먼저 묻기에서 꺼 둔 곁들임 물음인가(사용자 결정 11/06). 시험 명령으로 넣은 물음(ask_hour·jam·pick 등)은 막지 않는다 */
    private fun offAsk(line: Line): Boolean = when (line) {
        Line.HOUR -> !forceHour && !memory.asks("HOUR")
        Line.DAERI -> !forceDaeri && !memory.asks("DAERI")
        Line.TALK -> !forceTalk && !memory.asks("TALK")
        Line.JAM -> !forceJam && !memory.asks("JAM")
        Line.PICK -> !forcePick && !memory.asks("PICK")
        else -> false
    }

    /** 이 말은 줄에 둔 채 지금은 건너뛴다 */
    private fun waits(line: Line, car: CarState): Boolean = when {
        // 운전자가 자는 동안·깨우는 동안은 혹시 사고 말고는 묻지 않는다(깬 뒤 3분부터 이어 간다)
        line !in BUMPS && Stage.ui.value.napPhase.let { it == NapPhase.SLEEP || it == NapPhase.WAKE } -> true
        // 주차하는 동안은 운전자가 바쁘고 바퀴 안내가 나온다(끝나면 묻는다). 말로 청한 코치를 켜 둔 채 시속 15km 넘게 달리는 동안은 주차 중이 아니라 미루지 않는다
        // (코치를 5분 켜 두기로 해 졸음 쉼터 물음이 그만큼 밀릴 뻔했다, 11/08 사용자 결정 F5)
        line !in BUMPS && coaching() && car.speedKmh <= 15f -> true
        // 혹시 사고를 물은 뒤 다시 앉는지 보는 동안(10분)은 다른 물음을 미룬다(배너의 "혹시 사고였나요?"를 가리지 않고, 다시 앉으면 바로 물을 수 있게)
        line !in BUMPS && (bumpWatchUntil > 0 || Stage.ui.value.bumpHint) -> true
        // 동승자가 잘래요로 쉬는 동안에도 김서림은 미루지 않는다(검토 11/07: 도착까지 미뤄 김이 서리는 채로 두었다). 알아서 하는 바깥 공기는 목소리가 없고,
        // 먼저 여쭙기로 바뀐 사람에게는 목소리 없이 카드로만 묻는다(cardOnly, 먼저 말 걸지 않기 별첨 2 V9)
        // 세워 둔 동안은 이미 쉬는 중이라 쉼터를 권하지 않는다(P로 15분 쉬면 운전 시간과 함께 비운다)
        line == Line.REST && car.parked -> true
        // 한 시간 물음은 달리는 동안(세우면 기다렸다가 다시 달릴 때)
        line == Line.HOUR && car.parked -> true
        // 처음 켤 때는 허락 까닭 판을 먼저 끝낸다(11/07 시험: 판이 4초 뜬 뒤 가족 정보 카드가 덮어 판·카드·판이 오락가락했다)
        line == Line.KIDS && Stage.ui.value.let { it.visible && it.permPanel } -> true
        // 위치 알림 카드는 보일 때만(첫 화면이 보이고 허락 판이 없거나, 화면이 닫혀 있고 배너가 뜰 때). 기억 화면·허락 판 뒤·알림 허락 없는 닫힌 화면이면 기다린다
        line == Line.GEO && !geoShown() -> true.also { if (!geoWaitLogged) { geoWaitLogged = true; Prend.log("· GEO: 카드가 보이지 않아 기다림(첫 화면·배너가 뜰 때)") } }
        // 밤의 대리 운행 물음이 줄에 있으면 대리 운행일 때 하지 않을 말(사고 뒤 등)은 그 대답을 기다린다
        line in DAERI_QUIET && (Line.DAERI in queue || asking == Line.DAERI || speakingLine == Line.DAERI) -> true
        // 초보 운전이면 정체·같이 고르기는 차가 설 때까지 미룬다(달리는 동안 말 줄이기)
        Stage.ui.value.novice && (line == Line.JAM || line == Line.PICK) && car.speedKmh >= 1f -> true
        else -> false
    }

    /**
     * 건넬 말도, 묻는 말도 없다(초보 운전의 한마디·길 한마디는 이때만). 편한 때를 기다리며 줄에 서 있기만 한 말은 지금 나올 말이 아니라 막지 않는다:
     * 그 기다림은 몇 분까지 가서, 막으면 회전교차로·하이패스처럼 그 자리에서만 쓸모 있는 한마디가 버려진다.
     * 세워 둔 동안 다시 달릴 때를 기다리는 한 시간·쉼터 물음도 지금 나올 말이 아니다(P에서 차 돌봄·골목 목적지 카드가 막히지 않게).
     * 설 때를 기다리는 오늘도 조용히 카드(달리는 중이거나 신호 대기가 아닌 정차)와 보일 때를 기다리는 위치 알림 카드도 그렇다
     * (검토 11/06: 큰길 신호 대기가 없는 길이면 조용히 카드가 운행 내내 우회전·운전 도움 카드를 막았다)
     */
    val idle get() = speakingLine == null && asking == null && presenceLine == null && !toiletFinding &&
        queue.all {
            comfyHeld(it) || (it == Line.HOUR || it == Line.REST) && CarLink.state.parked ||
                it == Line.QUIET && (CarLink.state.speedKmh >= 1f || timing.comfy(CarLink.state) == null) || it == Line.GEO && !geoShown() ||
                // 달리는 동안 설 때를 기다리는 카드(한 시간·김서림 등)도 지금 나올 말이 아니다(11/08 오류 점검 C6: 신호 대기 없는 길에서
                // 첫 정차까지 회전교차로·주유구 한마디를 막았다)
                it in cardWaitSince && CarLink.state.speedKmh >= 1f
        }

    /** 혹시 사고를 물은 뒤 지켜보는 중인가(그동안 차 돌봄·골목 목적지 카드도 띄우지 않는다) */
    // 혹시 사고를 줄에 세웠거나 묻는 중인 동안도 지켜보는 것으로 본다(고속도로 정차 카드가 그 틈에 끼어들지 않게, 검토 11/05)
    val watchingBump get() = bumpWatchUntil > 0 || Stage.ui.value.bumpHint || Line.BUMP in queue || asking == Line.BUMP || speakingLine == Line.BUMP

    /** 편한 때를 기다리는 중이라 지금은 나오지 않을 말인가(이미 기다리기 시작했고, 상한 전이고, 지금 편한 때가 아님) */
    private fun comfyHeld(line: Line): Boolean =
        line in COMFY_WAIT && comfyWaitSince.containsKey(line) && !comfyOverdue(line) && timing.comfy(CarLink.state) == null

    /** 묻던 것을 거둔다(접촉 사고처럼 더 급한 말이 끼어들 때) */
    private fun closeAny() {
        when (asking ?: speakingLine) {
            Line.REST -> { restAt = if (restSleepy) sleepyAgain() else lastMinutes + REST_AGAIN; restSleepy = false; closeRest() }
            Line.TOILET -> { closeToilet(); toiletPick = null }
            Line.JAM -> closeJam()
            Line.PICK -> closePick()
            Line.NAP, Line.KIDS, Line.BUMP, Line.BUMPBACK, Line.AFTER, Line.SLEEP, Line.FOG, Line.HOUR, Line.SPOT, Line.DAERI, Line.TALK, Line.QUIET, Line.GEO -> closeSimple()
            null -> Unit
        }
    }

    // ── 기억대로 알아서: 묻지 않고 하고 알린다 ──

    /** "길이 많이 막히네요. 오늘도 분위기 바꿔 둘게요"(무드등과 잔잔한 곡, ride/JamHelp.accept) */
    private fun autoJam() {
        Prend.log("◆ 알아서: 정체 챙김(기억)")
        speakingLine = Line.JAM
        asking = Line.JAM
        forceJam = false
        jam.asked()
        auto = Auto(Memory.Kind.JAM, SystemClock.elapsedRealtime())
        // 카드 글은 실제로 한 일대로(검토 11/07: "공기 바꿔 뒀어요"라고 해서 공조가 바뀐 줄 알았다)
        Stage.ask(Ask("AUTO", "분위기 바꿔 뒀어요", "무드등과 잔잔한 곡 · 막힐 땐 늘 바꾸셔서 이번엔 묻지 않았어요", "좋아요", "되돌리기"))
        jam.accept { Prend.log("· 정체(알아서): $it") }
        // 이미 해 둔 일을 알리는 말이라 끊겨도 다시 하지 않는다
        say("voice/auto_jam.wav", "알아서 정체", requeue = false) { answerAuto(it, byButton = false) }
    }

    /** "한 시간 됐어요. 바깥 공기 넣어 둘게요"(한 시간 물음에서 바깥 공기를 세 번 연달아 고른 사람). 3분 안에 되돌리면 다음엔 다시 묻는다 */
    private fun autoHourAir(minutes: Int) {
        Prend.log("◆ 알아서: 한 시간 바깥 공기(기억, 연속 운전 ${minutes}분)")
        hourAsked = true
        auto = Auto(Memory.Kind.HOUR, SystemClock.elapsedRealtime())
        CarLink.hubOrNull?.freshAir { ok -> if (!ok) Prend.log("· 한 시간 바깥 공기: 바꾸지 못함") }
        // 먼저 꺼낸 말·카드가 이번 운행 여섯 번에 닿았으면 말·카드 없이 글 한 줄만(3분 안에 "되돌려"면 되돌린다)
        if (floor?.full == true) {
            Prend.log("· 알아서 한 시간: ${Floor.FIRST_CAP}라 글 한 줄만")
            speakingLine = null; asking = null
            Stage.toast("한 시간 달리셔서 바깥 공기를 넣어 뒀어요 · “되돌려”라고 하시면 돌려요", 5_000)
            return
        }
        Stage.ask(Ask("HAUTO","바깥 공기 넣어 뒀어요", "한 시간이 되면 늘 바깥 공기를 고르셔서 이번엔 묻지 않았어요", "좋아요", "되돌리기"))
        // 이미 해 둔 일을 알리는 말이라 끊겨도 다시 하지 않는다(잘래요·조용히 가는 중이면 카드만)
        say("voice/hour_air_auto.wav", "알아서 한 시간", requeue = false) { answerHourAuto(it, byButton = false) }
    }

    /**
     * 묻지 않고 공조를 바꾸는 말인가(알아서 김서림·알아서 한 시간, deliver와 같은 조건). 먼저 꺼낸 말·카드가 한 운행 여섯 번에 닿아도
     * 바꾸기는 하고 글 한 줄만 남긴다(목소리·카드·기척 없이, 세지 않는다)
     */
    private fun selfActs(line: Line, car: CarState): Boolean = CarLink.hubOrNull != null &&
        (line == Line.FOG && !memory.fogAsk || line == Line.HOUR && memory.habits.hour.auto && car.recirc == true)

    private fun answerHourAuto(text: String, byButton: Boolean) {
        val s = text.replace(" ", "")
        val undo = undoSaid(text)
        Prend.log("· 알아서 한 시간 대답(${if (byButton) "버튼" else "말 ${text.length}자"}) → ${if (undo) "되돌리기" else if (s.isBlank()) "없음" else "그대로"}")
        speaker.stop()
        if (s.isBlank() && !byButton) { later(15_000) { if (asking == Line.HOUR) closeSimple() }; return }
        closeSimple()
        if (undo) undoAuto("말·버튼")
    }

    /** 먼저 조용히 간 운행의 카드: [평소대로]면 조용히 가기를 끄고 그 칸의 [평소대로]를 센다(두 번이면 잊는다) */
    private fun answerQuiet(text: String, byButton: Boolean) {
        val s = text.replace(" ", "")
        val usual = listOf("평소", "아니", "싫어", "말해").any { it in s }
        Prend.log("· 오늘도 조용히 대답(${if (byButton) "버튼" else "말"}) → ${if (usual) "평소대로" else if (s.isBlank()) "없음" else "그대로"}")
        closeSimple()
        if (!usual) return
        setQuietRide(false)
        Stage.toast(memory.quietUsual(quietSlot ?: Memory.slot())?.text ?: "평소대로 할게요", 3_000)
    }

    /** 깨어 있는 사람이 운전자뿐이다: 동승자가 잘래요로 쉬고, 운전석 말고 앉은 자리가 쉬는 자리 하나뿐(대리 운행·운전자 쪽잠이면 아니다) */
    private fun onlyDriverAwake(car: CarState): Boolean {
        if (!nap.sleeping || nap.napping || Stage.ui.value.daeri) return false
        val seat = nap.sleepSeatName ?: return false
        return car.seats.none { (k, v) -> v && k != "ROW_1_LEFT" && k != seat }
    }

    /**
     * 알아서 해 둔 일(정체·한 시간 바깥 공기·김서림)을 되돌리라는 말. [되돌리기] 단추와 이 분명한 말만 되돌린다(검토 11/07 멋대로 판단:
     * 좋다는 뜻의 "응, 괜찮아"·"됐어"를 되돌리라는 말로 읽어 원래대로 돌리고 배운 버릇까지 지웠다). 낱말 앞자리에서만 찾고 "안 꺼도 돼"·"끄지 마"는 뺀다
     */
    private val UNDO_SAID = listOf("되돌", "원래대로", "원래로", "돌려", "꺼", "끄", "취소")
    private fun undoSaid(text: String) = Answer.has(text, UNDO_SAID)

    /** 알아서 한 일에 돌아온 말·버튼. 되돌리라는 말이면 되돌리고 다음엔 다시 묻는다("괜찮아"·"좋아"는 그대로 둔다). 말이 없으면 카드를 15초 남긴다 */
    private fun answerAuto(text: String, byButton: Boolean) {
        val s = text.replace(" ", "")
        val undo = undoSaid(text)
        Prend.log("· 알아서 한 일 대답(${if (byButton) "버튼" else "말 ${text.length}자"}) → ${if (undo) "되돌리기" else if (s.isBlank()) "없음" else "그대로"}")
        speaker.stop()
        if (s.isBlank() && !byButton) { later(15_000) { if (asking == Line.JAM) closeJam() }; return }
        closeJam()
        if (undo) undoAuto("말·버튼")
    }

    /** 알아서 한 일을 되돌린다: 무드등과 PREND가 튼 곡을 원래대로, 다음엔 다시 묻는다. 3분이 지났거나 한 일이 없으면 false */
    private fun undoAuto(why: String): Boolean {
        val a = auto ?: return false
        auto = null
        Prend.log("◆ 알아서 한 일 되돌림($why)")
        val l = memory.undoAuto(a.kind)
        if (a.kind == Memory.Kind.HOUR) {
            val hub = CarLink.hubOrNull
            if (hub == null) Stage.toast(l.text, 3_000)
            else hub.setRecirc(true) { ok -> Stage.toast("${if (ok) "안쪽 공기로 되돌렸어요" else "되돌리지 못했어요. 공조의 내기 순환 버튼을 눌러 주세요"} · ${l.text}", 4_000) }
        } else jam.undo { msg -> Stage.toast("$msg · ${l.text}", 4_000) }
        return true
    }

    /**
     * [프렌드] "되돌려"·"원래대로"(감사 F77 ②): 알림 카드가 떠 있으면 그 [되돌리기]와 같고, 카드가 닫힌 뒤에도 알아서 한 지 3분 안이면 되돌린다
     */
    fun undo() {
        if (asking == Line.JAM && Stage.ui.value.ask?.id == "AUTO") { answerAuto("되돌려", byButton = true); return }
        if (asking == Line.HOUR && Stage.ui.value.ask?.id == "HAUTO") { answerHourAuto("되돌려", byButton = true); return }
        if (asking == Line.FOG && fogAuto && Stage.ui.value.ask?.id == "FOG") { answerFog("되돌려", byButton = false); return }
        // 말로 켠 앞유리 성에 제거도 3분 안이면 끈다(검토 11/07 멋대로 판단, ride/Coach)
        if (Coach.undoDefrost?.invoke() == true) return
        if (fogAutoAt > 0 && SystemClock.elapsedRealtime() - fogAutoAt < 3 * 60_000) { undoFog("[프렌드]"); return }
        if (!undoAuto("[프렌드]")) Stage.toast("지금은 되돌릴 일이 없어요", 3_000)
    }

    /** 알아서 바꾼 무드등을 사람이 3분 안에 직접 바꿨다: 되돌린 것으로 세고(다음엔 다시 묻는다) 사람이 고른 빛은 그대로 둔다 */
    private fun handUndo() {
        val a = auto ?: return
        auto = null
        Prend.log("◆ 알아서 바꾼 무드등을 사람이 바꿈 → 되돌림으로 셈")
        jam.keepHands()
        if (asking == Line.JAM && Stage.ui.value.ask?.id == "AUTO") { speaker.stop(); Stage.endListen(); closeJam() }
        val l = memory.undoAuto(a.kind)
        Stage.toast("무드등을 직접 바꾸셨네요 · ${l.text}", 4_000)
    }

    /**
     * 기억이 바뀌었으면 글(알림)로만 알린다. 앱이 배운 것을 목소리로 한 문장 더 하는 것은 생색에 가깝고, 사람이 막 대답한 뒤라 말이 줄줄이 이어진다
     * (11/01 말 줄이기. 기억 화면에도 남는다)
     */
    private fun tell(l: Learned?) {
        l ?: return
        Stage.toast(l.text, 4_000)
    }

    private fun speakLine(asset: String, label: String = "기억") {
        runCatching { speaker.speak(asset, AudioAttributes.USAGE_ASSISTANT, label) }.onFailure { Prend.log("✗ $label 한마디 틀기 실패 $it") }
    }

    // ── 건네기 ──

    private fun deliver(line: Line, minutes: Int) {
        speakingLine = line
        asking = line
        askVoiced = false
        reasked = null
        reaskAnswer = null
        comfyDone(line)
        deferred.remove(line)
        when (line) {
            Line.PICK -> {
                Prend.log("◆ 말하기: 같이 고르기")
                forcePick = false
                together.asked()
                // 배너의 [골라 볼게요]는 같이 고르기 화면을 바로 연다(서비스를 거치면 안드로이드가 화면 열기를 막는다)
                Stage.ask(Ask("PICK", "같이 먹을 곳 골라 볼까요?", "가는 길에 들를 만한 곳을 찾아 드려요", "골라 볼게요", "괜찮아요", opens = Scene.PICK))
                say("voice/pick_ask.wav", "같이 고르기") { answerPick(it, byButton = false) }
            }
            Line.JAM -> {
                if (memory.habits.jam.auto) { autoJam(); return }
                Prend.log("◆ 말하기: 정체 챙김")
                forceJam = false
                jam.asked()
                Stage.ask(Ask("JAM", "길이 많이 막히네요", "차 안 분위기를 좀 바꿔 드릴까요?", "바꿔 주세요", "괜찮아요"))
                say("voice/jam.wav", "정체") { answerJam(it, byButton = false) }
            }
            Line.KIDS -> {
                Prend.log("◆ 말하기: 가족 정보(처음 한 번)")
                baby.delivered()
                Stage.ask(Ask("KIDS", "가족 중에 차에 함께 타는 아이가 있나요?", "사고가 나면 뒷좌석을 살피라고 알려 드려요", "네, 있어요", "아니요"))
                say("voice/kids_ask.wav", "가족 정보") { answerKids(it, byButton = false) }
            }
            Line.GEO -> {
                Prend.log("◆ 카드: 위치 알림(처음 한 번)")
                // 건넨 수는 보이는 채로 몇 초가 지난 뒤 tick이 센다(보이지 않은 채 30초가 지나면 세지 않는다)
                geoSeenS = 0; geoWaitLogged = false
                // 제목은 배너에도 그대로 실리므로 줄을 나누지 않는다(카드에서는 폭에 맞춰 두 줄로 접힌다)
                Stage.ask(Ask("GEO", "골목·큰길을 가리고 병원·화장실·날씨·가게를 찾을 때만 위치를 서버로 보내요", "저장하지 않아요", "괜찮아요", "보내지 않기"))
                // 목소리 없이 카드만 30초(대답이 없으면 정하지 않은 채로 두고 다음 운행에 한 번 더). 말 차례에는 세지 않는다
                say("", "위치 알림", requeue = false) { }
            }
            Line.BUMP -> {
                // 고속도로면 묻기 전에 한국도로공사 대피 문장부터(차 뒤에 서 있다 2차 사고가 나지 않게. 같은 공식 문장 e_hw_2를 앞에 이은 녹음)
                val hw = onHighway()
                Prend.log("◆ 말하기: 혹시 사고${if (hw) "(고속도로: 대피 문장 먼저)" else ""}")
                // 대답 없이 밖에 나갔다 다시 앉는지는 물을 때부터 본다(묻는 동안 닫은 문도 센다)
                bumpWatchUntil = SystemClock.elapsedRealtime() + BUMP_WATCH_MS
                bumpSawEmpty = false; bumpCloses = 0
                Stage.ask(Ask("BUMP", "혹시 사고 나셨어요?", if (hw) "차 뒤로 가지 말고 가드레일 밖으로 나가세요" else "사고 났을 때 할 일을 차례로 알려 드려요", "사고가 났어요", "아니에요", opens = Scene.ACCIDENT))
                say(if (hw) "voice/e_bump_hw.wav" else "voice/e_bump.wav", "혹시 사고") { answerBump(it, byButton = false) }
            }
            Line.BUMPBACK -> {
                Prend.log("◆ 말하기: 혹시 사고 다시(운전석에 다시 앉음)")
                if (Stage.ui.value.bumpHint) Stage.ui.value = Stage.ui.value.copy(bumpHint = false)
                Stage.ask(Ask("BUMPBACK", "확인해 보셨어요?", "사고였으면 할 일을 차례로 알려 드려요", "사고였어요", "괜찮아요", opens = Scene.ACCIDENT))
                say("voice/e_bump_back.wav", "혹시 사고 다시") { answerBumpBack(it, byButton = false) }
            }
            Line.AFTER -> {
                Prend.log("◆ 말하기: 사고 뒤 한 번")
                after.asked()
                // 한 번에 하나만 묻는다(검토 11/07 멋대로 판단: 몸과 보험을 함께 물어 "네, 괜찮아요"를 '보험까지 다 함'으로 받아 메모를 지웠다)
                Stage.ask(Ask("AFTER", "지난번 사고, 보험 접수는 끝내셨어요?", "사고 메모와 보험사 번호는 첫 화면에 있어요", "다 했어요", "아직이요"))
                say("voice/e_after.wav", "사고 뒤") { answerAfter(it, byButton = false) }
            }
            Line.NAP -> {
                val min = memory.habits.napMin ?: 15
                Prend.log("◆ 말하기: 쪽잠 ${min}분")
                Stage.ask(Ask("NAP", "${min}분 주무실래요?", "때 되면 빛과 바람으로 깨워 드려요", "잘게요", "괜찮아요"))
                say("voice/nap_ask.wav", "쪽잠") { answerNap(it, byButton = false) }
            }
            Line.FOG -> {
                // 11/02: 묻지 않고 바깥 공기로 바꾸고 알린다(비 오는 길에서 물음에 답하게 하면 노인에게 판단을 하나 더 얹는다).
                // [되돌리기]를 한 번 고른 사람에게는 다음부터 묻는다. 공조를 못 부르면(차 연결 없음) 전처럼 묻는다
                val hub = CarLink.hubOrNull
                if (!memory.fogAsk && hub != null) {
                    fogAuto = true
                    Prend.log("◆ 알아서: 김서림 → 바깥 공기")
                    hub.freshAir { ok -> if (!ok) Prend.log("· 김서림 바깥 공기: 바꾸지 못함") }
                    fogAutoAt = SystemClock.elapsedRealtime()
                    // 먼저 꺼낸 말·카드가 이번 운행 여섯 번에 닿았으면 세지 않고 글 한 줄만(바꾸기는 한다)
                    val full = floor?.full == true
                    if (!full) floor?.shown("알아서 김서림")
                    presenceOff("알아서 김서림")
                    // 달리는 중이면 카드 대신 멈춘 글 한 줄(달리며 카드를 읽지 않게), 서 있으면 [되돌리기] 카드. 목소리는 없다.
                    // 글 한 줄이면 하던 말이 없다: 말하는 중으로 남기면 줄이 멈춘다
                    if (full || lastCar.speedKmh >= 1f) {
                        Stage.toast("김이 서리지 않게 바깥 공기로 바꿨어요 · “되돌려”라고 하시면 돌려요", 5_000)
                        speakingLine = null
                        asking = null
                    } else {
                        Stage.ask(Ask("FOG", "김이 서리지 않게 바깥 공기로 바꿨어요", "비나 추위에 안쪽 공기만 오래 돌고 있었어요", "좋아요", "되돌리기"))
                        later(30_000) { if (asking == Line.FOG) { Prend.log("· 알아서 김서림: 30초, 카드 거둠"); closeSimple() } }
                    }
                    return
                }
                fogAuto = false
                Prend.log("◆ 말하기: 김서림")
                Stage.ask(Ask("FOG", "앞유리에 김이 서릴 수 있어요", "안쪽 공기만 오래 돌리고 있어요", "바깥 공기로", "괜찮아요"))
                say("voice/fog_ask.wav", "김서림") { answerFog(it, byButton = false) }
            }
            Line.SLEEP -> {
                Prend.log("◆ 말하기: 잘래요(등받이)")
                Stage.ask(Ask("SLEEP", "주무시게요?", "도착 전에 깨워 드릴게요", "네", "아니요"))
                say("voice/sleep_ask.wav", "잘래요") { answerSleep(it, byButton = false) }
            }
            Line.HOUR -> {
                // 바깥 공기를 세 번 연달아 고른 사람: 안쪽 공기만 돌고 있으면 묻지 않고 바깥 공기를 넣어 두고 알린다
                if (memory.habits.hour.auto && lastCar.recirc == true && CarLink.hubOrNull != null) { autoHourAir(minutes); return }
                hourOffer = offer(lastCar)
                val sleeper = Stage.ui.value.sleeper?.takeIf { nap.sleeping }
                Prend.log("◆ 말하기: 한 시간(연속 운전 ${minutes}분, 첫 버튼 ${hourOffer.name}${if (sleeper != null) ", $sleeper 쉬는 중이라 카드만" else ""})")
                // 배너에서는 첫 버튼 대신 [쉬어 갈 곳](화면을 열지 않은 운행은 버튼으로 받는다, 별첨 2 §5-2). 누르면 쉼터 카드로 잇는다
                Stage.ask(Ask("HOUR", "한 시간 달리셨어요", if (sleeper != null) "${sleeper}가 쉬는 동안, 필요한 것 있으세요?" else "필요한 것 있으세요? 바깥 공기·열선·음악", hourOffer.label, "괜찮아요", alt = "쉬어 갈 곳"))
                say("voice/hour_ask.wav", "한 시간") { answerHour(it, byButton = false) }
            }
            Line.QUIET -> {
                Prend.log("◆ 말하기: 오늘도 조용히(카드만)")
                Stage.ask(Ask("QUIET", "오늘도 조용히 갈게요", "이 시간엔 조용히 가자고 하셔서요 · 쉼터와 사고만 목소리로", "좋아요", "평소대로"))
                // 조용히 가는 중이라 목소리 없이 카드만 30초(대답이 없으면 그대로 조용히)
                say("", "오늘도 조용히", requeue = false) { answerQuiet(it, byButton = false) }
            }
            Line.SPOT -> {
                Prend.log("◆ 말하기: 주차 자리")
                spotDone = true
                Stage.ask(Ask("SPOT", "내리기 전에 주차 자리를 확인해 두세요", "층과 기둥 번호를 찍어 두면 찾기 쉬워요", "알겠어요", "그만 알려요"))
                // 알리기만 하는 말이라 대답을 듣지 않는다(그 5초 동안 다른 음악을 멈추지 않게). 버튼으로는 받는다
                say("voice/spot.wav", "주차 자리", listen = false) { answerSpot(it, byButton = false) }
            }
            Line.DAERI -> {
                Prend.log("◆ 말하기: 대리 운행")
                forceDaeri = false
                Stage.ask(Ask("DAERI", "대리 운행이세요?", "그러면 먼저 말 걸지 않고 조용히 모실게요", "네, 대리예요", "아니요"))
                say("voice/daeri_ask.wav", "대리 운행") { answerDaeri(it, byButton = false) }
            }
            Line.TALK -> {
                Prend.log("◆ 말하기: 이야기에 비켜 서기")
                forceTalk = false
                // 둘째 줄에 이야기 중엔 PREND도 말을 미룬다는 것을(사용자 결정 11/06: 곡을 틀지 않는 부모가 음악 이야기로만 읽고 넘기지 않게).
                // 제목에 바람도 적는다(검토 11/07: 켜면 바람도 한 단 낮추는데 음악만 적혀 있었다)
                Stage.ask(Ask("TALK", "이야기할 땐 음악과 바람이 비켜 설까요?", "말소리가 있는지만 재고 저장하지 않아요 · 이야기 중엔 저도 말을 미뤄요", "켜 둘게요", "괜찮아요"))
                say("voice/talk_ask.wav", "이야기 비켜 서기") { answerTalk(it, byButton = false) }
            }
            Line.TOILET -> {
                val p = toiletPick
                // 줄에서 다시 나온 말이면(끊겼거나 기다렸다) 그사이 고른 곳이 뒤로 갔을 수 있다: 지금 자리에서 다시 고른다
                if (p == null || SystemClock.elapsedRealtime() - toiletPickedAt > TOILET_REPICK_MS) {
                    speakingLine = null; asking = null
                    Prend.log("· 화장실: ${if (p == null) "고른 곳 없음" else "줄에서 다시 나옴"} → 지금 자리에서 다시 고름")
                    findToilet(again = true)
                    return
                }
                // 다른 화면(기억·같이 고르기·쉼터 카드)을 보던 중이면 카드가 보이게 첫 화면으로
                if (Stage.ui.value.scene != Scene.HOME) Stage.go(Scene.HOME)
                Prend.log("◆ 말하기: 화장실 권하기(${p.kind.name}${if (p.highway) ", 고속도로" else ""}, 확인받고 넣음)")
                Stage.ask(Ask("TOILET", "가까운 화장실을 경유지로 넣을까요?", toiletLine(p), "경유지로", "괜찮아요"))
                say("voice/toilet_ask.wav", "화장실") { answerToilet(it, byButton = false) }
            }
            Line.REST -> {
                // 대답이 올 때까지 다음 권하기는 미룬다(대답에 따라 다시 정한다)
                restAt = Int.MAX_VALUE
                restAskWas = restReoffered to restSleepy
                if (restSleepy) {
                    // "졸려"에서 온 물음: 숫자 없는 말로 권하고 확인받은 뒤 넣는다. 권할 쉼터가 없으면 카드 없이 세우고 쉬자고 권한다
                    Prend.log("◆ 말하기: 졸려 → 쉼터 권하기(확인받고 넣음)")
                    if (choose() == null) {
                        restSleepy = false
                        // 두 시간 권하기가 이미 밀려 있었으면 방금 권한 것이 그 몫이다(바로 또 묻지 않게 30분 뒤)
                        restAt = if (restBefore > lastMinutes) restBefore else lastMinutes + REST_AGAIN
                        closeRest()
                        restNone()
                    } else {
                        Stage.go(Scene.DRIVE)
                        // 화면은 달리는 동안의 쉼터 카드("잠깐 쉬어 가요"), 화면이 안 보이면 배너가 같은 질문을 띄운다
                        Stage.ask(Ask("REST", "가까운 쉼터를 경유지로 넣을까요?", Stage.ui.value.restLine ?: "앞쪽 쉼터", "경유지로", "괜찮아요"))
                        say("voice/rest_ask.wav", "졸려 쉼터") { answerRest(it, byButton = false) }
                    }
                } else {
                    Prend.log("◆ 말하기: 쉼터 권하기(연속 운전 ${minutes}분)")
                    choose()
                    Stage.go(Scene.DRIVE)
                    // PREND 화면이 보이지 않으면 배너가 같은 질문을 띄운다(31자 이하 한 줄).
                    // '졸려'를 넘긴 뒤 다시 권할 때는 두 시간이 안 됐을 수 있다: 그때는 시간을 말하지 않는다(11/04 사람 시험)
                    val twoHours = minutes >= REST_FIRST
                    Stage.ask(Ask("REST", if (twoHours) "두 시간 넘게 달리셨어요" else "잠깐 쉬어 가실래요?", Stage.ui.value.restLine ?: "앞쪽 졸음쉼터에서 쉬어 가실래요?", "경유지로", "괜찮아요"))
                    say(if (twoHours) "voice/rest_2h.wav" else "voice/rest_ask.wav", "쉼터") { answerRest(it, byButton = false) }
                }
            }
        }
    }

    /**
     * 목소리로 건네고, 끝나면 대답을 5초(넉넉히 기다리기면 8초) 듣는다. 그동안 다른 음악은 멈춘다(시험 49-2). 어떤 경우에도 30초 뒤엔 거둔다.
     * 대답을 알아듣지 못하면 한 번만 "'응'이나 '괜찮아'로 말씀해 주세요"라고 하고 다시 듣는다(unclear).
     * 말하는 도중 다른 한마디(주차 코치·쪽잠 등)나 [프렌드]에 끊기면 카드를 거두고 줄 앞에 되돌린다(requeue).
     * listen이 false면(알리기만 하는 말) 대답을 듣지 않고 버튼만 받는다. 다른 앱 음악 위에서는 말 앞에 0.5초 알림음을 내고 그 뒤에 말한다
     */
    private fun say(asset: String, label: String, requeue: Boolean = true, listen: Boolean = true, answer: (String) -> Unit) {
        val line = asking
        requeueAsk = requeue
        // 조용히 가는 운행·다른 앱 음악 위: 목소리와 대답 듣기 없이 카드(화면이 안 보이면 배너)만 30초 둔다. 버튼으로 대답할 수 있다
        val card = line?.let { cardOnly(it) }
        cardFor = null
        askVoiced = card == null
        if (line != null && card != null) {
            speakingLine = null
            // 카드도 한 번 설 때 하나(목소리 물음 수에는 세지 않는다)
            if (line in FLOOR_ASK) floor?.shown(line.name)
            Prend.log("· ${line.name}: $card → 목소리 없이 카드만")
            later(30_000) { if (asking == line) { Prend.log("· ${line.name}: 대답 없이 30초, 거둠"); if (requeue) decline(line, "대답 없음"); noAnswer(line); closeAny() } }
            return
        }
        // 먼저 꺼낸 물음의 목소리가 나간다: 말 차례에 센다(한 번 설 때 하나·30분 간격·한 운행 상한)
        if (line != null && line in FLOOR_ASK) floor?.spoke(Floor.Kind.ASK, line.name)
        // 다른 앱 음악 위에 얹는 말 앞의 짧은 알림음(별첨 4 §5 ①): 에뮬레이터·차가 음악을 낮추지 않아도 말이 온다는 걸 먼저 알린다
        if (line != null && jam.otherMusicOn()) {
            runCatching { Chime.alert() }
            Prend.log("◆ 다른 앱 음악 위: 말 앞 알림음(${line.name})")
            main.postDelayed({ if (speakingLine == line && asking == line) speakNow(line, asset, label, requeue, listen, answer, late = true) }, ALERT_GAP_MS)
            return
        }
        speakNow(line, asset, label, requeue, listen, answer, late = false)
    }

    /** say의 목소리 부분. late면 알림음 뒤라 그사이 말할 때가 닫혔는지(깜빡이·후진·통화) 한 번 더 본다 */
    private fun speakNow(line: Line?, asset: String, label: String, requeue: Boolean, listen: Boolean, answer: (String) -> Unit, late: Boolean) {
        if (late && line != null) {
            val car = CarLink.state
            val why = if (speaker.inCall) CALL else if (line in BUMPS) null else blockFor(car, line, starting = true)
            if (why != null) {
                Prend.log("◆ 알림음 뒤 말 멈춤($why): ${line.name}")
                if (requeue) putBack(line, why) else { speakingLine = null; presenceOff("말 멈춤"); answer("") }
                return
            }
        }
        // 말이 끝나면 기척의 빛과 점을 거두고 대답을 듣는다(알리기만 하는 말은 듣지 않고 버튼만)
        val after = { speakingLine = null; line?.let { cutOnce.remove(it) }; presenceOff("말 끝"); if (listen) Stage.call(answer = answer, windowMs = Stage.answerMs) else answer("") }
        val res = runCatching {
            // 다른 음악을 멈춰 두는 시간은 대답 창과 같게(넉넉히 기다리기면 8초)
            speaker.speak(asset, AudioAttributes.USAGE_ASSISTANT, label, answerMs = if (listen) Stage.answerMs else 0, onCut = { line?.let { cutOff(it, requeue) } }) { after() }
        }.onFailure { Prend.log("✗ 한마디($label) 틀기 실패 $it") }
        val ok = res.getOrDefault(false)
        // 포커스를 받지 못했다(통화가 막 걸려 옴 등): 대답 듣기를 열지 않는다(통화에 차임·마이크, 쉼터는 '대답 없음'으로 30분 밀림).
        // 카드를 거둬 줄 앞에 되돌리고 10초 뒤에 다시 본다. 이미 한 일을 알리는 말(requeue=false)은 카드만 남긴다
        if (res.isSuccess && !ok && line != null) {
            heldUntil = SystemClock.elapsedRealtime() + 10_000
            // 스위치(기본 꺼짐, 설명회 Q2 답에 따라 켬): 달리는 중에 막혔으면 신호 대기나 P에서 다시 말한다(별첨 2 §2-4 ②)
            val car = CarLink.state
            val defer = deferRefusedVoice && requeue && !car.parked && !timing.atLight(car)
            if (defer) deferred.add(line)
            Prend.log("· ${line.name}: 포커스를 받지 못해 듣지 않음${if (requeue) "(줄 앞에 되돌림${if (defer) ", 신호 대기·P까지 미룸" else ""})" else ""}")
            if (requeue) cutOff(line, true) else { speakingLine = null; answer("") }
            return
        }
        if (!ok) after()
        later(30_000) { if (asking == line && line != null) { Prend.log("· ${line.name}: 대답 없이 30초, 거둠"); if (requeue) decline(line, "대답 없음"); noAnswer(line); closeAny() } }
    }

    /**
     * 끝까지 건넨 물음이 대답 없이 지나갔다(카드 30초·목소리 뒤 대답 없음). 대리 운행 물음은 기억에 세어 두 번이면 묻지 않는다
     * (검토 11/06: 밤에 가족을 태우면 대답할 때까지 매번 떴다). 다른 말에 끊겨 되돌린 물음은 여기 오지 않는다
     */
    private fun noAnswer(line: Line) {
        if (line == Line.DAERI) memory.unanswered(Memory.Kind.DAERI)
    }

    /** 묻던 말이 다른 말에 끊겼다: 카드를 거두고, requeue면 줄 앞에 되돌려 편해지면 처음부터 다시 건넨다 */
    private fun cutOff(line: Line, requeue: Boolean) {
        if (speakingLine != line) return
        // 통화·다른 음성 비서·사람이 [프렌드]를 눌러 끊긴 말은 대답을 들을 수 없었으니 거의 다 했어도 되돌린다(거의 다 한 말 규칙은 stopSpeaking에서만)
        val again = requeue && requeueOnce(line)
        // 주차 자리 알림을 끝까지 못 했으면 '이번 운행에 알림'으로 치지 않는다(편해지면 다시)
        if (line == Line.SPOT && again) spotDone = false
        Prend.log("◆ 말 끊김: ${line.name}${if (again) "(줄 앞에 되돌림)" else if (requeue) "(두 번째라 버림)" else ""}")
        cardTimeout?.let { main.removeCallbacks(it) }
        presenceOff("끊김")
        speakingLine = null
        asking = null
        clearAsk()
        if (line == Line.REST && Stage.ui.value.scene == Scene.DRIVE) Stage.go(Scene.HOME)
        if (again) { queue.remove(line); queue.addFirst(line); unask(line) }
    }

    /**
     * 끊긴 말을 줄에 한 번만 되돌린다(11/01): 두 번째로 끊기면 버리고 물은 것으로 친다(같은 물음이 처음부터 세 번 나오지 않게).
     * 쉼터는 졸음과 이어져 있고, 잘래요는 등받이를 젖힌 그 사람의 몸짓에 답하는 물음이라 늘 되돌린다(대답이 없으면 켜지 않는다, 11/08). 되돌리면 true
     */
    private fun requeueOnce(line: Line): Boolean {
        // 화장실은 사람이 청한 일이라 끊겨도 늘 되돌린다(처음 말한 때부터 3분이 지나면 dropWhy가 버리고 한 줄로 알린다)
        if (line == Line.REST || line == Line.SLEEP || line == Line.TOILET) return true
        if (cutOnce.add(line)) { if (line in FLOOR_ASK) floor?.unspoke(line.name); return true }
        comfyDone(line); expired(line)
        return false
    }

    /**
     * 거의 다 한 말(70% 넘게)이 멈췄다: 처음부터 다시 하지 않는다. 대답은 듣지 않고 카드(버튼)만 15초 둔다
     * (시험 11/01: 6.4초 중 6.1초까지 나간 김서림 물음이 23초 뒤 처음부터 다시 나왔다)
     */
    private fun nearlyDone(line: Line, why: String) {
        speakingLine = null
        presenceOff("거의 다 함")
        Prend.log("◆ 말 멈춤($why): ${line.name}, 거의 다 해 다시 하지 않고 카드만 15초")
        later(15_000) { if (asking == line) { Prend.log("· ${line.name}: 대답 없이 15초, 거둠"); decline(line, "대답 없음"); closeAny() } }
    }

    /**
     * 차 신호가 바뀌는 순간(운행 서비스가 부른다, 감사 F47): 깜빡이를 켜거나 R에 넣으면 1초 틱을 기다리지 않고 하던 말을 멈추고(0.3초 안),
     * 대답을 기다리던 중이면 듣기와 카드를 닫는다. 둘 다 줄 앞에 되돌려 편해지면 처음부터 다시 건넨다
     */
    fun onCar(old: CarState, now: CarState) {
        bumpCar(old, now)
        guestCar(old, now)
        val signal = old.turnSignal != now.turnSignal && now.turnSignal != "OFF"
        val reverse = old.gear != now.gear && now.gear == VehicleGear.GEAR_REVERSE
        if (!signal && !reverse || Stage.ui.value.scene.isE) return
        if (speakingLine != null) stopSpeaking(now) else stopAnswer(now)
    }

    /** 말하는 중에 말할 때가 닫혔으면(깜빡이·후진·통화 등) 멈춘다. 혹시 사고는 통화일 때만 멈춘다. 멈췄으면 true */
    private fun stopSpeaking(car: CarState): Boolean {
        val line = speakingLine ?: return false
        val call = speaker.inCall
        val why = if (call) CALL else blockFor(car, line)
        if (why == null || !speaker.speaking || (line in BUMPS && !call)) return false
        val done = speaker.progress()
        speaker.stop()
        // 못 알아들어 다시 묻던 말이면 물음은 이미 다 들었다: 처음부터 다시 하지 않는다
        if (reasked == line) { reaskStopped(line, why); return true }
        // 이미 한 일을 알리는 말(정체 알아서)은 다시 하지 않는다: 말만 멈추고 카드([되돌리기])는 남긴다
        if (!requeueAsk) { speakingLine = null; presenceOff("말 멈춤"); Prend.log("◆ 말 멈춤($why): ${line.name}, 카드만 남김"); return true }
        // 거의 다 한 말은 처음부터 다시 하지 않는다(통화는 대답을 들을 수 없어 되돌린다)
        // 먼저 꺼낸 물음만(쉼터·잘래요·쪽잠은 대답이 일을 정하므로 늘 처음부터 다시)
        if (!call && line in FLOOR_ASK && done >= NEARLY_DONE) { nearlyDone(line, "$why, ${(done * 100).toInt()}% 나감"); return true }
        if (putBack(line, why)) Prend.log("◆ 말 멈춤($why): ${(done * 100).toInt()}% 나간 ${line.name}, 편해지면 처음부터 다시")
        return true
    }

    /** 대답을 기다리는 5초에 후진·깜빡이·통화가 생겼으면 듣기와 카드를 닫고 줄 앞에 되돌린다(감사 F14). 혹시 사고는 그대로 둔다. 닫았으면 true */
    private fun stopAnswer(car: CarState): Boolean {
        val line = asking ?: return false
        if (speakingLine != null || line in BUMPS || !Stage.answering) return false
        val why = when {
            speaker.inCall -> CALL
            car.gear == VehicleGear.GEAR_REVERSE -> "후진 중"
            car.turnSignal != "OFF" -> "깜빡이"
            else -> return false
        }
        Stage.endListen()
        speaker.stop()
        if (!requeueAsk) { Prend.log("◆ 대답 듣기 멈춤($why): ${line.name}, 카드만 남김"); return true }
        // 다시 물은 뒤의 대답 창이면 대답이 없던 것처럼 카드만(쉼터·쪽잠도 물음을 처음부터 다시 하지 않는다)
        if (why != CALL && reasked == line) { reaskStopped(line, why); return true }
        // 다 들은 물음은 처음부터 다시 묻지 않고 카드(버튼)만 15초(같은 물음을 두 번 듣지 않게. 통화는 대답을 들을 수 없어 되돌린다)
        if (why != CALL && line in FLOOR_ASK) { nearlyDone(line, "$why, 다 들은 뒤"); return true }
        if (putBack(line, why)) Prend.log("◆ 대답 듣기 멈춤($why): ${line.name}, 편해지면 처음부터 다시")
        return true
    }

    /**
     * 하던 말·대답 듣기를 거두고 줄 앞에 되돌린다(편해지면 처음부터). 쉼터 카드를 띄운 달리는 화면만 첫 화면으로 돌리고,
     * 쪽잠·기억·같이 고르기처럼 사람이 보던 화면은 그대로 둔다. 두 번째로 멈춘 말은 되돌리지 않고 버린다(되돌렸으면 true)
     */
    private fun putBack(line: Line, why: String): Boolean {
        cardTimeout?.let { main.removeCallbacks(it) }
        speakingLine = null
        asking = null
        val again = requeueOnce(line)
        if (again) {
            if (line == Line.SPOT) spotDone = false
            queue.remove(line); queue.addFirst(line)
            unask(line)
        } else Prend.log("◆ 말 멈춤($why): ${line.name}, 두 번째라 버림")
        swallowed = why
        clearAsk()
        if (line == Line.REST && Stage.ui.value.scene == Scene.DRIVE) Stage.go(Scene.HOME)
        presenceOff("말 멈춤")
        return again
    }

    /** 5초 틈이면 건넬 말(쉼터·화장실). 나머지는 이야기 틈 20초를 기다린다 */
    private fun urgentLine(line: Line) = line == Line.REST || line == Line.TOILET

    /** 출발 직후(3분)를 피할 말. 대리 운행 물음은 출발 직후에 묻고, 화장실은 사람이 먼저 꺼낸 말의 대답이라 피하지 않는다 */
    private fun startingLine(line: Line) = line != Line.DAERI && line != Line.TOILET

    /**
     * 이 말을 지금 하면 안 되는 까닭(ride/Timing.block). starting이면 새로 꺼내려는 때. 화장실은 사람이 먼저 꺼낸 말의 대답이라
     * 시속 100km 넘음·이야기 중으로는 막지 않는다(검토 11/07: 고속도로 순항에서 1초 만에 끊겨 3분 뒤 조용히 사라졌다)
     */
    private fun blockFor(car: CarState, line: Line, starting: Boolean = false): String? =
        timing.block(car, urgent = urgentLine(line), starting = starting && startingLine(line), asked = line == Line.TOILET)

    /** 끊겨 되돌린 말은 물은 것으로 치지 않는다(정체 60분·같이 고르기 3시간 쉬기에 걸려 편한 때 다시 못 묻지 않게) */
    private fun unask(line: Line) {
        when (line) {
            Line.JAM -> jam.unask()
            Line.PICK -> together.unask()
            Line.KIDS -> baby.undelivered()
            else -> Unit
        }
    }

    private fun later(ms: Long, block: () -> Unit) {
        cardTimeout?.let { main.removeCallbacks(it) }
        val r = Runnable(block)
        cardTimeout = r
        main.postDelayed(r, ms)
    }

    /** 예로 볼 긴 낱말. 한두 글자 예(응·어·네·예)와 거절 낱말은 ride/Answer가 본다("필요 없어"의 '어'를 예로 읽지 않게) */
    private val yesWords = listOf("그래", "좋아", "졸려", "쉬", "넣어", "가자", "경유", "그렇게", "부탁")
    /** 마이크 동의: 이 대답 전체일 때만 예("요"는 떼고 본다), 거절 낱말은 어디에 있어도 아니오 */
    private val TALK_YES = setOf("응", "네", "예", "그래", "좋아", "켜", "켜줘", "켜둬", "켜놔", "켜둘게", "켜주세", "켜놔줘", "켜둬줘", "네켜줘", "응켜줘", "좋아켜줘", "그렇게해줘", "그렇게해")
    private val TALK_NO = listOf("괜찮", "아니", "됐어", "싫어", "나중", "없어", "안켜", "켜지마", "말아", "하지마", "안돼", "안해", "꺼", "끄")

    /**
     * 예·아니오·없음. 되물은 "네?"·'어' 한마디와 "어, 그래서…"는 없음(한 번 다시 묻는 길로), 예 낱말은 낱말 앞자리에서만 본다(검토 11/07, ride/Answer).
     * item은 물은 것을 가리키는 낱말("커피 말고 쉼터로 넣어 줘"를 예로, "쉼터 말고"는 거절로)
     */
    private fun yesNo(text: String, more: List<String> = emptyList(), item: List<String> = emptyList()): Boolean? = Answer.of(text, yesWords + more, item = item)

    private fun logAnswer(what: String, text: String, byButton: Boolean, yes: Boolean?) {
        // 먼저 묻고 대답을 받은 수(기기 안, ride/Stats)
        if (yes != null) Stats.add("asked")
        Prend.log("· $what 대답(${if (byButton) "버튼" else "말 ${text.length}자"}) → ${when (yes) { true -> "예"; false -> "아니오"; null -> "없음" }}")
    }

    /**
     * 말로 한 대답이 예도 아니오도 아니다(검토-방향노인초보 3-5): 그 물음에 한 번만 "'응'이나 '괜찮아'로 말씀해 주세요"라고 하고 대답 창을 다시 연다.
     * 다시 물었으면 true. 두 번째도 못 알아들었거나 지금 말할 때가 아니면 false(전처럼 듣기를 닫고 카드 버튼만 남긴다. 그 뒤 10초 안의 버튼은 늦은 대답으로 센다).
     * 말 차례에는 세지 않는다(새로 꺼내는 말이 아니다). "뭐라고?"에는 이 말 말고 그 앞의 물음을 다시 한다
     */
    private fun unclear(line: Line, text: String, byButton: Boolean, again: (String) -> Unit): Boolean {
        if (byButton || text.isBlank() || asking != line) return false
        val why = when {
            reasked == line -> "다시 물은 뒤"
            Stage.ui.value.scene.isE -> "급할 때"
            speaker.inCall -> CALL
            // 가족 정보 물음에 "'응'이나 '괜찮아'로"는 말이 맞지 않는다(괜찮아 = 아이 없음으로 읽힘). 동승자가 있으면 곁의 말일 수 있다(11/04 사람 시험)
            line in BUMPS -> null
            line == Line.KIDS -> "가족 정보 물음은 카드로"
            // 잘래요는 등받이를 젖힌 그 동승자에게 물은 말이라 동승자가 있어도 한 번 다시 묻는다(검토 11/07: 되물은 "어?"에 잘래요를 켰다)
            line == Line.SLEEP -> blockFor(CarLink.state, line)
            !alone(lastCar) -> "동승자가 있음(곁의 말일 수 있음)"
            else -> blockFor(CarLink.state, line)
        }
        if (why != null) { Prend.log("· ${line.name}: 알아듣지 못함($why) → 듣기를 닫고 카드만"); Stage.missed(); return false }
        reasked = line
        reaskAnswer = again
        speakingLine = line
        Prend.log("◆ ${line.name}: 알아듣지 못함 → 한 번 다시 물음")
        val ok = runCatching {
            speaker.speak("voice/say_yes.wav", AudioAttributes.USAGE_ASSISTANT, "다시 묻기", answerMs = Stage.answerMs, remember = false,
                onCut = { if (speakingLine == line && reasked == line) reaskStopped(line, if (speaker.inCall) CALL else "끊김") }) {
                speakingLine = null
                if (asking == line) Stage.call(answer = again, windowMs = Stage.answerMs)
            }
        }.onFailure { Prend.log("✗ 다시 묻기 틀기 실패 $it") }.getOrDefault(false)
        if (!ok) { speakingLine = null; Stage.missed(); return false }
        later(30_000) { if (asking == line) { Prend.log("· ${line.name}: 대답 없이 30초, 거둠"); decline(line, "대답 없음"); closeAny() } }
        return true
    }

    /**
     * 다시 묻던 말·그 대답 창이 멈췄다(깜빡이·후진·다른 소리·[프렌드]): 물음은 이미 다 들었으니 처음부터 다시 하지 않고
     * 대답이 없던 것으로 넘긴다(카드 버튼만 남는다). 통화면 대답을 들을 수 없었으니 전처럼 줄 앞에 되돌린다
     */
    private fun reaskStopped(line: Line, why: String) {
        speakingLine = null
        if (why == CALL) { if (putBack(line, why)) Prend.log("◆ 다시 묻기 멈춤($why): ${line.name}, 편해지면 처음부터 다시"); return }
        Prend.log("◆ 다시 묻기 멈춤($why): ${line.name}, 카드만")
        reaskAnswer?.invoke("")
    }

    // ── 쉼터 ──

    /** 앞쪽의 가장 가까운 졸음쉼터를 고르고 카드의 한 줄을 채운다(위치를 모르면 비운다) */
    private fun choose(): RestPick? {
        val w = navi?.last
        val p = if (w == null) null else rests()?.next(w.lat, w.lon, w.heading)
        pick = p
        val line = p?.let {
            val km = it.alongKm
            val dist = if (km < 10) "%.1fkm".format(km) else "${kotlin.math.round(km).toInt()}km"
            val speed = lastCar.speedKmh.toDouble().takeIf { s -> s > 30 } ?: 80.0
            val min = kotlin.math.ceil(km / speed * 60).toInt().coerceAtLeast(1)
            // 휴게소면 차에 맞는 연료가 있는지 한 낱말(11/02, 한국도로공사_휴게소정보). LPG는 자료가 늦을 수 있어 말하지 않는다
            val electric = Stage.ui.value.care.electric == true
            val fuelTag = if (!it.area.stop) "" else if (electric) (if (it.area.ev) " · 충전기 있음" else "") else if (it.area.fuel) " · 주유소 있음" else ""
            "${it.area.name} · $dist · 약 ${min}분$fuelTag"
        }
        Stage.ui.value = Stage.ui.value.copy(restLine = line)
        // 어느 쉼터인지(이름·노선·거리)는 위치라 남기지 않는다
        Prend.log("· 쉼터 고름: ${p?.let { (if (it.onRoute) "있음(같은 노선" else "있음(직선") + if (it.area.stop) ", 휴게소)" else ", 졸음쉼터)" } ?: if (w == null) "위치 모름" else "앞쪽에 없음"}")
        return p
    }

    private fun answerRest(text: String, byButton: Boolean) {
        // "안 졸려"·"안 쉬어도 돼"는 예 낱말(졸려·쉬)이 들었어도 아니오(Stage.route의 마다하는 말과 같은 낱말).
        // "안 그래도 쉬려고 했어"·"아무 데나 괜찮아"·"커피 말고 쉼터로 넣어 줘"는 거절 꼴을 걸러 내고 본다(검토 11/07 멋대로 판단)
        val yes = if (Stage.REST_NO.any { it in Answer.plain(text, REST_ITEM).replace(" ", "") }) false else yesNo(text, item = REST_ITEM)
        logAnswer("쉼터", text, byButton, yes)
        // 못 알아들었으면 한 번 다시 묻는다(다음 권하기는 다시 들은 대답으로 정한다)
        if (yes == null && unclear(Line.REST, text, byButton) { answerRest(it, byButton = false) }) return
        // "졸려"에서 온 물음이면 괜찮아요·대답 없음일 때 다음 권하기를 원래보다 늦추지 않는다(졸리다고 했으니 30분 안에 다시).
        // 두 시간 쉼터는 1시간 뒤 한 번만 다시 권하고, 그것도 넘기면 쉬기 전까지 다시 권하지 않는다(별첨 2 §5-2 ③. 전에는 30분마다 끝없이)
        val sleepyAsk = restSleepy
        restSleepy = false
        val again = if (sleepyAsk) sleepyAgain() else if (yes == true) 0 else restLater()
        // 대답이 정해지면 멈춰 둔 다른 음악을 바로 돌려준다
        speaker.stop()
        when (yes) {
            true -> {
                closeRest()
                // 쉼터에서 15분 넘게 쉬면 운전 시간과 함께 처음으로 돌아간다. 들르지 않았거나 잠깐만 섰으면 한 시간 뒤 다시 권한다
                restAt = lastMinutes + REST_AFTER_YES
                // 권할 때 고른 곳이 없으면 지금 고른다
                val p = pick ?: choose()
                val how = p?.let { navi?.goVia(it.area.lat, it.area.lon, it.area.name, "${it.area.route} ${it.area.dir} 방향") }
                when (how) {
                    "waypoint" -> {
                        Stage.toast("${p.area.name}를 경유지로 넣었어요. 세우면 쪽잠을 챙겨 드릴게요", 3_500)
                        speakLine("voice/rest_via.wav", "쉼터 넣음")
                    }
                    "route" -> Stage.toast("${p.area.name}로 안내해요. 세우면 쪽잠을 챙겨 드릴게요", 3_500)
                    else -> {
                        Prend.log("· 쉼터: ${if (p == null) "앞쪽 40km 안에 쉼터 없음 또는 위치 모름" else "내비에 넣지 못함"}")
                        // 넣을 곳이 없거나 내비가 받지 않았다: 다른 쉴 길을 권한다
                        if (p == null) restNone() else Stage.toast("내비에 넣지 못했어요. ${p.area.name}에 들러 쉬어 가세요", 4_000)
                    }
                }
                restAcceptedAt = SystemClock.elapsedRealtime()
                pick = null
            }
            false -> {
                closeRest()
                restAt = again
                if (restAt != REST_NEVER) Prend.log("· 쉼터: ${restAt - lastMinutes}분 뒤 한 번 더")
            }
            null -> {
                // 대답이 없으면 화면 버튼을 15초 남겨 두고, 그래도 없으면 1시간 뒤 한 번 더(이미 한 번 더 권했으면 쉬기 전까지 없음)
                restAt = again
                later(15_000) { if (asking == Line.REST) closeRest() }
            }
        }
    }

    /**
     * "졸려" 물음에 괜찮아요·대답 없음·끊김이면 다음 쉼터 권하기: 원래 때가 아직 앞이면 그보다 늦추지 않고(30분 안),
     * 이미 지났으면(두 시간 권하기가 이야기 등에 밀려 줄에 있던 중) 방금 물은 것이 그 몫이라 30분 뒤(바로 두 시간 물음이 또 나오지 않게)
     */
    private fun sleepyAgain(): Int =
        if (restBefore > lastMinutes) minOf(restBefore, lastMinutes + REST_AGAIN) else lastMinutes + REST_AGAIN

    /** 쉼터 카드가 떠 있지 않을 때의 "안 졸려"(Stage.onRestNo, 11/08 오류 점검 A8): [프렌드]가 끊어 줄 앞에 되돌린 쉼터 물음을 빼고 다음 권하기를 미룬다 */
    private fun restNo() {
        if (Line.REST !in queue && asking != Line.REST && speakingLine != Line.REST) return
        queue.remove(Line.REST)
        restAt = if (restSleepy) sleepyAgain() else restLater()
        restSleepy = false
        Prend.log("· 쉼터: 카드 밖에서 마다함 → 줄에서 뺌")
    }

    /**
     * 쉼터 물음을 확인 카드가 덮었다(쉼터 대답을 기다리는 사이 [프렌드]로 한 애매한 말에 뜬 카드). 전에는 쉼터 물음이 시간 끝에 '대답 없음'이 되어
     * 1시간 뒤로 밀렸다. 이제 그 물음은 묻지 않은 것으로 되돌려(한 번 더 권함·"졸려"에서 온 물음인지도 물을 때대로) 줄 앞에 두고,
     * 확인 카드가 닫히면 편한 때 다시 묻는다(졸린 운전자, 원칙 4. 11/08 사용자 결정)
     */
    private fun restCovered() {
        cardTimeout?.let { main.removeCallbacks(it) }
        asking = null
        restReoffered = restAskWas.first
        restSleepy = restAskWas.second
        restAt = Int.MAX_VALUE
        queue.remove(Line.REST); queue.addFirst(Line.REST)
        Prend.log("· 쉼터: 확인 카드가 물음을 덮음 → 카드가 닫히면 다시 물음")
    }

    /** 두 시간 쉼터를 거절·무응답: 처음이면 1시간 뒤 한 번 더, 이미 한 번 더 권했으면 쉬기 전까지 권하지 않는다 */
    private fun restLater(): Int {
        if (restReoffered) { Prend.log("· 쉼터: 한 번 더 권한 것도 넘겨 쉬기 전까지 다시 권하지 않음(\"졸려\"에는 권함)"); return REST_NEVER }
        restReoffered = true
        return lastMinutes + REST_REOFFER
    }

    /** 쉼터를 묻던 것을 거둔다. 다른 물음이 열려 있으면 그 카드와 시간은 건드리지 않는다(감사 F55) */
    private fun closeRest() {
        if (asking != Line.REST && speakingLine != Line.REST) { if (Stage.ui.value.ask?.id == "REST") Stage.ask(null); return }
        cardTimeout?.let { main.removeCallbacks(it) }
        speakingLine = null
        asking = null
        clearAsk()
        if (Stage.ui.value.scene == Scene.DRIVE) Stage.go(Scene.HOME)
    }

    /**
     * "졸려"·"쉼터"([프렌드]·AI 대역·한 시간 물음의 대답·배너의 [쉬어 갈 곳], 별첨 2 §5-2 ④). 곧바로 경유지에 넣지 않는다:
     * 앞쪽 쉼터를 골라 카드를 띄우고 숫자 없는 말로 권한 뒤, 5초 듣고 "응"이나 [경유지로]일 때만 넣는다.
     * - 쉼터 카드가 이미 떠 있으면 그 대답("응")으로 받는다
     * - 세워 둔 동안은 이미 쉬는 중이라 쪽잠을 권한다
     * - 앞쪽에 쉼터가 없거나 위치를 모르면 안전한 곳에 세우고 쉬자고 권한다
     * - 묻던 다른 말은 지우지 않고 줄 앞에 되돌린다
     */
    fun sleepy() {
        if (Stage.ui.value.scene.isE) return
        // 대리 운행이면 "졸려"는 주인의 말이다(기사는 PREND에 말을 걸지 않는다): 쉼터 대신 주인 자리 잘래요(사용자 결정 10/31)
        if (Stage.ui.value.daeri) {
            if (Stage.ui.value.sleeper == null) Stage.sleep(true) else Stage.toast("편히 쉬세요 · 도착할 때 깨워 드릴게요", 3_000)
            return
        }
        if (asking == Line.REST) { Prend.log("· 졸려: 쉼터를 묻던 중이라 그 대답으로 받음"); answerRest("응", byButton = true); return }
        if (CarLink.state.parked) {
            // 고속도로 갓길·막힌 차로면 쪽잠을 권하지 않고 쉼터로 가자고 한 줄만(대피 카드를 덮지 않게, 11/08 오류 점검 C2)
            if (!nap.napping && highwayStopped()) { Prend.log("· 졸려(고속도로 위에 세움): 쪽잠 대신 쉼터 안내"); Stage.toast("고속도로 위는 쉬기 위험해요 · 가까운 졸음쉼터나 휴게소에서 쉬어 가세요", 4_000); return }
            if (!nap.napping && Line.NAP !in declined) { Prend.log("· 졸려(세워 둠): 쪽잠 권하기로"); queueNap() }
            else Stage.toast("세워 둔 채 잠깐 쉬었다 가세요", 3_000)
            return
        }
        presenceLine?.let { cancelPresence("졸려", drop = it == Line.REST) }
        // 한 시간 물음("필요한 것 있으세요?")이 떠 있었으면 "졸려"가 그 대답이다: 다시 묻지 않는다. 다른 말은 줄 앞에 되돌린다
        if (asking == Line.HOUR) { speaker.stop(); closeSimple(); memory.answered(Memory.Kind.HOUR, true) }
        else (asking ?: speakingLine)?.let { setAside(it) }
        queue.remove(Line.REST)
        // 끊겨 줄에 되돌아가도(깜빡이 등) 다시 건넬 때 같은 "졸려" 물음으로 건넨다(deliver의 REST)
        if (!restSleepy) restBefore = if (restAt == Int.MAX_VALUE) lastMinutes + REST_AGAIN else restAt
        restSleepy = true
        // 확인 카드가 떠 있으면 덮지 않고 줄 맨 앞에 세워 카드가 닫히면 곧바로 권한다(검토 11/07, 확인 카드는 길어야 30초)
        if (confirmUp()) { queue.addFirst(Line.REST); Prend.log("· 졸려: 확인 카드가 떠 있어 닫힌 뒤 쉼터를 권함"); return }
        deliver(Line.REST, lastMinutes)
    }

    /**
     * "애가 멀미해"·"속이 울렁거려"(매력 제안 10): 급할 때(E)로 가지 않는다. 바깥 공기로 바꾸고 바람을 얼굴 쪽으로 돌리고,
     * PREND 곡은 이번 운행 동안 한 단(−3dB) 낮춘다. 약·자세 같은 의학 조언은 하지 않고, 쉬어 가는 길은 "쉬어 가자"([프렌드])로 잇는다
     */
    fun motion() {
        if (Stage.ui.value.scene.isE) return
        Prend.log("◆ 멀미: 바깥 공기·얼굴 쪽 바람·곡 한 단 낮춤")
        Bridge.motion = true
        Bridge.director?.motion = true
        val h = CarLink.hubOrNull ?: return Stage.toast("공조에 닿지 못했어요 · 창을 조금 열어 주세요", 3_500)
        h.freshAir { ok ->
            h.setFanDirection(FanDirectionType.FACE)
            Stage.toast(if (ok) "바깥 공기로 바꾸고 바람을 얼굴 쪽으로 돌렸어요" else "바람을 얼굴 쪽으로 돌렸어요 · 창을 조금 열어 주세요", 3_500)
        }
        speakLine("voice/motion_air.wav", "멀미")
    }

    /** "졸려"·"화장실 급해"에 자리를 비켜 준다: 묻던 말을 거두고, 다시 할 말이면 줄 앞에 되돌린다(물은 것으로 치지 않는다) */
    private fun setAside(line: Line, why: String = "졸려") {
        cardTimeout?.let { main.removeCallbacks(it) }
        speaker.stop()
        if (!confirmUp()) Stage.endListen()
        speakingLine = null
        asking = null
        clearAsk()
        presenceOff(why)
        if (requeueAsk) {
            if (line == Line.SPOT) spotDone = false
            // 다시 건넬 때 또 세므로 이번 것은 말 차례에서 뺀다(목소리면 물음 수까지, 카드만이면 먼저 꺼낸 수만)
            if (line in FLOOR_ASK) floor?.unspoke(line.name, voice = askVoiced)
            queue.remove(line); queue.addFirst(line); unask(line)
        }
        Prend.log("· ${line.name}: ${why}에 비켜 섬${if (requeueAsk) "(줄 앞에 되돌림)" else ""}")
    }

    /** 앞쪽에 권할 쉼터가 없다(위치 모름 포함): 안전한 곳에 세우고 쉬자고 한 줄과 목소리로 권한다. 차 안은 깨어 있게 둔다 */
    private fun restNone() {
        Prend.log("· 쉼터: 권할 곳이 없어 세우고 쉬자고 권함")
        Stage.toast("가까운 쉼터를 찾지 못했어요 · 안전한 곳에 세우고 쉬어 가세요", 5_000)
        speakLine("voice/rest_none.wav", "쉼터 없음")
    }

    // ── 화장실 ──

    /** 고른 화장실 자리와 고른 때, 처음 말한 때(끊겨 줄에 되돌아가도 처음 말한 때부터 3분까지만) */
    private var toiletPick: ToiletPick? = null
    private var toiletPickedAt = 0L
    private var toiletAt = 0L
    /** 세워 둔 채 청했나(그러면 줄에서 기다리는 동안 '세움'으로 버리지 않는다) */
    private var toiletFromPark = false
    /** 주변을 찾는 중인가(비동기, 최대 2.5초)와 찾기 번호(마지막 요청의 결과만 받는다. 그사이 다시 청했거나 그만두라고 했으면 늦게 온 결과를 버린다) */
    private var toiletFinding = false
    private var toiletGen = 0

    /**
     * "화장실 급해"·"애가 쉬 마렵대"([프렌드]·AI 대역, 사용자 11/07): 앞쪽에서 들르기 쉬운 화장실 한 곳을 카드로 권하고 "응"·[경유지로]일 때만 넣는다.
     * 사람이 먼저 꺼낸 말의 대답이라 말 차례·편한 때를 기다리지 않는다("졸려"와 같다). 두 시간 쉼터 시계와 쪽잠은 건드리지 않는다.
     * 고속도로면 휴게소·졸음쉼터, 일반 도로면 주유소(ride/Toilets). 화장실 카드가 떠 있으면 들은 말을 그 대답으로 받는다
     */
    fun toilet(text: String = "") {
        if (Stage.ui.value.scene.isE) return
        if (asking == Line.TOILET) { Prend.log("· 화장실: 묻던 중이라 그 대답으로 받음"); answerToilet(text.ifBlank { "응" }, byButton = text.isBlank()); return }
        toiletAt = SystemClock.elapsedRealtime()
        toiletFromPark = CarLink.state.parked
        findToilet(again = false)
    }

    /** "화장실 괜찮아"·"안 가도 돼": 기다리던 화장실 물음(줄·기척·찾는 중)을 거둔다. 카드가 떠 있으면 Stage가 [괜찮아요]로 받는다 */
    fun toiletOff() {
        val had = Line.TOILET in queue || toiletFinding || presenceLine == Line.TOILET
        queue.remove(Line.TOILET)
        toiletGen++
        toiletFinding = false
        toiletPick = null
        if (presenceLine == Line.TOILET) cancelPresence("화장실 그만", drop = true)
        if (had) Prend.log("· 화장실: 그만두라고 해 거둠")
    }

    /**
     * 지금 자리에서 고르고 카드로 건넨다. again이면 줄에서 다시 나온 말을 다시 고른 것.
     * thenAdd면 사람이 이미 "응"이라고 했는데 고른 곳을 지나쳐 다시 고른 것이라 묻지 않고 바로 넣는다
     */
    private fun findToilet(again: Boolean, thenAdd: Boolean = false) {
        val w = navi?.last
        val t = toilets
        if (w == null || t == null) { toiletNone(if (w == null) "위치 모름" else "자료 없음"); return }
        val remote = GeoShare.on
        if (remote && !again) Stage.toast(TOILET_FINDING, 3_000)
        val gen = ++toiletGen
        toiletFinding = true
        t.find(w.lat, w.lon, w.heading, System.currentTimeMillis() - w.at, lastCar.speedKmh, CarLink.state.parked, remote) { pick, how ->
            if (gen != toiletGen) return@find
            toiletFinding = false
            Stage.clearToastIf(TOILET_FINDING)
            // 고르는 사이 급할 때가 됐으면 그대로 둔다
            if (Stage.ui.value.scene.isE) return@find
            // 어느 곳인지(이름·거리)는 위치라 남기지 않는다
            Prend.log("· 화장실 고름: ${pick?.kind?.name ?: "앞쪽에 없음"}($how)")
            if (pick == null) { toiletNone(how); return@find }
            // 찾는 사이 세웠으면(그 주유소에 들어섰거나 다른 곳에 섬) 건네지 않는다
            if (CarLink.state.parked && !toiletFromPark) { Prend.log("· 화장실: 찾는 사이 세워 건네지 않음"); return@find }
            toiletPick = pick
            toiletPickedAt = SystemClock.elapsedRealtime()
            if (thenAdd) { addToilet(pick, passed = true); return@find }
            // 고속도로에 선 차의 대피 카드가 떠 있으면 그 안내가 먼저다
            if (Stage.ui.value.ask?.id == "HSTOP") { Prend.log("· 화장실: 고속도로 대피 카드가 떠 있어 건네지 않음"); Stage.toast("대피 안내가 먼저예요 · 비상등을 켜고 가드레일 밖으로", 4_000); toiletPick = null; return@find }
            // 고르는 사이 [프렌드]를 다시 불렀거나 다른 한마디가 나오는 중이거나, 지금이 말할 때가 아니면(깜빡이·회전 직전·주차 코치 등)
            // 줄 맨 앞에 세워 그것이 끝난 뒤 건넨다(열린 마이크 위로 말해 대답을 잃지 않게, 검토 11/07)
            val car = CarLink.state
            val wait = when {
                Stage.ui.value.listen != null -> "프렌드를 부름"
                // 확인 카드를 덮지 않는다(검토 11/07)
                confirmUp() -> CONFIRM
                speaker.speaking && speakingLine == null -> "다른 한마디"
                waits(Line.TOILET, car) -> "지금은 건너뜀"
                else -> blockFor(car, Line.TOILET, starting = true)
            }
            if (wait != null) {
                Prend.log("· 화장실: 지금은 건네지 않고 줄 앞에($wait)")
                queue.remove(Line.TOILET); queue.addFirst(Line.TOILET)
                return@find
            }
            presenceLine?.let { cancelPresence("화장실", drop = it == Line.TOILET) }
            // 처음 청한 말이고 한 시간 물음("필요한 것 있으세요?")이 떠 있었으면 이 말이 그 대답이다: 다시 묻지 않는다. 다른 말은 줄 앞에 되돌린다
            if (asking == Line.HOUR && !again) { speaker.stop(); closeSimple() }
            else (asking ?: speakingLine)?.takeIf { it != Line.TOILET }?.let { setAside(it, "화장실") }
            queue.remove(Line.TOILET)
            deliver(Line.TOILET, lastMinutes)
        }
    }

    /** 카드 둘째 줄: "○○휴게소 · 3.2km · 약 3분 · 수유실 있음", "○○주유소 · 1.2km 앞 · 24시간" */
    private fun toiletLine(p: ToiletPick): String {
        val dist = if (p.km < 10) "%.1fkm".format(Locale.US, p.km) else "${kotlin.math.round(p.km).toInt()}km"
        return if (p.highway) {
            val speed = lastCar.speedKmh.toDouble().takeIf { it > 30 } ?: 80.0
            val min = kotlin.math.ceil(p.km / speed * 60).toInt().coerceAtLeast(1)
            "${p.name} · $dist · 약 ${min}분" + if (p.nursing) " · 수유실 있음" else ""
        } else "${p.name} · $dist 앞" + if (p.open24 == true) " · 24시간" else ""
    }

    private fun answerToilet(text: String, byButton: Boolean) {
        val s = text.replace(" ", "")
        // "안 가면 안 돼"·"못 참아"는 급하다는 말이라 마다하는 말보다 먼저 본다. "급해"·"빨리"·"화장실"도 예로 받는다.
        // 되물은 "네?"는 예가 아니고, "커피 말고 화장실 들르자"의 '말고'는 거절이 아니다(검토 11/07 멋대로 판단)
        val yes = when {
            Stage.toiletUrge(s) -> true
            Stage.REST_NO.any { it in Answer.plain(text, TOILET_ITEM).replace(" ", "") } -> false
            else -> yesNo(text, Stage.TOILET_YES, TOILET_ITEM)
        }
        logAnswer("화장실", text, byButton, yes)
        if (yes == null && unclear(Line.TOILET, text, byButton) { answerToilet(it, byButton = false) }) return
        speaker.stop()
        when (yes) {
            true -> {
                val p = toiletPick
                closeToilet()
                if (p == null) { toiletNone("고른 곳 없음"); return }
                addToilet(p, passed = false)
            }
            false -> { closeToilet(); toiletPick = null }
            // 대답이 없으면 버튼을 15초 남겨 둔다
            null -> later(15_000) { if (asking == Line.TOILET) { closeToilet(); toiletPick = null } }
        }
    }

    /**
     * 고른 곳을 내비에 넣는다. 대답을 기다리는 사이 지나쳤으면 묻지 않고 다음 곳을 찾아 바로 넣는다(사람이 이미 "응"이라고 했다).
     * passed면 그렇게 다시 고른 곳
     */
    private fun addToilet(p: ToiletPick, passed: Boolean) {
        val w = navi?.last
        if (!passed && w != null && toilets?.stillAhead(p, w.lat, w.lon, w.heading, System.currentTimeMillis() - w.at, lastCar.speedKmh) == false) {
            Prend.log("· 화장실: 고른 곳을 지나쳐 다음 곳을 찾아 바로 넣음")
            Stage.toast("방금 지나쳤어요 · 다음 곳을 찾아 넣을게요", 3_000)
            findToilet(again = true, thenAdd = true)
            return
        }
        toiletPick = null
        when (navi?.goVia(p.lat, p.lon, p.name, p.address)) {
            "waypoint" -> {
                Stage.toast("${p.name}${eul(p.name)} 경유지로 넣었어요", 3_500)
                if (Stage.ui.value.listen == null) speakLine("voice/toilet_via.wav", "화장실 넣음")
            }
            "route" -> Stage.toast("${p.name}${ro(p.name)} 안내해요", 3_500)
            else -> {
                Prend.log("· 화장실: 내비에 넣지 못함")
                Stage.toast("내비에 넣지 못했어요 · ${p.name}에 들러 가세요", 4_000)
            }
        }
    }

    /** 화장실 묻던 것을 거둔다. 다른 물음이 열려 있으면 그 카드와 시간은 건드리지 않는다 */
    private fun closeToilet() {
        if (asking != Line.TOILET && speakingLine != Line.TOILET) { if (Stage.ui.value.ask?.id == "TOILET") Stage.ask(null); return }
        cardTimeout?.let { main.removeCallbacks(it) }
        speakingLine = null
        asking = null
        clearAsk()
    }

    /**
     * 고를 곳이 없다(위치 모름 포함): 한 줄로 알리고 끝낸다. 듣는 중이거나 다른 한마디가 나오는 중이면 목소리 없이 글만
     * (열린 마이크 위로 말하지 않게). 쉼터 안에 서 있으면 그곳 화장실을 쓰라고만
     */
    private fun toiletNone(why: String) {
        Prend.log("· 화장실: 고를 곳 없음($why)")
        val text = when (why) {
            Toilets.IN_REST -> "지금 쉼터·휴게소 안이에요 · 그곳 화장실을 쓰세요"
            Toilets.FAST -> "빠르게 달리는 중이라 길가 주유소는 고르지 않았어요 · 나들목을 나가면 다시 말씀해 주세요"
            else -> "앞쪽에서 가까운 화장실을 찾지 못했어요"
        }
        Stage.toast(text, 5_000)
        if (why != Toilets.IN_REST && Stage.ui.value.listen == null && !speaker.speaking) speakLine("voice/toilet_none.wav", "화장실 없음")
    }

    /** 줄에 되돌아간 화장실 물음이 고른 곳에 들어서는 중인가(150m 안이고 시속 20 아래) */
    private fun toiletReached(): Boolean {
        val p = toiletPick ?: return false
        val w = navi?.last ?: return false
        return lastCar.speedKmh < 20f && RestAreas.dist(w.lat, w.lon, p.lat, p.lon) < 150
    }

    /** 고른 화장실 자리 150m 안에 있나(P에 넣은 곳이 그곳이면 말없이 거둔다). 고른 곳이나 위치를 모르면 false */
    private fun toiletNear(): Boolean {
        val p = toiletPick ?: return false
        val w = navi?.last ?: return false
        return RestAreas.dist(w.lat, w.lon, p.lat, p.lon) < 150
    }

    /**
     * 줄에서 버린 화장실 물음의 뒷정리. 3분이 지나 버렸거나, 고른 곳이 아닌 데서 세워 버렸으면 한 줄로 알린다
     * (사람이 청한 일이 말없이 사라지지 않게, 검토 11/07). 고른 곳 가까이에 세웠으면 그곳에 들어선 것이라 말없이
     */
    private fun toiletGone(why: String) {
        val tell = why == TOILET_LATE || why == TOILET_PARKED && !toiletNear() && !Stage.ui.value.scene.isE
        toiletPick = null
        if (tell) Stage.toast(TOILET_STOPPED, 4_000)
    }

    /** 받침이 있으면 을·으로(ㄹ 받침은 로), 없으면 를·로 */
    private fun batchim(word: String): Int? = word.lastOrNull()?.takeIf { it in '가'..'힣' }?.let { (it - '가') % 28 }
    private fun eul(word: String) = if ((batchim(word) ?: 0) > 0) "을" else "를"
    private fun ro(word: String) = batchim(word).let { if (it == null || it == 0 || it == 8) "로" else "으로" }

    // ── 정체 ──

    private fun answerJam(text: String, byButton: Boolean) {
        val yes = yesNo(text, listOf("바꿔"))
        logAnswer("정체", text, byButton, yes)
        speaker.stop()
        when (yes) {
            true -> {
                closeJam()
                // 배운 것은 글로만(tell과 같은 까닭)
                val l = memory.answered(Memory.Kind.JAM, true)
                jam.accept { msg -> Stage.toast(listOfNotNull(msg, l?.text).joinToString(" · "), if (l != null) 4_000 else 3_000) }
            }
            false -> { closeJam(); decline(Line.JAM, "괜찮아요"); tell(memory.answered(Memory.Kind.JAM, false)) }
            // 못 알아들었으면(되물은 "네?" 포함) 한 번 다시 묻고, 말이 없으면 카드와 버튼을 15초 남긴다. 기억('응' 세 번이면 알아서)에는 세지 않는다(검토 11/07)
            null -> if (!unclear(Line.JAM, text, byButton) { answerJam(it, byButton = false) }) later(15_000) { if (asking == Line.JAM) { decline(Line.JAM, "대답 없음"); closeJam() } }
        }
    }

    private fun closeJam() {
        cardTimeout?.let { main.removeCallbacks(it) }
        speakingLine = null
        asking = null
        clearAsk()
    }

    // ── 같이 고르기 ──

    private fun answerPick(text: String, byButton: Boolean) {
        val yes = yesNo(text, listOf("골라", "좋지", "찾아"))
        logAnswer("같이 고르기", text, byButton, yes)
        speaker.stop()
        when (yes) {
            true -> { closePick(); memory.answered(Memory.Kind.PICK, true); Stage.openPick("food") }
            false -> { closePick(); decline(Line.PICK, "괜찮아요"); tell(memory.answered(Memory.Kind.PICK, false)) }
            null -> if (!unclear(Line.PICK, text, byButton) { answerPick(it, byButton = false) }) later(15_000) { if (asking == Line.PICK) { decline(Line.PICK, "대답 없음"); closePick() } }
        }
    }

    private fun closePick() {
        cardTimeout?.let { main.removeCallbacks(it) }
        speakingLine = null
        asking = null
        clearAsk()
    }

    // ── 쪽잠·잘래요 ──

    private fun answerNap(text: String, byButton: Boolean) {
        val yes = yesNo(text, listOf("잘게", "잘래", "잘래요", "자고"))
        logAnswer("쪽잠", text, byButton, yes)
        speaker.stop()
        when (yes) {
            true -> { closeSimple(); Stage.napStart(memory.habits.napMin ?: 15) }
            false -> { closeSimple(); decline(Line.NAP, "괜찮아요") }
            null -> if (!unclear(Line.NAP, text, byButton) { answerNap(it, byButton = false) }) later(15_000) { if (asking == Line.NAP) { decline(Line.NAP, "대답 없음"); closeSimple() } }
        }
    }

    /**
     * 등받이를 젖혀 물은 "주무시게요?"의 대답. 분명한 예나 [네]일 때만 잘래요를 켠다(검토 11/07 멋대로 판단: 이어폰을 끼고 대답하지 않거나 "어?"만 해도
     * 불을 어둡게 하고 "네, 편히 쉬세요"라고 했다. 10/27의 '대답이 없으면 켠다'를 11/08 사용자 승인으로 바꿈).
     * - 대답이 없으면 아무것도 바꾸지 않는다: 카드는 말할 때 건 30초까지 두었다가 말없이 거둔다(say)
     * - 되물은 "네?"·"어?"와 알아듣지 못한 말은 한 번 다시 묻고, 그래도 모르면 대답이 없던 것처럼
     * - 다른 부탁("에어컨 좀 세게 해 줘")이면 켜지 않고 그 말을 [프렌드]에 한 말처럼 들어준다(카드는 그대로 둔다)
     */
    private fun answerSleep(text: String, byButton: Boolean) {
        val yes = yesNo(text, listOf("잘게", "잘래"))
        logAnswer("잘래요", text, byButton, yes)
        speaker.stop()
        when {
            // 묻는 사이 세웠으면 잘래요를 켜지 않는다(세운 차에서 켜면 운전자 쪽잠이 된다)
            yes == true -> { closeSimple(); if (!lastCar.parked) nap.sleepFromAsk() }
            yes == false -> { closeSimple(); decline(Line.SLEEP, "아니요") }
            text.isBlank() -> Prend.log("· 잘래요: 대답 없음 → 켜지 않고 카드만 둠")
            !Answer.unsure(text) && asksSomething(text) -> { Prend.log("· 잘래요: 다른 부탁이라 켜지 않고 [프렌드]로 넘김"); Stage.request(text) }
            else -> if (!unclear(Line.SLEEP, text, byButton) { answerSleep(it, byButton = false) }) Prend.log("· 잘래요: 알아듣지 못함 → 켜지 않고 카드만 둠")
        }
    }

    /** 이번 김서림은 알아서 바꾸고 알린 것이다(대답의 아니오는 되돌리기) */
    private var fogAuto = false
    /** 김서림을 알아서 바꾼 때("되돌려"를 3분 안에 받는다) */
    private var fogAutoAt = 0L
    /** 알아서 바꾼 바깥 공기가 실제로 들어온 것을 봤나(그 뒤에 안쪽 공기가 되면 사람이 되돌린 것) */
    private var fogSawOutside = false

    /** 알아서 바꾼 김서림을 되돌리고, 다음부터는 묻는다 */
    private fun undoFog(why: String) {
        fogAutoAt = 0L
        memory.fogAsk = true
        Prend.log("◆ 알아서 김서림 되돌림($why)")
        CarLink.hubOrNull?.setRecirc(true) { ok -> Stage.toast(if (ok) "안쪽 공기로 되돌렸어요 · 다음에는 먼저 여쭐게요" else "되돌리지 못했어요. 공조의 내기 순환 버튼을 눌러 주세요", 3_500) }
    }

    private fun answerFog(text: String, byButton: Boolean) {
        if (fogAuto) {
            // [되돌리기](버튼은 "괜찮아"로 온다)와 분명히 되돌리라는 말만 되돌린다. 말로 한 "괜찮아"·"좋아"는 그대로 둔다(검토 11/07)
            val undo = if (byButton) Answer.no(text) else undoSaid(text)
            Prend.log("· 알아서 김서림 대답(${if (byButton) "버튼" else "말 ${text.length}자"}) → ${if (undo) "되돌리기" else "그대로"}")
            speaker.stop()
            if (text.isBlank() && !byButton) { later(15_000) { if (asking == Line.FOG) closeSimple() }; return }
            closeSimple()
            if (undo) undoFog("말·버튼")
            return
        }
        val yes = yesNo(text, listOf("바꿔", "바깥"))
        logAnswer("김서림", text, byButton, yes)
        speaker.stop()
        when (yes) {
            true -> { closeSimple(); app.prend.car.CarLink.hubOrNull?.freshAir { ok -> Stage.toast(if (ok) "바깥 공기로 바꿨어요" else "바꾸지 못했어요. 공조의 내기 순환 버튼을 눌러 주세요", 3_000) } }
            false -> { closeSimple(); decline(Line.FOG, "괜찮아요") }
            null -> if (!unclear(Line.FOG, text, byButton) { answerFog(it, byButton = false) }) later(15_000) { if (asking == Line.FOG) { decline(Line.FOG, "대답 없음"); closeSimple() } }
        }
    }

    // ── 한 시간·주차 자리·대리 운행·이야기 비켜 서기 ──

    /**
     * "필요한 것 있으세요?"의 대답. "괜찮아"면 거두고(두 번 이어지면 묻지 않음), 요청이 있으면 [프렌드]에 한 말처럼 고른다
     * (바깥 공기·열선·통풍·음악·쉼터·카페…). "응"만 했으면 무엇이 필요한지 다시 듣는다. 첫 버튼(그때 차에 맞게 고른 것)은 바로 한다.
     * 넷 가운데 고른 것은 기억해 다음에 맞으면 첫 버튼으로 먼저 내민다.
     * 검토 11/07 멋대로 판단: 지금 상태를 말한 것("지금 딱 시원해요"·"따뜻해서 좋아"·"음악 좋네")은 괜찮다는 말이라 켜지 않고 부탁하는 꼴일 때만 한다.
     * 되물은 "네?"는 한 번 다시 묻고, 알아듣지 못한 말은 AI 대역에 보내 짐작하게 하지 않고 대답이 없던 것처럼 둔다
     */
    private fun answerHour(text: String, byButton: Boolean) {
        val s = text.replace(" ", "")
        // 끄기·막기 말("음악 꺼 줘", "외기 차단해")은 [프렌드]에 한 말처럼 그대로 넘기고, "안 추워요"·"춥지 않아"처럼 부정이 붙으면 괜찮아요로 본다
        val off = listOf("꺼", "끄", "그만", "차단", "막아", "닫아").any { it in s }
        val neg = listOf("안추", "안더", "안시원", "안따뜻", "필요없", "괜찮", "됐어", "없어", "지않").any { it in s }
        // 장르·노래 이름("트로트 틀어 줘")은 PREND 곡으로 들어줄 수 없다: [프렌드]에 한 말처럼 넘겨 그 알림을 띄운다(11/04 검토)
        val genre = Stage.GENRE.any { it in s }
        // 무엇을 말했나. 열선·통풍을 바깥 공기보다 먼저 본다("바깥 날씨 추우니까 열선 켜 줘"는 열선, 검토 11/07)
        val offer = if (off || neg || genre) null else when {
            "열선" in s -> Offer.HEAT
            "통풍" in s -> Offer.VENT
            listOf("바깥", "공기", "환기", "외기").any { it in s } -> Offer.AIR
            listOf("음악", "노래", "곡").any { it in s } -> Offer.MUSIC
            (HOUR_COLD + "따뜻").any { it in s } -> Offer.HEAT
            (HOUR_HOT + "시원").any { it in s } -> Offer.VENT
            else -> null
        }
        // 부탁하는 꼴일 때만 한다: 버튼, 켜·틀어·넣어·시원하게 같은 말, 춥다·덥다·(공기가) 답답하다는 불편, 카드가 권한 넷의 이름만("열선", "바깥 공기", "음악 좀").
        // 말버릇("그냥 음악")이 요청을 삼키지 않게 이름만인 말은 요청으로 받는다
        // "시원한 바람 좀"처럼 '좀'으로 끝나는 말도 부탁이다("지금 좀 시원하네"는 아니다)
        val asked = byButton || Answer.has(text, HOUR_ASK) || (HOUR_COLD + HOUR_HOT + "답답").any { it in s } || namesOnly(text) ||
            text.trim().trimEnd('.', '!', '?', '~').endsWith("좀")
        val want = offer?.takeIf { asked }?.want
        // 지금 상태를 말한 것은 괜찮다는 말이다
        val state = offer != null && want == null
        val no = want == null && !off && (neg || state || listOf("아니").any { it in s })
        val unsure = !byButton && Answer.unsure(text)
        val bare = want == null && !no && !unsure && (Answer.short(text) || s.length <= 3 && "그래" in s)
        // 다른 부탁(쉼터·화장실·카페·창문·아플 때…)과 끄기·장르 말만 [프렌드]에 한 말처럼 낱말 규칙으로 고른다
        val other = want == null && !no && !unsure && !bare && (off || genre || asksSomething(text) || HOUR_OTHER.any { it in s })
        Prend.log("· 한 시간 대답(${if (byButton) "버튼" else "말 ${text.length}자"}) → ${when { s.isBlank() -> "없음"; want != null -> "요청($want)"; state -> "지금 상태(괜찮아요)"; no -> "괜찮아요"; unsure -> "되물음"; bare -> "예(다시 듣기)"; other -> "요청"; else -> "알아듣지 못함" }}")
        speaker.stop()
        when {
            // "어어 잠깐만"처럼 곁의 사람에게 한 말은 대답이 아니다: 대답 없음처럼 카드만 남긴다(11/04 검토)
            (s.isBlank() || Answer.aside(text) && want == null) && !byButton -> later(15_000) { if (asking == Line.HOUR) { decline(Line.HOUR, "대답 없음"); closeSimple() } }
            want != null && offer != null -> {
                closeSimple(); memory.answered(Memory.Kind.HOUR, true); memory.hourPicked(offer.name); Stage.request(offer.want)
                // 바깥 공기를 세 번 연달아 골랐으면 다음부터 알아서(알림은 바꾼 일의 알림 뒤에)
                memory.hourAir(offer == Offer.AIR)?.let { l -> main.postDelayed({ tell(l) }, 3_500) }
            }
            no -> { closeSimple(); decline(Line.HOUR, "괜찮아요"); memory.hourAir(false); tell(memory.answered(Memory.Kind.HOUR, false)) }
            // 되물은 "네?"는 예가 아니다: 한 번 다시 묻고, 그래도 모르면 대답이 없던 것처럼(기억에 세지 않는다)
            unsure -> if (!unclear(Line.HOUR, text, byButton) { answerHour(it, byButton = false) }) later(15_000) { if (asking == Line.HOUR) { decline(Line.HOUR, "대답 없음"); closeSimple() } }
            bare -> { closeSimple(); memory.answered(Memory.Kind.HOUR, true); Stage.toast("필요한 걸 말씀해 주세요", 3_000); Stage.call() }
            other -> { closeSimple(); memory.answered(Memory.Kind.HOUR, true); memory.hourAir(false); Stage.request(text) }
            // 알아듣지 못한 말은 AI 대역에 보내 짐작하게 하지 않는다(검토 11/07): 대답이 없던 것처럼 카드만 15초
            else -> later(15_000) { if (asking == Line.HOUR) { decline(Line.HOUR, "대답 없음"); closeSimple() } }
        }
    }

    /** 한 시간 물음의 대답에서 부탁하는 꼴("켜 줘"·"틀어"·"시원하게"·"넣어"·"세게") */
    private val HOUR_ASK = listOf("켜", "틀어", "틀까", "시원하게", "따뜻하게", "넣어", "바꿔", "세게", "강하게", "올려", "높여", "해줘", "해주", "부탁", "줘", "주세요")
    /** 춥다·덥다는 불편(이 말이면 부탁으로 받는다. "시원해요"·"따뜻해서 좋아"는 지금 상태라 넣지 않는다) */
    private val HOUR_COLD = listOf("추워", "춥", "추운", "추우")
    private val HOUR_HOT = listOf("더워", "덥", "더운", "더우")
    /** 카드가 권한 넷의 이름과 이름 곁의 말버릇(이름만 말한 것은 부탁이다: "열선", "바깥 공기", "음악 좀", "응 통풍이요") */
    private val HOUR_NAMES = setOf("열선", "통풍", "바깥", "공기", "바깥공기", "환기", "외기", "음악", "노래", "곡", "잔잔한", "잔잔한곡", "시트")
    private val HOUR_FILL = setOf("좀", "그냥", "음", "어", "응", "네", "예", "그럼")
    /** 한 시간 물음의 대답이 그 일 말고 다른 부탁인가(낱말 규칙이 알아듣는 것: 쉼터·화장실·카페·창문·아플 때…) */
    private val HOUR_OTHER = listOf("쉼터", "휴게소", "졸려", "졸리", "졸음", "쉬어", "쉬고", "쉬자", "화장실", "카페", "커피", "밥", "식당", "창문", "조용히", "빗소리",
        "멀미", "울렁", "주유", "기름", "충전", "아파", "아프", "아퍼", "열이", "숨이", "숨을", "가슴")

    /** 카드가 권한 넷의 이름만 말했나(말버릇·토씨는 뗀다) */
    private fun namesOnly(text: String): Boolean {
        val w = text.trim().split(Regex("[\\s,.!?~]+")).filter { it.isNotBlank() }
            .map { it.removeSuffix("요").removeSuffix("이").removeSuffix("을").removeSuffix("를").removeSuffix("은").removeSuffix("는") }
            .filter { it !in HOUR_FILL }
        return w.isNotEmpty() && w.all { it in HOUR_NAMES }
    }

    /** 부탁하는 꼴의 말(물음의 대답이 아니라 다른 부탁인지 가를 때, 검토 11/07). 낱말 앞자리에서만 찾고 부정한 것은 뺀다 */
    private val ASKS = listOf("켜", "꺼", "끄", "틀어", "넣어", "바꿔", "찾아", "들러", "들르", "열어", "닫아", "올려", "내려", "줄여", "키워", "높여", "낮춰", "세게", "약하게", "해줘", "해주", "줘", "주세요", "부탁")
    private fun asksSomething(text: String) = Answer.has(text, ASKS)

    /** 주차 자리 알림의 대답. "그만"이면 다음부터 알리지 않는다 */
    private fun answerSpot(text: String, byButton: Boolean) {
        val s = text.replace(" ", "")
        val stop = listOf("그만", "안해도", "필요없", "됐어", "알리지마").any { it in s }
        Prend.log("· 주차 자리 대답(${if (byButton) "버튼" else "말 ${text.length}자"}) → ${if (stop) "그만" else if (s.isBlank()) "없음" else "알겠어요"}")
        speaker.stop()
        when {
            stop -> { closeSimple(); memory.quiet(Memory.Kind.SPOT, "“그만 알려요” 하셔서"); Stage.toast("다음부턴 주차 자리를 알리지 않을게요", 3_000) }
            s.isBlank() && !byButton -> later(10_000) { if (asking == Line.SPOT) closeSimple() }
            else -> closeSimple()
        }
    }

    private fun answerDaeri(text: String, byButton: Boolean) {
        val s = text.replace(" ", "")
        val no = Answer.no(text, listOf("안불렀", "내가", "직접"))
        // 되묻는 말("대리? 무슨 대리?")은 예가 아니다(11/04 사람 시험: 예로 받아 그 운행 내내 말을 걸지 않았다)
        val backAsk = listOf("무슨", "뭔", "뭐", "왜").any { it in s } || text.contains('?')
        // '어'·'엉' 한마디나 "어, 그래서…"로는 대리 운행으로 바꾸지 않는다(검토 11/07 멋대로 판단: 대답 하나가 그 운행의 졸음 안내를 모두 끈다)
        val yes = !no && !backAsk && Answer.of(text, listOf("대리", "맞아", "그래")) == true
        logAnswer("대리 운행", text, byButton, if (yes) true else if (no) false else null)
        speaker.stop()
        when {
            yes -> { closeSimple(); memory.answered(Memory.Kind.DAERI, true); setDaeri(true, byAsk = true) }
            no -> { closeSimple(); tell(memory.answered(Memory.Kind.DAERI, false)) }
            // 대답이 없거나 끝내 알아듣지 못했으면 카드를 15초 두고, 그래도 대답이 없으면 대답 없이 지나간 것으로 센다
            else -> if (!unclear(Line.DAERI, text, byButton) { answerDaeri(it, byButton = false) }) later(15_000) { if (asking == Line.DAERI) { noAnswer(Line.DAERI); closeSimple() } }
        }
    }

    /** 대리 운행을 켜고 끈다. 켜면 줄에 선 먼저 건넬 말과 지금 묻던 말을 거두고, 한마디로 알린다 */
    private fun setDaeri(on: Boolean, byAsk: Boolean) {
        if (Stage.ui.value.daeri == on) return
        Stage.ui.value = Stage.ui.value.copy(daeri = on)
        Prend.log("◆ 대리 운행 ${if (on) "켬" else "끔"}(${if (byAsk) "물음" else "말·버튼"})")
        // 대리 운행은 주인이 쉬는 길이라 잘래요를 기본으로 켠다(사용자 결정 10/31: 기사 옆에서 "졸려"라고 말하지 않아도 되게).
        // 끄면 잘래요도 끝낸다("대리 끝"은 깨어 있다는 뜻)
        if (!on) { if (Stage.ui.value.sleeper != null) Stage.onNap?.invoke("sleep_off", 0); return }
        if (Stage.ui.value.sleeper == null) Stage.onNap?.invoke("sleep_on_quiet", 0)
        // 이미 대리 운행이니 물음도 다시 하지 않는다
        daeriAsked = true
        presenceLine?.let { if (it in DAERI_QUIET || it == Line.DAERI) cancelPresence("대리 운행", drop = true) }
        val dropped = queue.filter { it in DAERI_QUIET || it == Line.DAERI }
        if (dropped.isNotEmpty()) { queue.removeAll(dropped); Prend.log("· 대리 운행이라 줄의 말 버림: ${dropped.joinToString { it.name }}") }
        // 하던 말과 대답 듣기도 멈춘다(끝나면 대답 창이 열려 쉼터·무드등을 바꾸지 않게)
        if ((asking ?: speakingLine) in DAERI_QUIET) { speaker.stop(); Stage.endListen(); closeAny() }
        // [네, 대리예요]로 켰을 때도 말·버튼으로 켤 때와 같은 한 줄을 남긴다(감사 F11 ②)
        if (byAsk) { speakLine("voice/daeri_on.wav"); Stage.toast(Stage.DAERI_ON, 3_500) }
    }

    /** 이야기에 비켜 서기를 켤까요? "응"·[켜 둘게요]면 켜고, "괜찮아요"면 끈 채로 둔다(둘 다 정한 것으로 보고 다시 묻지 않는다) */
    private fun answerTalk(text: String, byButton: Boolean) {
        // 마이크 동의라 곁의 말("어 그래서…")이나 "필요 없어"·"켜지 마"를 예로 읽지 않는다: 거절 낱말을 먼저 보고, 예는 짧은 대답 전체가 맞을 때만
        val s = text.replace(" ", "").trimEnd('.', '!', '?', '~').removeSuffix("요")
        val yes = when {
            // 되물은 "응?"은 물음표를 떼면 "응"이 된다: 먼저 거른다(검토 11/07)
            Answer.unsure(text) -> null
            TALK_NO.any { it in s } -> false
            s in TALK_YES -> true
            else -> null
        }
        logAnswer("이야기 비켜 서기", text, byButton, yes)
        speaker.stop()
        when (yes) {
            true -> { closeSimple(); onTalkAnswer(true) }
            false -> { closeSimple(); onTalkAnswer(false) }
            null -> if (!unclear(Line.TALK, text, byButton) { answerTalk(it, byButton = false) }) later(15_000) { if (asking == Line.TALK) closeSimple() }
        }
    }

    // ── 조용히 가기·거절한 물음 ──

    /**
     * 조용히 가기를 켜고 끈다(그 운행만). 켜면 한마디로 받는다("네, 오늘은 조용히 모실게요"). 받은 글은 화면(Stage.quietRide)이 띄운다.
     * asleep은 "애 잔다"처럼 누가 잔다는 말, kid는 그 말에 아이가 들었다(검토 11/06: 카시트 아이는 좌석 센서가 몰라 혼자로 보고 목소리로 대답했다)
     */
    private fun setQuietRide(on: Boolean, asleep: Boolean = false, kid: Boolean = false) {
        // 아이가 잔다고 했으면 이번 운행 아이 탑승도 켠다(사고 때 "뒷좌석에 아이가 있어요"). 이미 조용히 가는 중이어도
        if (on && kid && !Stage.ui.value.baby) baby.toggle(true)
        if (Stage.ui.value.quietRide == on) return
        Stage.ui.value = Stage.ui.value.copy(quietRide = on)
        Prend.log("◆ 조용히 가기 ${if (on) "켬" else "끔"}(이번 운행${if (asleep) ", 누가 잔다고 함" else ""})")
        if (on) {
            // 동승자가 있거나 누가 잔다고 했으면(카시트 아이는 좌석 센서에 잡히지 않는다) 목소리 대신 작은 차임과 글로 받는다(11/04 사람 시험: 조용히 하랬더니 크게 대답)
            if (alone(lastCar) && !asleep && !Stage.ui.value.baby) speakLine("voice/quiet_ride.wav")
            else runCatching { app.prend.sound.Chime.play(soft = true) }
        }
        // 혼자 탄 운행에서 사람이 부른 조용히 가기는 그 칸에 센다(운행마다 한 번). 두 번이면 다음부터 먼저(알림은 받은 한마디 뒤에, 글로만).
        // 누가 잔다고 했거나 아이가 탄 운행은 혼자가 아니라 세지 않는다(아이가 없는 그 시간대 혼자 운행까지 먼저 조용히 가지 않게)
        if (on && !asleep && !quietCounted && !Stage.ui.value.daeri && alone(lastCar) && !Stage.ui.value.baby) {
            quietCounted = true
            memory.quietCalled(Memory.slot())?.let { l -> main.postDelayed({ tell(l) }, 3_500) }
        }
    }

    /** 이번 운행에 조용히 가기를 칸에 셌나, 배운 칸인지 출발할 때 보았나, 먼저 조용히 둔 칸 */
    private var quietCounted = false
    private var quietChecked = false
    private var quietSlot: String? = null

    /** 띄운 위치 알림 카드가 보인 초(tick이 1초마다 센다. GEO_SEEN_S에 닿으면 건넨 것으로 센다), 보이지 않아 기다린다고 적었나(띄우면 다시) */
    private var geoSeenS = 0
    private var geoWaitLogged = false

    /**
     * 목소리 없이 카드로만 건넬 까닭. 목소리로 건네면 null.
     * 조용히 가는 운행은 쉼터·혹시 사고만 목소리로(V10), 다른 앱 음악이 흐르면 쉼터·혹시 사고·주차 자리만 그 음악 위에 얹는다(V16)
     */
    /** 카드로 갈 물음이 설 때를 기다리기 시작한 때(달리는 동안 카드를 띄우지 않는다, 11/02) */
    private val cardWaitSince = mutableMapOf<Line, Long>()

    private fun cardOnly(line: Line): String? = when {
        // 위치 알림은 처음 한 번 읽고 고르는 카드(목소리 없이, 기척도 없이)
        line == Line.GEO -> "처음 한 번 알리는 카드"
        // 동승자가 잘래요로 쉬는 동안의 한 시간 물음(알아서 한 바깥 공기 포함)은 운전자에게만 카드로(목소리가 쉬는 사람을 깨우지 않게)
        line == Line.HOUR && nap.sleeping -> "옆자리가 쉬는 중"
        // 쉬는 동안의 김서림은 먼저 여쭙기로 바뀐 사람에게만 카드로 묻는다. 알아서 바꾸는 쪽은 목소리가 없어 그대로 한다(검토 11/07)
        line == Line.FOG && nap.sleeping && !selfActs(line, lastCar) -> "옆자리가 쉬는 중"
        Stage.ui.value.quietRide && line !in QUIET_VOICE -> "조용히 가는 중"
        line !in MUSIC_VOICE && jam.otherMusicOn() -> "다른 앱 음악이 흐름"
        // 세워 두고 화면을 보는 동안만 묻는 설정이라 화면 카드로 충분하다(가장 긴 먼저 하는 말이었다, 11/01)
        line == Line.TALK -> "화면을 보는 동안의 설정 물음"
        // 손주·아내·손님 앞에서 대리 운행·지난 사고·정체·주차 자리·다시 앉았을 때의 사고 확인을 소리 내 말하지 않는다(사용자 결정 11/04, 사람 시험 9명)
        line in GUEST_CARD && !alone(lastCar) -> "동승자가 있어 소리 없이"
        // 동승자가 있는데 말소리 감지가 꺼져 있으면 대화 중인지 알 수 없다: 쉼터·혹시 사고 말고는 카드로만(별첨 2 V15, 대화에 끼어드는 것이 가장 나쁜 실패).
        // 동승자가 등받이를 젖힌 잘래요·쪽잠 물음은 그 사람의 몸짓에 답하는 말이라 목소리로
        line in FLOOR_ASK && !alone(lastCar) && !Stage.ui.value.talkLive -> "동승자가 있는데 말소리 감지가 돌지 않음"
        // 앞 물음 뒤 30분 안(V13)이거나 이번 운행 목소리 물음이 두 번(V14)이면 카드로만. 대리 운행 물음은 그 대답이 다른 말을 모두 끄는 물음이라 뺀다
        line in FLOOR_ASK && line != Line.DAERI && floor?.cardWhy() != null -> floor.cardWhy()
        line == cardFor -> "말 차례가 아님(내리기 전 그때뿐이라 카드로)"
        else -> null
    }

    /** 그 운행에서 다시 묻지 않을 물음으로 적는다(쉼터·혹시 사고처럼 그때마다 다시 볼 일은 빼고) */
    private fun decline(line: Line, why: String) {
        if (line !in ONCE || !declined.add(line)) return
        queue.remove(line)
        Prend.log("· ${line.name}: $why → 이번 운행에서 다시 묻지 않음")
    }

    // ── 같은 운행 잇기(감사 F02) ──

    /** 지금의 운행 범위 값. 프로세스가 지금 꺼진다면 다음에 이을 모습으로 적는다 */
    private fun snapshot(): JSONObject {
        val ui = Stage.ui.value
        // 묻던 물음은 다시 뜨면 대답 없이 넘긴 것으로 친다(그 운행에서 다시 묻지 않을 물음만). 쉼터를 묻던 중이면 30분 뒤 다시
        val gone = asking?.takeIf { requeueAsk && it in ONCE }
        val rest = if (restAt == Int.MAX_VALUE) lastMinutes + REST_AGAIN else restAt
        // 혹시 사고는 그 자리의 일이라 잇지 않는다
        val lines = queue.filter { it !in BUMPS }
        return JSONObject()
            .put("restAt", rest).put("restReoffered", restReoffered).put("hourAsked", hourAsked)
            .put("daeriAsked", daeriAsked).put("talkAsked", talkAsked).put("spotDone", spotDone).put("moved", moved)
            .put("kidsAsked", baby.asked).put("geoAsked", GeoShare.asked)
            .put("quietChecked", quietChecked).put("quietCounted", quietCounted).put("quietSlot", quietSlot ?: JSONObject.NULL).put("drove", drove)
            .put("rideStart", RideKeep.wall(rideStartAt)).put("restAccepted", RideKeep.wall(restAcceptedAt))
            .put("jamAsked", jam.askedWall).put("pickAsked", together.askedWall)
            .put("declined", JSONArray((declined + listOfNotNull(gone)).map { it.name }))
            .put("queue", JSONArray(lines.map { it.name }))
            .put("baby", ui.baby).put("daeri", ui.daeri).put("quietRide", ui.quietRide)
            .apply { Bridge.rain?.let { put("rain", it) } }
    }

    /** 운행 범위 값을 적어 둔다(1초마다 부른다. 바뀌었을 때만 쓴다) */
    private fun keep() { kept?.save(snapshot()) }

    /**
     * 같은 운행 중 프로세스가 다시 떴다(운행 서비스가 새 운행이 아니라고 본 때): 적어 둔 운행 범위 값을 잇는다.
     * 연속 운전의 지난 분(lastMinutes)은 잇지 않는다: 운전 시간은 10초마다 적혀 1분 덜 읽힐 수 있고, 그러면 쉰 것으로 보고 쉼터 권하기를 처음부터 한다
     */
    fun restore() {
        val j = kept?.load() ?: return Prend.log("· 이어 갈 운행 상태 없음")
        val names = { key: String -> j.optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { i -> Line.entries.firstOrNull { it.name == a.optString(i) } } }.orEmpty() }
        restAt = j.optInt("restAt", REST_FIRST)
        restReoffered = j.optBoolean("restReoffered")
        hourAsked = j.optBoolean("hourAsked")
        daeriAsked = j.optBoolean("daeriAsked")
        talkAsked = j.optBoolean("talkAsked")
        spotDone = j.optBoolean("spotDone")
        moved = j.optBoolean("moved")
        rideStartAt = RideKeep.elapsed(j.optLong("rideStart"))
        restAcceptedAt = RideKeep.elapsed(j.optLong("restAccepted"))
        jam.askedWall = j.optLong("jamAsked")
        together.askedWall = j.optLong("pickAsked")
        declined.clear(); declined += names("declined")
        queue.clear(); queue += names("queue")
        // 화면과 소리에 걸린 이번 운행의 설정(대리 운행·조용히 가기는 알리는 한마디 없이 조용히 되돌린다)
        Stage.ui.value = Stage.ui.value.copy(daeri = j.optBoolean("daeri"), quietRide = j.optBoolean("quietRide"))
        // 대리 운행이면 주인 자리 잘래요도 다시(잘래요 상태는 잇지 않으니 대리 운행 기본값으로, 쪽잠 쪽이 붙은 뒤에)
        if (j.optBoolean("daeri")) later(1_500) { if (Stage.ui.value.daeri && Stage.ui.value.sleeper == null) Stage.onNap?.invoke("sleep_on_quiet", 0) }
        baby.restore(j.optBoolean("baby"), j.optBoolean("kidsAsked"))
        GeoShare.restore(j.optBoolean("geoAsked"))
        quietChecked = j.optBoolean("quietChecked"); quietCounted = j.optBoolean("quietCounted")
        quietSlot = if (j.isNull("quietSlot")) null else j.optString("quietSlot").ifEmpty { null }
        drove = j.optBoolean("drove")
        if (j.has("rain")) j.optBoolean("rain").let { r -> Bridge.rain = r; Bridge.director?.let { it.manualRain = r; it.rainOff = !r } }
        val ui = Stage.ui.value
        Prend.log("◆ 같은 운행을 이어 감(다음 쉼터 ${restAt}분, 거절한 물음 ${declined.joinToString { it.name }.ifEmpty { "없음" }}, 줄 ${queue.joinToString { it.name }.ifEmpty { "없음" }}" +
            "${if (ui.baby) ", 아이 탑승" else ""}${if (ui.daeri) ", 대리 운행" else ""}${if (ui.quietRide) ", 조용히" else ""})")
    }

    /**
     * 시험: 프로세스가 다시 뜬 것처럼. 지금 값을 적고, 운행 범위 값을 새 프로세스의 처음 값으로 비운 뒤 restore()로 잇는다.
     * 기록의 '같은 운행을 이어 감' 줄이 바로 앞 '비우기 전' 줄과 같으면 된다
     */
    fun reloadTest() {
        keep()
        Prend.log("· 시험: 운행 상태 다시 읽기(비우기 전 다음 쉼터 ${restAt}분, 거절한 물음 ${declined.joinToString { it.name }.ifEmpty { "없음" }}, 줄 ${queue.joinToString { it.name }.ifEmpty { "없음" }})")
        speaker.stop(); Stage.endListen(); cardTimeout?.let { main.removeCallbacks(it) }
        cancelPresence("시험", drop = true); presenceOff("시험")
        speakingLine = null; asking = null; Stage.ask(null)
        restAt = REST_FIRST; restReoffered = false; lastMinutes = 0; hourAsked = false; daeriAsked = false; talkAsked = false; spotDone = false; moved = false
        rideStartAt = 0L; restAcceptedAt = 0L; jam.askedWall = 0L; together.askedWall = 0L
        declined.clear(); queue.clear()
        Stage.ui.value = Stage.ui.value.copy(daeri = false, quietRide = false, baby = false)
        restore()
    }

    // ── 말하기 전 기척(별첨 2 §2-2 ①) ──

    /**
     * 이 말 앞에 기척을 보이나: 목소리로 건네는 말만. 혹시 사고(기다리지 않음)·급할 때·쪽잠·잘래요·대리 운행에는 없이.
     * 한 운행 여섯 번에 닿은 뒤의 알아서 김서림·한 시간(글 한 줄만)에도 없이
     */
    private fun presenceFirst(line: Line): Boolean {
        val ui = Stage.ui.value
        return line !in BUMPS && cardOnly(line) == null && !ui.daeri && !ui.scene.isE && !nap.napping && !nap.sleeping &&
            !(line in FLOOR_ASK && floor?.full == true)
    }

    /**
     * 2초 기척을 시작한다: 무드등이 1~15%면 5%p 올리고(색은 그대로), 아니면(꺼짐·밝음·없음) 작은 차임. 위 막대에 민트 점.
     * 대답이 무드등을 바꾸는 말(정체·쪽잠·잘래요)은 차임으로 한다(그 일이 적어 두는 '원래 밝기'가 엇갈리지 않게)
     */
    private fun startPresence(line: Line, minutes: Int) {
        presenceLine = line
        presenceAt = SystemClock.elapsedRealtime()
        presenceMinutes = minutes
        Stage.ui.value = Stage.ui.value.copy(presence = true)
        main.postDelayed(presenceCheck, PRESENCE_STEP_MS)
        val hub = CarLink.hubOrNull
        if (line in LIGHT_OWN || hub?.ambientArea == null) { presenceChime(line, null); return }
        hub.readAmbient { b, c ->
            // 그사이 삼켰거나 이미 건넸으면 빛을 올리지 않는다
            if (presenceLine != line) return@readAmbient
            if (b == null || c == null || b !in 1..PRESENCE_MAX) { presenceChime(line, b); return@readAmbient }
            val up = b + PRESENCE_UP
            lit = Triple(b, up, c)
            Prend.log("◆ 기척: 무드등 $b → $up(${line.name})")
            hub.setAmbient(up, c) { ok -> if (!ok) { lit = null; Prend.log("· 기척: 무드등 올리기 실패") } }
        }
    }

    private fun presenceChime(line: Line, b: Int?) {
        // 다른 앱 음악 위에서는 말 바로 앞의 알림음(say)이 대신한다: 작은 차임은 음악에 묻히고, 두 번 울리면 같은 신호가 겹친다
        if (jam.otherMusicOn()) { Prend.log("· 기척: 다른 앱 음악 위라 차임 대신 말 앞 알림음(${line.name})"); return }
        Prend.log("◆ 기척: 차임(${line.name}, 무드등 ${b ?: "모름"})")
        runCatching { Chime.play(soft = true) }
    }

    /** 0.25초마다: 말할 때가 닫히면 삼키고, 2초가 지나면 건넨다 */
    private fun presenceStep() {
        val line = presenceLine ?: return
        val car = CarLink.state
        val ui = Stage.ui.value
        val resting = nap.napping || nap.sleeping
        dropWhy(line, car, resting)?.let { cancelPresence(it, drop = true); if (line == Line.TOILET) toiletGone(it); return }
        val why = when {
            ui.scene.isE -> "급할 때"
            speaker.inCall -> CALL
            ui.listen != null -> "프렌드를 부름"
            // 기척 사이 확인 카드가 떴으면 덮지 않고 줄 앞에 되돌린다(검토 11/07)
            line !in BUMPS && ui.ask?.id?.startsWith("Q_") == true -> CONFIRM
            // 고속도로 정차 카드도 덮지 않는다(11/08 오류 점검 C2)
            line !in BUMPS && ui.ask?.id == "HSTOP" -> HSTOP_UP
            speaker.speaking -> "다른 한마디"
            waits(line, car) -> "지금은 건너뜀"
            // 편한 때를 기다려 건네던 말은 그 2초 사이 편한 때가 닫히면(신호에서 출발 등) 삼킨다(별첨 2 V5)
            line in COMFY_WAIT && timing.comfy(car) == null -> "편한 때가 닫힘"
            else -> blockFor(car, line, starting = true)
        }
        if (why != null) { cancelPresence(why); return }
        if (SystemClock.elapsedRealtime() - presenceAt < PRESENCE_MS) { main.postDelayed(presenceCheck, PRESENCE_STEP_MS); return }
        presenceLine = null
        deliver(line, presenceMinutes)
    }

    /** 기척 중이던 말을 삼킨다: 빛을 되돌리고, 줄 앞에 되돌린다(drop이면 버린다) */
    private fun cancelPresence(why: String, drop: Boolean = false) {
        val line = presenceLine ?: return
        presenceLine = null
        main.removeCallbacks(presenceCheck)
        presenceOff("삼킴")
        if (!drop) { queue.remove(line); queue.addFirst(line) }
        heldUntil = maxOf(heldUntil, SystemClock.elapsedRealtime() + 10_000)
        swallowed = why
        presenceHeld++
        Prend.log("◆ 기척 중 말 삼킴($why): ${line.name}${if (drop) " 버림" else ", 편해지면 다시"}(이번 운행 ${presenceHeld}번째)")
    }

    /** 기척을 거둔다: 점을 끄고, 올린 무드등을 되돌린다. 그사이 사람이 밝기나 색을 바꿨으면 그대로 둔다 */
    private fun presenceOff(why: String) {
        if (Stage.ui.value.presence) Stage.ui.value = Stage.ui.value.copy(presence = false)
        val (from, up, color) = lit ?: return
        lit = null
        val hub = CarLink.hubOrNull ?: return
        hub.readAmbient { b, c ->
            when {
                // 막 올린 값이 아직 차에 반영되지 않았으면 원래 밝기로 읽힌다: 그때도 원래 밝기로 한 번 더 둔다(늦게 오른 빛이 남지 않게)
                (b == up || b == from) && (c == null || c == color) -> hub.setAmbient(from, color) { ok -> Prend.log("· 기척 끝($why): 무드등 $up → $from ${if (ok) "되돌림" else "되돌리기 실패"}") }
                else -> Prend.log("· 기척 끝($why): 사람이 무드등을 바꿔 그대로 둠")
            }
        }
    }

    // ── 한 시간 물음의 첫 버튼 ──

    /**
     * 한 시간 물음의 첫 버튼: label은 버튼 글, want는 [프렌드]에 한 말처럼 고를 요청(route가 그대로 알아듣는 말).
     * REST는 맞는 것이 하나도 없을 때만(배너의 [쉬어 갈 곳]과 같다. 고른 것으로 기억하지 않는다)
     */
    private enum class Offer(val label: String, val want: String) {
        AIR("바깥 공기로", "바깥 공기"), HEAT("열선 켜기", "열선"), VENT("통풍 켜기", "통풍"), MUSIC("잔잔한 곡", "음악 틀어"), REST("쉬어 갈 곳", "쉼터"),
    }

    /**
     * 그때 차에 맞는 첫 버튼: 안쪽 공기만 10분 넘게(비·5°C 이하면 바로) 돌렸으면 바깥 공기, 3°C 이하에 열선이 꺼져 있으면 열선,
     * 28°C 이상에 통풍이 꺼져 있으면 통풍, 아무 음악도 없으면 잔잔한 곡. 맞는 것 가운데 지난번 고른 것을 먼저.
     * 맞는 것이 없으면(바깥 공기가 이미 들어오고 기온이 알맞고 음악이 흐름) 이미 켜진 [바깥 공기로] 대신 배너처럼 [쉬어 갈 곳](검토 11/06)
     */
    private fun offer(car: CarState): Offer = fits(car).let { fits ->
        val last = memory.habits.hourPick?.let { p -> Offer.entries.firstOrNull { it.name == p } }
        last?.takeIf { it in fits } ?: fits.firstOrNull() ?: Offer.REST
    }

    private fun fits(car: CarState): List<Offer> {
        val ui = Stage.ui.value
        val t = ui.outsideC
        val hub = CarLink.hubOrNull
        val inside = car.recirc == true && recircSince > 0
        val long = inside && SystemClock.elapsedRealtime() - recircSince > 10 * 60_000L
        // 이 차에 열선·통풍이 있을 때만(없으면 버튼을 눌러도 할 수 없다)
        val heat = (hub?.heatRange?.last ?: 0) > 0
        val vent = (hub?.ventRange?.last ?: 0) > 0 || (hub?.heatRange?.first ?: 0) < 0
        return buildList {
            if (long || inside && (car.wiperOn || (t != null && t <= 5))) add(Offer.AIR)
            if (heat && t != null && t <= 3 && (ui.heat ?: 0) <= 0) add(Offer.HEAT)
            if (vent && t != null && t >= 28 && (ui.vent ?: 0) <= 0 && (ui.heat ?: 0) >= 0) add(Offer.VENT)
            if (!jam.musicOn()) add(Offer.MUSIC)
        }
    }

    /** 시험: 지금 한 시간 물음을 하면 첫 버튼이 무엇인지 */
    fun offerDump() = Prend.log("· 시험: 한 시간 첫 버튼 ${offer(lastCar).name}(맞는 것 ${fits(lastCar).joinToString { it.name }}, 지난번 ${memory.habits.hourPick ?: "없음"})")

    /** 시험: 이번 운행에 거절한 물음과 주차 자리 알림 */
    fun declinedDump() = Prend.log("· 시험: 거절한 물음 ${declined.joinToString { it.name }.ifEmpty { "없음" }} · 주차 자리 ${if (spotDone) "알림" else "아직"} · 기척 중 삼킨 말 ${presenceHeld}")

    /**
     * 주차 자리를 알릴 때: 낮(7~18시)인데 차가 어둡다고 본다(NIGHT_MODE). 대리 운행·묻지 않기·급할 때는 아니다.
     * 내비 안내로 찾아와 10분 안에 도착한 날에만 알린다(날마다 가는 회사 지하처럼 길을 아는 곳에서는 묻지 않는다, 매력 검토 16).
     * 어디인지는 보지 않고 '안내로 도착했나'로만 가른다
     */
    private fun spotWanted(car: CarState): Boolean {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val arrived = navi?.arrivedAt?.let { it > 0 && System.currentTimeMillis() - it < SPOT_ARRIVE_MS } == true
        if (car.night && h in 7..17 && !arrived) Prend.log("· 주차 자리: 내비 안내로 도착한 곳이 아니라 알리지 않음")
        return car.night && h in 7..17 && arrived && !Stage.ui.value.daeri && !memory.habits.spot.quiet && !Stage.ui.value.scene.isE
    }

    /** 내비 목적지가 15분 안에 있거나 막 도착했다(10분 안). 목적지가 없거나 도착 시각을 모르면 false */
    private fun arrivingSoon(): Boolean =
        navi?.etaSec()?.let { it in 0 until ARRIVE_SOON_S } == true ||
            navi?.arrivedAt?.let { it > 0 && System.currentTimeMillis() - it < SPOT_ARRIVE_MS } == true
    /** 곧 도착이라 쉼터를 권하지 않았다고 기록에 한 번만 남긴다 */
    private var soonLogged = false

    /** 대리 운행을 물을 밤(20시~새벽 5시) */
    private fun nightHour(): Boolean = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY).let { it >= 20 || it < 5 }

    // ── 가족 정보·혹시 사고·사고 뒤 ──

    private fun answerKids(text: String, byButton: Boolean) {
        val s = text.replace(" ", "")
        // 지금·오늘 이야기("지금은 안 탔어요"·"오늘은 없는데 주말엔 타요"·"가끔")는 가족에 아이가 있는지에 대한 대답이 아니다:
        // 정하지 않고 다음 운행에 한 번 더(검토 11/07 멋대로 판단: '아이 없음'으로 적어, 아플 때 안내가 그 값을 믿었다). "아직 없어요"는 가족 이야기라 아니오
        val now = !byButton && (KIDS_NOW.any { it in s } || "아직" in s && "아직없" !in s)
        // 한 번 정하면 다시 묻지 않는 대답이라 거절 낱말을 넓게 본다
        val no = !now && listOf("아니", "아뇨", "없", "싫어", "안타", "안태").any { it in s }
        // "나중에·됐어·괜찮아"는 정하지 않겠다는 말이다: 아이 없음으로 적지 않고 다음 운행에 한 번 더(11/04 사람 시험)
        val later = !now && !no && listOf("나중", "됐어", "됐다", "괜찮").any { it in s }
        // 예는 짧은 예나 낱말 앞자리의 '있'·'그래'만("아프네요"의 '네'·"예전"의 '예'를 예로 읽지 않게). 되물은 "네?"·'어' 한마디로는 정하지 않는다(검토 11/07)
        val yes = !now && !no && !later && Answer.of(text, KIDS_YES) == true
        logAnswer("가족 정보", text, byButton, if (yes) true else if (no) false else null)
        speaker.stop()
        when {
            now || later -> { closeSimple(); Prend.log("· 가족 정보: 미룸(${if (now) "지금·오늘 이야기" else "정하지 않겠다고 함"}, 다음 운행에 다시)") }
            // 어느 쪽으로 적었는지 한 줄로 분명히(말로 정한 것이 맞게 들렸는지 사람이 알 수 있게, 검토 11/07)
            yes -> { closeSimple(); baby.setKids(true, "물음에 있다고 함"); Stage.toast("가족 정보에 '아이 있음'으로 적었어요", 3_500) }
            no -> { closeSimple(); baby.setKids(false, "물음에 없다고 함"); Stage.toast("가족 정보에 '아이 없음'으로 적었어요 · 기억 화면에서 바꿀 수 있어요", 3_500) }
            // 말이 없으면 카드를 15초 남긴다(정하지 않은 채로 두고 다음 운행에 한 번 더)
            else -> if (!unclear(Line.KIDS, text, byButton) { answerKids(it, byButton = false) }) later(15_000) { if (asking == Line.KIDS) closeSimple() }
        }
    }

    /** 위치 알림 카드: [괜찮아요]면 보내고, [보내지 않기]면 어느 위치도 보내지 않는다(병원은 앱 안 목록, 골목은 차 신호만, ride/GeoShare) */
    private fun answerGeo(yes: Boolean) {
        Prend.log("· 위치 알림 대답(버튼) → ${if (yes) "괜찮아요" else "보내지 않기"}")
        closeSimple()
        GeoShare.set(yes, "처음 알림 카드")
        Stage.toast(if (yes) "알겠어요. 기억 화면에서 바꿀 수 있어요" else "위치를 보내지 않을게요 · 병원은 앱 안 목록으로 찾아요", 3_500)
    }

    private fun answerBump(text: String, byButton: Boolean) {
        val s = text.replace(" ", "")
        val yes = bumpYes(text)
        // "사고 날 뻔했어요"·"사고는 무슨"·"아니 소리만 났어"는 사고가 아니라는 대답이다(검토 11/07)
        val no = !yes && (Answer.no(text) || bumpDenied(s))
        logAnswer("혹시 사고", text, byButton, if (yes) true else if (no) false else null)
        speaker.stop()
        when {
            yes -> { closeSimple(); endBumpWatch(null); Stage.go(Scene.ACCIDENT) }
            no -> { closeSimple(); endBumpWatch(null) }
            // 못 알아들었으면 한 번 다시 묻는다. 대답이 없으면 카드는 15초 뒤 거두고, 10분 동안 배너에 목소리 없이 한 줄을 둔다(밖을 살피고 다시 앉으면 한 번 더 묻는다)
            else -> if (!unclear(Line.BUMP, text, byButton) { answerBump(it, byButton = false) }) later(15_000) {
                if (asking != Line.BUMP) return@later
                closeSimple()
                if (bumpWatchUntil > 0) { Stage.ui.value = Stage.ui.value.copy(bumpHint = true); Prend.log("· 혹시 사고: 대답 없음 → 10분 동안 배너에 한 줄, 운전석에 다시 앉는지 봄") }
            }
        }
    }

    /**
     * "혹시 사고 나셨어요?"에 예인가. "사고 났는데 다친 사람은 없어"·"응 다친 데는 없어"처럼 사고를 분명히 말하면서 '없어'·'괜찮'이 붙어도 예로 본다.
     * 아니오는 "안 났어"·"사고 아니야"처럼 사고를 부정할 때와, 사고 말 없이 '아니'만 할 때. "아니, 박았어요"처럼 '아니' 뒤에 사고 말이 오면 예다
     * (검토 11/07: 전에는 '아니'만 보고 닫았다). 놀라서 되물은 "네?"는 예로 보지 않는다(한 번 다시 묻는다).
     * 검토 11/07 멋대로 판단: "사고 날 뻔했어요"·"사고는 무슨"·"아니, 소리만 났어"·"아니 전화 받았어"를 '났'·'받았'만 보고 사고로 읽어 사고 안내를 열었다.
     * 안 났다는 말은 뒤집지 않는다. '났'·'받았'·'도와'만 있고 사고를 뜻하는 말이 없으면 짐작하지 않고 한 번 다시 묻는다(대답이 정말 애매할 때만
     * 사고 쪽을 고르는 것이고, 카드에 [사고가 났어요] 단추가 크게 있다)
     */
    private fun bumpYes(text: String): Boolean {
        val s = text.replace(" ", "")
        if (bumpDenied(s)) return false
        if (BUMP_SAID.any { it in s }) return true
        if ("아니" in s || "아뇨" in s || Answer.unsure(text)) return false
        // 짧은 예("응"·"네 맞아요"). "응 다친 데는 없어"처럼 짧은 예 뒤에 '없어'·'괜찮'이 붙어도 예(다친 사람이 없다는 말로 듣는다).
        // "어, 그래서…"처럼 이야기를 잇는 말과 '났'·'받았'·'도와'만 있는 말("누가 받았어")은 예로 정하지 않는다(answerBump가 한 번 다시 묻는다)
        return Answer.of(text, listOf("그래", "맞아")) == true || Answer.short(text) && Answer.no(text)
    }

    /** 사고가 아니라는 말인가(빈칸 지운 말). "사고 날 뻔했는데 결국 박았어"처럼 '뻔' 뒤에 사고 말이 오면 아니다 */
    private fun bumpDenied(s: String): Boolean {
        val near = s.indexOf("뻔")
        if (near >= 0 && BUMP_HIT.none { s.indexOf(it, near) > near }) return true
        return BUMP_DENY.any { it in s }
    }

    /** 운전석에 다시 앉아 물은 "확인해 보셨어요?"의 대답. 사고였으면 사고 안내 순서로, 아니면 거둔다. 자동으로 신고하지 않는다 */
    private fun answerBumpBack(text: String, byButton: Boolean) {
        val s = text.replace(" ", "")
        val yes = bumpYes(text)
        val no = !yes && (Answer.no(text) || bumpDenied(s))
        logAnswer("혹시 사고 다시", text, byButton, if (yes) true else if (no) false else null)
        speaker.stop()
        when {
            yes -> { closeSimple(); Stage.go(Scene.ACCIDENT) }
            no -> closeSimple()
            else -> if (!unclear(Line.BUMPBACK, text, byButton) { answerBumpBack(it, byButton = false) }) later(15_000) { if (asking == Line.BUMPBACK) closeSimple() }
        }
    }

    // ── 손님이 타는 순간 ──

    /** 이번 운행에 이미 달렸나(운행을 시작할 때 타는 사람은 손님 인사로 보지 않는다), 세워 둔 채 조수석 문이 열린 때 */
    private var drove = false
    private var guestDoorAt = 0L

    /**
     * 운전 도중 세우고 손님이 탄다(조수석 문이 열렸다 닫히고 그 자리에 사람): 인사하는 30초 동안 먼저 건넬 말을 꺼내지 않고(기척 중이던 말도 삼킨다),
     * PREND 곡이면 목소리 길을 비운다. 짐을 올려도 자리가 켜질 수 있어 문이 열렸다 닫힌 것과 함께일 때만, 뒷자리는 센서가 사람을 가리지 못할 수 있어 조수석만 본다
     */
    private fun guestCar(old: CarState, now: CarState) {
        if (!drove || !now.parked || Stage.ui.value.scene.isE || Stage.ui.value.daeri) { guestDoorAt = 0L; return }
        val t = SystemClock.elapsedRealtime()
        if ((old.doors["ROW_1_RIGHT"] ?: 0) == 0 && (now.doors["ROW_1_RIGHT"] ?: 0) > 0) { guestDoorAt = t; Prend.log("· 손님 살핌: 달린 뒤 세운 차의 조수석 문 열림") }
        val closed = (old.doors["ROW_1_RIGHT"] ?: 0) > 0 && (now.doors["ROW_1_RIGHT"] ?: 0) == 0
        if (!closed || guestDoorAt == 0L) return
        if (t - guestDoorAt > GUEST_DOOR_MS || now.seats["ROW_1_RIGHT"] != true) { guestDoorAt = 0L; Prend.log("· 손님 살핌: 조수석 문 닫힘(${if (now.seats["ROW_1_RIGHT"] != true) "그 자리 빔" else "2분 지남"})"); return }
        guestDoorAt = 0L
        Prend.log("◆ 손님 탐: 조수석 문 열림·닫힘, 그 자리에 사람 → 인사하는 ${GREET_MS / 1000}초 비켜 섬")
        // 밤의 대리 운행 물음은 시동 뒤 3분 안에만 묻는다: 그 물음이 아직 남았으면 말 막기는 걸지 않는다(소리만 비켜 선다)
        val daeriPending = Line.DAERI in queue || asking == Line.DAERI || speakingLine == Line.DAERI ||
            (!daeriAsked && memory.asks("DAERI") && nightHour() && rideStartAt > 0 && SystemClock.elapsedRealtime() - rideStartAt < DAERI_WINDOW_MS && !memory.habits.daeri.quiet)
        if (!daeriPending) timing.greet(GREET_MS)
        Bridge.director?.greet(GREET_MS)
        presenceLine?.let { cancelPresence("손님 인사") }
    }

    // ── 혹시 사고 뒤 다시 앉기 ──

    /** 혹시 사고를 물은 뒤 10분 동안: 운전석에 다시 앉는지 본다(0이면 보지 않음), 그동안 운전석이 빈 것을 보았나, 운전석 문이 닫힌 수 */
    private var bumpWatchUntil = 0L
    private var bumpSawEmpty = false
    private var bumpCloses = 0

    /**
     * 차 신호: 혹시 사고를 물은 뒤 운전석 문이 닫혔고 그 자리에 사람이 있으면 다시 앉은 것으로 보고 "확인해 보셨어요?"를 줄 맨 앞에.
     * 자리 센서가 한 번이라도 '빔'을 보였으면 그 센서를 믿고, 늘 사람으로 읽히는 차면 내리며 닫은 첫 번째는 빼고 두 번째 닫힘부터 본다.
     * 출발하거나, 10분이 지나거나, 급할 때가 되면 그만 본다
     */
    private fun bumpCar(old: CarState, now: CarState) {
        if (bumpWatchUntil == 0L) return
        val t = SystemClock.elapsedRealtime()
        when {
            t > bumpWatchUntil -> return endBumpWatch("10분 지남")
            !now.parked -> return endBumpWatch("출발함")
            Stage.ui.value.scene.isE -> return endBumpWatch("급할 때")
        }
        if (now.seats["ROW_1_LEFT"] == false) bumpSawEmpty = true
        val closed = (old.doors["ROW_1_LEFT"] ?: 0) > 0 && (now.doors["ROW_1_LEFT"] ?: 0) == 0
        if (!closed) return
        bumpCloses++
        val seated = now.seats["ROW_1_LEFT"] != false && (bumpSawEmpty || bumpCloses >= 2)
        if (!seated) { Prend.log("· 혹시 사고: 운전석 문 닫힘(내리며 닫은 것으로 봄)"); return }
        // 아직 첫 물음에 대답을 기다리는 중이면 다시 묻지 않는다
        if (asking == Line.BUMP || speakingLine == Line.BUMP || Line.BUMP in queue) return
        endBumpWatch(null)
        // 다른 물음이 떠 있으면 거둔다(혹시 사고처럼 맨 앞, 묻던 말은 편해지면 다시)
        cancelPresence("혹시 사고 다시")
        (asking ?: speakingLine)?.takeIf { it !in BUMPS }?.let { speaker.stop(); Stage.endListen(); setAside(it) }
        queue.remove(Line.BUMPBACK); queue.addFirst(Line.BUMPBACK)
        Prend.log("· 권할 말 줄 세움: 혹시 사고 다시(운전석에 다시 앉음, 맨 앞)")
    }

    /** 다시 앉는지 보던 것을 그만둔다(why가 있으면 기록). 배너의 한 줄도 거둔다 */
    private fun endBumpWatch(why: String?) {
        if (bumpWatchUntil == 0L && !Stage.ui.value.bumpHint) return
        bumpWatchUntil = 0L
        if (Stage.ui.value.bumpHint) Stage.ui.value = Stage.ui.value.copy(bumpHint = false)
        why?.let { Prend.log("· 혹시 사고: 다시 앉는지 그만 봄($it)") }
    }

    /**
     * "지난번 사고, 보험 접수는 끝내셨어요?"의 대답. 사고 메모(시각·장소·날씨)는 되살릴 수 없어 [다 했어요]를 눌렀을 때만 바로 지운다
     * (검토 11/07 멋대로 판단: 몸과 보험을 한 번에 물어 "네"·"목이 좀 아프네요"로 메모를 지우고 "다행이에요"라고 했다).
     * - 말로 다 했다고 했거나("다 했어"·"접수했어"·"끝냈어"·"했어요") "네"·"응"만 했으면 확인 카드 "사고 메모를 지울까요?"로 한 번 더 묻는다
     * - 아프다는 말(아프·병원·응급실·다쳤)이 있으면 메모를 그대로 두고 진료를 권한다. 괜찮다고 하지 않은 사람에게 "다행이에요"라고 하지 않는다
     * - 낱말은 낱말 앞자리에서만 본다("아프네요"의 '네', "응급실"의 '응', "예약했어요"의 '예'·'했어'를 대답으로 읽지 않게)
     */
    private fun answerAfter(text: String, byButton: Boolean) {
        val s = text.replace(" ", "")
        val hurt = !byButton && AFTER_HURT.any { it in s }
        val notYet = !hurt && AFTER_NOT.any { it in s }
        val done = !hurt && !notYet && Answer.has(text, AFTER_DONE)
        // "괜찮아"만 했으면(다시 묻기의 '괜찮아')·"안 했어"는 [아직이요]로: 메모를 지우지 않는 쪽
        val keep = notYet || !hurt && !done && Answer.no(text)
        // "네"·"응" 한마디(되물은 "네?"는 아니다)
        val bare = !hurt && !done && !keep && Answer.of(text, emptyList()) == true
        Prend.log("· 사고 뒤 대답(${if (byButton) "버튼" else "말 ${text.length}자"}) → ${when { hurt -> "아프다고 함(메모 둠)"; done && byButton -> "다 함"; done || bare -> "다 함(지울지 한 번 더)"; keep -> "아직"; else -> "없음" }}")
        speaker.stop()
        when {
            hurt -> { closeSimple(); Stage.toast("몸이 계속 아프면 진료를 받아 두세요 · 사고 메모는 그대로 둘게요", 4_000) }
            done && byButton -> { closeSimple(); after.done(); Stage.toast("사고 메모를 지웠어요", 3_000) }
            done || bare -> { closeSimple(); confirmAfterDelete() }
            keep -> { closeSimple(); Stage.toast("사고 메모와 보험사 번호는 첫 화면에 둘게요", 3_000) }
            else -> if (!unclear(Line.AFTER, text, byButton) { answerAfter(it, byButton = false) }) later(15_000) { if (asking == Line.AFTER) closeSimple() }
        }
    }

    /** 말로 "다 했어"·"네"라고 한 사람에게 사고 메모를 지울지 한 번 더 묻는다(지우기는 분명한 대답이나 단추로만, 검토 11/07). 대답이 없으면 그대로 둔다 */
    private fun confirmAfterDelete() {
        Stage.confirm("AFTER_DEL", "사고 메모를 지울까요?", "사고 시각·장소·날씨 메모예요", "지워요", "그냥 둬요") { a ->
            when (a) {
                true -> { after.done(); Stage.toast("사고 메모를 지웠어요", 3_000) }
                false -> Stage.toast("사고 메모와 보험사 번호는 첫 화면에 둘게요", 3_000)
                null -> Prend.log("· 사고 뒤: 지울지 대답이 없어 메모를 그대로 둠")
            }
        }
    }

    private fun closeSimple() {
        cardTimeout?.let { main.removeCallbacks(it) }
        // 그사이 확인 카드가 떴으면 그 카드의 듣기는 닫지 않는다(떠 있는 동안의 듣기는 그 카드의 것이다)
        if (!confirmUp()) Stage.endListen()
        speakingLine = null
        asking = null
        clearAsk()
    }

    /** 확인 카드(Stage.confirm, id "Q_")가 떠 있나: 그동안은 제 말을 꺼내지 않고 카드를 덮지 않는다(검토 11/07 멋대로 판단) */
    private fun confirmUp() = Stage.ui.value.ask?.id?.startsWith("Q_") == true

    /** 묻던 카드를 거둔다. 그사이 다른 모듈이 띄운 확인 카드는 두고(대답을 잃지 않게) 제 카드만 지운다 */
    private fun clearAsk() { if (!confirmUp()) Stage.ask(null) }

    /** 고속도로 정차 카드(대피 안내, ride/HighwayStop)가 떠 있나: 그동안은 제 말로 덮지 않는다(11/08 오류 점검 C2) */
    private fun hstopUp() = Stage.ui.value.ask?.id == "HSTOP"

    /**
     * 고속도로 위(졸음쉼터·휴게소 0.6km 밖)에 섰나: 갓길이나 막힌 차로라 쪽잠을 권하지 않는다(11/08 오류 점검 C2, 원칙 ④).
     * 쉼터까지의 거리를 모르면 고속도로 정차 카드와 같이 쉼터 밖으로 본다
     */
    private fun highwayStopped(): Boolean =
        onHighway() && (navi?.last?.let { w -> rests()?.nearestKm(w.lat, w.lon) } ?: 99.0) > HighwayStop.REST_KM

    /** 시험: 지금 바로 같이 고르기를 묻는다(때 판단 없이) */
    fun queuePick() { forcePick = true; if (Line.PICK !in queue) queue.addLast(Line.PICK); Prend.log("· 시험: 같이 고르기 줄 세움") }
    private var forcePick = false

    /** 시험: 지금 바로 정체 한마디를 줄에 세운다(막힘 판단 없이) */
    fun queueJam() { forceJam = true; if (Line.JAM !in queue) queue.addLast(Line.JAM); Prend.log("· 시험: 정체 한마디 줄 세움") }
    private var forceJam = false
    /** 시험: 동승자가 있어도 한 시간 물음을 건넨다(에뮬레이터는 자리에 사람이 있다고 나온다) */
    private var forceHour = false
    /** 시험: 시동 뒤 3분이 지났어도 대리 운행을 묻는다 */
    private var forceDaeri = false
    /** 시험: 먼저 묻기에서 꺼 둬도 이야기에 비켜 서기를 묻는다(한 시간·대리 운행·정체·같이 고르기는 위의 force가 같은 일을 한다) */
    private var forceTalk = false

    /** 운전석 말고 앉은 자리가 없다 */
    private fun alone(car: CarState) = car.seats.none { (k, v) -> v && k != "ROW_1_LEFT" }

    /** 급할 때: 하던 말과 묻던 카드를 거둔다(쉼터는 대답이 없던 것처럼 30분 뒤 다시) */
    private fun quiet() {
        Prend.log("· 급할 때라 평소의 말 거둠(${(speakingLine ?: asking)?.name})")
        cardTimeout?.let { main.removeCallbacks(it) }
        presenceOff("급할 때")
        speaker.stop()
        if (asking == Line.REST) { restAt = lastMinutes + REST_AGAIN; restSleepy = false }
        speakingLine = null
        asking = null
        clearAsk()
    }

    /** 시험: 운전 시간을 바꾸면 권하기도 처음 상태로 */
    fun reset() {
        speaker.stop(); speakingLine = null; asking = null; queue.clear(); swallowed = null; restAt = REST_FIRST; restReoffered = false; auto = null; restSleepy = false
        cardTimeout?.let { main.removeCallbacks(it) }
        cancelPresence("시험", drop = true); presenceOff("시험")
        clearAsk()
    }

    /** 시험: 한 시간 물음·대리 운행 물음·주차 자리·이야기 비켜 서기를 지금 줄에 세운다(때 판단 없이) */
    fun queueTest(what: String) {
        val line = when (what) { "hour" -> Line.HOUR; "daeri" -> Line.DAERI; "spot" -> Line.SPOT; "talk" -> Line.TALK; else -> return }
        if (line == Line.HOUR) forceHour = true
        if (line == Line.DAERI) forceDaeri = true
        if (line == Line.TALK) forceTalk = true
        if (line !in queue) queue.addLast(line)
        Prend.log("· 시험: ${line.name} 줄 세움")
    }

    /**
     * 시험: 지금 바로 쉼터 권하기를 줄에 세운다. 이미 권하는 중이면 세우지 않는다(실제 줄 세우기와 같은 조건.
     * 11/08 시험에서 두 시간이 된 순간 이 명령을 함께 보내 [아니요] 2초 뒤 같은 물음이 한 번 더 나왔다)
     */
    fun queueRest() {
        val busy = Line.REST in queue || speakingLine == Line.REST || asking == Line.REST || presenceLine == Line.REST
        if (!busy) queue.addLast(Line.REST)
        Prend.log("· 시험: 쉼터 권하기 ${if (busy) "이미 권하는 중" else "줄 세움"}")
    }

    /** 시험: 편한 때를 기다리는 상한을 30초로 줄이거나(fast) 5분으로 되돌린다 */
    fun comfyFast(fast: Boolean) { comfyMaxMs = if (fast) 30_000L else COMFY_MAX_MS; Prend.log("· 시험: 편한 때 기다림 상한 ${comfyMaxMs / 1000}초") }

    /** 시험: 편한 때를 기다리는 말과 미룬 말 */
    fun comfyDump() {
        val now = SystemClock.elapsedRealtime()
        Prend.log("· 시험: 편한 때 기다림 ${comfyWaitSince.entries.joinToString { "${it.key.name} ${(now - it.value) / 1000}초" }.ifEmpty { "없음" }}" +
            " · 목소리 미룸(${if (deferRefusedVoice) "켬" else "꺼짐"}) ${deferred.joinToString { it.name }.ifEmpty { "없음" }} · 줄 ${queue.joinToString { it.name }.ifEmpty { "없음" }}" +
            " · 다른 카드 ${if (idle) "띄울 수 있음" else "막힘"}")
    }

    /** 시험: 목소리 포커스를 받지 못한 말을 신호 대기·P까지 미루는 스위치(기본 꺼짐) */
    fun deferVoice(on: Boolean) { deferRefusedVoice = on; if (!on) deferred.clear(); Prend.log("· 시험: 목소리 미루기 ${if (on) "켬" else "끔"}") }

    /** 시험: 쉼터를 방금 경유지로 넣은 것처럼(다음에 세우면 쪽잠을 권한다) */
    fun pretendRestAccepted() { restAcceptedAt = SystemClock.elapsedRealtime(); Prend.log("· 시험: 쉼터를 경유지로 넣은 것처럼") }

    companion object {
        const val REST_FIRST = 120
        const val REST_AGAIN = 30
        const val REST_AFTER_YES = 60
        /** 두 시간 쉼터를 거절·무응답이면 이만큼(분) 뒤 한 번만 더(별첨 2 §5-2 ③), 그 뒤로는 쉬기 전까지 권하지 않음 */
        const val REST_REOFFER = 60
        const val REST_NEVER = Int.MAX_VALUE - 1
        /** 끊긴 말이 이만큼 넘게 나갔으면 처음부터 다시 하지 않는다(대사 대부분이 두 문장이고 뜻은 첫 문장에 있다, 제안값) */
        const val NEARLY_DONE = .7f
        /**
         * 말 차례(ride/Floor)를 지나는 먼저 꺼내는 물음. 쉼터(졸음)·혹시 사고는 막지 않고, 쪽잠·잘래요는 등받이를 젖힌 사람의 몸짓에 답하는 말이라 뺀다.
         * 오늘도 조용히(QUIET)는 늘 카드만이라 뺀다
         */
        /** 편한 때를 5분 넘게 못 만나도 버리지 않는 말 */
        private val NO_EXPIRE = setOf(Line.HOUR, Line.FOG, Line.QUIET)
        /** 화장실: 끊겨 되돌아간 말은 처음 말한 때부터 3분까지. 줄에서 다시 나온 말(고른 지 1.5초 넘음)은 지금 자리에서 다시 고른다 */
        private const val TOILET_STALE_MS = 3 * 60_000L
        private const val TOILET_REPICK_MS = 1_500L
        private const val TOILET_LATE = "때가 지남"
        private const val TOILET_FINDING = "가까운 화장실을 찾고 있어요"
        /** 화장실: 묻던 중이나 줄에서 기다리던 중에 세운 때의 까닭, 고른 곳이 아닌 데서 세웠거나 3분이 지나 멈춘 때의 한 줄 */
        private const val TOILET_PARKED = "세움"
        private const val TOILET_STOPPED = "화장실 찾기를 멈췄어요 · 필요하면 다시 말씀해 주세요"
        /** 쉼터·화장실 물음에서 물은 것을 가리키는 낱말("커피 말고 쉼터로 넣어 줘"는 예, "쉼터 말고 그냥 가자"는 거절, ride/Answer.settle) */
        private val REST_ITEM = listOf("쉼터", "휴게소", "경유", "쉬어", "쉬자", "쉬러", "쉬고")
        private val TOILET_ITEM = listOf("화장실", "주유소", "휴게소", "쉼터", "경유", "들러", "들르")
        /** 가족 정보 물음에서 정하지 않는 지금·오늘 이야기("지금은 안 탔어요"·"오늘은 없는데 주말엔 타요"·"가끔"·"예전엔 있었는데"), 예 낱말(낱말 앞자리에서만) */
        private val KIDS_NOW = listOf("지금은", "지금안", "지금없", "오늘은", "오늘안", "오늘없", "주말엔", "주말에는", "주말에만", "주말만", "가끔", "요즘", "있었")
        private val KIDS_YES = listOf("있", "그래", "맞아", "하모", "그려", "기여", "오야")
        /** 사고 뒤 물음: 다 했다는 말(낱말 앞자리에서만, "예약했어요"는 아니다), 아직이라는 말, 아프다는 말(메모를 두고 진료를 권한다) */
        private val AFTER_DONE = listOf("다했", "다됐", "접수했", "접수됐", "끝냈", "끝났", "마쳤", "처리했", "했어", "했습", "했죠")
        private val AFTER_NOT = listOf("아직", "아니", "아뇨", "안했", "못했", "안끝", "못끝", "덜했", "덜끝", "하는중", "하고있", "기다리")
        private val AFTER_HURT = listOf("아프", "아파", "아퍼", "병원", "응급실", "다쳤", "다친", "진료", "통증", "결려", "결리", "쑤셔", "쑤시")
        /**
         * 혹시 사고 물음(빈칸 지운 말에서 찾음, 검토 11/07): 사고가 아니라는 말, 사고를 분명히 뜻하는 말, '뻔' 뒤에 와도 사고인 말.
         * '났'·'받았'·'도와'는 홀로는 사고 말이 아니다("짜증 났어"·"전화 받았어"·"도와줄 건 없어")
         */
        private val BUMP_DENY = listOf("안났", "안부딪", "안박", "사고없", "사고안", "사고는아니", "사고아니", "무슨사고", "사고는무슨", "짜증났", "전화받았", "문자받았", "소리만", "도와줄건없", "도와줄거없", "도와줄게없", "도와줄것없")
        private val BUMP_SAID = listOf("사고났", "사고가났", "사고나서", "사고난", "사고예", "사고야", "박았", "박혔", "부딪혔", "부딪쳤", "받혔", "들이받", "긁었", "쾅")
        private val BUMP_HIT = listOf("박았", "박혔", "부딪혔", "부딪쳤", "받혔", "들이받", "긁었", "쾅", "사고났", "사고가났")
        /** 말을 삼키는 까닭: 확인 카드(Stage.confirm)가 떠 있음 */
        private const val CONFIRM = "확인 카드"
        /** 말을 삼키는 까닭: 고속도로 정차 카드(대피 안내)가 떠 있음(11/08 오류 점검 C2) */
        private const val HSTOP_UP = "고속도로 대피 카드"
        private val FLOOR_ASK =setOf(Line.JAM, Line.PICK, Line.KIDS, Line.AFTER, Line.FOG, Line.HOUR, Line.SPOT, Line.DAERI, Line.TALK)
        /** 목적지가 이만큼(초) 안이면 두 시간 쉼터를 권하지 않는다 */
        const val ARRIVE_SOON_S = 15 * 60
        /** 한 시간 물음(연속 운전 분) */
        const val HOUR_AT = 60
        /** 대리 운행은 시동 뒤 이 시간 안에만 묻는다 */
        const val DAERI_WINDOW_MS = 3 * 60_000L
        /** 대리 운행이면 먼저 건네지 않는 말(혹시 사고·잘래요만 남긴다. 가족 정보도 기사 앞에서는 묻지 않는다) */
        private val DAERI_QUIET = setOf(Line.REST, Line.NAP, Line.JAM, Line.PICK, Line.AFTER, Line.FOG, Line.HOUR, Line.SPOT, Line.TALK, Line.KIDS, Line.QUIET, Line.GEO)
        /** 알아서 한 일을 되돌리면 '다시 묻기'로 세는 시간 */
        const val UNDO_MS = 3 * 60_000L
        /** 말을 삼키는 까닭: 통화 중 */
        private const val CALL = "통화 중"
        /** 혹시 사고와 그 뒤 다시 앉았을 때의 물음: 말할 때를 기다리지 않고, 자는 동안·주차 코치 중에도 묻는다 */
        private val BUMPS = setOf(Line.BUMP, Line.BUMPBACK)
        /** 손님 인사: 조수석 문이 열린 뒤 이 안에 닫혀야 타는 것으로 보고, 인사하는 이 시간 동안 비켜 선다 */
        const val GUEST_DOOR_MS = 2 * 60_000L
        const val GREET_MS = 30_000L
        /** 혹시 사고를 물은 뒤 운전석에 다시 앉는지 보는 시간 */
        const val BUMP_WATCH_MS = 10 * 60_000L
        /** 조용히 가는 운행에도 목소리로 묻는 말(쉼터·혹시 사고) */
        private val QUIET_VOICE = setOf(Line.REST, Line.BUMP, Line.BUMPBACK, Line.TOILET)
        /** 동승자가 있으면 목소리 없이 카드로만 건네는 말(사용자 결정 11/04) */
        private val GUEST_CARD = setOf(Line.DAERI, Line.AFTER, Line.JAM, Line.SPOT, Line.BUMPBACK)
        /** "괜찮아요"·대답 없음이면 그 운행에서 다시 묻지 않는 물음 */
        private val ONCE = setOf(Line.JAM, Line.PICK, Line.HOUR, Line.NAP, Line.SLEEP, Line.FOG)
        /** 대답이 무드등을 바꾸는 말(정체 호박색, 쪽잠·잘래요 어둡게): 기척은 차임으로 */
        private val LIGHT_OWN = setOf(Line.JAM, Line.NAP, Line.SLEEP)
        /**
         * 편한 때(신호 대기·안정 주행·P)를 기다렸다 건네는 급하지 않은 말(감사 F45).
         * 쉼터(졸음)·혹시 사고는 기다리지 않고, 잘래요는 등받이를 젖힌 그때 묻는 말이라 기다리지 않는다.
         * 가족 정보·위치 알림은 세워 둔 동안만 묻는데, P여도 문이 모두 닫히고 10초를 기다린다(검토 11/06: 시동 직후 뒷문을 열고 아이 벨트를 매는 동안 묻던 것).
         * 5분 넘게 문이 열려 있으면 그 운행에는 버리고 다음 운행에 묻는다(건넨 수에는 세지 않는다)
         */
        private val COMFY_WAIT = setOf(Line.HOUR, Line.PICK, Line.JAM, Line.FOG, Line.TALK, Line.SPOT, Line.NAP, Line.AFTER, Line.DAERI, Line.QUIET, Line.KIDS, Line.GEO)
        /** 편한 때를 기다리는 상한(별첨에 값이 없어 5분) */
        const val COMFY_MAX_MS = 5 * 60_000L
        /** 위치 알림 카드가 보인 채로 이만큼(초) 지나야 건넨 것으로 센다(두 줄 글을 읽을 만큼) */
        const val GEO_SEEN_S = 5
        /** 다른 앱 음악이 흘러도 목소리로 얹는 말(별첨 2 V16: 두 시간 쉼터와 "졸려"의 대답, 그리고 혹시 사고·주차 자리) */
        private val MUSIC_VOICE = setOf(Line.REST, Line.BUMP, Line.BUMPBACK, Line.SPOT, Line.TOILET)
        /** 음악 위 알림음(0.5초)이 끝나고 말을 시작하기까지 */
        const val ALERT_GAP_MS = 600L
        /** 내비 안내로 도착한 뒤 이 시간 안에 P에 넣으면 주차 자리를 알린다 */
        const val SPOT_ARRIVE_MS = 10 * 60_000L
        /** 기척: 말하기 몇 ms 전, 그동안 몇 ms마다 다시 보나, 무드등을 얼마나 올리나(%p), 몇 % 이하일 때만 올리나(밤 상한 20%) */
        const val PRESENCE_MS = 2_000L
        const val PRESENCE_STEP_MS = 250L
        const val PRESENCE_UP = 5
        const val PRESENCE_MAX = 15
    }
}
