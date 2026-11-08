package app.prend.ride

import android.content.Context
import app.prend.Prend
import app.prend.ui.Habit
import app.prend.ui.Habits
import app.prend.ui.Remembered
import app.prend.ui.Stage
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/** 기억이 바뀌어 사람에게 알릴 한마디. asset이 있으면 목소리로도 */
data class Learned(val text: String, val asset: String? = null)

/**
 * 쓸수록 기억하는 PREND(사용자 결정 10/27). 순간마다 받은 대답을 이 차 안에 적어 두고 다음엔 더 맞게 한다.
 * - 정체: 세 번 연달아 "응"이면 다음부터는 묻지 않고 "오늘도 분위기 바꿔 둘게요"라고 알리며 바로 한다(무드등과 잔잔한 곡, ride/JamHelp).
 *   알아서 한 것을 "되돌려"나 [되돌리기]로 되돌리면 다음엔 다시 묻는다("괜찮아"·"좋아"는 되돌림이 아니라 배운 것을 지우지 않는다, 검토 11/07).
 *   되물은 "네?"·'어' 한마디는 대답으로 세지 않는다(ride/Answer.unsure: 부르는 쪽이 예·아니오로 정해진 대답만 넘긴다)
 * - 먼저 묻기(사용자 결정 11/06): 곁들임 물음 다섯(한 시간·대리 운행·이야기에 비켜 서기·오래 막힐 때·같이 고르기)은 켜 둔 것만 묻는다.
 *   새로 깐 앱은 모두 꺼 두고 기억 화면에서 켠다. 전의 앱에는 사람이 '켠' 값이 없었다(처음부터 켜져 있었을 뿐): 이미 깔린 기기도 같이 꺼지고,
 *   배운 버릇(대답 수·알아서 하기·지난번 고른 것)은 남아 다시 켜면 이어 쓴다. 켜고 끄는 설정이라 [모두 잊기]로 지우지 않는다
 * - 정체·같이 고르기·한 시간에 묻기·대리 운행 묻기: 두 번 연달아 "괜찮아요"면 먼저 묻지 않는다. 묻지 않게 된 물음을 먼저 묻기에서 다시 켜면 처음처럼 묻는다.
 *   대리 운행 물음은 대답 없이 지나간 것도 센다(밤 가족 운행마다 다시 뜨지 않게, 검토 11/06). 주차 자리 알림은 "그만 알려요" 한 번이면 그친다
 * - 쪽잠: 고른 길이를 기억해 다음에 먼저 권한다. 소리와 목소리를 두 번 넘게 불러야 깨는 날이 두 번 이어지면
 *   다음부터 깨우기를 1분 더 일찍(가장 길게 4분 전부터) 시작한다
 * - 한 시간 물음: 고른 것(바깥 공기·열선·통풍·잔잔한 곡)을 기억해, 다음에 그때 차에 맞으면 첫 버튼으로 먼저 내민다.
 *   바깥 공기를 세 번 연달아 고르면 다음부터는 한 시간이 되면 묻지 않고 바깥 공기를 넣어 두고 알린다(되돌리면 다시 묻는다, 11/01 추천 2)
 * - 조용히 가기: 혼자 탄 운행에서 "오늘은 조용히 가자"를 같은 칸(평일·주말 × 아침·낮·저녁·밤)에 14일 안에 두 번 부르면, 다음부터 그 칸의 혼자 운행은
 *   먼저 조용히 두고 목소리 없이 카드 한 장으로 알린다. 그 카드의 [평소대로]가 두 번이면 그 칸을 잊는다(부른 것은 다음엔 부르기 전에).
 *   두 번 사이가 14일을 넘으면 이번을 첫 번째로 센다(검토 11/07 멋대로 판단: 몇 주 떨어진 두 번으로 그 칸을 늘 조용히 두었다)
 * - 졸음쉼터 권하기는 졸음과 이어져 있어 기억으로 줄이지 않는다
 * - 새로 배운 것은 알림과 기억 화면의 '바뀜'으로 알린다. 첫 화면의 '새로 기억함' 한 줄은 뺐다(11/03, 검토-방향노인초보 2-2).
 *   무엇이 바뀌었는지 한 줄과 그때 시각은 하루 동안 기록으로만 둔다(매력 9번)
 * 남기는 것은 예·아니오와 쪽잠 길이·깬 단계·한 시간에 고른 것, 먼저 묻기의 켬·끔뿐이고, 들은 말은 남기지 않는다. 앱 저장소(차 안)에만 두고 밖으로 보내지 않는다.
 * 시동 때 열선·통풍 기억은 뺐다(차의 공조 연동 자동 제어와 겹침, 10/27)
 */
