package app.prend.ride

/**
 * 물음에 한 대답이 예인가 아니오인가(11/02 고침).
 *
 * 전에는 예 낱말이 말 안에 들어 있기만 하면 예로 봐서 "필요 없어"의 '어', "쉬지 않을래"의 '쉬'가 예가 됐다
 * (김서림 물음에 "필요 없어"면 바깥 공기로 바꾸고, 병원 물음에 "필요 없어"면 경유지를 넣었다).
 * - 거절 낱말을 먼저 본다. "안 잘래"·"안잘래"처럼 낱말 앞의 '안'도 거절로 본다(안전·안내·안쪽 같은 낱말은 빼고).
 * - 한두 글자 예 낱말(응·어·네·예)은 대답 전체이거나 첫 낱말일 때만 예로 본다("네 넣어 줘"는 예, "필요 없어"는 아님).
 * 검토 11/07 멋대로 판단:
 * - 되물은 "네?"·"응?"이나 '어'·'엉' 한마디는 어느 물음에서도 예가 아니다(모름: 묻는 쪽이 한 번 다시 묻는다).
 * - 짧은 예 뒤에 이야기를 잇는 말이 오면 예로 보지 않는다("어, 그래서…"·"응 근데 추워"는 곁의 이야기일 수 있다).
 * - 긴 예 낱말은 낱말 앞자리에서만 찾는다("그래서"의 '그래', "쉬운"의 '쉬'는 예가 아니다. "그래요"·"좋아요"·"넣어 줘"는 예).
 * - 거절처럼 보여도 그러자는 말인 꼴("안 그래도 쉬려고 했어"·"아무 데나 괜찮아"·"커피 말고 쉼터로 넣어 줘")은 거절로 보지 않고,
 *   그러자는 말과 아니라는 말이 섞여 갈리지 않으면 한쪽으로 정하지 않는다(모름)
 */
object Answer {
    /** 거절 낱말(빈칸을 지운 말에서 찾음) */
    val NO = listOf(
        "아니", "아뇨", "괜찮", "됐어", "됐다", "싫어", "나중", "없어", "필요없", "그만", "놔둬", "냅둬",
        "안해", "안할", "안돼", "지마", "말고", "않", "고마해",
    )

    /** 한두 글자 예 낱말: 대답 전체이거나 첫 낱말일 때만. 사투리 예(하모·그려·기여·오야·그랴·잉)도 같다(11/04 사람 시험: 경상·충청 어르신) */
    private val SHORT = setOf("응", "어", "네", "예", "웅", "엉", "넹", "넵", "옙", "응응", "어어", "네네", "예예", "ㅇㅇ", "하모", "그려", "기여", "오야", "그랴", "잉")

    /** 첫 낱말이거나 대답 전체일 때만 거절로 보는 말(사투리·짧은 거절). '어데로'·'꺼내' 같은 다른 뜻과 섞이지 않게 낱말째로 본다 */
    private val SHORT_NO = setOf("아이다", "아이요", "아입니다", "됐고", "패스", "꺼", "됐다마")
    /** 대답 전체일 때만 거절("어데?"는 '어디?'로도 쓴다) */
    private val WHOLE_NO = setOf("어데", "어데예")

    /** 예 낱말 뒤에 붙으면 대답이 아니라 곁의 사람에게 한 말이다("어어 잠깐만", "응 조금만 참아") */
    private val ASIDE = listOf("잠깐", "잠시", "기다려", "참아", "있어봐")

    /** '안'으로 시작해도 거절이 아닌 낱말(안전·안내·안쪽·안에·안녕·안개·안경·안심·안방·안과·안주·안팎) */
    private val AN_OK = setOf('전', '내', '쪽', '에', '녕', '개', '경', '심', '방', '과', '주', '팎')

