package app.prend.ui

import android.car.VehicleGear
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.prend.Prend
import app.prend.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** PREND 화면의 장면. lit는 위쪽 이름에서 켜는 기어 글자, moment는 그 옆의 지금 순간 */
enum class Scene(val lit: String, val moment: String) {
    HOME("PR", "세워 둔 동안"),
    DRIVE("ND", "달리는 중"),
    EMERGENCY("E", "급할 때"),
    ACCIDENT("E", "사고 · 고속도로"),
    ILLNESS("E", "갑자기 아플 때"),
    AID("E", "응급처치"),
    NAP("PR", "쪽잠"),
    PICK("PR", "밥집·카페"),
    MEMO("PR", "설정"),
    CARE("PR", "차 정비");

    val isE get() = lit == "E"
}

/** [프렌드]를 부른 뒤: 듣는 중 → 알아들음(또는 못 들음) → 닫힘. hints는 '듣는 중' 아래에 옅게 보일 말(부를 때만, 대답을 들을 때는 비움) */
data class Listen(val heard: String = "", val done: Boolean = false, val level: Float = 0f, val hints: List<String> = emptyList())

/** PREND가 지금 묻는 것. PREND 화면이 보이지 않으면 배너로 띄우고, 대답은 id로 돌려준다. opens면 [yes]가 그 화면을 연다 */
data class Ask(
    val id: String, val title: String, val text: String, val yes: String, val no: String, val opens: Scene? = null,
    /** 화면 위 알림(배너)에서만 yes 대신 둘 버튼 글(한 시간 물음의 [쉬어 갈 곳], 별첨 2 §5-2). 누르면 Stage.answerAlt */
    val alt: String? = null,
)

/** 같이 고르기의 한 곳(NAVER 지역 검색). reviews는 블로그 글 수, meters는 지금 위치에서 곧은 거리 */
data class Place(val name: String, val category: String, val address: String, val lat: Double, val lon: Double, val reviews: Int?, val meters: Int?)

/** 같이 고르기 화면. kind는 food(밥집)·cafe(카페), area는 검색에 쓴 동네 이름 */
data class Pick(val kind: String = "food", val area: String? = null, val loading: Boolean = false, val error: String? = null, val items: List<Place> = emptyList(), val chosen: Int = -1)

/** 차 돌봄 한 줄(ride/CarCare): key는 ride/CareBook의 항목, state는 지금 상태 한 줄, due는 갈 때(호박색), soon은 곧, diy는 직접 할 수 있음 */
data class CareRow(val key: String, val label: String, val state: String, val due: Boolean = false, val soon: Boolean = false, val diy: Boolean = false)

/** 차 돌봄 화면. km는 누적 주행거리(모르면 null), open은 펼친 항목, shops는 가까운 블루핸즈(같이 고르기의 Pick 꼴, kind "service") */
data class CareUi(val km: Int? = null, val electric: Boolean? = null, val rows: List<CareRow> = emptyList(), val open: String? = null, val shops: Pick? = null, val visit: String? = null)

/** 급할 때 찾은 병원(국립중앙의료원). 응급실은 남은 병상(음수면 붐빔)과 갱신 시각, 달빛어린이병원은 오늘 진료 시간(HHmm) */
data class Hospital(
    val name: String, val address: String, val tel: String?, val lat: Double, val lon: Double, val km: Double,
    val beds: Int? = null, val bedsAt: String? = null, val open: String? = null, val close: String? = null,
    /** 앱 안 목록에서 골랐을 때의 분류(지역응급의료센터 등). 실시간 결과면 null */
    val kind: String? = null,
)

/**
 * 급할 때의 병원 찾기. chosen은 경유지로 넣은 곳.
 * asOf는 앱에 넣어 둔 목록에서 골랐을 때 그 목록을 모은 날(YYYY-MM-DD). 그때는 '가기 전에 전화로 확인하세요'를 붙인다.
 * noNet은 중계 서버가 답하지 않아 그 목록으로 갔을 때만 참(서버는 답했는데 결과가 비었으면 '인터넷이 안 돼'라고 하지 않는다)
 */
data class Hosp(
    val loading: Boolean = false, val error: String? = null, val er: Hospital? = null, val moon: Hospital? = null, val chosen: Hospital? = null,
    val asOf: String? = null, val noNet: Boolean = false,
) {
    /** 아이 이야기면 달빛어린이병원을 먼저 권한다. 다만 15km보다 멀면 가까운 응급실을 먼저(화면에는 둘 다 둔다) */
    fun moonFirst(kid: Boolean) = kid && moon != null && (er == null || moon.km <= 15.0)
}

/**
 * 쓸수록 기억하는 PREND: 한 순간의 버릇(ride/Memory가 적고, 화면 '기억'이 보인다).
 * yes·no는 연달아 받은 "응"·"괜찮아요" 수, auto면 묻지 않고 알리며 하고, quiet면 묻지 않는다. why는 마지막으로 바뀐 까닭 한 줄
 */
data class Habit(val yes: Int = 0, val no: Int = 0, val auto: Boolean = false, val quiet: Boolean = false, val why: String? = null) {
    val changed get() = auto || quiet
}

/** 정체·같이 고르기의 버릇과 쪽잠(고른 길이, 깨우기를 끝나기 몇 초 전부터 시작하나, 다시 불러야 깬 날이 이어진 수) */
data class Habits(
    val jam: Habit = Habit(), val pick: Habit = Habit(),
    val napMin: Int? = null, val napLead: Int = 120, val napHard: Int = 0,
    /** 한 시간에 묻기·주차 자리 알림·대리 운행 묻기: 두 번 연달아 "괜찮아요"(주차 자리는 "그만 알려요" 한 번)면 묻지 않는다 */
    val hour: Habit = Habit(), val spot: Habit = Habit(), val daeri: Habit = Habit(),
    /** 한 시간 물음에서 지난번 고른 것(AIR·HEAT·VENT·MUSIC). 그때 차에 맞으면 첫 버튼으로 먼저 내민다 */
    val hourPick: String? = null,
    /** 한 시간 물음에서 바깥 공기를 연달아 고른 수(세 번이면 hour.auto: 묻지 않고 넣어 둔다) */
    val hourAirRun: Int = 0,
    /** 조용히 가기를 부른 칸(평일·주말 × 시간대)과 수(두 번이면 먼저 조용히), 그 칸의 [평소대로] 수 */
    val quietSlots: Map<String, Int> = emptyMap(),
    val quietNos: Map<String, Int> = emptyMap(),
) {
    /** 먼저 조용히 가는 칸 */
    val quietLearned get() = quietSlots.filterValues { it >= 2 }.keys.sorted()
    val napChanged get() = napMin != null || napLead != 120
}

/** 새로 기억한 것 한 줄(하루 동안 기록으로만 둔다. 첫 화면의 '새로 기억함' 줄은 뺐다, 검토-방향노인초보 2-2). kind는 바뀐 버릇(JAM·PICK·HOUR·SPOT·DAERI·NAP), 그것을 잊으면 함께 거둔다 */
data class Remembered(val note: String, val kind: String, val at: Long)

/**
 * 응급처치 카드. 그림과 순서는 소방청 교육자료(공공누리 제1유형: 출처 표시, 변경 가능)에서 가져오고,
 * 문장은 「2025년 한국 심폐소생술 가이드라인」과 대조해 PREND 말투로 다듬었다(10/28, 03 문서 2주차 시험 16).
 * voice는 걸음별 목소리 파일의 앞 이름(voice/<voice>_<걸음>.wav), red는 화면 아래 빨간 한 줄
 */
enum class Aid(val label: String, val source: String, val red: String, val voice: String) {
    CPR("심폐소생술", "소방청 교육자료 「심폐소생술(CPR)」", "반응이 없으면 119가 먼저", "aid_cpr"),
    CHOKE("목에 걸렸을 때", "소방청 교육자료 「기도폐쇄」", "숨을 못 쉬면 옆 사람에게 119를 부탁하세요", "aid_ch"),
    INFANT("돌 전 아기 목에 걸렸을 때", "소방청 교육자료 「영아 기도폐쇄 처치」", "아기가 숨을 못 쉬면 옆 사람에게 119를 부탁하세요", "aid_inf"),
    SEIZURE("경련", "소방청 교육자료 「경련처치」", "5분 넘게 이어지면 바로 119", "aid_sz"),
}

/** 쪽잠의 때: 자는 중 → 깨우는 중(빛·바람·곡·목소리) → 깬 뒤 3분 */
enum class NapPhase { SLEEP, WAKE, AFTER }

data class StageUi(
    val scene: Scene = Scene.HOME,
    /** 차의 기어(android.car.VehicleGear). 운행 서비스가 넣는다 */
    val gear: Int = VehicleGear.GEAR_PARK,
    /** 차가 주행 제한을 거는 중(CarHub CarState.uxLimited). 켜져 있으면 P여도 달리는 동안의 화면(parked=false) */
    val uxLimited: Boolean = false,
    /** 연속 운전 시간(P로 15분 쉬면 0) */
    val driveMinutes: Int = 0,
    val outsideC: Int? = null,
    /** 운전석 열선(시트 온도)·통풍 단계. 모르면 null */
    val heat: Int? = null,
    val vent: Int? = null,
    /** 조수석이나 뒷자리에 사람이 있다(달리는 동안의 첫 화면에 [잘래요]를 둔다) */
    val others: Boolean = false,
    val step: Int = 1,
    val napMinutes: Int = 15,
    val napEndAt: Long = 0L,
    /** 쪽잠의 때(쪽잠이 아니면 null), 깬 뒤 3분이 끝나는 때 */
    val napPhase: NapPhase? = null,
    val napAfterUntil: Long = 0L,
    /** 잘래요: 쉬는 사람의 자리("옆자리"·"뒷자리"), 언제 깨우는지 한 줄. 쉬지 않으면 null */
    val sleeper: String? = null,
    val sleepWake: String? = null,
    /** 가족 정보: 함께 타는 아이가 있다(true)·없다(false)·아직 정하지 않았다(null). 있으면 사고 때 뒷좌석에 아이가 있는지 살피라고 알린다 */
    val kids: Boolean? = null,
    /** 혹시 사고에 대답이 없었다: 10분 동안(세워 둔 채) 배너에 "혹시 사고였나요?" 한 줄을 목소리 없이 둔다 */
    val bumpHint: Boolean = false,
    /** 초보 운전: 달리는 동안 말을 줄이고, 비·영하에 섰을 때 한마디 */
    val novice: Boolean = false,
    val listen: Listen? = null,
    val toast: String? = null,
    /** 쉼터 카드의 다음 쉼터 한 줄(이름·거리). 모르면 null */
    val restLine: String? = null,
    /** 시연·촬영용 값(정 과장의 하루)을 띄우는 중이면 운행 서비스가 덮어쓰지 않는다 */
    val demo: Boolean = false,
    /** PREND 화면이 지금 보이는가(안 보이면 묻고 알리는 일을 배너가 맡는다) */
    val visible: Boolean = false,
    val ask: Ask? = null,
    /** 사고 안내의 순서: 고속도로면 한국도로공사 순서, 아니면 일반 도로 순서 */
    val highway: Boolean = true,
    /** 119에 말할 위치 한 줄(고속도로면 노선·방향·이정, 아니면 내비의 주소). 모르면 null */
    val place: String? = null,
    val pick: Pick = Pick(),
    val care: CareUi = CareUi(),
    val hosp: Hosp = Hosp(),
    /** 아플 때 "아이가…"처럼 아이 이야기였다(달빛어린이병원을 먼저 권한다). 들은 말 자체는 남기지 않는다 */
    val kid: Boolean = false,
    /** 아플 때 말로 가른 아픈 사람: true 같이 탄 사람(아이·엄마·옆 사람…), false 운전자 자신("내가"·"운전하다가"), null 모름(버튼). 들은 말은 남기지 않는다 */
    val illOther: Boolean? = null,
    /** 아픈 사람이 운전자다(ride/Crash가 정함): 병원 경유지를 권하지 않고 "운전은 멈추고 세운 뒤 119" */
    val driverIll: Boolean = false,
    /**
     * 낱말 규칙에 없는 말을 AI 대역이 듣고 짐작으로 연 안내("사고"·"아플 때"). 화면이 '‘사고’로 알아들었어요 [아니에요]'를 두고 [아니에요]는 Stage.guessWrong이 받는다.
     * 사람이 분명히 말했거나 E를 벗어나면 null(검토 11/07 멋대로 판단: 낯선 말로 급한 일을 알리는 사람을 늦추지 않되, 짐작이었다는 것은 보이게)
     */
    val guess: String? = null,
    /** 쓸수록 기억하는 PREND가 적어 둔 버릇 */
    val habits: Habits = Habits(),
    /** 이번 운행에 뒷자리에 아이가 탔다("아이 탔어"로 켠다. 새 운행이면 끈다). 사고가 나면 "뒷좌석에 아이가 있어요"로 분명히 알린다 */
    val baby: Boolean = false,
    /** 사고 안내: 일반 도로에서 다친 사람이 없다고 답했다(사진 먼저, 차를 길가로) */
    val minor: Boolean = false,
    /** 사고가 아니라 고장(11/02): "차가 고장 났어"·"타이어 터졌어"·"기름 떨어졌어". 대피·견인·긴급출동만, 괜찮으세요·사고 메모·상대는 없다 */
    val breakdown: Boolean = false,
    /** 연기·타는 냄새(11/02): 세우고 → 시동 끄고 멀리 → 119 */
    val fire: Boolean = false,
    /** 에어백 뒤 "괜찮으세요?"에 대답이 없다: 구조하러 온 사람이 읽을 화면 */
    val rescue: Boolean = false,
    /** 이번 급할 때가 시작된 때(벽시계, 에어백이면 터진 때). 구조 요청 화면·배너에 "오후 3:42"로 적어 구하러 온 사람과 119가 언제부터인지 알게 한다 */
    val eSince: Long = 0L,
    /** 사고 안내가 "SOS 버튼을 누르거나 119에 알리세요"를 말했다(다쳤거나 대답이 없음). 목소리만으로 지나가지 않게 화면과 배너 맨 위에 빨간 띠로 둔다 */
    val sos: Boolean = false,
    /** 급할 때 안내(ride/Crash)가 "괜찮으세요?"를 묻고 대답을 듣는 중: 걸음 위에 물음 띠와 [괜찮아요]·[다쳤어요](아플 때는 [아파요])를 둔다(검토 11/06) */
    val okAsk: Boolean = false,
    /** 급할 때 안내가 통화(블루링크 상담원·전화)가 끝나기를 기다리는 중: 화면과 위 막대에 "통화가 끝나면 이어서 안내해요"(검토 11/06) */
    val callWait: Boolean = false,
    /** 디버그·PT 빌드에서 켜 둔 시험 값("시연 · 에어백 · 통화"). 위 막대에 작은 표로 보인다. 출시 빌드는 늘 null(운행 서비스가 넣는다) */
    val demoTag: String? = null,
    /** 내 보험사 이름(한 번 고르면 기억한다) */
    val insurer: String? = null,
    /** 사고 메모. 남아 있으면 첫 화면에 '사고 뒤 남은 일'을 둔다 */
    val note: CrashNote? = null,
    /** 응급처치 카드: 보고 있는 카드, 카드를 연 시각(경련은 이때부터 잰다), 달리는 중에 말해 세우면 열 카드 */
    val aid: Aid = Aid.CPR,
    val aidSince: Long = 0L,
    val aidWant: Aid? = null,
    /** 경련 시계의 시작 시각. 심폐소생술 카드에 다녀와 경련 카드로 돌아와도 이어서 잰다(11/08 사용자 결정) */
    val szSince: Long = 0L,
    /** 대리 운행(이번 운행만): 먼저 말 걸지 않고, 동승석 잘래요와 급할 때만 */
    val daeri: Boolean = false,
    /** 이야기에 비켜 서기: 켜 둠(사람이 고름), 지금 말소리 여부를 재는 중(마이크), 지금 이야기 중 */
    val talkOn: Boolean = false,
    val talkLive: Boolean = false,
    val talking: Boolean = false,
    /**
     * 먼저 묻기에서 켜 둔 곁들임 물음(HOUR·DAERI·TALK·JAM·PICK, ride/Memory). 사용자 결정 11/06: 새로 깐 앱은 모두 꺼 두고 기억 화면에서 켠다.
     * 쉼터 권하기(두 시간·졸음)·혹시 사고·고속도로 정차·급할 때는 여기와 상관없이 늘 묻는다
     */
    val asks: Set<String> = emptySet(),
    /** 위치 보내기(ride/GeoShare): 골목·큰길 가리기와 병원·화장실·날씨·가게 찾기에 위치를 서버로 보내나. 끄면 병원은 앱 안 목록, 골목은 차 신호만 */
    val geoSend: Boolean = true,
    /** 차 속도 허락이 없다(혹시 사고·말할 때 고르기가 멈춘다), 알림 허락이 없다(화면을 닫으면 물음이 뜨지 않는다). 화면이 넣는다 */
    val speedOff: Boolean = false,
    val notifOff: Boolean = false,
    /**
     * 허락 까닭 판을 띄울 차례이거나 안드로이드 허락 창이 떠 있다(화면이 넣는다). 그동안 위치 알림 카드(ride/Care의 GEO)는 기다린다:
     * 판이 카드를 덮어 읽지도 누르지도 못한 채 '건넸다'로 세지 않게(검토 11/06)
     */
    val permPanel: Boolean = false,
    /** 조용히 가기(이번 운행만): 먼저 건네는 물음은 카드로만, 쉼터·혹시 사고·급할 때만 목소리 */
    val quietRide: Boolean = false,
    /** 늘 조용히(기억 화면, 사용자 결정 11/04) */
    val alwaysQuiet: Boolean = false,
    /** 말하기 전 기척: PREND가 곧 말을 건넨다(위 막대 기어 옆의 민트 점). 말이 끝나거나 삼키면 꺼진다 */
    val presence: Boolean = false,
    /** 새로 기억한 것(하루 동안, 화면에는 보이지 않는다). ride/Memory가 넣고 거둔다 */
    val remembered: Remembered? = null,
    /** 한 시간 넘게 달린 뒤 P로 15분 쉬어 연속 운전이 0이 된 때(currentTimeMillis). 0이면 아님. 다시 달리면 거둔다 */
    val restedAt: Long = 0L,
    /** 기억 화면 안의 앱 정보(시연 안내·출처·라이선스)를 보는 중. 세워 둔 동안만, 기억 화면을 벗어나면 거둔다 */
    val about: Boolean = false,
    /** 기억 화면 안의 '그 밖에'(한 시간·주차 자리·대리 운행·쪽잠·오래 막힐 때·같이 고르기·이야기에 비켜 서기)를 보는 중. 기억 화면을 벗어나면 거둔다 */
    val memoMore: Boolean = false,
    /** 설정 안의 '내 보험사' 고르기를 보는 중(11/10). 설정을 벗어나면 거둔다 */
    val insurerPick: Boolean = false,
    /**
     * 듣고 보기(ride/SlowTalk·Dusk가 넣는다, 검토-방향노인초보 3-9): 천천히 말하기, 넉넉히 기다리기(대답 창 8초), 해 지기 전에 알려 주기.
     * why는 켜진 까닭 한 줄(기억 화면에서 사람이 켰으면 null)
     */
    val slowTalk: Boolean = false,
    val slowWhy: String? = null,
    val longWait: Boolean = false,
    val waitWhy: String? = null,
    val dusk: Boolean = false,
    val duskWhy: String? = null,
) {
    /** 세워 둔 동안의 화면을 보여도 되나: P이고 차가 주행 제한을 걸지 않았다(기어 값이 늦거나 틀려도 차가 알린 제한을 따른다) */
    val parked get() = gear == VehicleGear.GEAR_PARK && !uxLimited

    /** 달리는 동안: E 걸음 화면을 큰 글씨 한 줄과 빨간 줄로 줄인다(접수양식 '멈춘 글 한 줄과 큰 버튼 두 개까지'). 시연 값이면 그대로 */
    val brief get() = !parked && !demo

    /** 위쪽 이름에서 켤 글자: E 장면은 E, P·R이면 P·R, N·D면 N·D */
    val lit get() = when {
        scene.isE -> "E"
        scene == Scene.DRIVE -> "ND"
        gear == VehicleGear.GEAR_PARK || gear == VehicleGear.GEAR_REVERSE -> "PR"
        else -> "ND"
    }
    /** 구조 요청 화면·배너의 첫 줄: "오후 3:42 에어백이 터진 뒤 대답이 없었어요"(때를 모르면 때 없이) */
    val rescueWhy get() = listOfNotNull(
        eSince.takeIf { it > 0 }?.let { java.text.SimpleDateFormat("a h:mm", java.util.Locale.KOREA).format(java.util.Date(it)) },
        if (scene == Scene.ILLNESS) "아프다고 한 뒤 대답이 없었어요" else "에어백이 터진 뒤 대답이 없었어요",
    ).joinToString(" ")
    val moment get() = base + (if (callWait && scene.isE) " · $CALL_WAIT" else "") + (if (daeri && !scene.isE) " · 대리 운행" else "") + (if (quietRide && !scene.isE) " · 급한 말만" else "")

    private val base get() = when {
        scene == Scene.ACCIDENT && rescue -> "사고 · 구조 요청"
        scene == Scene.ILLNESS && rescue -> "아플 때 · 구조 요청"
        scene == Scene.ACCIDENT && fire -> "연기·불"
        scene == Scene.ACCIDENT && breakdown -> if (highway) "고장 · 고속도로" else "고장 · 일반 도로"
        scene == Scene.ACCIDENT && minor -> "사고 · 가벼운 사고"
        scene == Scene.ACCIDENT && !highway -> "사고 · 일반 도로"
        scene == Scene.AID -> "응급처치 · ${aid.label}"
        scene == Scene.MEMO && about -> "앱 정보"
        scene == Scene.MEMO && memoMore -> "설정 · 배운 것"
        scene == Scene.MEMO && insurerPick -> "설정 · 내 보험사"
        scene != Scene.HOME -> scene.moment
        gear == VehicleGear.GEAR_PARK -> "세워 둔 동안"
        gear == VehicleGear.GEAR_REVERSE -> "후진 중"
        else -> "달리는 중"
    }
}

/** 급할 때 안내가 통화(블루링크 상담원·전화)가 끝나기를 기다리는 동안의 한 줄. 화면·위 막대·배너가 함께 쓴다 */
const val CALL_WAIT = "통화가 끝나면 이어서 안내해요"

/** 걸음 아래의 판: 119에 말할 위치, 내 보험사와 사고 메모, 찍을 사진, 주고받을 것 */
enum class Card { PLACE, INSURER, PHOTO, SWAP }

/**
 * E 안내의 한 걸음: 한 화면에 한 가지. 문장은 공식 자료의 순서 그대로(별첨 12 §5). pic·pic2는 응급처치 카드의 그림, 그림이 없는 걸음은 mark를 크게.
 * dial은 그 걸음에서 바로 걸 번호(119는 빨간 버튼. 달리는 동안의 한 줄 판에서는 119에 말할 위치를 크게)
 */
data class Step(val title: String, val sub: String? = null, val card: Card = Card.PLACE, val pic: Int? = null, val pic2: Int? = null, val mark: String? = null, val dial: String? = null)

/** 자동차보험 사고 접수 번호와 그 번호의 사고 접수 ARS 메뉴(누를 번호). 보험사 공식 누리집에서 확인한 것만 둔다 */
data class Insurer(val name: String, val tel: String?, val ars: String? = null)

/** 사고 메모: 보험사·경찰에 말할 때 쓰도록 PREND가 적어 둔 시각·장소·날씨. 이 차 안에만 두고 [다 했어요]로 지운다 */
data class CrashNote(val at: Long, val place: String?, val weather: String?)

/** E 안내의 걸음들. 화면(Scenes)과 배너, 사고 안내 목소리(Crash)가 같은 걸음을 본다 */
object Guide {
    /** 상대와 주고받을 것(세 갈래의 마지막 걸음). 합의는 현장에서 하지 않는다(금융감독원 보험사기 주의) */
    /**
     * 부르지 않은 견인차(11/02, 사용자 "사설견인차 말고"). 화물자동차 운수사업법 시행규칙 제21조 제20·21호: 운전자 뜻에 반해 끌고 갈 수 없고(경찰이 옮기라고 할 때는 예외),
     * 최종 목적지까지 총 운임·요금이 적힌 구난동의서(별지 제15호서식)를 받은 뒤에 출발한다. 부당 요금은 시·군·구청, 상담 1372(공정위·소비자원)
     */
    private val TOW = Step("부르지 않은\n견인차는\n보내도 돼요", "내가 허락해야 끌고 가요 · 맡기려면 목적지까지 총요금이 적힌 구난동의서를 보고 서명하세요 · 경찰이 옮기라면 따르세요")

    private val SWAP = Step("상대와 연락처·\n차 번호·보험사를\n주고받으세요", "합의는 현장에서 하지 말고 보험사에 맡기세요", Card.SWAP)