class Memory(context: Context) {

    enum class Kind { JAM, PICK, HOUR, SPOT, DAERI }

    private val prefs = context.getSharedPreferences("memory", Context.MODE_PRIVATE)

    /** 김서림을 알아서 바꾼 것을 [되돌리기]한 적이 있다: 다음부터는 묻는다(11/02). [모두 잊기]로 지운다 */
    var fogAsk: Boolean
        get() = prefs.getBoolean("fog_ask", false)
        set(v) { prefs.edit().putBoolean("fog_ask", v).apply() }

    /** 늘 조용히(사용자 결정 11/04): 켜 두면 운행마다 조용히 가기로 시작한다. 켜고 끄는 설정이라 [모두 잊기]로 지우지 않는다 */
    var alwaysQuiet: Boolean
        get() = prefs.getBoolean("always_quiet", false)
        set(v) { prefs.edit().putBoolean("always_quiet", v).apply(); Stage.ui.update { it.copy(alwaysQuiet = v) } }

    var habits = load()
        private set

    /** 먼저 묻기에서 켜 둔 물음(ASK_KEYS 가운데). 1초마다 보므로 읽어 둔다 */
    private var asksOn: Set<String> = ASK_KEYS.filter { prefs.getBoolean("ask_$it", false) }.toSet()

    /** 이 물음을 먼저 묻나(켜 둠). 두 번 "괜찮아요"로 묻지 않게 된 것은 habit의 quiet가 따로 막는다 */
    fun asks(key: String) = key in asksOn

    /**
     * 먼저 묻기의 한 물음을 켜고 끈다(기억 화면). 켤 때 그 물음이 "괜찮아요" 두 번으로 묻지 않게 돼 있었으면 그것도 풀어 처음처럼 묻는다
     * (알아서 하기·지난번 고른 것은 그대로)
     */
    fun setAsks(key: String, on: Boolean) {
        if (key !in ASK_KEYS) return
        prefs.edit().putBoolean("ask_$key", on).apply()
        asksOn = if (on) asksOn + key else asksOn - key
        val kind = Kind.entries.firstOrNull { it.name == key }
        if (on && kind != null && habit(kind).quiet) set(kind, habit(kind).copy(no = 0, quiet = false, why = null))
        Stage.ui.update { it.copy(asks = asksOn) }
        Prend.log("◆ 먼저 묻기 $key ${if (on) "켬" else "끔"}")
    }

    init {
        publish()
        Stage.ui.update { it.copy(alwaysQuiet = alwaysQuiet, asks = asksOn) }
        // 하루가 안 된 '새로 기억함'은 다시 시동을 걸어도 남긴다(화면에는 보이지 않는다)
        loadRemembered()?.takeIf { System.currentTimeMillis() - it.at < REMEMBER_MS }?.let { r -> Stage.ui.update { it.copy(remembered = r) } }
    }

    fun habit(k: Kind) = when (k) {
        Kind.JAM -> habits.jam
        Kind.PICK -> habits.pick
        Kind.HOUR -> habits.hour
        Kind.SPOT -> habits.spot
        Kind.DAERI -> habits.daeri
    }