    /** 그러자는 뜻이 분명한 말(낱말 앞자리에서 찾는다). 거절처럼 보이는 낱말과 함께 나오면 거절로 정하지 않는다 */
    private val GO = listOf("넣어", "가자", "쉬려고", "쉬자", "쉬어가", "바꿔", "부탁", "그래", "해줘", "해주세")
    /** 짧은 예 뒤에 와도 대답인 말("응 좋아요"·"네 그렇게 해 주세요"·"네 가 주세요"·"응 거기로") */
    private val ANSWER_OK = listOf(
        "그래", "좋아", "좋지", "좋습", "맞아", "맞습", "알겠", "알았", "오케이", "부탁", "고마", "감사", "해줘", "해주", "그렇게",
        "당연", "물론", "그럼", "얼른", "어서", "빨리", "가줘", "가주", "가요", "가자", "데려", "넣어", "거기", "그리로", "그쪽",
    )
    /** 짧은 예 바로 뒤에 오면 대답이 아니라 이야기를 잇는 말("어, 그래서…"·"응 근데 추워"). 앞의 LEAD_N개는 말머리에 와도 이야기를 잇는 말이다("그래서 대리는…") */
    private val CONNECT = listOf("그래서", "근데", "그런데", "그러니까", "그니까", "그래도", "그게", "있잖", "뭐", "왜")
    private const val LEAD_N = 5
    /** 예 낱말로 시작해도 다른 낱말인 것("그래서"·"그래도"·"좋아하는"·"쉬운"·"쉬마려"·"가자마자") */
    private val NOT_YES = listOf("그래서", "그래도", "그래가", "그래갖", "그래봤", "좋아하", "좋아해", "좋아지", "좋아졌", "쉬운", "쉬움", "쉬워", "쉬웠", "쉬마", "쉬하", "가자마자")
    /** 앞에 오면 뒤의 낱말을 부정하는 말("안 넣어도 돼"·"안 꺼도 돼") */
    private val NEG = setOf("안", "못")

    /** "안 그래도 쉬려고 했어"의 '안'은 거절이 아니다 */
    private val AN_GEUREDO = Regex("안\\s*그래도")
    /** "아무 데나 괜찮아"·"어디든 좋아": 고르는 것을 맡기는 말이라 예 */
    private val ANY_OK = Regex("(아무\\s*(데나|데라도|데든|곳이나|거나)|어디(든지|든|라도))\\s*(다\\s*)?(괜찮|좋)[가-힣]*")
    /** "그냥 가자"·"계속 가": 쉬지 않고 가자는 말인지 그곳으로 가자는 말인지 갈리지 않는다(정하지 않는다) */
    private val JUST_GO = Regex("(그냥|계속)\\s*(가자|갈래|가요|갈게|갈께|가던|가)(?![가-힣])")
    /** '로'로 끝나도 갈 곳이 아닌 낱말("바로 가자"·"그대로 둬") */
    private val NOT_DEST = setOf("바로", "새로", "따로", "별로", "주로", "진짜로", "정말로", "실제로", "절대로", "그대로", "이대로", "저대로", "마음대로", "맘대로", "제대로")

    private fun words(text: String) = text.trim().trimEnd('.', '!', '?', '~').split(Regex("[\\s,.!?~]+")).filter { it.isNotBlank() }

    /**
     * list 가운데 하나로 시작하는 낱말 자리(없으면 -1, from부터 본다). 빈칸은 낱말 앞에서만 보고 그 뒤는 이어 본다("넣어 줘"·"해 줘"도 잡는다).
     * NOT_YES의 더 긴 낱말이 그 자리에 있으면 짧은 예 낱말로 보지 않고("그래서"), 앞 낱말이 '안'·'못'이거나 "~지 마"가 붙으면 부정이라 뺀다
     */
    private fun at(w: List<String>, list: List<String>, from: Int = 0): Int {
        for (i in from until w.size) {
            if (i > 0 && w[i - 1] in NEG) continue
            val pair = w[i] + (w.getOrNull(i + 1) ?: "")
            if (listOf("지마", "지말", "지않").any { it in pair }) continue
            val rest = w.subList(i, w.size).joinToString("")
            if (list.any { y -> y.isNotEmpty() && rest.startsWith(y) && NOT_YES.none { n -> n.length > y.length && n.startsWith(y) && rest.startsWith(n) } }) return i
        }
        return -1
    }