    /** 아플 때 병원 걸음의 한 줄: PREND가 하는 일(조건별 판정 없이 공식 안내와 119·병원 연결, 별첨 11 §13-1의 앱 문구) */

    /**
     * 고속도로 사고(§5-1, 한국도로공사 순서). 대피와 119가 먼저이고, 보험사·영상·상대는 가드레일 밖에서.
     * 트렁크는 목소리(e_hw_2)처럼 대피 걸음에 둔다. 112·견인 번호와 견인비는 한국도로공사 누리집에서 확인했다(10/29:
     * 2차사고 예방 '112(경찰), 119(소방), 1588-2504(긴급견인) 신고', 긴급견인 '안전지대까지의 견인비용은 한국도로공사가 부담 · 소형차량: 승용차…')
     */
    val HIGHWAY = listOf(
        Step("비상등을 켜세요"),
        Step("차 뒤로 가지 말고\n바로 가드레일 밖으로\n나가세요", "트렁크는 운전석에서 열 수 있으면 열어 두세요"),
        // 대피한 뒤에는 차 화면을 볼 수 없어 길가 이정표(200m마다)의 숫자를 말하게 한다(소방청 "고속도로 이정좌표 숫자를 불러주시고", R15 §5-1, 11/05)
        Step("안전한 곳에서\n119에 알리세요", "이 위치를 그대로 읽어 주세요 · 밖에서는 길가 이정표 숫자를 · 경찰 112도 돼요", dial = "119"),
        Step("견인이 필요하면\n1588-2504", "한국도로공사 무료 긴급견인 · 가장 가까운 휴게소·졸음쉼터까지 · 24시간", dial = "1588-2504"),
        Step("가드레일 밖에서\n보험사에 사고를\n접수하세요", "휴게소에서 정비소까지는 내 보험 긴급출동으로 · 보통 10km까지 무료, 보험마다 달라요", Card.INSURER),
        Step("블랙박스 영상을\n따로 저장해 두세요", "사진은 가드레일 밖에서만 · 길로 다시 들어가지 마세요", Card.PHOTO),
        SWAP,
        TOW,
    )

    /**
     * 119에 말할 위치가 둘일 때(고속도로인지 애매: 동네 주소 다음 줄에 이 머리를 붙인 고속도로 이정, RideService.place, 검토 11/07 멋대로 판단).
     * '이 위치를 그대로 읽어 주세요'는 확실한 한 줄에만 붙이고, 둘이면 맞는 곳을 고르게 한다(걸음 수·순서는 같다)
     */
    const val PLACE_OR = "고속도로 위라면 "
    fun placeBoth(place: String?) = place?.contains("\n$PLACE_OR") == true
    private val HIGHWAY_BOTH = HIGHWAY.map { if (it.dial == "119") it.copy(sub = "둘 중 맞는 곳을 읽어 주세요 · 밖에서는 길가 이정표 숫자를 · 경찰 112도 돼요") else it }

    /** 일반 도로 사고(§5-2). 한 기관이 정한 순서가 없어 고속도로처럼 대피를 앞에 둔다 */
    val ROAD = listOf(
        Step("비상등을 켜세요", "차가 움직이면 길 오른쪽 가장자리로 옮겨 세우세요"),
        Step("모두 내려서\n길 밖 안전한 곳으로\n가세요", "다친 사람은 불이 나지 않았다면 옮기지 마세요"),
        Step("다친 사람이 있으면\n지금 119에\n전화하세요", "사람이 다친 사고는 경찰에도 꼭 알려야 해요", dial = "119"),
        Step("안전할 때 삼각대를\n차 뒤 잘 보이는 곳에\n세우세요", "고속도로나 자동차전용도로라면 꼭 세워야 해요"),
        Step("보험사에 사고를\n접수하세요", "견인은 보험 긴급출동으로 · 가까운 정비소까지, 보통 10km까지 무료, 보험마다 달라요", Card.INSURER),
        Step("길 밖에서 현장 사진을\n찍어 두세요", "보험사나 경찰을 기다리는 동안", Card.PHOTO),
        SWAP,
        TOW,
    )

    /**
     * 고속도로에서 차가 고장 났을 때(11/02, 검토-방향노인초보 3-7). 대피 문장은 사고와 같다(한국도로공사 2차사고 예방: 비상등·트렁크·가드레일 밖·112·119·1588-2504).
     * 고속도로 2차 사고 치사율은 일반 사고의 약 5배(기획안 2장). 긴급출동이 무엇을 해 주는지는 보험마다 달라 단정하지 않는다
     */
    val BREAK_HW = listOf(
        Step("비상등을 켜세요"),
        Step("차 뒤로 가지 말고\n바로 가드레일 밖으로\n나가세요", "트렁크는 운전석에서 열 수 있으면 열어 두세요"),
        Step("견인이 필요하면\n1588-2504", "한국도로공사 무료 긴급견인 · 가장 가까운 휴게소·졸음쉼터까지 · 24시간", dial = "1588-2504"),
        Step("가드레일 밖에서\n보험사 긴급출동을\n부르세요", "휴게소에서 정비소까지는 내 보험 긴급출동으로 · 보통 10km까지 무료, 보험마다 달라요", Card.INSURER),
        TOW,
    )

    /**
     * 연기·타는 냄새(11/02). 소방청 카드뉴스(2024-08-19, 2025-05-19): 안전한 곳에 세우고 시동을 끄고 바로 내려 119. 현대 설명서: 정차 → 시동 끄기 → 대피, 사람이 다가가지 않게.
     * 보닛 이야기는 공식 원문끼리 엇갈려 하지 않는다. 소화기는 불이 작을 때만(소방청), 화면에만
     */
    val FIRE = listOf(
        Step("안전한 곳에\n바로 세우세요", "비상등을 켜고 · 고속도로면 갓길에"),
        Step("시동을 끄고\n모두 내려\n멀리 떨어지세요", "고속도로면 가드레일 밖으로 · 차로 다시 가지 마세요"),
        Step("멀리서\n119에 알리세요", "불이 작을 때만 소화기 · 연기가 많으면 바로 멀리", dial = "119"),
    )

    /** 전기차의 연기·불(현대·기아 전기차 설명서, 행정안전부 안전한TV: 창문 내리고 전원 끄고 내려, 119에 전기차 화재라고, 다가가지 않게) */
    val FIRE_EV = listOf(
        Step("안전한 곳에\n바로 세우세요", "비상등을 켜고 · 고속도로면 갓길에"),
        Step("창문을 내리고\n시동을 끈 뒤\n모두 내리세요", "고속도로면 가드레일 밖으로 · 끄려고 다가가지 마세요"),
        Step("멀리서 119에\n전기차 불이라고\n알리세요", "아무도 차에 다가가지 않게 해 주세요", dial = "119"),
    )

    /** 일반 도로에서 차가 고장 났을 때 */
    val BREAK_RD = listOf(
        Step("비상등을 켜세요", "차가 움직이면 길 오른쪽 가장자리로 옮겨 세우세요"),
        Step("안전할 때 삼각대를\n차 뒤 잘 보이는 곳에\n세우세요", "고속도로나 자동차전용도로라면 꼭 세워야 해요"),
        Step("차에서 내려\n길 밖 안전한 곳에서\n기다리세요"),
        Step("보험사 긴급출동을\n부르세요", "가까운 정비소까지 끌어 줘요 · 보통 10km까지 무료, 보험마다 달라요", Card.INSURER),
        TOW,
    )

    /** 일반 도로의 가벼운 사고(다친 사람 없음): 사진을 짧게 찍고 차를 길가로 옮기는 것이 먼저(R16 §6) */
    val MINOR = listOf(
        Step("비상등을 켜세요", "다친 사람이 없는 가벼운 사고의 순서예요"),
        Step("차를 옮기기 전에\n사진을 짧게\n찍으세요", "차가 선 자리가 보이게", Card.PHOTO),
        Step("차를 길가\n안전한 곳으로\n옮기세요", "다른 차가 지나갈 수 있게 · 몸이 아프면 바로 119"),
        Step("보험사에 사고를\n접수하세요", "견인이 필요하면 보험사 긴급출동으로", Card.INSURER),
        SWAP,
        TOW,
    )

    /**
     * 함께 탄 사람이 있을 때(동승자 자리·이번 운행 아이 탑승)의 가벼운 사고 순서: 첫 걸음 설명만 다르다(검토 11/07 멋대로 판단:
     * 운전자 한 사람의 "괜찮아요"로 '다친 사람이 없는' 순서라고 적었다. 다른 분이 다쳤으면 오른쪽 위 [다쳤어요])
     */
    val MINOR_TOGETHER = listOf(Step("비상등을 켜세요", "함께 탄 분들도 다친 데가 없을 때의 가벼운 사고 순서예요")) + MINOR.drop(1)

    /**
     * 사고 접수 번호(24시간). 11곳 모두 그 회사의 누리집·약관·앱 장터 소개 글에서 확인했다(10/27, 출처는 03 문서 2주차 시험 11).
     * 대표 번호가 곧 사고 접수 번호이고, ars는 연결 뒤 사고 접수로 가는 메뉴 번호다. 번호가 바뀌면 여기만 고친다
     */
    val INSURERS = listOf(
        Insurer("삼성화재", "1588-5114", "1번"), Insurer("현대해상", "1588-5656", "2번"), Insurer("DB손해보험", "1588-0100", "2번"),
        Insurer("KB손해보험", "1544-0114"), Insurer("메리츠화재", "1566-7711", "1번"), Insurer("한화손해보험", "1566-8000", "1번"),
        Insurer("롯데손해보험", "1588-3344", "2번"), Insurer("흥국화재", "1688-1688", "1번"), Insurer("AXA손해보험", "1566-1566", "3번"),
        Insurer("하나손해보험", "1566-3000"), Insurer("캐롯손해보험", "1566-0300"),
    )

    /**
     * 갑자기 아플 때. 아이 이야기이고 진료 중인 달빛어린이병원이 있으면 둘째 걸음이 그곳을 묻는다.
     * 첫 걸음의 상황요원은 별첨 §5-4 차례 2(설명 줄은 세워 둔 화면에만 보여, '달리는 중이면 핸즈프리로'는 달릴 때 목소리 e_ill_call이 맡는다).
     * 둘째 걸음은 물음 한 줄만(11/09 사용자 "담백하게": '진단하지 않는다' 단서 줄을 뺐다. 원칙은 별첨 11 §13-1에 그대로)
     */
    fun illness(ui: StageUi) = listOf(
        // 운전자가 아프면(11/02) 병원으로 몰고 가게 하지 않고 세운 뒤 119. 둘째 걸음의 병원은 사람이 고를 때만
        if (ui.driverIll) Step("운전은 멈추고\n세운 뒤 119", "119에 이 위치를 읽어 주세요\n가슴 통증·한쪽 팔다리 힘 빠짐·말이 어눌하면 기다리지 마세요", dial = "119")
        else Step("숨쉬기나 의식이 이상하면\n119가 먼저예요", "119에 이 위치를 읽어 주고\n상황요원의 안내를 따르세요", dial = "119"),
        if (ui.hosp.moonFirst(ui.kid) || (ui.hosp.er == null && ui.hosp.moon != null)) Step("가까운 달빛어린이병원을\n경유지로 넣을까요?") else Step("가까운 응급실을\n경유지로 넣을까요?"),
        Step("병원 가는 길을\n넣어 뒀어요", ui.hosp.chosen?.name),
    )

    /**
     * 응급처치 카드의 걸음. 한 걸음에 한 동작, 그림은 소방청 카드에서 그 동작만 잘라 썼다.
     * 가이드라인과 다른 곳은 가이드라인을 따랐다: 영아 가슴 밀어내기는 한 손 손꿈치(소방청 2024 카드 그림은 손가락이라 그 그림은 쓰지 않음),
     * 인공호흡은 배운 사람만(아니면 가슴압박만), 경련의 119 기준은 가이드라인의 일곱 가지 중 차 밖에서 볼 수 있는 것
     */
    fun aid(kind: Aid) = when (kind) {
        Aid.CPR -> listOf(
            Step("어깨를 두드리며\n“괜찮으세요?”\n크게 부르세요", "대답이 없으면 바로 다음 걸음으로", pic = R.drawable.aid_cpr_1),
            Step("119에 전화하고\n자동심장충격기를\n부탁하세요", "전화는 스피커로 · 숨을 10초 안에 보고, 안 쉬거나 헐떡이면 누르기 · 정상으로 쉬면 곁에서 지켜보기", pic = R.drawable.aid_cpr_2),
            Step("가슴 한가운데에\n두 손을 겹쳐\n대세요", "복장뼈 아래쪽 절반 · 손뒤꿈치로 누르기", pic = R.drawable.aid_cpr_point, pic2 = R.drawable.aid_cpr_hand),
            Step("세게, 빠르게\n30번\n누르세요", "5cm 깊이 · 1분에 100~120번 빠르기", pic = R.drawable.aid_cpr_3),
            Step("인공호흡은\n배운 적이 있을 때만\n2번", "이마를 젖히고 코를 잡고 · 숨 사이엔 코 잡은 손을 놓기 · 아니면 가슴압박만 이어 가세요", pic = R.drawable.aid_cpr_4),
            Step("깨어나거나\n구급대가 올 때까지\n멈추지 마세요", "인공호흡을 하면 30번마다 2번 · 자동심장충격기가 오면 켜고 안내대로", pic = R.drawable.aid_cpr_3),
        )
        Aid.CHOKE -> listOf(
            Step("“목에 뭐가\n걸렸어요?”\n물어보세요", "소리 내 기침을 하면 계속 기침하게 두세요", pic = R.drawable.aid_choke_3),
            Step("기침 소리가 안 나면\n등을 5번\n두드리세요", "말을 못 하거나 숨을 못 쉬면 바로", mark = "등 ×5"),
            Step("그래도 막혀 있으면\n뒤에서\n감싸 안으세요", "내 발 하나를 그 사람 두 발 사이에", pic = R.drawable.aid_choke_2),
            Step("주먹을\n명치 끝과 배꼽 사이에\n대세요", "엄지 쪽이 안으로 가게", pic = R.drawable.aid_choke_1),
            Step("내 머리 쪽으로\n배를 5번\n밀어 올리세요", "깊고 세게 · 임신부나 몸집이 아주 크면 배 대신 가슴을", pic = R.drawable.aid_choke_4),
            Step("나올 때까지\n등 5번, 배 5번을\n되풀이하세요", "정신을 잃으면 눕히고 119에 알린 뒤 심폐소생술", mark = "등 5\n배 5"),
        )
        Aid.INFANT -> listOf(
            Step("앉거나 무릎을 꿇고\n아기를 팔 위에\n엎드려 놓으세요", "손으로 턱을 받치고 · 머리를 몸보다 낮게 · 다리가 한쪽으로 쏠리지 않게", pic = R.drawable.aid_inf_1),
            Step("어깨뼈 사이를\n손뒤꿈치로\n5번 두드리세요", pic = R.drawable.aid_inf_2),
            Step("뒤통수를 받치고\n아기를\n뒤집으세요", "두드린 손바닥으로 뒤통수를 받쳐서", pic = R.drawable.aid_inf_3),
            Step("가슴 가운데를\n한 손 손뒤꿈치로\n5번 누르세요", "두 젖꼭지를 이은 선의 가운데 · 배는 누르지 않아요", mark = "가슴 ×5"),
            Step("나올 때까지\n등 5번, 가슴 5번을\n되풀이하세요", "아기가 축 늘어지면 바로 119 · 상황요원이 다음을 알려 줘요", mark = "등 5\n가슴 5"),
        )
        Aid.SEIZURE -> listOf(
            Step("머리 아래에\n두꺼운 옷이나 방석을\n대 주세요", "억지로 붙잡지 마세요", pic = R.drawable.aid_sz_1),
            Step("안경과 틀니를 빼고\n고개를 옆으로\n돌려 주세요", "입 안에는 아무것도 넣지 마세요", pic = R.drawable.aid_sz_2),
            Step("사람들이\n보지 않게\n가려 주세요", "주변을 비키게 하고 · 치마를 입었으면 덮어 주기", pic = R.drawable.aid_sz_3),
            Step("옆으로 눕혀\n편히 숨 쉬게\n해 주세요", "깨어날 때까지 곁에 있어 주세요", pic = R.drawable.aid_sz_4),
            Step("이럴 땐\n바로 119", "5분 넘게 이어짐 · 또 함 · 처음 하는 경련 · 숨쉬기가 힘듦 · 다침 · 멈춘 뒤에도 깨지 않음 · 임신 중이거나 6개월 안 된 아기 · 정신이 없고 숨이 이상하면 심폐소생술", mark = "119"),
        )
    }

    fun steps(ui: StageUi) = when {
        ui.scene == Scene.AID -> aid(ui.aid)
        ui.scene == Scene.ILLNESS -> illness(ui)
        ui.fire -> if (ui.care.electric == true) FIRE_EV else FIRE
        ui.breakdown && ui.highway -> BREAK_HW
        ui.breakdown -> BREAK_RD
        // 119에 말할 위치가 둘이면(고속도로인지 애매) 119 걸음 설명만 '둘 중 맞는 곳을'로
        ui.highway -> if (placeBoth(ui.place)) HIGHWAY_BOTH else HIGHWAY
        ui.minor -> if (ui.others || ui.baby) MINOR_TOGETHER else MINOR
        else -> ROAD
    }
}

/**
 * 화면 상태 한 곳. 차의 값(기어, 운전 시간, 기온)은 운행 서비스(RideService)가 넣고,
 * 장면은 화면 버튼·들은 말·시연 명령이 바꾼다.
 * 들은 말은 낱말 규칙으로 할 일만 고르고, 글자 자체는 기록하지 않는다.
 */
object Stage {
    val ui = MutableStateFlow(StageUi())
    private val main = Handler(Looper.getMainLooper())
    private var toastClear: Runnable? = null

    /** 운행 서비스가 차의 값을 넣는다(시연 값을 띄우는 중이면 그대로 둔다) */
    fun car(gear: Int, outsideC: Float?, driveMinutes: Int, heat: Int?, vent: Int?, others: Boolean = false, uxLimited: Boolean = false) = ui.update {
        if (it.demo) it else it.copy(gear = gear, uxLimited = uxLimited, outsideC = outsideC?.let { c -> kotlin.math.round(c).toInt() }, driveMinutes = driveMinutes, heat = heat, vent = vent, others = others)
    }

    /** 시연·촬영: 정 과장의 하루 값(세워 둠, 1시간 12분, 영하 4도, 열선 켜짐). 끄면 운행 서비스가 다음 1초에 실제 값으로 되돌린다 */
    fun demo(on: Boolean) = ui.update {
        if (on) it.copy(demo = true, gear = VehicleGear.GEAR_PARK, uxLimited = false, driveMinutes = 72, outsideC = -4, heat = 1, vent = 0)
        else it.copy(demo = false)
    }

    /** 운행 서비스가 붙이는 운전석 열선·통풍 요청(warm·cool·heat_off·vent_off). 한 일 한 줄은 알림으로 */
    var onSeat: ((kind: String) -> Unit)? = null
    /** 동승자의 춥다·덥다를 그 자리로(ride/Care가 붙인다). kind는 warm·cool, who는 front·rear·kid·adult */
    var onSeatAt: ((kind: String, who: String) -> Unit)? = null
    /** "애가 멀미해"(ride/Care가 붙인다): 바깥 공기·얼굴 쪽 바람·곡 한 단 낮추기 */
    var onMotion: (() -> Unit)? = null
    /** "이 노래 뭐야?"(운행 서비스가 붙인다). before면 바로 전 곡("아까 그 노래") */
    var onSong: ((before: Boolean) -> Unit)? = null
    /** "되돌려"·"원래대로"(ride/Care가 붙인다): 기억대로 알아서 한 일을 3분 안에 되돌린다 */
    var onUndo: (() -> Unit)? = null

    /** 귀(Pleos STT)와 차임은 화면이 붙인다 */
    var ears: (() -> Unit)? = null
    var stopEars: (() -> Unit)? = null
    var chime: (() -> Unit)? = null
    /** PREND가 하던 말을 끊는다([프렌드]를 눌렀을 때). 운행 서비스가 붙인다 */
    var hush: (() -> Unit)? = null
    /** 생성형 AI 대역: (들은 말, 세워 둠) → (할 일 이름 또는 null, 아픈 사람이 아이인가). 운행 서비스가 붙인다 */
    var askAi: ((String, Boolean, (String?, Boolean?) -> Unit) -> Unit)? = null

    fun go(scene: Scene, step: Int = 1) {
        Prend.log("· 장면: ${scene.name}")
        // 다른 화면에서 띄운 확인 카드(Q_)는 급할 때 화면으로 들어갈 때 거둔다: 남아 있으면 SOS 띠와 배너의 걸음을 가리고, 그 [열기]가 걸음을 1로 돌려 안내 목소리가 멈췄다
        // (11/08 오류 점검 A1). 같은 장면 안에서 띄운 카드(아플 때의 '누가 아프세요?')는 그대로 둔다. 거둔 카드는 30초 끝에 '대답 없음'으로 끝나 아무것도 바꾸지 않는다
        if (scene.isE && scene != ui.value.scene && ui.value.ask?.id?.startsWith("Q_") == true) { Prend.log("· 급할 때 화면으로 옮기며 확인 카드 거둠"); ask(null) }
        // 듣는 중에 E 밖으로 옮기면 듣기 화면만 지우던 탓에 귀(Pleos 음성 인식)가 닫히지 않고 계속 들었다(긴급 화면을 "괜찮으세요?" 대답 중에 닫음, 11/03 시험 25)
        if (!scene.isE && ui.value.listen?.done == false) {
            answerTo = null
            stopEars?.invoke()
            Prend.log("· 화면을 옮기며 듣기를 닫음")
        }
        // E를 벗어나면 아이 이야기와 찾아 둔 병원, 세우면 열 카드, 119에 말할 위치, SOS 띠도 비운다(다음에 다시 찾는다. 지난 위치가 다음 사고 메모에 적히지 않게)
        ui.update {
            it.copy(scene = scene, step = step, listen = if (scene.isE) it.listen else null,
                kid = scene.isE && it.kid, illOther = if (scene.isE) it.illOther else null, driverIll = scene.isE && it.driverIll,
                // 고장은 사고 화면으로 들어갈 때만 켠다(E 첫 화면·다른 E로 가면 끈다)
                breakdown = scene == Scene.ACCIDENT && it.breakdown, fire = scene == Scene.ACCIDENT && it.fire,
                hosp = if (scene.isE) it.hosp else Hosp(), aidWant = if (scene.isE) it.aidWant else null,
                place = if (scene.isE) it.place else null, sos = scene.isE && it.sos, about = false, memoMore = false, insurerPick = false,
                okAsk = scene.isE && it.okAsk, callWait = scene.isE && it.callWait,
                // AI 대역의 짐작 표시도 E를 벗어나면 거둔다(검토 11/07 멋대로 판단)
                guess = if (scene.isE) it.guess else null,
                // 구조 요청 표시는 같은 장면에 머물 때만 남긴다(E를 닫았다 다시 열면 잠깐 '운전자가 대답하지 않아요'가 떴다, 검토 11/05)
                rescue = scene == it.scene && it.rescue)
        }
    }

    /** PREND 화면의 Emergency: E 첫 화면을 열며 바로 듣는다(접수양식 '버튼을 누른 뒤 몇 초는 말로'). 이미 듣는 중이면 그 듣기를 잇는다 */
    fun emergency() {
        // PREND가 물은 것의 대답 창(5초)이 열려 있으면 닫는다: E에서 한 말("사고 났어")이 앞 물음의 대답으로 가지 않게
        if (answerTo != null) endListen()
        go(Scene.EMERGENCY)
        call()
    }

    /** 응급처치 카드를 연다. 세워 둔 동안만(달리는 동안은 운전자에게 화면을 맡기지 않는다) */
    fun openAid(kind: Aid) {
        if (!ui.value.parked && !ui.value.demo) { toast("세우면 ${kind.label} 카드를 보여 드려요", 3_500); return }
        Prend.log("· 응급처치 카드: ${kind.name}")
        // 같은 카드를 30분 안에 다시 열면(병원 찾기에 다녀옴·"경련 멈췄어"·달리며 말한 뒤 세움) 잰 시간을 잇는다
        val now = System.currentTimeMillis()
        val keep = ui.value.let { it.aid == kind && it.aidSince > 0 && now - it.aidSince < 30 * 60_000L }
        // 이미 같은 카드를 보고 있으면 걸음을 처음으로 돌리지 않는다("숨을 안 쉬어"를 다시 말하면 걸음이 1로 돌아갔다, 11/08 오류 점검 A3)
        val same = ui.value.scene == Scene.AID && ui.value.aid == kind
        val since = when {
            keep -> ui.value.aidSince
            // 경련 → 심폐소생술 → 경련: 경련 시계는 처음부터 다시 재지 않고 잇는다(11/08 사용자 결정)
            kind == Aid.SEIZURE -> szClock(now)
            else -> now
        }
        ui.update { it.copy(aid = kind, aidSince = since, aidWant = null, szSince = if (kind == Aid.SEIZURE) since else it.szSince) }
        if (same) { Prend.log("· 이미 같은 응급처치 카드 → 걸음 그대로"); return }
        go(Scene.AID)
    }