    /** PREND가 물은 것에 대답했다(말·버튼). 대답이 없던 것은 세지 않는다 */
    fun answered(k: Kind, yes: Boolean): Learned? {
        val h = habit(k)
        if (yes) {
            val run = h.yes + 1
            val auto = k == Kind.JAM && run >= AUTO_AFTER
            set(k, h.copy(yes = run, no = 0, auto = h.auto || auto, why = if (auto && !h.auto) "세 번 연달아 “응” 하셔서" else h.why))
            Prend.log("· 기억 ${k.name}: “응” ${run}번째${if (auto && !h.auto) " → 다음부터 알아서" else ""}")
            return if (auto && !h.auto) learn(k.name, AUTO_JAM, Learned("다음부턴 묻지 않고 바꿔 둘게요", "voice/learn_auto_jam.wav")) else null
        }
        val run = h.no + 1
        Prend.log("· 기억 ${k.name}: “괜찮아요” ${run}번째")
        // 한 시간의 '알아서 바깥 공기'는 [괜찮아요] 한 번으로 지우지 않는다(바깥 공기가 이미 들어와 평소처럼 물은 날의 대답이다). 두 번이면 묻지 않기로 함께 끈다
        if (run < QUIET_AFTER) { set(k, h.copy(yes = 0, no = run, auto = k == Kind.HOUR && h.auto)); return null }
        set(k, h.copy(yes = 0, no = 0, auto = false, quiet = true, why = "두 번 연달아 “괜찮아요” 하셔서"))
        Prend.log("· 기억 ${k.name}: 다음부터 묻지 않음")
        return learn(k.name, quietNote(k), Learned("다음부턴 묻지 않을게요", "voice/learn_quiet.wav"))
    }

    /**
     * 물음이 대답 없이 지나갔다(대리 운행, 검토 11/06). "괜찮아요"와 같은 줄에 세어 두 번이면 묻지 않는다. 사람이 보지 않았을 수 있어 알림은 띄우지 않고
     * 기억 화면의 '바뀜'에만 남긴다. 다른 말에 끊겨 되돌린 것은 부른 쪽이 세지 않는다
     */
    fun unanswered(k: Kind) {
        val h = habit(k)
        val run = h.no + 1
        Prend.log("· 기억 ${k.name}: 대답 없이 지나감 ${run}번째")
        if (run < QUIET_AFTER) { set(k, h.copy(yes = 0, no = run)); return }
        set(k, h.copy(yes = 0, no = 0, auto = false, quiet = true, why = "두 번 대답 없이 지나가서"))
        Prend.log("· 기억 ${k.name}: 다음부터 묻지 않음")
        learn(k.name, quietNote(k), Learned(quietNote(k)))
    }

    /** 사람이 "그만 알려요"라고 했다: 한 번에 묻지 않기로(주차 자리처럼 알리기만 하는 말) */
    fun quiet(k: Kind, why: String) {
        set(k, habit(k).copy(yes = 0, no = 0, auto = false, quiet = true, why = why))
        Prend.log("· 기억 ${k.name}: 다음부터 알리지 않음")
        learn(k.name, quietNote(k), Learned(quietNote(k)))
    }

    /**
     * 알아서 한 것을 사람이 곧바로 되돌렸다. 다음엔 다시 묻는다(정체는 이번 일을 "괜찮아요" 한 번으로 센다.
     * 한 시간 바깥 공기는 세지 않는다: 한 번 되돌렸다고 한 시간 물음까지 그치는 데 가까워지지 않게)
     */
    fun undoAuto(k: Kind): Learned {
        val h = habit(k)
        val hour = k == Kind.HOUR
        set(k, h.copy(auto = false, yes = 0, no = if (hour) 0 else 1, why = "알아서 한 걸 바로 되돌리셔서"))
        if (hour && habits.hourAirRun != 0) { habits = habits.copy(hourAirRun = 0); save() }
        Prend.log("· 기억 ${k.name}: 알아서 하기 그만, 다음엔 다시 물음")
        return learn(k.name, if (hour) "한 시간이 되면 다시 물어요" else "막히면 다시 물어요", Learned("다음엔 다시 물을게요"))
    }

