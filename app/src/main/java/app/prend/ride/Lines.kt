package app.prend.ride

/** 목소리 톤(기획안 v6 §6-5). 탄 사람과 대화의 결로 고른다 */
enum class Tone(val label: String) { SOLO("차분"), EASY("편안"), BRIGHT("밝게"), BABY("아이"), WHISPER("속삭임") }

/**
 * 시연 음악 판단(RideBrain)이 화면 자막으로 쓰는 대사집. 사람이 쓴 줄만 쓰고, { } 자리는 앱이 날씨·요일·고른 것으로 채운다
 * (생성형 AI는 줄을 짓지도 고르지도 않는다. AI가 하는 일은 [프렌드]가 못 알아들은 말을 할 일로 바꾸는 것 하나).
 * 대사 원칙(별첨 v9): 두 문장까지, 끝은 사람에게 넘긴다. 칭찬·운전 평가·마지막 인사·감정 짐작은 쓰지 않는다(급할 때 안내는 안내원 말투로 따로).
 * 목소리로 내는 줄은 tools/voice의 JSON에 있고 같은 원칙을 따른다(어긋났던 줄은 ride4.json에서 고쳐 씀, 감사 F110)
 */
object Lines {
    const val OFFER_JAM = "offer_jam"
    const val OFFER_RAIN = "offer_rain"
    const val OFFER_OPEN = "offer_open"
    const val ICEBREAK = "icebreak"
    const val ICEBREAK_NIGHT = "icebreak_night"
    const val HALF = "half"
    const val ONE_SIDE = "one_side"
    const val GROUP = "group"
    const val SONG_INFO = "song_info"
    const val RAIN_ON = "rain_on"
    const val RAIN_OFF = "rain_off"
    const val QUIET = "quiet"
    const val STOP = "stop"
    const val BABY_FIRST = "baby_first"
    const val BABY_SLEEP = "baby_sleep"
    const val SLEEP = "sleep"
    const val WAKE_5 = "wake_5"
    const val WAKE_P = "wake_p"

    val text = mapOf(
        OFFER_JAM to "차가 좀 밀리네요. 한 곡 깔아 둘까요?",
        OFFER_RAIN to "비 오는 날엔 음악이 좀 있어야죠. 틀어 볼까요?",
        OFFER_OPEN to "길이 시원하게 뚫렸네요. 음악은 가볍게 갈까요, 진하게 갈까요?",
        ICEBREAK to "{날씨}{요일}이네요. 음악은 가볍게 갈까요, 진하게 갈까요?",
        ICEBREAK_NIGHT to "밤길이네요. 잔잔하게 갈까요, 조금 신나게 갈까요?",
        HALF to "그럼 반반 갈게요. 가볍게 시작해서 진해지는 곡으로요.",
        ONE_SIDE to "좋아요, {고른 것} 가 볼게요.",
        GROUP to "다 같이 아는 노래로 갈까요, 잔잔하게 깔아 둘까요?",
        SONG_INFO to "저희가 만든 '{곡}'이에요. 이런 노래 좋아하세요?",
        RAIN_ON to "빗소리 조금 깔아 둘게요.",
        RAIN_OFF to "네, 빗소리는 빼 둘게요.",
        QUIET to "네, 오늘은 조용히 모실게요.",
        STOP to "네, 음악만 틀어 둘게요.",
        BABY_FIRST to "꼬마 손님이 있네요. 신나는 노래로 갈까요, 자장 노래로 갈까요?",
        BABY_SLEEP to "쉿, 조용한 곡으로 바꿔 둘게요.",
        SLEEP to "네, 편히 쉬세요. 도착 전에 깨워 드릴게요.",
        WAKE_5 to "5분 남았어요. 천천히 일어나셔도 돼요.",
        WAKE_P to "다 왔어요. 천천히 일어나세요.",
    )

    fun fill(id: String, slots: Map<String, String> = emptyMap()): String {
        var s = text[id] ?: id
        slots.forEach { (k, v) -> s = s.replace("{$k}", v) }
        return s
    }
}