    /** 말 안에 list의 낱말이 낱말 앞자리에서 시작하나(부정한 것은 빼고). "예약했어요"의 '했어'·"아프네요"의 '네'는 잡지 않는다 */
    fun has(text: String, list: List<String>): Boolean = at(words(text), list) >= 0

    /** 거절처럼 보이는 꼴을 걸러 낸 말. yes면 그 꼴 자체가 예("아무 데나 괜찮아"), mixed면 무엇을 골랐는지 갈리지 않는다 */
    private class Settled(val text: String, val yes: Boolean = false, val mixed: Boolean = false)

    /**
     * 거절 낱말이 들었어도 거절이 아닌 꼴을 가른다(검토 11/07). item은 물은 것을 가리키는 낱말(쉼터·화장실…, 모르면 빈 목록).
     * "○○ 말고 △△로/넣어": 물은 것을 마다했으면("쉼터 말고 그냥 가자") 거절 그대로, 다른 것을 마다하고 물은 것을 골랐으면("커피 말고 쉼터로 넣어 줘") 예,
     * 무엇을 골랐는지 알 수 없으면(물은 것을 모르는 쪽의 "응급실 말고 달빛 병원으로") 정하지 않는다
     */
    private fun settle(text: String, item: List<String>): Settled {
        var t = AN_GEUREDO.replace(text, " ")
        var yes = false
        if (ANY_OK.containsMatchIn(t)) { yes = true; t = ANY_OK.replace(t, " ") }
        val m = t.indexOf("말고")
        if (m >= 0) {
            val before = t.substring(0, m).replace(" ", "")
            val after = t.substring(m + 2)
            val aw = words(after)
            if (aw.isEmpty() || "그냥" in aw) return Settled(t, yes)
            val dest = aw.any { it.length >= 2 && it.endsWith("로") && it !in NOT_DEST && !it.endsWith("대로") }
            // 갈 곳 없는 "가자"·"그래"만으로는 고른 것이 없다("커피 말고 가자")
            val go = at(aw, GO - "가자" - "그래") >= 0
            val names = item.any { it in after.replace(" ", "") }
            if (!dest && !go && !names) return Settled(t, yes)
            return when {
                item.isNotEmpty() && item.any { it in before } -> Settled(t, yes)
                item.isNotEmpty() && (names || go) -> Settled(after, yes = true)
                else -> Settled(t, yes, mixed = true)
            }
        }
        // "싫어, 그냥 가자"처럼 거절 낱말이 함께면 거절이다(of가 거절로 받는다)
        if (JUST_GO.containsMatchIn(t) && !rawNo(JUST_GO.replace(t, " "), emptyList())) return Settled(t, yes, mixed = true)
        return Settled(t, yes)
    }

    /** 거절 꼴을 걸러 낸 말(쉼터·화장실 물음이 Stage.REST_NO를 볼 때 "커피 말고 쉼터로"의 '말고'를 거절로 읽지 않게) */
    fun plain(text: String, item: List<String> = emptyList()): String = settle(text, item).text

    /** 거절하는 말인가("안 그래도 …"·"아무 데나 괜찮아"는 거절이 아니다) */
    fun no(text: String, more: List<String> = emptyList()): Boolean = rawNo(settle(text, emptyList()).text, more)

    private fun rawNo(text: String, more: List<String>): Boolean {
        val s = text.replace(" ", "")
        if ((NO + more).any { it in s }) return true
        val w = words(text)
        val f = w.firstOrNull()
        val all = w.joinToString("")
        if (f in SHORT_NO || f?.removeSuffix("요") in SHORT_NO || all in SHORT_NO || all.removeSuffix("요") in SHORT_NO) return true
        if (w.size == 1 && w[0].removeSuffix("요") in WHOLE_NO && !text.contains('?')) return true
        return w.any { x -> x == "안" || x.length >= 2 && x[0] == '안' && x[1] !in AN_OK }
    }