    /** 경련 카드의 [다시 재기]: 지금부터 다시 잰다 */
    fun aidRestart() = System.currentTimeMillis().let { now -> ui.update { it.copy(aidSince = now, szSince = if (it.aid == Aid.SEIZURE) now else it.szSince) } }

    /** 30분 안에 잰 경련 시계가 있으면 그 시작 시각, 없으면 지금 */
    private fun szClock(now: Long) = ui.value.szSince.takeIf { it > 0 && now - it < 30 * 60_000L } ?: now

    /** 같이 고르기(운행 서비스가 붙인다). what: open(kind)·kind(kind)·choose(번호)·go·retry */
    var onPick: ((what: String, arg: String) -> Unit)? = null

    /** 같이 고르기를 연다. 세워 둔 동안만(달리는 동안은 차 화면의 긴 목록이 막힌다) */
    fun openPick(kind: String = "food") {
        if (!ui.value.parked) { toast("세우면 같이 골라요"); return }
        go(Scene.PICK)
        onPick?.invoke("open", kind) ?: toast("밥집·카페 찾기는 운행 서비스가 있어야 해요")
    }

    fun pick(what: String, arg: String = "") { onPick?.invoke(what, arg) }

    /** 차 돌봄(운행 서비스가 붙인다, ride/CarCare). what: open(항목)·item(항목)·list·shops·choose(번호)·go·done(항목)·done_said(항목)·answer(yes·no) */
    var onCare: ((what: String, arg: String) -> Unit)? = null
    /** 정비소에 간 날(ride/ShopVisit): "done"(오늘 정비했어)·"again"(고친 데가 또 이상해) */
    var onShop: ((String) -> Unit)? = null

    /** 연기·불 안내 중 "사고 났어"(ride/Crash가 붙인다): 대피 안내는 그대로 두고 괜찮은지 묻는다 */
    var onAccidentSaid: (() -> Unit)? = null

    /** 고장 안내를 연다("차가 고장 났어"·고속도로 정차 카드의 [고장 났어요]) */
    fun breakdown() { ui.update { it.copy(breakdown = true, fire = false) }; go(Scene.ACCIDENT) }
    /** 연기·타는 냄새 안내를 연다 */
    fun fire() { ui.update { it.copy(fire = true, breakdown = false) }; go(Scene.ACCIDENT) }
    /** "견인차 왔어": 사고·고장 화면이면 부르지 않은 견인차 걸음으로, 아니면 한 줄로 */
    fun towCame() {
        if (ui.value.scene == Scene.ACCIDENT && !ui.value.rescue && !ui.value.fire) { val n = Guide.steps(ui.value).size; ui.update { it.copy(step = n) }; Prend.log("· 견인차 왔어 → 사고·고장 화면 마지막 걸음"); return }
        toast("부르지 않은 견인차는 보내도 돼요 · 내가 허락해야 끌고 가요 · 맡기려면 목적지까지 총요금이 적힌 구난동의서를 보고 서명하세요", 8_000)
    }

    /** 차 돌봄을 연다. 세워 둔 동안만(긴 글). key를 주면 그 항목을 펼친다 */
    fun openCare(key: String = "") {
        if (!ui.value.parked) { toast("세우면 차 정비를 보여 드릴게요"); return }
        go(Scene.CARE)
        onCare?.invoke("open", key) ?: toast("차 정비는 운행 서비스가 있어야 해요")
    }

    fun care(what: String, arg: String = "") { onCare?.invoke(what, arg) }

    /** 묻는 말 줄 밖의 카드 대답 받기(id → 대답). ride/AlleyDest가 붙인다 */
    val sideAsks = java.util.concurrent.ConcurrentHashMap<String, (Boolean) -> Unit>()

    /**
     * 애매한 말에 한 번 묻는 카드(검토 11/07 '멋대로 판단'): 짐작으로 움직이지 않고 단추나 다음 한마디로 확인받는다.
     * 말 대답은 분명한 예·아니오만 받는다. 되물은 "네?"·"어" 한마디면 한 번 더 듣고, 예도 아니오도 아닌 말이면 카드는 두고 그 말을 [프렌드]에 한 말처럼 고른다.
     * 대답이 없으면 done(null): 아무것도 바꾸지 않는다. id는 "Q_"로 시작한다(ride/Care가 이 카드가 떠 있는 동안 제 물음을 미룬다).
     * pick이 있으면 예·아니오 대신 두 갈래를 고르는 카드다: 첫째 낱말 목록이면 [yes], 둘째면 [no]("응"·"아니"만으로는 정하지 않는다).
     */
    fun confirm(
        id: String, title: String, text: String, yes: String, no: String,
        listen: Boolean = true, timeoutMs: Long = 30_000, pick: Pair<List<String>, List<String>>? = null,
        done: (Boolean?) -> Unit,
    ) {
        val key = if (id.startsWith("Q_")) id else "Q_$id"
        var settled = false
        val card = Ask(key, title, text, yes, no)
        var handler: ((Boolean) -> Unit)? = null
        val finish: (Boolean?) -> Unit = { a ->
            if (!settled) {
                settled = true
                // 같은 id의 카드를 다시 띄웠으면 앞 카드의 시간 끝이 새 카드와 그 대답 받기를 거두지 않게 제 것만 지운다
                handler?.let { sideAsks.remove(key, it) }
                if (pick != null) confirmPicks.remove(key, pick)
                if (ui.value.ask === card) ask(null)
                Prend.log("· 확인 카드 ${key.removePrefix("Q_")}: ${when (a) { true -> yes; false -> no; null -> "대답 없음" }}")
                done(a)
            }
        }
        val h: (Boolean) -> Unit = { y -> finish(y) }
        handler = h
        sideAsks[key] = h
        if (pick != null) confirmPicks[key] = pick else confirmPicks.remove(key)
        ask(card)
        Prend.log("◆ 확인 카드 ${key.removePrefix("Q_")}")
        // 시간이 지나거나 다른 카드에 밀려 사라졌으면 대답이 없던 것으로
        main.postDelayed({ finish(null) }, timeoutMs)
        if (listen) hearConfirm(key, yes, no, pick, finish, again = true)
    }

    private fun hearConfirm(key: String, yes: String, no: String, pick: Pair<List<String>, List<String>>?, finish: (Boolean?) -> Unit, again: Boolean) {
        call(answer = { t ->
            if (ui.value.ask?.id != key) return@call
            val s = t.replace(" ", "")
            // 목소리 없이 뜬 카드라 "뭐라고?"에 지난 말을 다시 틀지 않고 "네?"처럼 한 번 더 듣는다(11/08 오류 점검 A6)
            val unsure = app.prend.ride.Answer.unsure(t) || asksAgain(t)
            var a = when {
                t.isBlank() || unsure -> null
                pick != null -> pickOf(pick, t)
                else -> confirmAnswer(key, t)
            }
            // 카드의 단추 글을 소리 내어 읽은 말("네, 대리예요"·"고장 났어요"·"열기")은 그 단추를 누른 것과 같다(11/08 오류 점검 A5)
            if (a == null && !unsure && t.isNotBlank()) a = saidButton(t, yes, no)
            // 급할 때를 묻는 카드에 '아니'가 섞여도 사고·아픈 말이 분명하면("아니 너무 아파"·"아니 진짜 박았어") 아니오로 닫지 않고 [프렌드]에 한 말처럼 그 안내를 연다(원칙 4, 11/08 오류 점검 A2).
            // 그 말을 스스로 부정하면("사고 안 났어"·"아프진 않아") 그대로 아니오
            if (a == false && key in URGENT_CARDS && E_DENY.none { it in s }) {
                said = t
                // route와 같게 "숨 좀 돌리자"는 쉬어 가자는 말로 본다(아픈 말로 읽어 [쉬어 갈래요]를 대답 없음으로 바꾸지 않게, 11/08 오류 점검 A2 검토)
                if (pickIntent(s.replace(BREATHER, "쉬어"), t) in E_INTENTS) { finish(null); route(t); return@call }
            }
            when {
                a != null -> finish(a)
                t.isBlank() -> Unit
                unsure -> if (again) hearConfirm(key, yes, no, pick, finish, again = false)
                else -> route(t)
            }
            // 넉넉히 기다리기(8초)를 켰으면 확인 카드도 그만큼 기다린다(11/08 오류 점검 A11)
        }, windowMs = maxOf(6_000L, answerMs))
    }

    /** 확인 카드에 말로 한 대답(대답 창 안과 대답 창 뒤 10초의 늦은 대답이 같이 쓴다) */
    private fun confirmAnswer(key: String, text: String): Boolean? {
        // 아픈지 묻는 카드에 "안 괜찮아요"·"괜찮지 않아"는 급하다는 말이다('괜찮'을 거절로 읽어 아픈 사람에게 쉼터를 권했다, 11/08 오류 점검 A2)
        if (key in SICK_CARDS && SICK_NOT_OK.any { it in text.replace(" ", "") }) return true
        // "아니, 급해요"는 [급해요]다('아니'만 보고 쉼터를 권했다, 원칙 4). "안 급해"·"급하진 않아"는 그대로 아니오(11/08 오류 점검 A2 검토)
        val s = text.replace(" ", "")
        if (key == "Q_ILL" && "급해" in s && NOT_URGENT.none { it in s } && !toiletAsk(s, text)) return true
        return app.prend.ride.Answer.of(text, CONFIRM_YES, CONFIRM_NO)
    }
    private val SICK_CARDS = setOf("Q_ILL", "Q_MEM")
    /** 급할 때인지 묻는 확인 카드(가벼운 통증·이 차의 사고·기억이 이상함·달리다 섬) */
    private val URGENT_CARDS = setOf("Q_ILL", "Q_ACC", "Q_MEM", "Q_STOPPED")
    private val SICK_NOT_OK = listOf("안괜찮", "괜찮지않", "괜찮지가않", "안괜찬")
    private val NOT_URGENT = listOf("안급", "급하진", "급하지", "급한건아니", "급한거아니", "급한건없", "급하긴")
    /** 사고·아픈 말을 스스로 부정하는 꼴: 확인 카드의 아니오를 뒤집지 않는다 */
    private val E_DENY = listOf("안났", "안부딪", "안박", "안받", "사고없", "사고안", "사고는아니", "사고아니", "무슨사고", "사고는무슨",
        "안아파", "안아프", "안아퍼", "아프진않", "아프지않", "아프진안", "아프지는않", "아픈건아니", "아픈게아니")

    /**
     * 확인 카드의 단추 글을 소리 내어 읽은 말인가: 빈칸·문장부호와 끝 '요'를 떼고 [yes]·[no] 글과 견준다.
     * 앞의 "네,"를 뗀 꼴("대리예요")과 짧은 예 뒤에 붙은 꼴("응 고장 났어요")도 같다. 아니면 null
     */
    private fun saidButton(text: String, yes: String, no: String): Boolean? {
        fun norm(x: String) = x.replace(Regex("[\\s,.!?~]"), "").removeSuffix("요")
        val n = norm(text)
        // 짧은 예("응"·"네") 뒤의 나머지만 단추 글이면 같다("네 에어컨 켜 주세요"는 [켜 주세요]가 아니다)
        val rest = words(text).drop(1).joinToString("").let { if (app.prend.ride.Answer.short(text) && it.isNotEmpty()) norm(it) else null }
        fun hit(label: String): Boolean {
            val core = norm(label.removePrefix("네,"))
            return label.isNotBlank() && core.isNotEmpty() && (n == norm(label) || n == core || rest == core)
        }
        return when { n.isEmpty() -> null; hit(yes) -> true; hit(no) -> false; else -> null }
    }

    /**
     * 두 갈래 카드의 대답: [no] 낱말을 먼저, 그다음 [yes] 낱말. '='로 시작하는 낱말("=나"·"=저")은 그 말 하나로 선 낱말일 때만
     * ("나야"·"저요"는 맞고 "누나가"·"저희"의 글자는 아니다, 11/08: "누나가 아파요"를 운전자로 읽었다)
     */
    fun pickOf(pick: Pair<List<String>, List<String>>, text: String): Boolean? {
        val s = text.replace(" ", "")
        val ws = words(text)
        fun hit(list: List<String>) = list.any { w ->
            if (w.startsWith("=")) w.drop(1).let { one -> ws.any { x -> x == one || x.length == 2 && x[0] == one[0] && x[1] in "는도요야" } }
            else w in s
        }
        return when { hit(pick.second) -> false; hit(pick.first) -> true; else -> null }
    }

    /** 두 갈래를 고르는 확인 카드의 낱말 목록(카드 id → [yes] 낱말, [no] 낱말). 대답 창이 닫힌 뒤의 늦은 대답도 같은 낱말로 가른다 */
    private val confirmPicks = java.util.concurrent.ConcurrentHashMap<String, Pair<List<String>, List<String>>>()

    /** 확인 카드에 말로 한 예(짧은 "응"·"네"는 Answer.short가 본다) */
    private val CONFIRM_YES = listOf("그래", "좋아", "맞아", "맞습", "부탁", "해줘", "해주세요", "열어", "그렇게", "지워")
    /** 확인 카드에 말로 한 아니오 가운데 Answer.NO에 없는 것(사고 메모 카드의 [그냥 둬요]를 소리 내어 읽은 말) */
    private val CONFIRM_NO = listOf("그냥둬", "그냥두", "그대로둬", "그대로두", "남겨")

    /** 기억 화면의 [처음대로]·[모두 잊기](운행 서비스가 붙인다). kind는 NAP·JAM·PICK·KIDS·HOUR·SPOT·DAERI, null이면 모두 */
    var onForget: ((kind: String?) -> Unit)? = null

    /** 기억 화면을 연다. 세워 둔 동안만(달리는 동안은 차 화면의 긴 글이 막힌다) */
    fun openMemo() {
        if (!ui.value.parked) { toast("세우면 설정을 보여 드릴게요"); return }
        go(Scene.MEMO)
    }

    /**
     * 앱 정보(시연 안내·자료 출처·오픈소스 라이선스, 감사 F124·F125). 기억 화면 아래의 조용한 입구로만 연다.
     * 세워 둔 동안만(긴 글). 출발하면 기억 화면과 함께 닫힌다(ride/Care)
     */
    fun openAbout(on: Boolean = true) {
        if (on && !ui.value.parked) { toast("세우면 앱 정보를 보여 드릴게요"); return }
        if (on && ui.value.scene != Scene.MEMO) go(Scene.MEMO)
        ui.update { it.copy(about = on && it.scene == Scene.MEMO, insurerPick = false) }
    }

    /**
     * 기억 화면 안의 '그 밖에'(검토-방향노인초보 2-2): 앞에 내세우지 않는 기억(한 시간·주차 자리·대리 운행·쪽잠·오래 막힐 때·같이 고르기)과
     * 이야기에 비켜 서기를 한 화면에. 기억 화면의 '그 밖에' 판으로 연다. 세워 둔 동안만, 출발하면 기억 화면과 함께 닫힌다
     */
    fun openMemoMore(on: Boolean = true) {
        if (on && !ui.value.parked) { toast("세우면 설정을 보여 드릴게요"); return }
        if (on && ui.value.scene != Scene.MEMO) go(Scene.MEMO)
        ui.update { it.copy(memoMore = on && it.scene == Scene.MEMO, about = false, insurerPick = false) }
    }

    /** 설정 안의 '내 보험사' 고르기를 열고 닫는다(세워 둔 동안만) */
    fun openInsurer(on: Boolean = true) {
        if (on && !ui.value.parked && !ui.value.demo) { toast("세우면 설정을 보여 드릴게요"); return }
        if (on && ui.value.scene != Scene.MEMO) go(Scene.MEMO)
        ui.update { it.copy(insurerPick = on && it.scene == Scene.MEMO, memoMore = false, about = false) }
    }

    fun forget(kind: String?) { onForget?.invoke(kind) ?: toast("배운 것은 운행 서비스가 있어야 지울 수 있어요") }

    /** 이번 운행만의 아이 탑승을 켜고 끈다("아이 탔어"·"아이 내렸어"). 운행 서비스가 소리를 맞춘다 */
    var onBaby: ((Boolean) -> Unit)? = null

    fun baby(on: Boolean) {
        onBaby?.takeIf { !ui.value.demo }?.invoke(on) ?: ui.update { it.copy(baby = on) }
        toast(if (on) "아이 탑승으로 둘게요. 사고가 나면 먼저 알려요" else "아이 탑승을 껐어요")
    }

    /** 가족 정보의 "함께 타는 아이가 있어요"를 체크하고 뺀다(첫 화면·기억 화면). 운행 서비스가 저장한다 */
    var onKids: ((Boolean) -> Unit)? = null

    fun kids(on: Boolean) {
        onKids?.takeIf { !ui.value.demo }?.invoke(on) ?: ui.update { it.copy(kids = on) }
        toast(if (on) "가족 정보에 적어 둘게요. 사고가 나면 뒷좌석을 살피라고 알려 드려요" else "가족 정보에서 아이를 뺐어요")
    }

    /** 대리 운행 켜고 끄기(Care가 붙인다: 기억과 한마디). 없으면 화면만 */
    var onDaeri: ((Boolean) -> Unit)? = null

    fun daeri(on: Boolean) {
        onDaeri?.takeIf { !ui.value.demo }?.invoke(on) ?: ui.update { it.copy(daeri = on) }
        toast(if (on) DAERI_ON else "대리 운행을 껐어요", 3_500)
    }

    /** 대리 운행을 켰을 때의 한 줄(말·버튼·물음의 [네, 대리예요]가 같은 글) */
    const val DAERI_ON = "대리 운행으로 둘게요. 편히 쉬세요, 도착할 때 깨워 드릴게요"

    /** 이야기에 비켜 서기 켜고 끄기(운행 서비스가 저장하고 마이크를 맞춘다) */
    var onTalk: ((Boolean) -> Unit)? = null

    fun talk(on: Boolean) {
        onTalk?.invoke(on) ?: ui.update { it.copy(talkOn = on) }
        toast(if (on) "대화할 땐 음악과 바람 소리를 낮출게요 · 말소리가 들리는지만 재요" else "대화 중엔 소리 낮추기를 껐어요 · 마이크를 쓰지 않아요", 3_500)
    }

    /** 마이크 허락을 묻는다(화면이 붙인다. 이야기에 비켜 서기를 켰는데 허락이 없을 때) */
    var needMic: (() -> Unit)? = null

    /** 바깥 공기(true)·안쪽 공기(false)로 바꾸기, PREND 곡 틀기(true)·멈추기(false)(운행 서비스가 붙인다) */
    var onAir: ((Boolean) -> Unit)? = null
    var onMusic: ((Boolean) -> Unit)? = null
    /** PREND 곡 위의 빗소리 깔기(true)·빼기(false)(운행 서비스가 붙인다) */
    var onRain: ((Boolean) -> Unit)? = null

    /**
     * 조용히 가기 켜고 끄기(ride/Care가 붙인다: 한마디와 줄). 없으면 화면만.
     * asleep은 "애 잔다"처럼 누가 잔다고 한 말(목소리 대신 작은 차임과 글로 받는다), kid는 그 말에 아이가 들었다(이번 운행 아이 탑승도 켠다, 검토 11/06)
     */
    var onQuietRide: ((on: Boolean, asleep: Boolean, kid: Boolean) -> Unit)? = null
    var onAlwaysQuiet: ((Boolean) -> Unit)? = null
    fun alwaysQuiet(on: Boolean) { onAlwaysQuiet?.takeIf { !ui.value.demo }?.invoke(on) ?: ui.update { it.copy(alwaysQuiet = on, quietRide = on) } }

    fun quietRide(on: Boolean, asleep: Boolean = false, kid: Boolean = false) {
        onQuietRide?.takeIf { !ui.value.demo }?.invoke(on, asleep, kid) ?: ui.update { it.copy(quietRide = on, baby = it.baby || kid) }
        toast(when {
            !on -> "다시 먼저 말을 건넬게요"
            kid -> "오늘은 조용히 갈게요 · 아이 탑승으로 둘게요"
            asleep -> "오늘은 조용히 갈게요 · 쉼터와 급한 일만 말해요"
            else -> "오늘은 조용히 모실게요. 쉼터와 급한 일만 말할게요"
        }, 3_500)
    }

    /** 먼저 묻기의 한 물음을 켜고 끈다(운행 서비스가 붙인다: ride/Memory에 적고, 이야기에 비켜 서기는 다시 물을 수 있게 한다) */
    var onAsks: ((key: String, on: Boolean) -> Unit)? = null

    fun asks(key: String, on: Boolean) {
        onAsks?.takeIf { !ui.value.demo }?.invoke(key, on) ?: ui.update { it.copy(asks = if (on) it.asks + key else it.asks - key) }
        val what = ASK_LINES[key]?.second ?: return
        toast(if (on) what else "이제 먼저 묻지 않을게요", 3_000)
    }

    /** 먼저 묻기의 물음 이름과 켰을 때의 한 줄(기억 화면의 줄 이름도 이것) */
    val ASK_LINES = linkedMapOf(
        "HOUR" to ("한 시간 달리면 필요한 것" to "한 시간 달리면 필요한 것을 여쭐게요"),
        "DAERI" to ("밤에 대리 운행인지" to "밤에 동승자가 타면 대리 운행인지 여쭐게요"),
        "TALK" to ("동승자가 타면 소리 낮출지" to "동승자가 타면 대화 중엔 소리를 낮출지 여쭐게요"),
        "JAM" to ("오래 막히면 분위기 바꿀지" to "오래 막히면 차 안 분위기를 바꿀지 여쭐게요"),
        "PICK" to ("밥때 밥집·카페 고를지" to "밥때 같이 세우면 먹을 곳을 같이 고를지 여쭐게요"),
    )

    /** 위치 보내기를 켜고 끈다(기억 화면, ride/GeoShare) */
    fun geo(on: Boolean) {
        if (ui.value.demo) { ui.update { it.copy(geoSend = on) }; return }
        app.prend.ride.GeoShare.set(on, "기억 화면")
        toast(if (on) "골목·병원·화장실·날씨를 찾을 때 위치를 보내요 · 저장하지 않아요" else "위치를 보내지 않아요 · 병원은 앱 안 목록으로 찾아요", 3_500)
    }

    /** 허락 설정 화면을 연다(화면이 붙인다). what은 speed(차 속도)·notif(알림) */
    var openSettings: ((what: String) -> Unit)? = null

    /** 먼저 건네는 말 하나를 그만두기(SPOT: "주차 자리 그만 알려 줘", ride/Care가 붙인다) */
    var onQuiet: ((String) -> Unit)? = null

    /** 초보 운전을 돕는 말 요청(ride/Coach가 붙인다): park(주차 도와줘)·park_off(주차 코치 꺼)·fuel(주유구·유종)·toll(하이패스)·fog(김 서림)·fog_off(성에 제거 꺼) */
    var onCoach: ((String) -> Unit)? = null

    /** 운전 도움(옛 초보 운전) 켜고 끄기(운행 서비스가 저장한다). 11/02 사용자: 이름만 바꾸고, 본인이 켜고 익숙해지면 본인이 끈다 */
    var onNovice: ((Boolean) -> Unit)? = null
    /** "밤 운전 불편해": 해 지기 전에 알려 주기를 켠다(ride/Dusk) */
    var onDusk: (() -> Unit)? = null

    fun novice(on: Boolean) {
        onNovice?.invoke(on) ?: ui.update { it.copy(novice = on) }
        toast(if (on) "운전 도움을 켤게요. 달리는 동안은 꼭 필요한 말만 할게요" else "운전 도움을 껐어요", 3_500)
    }

    /** 내 보험사 고르기·사고 메모 지우기(운행 서비스가 저장한다) */
    var onInsurer: ((String?) -> Unit)? = null
    var onNoteDone: (() -> Unit)? = null

    fun insurer(name: String?) { onInsurer?.invoke(name) ?: ui.update { it.copy(insurer = name) } }
    fun noteDone() { onNoteDone?.invoke() ?: ui.update { it.copy(note = null) }; toast("사고 메모를 지웠어요") }

    /** 전화 걸기 화면을 연다(화면이 붙인다. 차에 연결된 폰으로 건다) */
    var dial: ((String) -> Unit)? = null

    fun dial(tel: String) { dial?.invoke(tel) ?: toast("전화는 폰으로 걸어 주세요 · $tel", 4_000) }

    /** 구조용 화면에서 [괜찮아요]: 사고 안내 걸음으로 돌아간다 */
    fun rescueOff() = ui.update { it.copy(rescue = false) }

