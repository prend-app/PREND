package app.prend.ride

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import app.prend.Bridge
import app.prend.Prend
import app.prend.car.CarState
import app.prend.car.Navi
import app.prend.net.Relay
import app.prend.ui.Aid
import app.prend.ui.Guide
import app.prend.ui.Hosp
import app.prend.ui.Hospital
import app.prend.ui.PrendActivity
import app.prend.ui.Scene
import app.prend.ui.Stage
import app.prend.ui.StageUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 급할 때의 목소리 안내(별첨 12 §4·§5). 사고와 갑자기 아플 때 두 갈래.
 * 사고: 에어백이 터지거나 사람이 [사고가 났어요]·"사고 났어"로 열면 차례대로 말한다.
 * - 화면을 열지 못해도 목소리만으로 끝까지 안내한다. Pleos(안드로이드 14)는 앱이 스스로 화면을 여는 것을 막아(10/26 시험),
 *   에어백 때는 화면을 먼저 열어 보고(이번 운행에 PREND를 연 적이 있으면 홈 화면이 띄운다) 막히면 배너가 지금 걸음과 [열기]를 띄운다
 * - 첫마디 "괜찮으세요?" 뒤 5초(넉넉히 기다리기면 8초) 대답을 듣는다. 다쳤다거나 대답이 없으면 "SOS 버튼을 누르거나 119에 알리세요"를 먼저 말한다.
 *   듣는 동안 화면 걸음 위에 물음 띠와 [괜찮아요]·[다쳤어요]를 두고, 누르면 그 말을 들은 것처럼 받는다. 대답이 없어 SOS로 갔어도
 *   1·2걸음의 [안 다쳤어요]로 바로잡으면 띠를 거두고, 일반 도로면 가벼운 사고 순서로 처음부터 다시 말한다(11/06)
 * - 고속도로(쉼터 자료의 이정 선 위)면 한국도로공사 순서, 일반 도로에서 괜찮다고 답했고 에어백이 아니면 가벼운 사고 순서(사진 먼저, 차를 길가로),
 *   아니면 일반 도로 순서. 세 갈래 모두 보험사 접수, 사진·블랙박스, 상대와 주고받을 것으로 끝난다(사용자 결정 10/27)
 * - 이번 운행에 아이 탑승("아이 탔어")이면 화면 맨 위에 "뒷좌석에 아이가 있어요"를 두고, 대답 뒤 "뒷좌석 아이도 꼭 함께 챙기세요"를 먼저 말한다.
 *   가족 정보에 아이가 있으면(그날 탔는지는 모른다) "뒷좌석 아이 확인"을 두고 "뒷좌석에 아이가 있는지 꼭 살펴보세요"를 먼저 말한다(사용자 결정 11/01)
 * - 에어백 뒤 대답이 없으면 정신을 잃었을 수 있다고 보고 구조하러 온 사람이 읽을 화면으로 바꾼다. 누가 문을 열면 목소리로 다시 알린다
 * - 고장 안내나 사람이 연 사고 안내 중(목소리가 끝났어도)에 에어백이 터지거나 "사고 났어"면 사고 안내로 새로 시작한다.
 *   연기·불 안내 중이면 대피 안내는 그대로 두고 그 뒤에 "괜찮으세요?"만 더한다(대답이 없으면 에어백일 때 구조 화면, 11/06)
 * - 대피 문장(가드레일 밖으로·모두 내려 길 밖으로·내려서 기다리세요)은 그다음 119·견인 한 줄까지 말한 뒤 운전석 문을 기다린다.
 *   문이 열려 나갔으면 목소리를 멈추고 다시 앉으면 잇는다. 운전석이 빈 것을 한 번도 보지 못한 차는 첫 닫힘을 내리며 닫은 것으로 본다(11/06)
 * - 고속도로 대피 문장("차 뒤로 가지 말고 바로 가드레일 밖으로") 뒤 30초가 지나도 운전석 문이 한 번도 열리지 않고 P 그대로면,
 *   같은 공식 문장을 한 번만 더 말한다(10/29 결정 3, 11/01 사용자 "너의 추천대로": 새 문장을 쓰지 않는다). 이 30초가 곧 문 기다림이다(시계 하나, 11/06)
 * - 일반 도로에서 괜찮다고 해도 바로 전 2분 안에 시속 80을 넘겨 달렸으면 가벼운 사고 순서(사진 먼저, 차를 길가로)를 쓰지 않는다
 *   (위치를 몰라 고속도로를 놓쳤거나 민자·도시고속도로일 수 있다, 11/06)
 * - 시작할 때 사고 메모(시각·장소·날씨)를 적는다(AfterCrash). 보험사 걸음에서 보여 준다
 * 아플 때: [갑자기 아파요]·"아이가 열이 나"로 열면 "숨쉬기나 의식이 이상하면 119가 먼저예요."를 말하고, 그사이 가까운 병원을 찾는다.
 * - 누가 아픈지 모르면(버튼만 눌렀고 가족에 아이가 있거나 동승자가 있음) 서 있어도 "운전하는 분이 아프면…" 한 줄을 더하고,
 *   세운 뒤 한 번 괜찮은지 묻는다(대답이 없으면 한 번 더 묻고 그친다. 구조 화면은 띄우지 않는다, 11/06)
 * - 괜찮은지 묻는 말에 아이·옆 사람 이야기가 섞이면 운전자는 괜찮은 것으로 보고 병원 걸음으로 잇는다.
 *   안내 중에 말로 아픈 사람이 바뀌면("아이가 열이 나") 처음부터 다시 안내한다(11/06)
 * - 중계 서버를 거쳐 국립중앙의료원 자료로 응급실(실시간 남은 병상)과 지금 진료 중인 달빛어린이병원을 찾는다.
 *   인터넷이 안 되거나 위치 보내기를 껐으면(ride/GeoShare) 앱에 넣어 둔 목록에서 직선거리로 고르고, 화면과 목소리로 "가기 전에 전화로 확인"을 붙인다
 * - "목에 걸렸어"·"경련해"처럼 응급처치가 먼저인 말을 달리는 중에 들었으면 병원을 묻지 않고 "차를 세우면 카드를 보여 드릴게요", P에 넣으면 그 카드를 연다
 * 응급처치 카드: 세워 둔 동안 연 카드의 걸음을 차례로 읽는다(걸음 사이를 조금 길게). 사람이 걸음을 넘기면 그 걸음만 읽고,
 * 달리기 시작하면 멈추고, 경련 카드는 연 지 5분이 지나면 "아직 경련 중이면 바로 119"를 한 번 말한다
 * - 아이 이야기였고 진료 중인 달빛어린이병원이 15km 안에 있으면 그곳을, 아니면 응급실을 경유지로 넣을지 묻고, "응"이면 내비에 넣는다
 * - 화면의 [달빛어린이병원] [응급실] 버튼으로 골라도 같다. 위치는 병원 찾기에만 쓰고 기록하지 않는다
 * 둘 다:
 * - 평소의 말과 카드, PREND 음악, 다른 앱 음악을 멈춘다. 포커스를 길게(GAIN) 잡아, 안내가 끝난 뒤에도 음악이 저절로 다시 흐르지 않는다
 * - 통화(블루링크 상담원·전화) 중에는 말하지 않는다. 오디오 모드가 통화면 기다리고, 포커스는 '늦게 받기'로 청해 통화가 끝나면 차가 돌려준다.
 *   말하다 통화에 포커스를 내주면 멈췄다가, 돌려받으면 그 문장부터 다시 말한다. 통화를 기다리는 동안 화면·위 막대·배너에 "통화가 끝나면 이어서 안내해요"(11/06)
 * - 문장마다 화면과 배너의 걸음을 함께 넘긴다. 사람이 화면에서 걸음을 넘기면 목소리를 멈추고, 그 화면을 닫으면 끝낸다
 * 들은 대답은 고르는 데만 쓰고 기록에는 길이만 남긴다.
 */
