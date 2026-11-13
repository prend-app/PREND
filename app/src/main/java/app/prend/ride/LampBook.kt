package app.prend.ride

/**
 * 경고등 뜻(차 정비 화면의 '경고등이 켜졌을 때', 11/13 사용자 요청: "초보 운전을 위한 기능에서 경고등이 켜져 있을 때 그게 뭔지 설명하는 기능").
 * 일반 앱은 어떤 경고등이 켜졌는지 읽을 수 없다(ride/Novice 머리말, 2주차 시험 2). 그래서 차가 먼저 알려 주지 못하고,
 * 운전자가 "경고등 켜졌어"라고 하거나 차 정비 화면에서 열어 같은 모양을 골라 본다.
 * 글의 근거: 현대차 취급설명서 '경고등 및 표시등'(2025 투싼, ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/warning_indicator_lights.html, 11/13 읽음).
 * - 설명서에 적힌 것(언제 켜지나, 켜졌을 때 할 일, 계속 달리면 생기는 일)만 쉬운 말로 옮겼다. 설명서에 없는 조치는 지어 넣지 않는다
 * - 모양을 말로 푼 줄(look)은 계기판에서 같은 것을 찾게 하려는 것이다. 차마다 모양과 색이 조금 달라 화면에 그렇게 적는다
 * - 설명서 목록에 색이 그림으로만 나오는 경고등 가운데 색을 확실히 아는 열 가지만 실었다(전동 조향·전자식 주차 브레이크는 뺐다)
 * - 연료 부족은 전기차에서는 보이지 않는다
 */
object LampBook {

    /** 빨간색(바로 조치)과 노란색(곧 점검) */
    enum class Tone { RED, AMBER }

    data class Lamp(
        val key: String, val label: String, val tone: Tone,
        /** 계기판에서 찾을 모양(말로) */
        val look: String,
        val whenOn: List<String>, val todo: List<String>, val more: List<String> = emptyList(),
        val fuelOnly: Boolean = false,
    )

    const val SOURCE = "현대차 취급설명서 ‘경고등 및 표시등’(2025 투싼)을 옮겼어요 · 차마다 모양과 뜻이 조금 달라요. 내 차 취급설명서가 기준이에요"
    const val HINT = "켜진 것과 같은 모양을 골라 보세요 · 달리는 중이면 안전한 곳에 세운 뒤에 살펴요"
    private const val SHOP = "블루핸즈에서 점검받아요"

    private val ALL = listOf(
        Lamp(
            "oil", "엔진오일 압력", Tone.RED, "방울이 떨어지는 주전자",
            listOf("엔진오일이 모자라 오일 압력이 낮아지면 켜져요"),
            listOf("길옆 안전한 곳에 세워요", "오일 양을 보고, 모자라면 채워요", "채워도 꺼지지 않으면 $SHOP"),
            listOf("켜진 채로 계속 달리면 엔진이 고장 날 수 있어요"),
        ),
        Lamp(
            "charge", "충전", Tone.RED, "＋ － 가 적힌 네모난 배터리",
            listOf("벨트가 끊어지거나 충전 장치에 이상이 있으면 켜져요"),
            listOf("배터리 충전 상태와 벨트, 충전 계통을 점검받아요"),
            listOf("켜진 채로 계속 달리면 엔진이 과열되거나 배터리가 방전될 수 있어요"),
        ),
        Lamp(
            "brake", "브레이크", Tone.RED, "동그라미 안의 느낌표",
            listOf("주차 브레이크가 걸려 있으면 켜져요", "풀었는데도 달리는 중에 켜지면 제동 계통에 이상이 있다는 뜻이에요"),
            listOf("급하게 밟지 말고 서서히 안전한 곳에 세워요", "브레이크액이 모자라면 채운 뒤에 달려요", "채워도 켜져 있으면 $SHOP"),
            listOf("ABS 경고등과 함께 켜지면 빠르게 달리거나 급하게 밟지 말고 곧바로 점검받아요"),
        ),
        Lamp(
            "airbag", "에어백", Tone.RED, "앉은 사람 앞의 둥근 공",
            listOf("시동을 걸면 몇 초 켜졌다 꺼져요", "켜지지 않거나 6초 뒤에도 꺼지지 않으면 이상이 있다는 뜻이에요"),
            listOf(SHOP),
        ),
        Lamp(
            "belt", "안전벨트", Tone.RED, "벨트를 맨 사람",
            listOf("벨트를 매지 않고 시동을 걸거나, 달리는 중에 풀면 켜져요"),
            listOf("벨트를 매면 바로 꺼져요"),
        ),
        Lamp(
            "engine", "엔진", Tone.AMBER, "엔진 블록",
            listOf("시동을 걸고 3초가 지나도 꺼지지 않으면 엔진 제어 장치나 연료 장치에 이상이 있다는 뜻이에요"),
            listOf("가능한 한 빨리 $SHOP", "시동이 걸리지 않으면 견인을 불러요"),
            listOf("켜지거나 깜빡이는 채로 계속 달리면 배기 계통이 상할 수 있어요"),
        ),
        Lamp(
            "abs", "ABS", Tone.AMBER, "동그라미 안의 ABS 글자",
            listOf("시동을 걸고 3초가 지나도 켜져 있으면 ABS에 이상이 있다는 뜻이에요"),
            listOf("안전한 곳에 세우고 시동을 껐다 다시 걸어 봐요", "꺼지지 않거나 달리는 중에 다시 켜지면 $SHOP"),
            listOf("브레이크 경고등과 함께 켜지면 빠르게 달리거나 급하게 밟지 말고 곧바로 점검받아요"),
        ),
        Lamp(
            "esc", "차체 자세 제어", Tone.AMBER, "미끄러지는 차와 구불구불한 두 줄",
            listOf("미끄러운 길에서 장치가 일하는 동안에는 깜빡여요. 고장이 아니에요", "시동을 걸고 3초가 지나도 꺼지지 않거나 달리는 중에 켜지면 이상이 있다는 뜻이에요"),
            listOf("계속 켜져 있으면 $SHOP"),
        ),
        Lamp(
            "tire", "타이어 공기압", Tone.AMBER, "납작한 타이어 안의 느낌표",
            listOf("타이어 공기압이 많이 낮으면 켜져요", "1분쯤 깜빡이다 켜지면 공기압 감지 장치에 이상이 있다는 뜻이에요"),
            listOf("가까운 곳에서 공기압을 맞춰요", "감지 장치 이상이면 $SHOP"),
            listOf("공기압이 낮으면 차가 불안정하고 멈추는 거리가 길어져 매우 위험해요"),
        ),
        Lamp(
            "fuel", "연료 부족", Tone.AMBER, "주유기",
            listOf("남은 연료가 적으면 켜져요"),
            listOf("바로 연료를 채워요"),
            listOf("연료를 다 쓴 채로 달리면 엔진과 연료 장치가 고장 날 수 있어요"),
            fuelOnly = true,
        ),
    )

    /** 전기차면 연료 부족을 빼고 보인다 */
    fun lamps(electric: Boolean?) = if (electric == true) ALL.filter { !it.fuelOnly } else ALL

    fun lamp(key: String) = ALL.firstOrNull { it.key == key }
}