    /**
     * 걸음 위 물음 띠의 [괜찮아요]·[다쳤어요]·[아파요]: 그 말을 들은 것처럼 대답 창에 넘긴다(ride/Crash가 물은 말에 따라 가른다).
     * 음성 인식이 막히거나 시끄러워 말이 잡히지 않아도 대답할 수 있게(검토 11/06). 대답 창이 이미 닫혔으면 버린다
     */
    fun okAnswer(text: String) {
        if (!ui.value.okAsk || answerTo == null || ui.value.listen?.done != false) return
        Prend.log("· 물음 띠 단추로 대답(${text.length}자)")
        finishListen(text)
    }

    /** 사고 걸음의 [안 다쳤어요](ride/Crash가 붙인다): 대답하지 못해 SOS로 간 안내를 늦게 바로잡는다 */
    var onNotHurt: (() -> Unit)? = null

    fun notHurt() { onNotHurt?.takeIf { !ui.value.demo }?.invoke() ?: ui.update { it.copy(sos = false) } }

    /** 가벼운 사고 순서의 [다쳤어요](ride/Crash가 붙인다): 다친 것을 늦게 알았다. 없으면(시연) 띠만 */
    var onHurtLate: (() -> Unit)? = null

    fun hurtLate() { onHurtLate?.takeIf { !ui.value.demo }?.invoke() ?: ui.update { it.copy(minor = false, sos = true) } }

    /**
     * 사고 안내 중 [프렌드]로 한 "다리가 아파요"·"다쳤어요"(ride/Crash가 붙인다, 검토 11/07 멋대로 판단): 아플 때 안내로 갈아타지 않고 [다쳤어요]를 누른 것처럼 받는다.
     * sure가 아니면 다친 말이 있을 때만 받는다. 받았으면 true(화면을 바꾸지 않는다)
     */
    var onHurtSaid: ((s: String, sure: Boolean) -> Boolean)? = null
    /** 아플 때 안내 중 [프렌드]로 한 말에 같이 탄 사람 이야기(ride/Crash가 붙인다): 운전자가 아프다고 한 뒤면 바로 바꾸지 않고 화면으로 묻는다. 받았으면 true */
    var onWhoSaid: ((kid: Boolean) -> Boolean)? = null

    /** 짐작 줄의 [아니에요](ride/Crash가 붙인다): 목소리를 멈추고 사고 메모를 남기지 않는다 */
    var onGuessWrong: (() -> Unit)? = null
    /** 사람이 사고·아플 때 걸음의 단추([다음]·[119 전화]·보험사)를 눌렀다(ride/Crash가 붙인다): 쓴 안내로 센다(사고 메모를 남긴다) */
    var onGuideTouch: (() -> Unit)? = null

    /**
     * E 걸음 맨 위 '‘사고’로 알아들었어요'의 [아니에요](검토 11/07 멋대로 판단): AI 대역이 짐작으로 연 안내를 거둔다.
     * 목소리를 멈추고(사고 메모는 남기지 않는다) 첫 화면으로 돌아가 다시 말해 달라고 한다
     */
    fun guessWrong() {
        Prend.log("· 짐작 줄 [아니에요]: '${ui.value.guess ?: "없음"}' 아님 → 안내를 거두고 첫 화면")
        onGuessWrong?.invoke()
        clearGuess()
        go(Scene.HOME)
        toast("다시 말씀해 주세요")
    }

    /** 짐작 줄을 거둔다: 20초가 지났거나 사람이 대답·단추로 그 안내가 맞다고 했다 */
    fun clearGuess() { if (ui.value.guess != null) ui.update { it.copy(guess = null) } }

    /** 사고·아플 때 걸음의 단추를 눌렀다(화면이 부른다): 짐작 줄을 거두고 쓴 안내로 센다 */
    fun guideTouched() { clearGuess(); onGuideTouch?.takeIf { !ui.value.demo }?.invoke() }

    /** [갑자기 아파요]를 누른 때(ride/Crash가 본다): 말없이 단추만 눌렀고 혼자일 때만 운전자가 아픈 것으로 본다(검토 11/07 멋대로 판단, 기획안) */
    @Volatile var illPressedAt = 0L

    /** E 첫 화면의 [갑자기 아파요] */
    fun illButton() { illPressedAt = SystemClock.elapsedRealtime(); go(Scene.ILLNESS) }

    /** 아플 때 화면의 [달빛어린이병원]·[응급실](운행 서비스의 Crash가 붙인다). 없으면(시연) 걸음만 넘긴다 */
    var onHosp: ((kind: String) -> Unit)? = null

    fun hospital(kind: String) { onHosp?.takeIf { !ui.value.demo }?.invoke(kind) ?: step(1, 3) }

    /** 아플 때 둘째 걸음의 [다시 찾기]: 병원을 찾지 못했을 때(인터넷이 안 됨·위치를 모름) 다시 찾는다 */
    fun hospitalAgain() { onHosp?.invoke("again") }

    fun step(delta: Int, max: Int) = ui.update { it.copy(step = (it.step + delta).coerceIn(1, max)) }

    /** 쪽잠과 잘래요(운행 서비스의 ride/Nap이 붙인다). what: start(분)·more·woke·stop·sleep_on·sleep_off */
    var onNap: ((what: String, min: Int) -> Unit)? = null

    /** 쪽잠을 시작한다(세워 둔 동안만). 운행 서비스가 없으면(시연) 화면만 */
    fun napStart(min: Int) {
        if (!ui.value.parked && !ui.value.demo) { toast("세우면 쪽잠을 챙겨 드릴게요"); return }
        // 대리 운행이면 운전석은 기사님 자리라 쪽잠 대신 주인 자리의 잘래요(별첨 2 §5-9)
        if (ui.value.daeri && !ui.value.demo) { sleep(true); return }
        onNap?.takeIf { !ui.value.demo }?.invoke("start", min) ?: run {
            Prend.log("◆ 쪽잠 ${min}분(화면만)")
            ui.update { it.copy(scene = Scene.NAP, napMinutes = min, napEndAt = System.currentTimeMillis() + min * 60_000L, napPhase = NapPhase.SLEEP) }
        }
    }

    /** [그만 쉬기]: 깨우기 전이면 바로 끝내고, 깬 뒤면 3분 기다림을 건너뛴다 */
    fun napStop() {
        onNap?.takeIf { !ui.value.demo }?.invoke("stop", 0) ?: run {
            Prend.log("◆ 쪽잠 끝(화면만)")
            ui.update { it.copy(scene = Scene.HOME, napEndAt = 0L, napPhase = null) }
        }
    }

    /** 깨우는 중 [일어났어요]·[5분만 더] */
    fun napWoke() { onNap?.invoke("woke", 0) ?: napStop() }
    fun napMore() { onNap?.invoke("more", 5) }

    /** 잘래요: 달리는 중 동승자가 쉰다(말·버튼). 운행 서비스가 없으면 알림만 */
    fun sleep(on: Boolean) { onNap?.invoke(if (on) "sleep_on" else "sleep_off", 0) ?: toast(if (on) "편히 쉬세요" else "잘래요를 껐어요") }

    fun toast(text: String, ms: Long = 2600) {
        toastClear?.let { main.removeCallbacks(it) }
        ui.update { it.copy(toast = text) }
        val clear = Runnable { ui.update { it.copy(toast = null) } }
        toastClear = clear
        main.postDelayed(clear, ms)
    }

    /** 운행 서비스가 띄운 알림 중 이 글이 아직 떠 있으면 거둔다("가까운 화장실을 찾고 있어요") */
    fun clearToastIf(text: String) = clearToast(text)

    /** 이 알림이 아직 떠 있으면 바로 거둔다(다른 알림으로 바뀌었으면 그대로 둔다) */
    private fun clearToast(text: String) {
        if (ui.value.toast != text) return
        toastClear?.let { main.removeCallbacks(it) }
        toastClear = null
        ui.update { it.copy(toast = null) }
    }

    // ── [프렌드] 부르기: 화면 아무 데나 1.2초 꾹, 또는 프렌드 캡슐 ──

    private var listenStarted = 0L

    /** PREND가 물은 뒤 대답을 듣는 창. 늦게 대답하는 사람은 8초(ride/SlowTalk, 넉넉히 기다리기) */
    const val ANSWER_MS = 5_000L
    @Volatile var answerMs = ANSWER_MS
    /** "뭐라고?"(ride/SlowTalk): 대답 창 안이었으면 그 창의 대답 받기를 넘긴다 */
    var onAgain: ((((String) -> Unit)?) -> Unit)? = null
    /** 대답 창이 말없이 닫힌 뒤 10초 안에 그 카드 버튼으로 대답했다 */
    var onLate: (() -> Unit)? = null
    /** 대답 창이 닫힌 뒤 10초 안에 [프렌드]로 한 대답 한마디를 말 대답으로 받는다(ride/Care: 단추와 말의 뜻이 다른 사고 뒤 카드). 받았으면 true, 아니면 단추 대답으로 */
    var onLateSaid: ((id: String, text: String) -> Boolean)? = null
    /** "천천히 말해 줘"·"원래대로 말해" */
    var onSlow: ((Boolean) -> Unit)? = null
    /** 기억 화면 '듣고 보기'의 켜고 끄기(운행 서비스가 붙인다). what: slow(천천히 말하기)·wait(넉넉히 기다리기)·dusk(해 지기 전에 알려 주기) */
    var onListen: ((what: String, on: Boolean) -> Unit)? = null

    fun slow(on: Boolean) = listen("slow", on, if (on) "앞으로 천천히 말할게요" else "원래 빠르기로 말할게요")
    fun longWait(on: Boolean) = listen("wait", on, if (on) "대답을 ${app.prend.ride.SlowTalk.LONG_WAIT_MS / 1000}초까지 기다릴게요" else "대답을 ${ANSWER_MS / 1000}초 기다릴게요")
    fun dusk(on: Boolean) = listen("dusk", on, if (on) "도착이 해 진 뒤면 출발 전에 알려 드릴게요" else "해 지는 시각은 이제 알리지 않을게요")

    /** 운행 서비스가 없으면(시연) 화면만 */
    private fun listen(what: String, on: Boolean, said: String) {
        onListen?.takeIf { !ui.value.demo }?.invoke(what, on)
            ?: ui.update { when (what) { "slow" -> it.copy(slowTalk = on); "wait" -> it.copy(longWait = on); else -> it.copy(dusk = on) } }
        toast(said, 3_000)
    }
    /** 말없이 닫힌 대답 창(때와 그때의 카드) */
    private var missAt = 0L
    private var missId: String? = null

    /** 대답 창이 대답 없이 닫혔다(말이 없었거나 다시 물어도 알아듣지 못함, ride/Care): 10초 안에 그 카드 버튼을 누르면 늦은 대답으로 센다 */
    fun missed() { missAt = SystemClock.elapsedRealtime(); missId = ui.value.ask?.id }

    /** 다시 말해 달라는 말인가. "다시"·"뭐" 한마디는 대답 창 안에서만 */
    private fun again(text: String, inWindow: Boolean): Boolean {
        val s = text.replace(" ", "").trimEnd('?', '.', '!')
        // 아플 때 말("숨소리가 안 들려")과 음악·바람 말("노래가 안 들려")은 다시 말해 달라는 말이 아니다(11/04 검토)
        if (ILL.any { it in s } || listOf("음악", "노래", "볼륨", "바람", "라디오", "숨소리").any { it in s }) return false
        // 쓰러짐·반응 없음·숨·목 막힘·경련 같은 급한 말이 함께면 아니다("애가 반응이 없어, 아무 소리도 안 들려"). '안 들려'는 짧은 말일 때만,
        // "뭐라고"·"다시 말해"처럼 분명히 되묻는 말은 길어도 받는다(검토 11/07 멋대로 판단)
        if (AGAIN_NOT.any { it in s }) return false
        return AGAIN.any { it in s && it !in AGAIN_HEAR } || s.length <= 12 && AGAIN_HEAR.any { it in s } ||
            inWindow && s in setOf("다시", "다시요", "뭐", "뭐요", "뭐라", "네에")
    }
    /** 급한 말: 함께 들리면 '안 들려'가 있어도 다시 말해 달라는 말이 아니다("피곤"의 '피'는 아니다) */
    private val AGAIN_NOT = listOf("쓰러", "반응이없", "반응없", "숨", "목에걸", "경련", "의식", "피가", "피나", "피를", "출혈", "아파")
    /** '안 들려'·'못 들었어' 꼴: 짧은 말일 때만 되묻는 말로 본다(나머지 AGAIN은 "뭐라고"·"다시 말해"처럼 분명한 되물음) */
    private val AGAIN_HEAR = listOf("못들었", "잘안들려", "안들려", "안들렸", "잘못들었", "안들린", "안들리")

    /** 급할 때 안내가 물은 뒤의 대답이 "뭐라고?"인가(ride/Crash가 한 번 천천히 다시 묻는다, 11/05) */
    fun asksAgain(text: String) = again(text, inWindow = true)
    private val AGAIN = listOf("뭐라고", "뭐라구", "뭐라는", "뭐라했", "다시말해", "다시한번말", "한번더말", "다시말씀", "못들었", "잘안들려", "안들려", "안들렸", "잘못들었",
        // 사투리·어르신 말버릇(11/04 사람 시험: "니 뭐라카노? 크게 좀 말해 봐라"가 대답으로 넘어가 다시 묻기를 들었다)
        "뭐라카", "머라카", "뭐라꼬", "머라꼬", "뭐라캐", "크게말", "크게좀말", "안들린", "안들리")
    /** PREND가 물은 뒤의 대답을 기다리는 중이면 들은 말을 여기로 넘긴다(아니면 프렌드 요청으로 고른다) */
    private var answerTo: ((String) -> Unit)? = null

    /** 지금 듣는 것이 PREND가 물은 뒤의 대답인가([프렌드]를 부른 듣기와 가리려고. ride/Care가 대답 창을 닫을 때 본다) */
    val answering get() = answerTo != null && ui.value.listen?.done == false
    private val OFF = listOf("꺼", "끄", "그만")
    private val CHILD = listOf("아이", "애기", "아기", "우리애", "애가", "딸", "아들")
    /** 조용히 가자는 말 가운데 누가 잔다는 말("애 잔다"·"자고 있어"), 그 말의 아이 낱말(CHILD에 없는 "애 잔다"·"애들 자요") */
    private val ASLEEP = listOf("잔다", "자요", "자니까", "자고있", "자거든", "잠들", "애자")
    private val KID_ASLEEP = listOf("애잔", "애자", "애들")
    private val BABY = listOf("아기", "애기", "갓난", "돌전", "신생아")
    /** 쓰러짐·반응 없음은 일어난 일만("기절할 것 같아"·"심장이 아파"는 아니다) */
    private val CPR = listOf("숨을안쉬", "숨안쉬", "쓰러졌", "쓰러져있", "의식이없", "반응이없", "정신을잃었", "정신잃었", "기절했", "심장이멈", "심장이안뛰", "맥이없")
    /** 경련 낱말이어도 경련이 아닌 것(다리 쥐, 추위 떨림, 공황발작) */
    private val NOT_SEIZURE = listOf("공황", "다리", "종아리", "쥐가", "쥐났", "근육")
    /** 추위 떨림("추워서 몸을 떨어"). "경련"·"발작"이라고 분명히 말하면 함께 있어도 경련으로 본다("애가 경련해, 추워 보여") */
    private val COLD_SHAKE = listOf("추워", "춥", "오한")
    private val AIR = listOf("바깥공기", "환기", "공기바꿔", "공기좀", "외기")
    /** 차 돌봄 항목 낱말(ride/CareBook의 key). 앞의 것부터 본다("에어컨 필터"가 다른 필터로 잡히지 않게) */
    private val CARE_KEYS = listOf(
        "cabin" to listOf("에어컨필터", "실내필터", "항균필터", "공조필터", "에어컨냄새", "에어컨에서냄새"),
        "air" to listOf("에어클리너", "엔진필터", "공기필터"),
        "oil" to listOf("엔진오일", "오일교환", "오일갈", "엔진기름"),
        "wiper" to listOf("와이퍼"),
        "tire" to listOf("타이어", "펑크", "빵꾸", "공기압", "바람빠"),
        "brake" to listOf("브레이크패드", "브레이크액", "브레이크오일", "패드교환", "패드갈", "브레이크소리", "브레이크에서소리"),
        "battery" to listOf("배터리방전", "방전", "배터리교체", "배터리갈", "보조배터리", "12볼트"),
        "washer" to listOf("워셔액", "워셔"),
    )
    private fun careKey(s: String): String? = CARE_KEYS.firstOrNull { (_, w) -> w.any { it in s } }?.first
        // "에어컨 공기필터"는 에어컨 필터
        ?.let { k -> if (k == "air" && listOf("에어컨", "실내").any { it in s }) "cabin" else k }

    /**
     * 그 항목을 갈았다(바꿨다·채웠다)는 말인가. 보충은 교체가 아니다: "엔진오일 채웠어"·"타이어 바람 넣었어"를 교체로 적으면 다음 알림이 밀린다.
     * 채웠다·넣었다는 워셔액만, 타이어는 위치를 바꿨거나 타이어를 갈았을 때만, 배터리는 점검도
     */
    private fun careDone(key: String, s: String): Boolean = when {
        // 묻는 말("엔진오일 언제 갈았지?")·아직 안 했다는 말("와이퍼 아직 안 갈았어")은 적지 않는다: 그 항목의 차 돌봄을 연다(검토 11/07 멋대로 판단 원칙 3)
        CARE_ASK.any { it in s } -> false
        key == "washer" -> listOf("넣었", "채웠", "보충했").any { it in s }
        key == "tire" -> "위치" in s && listOf("바꿨", "교환했").any { it in s } || listOf("갈았", "교체했").any { it in s }
        key == "battery" -> (CARE_DONE + listOf("점검했", "점검받았")).any { it in s }
        else -> CARE_DONE.any { it in s }
    }
    /** 정비 기록을 묻거나 아직 하지 않았다는 말 */
    private val CARE_ASK = listOf("언제", "갈았나", "갈았지", "갈았던가", "갈았었나", "바꿨나", "바꿨지", "바꿨던가", "교체했나", "교체했지", "교환했나", "교환했지",
        "넣었나", "넣었지", "?", "안갈았", "못갈았", "안바꿨", "못바꿨", "안넣었", "못넣었", "안했", "못했", "아직")
    /** 항목 없이 정비 일반("정비 어디서 해?"·"소모품 언제 갈아?") */
    private val CARE_ANY = listOf("차돌봄", "정비", "소모품", "블루핸즈", "카센터", "공업사", "고장났", "고장난", "수리비", "교환주기", "교체주기", "점검받")
    /** 갈았다는 말("와이퍼 갈았어"·"엔진오일 교환했어"). 채웠다·넣었다는 워셔액만(careDone) */
    private val CARE_DONE = listOf("갈았", "교체했", "교환했", "바꿨")
    /** 빗소리("비 소리"로 받아 적을 때도) */
    private val RAIN = listOf("빗소리", "비소리")
    /** 춥다·덥다(높임 "추우시대"·전한 말 "춥대"도) */
    private val COLD = listOf("추워", "춥", "추운", "추우")
    private val HOT = listOf("더워", "덥", "더운", "더우")
    /** 남이 춥다·덥다는 말을 전함("춥대"·"추우시대"·"추워해"): 자리 낱말이 없어도 운전자가 아니다 */
    private val HEARSAY = listOf("춥대", "덥대", "추우시", "더우시", "추우신", "더우신", "춥다고", "덥다고", "추워해", "더워해", "추워하", "더워하")
    /** 동승자 자리 낱말: 옆자리, 뒷자리, 아이, 그 밖의 사람(ride/SeatHelp.seatsFor가 점유와 함께 자리를 정한다) */
    private val FRONT_SEAT = listOf("옆자리", "옆에", "조수석", "동승석", "옆사람")
    private val REAR_SEAT = listOf("뒷자리", "뒷좌석", "뒤에", "뒤쪽", "뒷줄", "뒷사람")
    private val KID = listOf("애가", "애들", "아이", "아기", "애기", "딸", "아들")
    private val ADULT = listOf("엄마", "아빠", "어머니", "아버지", "어머님", "아버님", "할머니", "할아버지", "장모", "장인", "와이프", "아내", "남편", "집사람", "안사람", "마누라", "각시", "할매", "할배", "영감", "신랑", "손님", "친구", "동생", "누나", "언니", "오빠")
    /** 멀미(급할 때가 아니다). 숨·의식·피·쓰러짐·머리 낱말이 함께면 아플 때 안내로 */
    private val MOTION = listOf("멀미", "속이안좋", "속안좋", "토할것같", "토할거같", "울렁", "메스꺼", "메슥")
    private val SEVERE = listOf("의식", "숨", "피가", "피를", "피나", "쓰러", "머리", "경련", "가슴")
    /**
     * "화장실 급해"·"애가 쉬 마렵대"·"오줌 마려워"(11/07, ride/Toilets). "쉬어 가자"는 쉼터라 '쉬' 한 글자는 보지 않는다.
     * '응가'는 빈칸을 지운 말에서 찾으면 대답 "응 가자"를 잡아 따로 본다(toiletAsk)
     */
    private val TOILET = listOf("화장실", "쉬마려", "쉬마렵", "쉬하고", "쉬할래", "쉬해야", "쉬하러", "오줌", "소변", "똥마려", "똥마렵", "똥싸", "대변", "급똥")
    /** 화장실 카드의 대답에서 예로 받을 말("급해"·"빨리"·"화장실"·"못 참아", ride/Care.answerToilet) */
    val TOILET_YES = TOILET + listOf("급해", "급하", "빨리", "마려", "마렵", "못참", "참을수없")
    /** 화장실을 마다하거나 이미 다녀왔다는 말("화장실 괜찮아"·"안 가도 돼"·"다녀왔어"·"안 마려워"·"가기 싫대"·"나중에") */
    private val TOILET_NO = listOf("안가", "안들", "안급", "괜찮", "다녀왔", "갔다왔", "다녀옴", "들렀다왔", "됐어", "됐고", "됐다", "필요없", "참을만", "참을수있", "안마려", "안마렵", "싫", "나중")
    /** 마다하는 말처럼 보여도 급하다는 말("안 가면 안 돼"·"못 참아"·"참을 수 없어"·"다녀왔는데 또 마렵대") */
    private val TOILET_URGE = listOf("안가면", "못참", "참을수없", "또마려", "또마렵", "또가고")
    /** 화장실 낱말이 있어도 아플 때 안내로 갈 말("열이 나는데")과 다른 뜻("화장실 청소") */
    private val TOILET_SICK = listOf("열이", "두근", "심장", "청소")
    /** 소변·대변 증상("소변이 안 나와"·"소변 색이 이상해"·"피 섞여 나와"): 화장실이 아니라 아플 때 안내로 */
    private val URINE = listOf("소변", "오줌", "대변")
    private val URINE_SICK = listOf("안나와", "안나오", "색이", "피섞", "피가나", "피나와", "찔끔")
    /** 목에 걸림·경련 낱말(응급처치 카드. 화장실 갈래도 이 말이 함께면 비킨다) */
    private val CHOKE = listOf("목에걸", "목에뭐", "목이막혀", "목이막혔", "목막혀", "기도가막", "켁켁")
    private val SEIZURE = listOf("경련", "발작", "거품을", "몸을떨")
    /** "이 노래 뭐야?"·"무슨 노래야"·"아까 그 노래"(노래 틀어 달라는 말보다 먼저 가른다) */
    private val SONG_ASK = listOf("노래뭐", "곡뭐", "무슨노래", "무슨곡", "뭔노래", "노래제목", "곡제목", "노래이름", "곡이름", "누구노래", "아까그노래", "아까그곡", "아까노래", "방금그노래")
    /** 노래를 틀어 달라는 말("노래 뭐 좀 틀어 줘"·"아까 그 노래 다시 틀어 줘"는 노래 물음이 아니다) */
    private val SONG_PLAY = listOf("틀어", "틀까", "틀자", "켜", "들려", "골라", "추천")
    /** 아플 때 안내로 가는 낱말(멀미 낱말이 함께여도 이쪽이 먼저). 전한 말("엄마가 아프시대"·"아프다고")도 동승자 춥다·덥다보다 먼저 */
    private val ILL = listOf("아파", "아퍼", "아프", "아픈", "열이", "숨을", "숨이", "의식", "토해", "가슴", "두근", "심장")
    /** 알아서 한 일을 되돌리는 말 */
    private val UNDO = listOf("되돌려", "되돌리", "원래대로", "돌려놔", "돌려놓")
    /**
     * 졸음쉼터를 마다하는 말("안 졸려"·"쉼터 안 가도 돼"). 쉼터 카드가 떠 있을 때 "졸려"·"쉼터"는 그 대답("응")으로 받아 경유지를 넣으므로
     * 마다하는 말은 먼저 가른다. REST_NOT은 쉬기 자체를 마다하는 말이라 언제나 보고, REST_NO는 쉼터를 묻는 중일 때만 본다
     * ("졸려, 커피 말고 쉼터 가자"처럼 쉬고 싶은 사람의 말을 놓치지 않게. 같은 까닭으로 '괜찮'은 넣지 않는다). ride/Care.answerRest도 REST_NO를 본다
     */
    private val REST_NOT = listOf("안졸", "안쉬", "안가", "안갈", "안들러", "안들를")
    val REST_NO = REST_NOT + listOf("필요없", "말고", "됐어", "싫어")
    /** 같이 고르기의 번호: 서수와 숫자(받아 적기가 "3번째"로 줄 때도). "이번"은 '이번엔'과 헷갈려 받지 않는다 */
    private val ORDINALS = listOf(
        listOf("첫번째", "첫째", "1번"),
        listOf("두번째", "둘째", "2번"),
        listOf("세번째", "셋째", "3번", "삼번"),
        listOf("네번째", "넷째", "4번"),
        listOf("다섯번째", "다섯째", "5번", "오번"),
    )
    private val PICK_GO = listOf("가자", "가요", "갈래", "넣어", "거기로", "여기로", "그리로")
    /** 고른 곳을 가리키는 말 */
    private val PICK_THERE = listOf("거기로", "여기로", "그리로", "거기가", "여기가", "거기갈", "여기갈")
    /** 생성형 AI 대역의 답을 기다리는 시간(운행 서비스도 이만큼만 기다린다)과 그동안 띄우는 알림 */
    const val AI_WAIT_MS = 6_000L
    private const val THINKING = "알아듣는 중이에요"
    /** 급할 때 화면에서 AI 대역의 답으로 할 일(그 밖의 일은 화면을 바꿔 사고·아플 때 안내를 끊는다). 고장·연기는 route에서 한 번 더 가린다 */
    private val E_INTENTS = setOf("accident", "fire", "breakdown", "tow", "illness", "aid_cpr", "aid_choke", "aid_infant", "aid_seizure")