    /** 한 시간 물음의 대답: 바깥 공기를 골랐나. 세 번 연달아면 다음부터 한 시간이 되면 묻지 않고 바깥 공기를 넣어 두고 알린다 */
    fun hourAir(picked: Boolean): Learned? {
        val h = habits.hour
        if (!picked) { if (habits.hourAirRun != 0) { habits = habits.copy(hourAirRun = 0); save() }; return null }
        val run = habits.hourAirRun + 1
        val auto = run >= AUTO_AFTER && !h.auto
        habits = habits.copy(hourAirRun = run, hour = if (auto) h.copy(auto = true, why = "세 번 연달아 바깥 공기를 고르셔서") else h)
        save()
        Prend.log("· 기억 한 시간: 바깥 공기 ${run}번째${if (auto) " → 다음부터 알아서" else ""}")
        return if (auto) learn("HOUR", AUTO_AIR, Learned("다음부턴 한 시간이 되면 바깥 공기를 넣어 둘게요", "voice/learn_auto_air.wav")) else null
    }

    /**
     * 혼자 탄 운행에서 사람이 조용히 가기를 불렀다(운행마다 한 번 센다). 같은 칸에서 14일 안에 두 번이면 다음부터 그 칸은 먼저 조용히.
     * 앞의 한 번이 14일보다 오래됐으면(또는 언제였는지 모르면) 이번을 첫 번째로 센다(검토 11/07 멋대로 판단). 부른 때는 칸마다 시각 하나만 둔다
     */
    fun quietCalled(slot: String): Learned? {
        val now = System.currentTimeMillis()
        val lastAt = prefs.getLong(QUIET_AT + slot, 0L)
        prefs.edit().putLong(QUIET_AT + slot, now).apply()
        val before = habits.quietSlots[slot] ?: 0
        val stale = before in 1 until QUIET_AFTER && (lastAt == 0L || now - lastAt > QUIET_GAP_MS)
        if (stale) Prend.log("· 기억 조용히 가기: $slot 앞의 한 번이 ${QUIET_GAP_MS / 86_400_000L}일보다 오래돼 처음부터 셈")
        val n = if (stale) 1 else before + 1
        habits = habits.copy(quietSlots = habits.quietSlots + (slot to n), quietNos = habits.quietNos - slot)
        save()
        Prend.log("· 기억 조용히 가기: $slot ${n}번째${if (n == QUIET_AFTER) " → 다음부터 먼저 조용히" else ""}")
        return if (n == QUIET_AFTER) learn("QUIET", "${slot}엔 먼저 조용히 가요", Learned("다음부턴 ${slot}엔 먼저 조용히 갈게요")) else null
    }

    /** 그 칸에서 먼저 조용히 가기로 배웠나 */
    fun quietLearned(slot: String) = (habits.quietSlots[slot] ?: 0) >= QUIET_AFTER

    /** 먼저 조용히 간 운행의 카드에서 [평소대로]: 그 칸에서 두 번이면 잊는다 */
    fun quietUsual(slot: String): Learned? {
        val n = (habits.quietNos[slot] ?: 0) + 1
        if (n < QUIET_AFTER) {
            habits = habits.copy(quietNos = habits.quietNos + (slot to n)); save()
            Prend.log("· 기억 조용히 가기: $slot [평소대로] ${n}번째")
            return null
        }
        habits = habits.copy(quietSlots = habits.quietSlots - slot, quietNos = habits.quietNos - slot); save()
        Prend.log("· 기억 조용히 가기: $slot [평소대로] 두 번 → 잊음")
        return learn("QUIET", "${slot}에도 평소대로 가요", Learned("${slot}에도 평소대로 할게요"))
    }