class Crash(
    private val context: Context,
    private val onHighway: () -> Boolean,
    private val navi: Navi?,
    private val after: AfterCrash,
    /** 119에 말할 위치 한 줄을 지금 맞춘다(사고 메모에 적을 장소. 화면의 위치는 1초마다 맞추므로 시작하는 순간에는 비었거나 낡았을 수 있다) */
    private val place: () -> String?,
    /** 천천히 말하기가 켜져 있나(ride/SlowTalk, 이 안내도 같은 빠르기로) */
    private val slow: () -> Boolean,
    private val car: () -> CarState,
) {

    private data class Say(val asset: String, val step: Int)

    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** 안내 중(그 화면이 닫히면 끝) */
    private var active = false
    /** 지금 안내하는 갈래: ACCIDENT 또는 ILLNESS */
    private var mode = Scene.ACCIDENT
    /** 목소리로 이끄는 중(다 말했거나 사람이 화면으로 넘겨받으면 false) */
    private var talking = false
    private var focus: AudioFocusRequest? = null
    private var hasFocus = false
    private var lines = listOf(Say("e_ok", 1))
    private var index = 0
    private var player: MediaPlayer? = null
    private var listening = false
    /** 물은 뒤 대답 듣기의 회차(갈래가 바뀌거나 멈추면 늘려, 늦게 온 대답을 버린다) */
    private var listenId = 0
    /** 달리기 시작해 멈춘 응급처치 카드 목소리(다시 세우면 끊긴 문장부터) */
    private var pausedByDrive = false
    /** 사람이 [프렌드]를 불러 멈춘 목소리(대답 창이 닫히면 끊긴 문장부터) */
    private var heldForListen = false
    /** 경련 5분 알림을 아직 끝까지 말하지 못했다 */
    private var pending5min = false
    /** 목소리가 넘긴 걸음. 화면의 걸음이 이와 다르면 사람이 넘긴 것 */
    private var shownStep = 1
    private var waitedCall = false
    private var lastScene = Stage.ui.value.scene
    /** 아플 때: 병원을 물었는가, 물은 곳이 달빛어린이병원인가, 병원 찾기를 기다린 횟수 */
    private var askedHospital = false
    private var askedMoon = false
    private var waitHosp = 0
    /** 위치를 몰라 병원을 찾지 못했다(내비 자리가 들어오면 tick이 한 번 더 찾는다), 물을 때 병원이 없어 묻지 못했다(찾으면 그때 한 번 묻는다, 11/08 오류 점검 B2) */
    private var hospNoPlace = false
    private var hospMissed = false
    /** 에어백으로 시작했다(대답이 없으면 구조용 화면) */
    private var byAirbag = false
    /** 구조용 화면을 띄웠다. 사람이 [괜찮아요]로 거두면 걸음 안내를 이어 간다 */
    private var rescuing = false
    private var helpAt = 0L
    /**
     * 대피(EVAC 문장) 뒤에는 다음 줄로 바로 넘어가지 않는다(11/05: 전에는 1.2초 간격으로 이어 말해 빈 차에 34~52초를 말했다).
     * 11/06: 대피 문장을 말하기 시작한 때부터 운전석 문을 지켜보고(말하는 도중에 나가도 잡게), 그다음 119·견인 한 줄(WAIT_AFTER)까지 말한 뒤 기다린다
     * (전에는 대피 문장 바로 뒤에 기다려, 시킨 대로 바로 내린 사람은 "그다음 119에 알리세요"·견인 번호를 듣지 못했다).
     * 시계는 하나: 대피 문장이 끝난 때부터 고속도로 30초·그 밖 20초. 문이 열려 나갔으면 목소리를 멈추고 다시 앉으면 잇는다.
     * 고속도로에서 30초가 지나도 문이 한 번도 열리지 않고 P 그대로면 같은 대피 문장을 지금 걸음 그대로 한 번 더 말하고 다음 걸음으로
     * (전에는 30초 시계가 따로 돌아 화면이 2걸음으로 되돌아가고 또 20초 조용해졌다)
     */
    private var evacOn = false
    private var evacLine: String? = null
    /** 대피 문장이 끝난 때(시계), 그 뒤 운전석 문이 한 번이라도 열렸나(다시 말하지 않는다), 한 번 더 말했나 */
    private var evacAt = 0L
    private var evacDoor = false
    private var evacAgain = false
    /** 기다리기 시작한 때(0이면 기다리지 않음), 열린 뒤 아직 나간 것으로 볼 만한가(앉은 채 닫으면 거둔다), 자리 값을 믿지 못할 때 센 닫힘 */
    private var evacWaitAt = 0L
    private var evacOpened = false
    private var evacCloses = 0
    private var awayHold = false
    /** 자리 값을 믿지 못할 때 다시 앉은 것으로 볼 운전석 문 닫힘 수(밖에서 닫은 것을 뺀다) */
    private var awayNeed = 0
    /**
     * 운전석 자리 값을 믿어도 되나: 이번 안내 동안 '빔'을 보였거나, 이 차가 서 있을 때 '앉음 → 빔'을 보인 적이 있다(서비스가 떠 있는 동안).
     * 값이 없거나 늘 '앉음'인 차는 대피 뒤 밖에서 닫은 문을 다시 앉은 것으로 보아 빈 차에 남은 안내를 다 말했다(검토 11/06, Care.bumpCar와 같은 생각)
     */
    private var seatSawEmpty = false
    private var seatWorks = false
    private fun seatTrusted() = seatSawEmpty || seatWorks
    /** 바로 전에 시속 80을 넘겨 달린 때(가벼운 사고 순서를 막는다) */
    private var fastAt = 0L
    /** "뭐라고?"로 "괜찮으세요?"를 한 번 더 물었나, 다음 한 문장만 천천히(11/05) */
    private var repeatedOk = false
    private var slowOnce = false
    /** 운전자가 아프다고 했다: 세운 뒤 20초에 "괜찮으세요?"(두 번까지), 두 번 다 대답이 없으면 구조 요청 화면(11/05, 검토-방향노인초보 3-2) */
    private var drvChecking = false
    private var drvCheckAt = 0L
    private var drvChecks = 0
    /** 누가 아픈지 모르고 묻는 중: 대답이 없어도 한 번 더 묻고 그친다(구조 화면 없이. 부모가 아이를 돌보느라 대답하지 못할 수 있다, 11/06) */
    private var drvSoft = false
    /** 운전자가 아프다고 한 뒤 운전석이 비었다(내림): 묻지 않고, 다시 앉으면 20초를 새로 센다 */
    private var drvAway = false
    /** 운전자 확인 중 운전석 문이 마지막으로 열린 때, 그 까닭으로 한 번 더 기다렸나(자리 값을 믿지 못하는 차에서 바람 쐬러 내린 운전자를 구조 화면으로 보지 않게, 11/06) */
    private var drvDoorAt = 0L
    private var drvDoorWaited = false
    /** 안내를 시작할 때 연기·불, 고장이었나(사고 안내 중 "연기 나"로 바뀌면 목소리도 그 갈래로 다시 시작한다) */
    private var startedFire = false
    private var startedBreakdown = false
    /** 아플 때 안내를 시작할 때의 아픈 사람(말로 바뀌면 처음부터, 11/06) */
    private var startedIllOther: Boolean? = null
    private var startedKid = false
    private var startedWant: Aid? = null
    /** 이번 아플 때 안내를 [갑자기 아파요]를 말없이 눌러 열었다(혼자이고 아이가 없을 때만 운전자로 본다, 검토 11/07 멋대로 판단) */
    private var illByButton = false
    /** 병원 물음에 되물은 "네?"로 한 번 더 물었다(두 번째도 그러면 대답이 없던 것으로) */
    private var hospAgain = false
    /**
     * 에어백 뒤 "괜찮으세요?"에 대답이 없었는데 운전석이 비었다(자리 값을 믿을 수 있는 차): 정신을 잃은 것이 아니라 내린 것으로 보고
     * 구조 요청 화면 대신 SOS 띠만 둔 채 다시 앉기를 기다린다. 다시 앉으면 한 번 더 묻는다(검토 11/07 멋대로 판단)
     */
    private var okAway = false
    /** 짐작 줄(StageUi.guess)과 그것을 처음 본 때(20초 뒤 거둔다) */
    private var guessSeen: String? = null
    private var guessAt = 0L
    /** 연기·불 안내에 "괜찮으세요?"를 더했나(에어백·"사고 났어"), 에어백으로 더했나(말로 물은 뒤의 에어백은 한 번 더 묻는다), 그 물음을 아직 하지 않았나 */
    private var fireOk = false
    private var fireOkAirbag = false
    private var fireOkPending = false
    /** 사고 안내 대답에 연기·불과 다쳤다는 말이 함께 있었다: 연기·불 안내로 다시 시작할 때 SOS 띠를 켠다 */
    private var fireSos = false
    /** 응급처치 카드: 지금 카드, 경련 5분을 알렸는가, 잰 시작 시각([다시 재기]면 바뀐다) */
    private var lastAid = Stage.ui.value.aid
    private var said5min = false
    private var aidSinceSeen = 0L
    /** 5분 알림을 끝까지 말한 경련 시계(시작 시각). 심폐소생술 카드에 다녀와 같은 시계로 돌아오면 다시 알리지 않는다 */
    private var said5minFor = 0L

    /** E 첫 화면에서 음악을 멈춰 두는 포커스(말은 하지 않는다). 안내가 시작되면 안내의 포커스로 넘긴다 */
    private var hold: AudioFocusRequest? = null

    init {
        Stage.onHosp = { kind -> if (kind == "again") findHospitals() else chooseHospital(kind) }
        // 연기·불 안내 중 "사고 났어": 대피 안내는 그대로 두고 그 뒤에 괜찮은지 묻는다(11/06)
        Stage.onAccidentSaid = { if (active && mode == Scene.ACCIDENT && Stage.ui.value.fire) { Prend.log("· 연기·불 안내 중 사고 말 → 대피 안내 뒤 괜찮은지 물음"); askOkAfterFire() } }
        // 사고 걸음의 [안 다쳤어요]: SOS로 간 안내를 늦게 바로잡는다(11/06)
        Stage.onNotHurt = { notHurt() }
        Stage.onHurtLate = { hurtLate() }
        // 사고 안내 중 [프렌드]로 "다리가 아파요"·"다쳤어요": 아플 때 안내로 갈아타지 않고 [다쳤어요]를 누른 것처럼(검토 11/07 멋대로 판단)
        Stage.onHurtSaid = { s, sure -> hurtByWord(s, sure) }
        // 아플 때 안내 중 [프렌드]로 한 말에 같이 탄 사람 이야기: 운전자가 아프다고 한 뒤면 바로 바꾸지 않고 화면으로 묻는다(검토 11/07 멋대로 판단)
        Stage.onWhoSaid = { kid -> whoSaidLater(kid) }
        // 짐작 줄의 [아니에요]: 목소리를 멈추고 사고 메모를 남기지 않는다. 걸음 단추·[119 전화]·보험사를 누르면 쓴 안내로 센다(사고 메모를 남긴다)
        Stage.onGuessWrong = { guessWrong() }
        Stage.onGuideTouch = { if (active && mode == Scene.ACCIDENT) memoKept = true }
        // E에 들어서는 순간 음악을 멈추고, E가 끝나면 다음 곡을 서서히 들인다. 1초 틱을 기다리지 않는다(Emergency를 누르자마자 듣는 말에 음악이 섞이지 않게)
        scope.launch {
            var wasE = false
            Stage.ui.map { it.scene.isE }.distinctUntilChanged().collect { e ->
                if (e) holdMusic() else if (wasE) afterE()
                wasE = e
            }
        }
    }

    /**
     * E 첫 화면만 열어도 PREND 곡과 다른 앱 음악을 멈춘다. 잠깐 멈춤(GAIN_TRANSIENT)으로 잡아, 안내 없이 닫으면(잘못 누름) 다른 앱 음악이 다시 흐른다
     * (11/05: 전에는 길게 잡아 잘못 누르면 음악이 멈춘 채 돌아오지 않았다). 안내가 시작되면 안내의 긴 포커스로 넘긴다
     */
    private fun holdMusic() {
        if (hold != null || focus != null || active) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            // 통화 중에 열었으면 통화가 끝날 때 받는다(그때 음악이 다시 흐르지 않게)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener({ Prend.log("· 급할 때 첫 화면 포커스 변화 $it") }, main)
            .build()
        hold = req
        Prend.log("· 급할 때 첫 화면: 음악 멈춤(포커스 ${audio.requestAudioFocus(req)})")
    }

    private fun letGoHold(why: String) {
        val h = hold ?: return
        audio.abandonAudioFocusRequest(h)
        hold = null
        Prend.log("· 급할 때 첫 화면 포커스 놓음($why)")
    }

    /** E가 끝났다: 멈춰 둔 포커스를 놓고, 다음에 PREND 곡을 틀면 시동 뒤 첫 곡처럼 5초에 걸쳐 들인다(놀란 뒤에 소리가 확 들어오지 않게) */
    private fun afterE() {
        letGoHold("E 끝")
        Bridge.director?.afterEmergency()
    }

    /** 시험: E 첫 화면 포커스를 쥐고 있나 */
    val holding get() = hold != null

    /** 안내 목소리가 나오는 중인가(다른 음성 비서의 말을 가를 때 PREND 자신의 소리를 빼려고, ride/Timing) */
    val speaking get() = player != null

    /**
     * 에어백 신호. 화면이 닫혀 있어도 사고 화면으로 바꾸고 안내를 시작한다.
     * 고장 안내·사람이 연 사고 안내(목소리가 끝났어도)·아플 때 안내 중이면 끝내고 새로 시작한다(11/06: 전에는 사고 화면이 열려 있으면 아무 일도 하지 않았다).
     * 연기·불 안내 중이면 대피 안내는 두고 그 뒤에 "괜찮으세요?"를 더한다(대답이 없으면 구조 화면). "사고 났어"로 이미 물었어도 에어백이면 한 번 더 묻고,
     * 그 물음의 대답을 듣는 중이었으면 창을 닫고 3초 뒤에 같은 물음을 처음부터 다시 한다(검토 11/06: 전에는 대답 창만 닫혀 안내가 '듣는 중'에 갇혔다)
     */
    fun airbag() {
        if (active && mode == Scene.ACCIDENT && byAirbag) return
        val fire = Stage.ui.value.scene == Scene.ACCIDENT && Stage.ui.value.fire
        // 연기·불 안내에 더한 "괜찮으세요?"를 듣는 중: 그 대답은 버리고(늦게 와도 listenId로 버린다) 물음을 다시 한다
        val okWasOpen = fire && active && mode == Scene.ACCIDENT && listening && lines.getOrNull(index)?.asset == "e_ok"
        if (okWasOpen) { listening = false; listenId++; okBand(false) }
        Stage.endListen()
        Stage.ask(null)
        // 에어백은 짐작이 아니다: AI가 짐작해 연 안내의 [아니에요] 줄을 거둔다(검토 11/07 멋대로 판단)
        Stage.clearGuess()
        if (fire) {
            if (!active) { lastScene = Scene.ACCIDENT; start(Scene.ACCIDENT, "에어백", firstAfterMs = 3_000) }
            byAirbag = true
            // 구조 요청 화면의 때는 에어백이 터진 때로
            Stage.ui.update { it.copy(eSince = System.currentTimeMillis()) }
            Prend.log("◆ 에어백(연기·불 안내 중) → 대피 안내 뒤 괜찮은지 물음${if (okWasOpen) "(듣던 물음을 3초 뒤 다시)" else ""}")
            // 줄은 아직 그 물음을 가리킨다: 다시 묻는다(그사이 세우면 onCar가 대피 말 뒤에 붙인다)
            // 에어백 뒤 처음부터 다시 묻는 물음이라 "어"·"네?" 한마디에 천천히 다시 물을 기회도 새로(11/08 오류 점검 B11 검토)
            if (okWasOpen) { fireOkAirbag = true; fireOkPending = true; repeatedOk = false; next(3_000) }
            else askOkAfterFire(airbag = true)
            openScreen()
            return
        }
        if (active) end("에어백")
        // 고장 표시를 먼저 끈다(남아 있으면 start가 고장 안내로 다시 연다)
        Stage.ui.update { it.copy(breakdown = false, fire = false) }
        Stage.go(Scene.ACCIDENT)
        lastScene = Scene.ACCIDENT
        start(Scene.ACCIDENT, "에어백", firstAfterMs = 3_000)
        byAirbag = true
        openScreen()
    }

    /** 차 신호: 구조용 화면을 띄운 뒤 누가 문을 열면(구조하러 온 사람) 목소리로 다시 알린다(15초에 한 번까지) */
    /** 연기·불: 세운 뒤의 말을 했나 */
    private var fireParkSaid = false
    private fun firePark() = if (Stage.ui.value.care.electric == true) "e_fire_ev" else "e_fire_park"
    /** 전기차인가를 차에서 바로 읽어 화면(Guide.FIRE_EV)과 목소리를 맞춘다 */
    private fun syncElectric() { app.prend.car.CarLink.hubOrNull?.electric?.let { ev -> if (Stage.ui.value.care.electric != ev) Stage.ui.update { it.copy(care = it.care.copy(electric = ev)) } } }

    fun onCar(old: CarState, now: CarState) {
        // 연기·불 안내 중 P에 넣었다: "시동을 끄고 모두 내려 멀리, 119"를 바로(시동을 끄면 앱도 멈추니 그 전에)
        if (active && mode == Scene.ACCIDENT && Stage.ui.value.fire && !fireParkSaid && !old.parked && now.parked) {
            fireParkSaid = true
            Prend.log("◆ 연기·불: 세움 → 내려서 멀리")
            shownStep = 2
            Stage.ui.update { it.copy(step = 2) }
            talking = true
            // 에어백·"사고 났어"로 더한 "괜찮으세요?"를 아직 묻지 않았으면 그 뒤에
            lines = listOfNotNull(Say(firePark(), 2), if (fireOkPending) Say("e_ok", 2) else null)
            index = 0
            player?.let { it.release(); player = null }
            if (focus == null) requestFocus(300) else next(300)
            return
        }
        val drvOpened = (old.doors["ROW_1_LEFT"] ?: 0) == 0 && (now.doors["ROW_1_LEFT"] ?: 0) > 0
        val drvClosed = (old.doors["ROW_1_LEFT"] ?: 0) > 0 && (now.doors["ROW_1_LEFT"] ?: 0) == 0
        // 대피 문장 뒤에는 어느 문이든 본다(검토 11/07: 고속도로 갓길에서는 조수석 쪽으로 내리는 편이 안전한데, 운전석 문만 봐서 30초 뒤 빈 차에 대피 문장을 또 말했다)
        val anyOpened = now.doors.any { (k, v) -> v > 0 && (old.doors[k] ?: 0) == 0 }
        val anyClosed = now.doors.any { (k, v) -> v == 0 && (old.doors[k] ?: 0) > 0 }
        // 운전석 자리 값이 빔을 보이는 차인가: 서 있을 때 앉음 → 빔(달리는 동안 값의 뜻을 맞추며 뒤집힌 것은 세지 않는다)
        if (!seatWorks && old.parked && now.parked && old.seats["ROW_1_LEFT"] == true && now.seats["ROW_1_LEFT"] == false) { seatWorks = true; Prend.log("· 운전석 자리 값이 빔을 보임 → 다시 앉았는지는 자리 값으로 봄") }
        if (active && now.seats["ROW_1_LEFT"] == false) seatSawEmpty = true
        // 대피 문장을 말하기 시작한 뒤 문이 열렸다(말하는 도중도 잡는다): 나갔을 수 있다. 한 번이라도 열렸으면 대피 문장을 다시 말하지 않는다
        // 안내가 끝난 뒤(① 장면 준비 등)에 남은 대피 표시로는 세지 않는다(11/07 시험: 다음 장면의 혹시 사고 문 열림이 '대피 문장 뒤 문 열림'으로 찍혔다)
        if (active && evacOn && anyOpened) { evacOpened = true; if (!evacDoor) { evacDoor = true; Prend.log("· 대피 문장부터 문 열림(${if (drvOpened) "운전석" else "다른 자리"})") } }
        // 문을 열었다 앉은 채 닫았다: 나간 것이 아니다(검토 11/05). 자리 값을 믿지 못하는 차는 첫 닫힘을 내리며 닫은 것으로 센다(11/06)
        if (active && evacOn && !awayHold && anyClosed && now.seats["ROW_1_LEFT"] != false) { if (seatTrusted()) evacOpened = false else evacCloses++ }
        if (active && drvChecking && drvOpened) drvDoorAt = android.os.SystemClock.elapsedRealtime()
        if (active && drvChecking) {
            // 달리기 시작하면 시계를 지우고(다시 세우면 아래에서 새로), 운전석이 비면 묻지 않고 기다리고, 다시 앉으면 20초를 새로 센다
            if (old.parked && !now.parked) drvCheckAt = 0L
            if (!drvAway && now.seats["ROW_1_LEFT"] == false && (now.doors["ROW_1_LEFT"] ?: 0) > 0) { drvAway = true; drvCheckAt = 0L; Prend.log("· ${who()}: 운전석이 빔 → 묻지 않고 기다림") }
            if (drvAway && drvClosed && now.seats["ROW_1_LEFT"] != false) { drvAway = false; if (now.parked) drvCheckAt = android.os.SystemClock.elapsedRealtime() + DRV_CHECK_MS; Prend.log("· ${who()}: 다시 앉음 → 20초 뒤 물음") }
        }
        // 대피해 나갔다가 다시 앉았다(문이 닫히고 운전석이 비지 않음, 조수석으로 드나들어도): 멈춘 안내를 잇는다.
        // 자리 값을 믿지 못하는 차는 밖에서 닫은 것을 빼고 센다(11/06: 전에는 내린 뒤 밖에서 문을 닫는 순간 빈 차에 남은 안내를 다 말했다)
        if (active && awayHold && anyClosed && now.seats["ROW_1_LEFT"] != false) {
            if (seatTrusted() || --awayNeed <= 0) {
                awayHold = false
                Prend.log("· 대피 뒤 다시 앉음 → 안내 이어서")
                if (focus == null) requestFocus(GAP) else next(GAP)
            } else Prend.log("· 대피 뒤 문 닫힘(자리 값을 믿지 못해 밖에서 닫은 것으로 봄)")
        }
        // 운전자가 아프다고 한 뒤 세웠다: 20초 뒤 괜찮은지 묻는다
        if (active && drvChecking && !drvAway && drvCheckAt == 0L && !old.parked && now.parked) { drvCheckAt = android.os.SystemClock.elapsedRealtime() + DRV_CHECK_MS; Prend.log("· ${who()}: 세움 → 20초 뒤 괜찮은지 물음") }
        // 에어백 뒤 대답 없이 내렸던 운전자가 다시 앉았다: "괜찮으세요?"를 다시 묻는다(또 대답이 없으면 그때 구조 요청 화면, 검토 11/07 멋대로 판단)
        if (active && okAway && mode == Scene.ACCIDENT && now.seats["ROW_1_LEFT"] == true && old.seats["ROW_1_LEFT"] != true) {
            okAway = false
            Prend.log("· 사고 안내: 에어백 뒤 내렸던 운전석에 다시 앉음 → 괜찮은지 다시 물음")
            // 새로 묻는 물음이라 "어"·"네?" 한마디에 한 번 천천히 다시 물을 기회를 새로 준다(두 번째 한마디는 대답 없음으로 보므로, 11/08 오류 점검 B11)
            repeatedOk = false
            talking = true
            shownStep = Stage.ui.value.step
            lines = listOf(Say("e_ok", shownStep))
            index = 0
            player?.let { it.release(); player = null }
            if (focus == null) requestFocus(2_000) else next(2_000)
        }
        if (!active || !rescuing || !Stage.ui.value.rescue) return
        val opened = now.doors.any { (k, v) -> v > 0 && (old.doors[k] ?: 0) == 0 }
        val t = android.os.SystemClock.elapsedRealtime()
        if (!opened || t - helpAt < 15_000) return
        helpAt = t
        Prend.log("◆ 구조 요청: 문이 열려 다시 알림")
        talking = true
        lines = listOf(help())
        index = 0
        player?.let { it.release(); player = null }
        if (focus == null) requestFocus(300) else next(300)
    }

    private fun help() = Stage.ui.value.let { Say(if (it.baby) "e_help_baby" else if (it.kids == true) "e_help_kids" else "e_help", 1) }

    /** 운전자 확인을 기록에 적을 이름(누가 아픈지 모르고 묻는 중이면 따로) */
    private fun who() = if (drvSoft) "누가 아픈지 모름" else "운전자가 아픔"

    /** 1초마다: 사람이 연 사고·아플 때 화면이면 시작, 화면에서 걸음을 넘기면 목소리를 멈추고, 그 화면을 닫으면 끝낸다 */
    fun tick() {
        val ui = Stage.ui.value
        // 바로 전에 빨리 달렸나(가벼운 사고 순서를 막는다. 위치를 몰라도 차 속도로 본다)
        if (car().speedKmh >= FAST_KMH) fastAt = android.os.SystemClock.elapsedRealtime()
        // 달리는 중에 말한 응급처치 카드: P에 넣으면 연다
        if (ui.aidWant != null && ui.parked && (ui.scene == Scene.ILLNESS || ui.scene == Scene.EMERGENCY)) { Stage.openAid(ui.aidWant); return }
        val guide = ui.scene == Scene.ACCIDENT || ui.scene == Scene.ILLNESS || ui.scene == Scene.AID
        val opened = guide && (ui.scene != lastScene || (ui.scene == Scene.AID && ui.aid != lastAid))
        // 연기·불 안내로 다시 시작할 때 이미 켜진 SOS 띠를 남기려고 지금 값을 둔다(11/08 오류 점검 B6)
        val sosBefore = ui.sos
        lastScene = ui.scene
        lastAid = ui.aid
        // AI가 짐작해 연 안내의 '‘사고’로 알아들었어요 [아니에요]' 줄은 20초 뒤 거둔다(검토 11/07 멋대로 판단)
        if (ui.guess != null) {
            val t = android.os.SystemClock.elapsedRealtime()
            // 새 짐작(다른 갈래)이면 시계를 새로
            if (ui.guess != guessSeen) { guessSeen = ui.guess; guessAt = t }
            else if (t - guessAt >= GUESS_MS) { Prend.log("· 짐작 줄: 20초 지나 거둠"); Stage.clearGuess() }
        } else guessSeen = null
        // 사고 화면에서 사람이 걸음을 넘겼으면 연습이 아니다(닫는 순간 걸음은 1로 돌아가므로 지금 본다).
        // 목소리가 넘긴 걸음은 세지 않는다(검토 11/07 멋대로 판단: 아무도 대답하지 않아도 목소리가 2걸음으로 가면 사고 메모가 남았다)
        if (active && mode == Scene.ACCIDENT && ui.scene == Scene.ACCIDENT && ui.step != shownStep) memoKept = true
        // 에어백 뒤 내린 운전자를 기다리는 동안 사람이 화면에서 걸음을 넘겼다: 화면을 보고 있으니 다시 앉아도 묻지 않는다
        if (okAway && ui.step != shownStep) { okAway = false; Prend.log("· 사고 안내: 내린 뒤 기다리는 중 사람이 화면에서 걸음을 넘김 → 다시 묻지 않음") }
        if (active && (ui.scene != mode || opened)) end(if (guide) "갈래 바뀜" else "E 화면 닫힘")
        if (heldForListen && ui.listen == null) { heldForListen = false; if (active && talking) { Prend.log("· 급할 때 안내: [프렌드] 끝 → 끊긴 문장부터"); next(800) } }
        if (!active) {
            // E 첫 화면이 연 듣기(8초)를 먼저 닫는다: 열린 채면 "괜찮으세요?"와 빠른 대답을 '부탁'으로 고르러 갔다(검토 11/06)
            if (opened) { Stage.endListen(); start(ui.scene, "화면·말", firstAfterMs = 600) }
            return
        }
        // 사고 안내 중 "연기 나"·"타이어가 터졌어"로 바뀌었다: 화면만이 아니라 목소리도 그 갈래로 다시 시작한다(검토 11/05).
        // 고장 안내 중 "사고 났어"로 고장 표시가 꺼졌으면 "괜찮으세요?"부터 사고 안내로(11/06)
        if (mode == Scene.ACCIDENT && ui.scene == Scene.ACCIDENT &&
            ((ui.fire && !startedFire) || (ui.breakdown && !startedBreakdown && !ui.fire) || ((startedFire || startedBreakdown) && !ui.fire && !ui.breakdown))) {
            end(when { ui.fire -> "연기·불로 바뀜"; ui.breakdown -> "고장으로 바뀜"; else -> "고장에서 사고로 바뀜" })
            start(Scene.ACCIDENT, "말", firstAfterMs = 300)
            // 대답에 다쳤다는 말이 함께 있었거나 이미 SOS 띠가 떠 있었으면(다쳤다고 한 뒤 따로 "연기 나요") 연기·불 안내에도 SOS 띠를.
            // start가 띠를 지워 다친 사람이 있는데 [119 전화]가 3걸음으로 밀렸다(11/08 오류 점검 B6)
            if ((fireSos || sosBefore) && Stage.ui.value.fire) { Stage.ui.update { it.copy(sos = true) }; Prend.log("· 사고 안내: SOS 띠(연기·불 + 다침)") }
            fireSos = false
            return
        }
        // 아플 때 안내 중 말로 아픈 사람이 바뀌었다("아이가 열이 나"·"제가 아파요"·달리며 "목에 걸렸어"): 처음부터 다시(11/06: 전에는 화면만 첫 걸음으로 가고 목소리는 처음 판단대로 멈췄다)
        if (mode == Scene.ILLNESS && ui.scene == Scene.ILLNESS && (ui.illOther != startedIllOther || ui.kid != startedKid || ui.aidWant != startedWant)) {
            end("아픈 사람이 바뀜")
            start(Scene.ILLNESS, "말", firstAfterMs = 300, again = true)
            return
        }
        if (mode == Scene.ACCIDENT) after.place(ui.place)
        if (mode == Scene.AID) { aidTick(ui); return }
        // 운전자가 아플 때의 구조용 화면을 [괜찮아요]로 거뒀다: 정신이 있으니 묻기를 그만두고 아플 때 화면으로
        if (rescuing && !ui.rescue && mode == Scene.ILLNESS) {
            rescuing = false; drvChecking = false; drvCheckAt = 0L
            Prend.log("· 구조용 화면 거둠(사람이 누름) → 아플 때 화면")
            quiet()
            return
        }
        // 구조용 화면을 사람이 [괜찮아요]로 거뒀다: 정신이 있으니 걸음 안내를 이어 간다(연기·불 안내 중이었으면 그 대피 안내를 다시)
        if (rescuing && !ui.rescue) {
            rescuing = false
            Prend.log("· 구조용 화면 거둠(사람이 누름) → 걸음 안내")
            player?.let { it.release(); player = null }
            talking = true
            lines = if (ui.fire) listOf(if (car().parked) Say(firePark(), 2) else Say("e_fire_drive", 1)) else steps(ui.baby)
            index = 0
            shownStep = Stage.ui.value.step
            if (focus == null) requestFocus(300) else next(300)
            return
        }
        if (talking && ui.step != shownStep) {
            // 운전자 확인 "괜찮으세요?"를 말하거나 듣던 중이었으면 그 물음은 세지 않고 20초 뒤 다시 묻는다(chooseHospital과 같게).
            // 물을 때 이미 시계를 0으로 돌려 두어, 그대로 두면 운전자 확인이 아예 멈췄다(11/08 오류 점검 B3)
            val okCut = mode == Scene.ILLNESS && drvChecking && lines.getOrNull(index)?.asset == "e_ok"
            Prend.log("· 급할 때 안내: 사람이 화면에서 걸음을 넘김 → 목소리 멈춤"); evacWaitAt = 0L; awayHold = false; quiet()
            if (okCut) {
                if (drvChecks > 0) drvChecks--
                if (car().parked && !drvAway && !rescuing && !askingWho) drvCheckAt = android.os.SystemClock.elapsedRealtime() + DRV_CHECK_MS
                Prend.log("· ${who()}: 묻던 중 걸음을 넘김 → 그 물음은 세지 않고 20초 뒤 다시")
            }
        }
        // 시작할 때 위치를 몰라 일반 도로 고장 순서로 시작했는데 그사이 내비 자리가 들어와 고속도로로 보이면, 삼각대 문장 전에 고속도로 순서(대피 먼저)로 바꾼다.
        // 삼각대 문장을 말하는 중이면 멈추고 대피 문장부터(11/08 오류 점검 B2)
        if (mode == Scene.ACCIDENT && talking && ui.breakdown && !ui.fire && !ui.highway && !evacOn && !rescuing && index <= 1 && onHighway()) {
            val cut = index == 1 && player != null && lines.getOrNull(1)?.asset == "e_rd_4"
            Stage.ui.update { it.copy(highway = true) }
            lines = listOf(Say("e_hw_1", 1), Say("e_hw_2", 2), Say("e_tow_out", 3), Say("e_brk_ins_hw", 4))
            Prend.log("· 고장 안내: 그사이 내비 자리가 들어와 고속도로 순서로 바꿈${if (cut) "(삼각대 문장을 멈추고 대피부터)" else ""}")
            if (cut) { player?.let { it.release(); player = null }; next(GAP) }
        }
        // 시작할 때 위치를 몰라 병원을 찾지 못했는데 그사이 내비 자리가 들어왔다: 한 번 더 찾는다(11/08 오류 점검 B2)
        if (mode == Scene.ILLNESS && hospNoPlace && navi?.last != null) {
            hospNoPlace = false
            Prend.log("· 아플 때: 그사이 내비 자리가 들어와 병원을 다시 찾음")
            findHospitals()
        }
        // 물을 때 병원이 없어(위치 모름·찾는 중) 목소리가 묻지 못하고 끝났는데 이제 찾았다: 그때 한 번 묻는다.
        // 사람이 화면에서 걸음을 넘겨받았거나(ui.step != shownStep) 이미 골랐거나 운전자가 아프면 묻지 않는다(11/08 오류 점검 B2)
        if (mode == Scene.ILLNESS && hospMissed && !talking && !listening && !rescuing && !askingWho && ui.listen == null && ui.step == shownStep && ui.step <= 2 &&
            (ui.hosp.er != null || ui.hosp.moon != null) && ui.hosp.chosen == null && ui.aidWant == null && !ui.driverIll) {
            hospMissed = false
            askedHospital = false
            Prend.log("· 아플 때: 묻지 못했던 병원을 이제 찾아 물음")
            // sayNow가 줄 끝에서 병원을 묻는다(otherIll과 같게)
            talking = true; lines = emptyList(); index = 0
            if (focus == null) requestFocus(GAP) else next(GAP)
        }
        if (evacWaitAt > 0) evacWaitTick()
        if (drvCheckAt > 0) driverCheckTick(ui)
    }

    /**
     * 대피 문장 뒤 그다음 한 줄까지 말한 뒤: 문(어느 자리든)이 열려 나갔으면 목소리를 멈추고(다시 앉으면 onCar가 잇는다),
     * 대피 문장이 끝난 지 고속도로 30초·그 밖 20초가 지나도 나가지 않았으면 다음 걸음으로. 고속도로에서 문이 한 번도 열리지 않고 P 그대로면
     * 같은 대피 문장을 지금 걸음 그대로 한 번 더 말한 뒤 다음 걸음으로(10/29 결정 3. 화면을 2걸음으로 되돌리지 않는다)
     */
    private fun evacWaitTick() {
        val c = car()
        val doorOpen = c.doors.values.any { it > 0 }
        val seatEmpty = c.seats["ROW_1_LEFT"] == false
        // 운전석 자리 값을 믿을 수 있고 운전석이 앉음이면 다른 문이 열려 있어도 나간 것이 아니다(검토 11/07 멋대로 판단:
        // 동승자가 뒷문을 열어 둔 채 나가면 다리가 아파 운전석에 남은 운전자에게 견인·보험 안내를 하지 않았다)
        val seated = seatTrusted() && c.seats["ROW_1_LEFT"] == true
        // 열린 채이거나 빈자리면 나간 것(혹시 사고로 물을 때 이미 열어 둔 문은 빈자리로 본다). 자리 값을 믿지 못하는 차는 대피 문장 뒤 닫힘을 내리며 닫은 것으로 본다
        val gone = !seated && ((doorOpen && (evacOpened || seatEmpty)) || (evacOpened && seatEmpty) || (!seatTrusted() && evacCloses > 0))
        if (gone) {
            evacWaitAt = 0L; awayHold = true; index++
            // 열린 채면 밖에서 닫는 것과 다시 앉아 닫는 것 둘, 이미 닫혔으면 다시 앉아 닫는 것 하나(자리 값을 믿을 때는 자리 값으로)
            awayNeed = if (doorOpen) 2 else 1
            Prend.log("· 대피: 문이 열려 나감 → 목소리 멈춤(다시 앉으면 이어서${if (seatTrusted()) "" else ", 자리 값을 믿지 못함"})")
            return
        }
        val hw = evacLine == "e_hw_2"
        if (android.os.SystemClock.elapsedRealtime() - evacAt < if (hw) EVAC_AGAIN_MS else EVAC_WAIT_MS) return
        evacWaitAt = 0L
        index++
        if (hw && !evacAgain && !evacDoor && c.parked && !seatEmpty) {
            evacAgain = true
            Prend.log("◆ 대피 문장 다시: 30초가 지나도 문이 하나도 열리지 않음")
            lines = lines.toMutableList().apply { add(index.coerceAtMost(size), Say("e_hw_2", shownStep)) }
        } else Prend.log("· 대피 문장 뒤 ${if (hw) 30 else 20}초: 나가지 않아 다음 걸음으로${if (hw && !evacAgain) "(${if (!c.parked) "P 아님" else if (seatEmpty) "운전석 비었음" else "문이 열렸었음"})" else ""}")
        next(GAP)
    }

    /** 운전자가 아프다고 한 뒤(또는 누가 아픈지 모르고) 세운 지 20초: "괜찮으세요?"를 묻는다(두 번까지) */
    private fun driverCheckTick(ui: StageUi) {
        // [프렌드]로 듣는 중이면 그 뒤에(검토 11/05: 사람이 말하는 중에 묻고 그 말을 버렸다)
        // 누가 아픈지 화면으로 묻는 중이면 그 대답이 먼저(askWho)
        if (android.os.SystemClock.elapsedRealtime() < drvCheckAt || !ui.parked || talking || listening || rescuing || ui.listen != null || drvAway || askingWho) return
        drvCheckAt = 0L
        drvChecks++
        Prend.log("◆ ${who()}: 세운 뒤 괜찮은지 물음(${drvChecks}번째)")
        talking = true
        // 사람이 화면에서 걸음을 넘겨 두었으면 그 걸음에 맞춘다(아니면 sayNow가 '사람이 넘김'으로 보고 말없이 멈췄다, 검토 11/05)
        shownStep = ui.step
        lines = listOf(Say("e_ok", ui.step))
        index = 0
        if (focus == null) requestFocus(300) else next(300)
    }

    /**
     * 운전자에게 물은 "괜찮으세요?"의 대답. 말이 있으면 그만 묻고, 두 번 다 없으면 구조하러 온 사람이 읽을 화면으로.
     * 아이·옆 사람 이야기가 섞였으면("저는 괜찮은데 애가 아파요") 운전자는 괜찮은 것으로 보고 병원 걸음으로 잇는다(11/06: 전에는 '아파'만 보고 119를 세 번 되풀이했다).
     * 누가 아픈지 모르고 물었으면 두 번 대답이 없어도 구조 화면 없이 그친다
     */
    private fun answeredDriver(text: String) {
        if (!active) return
        val s = text.replace(" ", "")
        // "뭐라고?"·놀라서 낸 "네?"·"어": 한 번만 천천히 다시(묻는 횟수에 세지 않는다)
        if (s.isNotBlank() && !repeatedOk && (Stage.asksAgain(text) || Answer.unsure(text))) {
            repeatedOk = true; drvChecks--; slowOnce = true
            Prend.log("· ${who()}: 다시 말해 달라고 함 → 천천히 한 번 더")
            drvCheckAt = android.os.SystemClock.elapsedRealtime()
            talking = false; releaseFocus(); return
        }
        // 두 번째도 "네?"·"어" 한마디면 대답이 없던 것으로 본다(검토 11/07 멋대로 판단 원칙 2: 뜻이 분명하지 않은 한마디는 어느 물음에서도 대답이 아니다)
        if (s.isNotBlank() && Answer.unsure(text)) Prend.log("· ${who()}: 또 뜻이 분명하지 않은 한마디 → 대답 없음으로")
        else if (s.isNotBlank()) {
            // 신음("아이구"·"엄마야")과 몸 자리("목 뒤에가")는 사람으로 세지 않는다(검토 11/07 멋대로 판단)
            val plain = dropGroans(s)
            val whoSaid = Stage.illWhoIn(plain)
            if (whoSaid == true) {
                val kid = Stage.childIn(plain)
                // 운전자가 아프다고 했으면(혼자 [갑자기 아파요]를 눌렀어도) 대답 한마디로 뒤집지 않고 화면으로 한 번 묻는다
                // (검토 11/07 멋대로 판단: 잘못 알아들으면 아픈 운전자에게 병원까지 직접 몰고 가라는 셈이다)
                if (!drvSoft) { askWho(text.length, kid, fromAnswer = true); return }
                otherIll(kid, "대답에 같이 탄 사람 이야기(말 ${text.length}자)")
                return
            }
            // 나아졌다는 말(검토 11/07 멋대로 판단): "좀 나아졌어요"·"아까보다 덜 아파요"는 괜찮다는 말로 본다. "안 나아졌어"·"더 아파"는 아니다
            val worse = NOT_BETTER.any { it in s }
            val better = !worse && BETTER.any { it in s }
            val calm = !worse && !hurtIn(if (better) BETTER.fold(s) { a, w -> a.replace(w, "") } else s) && NOT_OK.none { it in s } &&
                (better || OK.any { it in s } || NO_119.containsMatchIn(s) || Answer.short(text) && !Answer.unsure(text))
            if (calm) {
                drvChecking = false
                Prend.log("· ${who()}: 괜찮다고 함(말 ${text.length}자) → 그만 물음")
                Stage.clearGuess()
                talking = false; releaseFocus(); return
            }
            // 누가 아픈지 몰랐는데 "제가 아파요"·[아파요]: 운전자로 본다(두 번 대답이 없으면 구조 화면).
            // "제가"·"내가"나 낱말 하나로 선 "저"·"나"일 때만(검토 11/07 멋대로 판단: "나아졌어요"처럼 '나'로 시작할 뿐인 말로 바꾸지 않았다)
            if (drvSoft && selfSaid(text)) {
                drvSoft = false
                Stage.ui.update { it.copy(driverIll = true, illOther = false) }
                startedIllOther = false
                Prend.log("· 누가 아픈지 모름: 운전자 자신이라고 함 → 운전자가 아픔으로")
            }
            // 아프다는 말이면 짐작 줄을 거둔다(알아듣지 못한 말이면 [아니에요]를 남겨 둔다)
            if (hurtIn(s)) Stage.clearGuess()
            notOk(text.length)
            return
        }
        if (drvChecks < 2) {
            drvCheckAt = android.os.SystemClock.elapsedRealtime() + DRV_CHECK_MS
            Prend.log("· ${who()}: 대답 없음 → 20초 뒤 한 번 더")
            talking = false; releaseFocus(); return
        }
        // 누가 아픈지 모르면 구조 화면 대신 그친다(부모가 아이를 돌보느라 대답하지 못했을 수 있다, 11/06)
        if (drvSoft) { drvChecking = false; Prend.log("· 누가 아픈지 모름: 두 번 대답 없음 → 그만 물음(구조 화면 없이)"); talking = false; releaseFocus(); return }
        // 내렸으면(운전석이 빔) 구조 화면 대신 다시 앉기를 기다린다(바람 쐬러 내린 운전자, 검토 11/05)
        if (car().seats["ROW_1_LEFT"] == false) { drvAway = true; Prend.log("· 운전자가 아픔: 대답 없음, 운전석이 빔 → 다시 앉기를 기다림"); talking = false; releaseFocus(); return }
        // 자리 값을 믿지 못하는 차에서 두 번 묻는 사이(약 50초) 운전석 문이 열렸으면 내렸을 수 있다: 구조 화면 전에 한 번 더 묻는다(11/06)
        if (!seatTrusted() && drvDoorAt > 0 && android.os.SystemClock.elapsedRealtime() - drvDoorAt < 2 * DRV_CHECK_MS + 10_000 && !drvDoorWaited) {
            drvDoorWaited = true; drvChecks--
            drvCheckAt = android.os.SystemClock.elapsedRealtime() + DRV_CHECK_MS
            Prend.log("· 운전자가 아픔: 대답 없음, 그사이 운전석 문이 열림(자리 값을 믿지 못함) → 20초 뒤 한 번 더")
            talking = false; releaseFocus(); return
        }
        drvChecking = false
        rescuing = true
        helpAt = android.os.SystemClock.elapsedRealtime()
        shownStep = 1
        Stage.ui.update { it.copy(rescue = true, step = 1) }
        Prend.log("◆ 구조 요청 화면(운전자가 아프다고 한 뒤 두 번 대답 없음)")
        lines = listOf(help())
        index = 0
        next(GAP)
        // 에어백처럼 화면을 앞으로(막히면 배너가 구조 문구를 둔다)
        if (!Stage.ui.value.visible) openScreen()
    }

    /** 운전자 확인의 대답이 '괜찮지 않음'(아프다·도와 달라·모르는 말): 정신은 있으니 구조 화면은 띄우지 않고 119를 다시 알린 뒤 20초 뒤 또 본다(세 번까지, 검토 11/05) */
    private fun notOk(len: Int) {
        Prend.log("· ${who()}: 괜찮지 않다고 함(말 ${len}자) → 119 다시, 20초 뒤 또 물음")
        if (drvChecks < 3) drvCheckAt = android.os.SystemClock.elapsedRealtime() + DRV_CHECK_MS else drvChecking = false
        // "화면의 119 버튼을 누르면 돼요"는 첫 걸음(119 단추가 있는 걸음)에서 말한다(검토 11/06: 병원 걸음에 머문 채 없는 단추를 누르라고 했다).
        // 사람이 화면에서 걸음을 넘겨 두었으면 그 걸음에서 시작한다(아니면 sayNow가 '사람이 넘김'으로 보고 말없이 멈춘다)
        talking = true
        shownStep = Stage.ui.value.step
        lines = listOf(Say("e_ill_drv_p", 1))
        index = 0
        if (focus == null) requestFocus(GAP) else next(GAP)
    }

    /** 아픈 사람은 같이 탄 사람이다: 운전자 확인을 그치고 화면도 그쪽으로(운전자 줄을 거둔다). 병원을 아직 묻지 않았으면 묻는다 */
    private fun otherIll(kid: Boolean, why: String) {
        val was = who()
        drvChecking = false; drvSoft = false; drvCheckAt = 0L
        Prend.log("· $was: $why → 운전자는 괜찮음, 그만 물음")
        Stage.clearGuess()
        // 말로 바뀐 것과 가르려고 시작할 때의 값도 맞춘다(tick이 처음부터 다시 하지 않게)
        Stage.ui.update { it.copy(driverIll = false, illOther = true, kid = it.kid || kid) }
        startedIllOther = true; startedKid = Stage.ui.value.kid
        // sayNow가 줄 끝에서 병원을 묻는다(사람이 넘겨 둔 걸음에 맞춘다)
        if (!askedHospital && Stage.ui.value.aidWant == null) {
            talking = true; lines = emptyList(); index = 0; shownStep = Stage.ui.value.step
            if (focus == null) requestFocus(GAP) else next(GAP)
        } else { talking = false; releaseFocus() }
    }

    /** 누가 아픈지 화면으로 묻는 중(그동안 운전자 확인을 멈춘다)과 그 물음의 회차 */
    private var askingWho = false
    private var whoId = 0

    /**
     * 운전자가 아프다고 했는데(또는 혼자 [갑자기 아파요]를 눌렀는데) 대답이나 [프렌드]로 한 말에 같이 탄 사람 이야기가 섞였다(검토 11/07 멋대로 판단 원칙 4).
     * 한마디로 뒤집지 않고 화면에 [제가 아파요]·[같이 탄 사람]을 한 번 띄운다(말로는 "제가"·"애가"처럼 사람을 말해야 정한다). 대답이 없으면 운전자 그대로.
     * fromAnswer: "괜찮으세요?"의 대답이었다(운전자 확인을 잇는다). 아니면 [프렌드]로 한 말이라 하던 안내는 그대로 두고, 같이 탄 사람이면 그쪽으로 처음부터
     */
    private fun askWho(len: Int, kid: Boolean, fromAnswer: Boolean) {
        Prend.log("· 운전자가 아픔: ${if (fromAnswer) "대답(말 ${len}자)" else "[프렌드]로 한 말"}에 같이 탄 사람 이야기 → 바꾸지 않고 누가 아픈지 화면으로 물음")
        askingWho = true
        // 안내가 끝나거나 다시 시작한 뒤에 온 대답은 버린다(end가 회차를 늘린다)
        val id = ++whoId
        if (fromAnswer) { drvCheckAt = 0L; talking = false; releaseFocus() }
        Stage.confirm("WHO", "누가 아프세요?", "운전하는 분이 아프면 운전을 멈추는 안내를 해요", "제가 아파요", "같이 탄 사람", pick = WHO_SELF to WHO_OTHER) { a ->
            if (id != whoId) return@confirm
            askingWho = false
            if (!active || mode != Scene.ILLNESS) return@confirm
            when (a) {
                false -> if (fromAnswer) otherIll(kid, "화면에서 같이 탄 사람이 아프다고 함") else {
                    // [프렌드]로 한 말이었으면 같이 탄 사람 쪽으로 처음부터 다시 안내한다(tick이 아픈 사람이 바뀐 것을 보고 다시 시작한다)
                    Prend.log("· 운전자가 아픔: 화면에서 같이 탄 사람이 아프다고 함 → 같이 탄 사람으로 처음부터")
                    Stage.clearGuess()
                    Stage.ui.update { it.copy(illOther = true, kid = it.kid || kid) }
                }
                true -> {
                    Prend.log("· 운전자가 아픔: 제가 아파요 → 운전자 그대로")
                    Stage.clearGuess()
                    if (fromAnswer && drvChecking) notOk(len)
                }
                null -> {
                    Prend.log("· 운전자가 아픔: 누가 아픈지 대답 없음 → 운전자 그대로")
                    if (fromAnswer && drvChecking) { if (drvChecks < 3) drvCheckAt = android.os.SystemClock.elapsedRealtime() + DRV_CHECK_MS else drvChecking = false }
                }
            }
        }
    }

    /** 아플 때 안내 중 [프렌드]로 한 말에 같이 탄 사람 이야기가 섞였다(Stage.act): 운전자가 아프다고 한 뒤면 바로 바꾸지 않고 묻는다. 받았으면 true */
    private fun whoSaidLater(kid: Boolean): Boolean {
        if (!active || mode != Scene.ILLNESS || !Stage.ui.value.driverIll) return false
        if (!askingWho) askWho(0, kid, fromAnswer = false)
        return true
    }

    /** 운전자 자신이라는 분명한 말인가: "제가"·"내가"로 시작하는 낱말, 낱말 하나로 선 "저"·"나"·"전"·"난"("문제가"·"안내가"·"나아졌어요"는 아니다) */
    private fun selfSaid(text: String): Boolean {
        val w = text.trim().split(Regex("[\\s,.!?~]+")).filter { it.isNotBlank() }
        return w.any { it.startsWith("제가") || it.startsWith("내가") || it in SELF_WORDS }
    }

    /** 신음·감탄("아이구"·"아이쿠"·"아이씨"·"엄마야")과 몸 자리("목 뒤에가")를 지운다: 아이·같이 탄 사람으로 세지 않게(빈칸 지운 말) */
    private fun dropGroans(s: String) = GROANS.fold(s) { a, w -> a.replace(w, "") }

    /**
     * 연기·불 안내에 "괜찮으세요?"를 더한다(에어백·"사고 났어", 11/06). 대피 안내는 그대로 두고 그 뒤에 묻는다:
     * 말하는 중이면 남은 줄 끝에, 안내가 끝났으면 이 물음만. 대답은 answered가 받는다(대답이 없으면 에어백일 때 구조 화면, 다쳤으면 SOS).
     * 말로 물은 뒤에 에어백이 터지면 한 번 더 묻는다(앞서 괜찮다고 했어도 그사이 정신을 잃었을 수 있다). 아직 하지 않은 물음이 줄에 있으면 그것이 함께 묻는다
     */
    private fun askOkAfterFire(airbag: Boolean = false) {
        if (if (airbag) fireOkAirbag else fireOk) return
        if (airbag) fireOkAirbag = true
        if (fireOk && fireOkPending && talking) return
        fireOk = true
        fireOkPending = true
        // 새로 묻는 물음이라 "어"·"네?" 한마디에 천천히 다시 물을 기회를 새로 준다(앞 물음에서 쓴 기회가 남아 첫 한마디를 바로 대답 없음으로 보지 않게, 11/08 오류 점검 B11 검토)
        repeatedOk = false
        if (talking) { lines = lines + Say("e_ok", lines.lastOrNull()?.step ?: shownStep); return }
        // 사람이 화면에서 걸음을 넘겨받았으면 그 걸음에 맞춰 둔다(아니면 sayNow가 '사람이 넘김'으로 보고 말하지 않는다). [프렌드]로 듣는 중이면 끝난 뒤에
        shownStep = Stage.ui.value.step
        if (Stage.ui.value.listen != null) heldForListen = true
        sayOnly(Say("e_ok", shownStep))
    }

    /**
     * 에어백 뒤에는 블루링크 자동 통보가 먼저 이어지도록 3초 기다렸다 말한다(음악은 바로 멈춘다).
     * 사람이 연 화면은 바로 말한다. again은 안내 중 말로 아픈 사람이 바뀌어 다시 시작하는 것(처음 연 방법은 그대로 둔다)
     */
    private fun start(scene: Scene, why: String, firstAfterMs: Long, again: Boolean = false) {
        // 급할 때 안내가 열린 수(기기 안, ride/Stats)
        Stats.add("e")
        // 시작한 때: 안내 중에 다시 시작하면(불·고장으로 바뀜, 아픈 사람이 바뀜) 처음 때를 둔다
        if (!active || Stage.ui.value.eSince == 0L) Stage.ui.update { it.copy(eSince = System.currentTimeMillis()) }
        mode = scene
        active = true
        talking = true
        shownStep = 1
        listenId++
        pausedByDrive = false
        heldForListen = false
        index = 0
        waitedCall = false
        askedHospital = false
        askedMoon = false
        hospAgain = false
        waitHosp = 0
        hospNoPlace = false; hospMissed = false
        byAirbag = false
        rescuing = false
        okAway = false
        askingWho = false
        evacOn = false; evacLine = null; evacAt = 0L; evacDoor = false; evacAgain = false
        evacWaitAt = 0L; evacOpened = false; evacCloses = 0; awayHold = false; awayNeed = 0
        seatSawEmpty = car().seats["ROW_1_LEFT"] == false
        repeatedOk = false; slowOnce = false
        drvChecking = false; drvCheckAt = 0L; drvChecks = 0; drvAway = false; drvSoft = false; drvDoorAt = 0L; drvDoorWaited = false
        fireOk = false; fireOkAirbag = false; fireOkPending = false
        startedFire = Stage.ui.value.fire; startedBreakdown = Stage.ui.value.breakdown
        // 위치를 모르면 내비 잇기를 한 번 더 해 본다(연결에 실패했으면 119 위치·고속도로·병원 찾기가 그 운행 내내 막혔다, 검토 11/06)
        if (navi != null && navi.last == null && scene != Scene.AID) navi.retry("급할 때, 지금 자리를 모름")
        if (scene == Scene.ACCIDENT) {
            val hw = onHighway()
            Stage.ui.update { it.copy(highway = hw, minor = false, rescue = false, sos = false, step = 1) }
            // 연기·불(11/02): 달리는 중이면 "세우세요" 한 줄, 서 있으면 바로 "시동 끄고 모두 내려 멀리, 119"(전기차는 따로). 세우는 순간 둘째 말을 한다(onCar)
            if (Stage.ui.value.fire) {
                syncElectric()
                val drive = !car().parked
                fireParkSaid = !drive
                lines = if (drive) listOf(Say("e_fire_drive", 1)) else listOf(Say(firePark(), 2))
                // 서 있으면 둘째 걸음부터: 보여 준 걸음도 2로 맞춘다(1로 두면 틱과 말하기가 '사람이 화면에서 넘김'으로 보고 이 말을 하지 않았다, 11/03 시험 26)
                if (!drive) { Stage.ui.update { it.copy(step = 2) }; shownStep = 2 }
                Prend.log("◆ 연기·불 안내 시작($why, ${if (drive) "달리는 중" else "서 있음"}${if (Stage.ui.value.care.electric == true) ", 전기차" else ""})")
                Stage.ui.update { it.copy(place = place()) }
                requestFocus(firstAfterMs)
                return
            }
            // 고장(11/02): 괜찮으세요를 묻지 않고 대피·견인·긴급출동을 차례로. 사고 메모는 적지 않는다.
            // 고속도로면 대피 문장 뒤 견인 번호를 말로 읽는 한 줄(e_tow_out, 11/06: 바로 내린 사람은 "번호는 화면에 있어요"를 차 밖에서 볼 수 없다)
            if (Stage.ui.value.breakdown) {
                lines = if (hw) listOf(Say("e_hw_1", 1), Say("e_hw_2", 2), Say("e_tow_out", 3), Say("e_brk_ins_hw", 4))
                else listOf(Say("e_rd_1", 1), Say("e_rd_4", 2), Say("e_brk_wait", 3), Say("e_brk_ins", 4))
                Prend.log("◆ 고장 안내 시작($why, ${if (hw) "고속도로" else "일반 도로"})")
                Stage.ui.update { it.copy(place = place()) }
                requestFocus(firstAfterMs)
                return
            }
            lines = listOf(Say("e_ok", 1))
            Prend.log("◆ 사고 안내 시작($why, ${if (hw) "고속도로" else "일반 도로"}${if (Stage.ui.value.baby) ", 아이 탑승" else if (Stage.ui.value.kids == true) ", 가족에 아이" else ""})")
            // 장소는 지금 위치로 바로 맞춰 적는다(화면의 위치를 기다리면 첫 1초 동안 지난번 급할 때의 위치가 적힐 수 있었다)
            val p = place()
            Stage.ui.update { it.copy(place = p) }
            memoPrev = after.snapshot()
            after.begin(p, weather(car()))
            memoAt = android.os.SystemClock.elapsedRealtime()
            memoKept = false
        } else if (scene == Scene.AID) {
            val ui = Stage.ui.value
            // 카드를 연 직후 사람이 이미 [다음]을 눌렀으면 그 걸음부터 읽는다
            val all = Guide.aid(ui.aid)
            val from = ui.step.coerceIn(1, all.size)
            shownStep = from
            lines = all.indices.filter { it + 1 >= from }.map { Say("${ui.aid.voice}_${it + 1}", it + 1) }
            // 같은 시계를 이어 가면(같은 카드를 다시 엶) 이미 한 5분 알림은 다시 하지 않는다
            if (ui.aidSince != aidSinceSeen) { said5min = ui.aid == Aid.SEIZURE && ui.aidSince == said5minFor; pending5min = false }
            aidSinceSeen = ui.aidSince
            Prend.log("◆ 응급처치 카드 안내 시작(${ui.aid.name}, $why)")
        } else {
            // 병원 걸음(2/3)으로 열었나(응급처치 카드 끝의 [가까운 병원]이 Stage.go(ILLNESS, 2)로 연다). 다시 시작하는 것과 달리며 말한 카드는 빼고 본다(11/08 오류 점검 E3)
            val atHosp = !again && Stage.ui.value.step == 2 && Stage.ui.value.aidWant == null
            Stage.ui.update { it.copy(step = 1, rescue = false) }
            val ui0 = Stage.ui.value
            val want = ui0.aidWant
            // 달리는 중이면 "전화는 핸즈프리로 거세요. 상황요원의 안내를 따르세요."(별첨 §5-4 차례 1·2). 목소리 파일이 아직 없으면 건너뛴다
            val drive = !car().parked
            // 아픈 사람이 운전자인가(11/02): 말에 남(아이·엄마·옆 사람…)이 있거나 응급처치 카드면 같이 탄 사람, "내가"·"운전하다가"면 운전자,
            // 말이 없으면(버튼) 혼자 탔을 때 운전자로 본다. 운전자면 병원 경유지를 권하지 않는다(아픈 채로 직접 몰고 가게 하지 않는다)
            // 가족 정보에 아이가 있으면 버튼만 눌렀을 때 '누군지 모름'으로 본다: 카시트의 아이는 좌석 센서가 모르니 혼자 탄 것처럼 보여도 아이가 아플 수 있다(11/05)
            // 검토 11/07 멋대로 판단: 이번 운행 아이 탑승("아이 탔어")도 가족 정보의 아이처럼 '누군지 모름'. 말로 열었는데 누가 아픈지 빠졌으면("허리 아파")
            // '누군지 모름'(기획안: 말없이 [갑자기 아파요]만 눌렀고 혼자일 때만 운전자). 처음 연 방법은 다시 시작해도 그대로 둔다
            if (!again) { illByButton = android.os.SystemClock.elapsedRealtime() - Stage.illPressedAt < PRESS_MS; Stage.illPressedAt = 0L }
            val driverIll: Boolean? = when {
                ui0.kid || ui0.illOther == true || want != null -> false
                ui0.illOther == false -> true
                ui0.kids == true || ui0.baby -> null
                !ui0.others && illByButton -> true
                else -> null
            }
            Stage.ui.update { it.copy(driverIll = driverIll == true) }
            // 말로 아픈 사람이 바뀌는지 보려고 시작할 때의 값을 적어 둔다(tick)
            startedIllOther = ui0.illOther; startedKid = ui0.kid; startedWant = want
            // 운전자 확인은 첫 문장이 끝나기를 기다리지 않고 지금 켠다(사람이 첫 문장 중에 [다음]을 눌러 목소리가 멈춰도 확인은 살아 있게, 검토 11/05).
            // 누가 아픈지 모를 때도 세운 뒤 한 번 묻는다(11/06: 가족에 아이가 있는 부모에게서 운전자 확인이 꺼져 있었다). 대답이 없으면 한 번 더 묻고 그친다
            if (driverIll != false) {
                drvChecking = true
                drvSoft = driverIll == null
                if (car().parked) drvCheckAt = android.os.SystemClock.elapsedRealtime() + DRV_CHECK_MS
                Prend.log("· ${who()}: ${if (car().parked) "20초 뒤" else "세우면 20초 뒤"} 괜찮은지 물음")
            }
            lines = when (driverIll) {
                // "운전은 여기서 멈추세요. 안전한 곳에 차를 세우고 119를 부르세요." / 세워 둔 차면 "지금 바로 119에 전화하세요. 화면의 119 버튼을 누르면 돼요."
                true -> listOf(Say(if (drive) "e_ill_drv" else "e_ill_drv_p", 1))
                // 누가 아픈지 모름: 119 먼저 뒤에 "운전하는 분이 아프면, 운전은 멈추고 안전한 곳에 세운 뒤 119를 부르세요." 한 줄을 서 있어도 더한다(11/06: 전에는 달릴 때만이라,
                // 갓길에 세운 부모가 병원 길을 받아 직접 몰고 갈 수 있었다). 병원 경유지는 그대로 묻는다(아이가 아파 버튼만 누른 부모가 늦지 않게)
                null -> listOfNotNull(Say("e_ill_1", 1), Say("e_ill_who", 1), if (drive) Say("e_ill_call", 1) else null)
                false -> listOfNotNull(Say("e_ill_1", 1), if (drive) Say("e_ill_call", 1) else null, if (want != null) Say("e_aid_stop", 1) else null)
            }
            // 병원 걸음으로 열었으면 그 걸음에 머문 채 말하고 병원을 묻는다(1걸음으로 되돌리면 단추 이름과 달리 [다음]을 한 번 더 눌러야 했다).
            // 운전자가 아프면 "화면의 119 버튼을 누르면 돼요"가 가리키는 첫 걸음 그대로 둔다(11/08 오류 점검 E3)
            if (atHosp && driverIll != true) {
                shownStep = 2
                Stage.ui.update { it.copy(step = 2) }
                lines = lines.map { it.copy(step = 2) }
            }
            Prend.log("◆ 아플 때 안내 시작($why, ${when (driverIll) { true -> "운전자"; false -> "같이 탄 사람"; null -> "누군지 모름" }}${if (ui0.kid) ", 아이" else ""}${if (ui0.baby) ", 아이 탑승" else ""}${if (illByButton) ", 단추" else ""}${want?.let { ", 세우면 ${it.name} 카드" } ?: ""})")
            findHospitals()
        }
        requestFocus(firstAfterMs)
    }

    /** 화면을 먼저 열어 본다. 막히면(BAL_BLOCK) 배너가 [열기]를 띄운다 */
    private fun openScreen() {
        runCatching { context.startActivity(Intent(context, PrendActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Prend.log("✗ 사고 안내: 화면 열기 실패 $it") }
        main.postDelayed({ Prend.log(if (Stage.ui.value.visible) "· 사고 안내: 화면이 열림" else "· 사고 안내: 화면 열기는 막힘 → 배너로") }, 2_000)
    }

    // ── 소리: 포커스와 통화 기다리기 ──

    private fun requestFocus(firstAfterMs: Long) {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ onFocus(it) }, main)
            .build()
        focus = req
        val r = audio.requestAudioFocus(req)
        // E 첫 화면이 멈춰 둔 포커스는 안내의 포커스를 청한 뒤에 놓는다(사이에 음악이 다시 들어오지 않게)
        letGoHold("안내로 넘김")
        when (r) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> { hasFocus = true; Prend.log("· 급할 때 안내 포커스 허락: 음악 멈춤"); next(firstAfterMs) }
            // 통화가 끝나기를 기다리는 동안 화면에 그 한 줄을 둔다(에어백 뒤 블루링크 통화 동안 말이 없는 까닭, 검토 11/06)
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> { callWaiting(true); Prend.log("· 급할 때 안내 포커스 보류(통화 중): 통화가 끝나면 말한다") }
            else -> {
                Prend.log("· 급할 때 안내 포커스 거절($r) → 2초 뒤 다시")
                focus = null
                main.postDelayed({ if (active && focus == null) requestFocus(0) }, 2_000)
            }
        }
    }

    private fun onFocus(change: Int) {
        Prend.log("· 급할 때 안내 포커스 변화 $change")
        when (change) {
            // 통화가 끝났다(또는 보류가 풀렸다): 하던 문장부터 다시
            AudioManager.AUDIOFOCUS_GAIN -> { hasFocus = true; callWaiting(false); if (talking && player == null && !listening) next(1_000) }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { hasFocus = false; pause() }
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                pause()
                focus?.let { audio.abandonAudioFocusRequest(it) }
                focus = null
                if (talking) main.postDelayed({ if (active && focus == null) requestFocus(1_000) }, 1_000)
            }
        }
    }

    private fun next(delayMs: Long) {
        main.removeCallbacks(sayNow)
        main.postDelayed(sayNow, delayMs)
    }

    private val sayNow = Runnable {
        if (!active || !talking || !hasFocus || player != null || listening || heldForListen || awayHold || evacWaitAt > 0) return@Runnable
        // 사람이 화면에서 걸음을 넘겼다(틱이 아직 못 봄): 목소리가 걸음을 덮어쓰지 않고 틱에 맡긴다(응급처치는 그 걸음만 읽기, 그 밖은 멈춤)
        if (Stage.ui.value.step != shownStep) return@Runnable
        if (inCall()) {
            if (!waitedCall) { waitedCall = true; callWaiting(true); Prend.log("· 급할 때 안내: 통화 중이라 기다림") }
            next(1_000); return@Runnable
        }
        if (waitedCall) { waitedCall = false; callWaiting(false); Prend.log("· 급할 때 안내: 통화 끝, 이어서 말함") }
        // 응급처치가 먼저인 말이었으면 병원을 묻지 않는다(차를 세우고 카드부터, 카드 끝에 [가까운 병원])
        // 운전자가 아프면 병원 경유지를 묻지 않는다(화면의 둘째 걸음에서 사람이 고를 수는 있다)
        if (index >= lines.size && mode == Scene.ILLNESS && !askedHospital && Stage.ui.value.aidWant == null && !Stage.ui.value.driverIll && askHospital()) return@Runnable
        val s = lines.getOrNull(index) ?: return@Runnable finish()
        if (s.step != shownStep) { shownStep = s.step; Stage.ui.update { it.copy(step = s.step) } }
        // "SOS 버튼을 누르거나 119에 알리세요"는 말하는 순간 화면과 배너에도 빨간 띠로(목소리를 놓쳐도 보이게)
        if (s.asset == "e_sos" && !Stage.ui.value.sos) { Stage.ui.update { it.copy(sos = true) }; Prend.log("· 사고 안내: SOS 띠") }
        // 대피 문장을 말하기 시작하면 운전석 문을 지켜본다(말하는 도중에 열고 나가도 잡게, 11/06). 구조용 화면 중이면 하지 않는다
        if (mode == Scene.ACCIDENT && s.asset in EVAC && !evacOn && !rescuing) {
            evacOn = true; evacLine = s.asset; evacOpened = false; evacCloses = 0
            if (!seatSawEmpty && car().seats["ROW_1_LEFT"] == false) seatSawEmpty = true
        }
        play(s)
    }

    private fun inCall() = audio.mode in listOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_RINGTONE, AudioManager.MODE_CALL_SCREENING)

    private fun play(s: Say) {
        Prend.log("◆ 급할 때 안내 말하기: ${s.asset}")
        runCatching {
            val afd = app.prend.voice.openVoice(context, "voice/${s.asset}.wav")
            player = MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                setOnCompletionListener { release(); player = null; spoken(s) }
                // 준비는 메인 밖에서(걸음이 바뀌는 첫 프레임이 준비만큼 늦지 않게). 멈추면(release) 이 알림은 오지 않는다
                var started = false
                // 천천히 말하기면 음높이는 그대로 두고 빠르기만 늦춘다(voice/Speaker와 같은 0.9배)
                setOnPreparedListener {
                    started = true
                    val sl = slow() || slowOnce
                    slowOnce = false
                    if (sl) runCatching { it.playbackParams = it.playbackParams.setSpeed(app.prend.voice.Speaker.SLOW_RATE).setPitch(1f) }.onFailure { e -> Prend.log("✗ 급할 때 안내 빠르기 못 바꿈 $e") }
                    if (!it.isPlaying) it.start()
                }
                // 준비하다 실패하면 전처럼(동기 prepare가 던지던 때) 그 문장을 건너뛴다. 말하던 중의 오류는 전처럼 끝난 것으로(onCompletion)
                setOnErrorListener { mp, what, _ ->
                    if (started) return@setOnErrorListener false
                    Prend.log("✗ 급할 때 안내 틀기 실패 $what"); mp.release(); if (player === mp) player = null; index++; next(GAP)
                    true
                }
                prepareAsync()
            }
        }.onFailure { Prend.log("✗ 급할 때 안내 틀기 실패 $it"); player?.release(); player = null; index++; next(GAP) }
    }

    private fun spoken(s: Say) {
        if (s.asset == SZ_5MIN) { pending5min = false; said5minFor = Stage.ui.value.aidSince }
        // 대피 문장이 끝난 때부터 시계 하나(고속도로 30초·그 밖 20초, evacWaitTick). 한 번 더 말한 대피 문장은 시계를 다시 걸지 않는다
        if (mode == Scene.ACCIDENT && s.asset in EVAC && evacAt == 0L && evacOn) evacAt = android.os.SystemClock.elapsedRealtime()
        // 그다음 119·견인 한 줄까지 말했으면 운전석 문을 기다린다. 구조용 화면 중이면 기다리지 않는다
        if (mode == Scene.ACCIDENT && s.asset in WAIT_AFTER && evacOn && evacAt > 0 && !rescuing) {
            evacWaitAt = android.os.SystemClock.elapsedRealtime()
            Prend.log("· 대피 문장과 그다음 한 줄 뒤: 문을 기다림(대피 문장부터 ${if (evacLine == "e_hw_2") 30 else 20}초까지)")
            // 말하는 사이 이미 나갔으면 바로 멈춘다
            evacWaitTick()
            return
        }
        when (s.asset) {
            "e_ok", "e_ill_er", "e_ill_moon" -> { if (s.asset == "e_ok" && fireOkPending) fireOkPending = false; listen(s.asset) }
            else -> { index++; next(if (mode == Scene.AID) AID_GAP else GAP) }
        }
    }

    /**
     * 물은 뒤 대답을 듣는다(5초, 넉넉히 기다리기면 8초). 대답은 물은 말에 따라 가른다(아플 때 "괜찮으세요?"는 운전자 확인, 병원 물음은 병원).
     * "괜찮으세요?"를 듣는 동안은 화면 걸음 위에 물음 띠와 대답 단추를 둔다(Stage.okAnswer가 그 말을 들은 것처럼 넘긴다, 11/06)
     */
    private fun listen(asked: String) {
        listening = true
        val id = ++listenId
        val m = mode
        Stage.endListen()
        okBand(asked == "e_ok")
        Stage.call(answer = { heard ->
            okBand(false)
            // 그사이 다른 카드로 바뀌었거나 멈췄으면(늦게 온 대답) 버린다
            if (id == listenId && m == mode && active) {
                listening = false
                // 누가 아픈지 모를 때는 운전자 확인과 병원 물음이 함께 있어 물은 말로 가른다(11/06)
                when {
                    m == Scene.ACCIDENT -> answered(heard)
                    asked == "e_ok" -> answeredDriver(heard)
                    else -> answeredHospital(heard)
                }
            } else Prend.log("· 급할 때 안내: 늦게 온 대답 버림")
        }, windowMs = Stage.answerMs)
    }

    /** 화면의 "괜찮으세요?" 물음 띠(바뀔 때만 화면 상태를 고친다) */
    private fun okBand(on: Boolean) { if (Stage.ui.value.okAsk != on) Stage.ui.update { it.copy(okAsk = on) } }

    /** 화면·위 막대·배너의 "통화가 끝나면 이어서 안내해요"(바뀔 때만) */
    private fun callWaiting(on: Boolean) { if (Stage.ui.value.callWait != on) Stage.ui.update { it.copy(callWait = on) } }

    /** 사람이 [프렌드]를 불렀다: 목소리를 멈추고(귀가 안내 문장을 듣지 않게, 말이 겹치지 않게), 대답 창이 닫히면 끊긴 문장부터 잇는다 */
    fun holdForListen() {
        if (!active || !talking || listening) return
        main.removeCallbacks(sayNow)
        player?.let { it.release(); player = null; Prend.log("· 급할 때 안내: [프렌드]를 불러 멈춤(${lines.getOrNull(index)?.asset})") }
        heldForListen = true
    }

    // ── 사고 ──

    private fun answered(text: String) {
        if (!active || !talking) return
        // 사람이 실제로 말했을 때만 쓴 안내로 센다(검토 11/07 멋대로 판단: 빈 채로 닫힌 대답 창도 '대답함'으로 세어, 잘못 눌러 연 화면의 사고 메모가 남았다)
        if (text.isNotBlank()) memoKept = true
        val s0 = text.replace(" ", "")
        // "뭐라고?"면 한 번만 천천히 다시 묻는다(11/05: 전에는 급할 때 화면이 다시 말하기를 건너뛰어 '뭐라고'가 괜찮음으로 읽혔다).
        // 놀라서 낸 "네?"·"어"도 같다(11/07)
        if (s0.isNotBlank() && !repeatedOk && (Stage.asksAgain(text) || Answer.unsure(text))) {
            repeatedOk = true
            Prend.log("· 사고 안내: ${if (Answer.unsure(text)) "뜻이 분명하지 않은 한마디" else "다시 말해 달라고 함"} → 괜찮으세요를 천천히 한 번 더")
            lines = listOf(Say("e_ok", shownStep)); index = 0; slowOnce = true
            next(GAP)
            return
        }
        // 두 번째도 "네?"·"어" 한마디면 대답이 없던 것으로 본다(원칙 2, 운전자 확인 answeredDriver와 같게). 에어백 뒤면 구조 요청 화면으로,
        // 그 밖은 전처럼 SOS와 걸음 안내(11/08 오류 점검 B11: 전에는 '모름 → 다친 것으로'가 되어 에어백 뒤 멍한 운전자에게 구조 요청 화면이 뜨지 않았다)
        val s = if (s0.isNotBlank() && Answer.unsure(text)) { Prend.log("· 사고 안내: 또 뜻이 분명하지 않은 한마디 → 대답 없음으로"); "" } else s0
        // 괜찮다는 말일 때만 괜찮음으로 본다. 모르는 말("어지러워", "잘 모르겠어")은 다친 것으로 보고 SOS부터(11/05: 전에는 모르는 말을 괜찮음으로 봐 일반 도로에서 '차를 길가로 옮기세요'까지 갔다)
        val hurtSaid = hurtIn(s)
        // "안 괜찮아요"·"괜찮지 않아"·"괜찮은지 모르겠어"는 괜찮다는 말이 아니다(검토 11/05)
        // "119는 안 불러도 돼요"·"119 필요 없어"도 괜찮다는 말이다(검토 11/07 멋대로 판단)
        val calm = s.isNotBlank() && !hurtSaid && NOT_OK.none { it in s } && (OK.any { it in s } || NO_119.containsMatchIn(s) || Answer.short(text) && !Answer.unsure(text))
        val hurt = !calm
        // 괜찮다·다쳤다로 분명한 대답이면 짐작 줄을 거둔다(그 안내가 맞다는 뜻). 알아듣지 못한 대답이면 [아니에요]를 남겨 둔다(검토 11/07 멋대로 판단)
        if (calm || hurtSaid) Stage.clearGuess()
        // 에어백 뒤 대답이 없는데 운전석이 비었다(자리 값을 믿을 수 있을 때): 정신을 잃은 것이 아니라 내린 것이다(검토 11/07 멋대로 판단)
        val gotOut = s.isBlank() && byAirbag && seatTrusted() && car().seats["ROW_1_LEFT"] == false
        // 연기·불 안내에 더한 "괜찮으세요?"의 대답(11/06): 대피 안내는 그대로. 대답이 없고 에어백이면 구조 화면, 다쳤거나 대답이 없으면 SOS.
        // 시킨 대로 내려 운전석이 비었으면 구조 화면 대신 SOS와 남은 대피 안내(검토 11/07)
        if (Stage.ui.value.fire) {
            Prend.log("· 연기·불 안내 중 대답(말 ${text.length}자) → ${if (s.isBlank()) "없음" else if (calm) "괜찮음" else "다친 것으로"}${if (gotOut) "(운전석이 빔, 내린 것으로)" else ""}")
            if (s.isBlank() && byAirbag && !gotOut) {
                rescuing = true
                helpAt = android.os.SystemClock.elapsedRealtime()
                Stage.ui.update { it.copy(rescue = true) }
                Prend.log("◆ 구조 요청 화면(연기·불 안내 중 에어백, 대답 없음)")
                lines = listOf(Say("e_sos", 1), help())
            } else {
                // 물은 뒤의 남은 줄(그사이 세워 "시동을 끄고 모두 내려"로 바뀌었으면 그 줄부터)
                val rest = if (lines.getOrNull(index)?.asset == "e_ok") lines.drop(index + 1) else lines.drop(index)
                lines = (if (hurt) listOf(Say("e_sos", shownStep)) else emptyList()) + rest
            }
            index = 0
            next(GAP)
            return
        }
        // "연기 나요"·"불났어": 연기·불 안내로(tick이 목소리도 그 갈래로 다시 시작한다). "연기는 안 나요"·"화재는 아니에요"는 불이 아니다(11/06)
        if (FIRE.any { it in Stage.dropFireDenial(s) }) {
            // 다쳤다는 말이 함께 있으면 연기·불 안내에도 SOS 띠를(다시 시작한 뒤 tick이 켠다)
            fireSos = hurtSaid
            Prend.log("· 사고 안내 대답에 연기·불 → 연기·불 안내로${if (hurtSaid) "(다침도)" else ""}")
            Stage.fire(); return
        }
        Prend.log("· 사고 안내 대답(말 ${text.length}자) → ${if (s.isBlank()) "없음" else if (calm) "괜찮음" else if (hurtSaid) "다침" else "모름 → 다친 것으로"}")
        // 시작할 때 위치를 몰랐으면 지금 다시 본다(그사이 내비가 이어졌을 수 있다)
        if (!Stage.ui.value.highway && onHighway()) { Stage.ui.update { it.copy(highway = true) }; Prend.log("· 사고 안내: 그사이 내비 자리가 들어와 고속도로로 바꿈") }
        val ui = Stage.ui.value
        // 에어백 뒤 대답이 없는데 운전석이 비었다: 구조 요청 화면("운전자가 대답하지 않아요") 대신 SOS 띠만 두고 다시 앉기를 기다린다.
        // 다시 앉으면 onCar가 "괜찮으세요?"를 다시 묻는다. 자리 값을 믿지 못하는 차는 아래 구조 요청 화면 그대로(그쪽이 안전하다)
        if (gotOut) {
            okAway = true
            Stage.ui.update { it.copy(sos = true) }
            Prend.log("· 사고 안내: 에어백 뒤 대답 없음, 운전석이 빔 → 내린 것으로 보고 다시 앉기를 기다림(SOS 띠)")
            talking = false
            releaseFocus()
            return
        }
        // 에어백 뒤 대답이 없다: 구조하러 온 사람이 읽을 화면으로(사람이 [괜찮아요]를 누르면 걸음 안내로 돌아간다)
        if (s.isBlank() && byAirbag) {
            rescuing = true
            helpAt = android.os.SystemClock.elapsedRealtime()
            Stage.ui.update { it.copy(rescue = true) }
            Prend.log("◆ 구조 요청 화면(대답 없음${if (ui.baby) ", 아이 탑승" else if (ui.kids == true) ", 가족에 아이" else ""})")
            lines = listOf(Say("e_sos", 1), help())
            index = 0
            next(GAP)
            return
        }
        // 일반 도로에서 괜찮다고 했고 에어백이 아니면 가벼운 사고 순서. 바로 전 2분 안에 시속 80을 넘겨 달렸으면 쓰지 않는다
        // (위치를 몰라 고속도로를 놓쳤거나 민자·도시고속도로일 수 있다: 차 옆에서 사진을 찍고 차를 옮기는 걸음이 위험하다, 검토 11/06)
        val fast = fastAt > 0 && android.os.SystemClock.elapsedRealtime() - fastAt < FAST_MS
        val minor = !hurt && !ui.highway && !byAirbag && !fast
        if (!hurt && !ui.highway && !byAirbag && fast) Prend.log("· 사고 안내: 바로 전에 시속 ${FAST_KMH.toInt()}을 넘겨 달려 가벼운 사고 순서를 쓰지 않음")
        if (minor) Stage.ui.update { it.copy(minor = true) }
        lines = buildList {
            if (hurt) add(Say("e_sos", 1))
            addAll(steps(ui.baby, minor))
        }
        index = 0
        next(GAP)
    }

    /**
     * 사고 걸음 1·2의 [안 다쳤어요](SOS 띠가 있을 때): 대답하지 못했거나(5초 안에 말이 잡히지 않음) 잘못 알아들어 SOS로 간 안내를 늦게 바로잡는다
     * (검토 11/06, 카드의 늦은 대답과 같은 생각). SOS 띠를 거두고, 괜찮다고 답했을 때처럼 가벼운 사고 순서를 쓸 수 있으면
     * (일반 도로·에어백 아님·바로 전에 빨리 달리지 않음) 그 순서로 처음부터 다시 말한다. 쓸 수 없으면 띠만 거두고 하던 순서를 잇는다(말하던 SOS 문장은 건너뛴다)
     */
    private fun notHurt() {
        val ui0 = Stage.ui.value
        if (!active || mode != Scene.ACCIDENT || ui0.fire || ui0.breakdown || rescuing || !ui0.sos) return
        memoKept = true
        Stage.clearGuess()
        if (!ui0.highway && onHighway()) { Stage.ui.update { it.copy(highway = true) }; Prend.log("· 사고 안내: 그사이 내비 자리가 들어와 고속도로로 바꿈") }
        val fast = fastAt > 0 && android.os.SystemClock.elapsedRealtime() - fastAt < FAST_MS
        val minor = !Stage.ui.value.highway && !byAirbag && !fast
        // 에어백 뒤 내린 운전자를 기다리던 중(okAway)이면 화면을 보고 있으니 다시 묻지 않고 걸음 안내를 잇는다(검토 11/07 멋대로 판단)
        if (okAway) {
            okAway = false
            Prend.log("· 사고 안내: 에어백 뒤 내려서 기다리던 중 [안 다쳤어요] → SOS 띠를 거두고 걸음 안내")
            Stage.ui.update { it.copy(sos = false) }
            resumeSteps(sos = false)
            return
        }
        Prend.log("· 사고 안내: [안 다쳤어요] 늦은 대답 → ${if (minor) "가벼운 사고 순서로 처음부터" else "SOS 띠만 거둠(${if (byAirbag) "에어백" else if (fast) "바로 전에 빨리 달림" else "고속도로"})"}")
        Stage.ui.update { it.copy(sos = false) }
        if (!minor) {
            // 말하던 "SOS 버튼을 누르거나 119에 알리세요"는 그만두고 다음 줄로
            if (talking && lines.getOrNull(index)?.asset == "e_sos") { player?.let { it.release(); player = null }; index++; next(GAP) }
            return
        }
        // 아이 한 줄("뒷좌석 아이도 꼭 함께 챙기세요")을 이미 말했으면 다시 하지 않는다
        val kidLines = setOf("e_baby", "e_kids")
        val kidSaid = lines.take(index).any { it.asset in kidLines } || (player != null && lines.getOrNull(index)?.asset in kidLines)
        player?.let { it.release(); player = null }
        if (listening) { listening = false; listenId++; Stage.endListen(); okBand(false) }
        // 일반 도로 순서의 대피 문장을 기다리던 중이면 거둔다(가벼운 사고 순서에는 대피 문장이 없다)
        evacOn = false; evacLine = null; evacAt = 0L; evacDoor = false; evacAgain = false
        evacWaitAt = 0L; evacOpened = false; evacCloses = 0; awayHold = false; awayNeed = 0
        Stage.ui.update { it.copy(minor = true, step = 1) }
        shownStep = 1
        talking = true
        lines = steps(Stage.ui.value.baby, minor = true).filterNot { kidSaid && it.asset in kidLines }
        index = 0
        if (focus == null) requestFocus(300) else next(300)
    }

    /**
     * 가벼운 사고 순서의 [다쳤어요](검토 11/07): 괜찮다고 답했지만 다친 것을 늦게 알았다(전에는 이 순서에 [119 전화]가 없고 설명 줄의 글뿐이었다).
     * SOS 띠와 [119 전화]를 띄우고, 다친 사람을 옮기지 않는 일반 도로 순서로 처음부터 다시 말한다
     */
    private fun hurtLate() {
        val ui0 = Stage.ui.value
        if (!active || mode != Scene.ACCIDENT || !ui0.minor || ui0.fire || ui0.breakdown || rescuing) return
        memoKept = true
        Stage.clearGuess()
        Prend.log("· 사고 안내: 가벼운 사고 순서에서 [다쳤어요] → SOS와 일반 도로 순서로 처음부터")
        val kidLines = setOf("e_baby", "e_kids")
        val kidSaid = lines.take(index).any { it.asset in kidLines } || (player != null && lines.getOrNull(index)?.asset in kidLines)
        player?.let { it.release(); player = null }
        if (listening) { listening = false; listenId++; Stage.endListen(); okBand(false) }
        evacOn = false; evacLine = null; evacAt = 0L; evacDoor = false; evacAgain = false
        evacWaitAt = 0L; evacOpened = false; evacCloses = 0; awayHold = false; awayNeed = 0
        // SOS 띠와 [119 전화]는 누른 즉시 켠다. e_sos를 말할 때까지 미루면 보험사와 통화하는 동안 통화가 끝날 때까지 뜨지 않았다(11/08 오류 점검 B4)
        Stage.ui.update { it.copy(minor = false, step = 1, sos = true) }
        shownStep = 1
        talking = true
        lines = listOf(Say("e_sos", 1)) + steps(Stage.ui.value.baby, minor = false).filterNot { kidSaid && it.asset in kidLines }
        index = 0
        if (focus == null) requestFocus(300) else next(300)
    }

    /**
     * 사고 안내 중 [프렌드]로 한 "다리가 아파요"·"다쳤어요"(검토 11/07 멋대로 판단 원칙 5: 말 한마디로 다른 안내로 갈아타지 않는다).
     * 아플 때 안내로 가는 대신 [다쳤어요]를 누른 것처럼 받는다. sure(아플 때 낱말로 고른 말)면 그대로, 아니면 다친 말이 있을 때만. 받았으면 true
     * - 가벼운 사고 순서: hurtLate(SOS 띠와 다친 사람을 옮기지 않는 일반 도로 순서로 처음부터)
     * - 아직 "괜찮으세요?"에 대답하기 전: 다쳤다고 답한 것처럼 SOS와 다친 사람 순서로
     * - 에어백 뒤 내려서 기다리던 중: SOS 띠를 두고 걸음 안내를 잇는다
     * - 그 밖(고속도로·일반 도로 순서, 연기·불 안내): SOS 띠를 켜고 "SOS 버튼을 누르거나 119에 알리세요"를 다음에 말한다. 이미 띠가 있으면 [119 전화]를 알린다
     * 고장 안내는 받지 않는다(119 걸음이 없어 아플 때 안내로 가는 편이 낫다). 구조 요청 화면은 그대로 둔다
     */
    private fun hurtByWord(s: String, sure: Boolean): Boolean {
        val ui0 = Stage.ui.value
        if (!active || mode != Scene.ACCIDENT || ui0.scene != Scene.ACCIDENT || ui0.breakdown) return false
        // 낱말로 고른 말이 아니면 몸을 다쳤다는 말만 본다("견인 좀 도와줘"·"119에 뭐라고 해?"의 '도와'·'119'는 세지 않는다)
        if (!sure && !hurtIn(s, told = true)) return false
        memoKept = true
        Stage.clearGuess()
        when {
            rescuing -> Prend.log("· 사고 안내 중 다쳤다는 말 → 구조 요청 화면 그대로")
            ui0.minor && !ui0.fire -> hurtLate()
            okAway -> {
                okAway = false
                Prend.log("· 사고 안내: 에어백 뒤 내려서 기다리던 중 다쳤다는 말 → SOS 띠와 걸음 안내")
                Stage.ui.update { it.copy(sos = true) }
                resumeSteps(sos = true)
            }
            !ui0.fire && lines.getOrNull(index)?.asset == "e_ok" -> {
                Prend.log("· 사고 안내: 괜찮은지 대답하기 전에 다쳤다는 말 → SOS와 다친 사람 순서로")
                player?.let { it.release(); player = null }
                Stage.ui.update { it.copy(sos = true, minor = false) }
                talking = true
                shownStep = Stage.ui.value.step
                lines = listOf(Say("e_sos", 1)) + steps(ui0.baby, minor = false)
                index = 0
                if (focus == null) requestFocus(GAP) else next(GAP)
            }
            !ui0.sos -> sosNext()
            else -> { Prend.log("· 사고 안내 중 다쳤다는 말 → 이미 SOS 띠, [119 전화]를 알림"); Stage.toast("화면의 119 전화를 눌러 주세요", 3_500) }
        }
        return true
    }

    /** SOS 띠를 켜고 "SOS 버튼을 누르거나 119에 알리세요"를 다음에 말한다(하던 순서는 그대로 잇는다) */
    private fun sosNext() {
        Stage.ui.update { it.copy(sos = true) }
        Prend.log("· 사고 안내 중 다쳤다는 말 → SOS 띠, 다음에 SOS 한 줄")
        if (!talking) { shownStep = Stage.ui.value.step; sayOnly(Say("e_sos", shownStep)); return }
        // 말하는 중이거나 끊긴 문장(다시 말할 줄)·대피 뒤 기다리는 줄을 가리키면 그 뒤에, 걸음 사이면 바로 다음에
        val at = if (player != null || heldForListen || evacWaitAt > 0 || awayHold || listening) index + 1 else index
        val step = lines.getOrNull(index)?.step ?: shownStep
        lines = lines.toMutableList().apply { add(at.coerceIn(0, size), Say("e_sos", step)) }
    }

    /** 에어백 뒤 내려서 기다리던 안내를 잇는다: 지금 걸음에서 사고 순서를 처음부터(sos면 "SOS 버튼을…"부터) */
    private fun resumeSteps(sos: Boolean) {
        player?.let { it.release(); player = null }
        talking = true
        shownStep = Stage.ui.value.step
        lines = (if (sos) listOf(Say("e_sos", shownStep)) else emptyList()) + steps(Stage.ui.value.baby)
        index = 0
        if (focus == null) requestFocus(300) else next(300)
    }

    /**
     * 짐작 줄의 [아니에요](검토 11/07 멋대로 판단): AI가 '사고'·'아플 때'로 잘못 알아들어 연 안내. 목소리를 멈추고, 사고 메모는 남기지 않는다
     * (앞 메모가 있었으면 그것으로 되돌린다). 에어백으로 연 안내는 짐작이 아니라 메모를 둔다. 화면은 Stage.guessWrong이 첫 화면으로 돌린다
     */
    private fun guessWrong() {
        if (!active) return
        if (mode == Scene.ACCIDENT && memoAt > 0 && !byAirbag) {
            memoPrev?.let { after.restore(it) } ?: after.done()
            memoAt = 0L
            Prend.log("· 사고 메모 취소(짐작 거둠)")
        }
        end("짐작 거둠")
    }

    /** 다쳤다는 말인가. "안 다쳤어"·"안 아파"·"다친 데 없어"·"119는 안 불러도 돼요"처럼 부정한 말은 지우고 본다 */
    private fun hurtIn(s: String, told: Boolean = false): Boolean {
        val t = NOT_HURT.fold(NO_119.replace(s, "")) { acc, w -> acc.replace(w, "") }
        return (if (told) HURT_TOLD else HURT).any { it in t }
    }

    /** 걸음 안내의 목소리. 아이 탑승이면 "뒷좌석 아이도 꼭 함께 챙기세요", 가족 정보에 아이가 있으면 "뒷좌석에 아이가 있는지 꼭 살펴보세요"가 먼저 */
    private fun steps(baby: Boolean, minor: Boolean = Stage.ui.value.minor): List<Say> = buildList {
        if (baby) add(Say("e_baby", 1)) else if (Stage.ui.value.kids == true) add(Say("e_kids", 1))
        addAll(if (Stage.ui.value.highway) HIGHWAY else if (minor) MINOR else ROAD)
    }

    /** 사고 메모의 날씨 한 줄: 밤·낮, 와이퍼가 돌면 비, 바깥 기온 */
    private fun weather(c: CarState): String = listOfNotNull(
        if (c.night) "밤" else "낮",
        if (c.wiperOn) "비(와이퍼 켜짐)" else null,
        c.outsideC?.let { "${it.roundToInt()}°C" },
    ).joinToString(" · ")

    // ── 응급처치 카드 ──

    /** 달리기 시작하면 목소리를 멈추고(화면은 가린다), 사람이 걸음을 넘기면 그 걸음만 읽고, 경련 5분이 지나면 한 번 알린다 */
    private fun aidTick(ui: StageUi) {
        if (!ui.parked) {
            if (talking) { Prend.log("· 응급처치 카드: 달리는 중 → 목소리 멈춤"); quiet(); pausedByDrive = true }
            return
        }
        // 다시 세웠다: 끊긴 문장부터 남은 걸음을 잇는다(화면을 못 보는 사람이 안내가 끝난 줄 알지 않게)
        if (pausedByDrive) {
            pausedByDrive = false
            if (index < lines.size) {
                Prend.log("· 응급처치 카드: 다시 세움 → 끊긴 문장부터(${lines[index].asset})")
                talking = true
                requestFocus(500)
                return
            }
        }
        if (ui.aidSince != aidSinceSeen) { aidSinceSeen = ui.aidSince; said5min = ui.aid == Aid.SEIZURE && ui.aidSince == said5minFor; pending5min = false }
        if (ui.aid == Aid.SEIZURE && !said5min && System.currentTimeMillis() - ui.aidSince >= FIVE_MIN) {
            said5min = true
            pending5min = true
            Prend.log("◆ 응급처치 카드: 경련 5분 지남 → 알림")
        }
        if (ui.step != shownStep) {
            Prend.log("· 응급처치 카드: 사람이 ${ui.step}걸음으로 → 그 걸음만 읽음")
            shownStep = ui.step
            // 아직 못 한 5분 알림이 있으면 그 걸음 앞에
            sayOnly(Say("${ui.aid.voice}_${ui.step}", ui.step), alert = pending5min && ui.aid == Aid.SEIZURE)
            return
        }
        // 5분 알림을 아직 못 했으면(사람이 넘겨 지워졌거나 달려서 멈춤) 줄에 다시 넣는다: 읽는 중이면 그 문장 뒤, 걸음 사이면 바로 다음, 쉬고 있으면 지금
        if (pending5min && ui.aid == Aid.SEIZURE && !heldForListen && lines.drop(index).none { it.asset == SZ_5MIN }) {
            when {
                talking && player != null -> lines = lines.toMutableList().apply { add(index + 1, Say(SZ_5MIN, lines[index].step)) }
                talking -> lines = lines.toMutableList().apply { add(index.coerceAtMost(size), Say(SZ_5MIN, ui.step)) }
                else -> sayOnly(Say(SZ_5MIN, ui.step))
            }
        }
    }

    /** 하던 말을 멈추고 이 한 문장만 말한다 */
    private fun sayOnly(s: Say, alert: Boolean = false) {
        player?.let { it.release(); player = null }
        talking = true
        lines = listOfNotNull(if (alert) Say(SZ_5MIN, s.step) else null, s)
        index = 0
        if (focus == null) requestFocus(300) else next(300)
    }

    // ── 아플 때: 병원 ──

    /**
     * 중계 서버로 가까운 응급실과 달빛어린이병원을 찾는다(위치는 이 요청에만 쓴다).
     * 인터넷이 안 되거나 서버가 빈 답을 주면 앱에 넣어 둔 목록(Hospitals)에서 직선거리로 고른다(감사 F40).
     * 위치 보내기를 끈 사람(ride/GeoShare, 사용자 결정 11/06)은 서버에 묻지 않고 처음부터 앱 안 목록으로(위치는 차 안에서만 쓴다)
     */
    private fun findHospitals() {
        val w = navi?.last
        // 위치를 몰라 찾지 못했으면 내비 자리가 들어올 때 tick이 한 번 더 찾는다(11/08 오류 점검 B2)
        hospNoPlace = w == null
        if (w == null) { Stage.ui.update { it.copy(hosp = Hosp(error = "지금 위치를 아직 몰라요")) }; return }
        Stage.ui.update { it.copy(hosp = Hosp(loading = true)) }
        val send = GeoShare.on
        scope.launch {
            val j = if (send) Relay.get("/hospitals", mapOf("lat" to "%.5f".format(Locale.US, w.lat), "lon" to "%.5f".format(Locale.US, w.lon)), waitMs = 5_000) else null
            val ers = list(j?.optJSONArray("er"))
            val moons = list(j?.optJSONArray("moon"))
            // 응급실: 남은 병상이 있는 가장 가까운 곳(없으면 가장 가까운 곳). 달빛어린이병원: 지금 진료 중인 가장 가까운 곳(50km 안)
            val er = ers.firstOrNull { (it.beds ?: 0) > 0 } ?: ers.firstOrNull()
            val moon = moons.firstOrNull { openNow(it) && it.km <= 50 }
            if (j != null && (er != null || moon != null)) {
                Prend.log("· 병원 찾음: 응급실 ${ers.size}곳(남은 병상 있는 곳 ${ers.count { (it.beds ?: 0) > 0 }}), 달빛어린이병원 ${moons.size}곳(지금 진료 ${if (moon != null) "있음" else "없음"})")
                Stage.ui.update { it.copy(hosp = Hosp(er = er, moon = moon)) }
                return@launch
            }
            // 목록 읽기(약 120KB)는 처음 한 번만, 메인 밖에서
            val book = withContext(Dispatchers.IO) { runCatching { hospitals }.onFailure { Prend.log("✗ 앱 안 병원 목록을 읽지 못함 $it") }.getOrNull() }
            val offEr = book?.er(w.lat, w.lon)
            val offMoon = book?.moon(w.lat, w.lon)
            Prend.log("· 병원: ${if (!send) "위치 보내기 꺼짐" else if (j == null) "중계 서버 답 없음" else "중계 서버 결과 없음"} → 앱 안 목록(${book?.asOf ?: "없음"}) 응급실 ${if (offEr != null) "있음" else "없음"}, 진료 중인 달빛어린이병원 ${if (offMoon != null) "있음" else "없음"}")
            // "인터넷이 안 돼서"는 서버에 물었는데 답이 없을 때만(위치 보내기를 꺼서 묻지 않았으면 그 말 없이 목록으로, e_ill_book)
            val noNet = send && j == null
            val none = if (noNet) "인터넷이 안 돼 병원을 찾지 못했어요" else "가까운 병원을 찾지 못했어요"
            Stage.ui.update { it.copy(hosp = Hosp(er = offEr, moon = offMoon, asOf = book?.asOf, noNet = noNet, error = if (offEr == null && offMoon == null) none else null)) }
        }
    }

    /** 인터넷이 없을 때의 병원 목록(처음 쓸 때 읽는다) */
    private val hospitals by lazy { Hospitals(context) }

    /** 시험(hosp_book): 지금 위치에서 앱 안 목록이 고를 곳. 이름·위치는 남기지 않고 거리와 분류만 */
    fun bookDump() {
        val w = navi?.last ?: return Prend.log("· 시험: 앱 안 병원 목록 · 위치 모름")
        scope.launch {
            val book = withContext(Dispatchers.IO) { runCatching { hospitals }.getOrNull() } ?: return@launch Prend.log("· 시험: 앱 안 병원 목록 없음")
            val er = book.er(w.lat, w.lon)
            val moon = book.moon(w.lat, w.lon)
            Prend.log("· 시험: 앱 안 병원 목록 ${book.count}곳(${book.asOf} 기준) · 응급실 ${er?.let { "%.1fkm ${it.kind}".format(Locale.US, it.km) } ?: "100km 안에 없음"}" +
                " · 진료 중인 달빛어린이병원 ${moon?.let { "%.1fkm ${it.open}~${it.close}".format(Locale.US, it.km) } ?: "50km 안에 없음"}")
        }
    }

    /** 중계 서버의 병원 목록. 거리·좌표가 빠졌거나 숫자가 아닌 곳은 버린다(거리가 NaN이면 병원 걸음의 거리 글자를 그리다 앱이 꺼지고, 좌표가 NaN이면 경유지가 틀린다, 11/08 오류 점검 B10) */
    private fun list(a: JSONArray?): List<Hospital> = if (a == null) emptyList() else (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
        Hospital(
            o.optString("name"), o.optString("address"), o.optString("tel").ifBlank { null }, o.optDouble("lat"), o.optDouble("lon"), o.optDouble("km"),
            beds = if (o.isNull("beds")) null else o.optInt("beds"), bedsAt = o.optString("bedsAt").ifBlank { null },
            open = o.optString("open").ifBlank { null }, close = o.optString("close").ifBlank { null },
        )
    }.filter { it.km.isFinite() && it.lat.isFinite() && it.lon.isFinite() }

    /** 오늘 진료 시간(HHmm) 안인가. 끝이 시작보다 이르면 밤을 넘기는 진료 */
    private fun openNow(h: Hospital): Boolean {
        val o = h.open?.toIntOrNull() ?: return false
        val c = h.close?.toIntOrNull() ?: return false
        val now = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 100 + it.get(Calendar.MINUTE) }
        return if (o <= c) now in o until c else now >= o || now < c
    }

    /** "숨쉬기나 의식이…" 다음: 병원을 찾았으면 경유지로 넣을지 묻는다. 아직 찾는 중이면 조금 기다린다(최대 8초) */
    private fun askHospital(): Boolean {
        val h = Stage.ui.value.hosp
        if (h.loading && waitHosp++ < 16) { next(500); return true }
        askedHospital = true
        val ui = Stage.ui.value
        val asset = when {
            h.moonFirst(ui.kid) -> "e_ill_moon"
            h.er != null -> "e_ill_er"
            h.moon != null -> "e_ill_moon"
            // 아직 병원이 없다(위치 모름·찾는 중): 나중에 찾으면 tick이 한 번 묻는다(11/08 오류 점검 B2)
            else -> { hospMissed = true; return false }
        }
        askedMoon = asset == "e_ill_moon"
        // 앱 안 목록에서 골랐으면 묻기 전에 "가기 전에 전화로 확인해 보세요"(대답 듣기는 물음 뒤에 열린다).
        // "인터넷이 안 돼서"는 서버가 답하지 않았을 때만(e_ill_offline), 서버 결과가 비어 목록으로 갔으면 그 말 없이(e_ill_book)
        val book = if (h.asOf == null) null else Say(if (h.noNet) "e_ill_offline" else "e_ill_book", 2)
        lines = lines + listOfNotNull(book, Say(asset, 2))
        next(GAP)
        return true
    }

    /**
     * 병원 물음("가까운 응급실/달빛어린이병원을 경유지로 넣을까요?")의 대답(검토 11/07 멋대로 판단: "응급실로 가 줘"·"빨리 가 주세요"를 아니오로 보고 말없이 멈췄다).
     * - 되물은 "네?"·"뭐라고?"는 같은 물음을 한 번만 천천히 다시. 두 번째도 그러면 대답이 없던 것으로
     * - 다른 병원을 말하면 그 병원을("아니, 응급실로"·"어린이병원으로 가 줘"), 물은 병원을 말하면 예로("응급실로")
     * - 분명히 마다하지 않았는데 알아듣지 못했으면 "화면에서 병원을 눌러 주세요"를 띄운다(병원 목록은 화면에 그대로)
     */
    private fun answeredHospital(text: String) {
        if (!active || !talking) return
        val s = text.replace(" ", "")
        val unsure = s.isNotBlank() && (Stage.asksAgain(text) || Answer.unsure(text))
        val asked = if (askedMoon) "moon" else "er"
        if (unsure && !hospAgain) {
            hospAgain = true
            Prend.log("· 아플 때 병원 물음: 뜻이 분명하지 않은 대답 → 천천히 한 번 더")
            lines = listOf(Say(if (askedMoon) "e_ill_moon" else "e_ill_er", 2)); index = 0; slowOnce = true
            next(GAP)
            return
        }
        val named = if (unsure) null else namedHospital(s)
        if (named != null) Stage.clearGuess()
        if (named != null && named != asked) {
            val h = Stage.ui.value.hosp
            val found = (if (named == "moon") h.moon else h.er) != null
            Prend.log("· 아플 때 대답(말 ${text.length}자) → 다른 병원(${if (named == "moon") "달빛어린이병원" else "응급실"}${if (found) "" else ", 찾지 못함"})")
            if (found) { chooseHospital(named); return }
            talking = false; releaseFocus()
            Stage.toast(if (named == "moon") "진료 중인 달빛어린이병원을 찾지 못했어요 · 화면에서 병원을 눌러 주세요" else "가까운 응급실을 찾지 못했어요 · 화면에서 병원을 눌러 주세요", 4_000)
            return
        }
        // "필요 없어"의 '어'를 예로 읽지 않는다(ride/Answer: 거절 먼저, 한두 글자 예는 대답 전체·첫 낱말일 때만).
        // "달빛 말고 응급실로 가 줘"는 '말고' 뒤만 본다('말고'를 거절로 읽지 않게)
        val ans = if (unsure) null else Answer.of(s.lastIndexOf("말고").takeIf { it >= 0 }?.let { s.substring(it + 2) } ?: text, YES, NO)
        val yes = ans == true || named == asked && ans == null
        Prend.log("· 아플 때 대답(말 ${text.length}자) → ${if (yes) "예" else if (s.isBlank() || unsure) "없음" else if (ans == false) "아니오" else "모름"}")
        if (yes) { Stage.clearGuess(); chooseHospital(asked); return }
        talking = false; releaseFocus() // 화면의 버튼에 맡긴다
        if (ans != false) Stage.toast("화면에서 병원을 눌러 주세요", 4_000)
    }

    /**
     * 대답에 나온 병원: 응급실이면 "er", 달빛어린이병원·어린이병원이면 "moon", 모르면 null(빈칸 지운 말).
     * "○○ 말고 △△로"는 '말고' 뒤의 것만 본다("응급실 말고"처럼 앞에만 있으면 그 병원이 아니라는 말이라 고르지 않는다)
     */
    private fun namedHospital(s: String): String? {
        val m = s.lastIndexOf("말고")
        val t = if (m >= 0) s.substring(m + 2) else s
        val er = "응급실" in t
        val moon = MOON_WORDS.any { it in t }
        return when {
            er && !moon -> "er"
            moon && !er -> "moon"
            else -> null
        }
    }

    /** 그 병원을 내비에 넣는다(화면 버튼이나 "응"). 넣었으면 3걸음으로 넘기고 "병원 가는 길을 넣어 뒀어요" */
    private fun chooseHospital(kind: String) {
        val h = Stage.ui.value.hosp
        val target = if (kind == "moon") h.moon else h.er
        if (target == null) { Stage.toast("가까운 병원을 아직 찾지 못했어요"); return }
        // 병원을 골랐으면 아플 때 안내가 맞다는 뜻: 짐작 줄을 거둔다(검토 11/07 멋대로 판단)
        Stage.clearGuess()
        // 듣던 대답 창을 닫는다. 운전자 확인("괜찮으세요?")이었으면 물음 띠도 거두고 그 물음은 세지 않는다(다음 확인은 finish가 다시 건다, 검토 11/06)
        if (listening) {
            val wasOk = lines.getOrNull(index)?.asset == "e_ok"
            listening = false; listenId++
            Stage.endListen(); okBand(false)
            if (wasOk && drvChecks > 0) drvChecks--
        }
        player?.let { it.release(); player = null }
        val how = navi?.goVia(target.lat, target.lon, target.name, target.address)
        if (how == null) { Stage.toast("내비에 넣지 못했어요. 119에 전화하세요"); return }
        Prend.log("◆ 아플 때: ${if (kind == "moon") "달빛어린이병원" else "응급실"}을 내비에 넣음($how)")
        shownStep = 3
        Stage.ui.update { it.copy(hosp = it.hosp.copy(chosen = target), step = 3) }
        if (!active) return
        talking = true
        lines = listOf(Say("e_ill_done", 3))
        index = 0
        if (focus == null) requestFocus(300) else next(300)
    }

    // ── 끝 ──

    /** 다 말했다. 화면이 안 보이면 배너를 알려 주고 포커스를 놓는다(멈춘 음악은 저절로 다시 흐르지 않는다) */
    private fun finish() {
        if (!Stage.ui.value.visible && !rescuing && lines.lastOrNull()?.asset != "e_look") {
            lines = lines + Say("e_look", shownStep)
            next(GAP)
            return
        }
        Prend.log("· 급할 때 안내: 목소리 안내 끝(화면과 배너는 남김)")
        talking = false
        releaseFocus()
        // 운전자 확인은 start에서 켠다. 이미 세워 둔 채 시계가 없으면(다시 묻기 뒤 등) 여기서 건다
        if (mode == Scene.ILLNESS && drvChecking && !drvAway && drvCheckAt == 0L && car().parked && !rescuing && !askingWho) drvCheckAt = android.os.SystemClock.elapsedRealtime() + DRV_CHECK_MS
    }

    /** 통화에 포커스를 내줬다: 지금 문장을 멈추고(끝까지 말하지 않았으니 돌려받으면 처음부터) 듣기도 닫는다 */
    private fun pause() {
        main.removeCallbacks(sayNow)
        player?.let { it.release(); player = null; Prend.log("· 급할 때 안내: 말하다 멈춤(${lines.getOrNull(index)?.asset})") }
        if (listening) { listening = false; listenId++; Stage.endListen() }
        okBand(false)
    }

    /** 목소리를 멈춘다(사람이 화면으로 넘겨받음). 화면은 그대로 */
    private fun quiet() {
        talking = false
        pause()
        releaseFocus()
    }

    private fun end(why: String) {
        Prend.log("◆ 급할 때 안내 끝($why)")
        // 사람이 연 사고 화면을 대답도, 걸음 넘김도 없이 30초 안에 닫았다(연습·잘못 누름): 사고 메모를 남기지 않는다(11/04 사람 시험: 시연한 다음 날 첫 화면에 '사고 뒤 남은 일')
        // 응급처치·아플 때처럼 다른 급할 때 화면으로 옮긴 것은 닫은 것이 아니다(11/04 검토). 지울 때 앞 메모가 있었으면 그것으로 되돌린다
        if (memoAt > 0 && !memoKept && !byAirbag && !Stage.ui.value.scene.isE && android.os.SystemClock.elapsedRealtime() - memoAt < 30_000) {
            memoPrev?.let { after.restore(it) } ?: after.done()
            Prend.log("· 사고 메모 취소(대답 없이 30초 안에 닫음)")
        }
        memoAt = 0L
        active = false
        rescuing = false
        okAway = false
        // 누가 아픈지 묻던 카드는 안내와 함께 거둔다(대답이 와도 안내가 끝나 버린다)
        if (askingWho) { askingWho = false; whoId++; if (Stage.ui.value.ask?.id == "Q_WHO") Stage.ask(null) }
        // E를 닫았으면 짐작 줄도 거둔다(다음 안내에 지난 짐작이 뜨지 않게). 다른 E 안내로 옮긴 것이면 새 짐작일 수 있어 둔다
        if (!Stage.ui.value.scene.isE) Stage.clearGuess()
        quiet()
    }

    /** 사람이 연 사고 화면의 메모를 적은 때, 그 뒤 대답했나(연습으로 연 화면을 가리려고) */
    private var memoAt = 0L
    private var memoKept = false
    private var memoPrev: AfterCrash.Snap? = null

    /**
     * 시연 장면 준비(RideService ①, 검토 11/06 10): 첫 화면으로 돌린 뒤 다음 틱을 기다리지 않고 안내를 끝낸다.
     * 틱을 기다리면 바로 이어 넣은 ③의 에어백이 '이미 에어백 안내 중'으로 버려졌다
     */
    fun endNow(why: String) {
        lastScene = Stage.ui.value.scene
        if (active) end(why)
    }

    /** 서비스가 끝날 때 */
    fun release() {
        if (active) end("서비스 끝")
        letGoHold("서비스 끝")
        fakeCall(false)
        scope.cancel()
        Stage.onHosp = null
        Stage.onAccidentSaid = null
        Stage.onNotHurt = null
        Stage.onHurtLate = null
        Stage.onHurtSaid = null
        Stage.onWhoSaid = null
        Stage.onGuessWrong = null
        Stage.onGuideTouch = null
    }

    private fun releaseFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
        hasFocus = false
        // 포커스를 놓으면 통화를 기다리지 않는다(사람이 걸음을 넘겨받았거나 안내가 끝남)
        callWaiting(false)
    }

    // ── 시험: 통화가 포커스를 잡은 것처럼(블루링크 상담원 통화 대역. 에뮬레이터에는 통화가 없다) ──

    private var fake: AudioFocusRequest? = null

    /** 시험 통화를 켜 둔 중인가(위 막대의 '시연' 표) */
    val faking get() = fake != null

    fun fakeCall(on: Boolean) {
        fake?.let { audio.abandonAudioFocusRequest(it); fake = null; Prend.log("· 시험 통화 끝") }
        if (!on) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { Prend.log("· 시험 통화 포커스 변화 $it") }
            .build()
        fake = req
        Prend.log("· 시험 통화 시작(포커스 ${audio.requestAudioFocus(req)})")
    }

    private companion object {
        const val GAP = 1_200L
        /** 응급처치 카드는 그림을 볼 틈을 두고 넘긴다 */
        const val AID_GAP = 2_500L
        const val FIVE_MIN = 5 * 60_000L
        const val SZ_5MIN = "aid_sz_5min"
        /** 고속도로 사고(한국도로공사 순서 + 보험사·블랙박스·상대). 걸음은 Stage.kt의 Guide와 같다 */
        /** 고속도로 대피 문장 뒤 이만큼 지나도 운전석 문이 열리지 않으면 한 번 더 */
        const val EVAC_AGAIN_MS = 30_000L
        val HIGHWAY = listOf(Say("e_hw_1", 1), Say("e_hw_2", 2), Say("e_hw_3", 3), Say("e_tow_hw", 4), Say("e_ins_hw", 5), Say("e_box_hw", 6), Say("e_swap", 7))
        /** 일반 도로 사고(§5-2 + 보험사·사진·상대) */
        val ROAD = listOf(Say("e_rd_1", 1), Say("e_rd_2", 2), Say("e_rd_3", 3), Say("e_rd_4", 4), Say("e_ins", 5), Say("e_photo", 6), Say("e_swap", 7))
        /** 가벼운 사고(일반 도로, 다친 사람 없음): 사진 먼저, 차를 길가로, 보험사, 상대 */
        val MINOR = listOf(Say("e_hw_1", 1), Say("e_mn_2", 2), Say("e_mn_3", 3), Say("e_ins", 4), Say("e_swap", 5))
        val HURT = listOf("다쳤", "다쳐", "다친", "아파", "아퍼", "아프", "피가", "피나", "출혈", "못움직", "살려", "도와", "119",
            "어지러", "어질", "숨이", "숨을", "숨쉬", "정신", "토할", "구토", "부러", "저려", "감각이")
        /** 안내 중 [프렌드]로 한 말에서 다쳤다고 볼 낱말: 물음의 대답이 아니라 '도와'·'119'는 부탁, "정신이 없네"는 경황일 수 있어 뺀다(hurtByWord) */
        val HURT_TOLD = HURT - setOf("도와", "119", "정신")
        /** 다치지 않았다는 말(HURT 낱말을 품고 있어 먼저 지운다) */
        val NOT_HURT = listOf("안다쳤", "안다쳐", "안다친", "안아파", "안아퍼", "안아프", "다친데없", "다친곳없", "다친사람없", "아픈데없", "아픈곳없", "다친데는없", "아픈데는없")
        /** 괜찮다는 말. "별거 아니에요"·"아무렇지도 않아요"·"차만 살짝 긁혔어요"도(검토 11/07 멋대로 판단: 괜찮다는 낱말이 없어 다친 것으로 보고 SOS로 갔다) */
        val OK = listOf("괜찮", "괜찬", "멀쩡", "문제없", "이상없", "별일없", "무사", "안다쳤", "안다쳐", "안아파", "안아퍼", "다친데없", "다친곳없", "다친사람없", "아픈데없", "아픈곳없", "다친데는없", "아픈데는없",
            "별거아니", "별것아니", "별일아니", "아무렇지", "살짝긁", "차만")
        /** 119가 필요 없다는 말("괜찮아요, 119는 안 불러도 돼요"·"119 필요 없어"): '119'를 다친 말로 세지 않게 먼저 지운다(검토 11/07 멋대로 판단) */
        val NO_119 = Regex("119(번)?(은|는|도|까지는|까진|까지)?(안불러|안불렀|안부를|부르지마|부르지말|부르지않|부를필요없|부를정도는아니|필요없|필요는없|안해도|안걸어도|괜찮|됐)")
        /**
         * 아플 때 다시 물은 "괜찮으세요?"에 나아졌다는 말("좀 나아졌어요"·"아까보다 덜 아파요"·"가라앉았어요", 검토 11/07 멋대로 판단: 119를 다시 말했다).
         * 사고 직후의 물음에는 쓰지 않는다(그때 "덜 아파"는 다친 것이다). NOT_BETTER가 함께면 나아진 것이 아니다
         */
        val BETTER = listOf("나아졌", "나아지", "나았", "좋아졌", "좋아지", "덜아파", "덜아퍼", "덜아프", "괜찮아지", "괜찮아졌", "진정됐", "진정되", "진정이됐", "가라앉")
        val NOT_BETTER = listOf("안나아", "안나았", "안좋아", "나아지지않", "나아지질않", "좋아지지않", "안가라앉", "가라앉지않", "진정이안", "진정안", "진정되지않", "더아파", "더아프", "더심해", "심해졌")
        /** 신음·감탄과 몸 자리: 아이·같이 탄 사람 낱말을 품고 있어 먼저 지운다("아이구 아파요"·"엄마야"·"목 뒤에가 아파", 검토 11/07 멋대로 판단) */
        val GROANS = listOf("아이구", "아이고", "아이쿠", "아이코", "아이씨", "아이참", "엄마야", "어머나", "어머머", "뒤에가", "옆에가")
        /** 운전자 자신이라는 말 가운데 낱말 하나로 서야 하는 것("저 아파요"의 '저'. "저기"·"나아졌어요"는 아니다) */
        val SELF_WORDS = setOf("저", "나", "저요", "나요", "저는", "나는", "전", "난", "저예요", "저에요", "나예요")
        /** 누가 아프세요? 카드의 말 대답: 첫째면 [제가 아파요], 둘째면 [같이 탄 사람](둘째를 먼저 본다. '아이'는 "아이구"에 걸려 "아이가"·"아이요"로) */
        val WHO_SELF = listOf("제가", "내가", "=저", "=나", "=전", "=난")
        val WHO_OTHER = listOf("애", "아이가", "아이요", "아이예요", "아기", "엄마", "아빠", "옆", "뒤", "같이", "동승", "딸", "아들", "누나", "언니", "오빠", "형", "남편", "아내", "와이프", "할머니", "할아버지", "어머니", "아버지", "친구", "동생")
        /** 달빛어린이병원을 가리키는 말 */
        val MOON_WORDS = listOf("달빛", "어린이병원", "아동병원", "소아과", "소아청소년과")
        /** [갑자기 아파요]를 누른 뒤 이만큼 안에 아플 때 안내가 시작되면 단추로 연 것이다 */
        const val PRESS_MS = 5_000L
        /** 짐작 줄을 두는 시간 */
        const val GUESS_MS = 20_000L
        /** '괜찮'을 품었지만 괜찮지 않다는 말(OK보다 먼저 본다) */
        val NOT_OK = listOf("안괜찮", "안괜찬", "괜찮지않", "괜찬지않", "괜찮지못", "괜찮지가않", "괜찮은지모르", "괜찮은지잘모르", "괜찮은건지모르", "괜찮을리",
            "안멀쩡", "멀쩡하지않", "무사하지않", "문제있", "이상있", "모르겠")
        /** 연기·불 말 */
        val FIRE = listOf("연기", "불났", "불이났", "불붙", "타는냄새", "탄내", "화재")
        /** 대피 문장: 이 말을 시작할 때부터 운전석 문을 지켜보고, 끝난 때부터 시계를 잰다(evacWaitTick) */
        val EVAC = setOf("e_hw_2", "e_rd_2", "e_brk_wait")
        /** 대피 문장 다음의 119·견인 한 줄: 이 말까지 하고 운전석 문을 기다린다(11/06: 바로 내린 사람도 듣게). 일반 도로 고장은 대피 문장이 곧 이 줄 */
        val WAIT_AFTER = setOf("e_hw_3", "e_tow_out", "e_rd_3", "e_brk_wait")
        const val EVAC_WAIT_MS = 20_000L
        /** 바로 전 이만큼 안에 이 속도를 넘겨 달렸으면 가벼운 사고 순서를 쓰지 않는다 */
        const val FAST_KMH = 80f
        const val FAST_MS = 2 * 60_000L
        const val DRV_CHECK_MS = 20_000L
        /** 병원 물음의 예. "응급실로 가 줘"·"빨리 가 주세요"·"거기로"·"어서"도(검토 11/07 멋대로 판단: 아니오로 보고 말없이 멈췄다) */
        val YES = listOf("그래", "넣어", "가자", "좋아", "부탁", "그렇게", "가줘", "가주세요", "가요", "빨리", "거기로", "어서")
        val NO = listOf("아니", "괜찮", "됐어", "싫어", "말고")
    }
}