    /** 아이 이야기인가("아이고"·"아이스"는 아니다) */
    /** 급하다는 말("안 가면 안 돼"·"못 참아"·"급해"). "안 급해"는 아니다(ride/Care.answerToilet도 쓴다) */
    fun toiletUrge(s: String) = TOILET_URGE.any { it in s } || ("급해" in s || "급하" in s) && "안급" !in s

    /** 경련을 말하는가(추워서 떠는 것·다리 쥐는 아니다). 응급처치 갈래와 화장실 갈래가 같은 기준을 쓴다 */
    private fun seizure(s: String) = SEIZURE.any { it in s } && NOT_SEIZURE.none { it in s } &&
        (COLD_SHAKE.none { it in s } || listOf("경련", "발작", "거품을").any { it in s })

    /** 화장실을 청하는 말인가(빈칸 지운 s, 원래 말 text) */
    private fun toiletAsk(s: String, text: String): Boolean =
        (TOILET.any { it in s } || "응가" in text && !Regex("응가[자요]").containsMatchIn(text)) &&
            (SEVERE + CPR + CHOKE + TOILET_SICK).none { it in s } && !seizure(s)

    /** 아이 이야기인가. 신음·감탄사("아이고"·"아이구"·"아이쿠"·"아이씨"·"아이 참"·"에구")와 "아이스"는 지우고 본다(검토 11/07 멋대로 판단) */
    private fun child(s: String) = groan(s).let { t -> CHILD.any { it in t } }

    /** 신음·감탄사를 지운 말(빈칸 지운 말). 같이 탄 아이·엄마를 찾기 전에 쓴다 */
    private fun groan(s: String) = GROAN.replace(s, "")

    /** 급할 때 안내가 물은 "괜찮으세요?"의 대답에서 아픈 사람을 가른다(ride/Crash: 아이·옆 사람 이야기면 운전자는 괜찮음). 들은 말은 남기지 않는다 */
    fun illWhoIn(text: String): Boolean? = illWho(text.replace(" ", ""), null, text.takeIf { ' ' in it.trim() })
    fun childIn(text: String) = child(text.replace(" ", ""))

    /**
     * 불을 부정하는 말을 지운다("연기는 안 나요"·"타는 냄새는 없어요"·"화재는 아니에요"·"연기 나지 않아요", 빈칸 지운 말).
     * 남은 말에서 불 낱말을 찾는다(여기 FIRE_WORDS와 ride/Crash의 대답 낱말이 함께 쓴다, 검토 11/06)
     */
    fun dropFireDenial(s: String) = NOT_FIRE.replace(s, "")
    private val NOT_FIRE = Regex("(불타는냄새|연기냄새|타는냄새|탄내|불냄새|연기|화재|불)(은|는|이|가|도|까진|까지는)?(나지|나진|올라오지|보이지|있지)?(안|않|없|아니|아닌|아냐|전혀|하나도|별로|못)")

    /**
     * 아픈 사람이 같이 탄 사람인가(true)·운전자 자신인가(false)·모름(null). 아이·남을 먼저 본다.
     * 신음("아이구 가슴이야"·"엄마야")과 몸의 자리("머리 뒤에가 아파")는 사람으로 세지 않는다(검토 11/07 멋대로 판단: 혼자 탄 운전자의 신음을 같이 탄 아이로 읽어 병원까지 몰고 가게 했다).
     * text는 빈칸 있는 원래 말(없으면 지금 고르는 말이 같을 때 그것을 쓴다)
     */
    private fun illWho(s: String, kid: Boolean?, text: String? = null): Boolean? {
        val t = BODY_SIDE.replace(groan(s), "")
        return when {
            kid == true || child(t) -> true
            ILL_OTHER.any { it in t } -> true
            selfNamed(t, text ?: said.takeIf { it.replace(" ", "") == s }) -> false
            else -> null
        }
    }

    /**
     * 운전자 자신을 가리켰나: "제가"·"내가"·"저는"·"전"·"나는"·"난"·"내"나 혼자 쓴 "나"·"저"일 때만, 낱말째로 본다
     * ("나아졌어요"·"나았어"·"나중에"·"나와"처럼 '나'로 시작하는 낱말은 운전자가 아니다, 검토 11/07 멋대로 판단). 빈칸 있는 말을 모르면 붙은 꼴만 본다
     */
    private fun selfNamed(t: String, text: String?): Boolean {
        if (ILL_SELF.any { it in t } || SELF_GA.containsMatchIn(t)) return true
        if (text == null) return t.startsWith("나는") || t.startsWith("저는")
        val w = words(text)
        return w.indices.any { i ->
            w[i] in SELF_WORDS && !(w[i] == "저" && w.getOrNull(i + 1)?.let { n -> THAT.any { n.startsWith(it) } } == true)
        }
    }

    /** 빈칸·쉼표로 나눈 낱말 */
    private fun words(text: String) = text.trim().split(Regex("[\\s,.!?~]+")).filter { it.isNotBlank() }

    /** 아픈 사람이 남이라는 말(빈칸 지운 말에서 찾음) */
    private val ILL_OTHER = listOf("엄마", "아빠", "어머니", "아버지", "남편", "아내", "와이프", "할머니", "할아버지", "집사람", "안사람", "마누라", "각시", "할매", "할배", "영감", "신랑", "애엄마", "애아빠", "친구", "동생", "옆사람", "옆자리", "옆에", "누나", "언니", "오빠", "형이", "형님", "손자", "손녀", "장모", "장인", "시어머니", "시아버지", "동료", "이사람", "이분", "손님", "애인", "여자친구", "남자친구", "여친", "남친", "부장", "과장", "사장", "뒤에")
    /** 정비한 곳이 다시 이상하다는 말의 앞부분 */
    private val SHOP_AGAIN = listOf("고친데", "고친곳", "고쳤는데", "수리한데", "수리했는데", "정비한데", "정비했는데", "정비받았는데")
    /** 오늘 정비했다는 말 */
    private val SHOP_DONE = listOf("정비했어", "정비받았", "정비하고왔", "정비맡겼", "수리받았", "수리하고왔", "정비했다")
    /** 연기·타는 냄새·불(빈칸 지운 말에서 찾음) */
    private val FIRE_WORDS = listOf("연기나", "연기가나", "연기가올라", "연기올라", "타는냄새", "탄내", "불났", "불이났", "불붙", "불이붙", "불타", "화재")
    /** 차가 고장 났다는 말(빈칸 지운 말에서 찾음) */
    private val BREAKDOWN = listOf("고장났", "고장이났", "고장난것", "타이어터졌", "타이어가터졌", "펑크", "빵꾸", "기름떨어졌", "기름이떨어", "기름이다떨", "연료가떨어", "배터리나갔", "배터리가나갔", "시동이안", "시동안걸", "시동이꺼졌", "차가안움직")
    /** 운전자 자신이 아프다는 말(붙은 꼴). "내가"·"제가"는 SELF_GA가 본다("안내가"·"문제가"는 아니다) */
    private val ILL_SELF = listOf("나아파", "나너무", "나좀", "저아파", "저너무", "저좀", "내가슴", "내머리", "제가슴", "제머리", "운전하다가", "운전중에", "운전하는데", "운전못")
    private val SELF_GA = Regex("(?<![안문숙형체])(내가|제가)")

    // ── 애매한 말을 가르는 낱말(검토 11/07 멋대로 판단: 말이 분명할 때만 바로 움직이고, 애매하면 카드로 한 번 묻거나 맞는 기능으로 보낸다) ──

    /** 신음·감탄사("아이구 가슴이야"·"아이씨"·"에구"·"엄마야"): 같이 탄 아이·엄마로 세지 않는다. "우리 아이 고열"·"우리 엄마야"는 사람이다 */
    private val GROAN = Regex("(?<!우리|저희|우리집|뒤에|옆에)아이(구|쿠|고(?!열)|씨|참)|에구|아이스|(?<!우리|저희)엄마야")
    /** 몸의 자리("머리 뒤에가 아파"·"목 옆에가"): 뒷자리·옆자리 사람이 아니다(아픈 사람을 가를 때만) */
    private val BODY_SIDE = Regex("(뒤에|옆에)가|(머리|목|등|귀|허리|무릎|어깨|눈)(뒤|옆)에")
    /** 운전자 자신을 가리키는 낱말(낱말째로). 혼자 쓴 '저'가 사람·곳을 가리키면("저 사람"·"저기") 아니다 */
    private val SELF_WORDS = setOf("나", "난", "나는", "내", "저는", "전", "제", "저")
    private val THAT = listOf("사람", "분", "기", "쪽", "거", "차", "아이", "애", "앞", "뒤", "집")
    /** 아픈 말과 함께면 묻지 않고 아플 때 안내를 여는 심한 말("피곤"의 '피'는 아니다) */
    private val ILL_SEVERE = listOf("가슴", "숨", "의식", "쓰러", "정신", "피가", "피를", "피나", "피흘", "출혈", "경련", "마비", "식은땀", "토하", "너무", "심하", "심해", "죽겠", "갑자기")
    /** 아프다는 말 밖의 증상(열·토함·가슴·숨·두근거림): 묻지 않고 아플 때 안내 */
    private val ILL_SYMPTOM = listOf("열이", "숨을", "숨이", "의식", "토해", "가슴", "두근", "심장")
    /** 가벼운 통증 뒤에 붙은 부탁("허리 아파, 열선 좀"·"머리 아파, 음악 꺼 줘"·"다리 아파서 쉬었다 가자"): 그 부탁을 들어준다 */
    private val ACHE_REQ = listOf("열선", "통풍", "음악", "노래", "쉬어", "쉬고", "쉼터", "쉬었다", "쉬다가", "쉬자", "쉴래", "공기", "에어컨", "창문")
    private val ACHE_REST = listOf("쉬어", "쉬고", "쉼터", "쉬었다", "쉬다가", "쉬자", "쉴래")
    /** 부탁만 남기려고 빼는 아픈 말과 몸 자리 낱말("허리가"·"목이") */
    private val ACHE = listOf("아파", "아퍼", "아프", "아픈")
    private val BODY = listOf("허리", "머리", "다리", "어깨", "목", "무릎", "배", "등", "손", "발", "눈", "귀")
    private val PARTICLE = setOf("", "가", "이", "도", "는", "은", "좀", "쪽", "랑")
    /** "숨 좀 돌리자"·"숨 돌리고 가자": 아픈 말이 아니라 쉬어 가자는 말 */
    private val BREATHER = Regex("숨(을|이)?(좀|잠깐|한번|한숨)?돌리")
    private const val ACHE_HINT = "많이 아프시면 [긴급]을 눌러 주세요"
    /** "눈이 안 보여"가 몸의 일일 때 */
    private val EYE_BODY = listOf("갑자기", "한쪽", "캄캄", "흐릿", "침침", "번쩍", "안떠져", "안떠지")
    /** 날씨·눈부심("눈이 많이 와서"·"햇빛에 눈이 부셔서"·"안개 때문에"): 앞이 안 보여도 아픈 것이 아니다. "눈 오른쪽"이 잡히지 않게 '눈오'는 꼴째로 */
    private val GLARE = listOf("눈이와", "눈와", "눈이오는", "눈이오고", "눈이오니", "눈오는", "눈오고", "눈이내", "눈내", "많이와", "눈발", "눈보라", "부셔", "부시", "햇빛", "해때문", "역광", "안개", "비가", "비와", "빗")
    private val EYE_WEATHER = GLARE + listOf("와서", "내려")
    private val VIS_BAD = listOf("안보", "뿌얘", "뿌옇", "뿌예")
    /** 앞유리를 가리키는 말이면 바로 성에 제거. '안 보여'·'뿌얘'만이면 묻는다. 앞유리에 금이 갔다는 말은 김서림이 아니다 */
    private val FOG_GLASS = listOf("김서", "김이", "성에", "서렸", "습기", "앞유리")
    private val FOG_ASK = listOf("안보여", "안보이", "뿌얘", "뿌옇", "뿌예")
    /** 앞유리를 닦거나 더럽다는 말(김서림이 아니다) */
    private val GLASS_DIRTY = listOf("닦", "더러", "묻었", "묻어", "새똥", "벌레", "얼룩", "먼지", "워셔", "와이퍼", "세차")
    /** 안 보이는 것이 앞유리가 아니라 화면·글씨인 말 */
    private val SCREEN = listOf("내비", "화면", "지도", "계기판", "모니터", "폰이", "폰화면", "글씨", "글자")
    private val GLASS_CRACK = listOf("금갔", "금이", "깨졌", "깨진", "돌튀", "돌이튀", "돌맞", "긁")
    /** 막힌 길("앞차가 안 움직여"·"차들이 안 움직여"·"길이 안 움직여"): 내 차 고장이 아니다. 시동·타이어·기름 말이 함께면 고장 */
    private val JAM_WORDS = listOf("앞차", "앞에차", "앞에있는차", "차들이", "차들", "길이", "도로가", "막혀", "막혔", "정체")
    private val JAM_NOT = listOf("시동", "타이어", "펑크", "빵꾸", "기름", "연료", "배터리", "엔진", "고장")
    /** 부품 이름: 고장 말과 함께면 차 돌봄으로. 차가 서는 말(시동·타이어·기름·배터리…)이 함께면 고장 안내 */
    private val CAR_PART = listOf("와이퍼", "에어컨", "히터", "블루투스", "창문", "화면", "라디오", "스피커", "열선", "통풍", "트렁크", "거울", "라이트", "전조등", "내비")
    private val BREAK_SERIOUS = listOf("타이어", "펑크", "빵꾸", "기름", "연료", "배터리", "시동", "차가안움직", "엔진", "멈췄", "서버렸", "섰어")
    /** 내 차가 부딪쳤다는 말(바로 사고 안내). "부딪힐 뻔"은 들지 않는다. 전하거나 짐작하는 꼴("박았대"·"부딪혔나 봐")이면 묻는다 */
    private val HIT = listOf("부딪혔", "박았", "들이받", "받혔")
    private val HIT_HEARSAY = Regex("(부딪혔|박았|들이받았|받혔)(나|대|다던|다는|을까|는지|던가|었)")
    /** 사고가 났다는 꼴(전과 같다)과, 표지 낱말과 함께일 때만 보는 짐작·가정의 꼴("사고 날 뻔"·"사고 나면"·"사고 난 것 같아") */
    private val ACC_WORDS = listOf("났", "나서", "접촉", "교통", "사고야", "사고예", "사고에")
    private val ACC_SOFT = Regex("사고(가|는|도)?(나면|난|날|낼|냈)")
    /** 남의 사고·지난 사고·짐작을 뜻하는 말: 바로 열지 않는다 */
    private val ACC_MAYBE = Regex("앞에|저기|저앞|뉴스|친구|누가|났대|났나봐|났다던|났었|났네|어제|아까저차|날뻔|낼뻔|나면|날까|날것|것같|거같")
    /** 그 가운데 남의 일이 분명한 말(카드 없이 한 줄만). "앞에서 사고 났어"는 내 차일 수 있어 묻는다 */
    private val ACC_OTHERS = Regex("앞에(?!서)|저기|저앞|뉴스|친구|누가|났대|났나봐|났다던|박았대|부딪혔대|받혔대")
    /** 내 차가 얽힌 말("앞차랑 사고 났어"·"누가 내 차 박았대"): 남의 일로 넘기지 않고 묻는다 */
    private val ACC_WITH = listOf("랑사고", "하고사고", "랑부딪", "하고부딪", "랑박", "이랑", "한테받", "한테박", "내차", "우리차", "제차", "나를", "저를", "우리를")
    /** 바깥에서 들어오는 연기·탄내("밖에서 타는 냄새 들어와"·"앞차에서 연기 나"): 내 차 불이 아니라 안쪽 공기로. 내 차를 가리키는 말이 함께면 불 안내 */
    private val OUT_SMOKE = listOf("밖에서", "밖에", "밖이", "바깥", "앞차", "옆차", "뒤차", "다른차", "저차", "들어와", "들어오", "논", "밭", "공장", "산불")
    private val OWN_SMOKE = listOf("엔진", "보닛", "본넷", "후드", "우리차", "내차", "제차", "이차에서", "계기판", "송풍구", "에어컨", "히터", "바퀴", "타이어", "브레이크", "배터리", "트렁크", "밑에서", "아래에서", "차안에서")
    /** 안쪽 공기로 바꿨을 때 운행 서비스가 띄우는 글과, 바깥 냄새 때문이라는 까닭을 붙인 글 */
    private const val AIR_IN = "안쪽 공기로 돌릴게요"
    private const val AIR_IN_SMELL = "바깥 냄새가 들어오지 않게 안쪽 공기로 바꿨어요"
    /** 운전자 자신이 초보라는 말, 운전 도움을 켜 달라는 말. 남 이야기("앞차 초보 운전이네"·"초보가 끼어들었어")로는 켜지도 끄지도 않는다 */
    private val NOVICE_SELF = listOf("저초보", "제가초보", "내가초보", "나초보", "난초보", "전초보", "저는초보", "나는초보", "초보예요", "초보에요", "초보야", "초보입니다", "초보라", "초보인데", "초보거든",
        "초보운전이에요", "초보운전이야", "초보운전자예요", "초보운전입니다")
    private val NOVICE_ASK = listOf("켜", "도와", "부탁", "해줘", "해주세요", "필요해")
    private val NOVICE_OTHER = Regex("(앞|옆|뒤|저|그)차(가|는|도)?초보|초보(가|네|딱지|차)|초보운전(이네|차|자가)|끼어들")
    /** 지금 기사님이 운전한다는 말과, 대리를 부를지·얼마인지 이야기하는 말("대리 불러야겠다"·"대리기사 번호 알아?": 켜지 않는다) */
    private val DAERI_SAID = listOf("대리운전", "대리기사", "대리불렀", "대리왔", "대리운행", "대륙운행", "대리예요", "대리에요")
    private val DAERI_TALK = listOf("불러야", "부를까", "불러줘", "불러주", "번호", "얼마", "요금", "어디", "부르려고", "부를래", "부르자", "부를게")
    /** 조용히 가자는 분명한 말. "조용히"만 있으면 소리·바람 말("에어컨 소리 좀 조용히")을 먼저 가른다 */
    private val QUIET_SAID = listOf("급한말만", "말걸지마", "말시키지마", "말하지마", "쉿", "애잔다", "애자요", "애자니까", "애기잔다", "애기자요", "아이잔다", "아이자요", "아기잔다", "아기자요", "애들잔다", "애들자요", "자고있어", "손님있어", "손님있으니", "손님탔어", "손님탔으니")
    private val QUIET_NOISE = listOf("에어컨", "바람", "소리", "볼륨", "팬", "히터")
    /** 에어컨·히터(PREND가 켜지 않는 차 공조) */
    private val HVAC = listOf("에어컨", "히터", "에어콘")
    /** 119·구급차를 부르라는 말("구급차 지나간다"는 아니다) */
    private val SOS_WORDS = listOf("119", "일일구", "구급차", "앰뷸런스", "엠뷸런스", "구급대")
    private val SOS_CALL = listOf("불러", "부를", "부르", "불렀", "전화", "걸어", "연결", "신고", "와야", "필요", "빨리", "해줘", "좀")
    /** 날씨를 묻거나 말하는 말("내일 추워?"·"밖이 덥네"): 열선·통풍을 말하지 않으면 운전석 열선·통풍을 켜지 않는다 */
    private val WEATHER_TALK = listOf("내일", "날씨", "밖이", "바깥")
    /** 엄마·아빠·아이가 기억을 못 하거나 말이 이상하다("아빠가 기억이 없대"·"엄마 말이 갑자기 천천히 나와"): 아플 때 안내로 */
    private val MEM_SICK = Regex("기억(이|을|도)?(좀)?(안나|안난|못|없|이상|깜빡|가물)")
    private val SPEECH_SICK = Regex("말(이|을|도)?(좀|갑자기|자꾸|이상하게)?(이상|어눌|천천히나|안나와|안나오|꼬여|꼬이|느려|느리)")
    /** 주차한 자리를 잊었다는 말("어디 주차했는지 기억이 안 나"): 기억 화면이 아니다 */
    private val PARK_SPOT = listOf("주차", "세웠", "댔는지", "대놨", "세워놨")
    /** "주차장 어디 있어?": 주차장 찾기는 차의 음성 비서에게 */
    private val PARK_LOT = listOf("알려", "어디", "찾아")
    /** 이미 세워 둔 자리를 가리키는 말("어디 주차했는지"·"주차한 곳"). "주차 한 번 도와줘"의 '주차한'은 아니다 */
    private val PARKED_SAID = listOf("주차했", "주차해놨", "주차해둔", "주차해뒀", "주차한곳", "주차한데", "주차한자리", "주차한위치", "댔는지", "대놨", "세워놨")
    /** "먹을 데"·"먹을 곳"·"먹을 거 찾아"(밥집). "약 먹을 시간"은 아니다 */
    private val EAT_PLACE = Regex("먹을(데|곳|거찾|만한)")
    /** 카드로 묻는 급할 때 말(같이 고르기의 번호보다 먼저 본다) */
    private val URGENT_ASKS = setOf("ask_ill", "ask_acc", "ask_stopped", "ask_mem", "sos")
    /** 늦은 대답으로 받을 수 있는 말: 다른 부탁으로 고르지 못한 말 */
    private val BARE = setOf("other", "none")
    /** 급할 때 첫 화면에서 들어주고 첫 화면으로 돌아갈 평소 부탁 */
    private val ORDINARY = setOf("warm", "cool", "heat_off", "vent_off", "warm_at", "cool_at", "heat_off_at", "vent_off_at", "music", "music_off", "fresh_air", "inside_air",
        "rain_on", "rain_off", "song_what", "fog", "fog_off", "ask_fog", "fuel", "toll", "food", "cafe", "rest", "quiet_ride", "quiet_off", "outside_smoke", "care_broken",
        "say_glare", "say_ear", "say_noise", "say_parklot", "say_jam", "say_hvac",
        // 화장실도 매 운행 기능이라 첫 화면으로 돌아가 찾는다(11/08 오류 점검 A9: "애가 쉬 마렵대"가 늘 '다시 한 번 말씀해 주세요'였다)
        "toilet", "toilet_no")
    /** AI 대역이 고른 일 가운데 카드로 한 번 묻고 할 일: 카드 제목·설명·[예] 글 */
    private val AI_ASKS = mapOf(
        "novice_on" to Triple("운전 도움을 켤까요?", "켜면 달리는 동안 꼭 필요한 말만 해요", "켜기"),
        "novice_off" to Triple("운전 도움을 끌까요?", "끄면 운전을 돕는 말을 하지 않아요", "끄기"),
        "dusk_on" to Triple("해 지기 전에 알릴까요?", "도착이 해 진 뒤면 출발 전에 알려 드려요", "알려 줘요"),
        "slow_on" to Triple("천천히 말할까요?", "앞으로 조금 더 천천히 말해요", "천천히"),
        "slow_off" to Triple("원래 빠르기로 말할까요?", "천천히 말하기를 그만해요", "원래대로"),
        "quiet_ride" to Triple("오늘은 조용히 갈까요?", "먼저 말 걸지 않고 쉼터와 급한 일만 알려요", "조용히"),
        "baby_on" to Triple("아이가 탔나요?", "그러면 사고가 나면 뒷좌석 아이부터 알려 드려요", "네, 탔어요"),
        "breakdown" to Triple("차가 고장 났나요?", "고장이면 비상등부터 차례로 알려 드려요", "고장 났어요"),
        "fire" to Triple("이 차에서 연기가 나나요?", "그러면 세울 곳과 내릴 차례를 알려 드려요", "이 차예요"),
    )
    /** 지금 고르는 말의 원래 글(빈칸 있는 말). 운전자를 가리키는 "나"처럼 낱말째로 볼 때만 쓰고 기록하지 않는다 */
    private var said = ""