    /** 시험: 그 순간을 지금 배운 것처럼(정체는 알아서 하기, 나머지는 묻지 않기). 첫 화면 한 줄과 기억 화면의 '바뀜'을 보려고 */
    fun pretendLearn(k: Kind): Learned {
        val auto = k == Kind.JAM
        set(k, habit(k).copy(yes = 0, no = 0, auto = auto, quiet = !auto, why = "시험"))
        Prend.log("· 시험: 기억 ${k.name} 배움")
        return learn(k.name, if (auto) AUTO_JAM else quietNote(k), Learned(if (auto) "다음부턴 묻지 않고 바꿔 둘게요" else "다음부턴 묻지 않을게요"))
    }

    /** 묻지 않기로 한 버릇의 지금 모습 한 줄(첫 화면 '새로 기억함'과 기억 화면이 같은 말을 쓴다) */
    private fun quietNote(k: Kind) = when (k) {
        Kind.JAM -> "막혀도 묻지 않아요"
        Kind.PICK -> "밥때에도 먼저 묻지 않아요"
        Kind.HOUR -> "한 시간 달려도 묻지 않아요"
        Kind.SPOT -> "주차 자리를 알리지 않아요"
        Kind.DAERI -> "대리 운행인지 묻지 않아요"
    }

    /**
     * 새로 배웠다: 첫 화면에 하루 동안 '새로 기억함 · note' 한 줄로 남긴다(시동을 다시 걸어도).
     * 남기는 것은 바뀐 버릇 이름과 그 한 줄, 시각뿐이다
     */
    private fun learn(kind: String, note: String, l: Learned): Learned {
        val r = Remembered(note, kind, System.currentTimeMillis())
        prefs.edit().putString("remembered", JSONObject().put("note", r.note).put("kind", r.kind).put("at", r.at).toString()).apply()
        Stage.ui.update { it.copy(remembered = r) }
        Prend.log("◆ 새로 기억함: $kind")
        return l
    }

    private fun loadRemembered(): Remembered? = runCatching {
        val j = JSONObject(prefs.getString("remembered", null) ?: return null)
        Remembered(j.getString("note"), j.getString("kind"), j.getLong("at"))
    }.getOrNull()

    /** 하루가 지난 '새로 기억함'을 거둔다(운행 서비스가 1초마다 부른다) */
    fun expire(now: Long = System.currentTimeMillis()) {
        val r = Stage.ui.value.remembered ?: return
        if (now - r.at < REMEMBER_MS) return
        prefs.edit().remove("remembered").apply()
        Stage.ui.update { it.copy(remembered = null) }
        Prend.log("· 새로 기억함: 하루 지나 거둠")
    }

    /** 한 시간 물음에서 고른 것(AIR·HEAT·VENT·MUSIC). 다음에 그때 차에 맞으면 첫 버튼으로 먼저 내민다 */
    fun hourPicked(what: String) {
        if (habits.hourPick == what) return
        habits = habits.copy(hourPick = what)
        save()
        Prend.log("· 기억 한 시간: $what")
    }

    /** 쪽잠 길이를 골랐다(다음에 "15분 주무실래요?"처럼 먼저 권한다) */
    fun napChosen(min: Int) {
        if (habits.napMin == min) return
        habits = habits.copy(napMin = min)
        save()
        Prend.log("· 기억 쪽잠: ${min}분")
    }