    /** 곁의 사람에게 한 말인가("어어 잠깐만") */
    fun aside(text: String) = ASIDE.any { it in text.replace(" ", "") }

    /** 첫 낱말이 짧은 예인가("응"·"네 좋아요"·"어 그래"). 물음의 대답으로 받을지는 of()가 뒤의 말까지 본다(shortYes) */
    fun short(text: String): Boolean {
        val w = words(text)
        if (ASIDE.any { it in text.replace(" ", "") }) return false
        val first = w.firstOrNull()?.removeSuffix("요") ?: return false
        return first in SHORT || w.joinToString("").removeSuffix("요") in SHORT
    }

    /**
     * 짧은 예가 대답 전체이거나, 뒤에 대답 낱말이 온다("응"·"네 좋아요"·"어 그래"·"네 가 주세요").
     * 바로 뒤에 이야기를 잇는 말이 오거나("어, 그래서…"·"응 근데 추워") 대답 낱말이 없으면 예가 아니다(검토 11/07: 곁의 이야기를 예로 읽었다)
     */
    private fun shortYes(text: String, yes: List<String>): Boolean {
        if (!short(text)) return false
        val w = words(text)
        if (w.size == 1 || w.joinToString("").removeSuffix("요") in SHORT) return true
        val rest = w.drop(1)
        if (rest.all { it.removeSuffix("요") in SHORT }) return true
        if (CONNECT.any { rest[0].startsWith(it) }) return false
        return at(rest, yes + ANSWER_OK) >= 0
    }

    /**
     * 놀라서 되물은 한마디("네?"·"응?")나 '어'·'엉'만 들린 대답: 예로 보지 않는다(검토 11/07: "괜찮으세요?"에 놀라 낸 "네?"를 괜찮음으로 읽어
     * [119 전화]가 없는 가벼운 사고 순서로 갔다). 묻는 쪽이 한 번 다시 묻는다
     */
    fun unsure(text: String): Boolean {
        val w = words(text)
        if (w.size != 1) return false
        val x = w[0].removeSuffix("요")
        return x == "어" || x == "엉" || x in SHORT && text.trim().endsWith("?")
    }

    /**
     * 예·아니오·모름. yes는 대답 안에 있으면 예로 볼 긴 낱말(그래·좋아·넣어…, 낱말 앞자리에서만), item은 물은 것을 가리키는 낱말(settle).
     * 되물은 한마디는 어느 물음에서도 모름이고(검토 11/07), 거절과 그러자는 말이 섞여 갈리지 않아도 거절로 정하지 않고 모름이다
     */
    fun of(text: String, yes: List<String>, no: List<String> = emptyList(), item: List<String> = emptyList()): Boolean? {
        if (text.isBlank() || unsure(text)) return null
        val t = settle(text, item)
        if (t.mixed) return null
        // "그냥 가자"의 '가자'는 그러자는 말로 세지 않는다("싫어, 그냥 가자"는 거절)
        if (rawNo(t.text, no)) return if (t.yes || at(words(JUST_GO.replace(t.text, " ")), GO) >= 0) null else false
        // 이야기를 잇는 말로 시작하거나 짧은 예 바로 뒤에 오면("그래서 대리는…"·"어, 그래서 대리는…") 뒤에 예 낱말이 있어도 곁의 이야기다
        val w = words(t.text)
        if (w.isNotEmpty() && (CONNECT.take(LEAD_N).any { w[0].startsWith(it) } || short(t.text) && w.size > 1 && CONNECT.any { w[1].startsWith(it) })) return null
        return when {
            t.yes -> true
            shortYes(t.text, yes) || at(words(t.text), yes) >= 0 -> true
            else -> null
        }
    }
}