    /** 춥다·덥다는 사람이 누구인가: 운전자 자신이면 null, 아니면 front·rear·kid·adult(자리 낱말이 사람 낱말보다 먼저) */
    private fun other(s: String): String? {
        // "아이 추워"·"아이씨 더워"의 '아이'는 감탄사라 운전자 자신이다. 전하는 말("우리 아이 추워해")이면 아이로 둔다.
        // "우리 아이 추워"·"뒤에 아이 더워"처럼 우리·저희·뒤에·옆에가 앞에 붙은 '아이'는 감탄사가 아니다(검토 11/07 멋대로 판단)
        val t = groan(s)
            .let { if (HEARSAY.none { h -> h in it }) it.replace(Regex("(?<!우리|저희|우리집|뒤에|옆에)아이(?=[추춥더덥씨참])"), "") else it }
        return when {
            FRONT_SEAT.any { it in t } -> "front"
            REAR_SEAT.any { it in t } -> "rear"
            KID.any { it in t } -> "kid"
            ADULT.any { it in t } || HEARSAY.any { it in t } -> "adult"
            else -> null
        }
    }

    /** 사고 안내 중이거나 사고 메모가 하루 안이다(그때의 울렁거림은 멀미로 보지 않고 아플 때 안내로) */
    private fun afterCrash() = ui.value.scene.isE || ui.value.note?.let { System.currentTimeMillis() - it.at < 24 * 60 * 60_000L } == true

    /** [프렌드]를 부르거나(answer 없음) PREND가 물은 뒤 대답을 듣는다(answer 있음). 말이 없으면 windowMs 뒤 닫는다 */
    fun call(useEars: Boolean = true, answer: ((String) -> Unit)? = null, windowMs: Long = 8_000) {
        if (ui.value.listen?.done == false) return
        // 끝난 듣기의 뒷일(대답 넘기기·요청 고르기)이 아직 남았으면 지금 먼저 한다('잘 못 들었어요'를 보고 바로 다시 누름).
        // 1초 뒤에 돌면 새 듣기 화면을 닫아, 새로 한 말이 아무 반응 없이 사라진다
        pendingFinish?.let { r -> main.removeCallbacks(r); r.run() }
        if (ui.value.listen?.done == false) return
        Prend.log(if (answer == null) "◆ 프렌드 부름" else "· 대답 듣기 ${windowMs / 1000}초")
        // 사람이 [프렌드]를 누르면 PREND가 하던 말을 끊고 듣는다(제 목소리를 알아듣지 않게)
        if (answer == null) hush?.invoke()
        answerTo = answer
        chime?.invoke()
        val started = SystemClock.elapsedRealtime().also { listenStarted = it }
        // 사람이 부를 때만 지금 할 수 있는 말을 옅게 보인다(PREND가 물은 뒤의 대답에는 섞지 않는다)
        ui.update { it.copy(listen = Listen(hints = if (answer == null) hints(it) else emptyList())) }
        if (useEars) ears?.invoke()
        main.postDelayed({ if (ui.value.listen?.done == false && listenStarted == started) finishListen(ui.value.listen?.heard ?: "") }, windowMs)
    }