    /**
     * 쪽잠에서 깼다. stage는 깬 단계(0 빛, 1 바람, 2 곡, 3 목소리, 4 다시 부른 뒤).
     * 다시 불러야 깬 날이 두 번 이어지면 깨우기를 1분 더 일찍 시작한다
     */
    fun napWoke(stage: Int): Learned? {
        val hard = if (stage >= 4) habits.napHard + 1 else 0
        if (hard < QUIET_AFTER || habits.napLead >= NAP_LEAD_MAX) {
            habits = habits.copy(napHard = hard.coerceAtMost(QUIET_AFTER))
            save()
            Prend.log("· 기억 쪽잠: ${STAGES[stage.coerceIn(0, STAGES.lastIndex)]}에 깸(어렵게 깬 날 ${hard}번 이어짐)")
            return null
        }
        val lead = (habits.napLead + 60).coerceAtMost(NAP_LEAD_MAX)
        habits = habits.copy(napHard = 0, napLead = lead)
        save()
        Prend.log("· 기억 쪽잠: 두 번 이어 다시 불러야 깨셔서 깨우기를 ${lead / 60}분 전부터")
        return learn("NAP", "쪽잠은 ${lead / 60}분 앞서 깨워요", Learned("다음엔 조금 더 일찍 깨우기 시작할게요", "voice/nap_earlier.wav"))
    }

    /** 시험: "응"을 n번 연달아 한 것처럼(정체 두 번이면 다음 물음 카드에 '이번에도 응이면…' 민트 줄) */
    fun pretendYes(k: Kind, n: Int) { set(k, habit(k).copy(yes = n, no = 0, auto = false, quiet = false, why = null)); Prend.log("· 시험: 기억 ${k.name} “응” ${n}번") }

    /** 시험: 그 순간을 바로 '알아서 하기'로(세 번 대답하는 시간을 줄인다) */
    fun pretendAuto(k: Kind) { set(k, habit(k).copy(auto = true, yes = AUTO_AFTER, no = 0, quiet = false, why = "시험")); Prend.log("· 시험: 기억 ${k.name} 알아서") }

    /** 잊기. what은 JAM·PICK·HOUR·SPOT·DAERI·NAP, null이면 모두 */
    fun forget(what: String?) {
        if (what == null) prefs.edit().remove("fog_ask").apply()
        habits = when (what) {
            null -> Habits()
            "JAM" -> habits.copy(jam = Habit())
            "PICK" -> habits.copy(pick = Habit())
            "HOUR" -> habits.copy(hour = Habit(), hourPick = null, hourAirRun = 0)
            "QUIET" -> habits.copy(quietSlots = emptyMap(), quietNos = emptyMap())
            "SPOT" -> habits.copy(spot = Habit())
            "DAERI" -> habits.copy(daeri = Habit())
            "NAP" -> habits.copy(napMin = null, napLead = NAP_LEAD, napHard = 0)
            else -> return
        }
        save()
        // 조용히 가기를 부른 때(칸마다)도 함께 지운다
        if (what == null || what == "QUIET") { val e = prefs.edit(); prefs.all.keys.filter { it.startsWith(QUIET_AT) }.forEach { e.remove(it) }; e.apply() }
        // 잊은 버릇의 '새로 기억함' 한 줄도 거둔다(처음대로 돌렸는데 첫 화면에 남지 않게)
        if (what == null || Stage.ui.value.remembered?.kind == what) {
            prefs.edit().remove("remembered").apply()
            Stage.ui.update { it.copy(remembered = null) }
        }
        Prend.log("· 기억 잊음: ${what ?: "모두"}")
    }

    private fun set(k: Kind, h: Habit) {
        habits = when (k) {
            Kind.JAM -> habits.copy(jam = h)
            Kind.PICK -> habits.copy(pick = h)
            Kind.HOUR -> habits.copy(hour = h)
            Kind.SPOT -> habits.copy(spot = h)
            Kind.DAERI -> habits.copy(daeri = h)
        }
        save()
    }

    private fun save() {
        val j = JSONObject()
            .put("jam", enc(habits.jam)).put("pick", enc(habits.pick))
            .put("hour", enc(habits.hour)).put("spot", enc(habits.spot)).put("daeri", enc(habits.daeri))
            .put("napMin", habits.napMin ?: JSONObject.NULL).put("napLead", habits.napLead).put("napHard", habits.napHard)
            .put("hourPick", habits.hourPick ?: JSONObject.NULL)
            .put("hourAirRun", habits.hourAirRun)
            .put("quietSlots", JSONObject(habits.quietSlots as Map<*, *>)).put("quietNos", JSONObject(habits.quietNos as Map<*, *>))
        prefs.edit().putString("habits", j.toString()).apply()
        publish()
    }

    private fun publish() = Stage.ui.update { it.copy(habits = habits) }

    private fun load(): Habits = runCatching {
        val j = JSONObject(prefs.getString("habits", null) ?: return Habits())
        Habits(dec(j.optJSONObject("jam")), dec(j.optJSONObject("pick")),
            napMin = if (j.isNull("napMin") || !j.has("napMin")) null else j.getInt("napMin"),
            napLead = j.optInt("napLead", NAP_LEAD), napHard = j.optInt("napHard"),
            hour = dec(j.optJSONObject("hour")), spot = dec(j.optJSONObject("spot")), daeri = dec(j.optJSONObject("daeri")),
            hourPick = if (j.isNull("hourPick") || !j.has("hourPick")) null else j.getString("hourPick"),
            hourAirRun = j.optInt("hourAirRun"), quietSlots = ints(j.optJSONObject("quietSlots")), quietNos = ints(j.optJSONObject("quietNos")))
    }.onFailure { Prend.log("✗ 기억 읽기 실패 $it") }.getOrDefault(Habits())

    private fun ints(j: JSONObject?): Map<String, Int> = j?.keys()?.asSequence()?.associateWith { j.optInt(it) }.orEmpty()

    private fun enc(h: Habit) = JSONObject()
        .put("yes", h.yes).put("no", h.no).put("auto", h.auto).put("quiet", h.quiet).put("why", h.why ?: JSONObject.NULL)

    private fun dec(j: JSONObject?): Habit {
        j ?: return Habit()
        return Habit(yes = j.optInt("yes"), no = j.optInt("no"), auto = j.optBoolean("auto"), quiet = j.optBoolean("quiet"),
            why = if (j.isNull("why")) null else j.optString("why"))
    }

    companion object {
        const val AUTO_AFTER = 3
        const val QUIET_AFTER = 2
        /** 조용히 가기를 배우는 두 번 사이의 가장 긴 틈(14일), 칸마다 마지막으로 부른 때를 적는 키 앞부분 */
        const val QUIET_GAP_MS = 14 * 24 * 3_600_000L
        private const val QUIET_AT = "quiet_at_"
        /** 먼저 묻기의 물음(화면 이름은 ui/Stage.ASK_LINES) */
        val ASK_KEYS = listOf("HOUR", "DAERI", "TALK", "JAM", "PICK")
        /** 쪽잠 끝나기 몇 초 전부터 깨우기 시작하나(처음 2분, 가장 길게 4분) */
        const val NAP_LEAD = 120
        const val NAP_LEAD_MAX = 240
        val STAGES = listOf("빛", "바람", "곡", "목소리", "다시 부른 뒤")
        /** '새로 기억함'을 첫 화면에 두는 시간 */
        const val REMEMBER_MS = 24 * 60 * 60_000L
        /** 정체를 알아서 하게 된 버릇의 한 줄(기억 화면 카드와 같은 말) */
        private const val AUTO_JAM = "막히면 알아서 바꿔요"
        private const val AUTO_AIR = "한 시간이면 바깥 공기를 넣어 둬요"

        /** 조용히 가기를 기억하는 칸: 평일·주말 × 아침(5~9시)·낮(10~16시)·저녁(17~20시)·밤(21~4시) */
        fun slot(c: java.util.Calendar = java.util.Calendar.getInstance()): String {
            val day = if (c.get(java.util.Calendar.DAY_OF_WEEK) in listOf(java.util.Calendar.SATURDAY, java.util.Calendar.SUNDAY)) "주말" else "평일"
            val band = when (c.get(java.util.Calendar.HOUR_OF_DAY)) { in 5..9 -> "아침"; in 10..16 -> "낮"; in 17..20 -> "저녁"; else -> "밤" }
            return "$day $band"
        }
    }
}