    /**
     * [프렌드]를 부르면 '듣는 중' 아래에 옅게 보일 말: 지금 맞는 것만, 세워 두면 둘·달리는 동안은 하나(짧게, 움직임 없이).
     * route가 그대로 알아듣는 말만 쓴다(처음 쓰는 사람이 무엇을 말해도 되는지 알게)
     */
    fun hints(u: StageUi): List<String> {
        if (u.scene.isE) return emptyList()
        // 같이 고르기 화면에서는 번호로 고르는 말과 다른 갈래(밥집·카페)
        if (u.scene == Scene.PICK) return listOfNotNull(
            when { u.pick.items.size >= 3 -> "세 번째로 가자"; u.pick.items.isNotEmpty() -> "첫 번째로 가자"; else -> null },
            if (u.pick.kind == "cafe") "밥집" else "카페",
        )
        val c = u.outsideC
        val cold = c != null && c <= 3 && (u.heat ?: 0) <= 0
        val hot = c != null && c >= 28 && (u.vent ?: 0) <= 0
        val m = java.util.Calendar.getInstance().let { it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE) }
        val meal = m in 11 * 60..13 * 60 + 30 || m in 17 * 60 + 30..19 * 60 + 30
        val out = buildList {
            if (u.parked) {
                if (cold) add("추워")
                if (hot) add("더워")
                if (u.others && meal) add("밥집 찾아 줘")
                if (u.driveMinutes >= 30) add("쪽잠 ${u.habits.napMin ?: 15}분")
                // 갈 때가 된 정비 항목이 있으면 차 돌봄을 여는 말
                if (u.care.rows.any { it.due }) add("차 정비 보여 줘")
                add("음악 틀어 줘")
            } else {
                if (u.driveMinutes >= 90) add("졸려")
                if (cold) add("추워")
                if (hot) add("더워")
                add(if (u.quietRide) "이제 말해도 돼" else "오늘은 조용히 가자")
            }
        }
        return out.take(if (u.parked) 2 else 1)
    }

    fun visible(v: Boolean) = ui.update { it.copy(visible = v) }

    fun ask(a: Ask?) = ui.update { it.copy(ask = a) }

    /** 운행 서비스가 붙이는 대답 받기(화면 버튼·배너 버튼). id는 Ask.id. 없으면 시연 동작 */
    var onAsk: ((id: String, yes: Boolean) -> Unit)? = null

    /** "졸려"·"쉼터"([프렌드]·AI 대역·한 시간 물음의 대답): 운행 서비스가 쉼터 카드로 권하고 확인받은 뒤 넣는다(ride/Care.sleepy). 없으면 시연 동작 */
    var onRest: (() -> Unit)? = null
    /** "화장실 급해": 운행 서비스가 앞쪽 화장실(휴게소·졸음쉼터·주유소)을 카드로 권하고 확인받은 뒤 넣는다(ride/Care.toilet). 들은 말을 넘긴다(카드가 떠 있으면 그 대답) */
    var onToilet: ((String) -> Unit)? = null
    /** "화장실 괜찮아": 기다리던 화장실 물음을 거둔다(ride/Care.toiletOff) */
    var onToiletNo: (() -> Unit)? = null
    /** 쉼터 카드가 떠 있지 않을 때의 "안 졸려"·"안 쉬어도 돼": 줄에 남은 쉼터 물음을 거두고 다음 권하기를 미룬다(ride/Care가 붙인다, 11/08 오류 점검 A8) */
    var onRestNo: (() -> Unit)? = null

    /** 배너에만 있는 버튼(Ask.alt)을 받는다 */
    var onAlt: ((id: String) -> Unit)? = null

    /** 배너의 Ask.alt 버튼을 눌렀다(지금 묻는 것일 때만) */
    fun answerAlt(id: String) {
        if (ui.value.ask?.id != id) return
        dropPendingAnswer()
        endListen()
        onAlt?.invoke(id)
    }

    fun answer(id: String, yes: Boolean) {
        // 지금 묻는 것이 아니면 버린다(사라지는 카드의 버튼을 한 번 더 누르면 같은 대답이 두 번 가서 기억이 한 번에 굳는다)
        if (ui.value.ask?.id != id) return
        dropPendingAnswer()
        // 대답 창이 말없이 닫힌 뒤 10초 안에 버튼으로 대답: 늦게 대답하는 사람(넉넉히 기다리기)
        if (missId == id && SystemClock.elapsedRealtime() - missAt < 10_000) onLate?.invoke()
        missId = null
        endListen()
        // 묻는 말 줄(ride/Care) 밖에서 띄운 카드(차 돌봄·골목 목적지)는 그 모듈이 받는다
        if (id == "CARE") { onCare?.invoke("answer", if (yes) "yes" else "no"); return }
        sideAsks[id]?.let { it(yes); return }
        val h = onAsk
        if (h == null || ui.value.demo) {
            go(Scene.HOME); ask(null)
            if (yes && id == "REST") toast("쉼터를 경유지로 넣었어요")
        } else h(id, yes)
    }

    fun restAnswer(yes: Boolean) = answer("REST", yes)

    /** 버튼으로 대답했으면 열어 둔 귀를 닫는다(듣던 창이 끝나며 빈 대답을 한 번 더 넘기지 않게) */
    fun endListen() {
        if (ui.value.listen == null) return
        answerTo = null
        stopEars?.invoke()
        closeListen()
    }

    fun onLevel(level: Float) = ui.update { s -> s.listen?.takeIf { !it.done }?.let { s.copy(listen = it.copy(level = level)) } ?: s }

    fun onHeard(text: String, completed: Boolean) {
        // 끝난 듣기 뒤에 온 결과는 버린다(물음 띠 단추나 창 시간으로 끝난 뒤 1.1초 사이에 온 귀의 결과가 [프렌드] 명령으로 한 번 더 가지 않게, 검토 11/06)
        val l = ui.value.listen ?: return
        if (l.done) return
        ui.update { s -> s.copy(listen = s.listen?.copy(heard = text)) }
        if (completed) finishListen(text)
    }

    fun finishListen(text: String) {
        stopEars?.invoke()
        ui.update { s -> s.copy(listen = (s.listen ?: Listen()).copy(heard = text, done = true, level = 0f)) }
        val answer = answerTo
        answerTo = null
        val r = object : Runnable {
            override fun run() {
                if (pendingFinish === this) pendingFinish = null
                // "뭐라고?": 방금 한 말을 천천히 한 번 더(급할 때 안내는 ride/Crash가 따로 듣는다).
                // 확인 카드(Q_)의 대답 창이면 보내지 않는다: 카드는 목소리 없이 떠서 지난 아무 말을 다시 틀고, 그 말에 한 "응"이 카드의 [예]가 됐다(11/08 오류 점검 A6, 카드가 한 번 더 듣는다)
                val confirmWindow = answer != null && ui.value.ask?.id?.startsWith("Q_") == true
                if (!ui.value.scene.isE && onAgain != null && !confirmWindow && again(text, answer != null)) { closeListen(); onAgain?.invoke(answer); return }
                if (answer != null && text.isBlank()) missed()
                if (answer != null) { closeListen(); answer(text) } else route(text)
            }
        }
        pendingFinish = r
        pendingIsAnswer = answer != null
        main.postDelayed(r, if (text.isBlank()) 900L else 1_100L)
    }

    /** 끝난 듣기의 뒷일. 알아들은 글을 1초쯤 보여 준 뒤에 한다 */
    private var pendingFinish: Runnable? = null
    /** 그 뒷일이 물음의 대답 넘기기인가([프렌드] 부탁이 아니라) */
    private var pendingIsAnswer = false

    /**
     * 알아들은 글을 보여 주는 1초 사이에 카드 단추로 대답했으면 남은 말 대답은 버린다: 단추가 이긴다
     * (단추 [없어요] 뒤에 말 "네"가 1.1초 뒤 다시 들어가 단추와 반대로 저장됐다, 원칙 3, 11/08 오류 점검 A7). [프렌드] 부탁은 그대로 둔다
     */
    private fun dropPendingAnswer() {
        if (!pendingIsAnswer) return
        pendingFinish?.let { main.removeCallbacks(it); pendingFinish = null; Prend.log("· 단추로 대답함 → 남은 말 대답은 버림") }
    }

    fun closeListen() = ui.update { it.copy(listen = null) }

    /** PREND가 물은 것("필요한 것 있으세요?")에 돌아온 요청을 [프렌드]에 한 말처럼 고른다. 대답 창에는 곁의 말이 섞일 수 있어 AI 대역으로 보내지 않는다 */
    fun request(text: String) = route(text, ai = false)

    /** PREND가 물은 말의 대답을 [프렌드]에 한 말처럼(낱말 규칙에 없으면 AI 대역까지). 혼자일 때 물은 한 시간 물음 */
    fun requestAi(text: String) = route(text, ai = true)

    /** 장르·노래 이름(PREND 곡으로 들어줄 수 없는 노래 요청) */
    val GENRE = listOf("트로트", "뽕짝", "동요", "상어", "발라드", "가요", "팝송", "힙합", "재즈", "클래식", "아이돌", "케이팝", "찬송", "캐롤", "캐럴")

    /** 할 일 목록에 없는 말·인터넷이 안 될 때 */
    /** 전화 화면을 연 뒤의 안내(배너도 같은 글을 보인다) */
    const val DIAL_HINT = "전화 화면의 통화 버튼을 누르면 걸려요"
    private const val CANT = "그건 제가 할 수 없는 일이에요 · 길 안내·전화는 차의 음성 비서에게 말씀해 주세요"
    private const val NO_NET = "인터넷이 안 돼서 지금은 알아듣지 못했어요"
    private const val BUSY = "지금은 알아듣는 서버가 바빠요 · 잠시 뒤 다시 말씀해 주세요"

    /** 낱말 규칙(접수양식 5번): 정해 둔 요청만 여기서 고르고, 그 밖의 말은 생성형 AI 대역(askAi)이 같은 할 일 목록에서 고른다 */
    private fun route(text: String, ai: Boolean = true) {
        closeListen()
        said = text
        // "숨 좀 돌리자"는 쉬어 가자는 말이다(아픈 말로 읽지 않는다, 검토 11/07 멋대로 판단)
        var s = text.replace(" ", "").replace(BREATHER, "쉬어")
        var heard = text
        val first = pickIntent(s, text)
        // 대답 창이 닫히고 10초 안에 [프렌드]로 한 말: "응"·"아니"처럼 대답 한마디일 때만 떠 있는 그 카드의 늦은 대답으로 받는다(11/04 검토: 넘기지 않으면 "할 수 없는 일" 글이 떴다).
        // 다른 부탁이 붙었거나("아니 그게 아니라 화장실 급해"·"어, 근데 추워") 되물은 "네?"면 카드는 두고 부탁으로 고른다. 늦게 대답한 수도 진짜 대답일 때만 센다(answer가 센다, 검토 11/07 멋대로 판단)
        if (ai && s.isNotBlank() && missId != null && SystemClock.elapsedRealtime() - missAt < 10_000) {
            val id = missId
            // 확인 카드(Q_)의 늦은 대답은 카드 대답 창과 같은 낱말로 가른다("그냥 둬"·"지워 줘"가 AI로 가서 '할 수 없는 일'이 뜨고 카드는 그대로였다, 11/08 에뮬레이터).
            // 두 갈래를 고르는 카드는 그 낱말 목록으로만("응" 한마디로 첫째를 고르지 않는다)
            val pick = id?.let { confirmPicks[it] }
            val yes = when {
                pick != null -> pickOf(pick, text)
                id?.startsWith("Q_") == true -> confirmAnswer(id.orEmpty(), text)
                else -> app.prend.ride.Answer.of(text, emptyList())
            }
            if (id != null && ui.value.ask?.id == id && yes != null && !app.prend.ride.Answer.unsure(text) && words(text).size <= 3 && first in BARE) {
                Prend.log("· 대답 창 뒤 10초 안의 말: 늦은 대답으로 받음")
                // 단추와 말의 뜻이 다른 카드(사고 뒤: 말로 한 "네"로 메모를 바로 지우지 않는다)는 그 모듈이 말 대답으로 받는다
                if (onLateSaid?.invoke(id, text) == true) { onLate?.invoke(); missId = null; return }
                answer(id, yes)
                return
            }
            Prend.log("· 대답 창 뒤 10초 안의 말: 대답 한마디가 아니라 부탁으로 고름")
        }
        var intent = first
        // 가벼운 통증에 붙은 부탁("허리 아파, 열선 좀 켜 줘"·"다리 아파서 좀 쉬었다 가자"): 아픈 말을 뺀 부탁을 들어주고, 조금 뒤 [긴급] 한 줄을 띄운다(검토 11/07 멋대로 판단)
        if (intent == "ache_req") {
            val req = dropAche(text)
            intent = when {
                ACHE_REST.any { it in s } -> "rest"
                req.isBlank() -> "ask_ill"
                else -> pickIntent(req.replace(" ", ""), req)
            }
            if (intent != "ask_ill") {
                heard = req
                s = req.replace(" ", "")
                main.postDelayed({ toast(ACHE_HINT, 3_500) }, 2_800)
            }
        }
        // 같이 고르기 화면에서는 번호로 고른다("세 번째로 가자"·"3번"). 손이 닿지 않는 뒷자리도 말로 낀다.
        // 사고·아플 때 말은 먼저 보낸다("첫째가 아파"·"여기가 아파"가 번호나 고른 곳으로 잡혀 급할 때 안내를 놓치지 않게)
        if (s.isNotBlank() && ui.value.scene == Scene.PICK && intent !in E_INTENTS && intent !in URGENT_ASKS && intent != "toilet" && intent != "toilet_no" && pickBy(s)) return
        Prend.log("· 프렌드: 들은 말 ${text.length}자 → ${if (intent != first) "$first → " else ""}$intent")
        // 낱말 규칙에 맞지 않은 말만 생성형 AI 대역(중계 서버의 Gemini)이 정해 둔 할 일 하나로 고른다. 인터넷이 없거나 못 고르면 다시 묻는다
        if (intent == "other") {
            // 사고 안내 중 낱말 규칙에 없는 "다쳤어요"·"피가 나요"는 AI를 기다리지 않고 [다쳤어요]를 누른 것처럼(검토 11/07 멋대로 판단, ride/Crash)
            if (ui.value.scene == Scene.ACCIDENT && onHurtSaid?.invoke(s, false) == true) return
            if (!ai) return toast("필요한 게 있으면 [${Prend.CALL}]를 불러 말씀해 주세요", 3_000)
            val ask = askAi ?: return toast("다시 한 번 말씀해 주세요")
            // 답이 오기까지 몇 초 걸린다: 기다리는 동안 알린다. 그사이 [프렌드]를 다시 불렀거나 화면이 바뀌었거나 너무 늦게 온 답은 버린다
            // (늦은 답이 사고 안내 화면을 바꿔 안내를 끊거나, 묻지도 않고 쉼터를 넣지 않게)
            val heardAt = listenStarted
            val scene = ui.value.scene
            val t0 = SystemClock.elapsedRealtime()
            val hs = s
            toast(THINKING, AI_WAIT_MS)
            ask(heard, ui.value.parked) { picked, kid ->
                clearToast(THINKING)
                val late = SystemClock.elapsedRealtime() - t0 > AI_WAIT_MS
                when {
                    listenStarted != heardAt || ui.value.scene != scene -> Prend.log("· 프렌드(AI 대역): 그사이 바뀌어 답 버림")
                    late -> { Prend.log("· 프렌드(AI 대역): 늦게 온 답 버림"); toast(NO_NET, 3_500) }
                    // 서버가 답했지만 바쁘거나(요청 수 상한) 실패했다: 인터넷 탓이라고 하지 않는다(운행 서비스가 "busy"로 넘긴다, 11/08 오류 점검 D5)
                    picked == "busy" -> toast(BUSY, 3_500)
                    // 인터넷 문제와 못 하는 일을 가른다(11/04 사람 시험: 둘 다 "다시 말씀해 주세요"라 발음 탓인 줄 알고 세 번 되풀이했다)
                    picked == null -> toast(NO_NET, 3_500)
                    picked == "other" -> toast(CANT, 4_000)
                    // 급할 때 첫 화면에서 들은 평소 부탁("열선 켜 줘")은 들어주고 첫 화면으로 돌아간다(검토 11/07 멋대로 판단)
                    scene == Scene.EMERGENCY && picked in ORDINARY -> { Prend.log("· 프렌드(AI 대역): 급할 때 첫 화면의 평소 부탁 → 첫 화면으로"); go(Scene.HOME); aiAct(picked, hs, kid, Scene.HOME) }
                    // 급할 때 화면에서는 급할 때의 할 일만(다른 화면으로 가면 사고·아플 때 안내가 끊긴다)
                    scene.isE && picked !in E_INTENTS -> { Prend.log("· 프렌드(AI 대역): 급할 때라 $picked 버림"); toast("다시 한 번 말씀해 주세요") }
                    // 이미 그 안내 중이면 그대로 둔다(걸음을 처음으로 돌리지 않게)
                    picked == "fire" && ui.value.fire || picked == "breakdown" && ui.value.breakdown -> Prend.log("· 프렌드(AI 대역): 이미 $picked 안내 중")
                    // 아플 때·응급처치 안내는 고장·연기 안내로 바꾸지 않는다. 고장은 E 첫 화면에서만(사고·연기 안내를 고장으로 낮추지 않게)
                    scene.isE && (picked == "breakdown" && scene != Scene.EMERGENCY || picked == "fire" && scene != Scene.EMERGENCY && scene != Scene.ACCIDENT) ->
                        { Prend.log("· 프렌드(AI 대역): ${scene.name} 안내 중이라 $picked 버림"); toast("다시 한 번 말씀해 주세요") }
                    else -> aiAct(picked, hs, kid, scene)
                }
            }
            return
        }
        // 급할 때 첫 화면([긴급]·급할 때 판)에서 들은 평소 부탁은 들어주고 첫 화면으로 돌아간다(빨간 화면이 남지 않게, 검토 11/07 멋대로 판단).
        // 사고·아플 때 걸음 화면에서는 그대로(안내를 끊지 않게)
        if (ui.value.scene == Scene.EMERGENCY && intent in ORDINARY) { Prend.log("· 급할 때 첫 화면의 평소 부탁 → 첫 화면으로"); go(Scene.HOME) }
        // 사람이 낱말로 분명히 말한 사고·아플 때면 AI 대역의 짐작 표시를 거둔다
        if (intent == "accident" || intent == "illness" || intent.startsWith("aid_")) clearGuess()
        act(intent, s, null)
    }

    /**
     * 들은 말 → 할 일 이름(낱말 규칙). 화면·차 값만 읽고 아무것도 바꾸지 않는다(늦은 대답인지 가를 때도 쓴다).
     * ask_로 시작하면 한 번 묻는 카드, say_로 시작하면 한 줄 알림(검토 11/07 멋대로 판단: 말이 분명할 때만 바로 움직이고, 애매하면 묻거나 맞는 기능으로 보낸다)
     */
    private fun pickIntent(s: String, text: String): String {
        val u = ui.value
        val e = u.scene.isE
        // 아플 때 낱말("열이"·"아파"…)이 든 말은 멀미 낱말이 함께여도 아플 때 쪽으로 간다(멀미 갈래가 생기기 전과 같게)
        val ill = ILL.any { it in s.replace("아파트", "") }
        // 쉼터를 마다하는 말인가(쉼터를 묻는 중이면 "필요 없어"·"됐어"까지)
        val restNo = (if (u.ask?.id == "REST") REST_NO else REST_NOT).any { it in s }
        val hit = HIT.any { it in s }
        // 춥다·덥다: 날씨를 묻거나 말하는 말("내일 추워?"·"밖이 덥네")은 열선·통풍을 말하지 않으면 운전석으로 보내지 않는다(검토 11/07 멋대로 판단)
        val cold = "열선" in s || COLD.any { it in s } && WEATHER_TALK.none { it in s }
        val hot = "통풍" in s || HOT.any { it in s } && WEATHER_TALK.none { it in s }
        return when {
            s.isBlank() -> "none"
            // 사고: "부딪혔어"·"박았어"·"받혔어"는 이 차의 일이라 바로 연다. 전하거나 짐작하는 꼴("박았대"·"부딪혔나 봐")이면 묻는다
            hit && !HIT_HEARSAY.containsMatchIn(s) -> "accident"
            hit -> accMaybe(s, u)
            // "사고 났어"("커피 사고 싶어"의 '사고'는 사다). 남의 사고("앞에 사고 났나 봐"·"친구가 사고 났대")·지난 일·짐작("사고 날 뻔")이면 바로 열지 않는다(검토 11/07 멋대로 판단)
            "사고" in s && ACC_MAYBE.containsMatchIn(s) && (ACC_WORDS.any { it in s } || ACC_SOFT.containsMatchIn(s)) -> accMaybe(s, u)
            "사고" in s && ACC_WORDS.any { it in s } -> "accident"
            // 연기·타는 냄새(11/02): 고장보다 먼저 본다. "타는 냄새는 안 나"·"화재는 아니야"는 불이 아니다(11/06).
            // 바깥에서 들어오는 냄새("밖에서 탄내 들어와"·"앞차에서 연기 나")는 이 차 불이 아니라 안쪽 공기로(검토 11/07 멋대로 판단)
            FIRE_WORDS.any { it in dropFireDenial(s) } -> if (smokeOutside(s)) "outside_smoke" else "fire"
            // "견인차 왔어"·"렉카가 와서 끌고 가려고 해"
            listOf("견인차", "렉카", "레커", "레카").any { it in s } && listOf("왔", "와서", "와요", "끌고", "가재", "온다").any { it in s } -> "tow"
            // 막힌 길("앞차가 안 움직여"·"차들이 안 움직여")은 고장이 아니다
            "안움직" in s && JAM_WORDS.any { it in s } && JAM_NOT.none { it in s } -> "say_jam"
            // 고장(11/02): "차가 고장 났어"·"타이어 터졌어"·"기름 떨어졌어"·"시동이 안 걸려". 차 돌봄(타이어·배터리 낱말)보다 먼저 본다(broken이 부품·달리는 중을 가른다)
            BREAKDOWN.any { it in s } -> broken(s, u)
            // 사람이 쓰러지거나 숨·의식처럼 급한 말은 기억·천천히·주차 같은 평소 말보다 먼저 본다(검토 11/07 멋대로 판단: "아빠가 쓰러졌는데 기억이 없대"가 기억 화면으로 갔다).
            // 응급처치 카드로 바로: 목에 걸림(돌 전 아기는 따로), 경련, 쓰러짐·반응 없음
            CHOKE.any { it in s } ->
                if (BABY.any { it in s }) "aid_infant" else "aid_choke"
            seizure(s) -> "aid_seizure"
            CPR.any { it in s } -> "aid_cpr"
            // 아픈 말에 심한 말(가슴·숨·의식·피·너무·갑자기…)이 함께면 묻지 않고 아플 때 안내. "배가 너무 아파서 화장실 급해"는 아래 화장실로(11/07)
            ill && ILL_SEVERE.any { it in s } && !(toiletAsk(s, text) && !e) -> "illness"
            // "119 불러"·"구급차 불러 줘"(11/08 무작위 시험: 낱말 규칙에 없어 AI가 한 번은 모르는 말, 한 번은 아플 때로 골랐다).
            // 사고인지 아픈지 모르면 짐작하지 않고 [119 전화]·[사고가 났어요]·[갑자기 아파요]가 함께 있는 급할 때 첫 화면으로. 아픈 말이 함께면 아플 때 안내로
            SOS_WORDS.any { it in s } && SOS_CALL.any { it in s } -> if (ill) "illness" else "sos"
            // 엄마·아빠·아이가 기억을 못 하거나 말이 이상하다: 기억 화면·천천히 말하기가 아니라 아플 때 안내로.
            // 말이 어눌하거나 갑자기 기억을 못 하면 바로 열고, 기억 말만 있으면("아빠 생일이 기억 안 나") 한 번 묻는다(시험 33, 원칙 1)
            (MEM_SICK.containsMatchIn(s) && PARK_SPOT.none { it in s } || SPEECH_SICK.containsMatchIn(s)) && illWho(s, null) == true ->
                if (e || SPEECH_SICK.containsMatchIn(s) || "갑자기" in s) "illness" else "ask_mem"
            // "어디 주차했는지 기억이 안 나"는 기억 화면이 아니다(주차 자리 사진을 알린다)
            "기억" in s && PARK_SPOT.any { it in s } -> "say_spot"
            // 급할 때 화면에서는 기억·천천히 말하기·주차 도움으로 옮겨 가지 않는다. "아까 그 노래 기억나?"는 기억 화면이 아니라 노래 물음
            !e && listOf("기억", "잊어").any { it in s } && SONG_ASK.none { it in s } -> "memo"
            // 첫 화면 단추 이름 그대로 "설정 열어 줘"(11/09). 말머리가 설정일 때만("온도 설정해 줘"는 아니다)
            !e && (s.startsWith("설정") || listOf("앱설정", "프렌드설정", "설정화면").any { it in s }) -> "memo"
            // 설정 화면에 보이는 이름 그대로 부르는 말(11/10 검토): "배운 거 보여 줘", "보험사 바꿔 줘", "대화할 때 소리 낮춰 줘"
            !e && listOf("배운거", "배운것", "배운게").any { it in s } -> "memo_more"
            !e && "보험사" in s && listOf("바꿔", "바꾸", "골라", "고를", "정해", "설정").any { it in s } -> "insurer"
            !e && "대화" in s && listOf("낮춰", "낮추", "줄여", "조용").any { it in s } -> if (OFF.any { it in s }) "talk_off" else "talk_on"
            // "천천히 말해 줘"·"원래대로 말해"(ride/SlowTalk)
            !e && "천천히" in s && "말" in s -> "slow_on"
            !e && listOf("원래대로말", "빨리말해", "보통으로말", "보통빠르기").any { it in s } -> "slow_off"
            // "밤 운전이 불편해"·"밤길이 무서워": 출발할 때 도착이 해 진 뒤면 카드로(ride/Dusk)
            listOf("밤운전", "야간운전", "밤길", "밤에운전").any { it in s } && listOf("불편", "무서", "힘들", "어려", "싫").any { it in s } -> "dusk_on"
            // 운전 도움(옛 이름 초보 운전): 끄라는 말("운전 도움 필요 없어")을 먼저. 켜는 것은 운전자 자신이 초보라고 하거나 켜 달라고 할 때만
            // ("앞차 초보 운전이네"·"초보가 끼어들었어"는 남 이야기라 켜지도 끄지도 않는다, 검토 11/07 멋대로 판단)
            listOf("초보", "운전도움").any { it in s } && !NOVICE_OTHER.containsMatchIn(s) && (OFF + "필요없").any { it in s } -> "novice_off"
            listOf("초보", "운전도움").any { it in s } && noviceOn(s) -> "novice_on"
            // "주차 자리 그만 알려 줘"는 주차 코치가 아니라 주차 자리 알림 끄기
            "주차자리" in s && (OFF + listOf("알리지마", "안알려", "필요없")).any { it in s } -> "spot_off"
            // "주차 코치 꺼"·"주차 도움 그만"은 끄기(전에는 켜는 말로 받았다, ride/Coach). "근처 주차장 알려 줘"는 주차 도움이 아니다
            // "주차 도와주지 마"·"주차 안 도와줘도 돼"·"주차 도와줄 필요 없어"도 끄기다(켜는 말로 받았다, 11/08 오류 점검 F1)
            "주차" in s && listOf("코치", "도움", "도와").any { it in s } && (OFF + listOf("됐어", "필요없", "하지마", "주지마", "안도와")).any { it in s } -> "park_off"
            "주차장" in s && PARK_LOT.any { it in s } -> "say_parklot"
            // "어디 주차했는지 알려 줘"·"주차 위치 알려 줘"는 주차 코치가 아니라 주차 자리를 묻는 말('기억'이 없어도, 11/08 오류 점검 F1).
            // "주차 도와줘, 어디 댈지 모르겠어"처럼 앞으로 댈 곳을 묻는 말은 주차 코치 그대로
            !e && (PARKED_SAID.any { it in s } || "주차위치" in s) && listOf("어디", "위치", "알려", "찾").any { it in s } -> "say_spot"
            !e && "주차" in s && listOf("도와", "코치", "어려", "봐줘", "알려").any { it in s } -> "park"
            // 화장실(11/07): 주유 말·아플 때 말보다 먼저("기름 넣고 화장실도"·"배 아파서 화장실 급해"). 숨·의식·피·가슴, 쓰러짐·경련·목 막힘,
            // 소변 증상이 함께면 아래 응급처치·아플 때 안내로. 급할 때 화면에서는 보지 않는다(아플 때 안내를 고쳐 주는 말이 먼저, 검토 11/07)
            URINE.any { it in s } && URINE_SICK.any { it in s } -> "illness"
            // [긴급]을 눌러 연 급할 때 첫 화면에서는 화장실도 받아 첫 화면으로 돌아가 찾는다(사고·아플 때 걸음 화면은 그대로, 11/08 오류 점검 A9)
            toiletAsk(s, text) && (!e || u.scene == Scene.EMERGENCY) -> if (TOILET_NO.any { it in s } && !toiletUrge(s)) "toilet_no" else "toilet"
            // "엔진 기름"은 주유구가 아니라 차 돌봄(엔진오일). "경유지 넣어 줘"의 '경유'는 기름이 아니다
            // "강남역 경유해서 가자"·"경유하자"의 '경유'와 "기름 냄새 나"는 주유구 이야기가 아니다(11/08 오류 점검 F9)
            careKey(s) == null && "냄새" !in s && listOf("주유구", "충전구", "유종", "휘발유", "경유", "기름").any { it in s.replace(Regex("경유(지|해|하)"), "") } -> "fuel"
            "하이패스" in s -> "toll"
            // "눈이 (갑자기·한쪽이) 안 보여"는 몸의 일이라 아플 때 안내로. 날씨·눈부심("눈이 많이 와서"·"햇빛에 눈이 부셔서")은 아래 한 줄로
            "눈이" in s && "안보" in s && EYE_WEATHER.none { it in s } && EYE_BODY.any { it in s } -> "illness"
            // 김서림: 끄라는 말을 먼저("성에 제거 꺼 줘", ride/Coach). 앞유리를 가리키는 말이면 바로 켜고, '안 보여'·'뿌얘'만이면 묻는다
            listOf("성에", "김서", "앞유리").any { it in s } && OFF.any { it in s } -> "fog_off"
            // '앞유리'만으로는 켜지 않는다: 김·성에·습기나 '안 보여'·'뿌얘'가 함께일 때만 바로 켜고, 닦는·더러운 이야기("앞유리 닦아야겠다")는 김서림이 아니다.
            // 그 밖의 '앞유리'만 있는 말은 한 번 묻는다(11/08 에뮬레이터: "앞유리 닦아야겠다"에 성에 제거와 바깥 공기가 켜졌다)
            FOG_GLASS.any { it in s } && !("앞유리" in s && GLASS_CRACK.any { it in s }) && (FOG_GLASS.any { it != "앞유리" && it in s } || FOG_ASK.any { it in s }) -> "fog"
            "앞유리" in s && GLASS_CRACK.none { it in s } && GLASS_DIRTY.none { it in s } && careKey(s) == null -> "ask_fog"
            "눈이" in s && "안보" in s -> if (EYE_WEATHER.any { it in s }) "say_glare" else if (e) "illness" else "ask_ill"
            // "내비가 안 보여"의 '비가'는 날씨가 아니다
            VIS_BAD.any { it in s } && GLARE.any { it in s.replace("내비", "") } -> "say_glare"
            // 화면·글씨가 안 보인다는 말("내비가 안 보여"·"글씨가 안 보여")은 김서림이 아니다
            !ill && FOG_ASK.any { it in s } && SCREEN.none { it in s } -> "ask_fog"
            // 멀미("애가 멀미해"·"속이 울렁거려")는 급할 때가 아니다: 바깥 공기 쪽으로. 숨·의식·피·머리가 함께거나 사고 뒤면 아래 아플 때 안내로
            MOTION.any { it in s } && SEVERE.none { it in s } && !ill && !afterCrash() -> "motion"
            MOTION.any { it in s } -> "illness"
            // 아픈 말: 급할 때 화면이면 전처럼 아플 때 안내. 아니면 '아파'뿐인 가벼운 통증인지 가른다
            ill -> if (e) "illness" else ache(s)
            // "이 노래 뭐야?"·"아까 그 노래"(노래 틀어 줘보다 먼저).
            // "노래 뭐 좀 틀어 줘"처럼 틀어 달라는 말은 노래 물음이 아니다
            SONG_ASK.any { it in s } && SONG_PLAY.none { it in s } -> "song_what"
            // 대리 운행: 대리운전을 뜻하는 말만(대리점·김 대리·대리주차는 아니다). 끝났다·아니라는 말을 먼저 가른다
            "대리" in s && listOf("대리점", "대리주차").none { it in s } && listOf("끝", "아니", "안불러", "안불렀", "필요없", "내렸", "갔어", "보냈").any { it in s } -> "daeri_off"
            // 지금 기사님이 운전한다는 말이면 카드로 한 번 묻는다(소리 없이 졸음 안내가 꺼지는 일). 부를지·얼마인지 이야기하는 말로는 켜지 않는다(검토 11/07 멋대로 판단)
            DAERI_SAID.any { it in s } && DAERI_TALK.none { it in s } -> if (u.daeri) "daeri_on" else "ask_daeri"
            // 조용히 가기(이번 운행만): 다시 말해도 된다는 말을 먼저 가른다. "조용히 말해 줘"(목소리 크기)·"조용한 곡"은 아니다
            listOf("말해도돼", "말걸어도돼", "조용히안해도").any { it in s } || "조용히" in s && UNDO.any { it in s } -> "quiet_off"
            // 화면 이름으로 끄는 말("급한 말만 하기 꺼 줘"·"조용히 가기 그만"): 아래 켜는 낱말에 걸려 거꾸로 켜졌다(11/10 검토)
            listOf("급한말만", "조용히가기").any { it in s } && (OFF + listOf("풀어", "해제")).any { it in s } -> "quiet_off"
            // 알아서 한 일 되돌리기("되돌려"·"원래대로"). "대리 끝났어, 원래대로"·"이제 말해도 돼, 원래대로"는 위에서 대리 끝·조용히 끝으로 먼저 간다.
            // "빗소리 원래대로"는 아래 빗소리 빼기로
            UNDO.any { it in s } && RAIN.none { it in s } -> "undo"
            // "에어컨 소리 좀 조용히"·"볼륨 조용히"는 PREND에게 말 걸지 말라는 뜻이 아니다: 차 단추를 알린다(검토 11/07 멋대로 판단)
            "조용히" in s && QUIET_NOISE.any { it in s } && QUIET_SAID.none { it in s } -> "say_noise"
            "조용히" in s && listOf("말해", "곡", "노래", "음악", "틀어").none { it in s } || QUIET_SAID.any { it in s } -> "quiet_ride"
            // 졸리면 음악·공기보다 쉼터가 먼저. "안 졸려"는 쉼터를 마다하는 말.
            // "저희 엄마 졸리대"·"애가 졸린가 봐"는 운전자가 아니다: 잘래요를 켤지 한 번 묻는다(시험 33: AI가 운전자 쉼터로 골랐다)
            listOf("졸려", "졸리", "졸음").any { it in s } -> when {
                restNo -> "rest_no"
                "졸음쉼터" !in s && illWho(s, null, text) == true -> "ask_sleep"
                else -> "rest"
            }
            // 바깥 공기("외기 차단"·"환기 그만"은 안쪽 공기로), PREND 곡("노래 좀 꺼"는 멈춤, “노래 좋네”처럼 틀어 달라는 말이 아니면 고르지 않는다)
            AIR.any { it in s } && (OFF + listOf("차단", "막아", "닫아")).any { it in s } -> "inside_air"
            AIR.any { it in s } -> "fresh_air"
            // 빗소리(PREND 곡 위의 결): 빼라는 말을 먼저 가른다
            RAIN.any { it in s } && (OFF + UNDO + listOf("빼", "치워", "없애", "걷어")).any { it in s } -> "rain_off"
            RAIN.any { it in s } -> "rain_on"
            listOf("음악", "노래").any { it in s } && OFF.any { it in s } -> "music_off"
            listOf("음악틀", "노래틀", "음악켜", "노래켜", "음악좀", "노래좀", "곡틀", "아무노래").any { it in s } ||
                listOf("음악", "노래").any { it in s } && listOf("틀어", "들려").any { it in s } -> "music"
            // 에어컨·히터는 PREND가 켜지 않는다: 운전석 통풍·열선으로 바꿔 듣지 않고 차의 단추를 알린다(11/08 무작위 시험: AI가 "에어컨 켜 줘"를 한 번은 통풍으로, 한 번은 모르는 말로 골랐다)
            HVAC.any { it in s } && careKey(s) == null && CARE_ANY.none { it in s } -> "say_hvac"
            // 동승자가 춥다·덥다("엄마가 추우시대"·"옆에 사람이 춥대"·"뒤에 애가 덥대"·"옆자리 열선 켜 줘"): 그 자리로.
            // "아기가 추워하고 있어"가 아기 탑승으로 가지 않게 아기 말보다 먼저. "옆자리 열선 꺼 줘"는 운전석이 아니라 그 자리만 끈다
            cold && OFF.none { it in s } && other(s) != null -> "warm_at"
            hot && OFF.none { it in s } && other(s) != null -> "cool_at"
            "열선" in s && OFF.any { it in s } && other(s) != null -> "heat_off_at"
            "통풍" in s && OFF.any { it in s } && other(s) != null -> "vent_off_at"
            // "아기 탔어"·"아기 내렸어"(아플 때 말은 위에서 먼저 가른다)
            listOf("아기", "애기").any { it in s } && listOf("내렸", "없어", "안탔").any { it in s } ||
                child(s) && listOf("내렸", "안탔").any { it in s } -> "baby_off"
            listOf("아기", "애기").any { it in s } && listOf("탔", "태웠", "있어").any { it in s } ||
                child(s) && listOf("탔", "태웠").any { it in s } -> "baby_on"
            // "잘래요 그만"·"쪽잠 꺼 줘"는 끄기("그만 잘래"는 아래에서 쉬기로)
            listOf("쪽잠", "잘래요").any { w -> (OFF + "끝").any { "$w$it" in s } } -> "nap_off"
            listOf("쪽잠", "잘래", "자고싶", "잠깐잘", "눈좀붙", "눈붙이").any { it in s } -> "nap"
            // 같이 고르기(카페·밥집), 그다음 그냥 쉬어 가자는 말. "약 먹을 시간"의 '먹을'은 밥집이 아니다(검토 11/07 멋대로 판단)
            // 단추 이름 그대로 "밥집 카페 열어 줘"는 단추처럼 밥집부터
            listOf("카페", "커피").any { it in s } && "밥집" !in s -> "cafe"
            listOf("맛집", "밥집", "식당", "배고파", "배고프", "점심", "저녁먹", "밥먹").any { it in s } || EAT_PLACE.containsMatchIn(s) -> "food"
            // "좀 쉬었다 가자"·"쉬자"도(시험 33: AI를 거쳐야 쉼터로 갔다)
            ACHE_REST.any { it in s } -> if (restNo) "rest_no" else "rest"
            // 운전석 열선·통풍: 끄라는 말을 먼저 가른다
            "열선" in s && OFF.any { it in s } -> "heat_off"
            "통풍" in s && OFF.any { it in s } -> "vent_off"
            cold -> "warm"
            hot -> "cool"
            // 차 돌봄(정비): "와이퍼 갈아야 해?"·"엔진오일 얼마야?"·"블루핸즈 어디야". "와이퍼 갈았어"는 간 것을 적는다("언제 갈았지?"·"아직 안 갈았어"는 적지 않고 연다).
            // 김서림·아플 때·공기·열선 같은 급한 요청 뒤에 둔다("와이퍼 켜도 김 서려서 안 보여"는 성에 제거)
            // 정비소에 간 날(11/02): "고친 데가 또 이상해"는 정비한 날·무료 재정비 기간, "오늘 정비했어"는 오늘 날짜로 적기(항목 낱말이 있으면 항목으로)
            SHOP_AGAIN.any { it in s } && listOf("또", "다시", "이상", "고장", "안돼", "그대로").any { it in s } -> "shop_again"
            careKey(s) == null && SHOP_DONE.any { it in s } -> "shop_done"
            careKey(s)?.let { careDone(it, s) } == true -> "care_done"
            careKey(s) != null || CARE_ANY.any { it in s } -> "care"
            else -> "other"
        }
    }

    /**
     * 연기·탄내가 바깥에서 들어오는 것인가: 다른 차를 가리키거나("앞차에서 연기 나") 바깥 말("밖에서 타는 냄새 들어와")이 있고 이 차를 가리키는 말이 없을 때.
     * "우리 차"·"내 차"라고 하면 늘 이 차의 불로 본다(기획안대로 세우라고 알린다)
     */
    private fun smokeOutside(s: String): Boolean {
        if (listOf("우리차", "내차", "제차", "이차에서").any { it in s }) return false
        return listOf("앞차", "옆차", "뒤차", "다른차", "저차").any { it in s } || OUT_SMOKE.any { it in s } && OWN_SMOKE.none { it in s }
    }

    /** 남의 사고·지난 사고·짐작의 말: 남의 일이 분명하면 한 줄만, 아니면 사고 안내를 열지 한 번 묻는다. 이미 사고 안내 중이면 그대로 둔다(ride/Crash가 맡는다) */
    private fun accMaybe(s: String, u: StageUi): String = when {
        u.scene == Scene.ACCIDENT && !u.breakdown && !u.fire -> "accident"
        // [긴급]을 눌러 연 첫 화면("무슨 일인가요?")에서 한 사고 말은 묻지 않고 연다(이 화면에는 확인 카드 자리도 없다)
        u.scene == Scene.EMERGENCY -> "accident"
        !u.scene.isE && ACC_OTHERS.containsMatchIn(s) && ACC_WITH.none { it in s } -> "say_others_acc"
        else -> "ask_acc"
    }

    /**
     * 고장 말을 가른다(검토 11/07 멋대로 판단): 부품 이름("와이퍼가 고장 났나 봐"·"에어컨 고장")이면 차 돌봄으로, 달리는 중이면 정말 섰는지 한 번 묻는다.
     * 세워 둔 채 "고장 났어"·급할 때 화면에서 한 말은 기획안대로 바로 고장 안내
     */
    private fun broken(s: String, u: StageUi): String = when {
        BREAK_SERIOUS.none { it in s } && CAR_PART.any { it in s } -> "care_broken"
        !u.parked && !u.scene.isE -> "ask_stopped"
        else -> "breakdown"
    }

    /**
     * 아픈 말이 '아파'뿐인가(검토 11/07 멋대로 판단): 다른 증상·다른 사람이 없으면(심한 말은 위에서 먼저 갈랐다) 가벼운 통증으로 보고 바로 급할 때 화면을 열지 않는다.
     * 부탁이 붙었으면 그 부탁을(ache_req), "소리 때문에 귀 아파"는 볼륨 한 줄, 그 밖에는 "급한 상황이세요?"로 한 번 묻는다
     */
    private fun ache(s: String): String = when {
        ILL_SYMPTOM.any { it in s } || illWho(s, null) == true -> "illness"
        "귀" in s && listOf("소리", "볼륨", "시끄").any { it in s } -> "say_ear"
        ACHE_REQ.any { it in s } -> "ache_req"
        else -> "ask_ill"
    }

    /** 가벼운 통증의 말에서 아픈 말과 몸 자리 낱말("허리가"·"아파서")을 뺀 나머지(붙은 부탁만 고른다) */
    private fun dropAche(text: String) = words(text).filter { w ->
        ACHE.none { it in w } && BODY.none { b -> w.startsWith(b) && w.removePrefix(b) in PARTICLE }
    }.joinToString(" ")

    /** 운전 도움을 켜는 말인가: 운전자 자신이 초보라고 하거나("저 초보예요"·"초보라서") 켜 달라고 할 때("운전 도움 켜 줘")만 */
    private fun noviceOn(s: String) = !NOVICE_OTHER.containsMatchIn(s) &&
        (NOVICE_SELF.any { it in s } || listOf("운전도움", "초보운전", "초보모드").any { it in s } && NOVICE_ASK.any { it in s })

    /**
     * 같이 고르기에서 번호("세 번째로 가자"·"3번"·"마지막")와 "거기로 가자"를 알아듣는다. 알아들었으면 true.
     * 번호만이면 고르기만 하고, 가자는 말이 붙으면 그곳을 내비에 넣는다(카드를 누르고 [여기로 가요]를 누른 것과 같다)
     */
    private fun pickBy(s: String): Boolean {
        val p = ui.value.pick
        val n = ORDINALS.indexOfFirst { w -> w.any { it in s } }.takeIf { it >= 0 } ?: if ("마지막" in s && p.items.isNotEmpty()) p.items.lastIndex else null
        val go = PICK_GO.any { it in s }
        // 번호 없이는 고른 곳을 가리키는 말("거기로 가자")이나 "가자" 한마디만. "카페 가자"·"쉬어 가자"는 route에 맡긴다
        if (n == null && PICK_THERE.none { it in s } && s.trimEnd('.', '!', '?', '~') !in PICK_GO) return false
        // 번호 없이 '거기가'·'여기가'만 있으면("거기가 별로야") 가자는 말이 아니다
        if (n == null && !go) return false
        Prend.log("· 프렌드: 들은 말 ${s.length}자 → 같이 고르기${n?.let { " ${it + 1}번" } ?: ""}${if (go) " 가기" else ""}")
        if (n != null) {
            if (n > p.items.lastIndex) { toast(if (p.items.isEmpty()) "아직 고를 곳이 없어요" else "${p.items.size}번까지 있어요"); return true }
            pick("choose", "$n")
            if (!go) { toast("${n + 1}번을 골랐어요. “가자” 하시면 길에 넣을게요", 3_000); return true }
        }
        if (ui.value.pick.chosen >= 0) pick("go") else toast("몇 번으로 갈까요?")
        return true
    }

    /** 응급처치 카드를 연다. 달리는 중이면 아플 때 안내(119 먼저, 가까운 병원)를 열고 P에 넣으면 그 카드를 연다(ride/Crash).
     *  경련 시계는 말한 때부터 잰다 */
    private fun aid(kind: Aid, s: String, kid: Boolean?) {
        // 아이 이야기면 카드 뒤 [가까운 병원]에서 달빛어린이병원을 먼저
        ui.update { it.copy(kid = kid ?: (kind == Aid.INFANT || child(s))) }
        if (ui.value.parked || ui.value.demo) openAid(kind)
        else {
            // 달리며 같은 응급처치를 다시 말했으면 아플 때 안내의 걸음을 처음으로 돌리지 않는다(11/08 오류 점검 A3)
            if (ui.value.scene == Scene.ILLNESS && ui.value.aidWant == kind) { Prend.log("· 이미 같은 응급처치를 기다리는 아플 때 안내 중 → 걸음 그대로"); return }
            val now = System.currentTimeMillis()
            val since = if (kind == Aid.SEIZURE) szClock(now) else now
            ui.update { it.copy(aidWant = kind, aid = kind, aidSince = since, szSince = if (kind == Aid.SEIZURE) since else it.szSince) }
            go(Scene.ILLNESS)
        }
    }

    /** 고른 할 일을 한다. kid는 AI가 아픈 사람이 아이라고 본 것(null이면 낱말로 가른다) */
    private fun act(intent: String, s: String, kid: Boolean?) {
        when (intent) {
            // 고장 안내 중 "사고 났어": 고장 표시를 끄고 사고 안내로(ride/Crash가 "괜찮으세요?"부터 새로).
            // 연기·불 안내 중이면 대피 안내는 그대로 두고 괜찮은지만 묻는다(11/06: 전에는 걸음만 처음으로 돌아가고 목소리가 멈췄다)
            "accident" -> when {
                ui.value.scene == Scene.ACCIDENT && ui.value.fire -> onAccidentSaid?.invoke() ?: Unit
                // 사고 안내 중 다쳤다는 말(AI 대역이 사고로 고른 "다쳤어요"): [다쳤어요]를 누른 것처럼(검토 11/07 멋대로 판단, ride/Crash)
                ui.value.scene == Scene.ACCIDENT && !ui.value.breakdown && onHurtSaid?.invoke(s, false) == true -> Unit
                // 이미 사고 안내 중이면 걸음과 목소리를 그대로 둔다("사고 났는데 보험 어떻게 해?"에 걸음이 1로 돌아가 안내 목소리가 멈췄다, 11/08 오류 점검 A3). 고장 → 사고는 아래로
                ui.value.scene == Scene.ACCIDENT && !ui.value.breakdown -> Prend.log("· 이미 사고 안내 중 → 걸음 그대로")
                else -> { if (ui.value.breakdown) ui.update { it.copy(breakdown = false) }; go(Scene.ACCIDENT) }
            }
            // 사고·연기 안내 중에는 고장 안내로 바꾸지 않는다(검토 11/07 멋대로 판단 원칙 5: "차가 안 움직여요" 한마디에 SOS 띠·119 걸음·"괜찮으세요?"가 사라졌다).
            // 사고 안내는 그대로 두고 견인이 있는 걸음을 알린다("견인차 왔어"처럼 안내 안에서). 연기·불 안내면 멀리 떨어지라는 말을 다시 보인다
            "breakdown" -> when {
                ui.value.scene == Scene.ACCIDENT && ui.value.fire -> { Prend.log("· 연기·불 안내 중 고장 말 → 안내 그대로"); toast("연기가 나면 차에서 멀리 떨어지세요", 3_500) }
                ui.value.scene == Scene.ACCIDENT && !ui.value.breakdown -> {
                    val n = Guide.steps(ui.value).indexOfFirst { "견인" in it.title || it.sub?.contains("견인") == true } + 1
                    Prend.log("· 사고 안내 중 고장 말 → 사고 안내 그대로, 견인 걸음 알림")
                    toast(if (n > 0) "견인은 화면 ${n}번째 걸음에 있어요" else "견인은 보험사 긴급출동으로 부르세요", 4_000)
                }
                else -> breakdown()
            }
            "fire" -> fire()
            "tow" -> towCame()
            "illness" -> {
                // 사고 안내 중의 아프다는 말("다리가 아파요"): 아플 때 안내로 갈아타지 않고 [다쳤어요]를 누른 것처럼(검토 11/07 멋대로 판단 원칙 5, ride/Crash)
                if (ui.value.scene == Scene.ACCIDENT && onHurtSaid?.invoke(s, true) == true) return
                // 아이 이야기면 달빛어린이병원을 먼저 권한다(낱말만 보고, 들은 말은 남기지 않는다).
                // 아플 때 안내 중에 누군지 말하지 않은 말("빨리 아파요")이면 앞서 가른 사람을 둔다(ride/Crash가 아픈 사람이 바뀌면 처음부터 다시 안내한다, 11/06)
                val who = illWho(s, kid)
                // 운전자가 아프다고 한 뒤 같이 탄 사람 말이 섞이면 바로 바꾸지 않고 화면으로 한 번 묻는다
                // (검토 11/07 멋대로 판단 원칙 4: "아이구 아파"·"엄마야"를 같이 탄 사람이 아픈 것으로 읽어 아픈 운전자에게 병원 길을 권했다, ride/Crash)
                if (who == true && ui.value.scene == Scene.ILLNESS && onWhoSaid?.invoke(kid ?: child(s)) == true) return
                val keep = who == null && ui.value.scene == Scene.ILLNESS
                // 이미 같은 사람의 아플 때 안내 중이면 걸음과 목소리를 그대로 둔다(걸음이 1로 돌아가면 ride/Crash가 사람이 넘긴 것으로 보고 목소리를 멈췄다, 11/08 오류 점검 A3)
                if (ui.value.scene == Scene.ILLNESS && (keep || who == ui.value.illOther && (kid ?: child(s)) == ui.value.kid)) { Prend.log("· 이미 아플 때 안내 중 → 걸음 그대로"); return }
                ui.update { if (keep) it else it.copy(kid = kid ?: child(s), illOther = who) }
                go(Scene.ILLNESS)
            }
            // 아기·아이가 숨을 안 쉬면 어른 카드(두 손·5cm)가 맞지 않다: 119가 먼저인 아플 때 안내로(구급상황요원이 전화로 이끈다)
            "aid_cpr" -> if (kid == true || child(s)) act("illness", s, true) else aid(Aid.CPR, s, kid)
            "aid_choke" -> aid(Aid.CHOKE, s, kid)
            "aid_infant" -> aid(Aid.INFANT, s, kid)
            "aid_seizure" -> aid(Aid.SEIZURE, s, kid)
            "food", "cafe" -> openPick(intent)
            "memo" -> openMemo()
            "memo_more" -> openMemoMore()
            "insurer" -> openInsurer()
            "talk_on" -> talk(true)
            "talk_off" -> talk(false)
            // 낱말 규칙에 없던 되묻기("뭐라 하셨어요?", AI 대역): 대답 창은 이미 닫혔으니 방금 한 말만 천천히 다시(ride/SlowTalk)
            "again" -> onAgain?.invoke(null) ?: toast("방금 드린 말이 없어요", 2_000)
            // 급할 때 화면에서는 차 돌봄으로 넘어가지 않는다(사고·아플 때 안내가 끊기지 않게)
            "care", "care_done" -> if (ui.value.scene.isE) toast("다시 한 번 말씀해 주세요") else if (intent == "care") openCare(careKey(s).orEmpty()) else care("done_said", careKey(s).orEmpty())
            "park", "fuel", "toll", "fog" -> onCoach?.invoke(intent) ?: toast("다시 한 번 말씀해 주세요")
            "novice_on" -> novice(true)
            "shop_done", "shop_again" -> onShop?.invoke(if (intent == "shop_done") "done" else "again") ?: toast("다시 한 번 말씀해 주세요")
            "dusk_on" -> onDusk?.invoke() ?: toast("다시 한 번 말씀해 주세요")
            "slow_on", "slow_off" -> onSlow?.let { it(intent == "slow_on"); toast(if (intent == "slow_on") "앞으로 천천히 말할게요" else "원래 빠르기로 말할게요", 2_500) } ?: toast("다시 한 번 말씀해 주세요")
            "novice_off" -> novice(false)
            "daeri_on" -> daeri(true)
            "daeri_off" -> daeri(false)
            "fresh_air", "inside_air" -> onAir?.invoke(intent == "fresh_air") ?: toast("공기는 차에서만 바꿀 수 있어요")
            // "트로트 틀어 줘"·"상어 노래"에 잔잔한 PREND 곡을 틀지 않는다(11/04 사람 시험): 곡은 하나뿐이라 알리기만
            "music" -> if (GENRE.any { it in s }) toast("${Prend.NAME} 곡은 잔잔한 곡 하나뿐이에요 · 라디오나 음악 앱에서 틀어 주세요", 4_000)
                else onMusic?.invoke(true) ?: toast("${Prend.NAME} 곡은 운행 서비스가 있어야 해요")
            "music_off" -> onMusic?.invoke(false) ?: toast("${Prend.NAME} 곡은 운행 서비스가 있어야 해요")
            "rain_on", "rain_off" -> onRain?.invoke(intent == "rain_on") ?: toast("빗소리는 운행 서비스가 있어야 해요")
            // "애 잔다"·"아기 자요": 카시트 아이는 좌석 센서가 몰라 혼자로 보이니, 잔다는 말이면 목소리 대신 차임과 글로 받고 아이 말이면 이번 운행 아이 탑승도 켠다(검토 11/06)
            "quiet_ride" -> {
                val asleep = ASLEEP.any { it in s.replace("가자", "") }
                quietRide(true, asleep, asleep && (child(s) || KID_ASLEEP.any { it in s }))
            }
            "quiet_off" -> quietRide(false)
            "spot_off" -> onQuiet?.invoke("SPOT") ?: toast("다시 한 번 말씀해 주세요")
            "baby_on" -> baby(true)
            "baby_off" -> baby(false)
            // 세워 두면 운전자의 쪽잠. 대리 운행이면 세워 둬도 주인의 잘래요. 달리는 중이면 누가 쉬려는지 가른다
            // (운전자는 달리며 잘 수 없다: 혼자 운전하는 사람의 "자고 싶다"는 졸음이라 쉼터를 권한다, 검토 11/07 멋대로 판단 원칙 4)
            "nap" -> when {
                ui.value.daeri -> sleep(true)
                ui.value.parked -> napStart(ui.value.habits.napMin ?: 15)
                else -> sleepWho(s)
            }
            // 곧바로 경유지에 넣지 않는다: 쉼터 카드로 권하고 "응"·[경유지로]일 때만(별첨 2 §5-2 ④)
            "rest" -> onRest?.takeIf { !ui.value.demo }?.invoke() ?: go(Scene.DRIVE)
            // 곧바로 넣지 않는다: 화장실 카드로 권하고 "응"·[경유지로]일 때만
            "toilet" -> if (ui.value.scene.isE) toast("다시 한 번 말씀해 주세요")
                else onToilet?.takeIf { !ui.value.demo }?.invoke(s) ?: toast("가까운 화장실은 운행 서비스가 있어야 찾아요", 3_000)
            // 마다하는 말: 화장실 카드가 떠 있으면 [괜찮아요]를 누른 것과 같고, 아니면 그대로 간다
            "toilet_no" -> if (ui.value.ask?.id == "TOILET") answer("TOILET", false) else { onToiletNo?.invoke(); toast("네, 그대로 갈게요") }
            // 마다하는 말: 쉼터 카드가 떠 있으면 [괜찮아요]를 누른 것과 같고, 아니면 그대로 간다
            // [프렌드]가 끊은 쉼터 물음은 줄 앞에 되돌아가 있어, 마다했다고 알려야 같은 물음이 다시 나오지 않는다(ride/Care가 붙인다, 11/08 오류 점검 A8)
            "rest_no" -> if (ui.value.ask?.id == "REST") answer("REST", false) else { onRestNo?.invoke(); toast("네, 그대로 갈게요") }
            "nap_off" -> when {
                ui.value.sleeper != null -> sleep(false)
                ui.value.napPhase != null -> napStop()
                else -> toast("지금은 쉬는 중이 아니에요")
            }
            "warm", "cool", "heat_off", "vent_off" -> onSeat?.takeIf { !ui.value.demo }?.invoke(intent)
                ?: if (intent == "warm") { ui.update { it.copy(heat = 1, vent = 0) }; toast("운전석 열선을 켰어요") } else toast("다시 한 번 말씀해 주세요")
            "warm_at", "cool_at", "heat_off_at", "vent_off_at" -> onSeatAt?.takeIf { !ui.value.demo }?.invoke(intent.removeSuffix("_at"), other(s) ?: "adult")
                ?: toast(when (intent) { "warm_at" -> "그 자리를 따뜻하게 할게요"; "cool_at" -> "그 자리를 시원하게 할게요"; else -> "그 자리를 끌게요" })
            "motion" -> onMotion?.takeIf { !ui.value.demo }?.invoke() ?: toast("바깥 공기로 바꾸고 바람을 얼굴 쪽으로 돌렸어요", 3_000)
            "song_what" -> onSong?.invoke("아까" in s) ?: toast("지금 곡은 화면 가운데에 보여요")
            "undo" -> onUndo?.invoke() ?: toast("지금은 되돌릴 일이 없어요")
            // ── 애매한 말: 짐작으로 움직이지 않고 카드로 한 번 묻거나, 맞는 기능·한 줄 알림으로 보낸다(검토 11/07 멋대로 판단) ──
            // 가벼운 통증("허리 아파"): [급해요]면 아플 때 안내, [쉬어 갈래요]면 쉼터 권하기, 대답이 없으면 그대로
            "ask_ill" -> {
                val heard = said
                once("ILL", "급한 상황이세요?", "많이 아프면 아플 때 안내를 열어요", "급해요", "쉬어 갈래요") { a ->
                    said = heard
                    when (a) { true -> act("illness", s, kid); false -> act("rest", s, null); null -> Unit }
                }
            }
            // 이 차의 사고인지 모르는 말("사고 날 뻔했어"·"앞에서 사고 났어"): [열기]일 때만 사고 안내(사고 메모도 그때만 적힌다)
            "ask_acc" -> once("ACC", "사고 안내를 열까요?", "이 차에 사고가 났으면 할 일을 차례로 알려 드려요", "열기", "아니요") { if (it == true) act("accident", s, null) }
            // 달리는 중의 고장 말: 대피·견인 안내 전에 정말 섰는지 묻는다
            "ask_stopped" -> once("STOPPED", "차가 서 버렸나요?", "고장이면 비상등부터 차례로 알려 드려요", "고장 났어요", "아니에요") { if (it == true) act("breakdown", s, null) }
            "ask_daeri" -> askDaeri()
            "ask_mem" -> once("MEM", "몸이 이상한 건가요?", "갑자기 기억을 못 하거나 말이 어눌하면 아플 때 안내를 열어요", "열기", "아니요") { if (it == true) act("illness", s, kid) }
            "ask_sleep" -> if (ui.value.scene.isE) toast("다시 한 번 말씀해 주세요")
                else once("SLEEPON", "잘래요를 켤까요?", "같이 탄 분이 쉬시게 음악과 무드등을 낮춰요", "켜 주세요", "아니요") { if (it == true) sleep(true) }
            // '안 보여'·'뿌얘'만 들은 말: 앞유리 김서림인지 묻고 [네, 켜 주세요]일 때만 성에 제거
            "ask_fog" -> once("FOG", "앞유리에 김이 서렸나요?", "그러면 앞유리 성에 제거를 켤게요", "네, 켜 주세요", "아니요") { if (it == true) act("fog", s, null) }
            // 부품 고장("와이퍼가 고장 났나 봐"): 그 항목의 차 돌봄으로(세우면 열린다). 차 돌봄에 없는 부품이면 한 줄만
            // 차 돌봄에 없는 부품이면 세워 둔 동안은 차 돌봄 목록(정비소 찾기)을 연다. "세운 뒤"는 달리는 동안에만(11/08 에뮬레이터: 세워 둔 채 "세운 뒤"가 떴다)
            "care_broken" -> careKey(s)?.takeIf { !ui.value.scene.isE }?.let { openCare(it) } ?: when {
                ui.value.scene.isE -> toast("고장 난 곳은 안내가 끝난 뒤 차 정비에서 볼 수 있어요", 3_500)
                ui.value.parked -> openCare()
                else -> toast("고장 난 곳은 세운 뒤 차 정비에서 볼 수 있어요", 3_500)
            }
            "outside_smoke" -> outsideSmoke()
            // 성에 제거 끄기·주차 코치 끄기(ride/Coach가 한다)
            "fog_off", "park_off" -> onCoach?.invoke(intent) ?: toast("다시 한 번 말씀해 주세요")
            "say_jam" -> toast("막힌 길이에요 · 돌아가는 길은 차의 음성 비서에게 물어보세요", 4_000)
            "say_others_acc" -> toast("앞쪽 사고로 막히는 길이면 돌아가는 길은 차의 음성 비서에게 물어보세요", 4_000)
            "say_glare" -> toast("앞이 잘 안 보이면 속도를 줄이고 차간 거리를 넉넉히 두세요", 4_000)
            "say_ear" -> toast("소리 크기는 차의 볼륨 단추로 바꿔 주세요", 3_500)
            "say_noise" -> toast("소리 크기는 차의 볼륨 단추로, 바람은 공조 단추로 바꿔 주세요", 4_000)
            "say_hvac" -> toast("에어컨·히터는 차의 공조 단추로 바꿔 주세요 · 운전석 열선·통풍은 말씀하시면 켜요", 4_000)
            // "119 불러": 급할 때 첫 화면([119 전화]와 사고·아플 때 고르기)을 열고 듣는다. 사고·아플 때 안내 중이면 세워 둔 동안 119 전화 화면을 바로 연다
            // (달리는 동안은 [119 전화] 단추를 두지 않는 화면이라 세우라고 알린다)
            "sos" -> when {
                !ui.value.scene.isE -> emergency()
                !ui.value.brief -> { guideTouched(); dial("119") }
                else -> toast("차를 세우면 [119 전화]가 보여요", 3_500)
            }
            "say_parklot" -> toast("주차장 찾기는 차의 음성 비서에게 물어보세요", 3_500)
            "say_spot" -> toast("주차 자리를 찍어 두셨다면 사진첩에서 찾아보세요", 3_500)
            // 아무 말도 없었으면 조용히 닫는다
            "none" -> Unit
            else -> toast(CANT, 4_000)
        }
    }

    /** 확인 카드를 그릴 자리가 없는 화면 */
    private val CARD_AWAY = setOf(Scene.EMERGENCY, Scene.MEMO, Scene.PICK, Scene.CARE)

    /** 같은 확인 카드가 이미 떠 있으면 다시 띄우지 않는다(같은 id를 겹쳐 띄우면 앞 카드의 시간 끝이 새 카드를 거둔다) */
    private fun once(id: String, title: String, text: String, yes: String, no: String, pick: Pair<List<String>, List<String>>? = null, done: (Boolean?) -> Unit) {
        if (ui.value.ask?.id == "Q_$id") { Prend.log("· 확인 카드 $id: 이미 떠 있음"); return }
        // 확인 카드는 첫 화면(물음 카드 자리)과 사고·아플 때 걸음(위 띠)에만 그린다. 급할 때 첫 화면·기억·같이 고르기·차 돌봄 화면이면 첫 화면으로 옮겨 띄운다
        if (ui.value.scene in CARD_AWAY) go(Scene.HOME)
        confirm(id, title, text, yes, no, pick = pick, done = done)
    }

    /**
     * "대리 왔어"·"대리운전이야"(검토 11/07 멋대로 판단): 소리 없이 졸음 안내가 꺼지는 일이라 밤 대리 물음과 같은 카드로 한 번 묻는다.
     * [네, 대리예요]면 대리 운행으로 두고 잘래요도 켠다(사용자 결정 10/31, ride/Care)
     */
    private fun askDaeri() = once("DAERI", "대리 운행이세요?", "그러면 먼저 말 걸지 않고 조용히 모실게요", "네, 대리예요", "아니요") { if (it == true) daeri(true) }

    /**
     * 달리는 중 "자고 싶다"·"잠깐 잘래"·"눈 좀 붙이고 싶다"(검토 11/07 멋대로 판단 원칙 4): 운전자가 졸린 말이면 "졸려"와 같게 쉼터를 권하고, 같이 탄 사람이면 잘래요.
     * 말에 누구인지 없으면 옆자리·뒷자리 사람(좌석)이나 이번 운행 아이 탑승을 보고, 누가 탔을 수 있으면 한 번 묻는다(대답이 없으면 그대로 둔다).
     * 뒷문을 연 기록은 소리 쪽(SoundDirector)에만 있어 여기서는 보지 않는다
     */
    private fun sleepWho(s: String) {
        val heard = said
        when (illWho(s, null)) {
            true -> sleep(true)
            false -> { Prend.log("· 달리는 중 자고 싶다: 운전자 말이라 쉼터 권하기로"); act("rest", s, null) }
            null -> if (!ui.value.others && !ui.value.baby) { Prend.log("· 달리는 중 자고 싶다: 같이 탄 사람이 없어 쉼터 권하기로"); act("rest", s, null) }
                else once("SLEEPWHO", "누가 쉬실 건가요?", "운전하는 분이 졸리면 쉼터를 찾아 드려요", "옆자리 분", "제가 졸려요",
                    pick = listOf("옆자리", "옆에", "손님", "동승", "엄마", "아빠", "애", "아이", "뒤에", "누나", "언니", "오빠", "형", "남편", "아내", "와이프", "할머니", "할아버지", "친구", "동생") to
                        // '졸려'만으로는 고르지 않는다("누나가 졸려"가 운전자로 갔다). "졸려요"만 하면 카드가 그 말을 [프렌드]에 한 말처럼 골라 쉼터를 권한다
                        listOf("제가", "내가", "=나", "=저", "=난", "=전")) { a ->
                    said = heard
                    when (a) { true -> sleep(true); false -> act("rest", s, null); null -> Unit }
                }
        }
    }

    /**
     * AI 대역이 고른 일을 한다(검토 11/07 멋대로 판단). 설정을 바꾸는 일(운전 도움·해 지기 전 알림·천천히 말하기·대리 운행·조용히 가기·잘래요·쪽잠·아이 탑승)과
     * 고장·연기 안내는 카드로 한 번 묻고 [예]일 때만 한다. 사고·아플 때는 낯선 말로 급한 일을 알리는 사람을 늦추지 않게 바로 열되, 짐작으로 열었다는 표시(guess)를 둔다
     */
    private fun aiAct(picked: String, s: String, kid: Boolean?, scene: Scene) {
        val heard = said
        // [긴급]을 눌러 연 첫 화면에서 AI 대역이 고장·연기로 고른 말은 묻지 않고 그 안내를 연다(사고·아플 때처럼. 이 화면에는 확인 카드 자리가 없다)
        if (scene == Scene.EMERGENCY && (picked == "breakdown" || picked == "fire")) { act(picked, s, kid); return }
        AI_ASKS[picked]?.let { (title, text, yes) ->
            once("AI_${picked.uppercase()}", title, text, yes, "아니요") { a -> if (a == true) { said = heard; act(picked, s, kid) } }
            return
        }
        when {
            picked == "daeri_on" -> if (ui.value.daeri) act(picked, s, kid) else askDaeri()
            picked == "nap" -> when {
                ui.value.daeri -> once("AI_SLEEP", "편히 쉬실 건가요?", "불빛을 낮추고 도착할 때 깨워 드려요", "쉴게요", "아니요") { if (it == true) sleep(true) }
                ui.value.parked -> once("AI_NAP", "쪽잠을 잘까요?", "세워 둔 동안 정한 시간에 깨워 드려요", "잘게요", "아니요") { if (it == true) { said = heard; act("nap", s, kid) } }
                else -> sleepWho(s)
            }
            !scene.isE && (picked == "accident" || picked == "illness") -> {
                Prend.log("· 프렌드(AI 대역): 짐작으로 연 안내라 표시를 둠")
                ui.update { it.copy(guess = if (picked == "accident") "사고" else "아플 때") }
                act(picked, s, kid)
            }
            else -> act(picked, s, kid)
        }
    }

    /** 바깥 연기·탄내(검토 11/07 멋대로 판단): 안쪽 공기로 바꾸고, 운행 서비스가 띄운 "안쪽 공기로 돌릴게요"에 까닭을 붙인다(바꾸지 못했다는 글은 그대로 둔다) */
    private fun outsideSmoke() {
        val air = onAir ?: return toast("공기는 차에서만 바꿀 수 있어요")
        air(false)
        listOf(800L, 2_000L).forEach { d -> main.postDelayed({ if (ui.value.toast == AIR_IN) toast(AIR_IN_SMELL, 4_000) }, d) }
    }
}
