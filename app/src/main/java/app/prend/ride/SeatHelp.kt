package app.prend.ride

import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import app.prend.Prend
import app.prend.car.CarHub
import app.prend.car.CarLink
import app.prend.car.CarState
import app.prend.ui.Stage

/**
 * 운전석 열선·통풍을 말로 바꾸기("추워"·"더워"·"열선 꺼"·"통풍 꺼").
 * 시동 때 열선·통풍을 먼저 묻던 일은 뺐다(사용자 결정 10/27): 현대차의 '공조 연동 자동 제어'가 시동 때 실내외 온도에 맞춰
 * 운전석 열선·통풍을 이미 켜고, 버튼도 바로 옆에 있다. PREND는 차가 모르는 때(쪽잠·잘래요, ride/Nap)에만 시트를 바꾼다.
 * 말로 하는 요청: "추워"·"더워"는 한 단계씩 올리고 반대쪽은 끈다. "열선 꺼"·"통풍 꺼"는 끈다.
 */
class SeatHelp {

    private val hub get() = CarLink.hubOrNull

    private fun hasVent() = (hub?.ventRange?.last ?: 0) > 0

    /** 말로 한 요청. kind = warm(추워) · cool(더워) · heat_off · vent_off */
    fun ask(kind: String, done: (String) -> Unit) {
        val h = hub ?: return done("차와 아직 연결되지 않았어요")
        val s = h.state
        when (kind) {
            "warm" -> {
                val r = h.heatRange?.takeIf { it.last > 0 } ?: return done("이 차는 운전석 열선을 바꿀 수 없어요")
                val cur = s.heat ?: 0
                if (cur >= r.last) return done("열선이 이미 가장 세요")
                val to = if (cur <= 0) 1 else cur + 1
                off(s.vent, h::setSeatVent) {
                    h.setSeatHeat(to) { ok -> done(if (!ok) "열선을 켜지 못했어요" else if (cur <= 0) "운전석 열선을 켰어요" else "열선을 ${to}단으로 올렸어요") }
                }
            }
            "cool" -> {
                if (!hasVent()) {
                    val r = h.heatRange?.takeIf { it.first < 0 } ?: return done("이 차는 운전석 통풍을 바꿀 수 없어요")
                    val cur = s.heat ?: 0
                    if (cur <= r.first) return done("이미 가장 시원하게 하고 있어요")
                    val to = if (cur >= 0) -1 else cur - 1
                    return h.setSeatHeat(to) { ok -> done(if (ok) "운전석을 시원하게 했어요" else "통풍을 켜지 못했어요") }
                }
                val r = h.ventRange!!
                val cur = s.vent ?: 0
                if (cur >= r.last) return done("통풍이 이미 가장 세요")
                val to = if (cur <= 0) 1 else cur + 1
                off(s.heat?.takeIf { it > 0 }, h::setSeatHeat) {
                    h.setSeatVent(to) { ok -> done(if (!ok) "통풍을 켜지 못했어요" else if (cur <= 0) "운전석 통풍을 켰어요" else "통풍을 ${to}단으로 올렸어요") }
                }
            }
            "heat_off" -> if ((s.heat ?: 0) <= 0) done("열선은 꺼져 있어요") else h.setSeatHeat(0) { ok -> done(if (ok) "열선을 껐어요" else "열선을 끄지 못했어요") }
            "vent_off" -> when {
                (s.vent ?: 0) > 0 -> h.setSeatVent(0) { ok -> done(if (ok) "통풍을 껐어요" else "통풍을 끄지 못했어요") }
                (s.heat ?: 0) < 0 -> h.setSeatHeat(0) { ok -> done(if (ok) "통풍을 껐어요" else "통풍을 끄지 못했어요") }
                else -> done("통풍은 꺼져 있어요")
            }
        }
    }

    /** 켜져 있으면 먼저 끄고(실패해도) 다음으로 */
    private fun off(level: Int?, set: (Int, (Boolean) -> Unit) -> Unit, next: () -> Unit) {
        if ((level ?: 0) > 0) set(0) { next() } else next()
    }

    // ── 동승자의 말은 그 자리로(매력 제안 10): "엄마가 추우시대"·"옆에 사람이 춥대"·"뒤에 애가 덥대" ──

    /**
     * 동승자의 자리를 낱말과 점유로 정한다(운전석은 빼고). who는 Stage가 낱말로 가른 것:
     * front(옆자리·조수석), rear(뒷자리·뒤에), kid(애·아이), adult(엄마·아빠·손님 등). 앉은 사람을 모르면 낱말이 가리키는 자리
     */
    fun seatsFor(who: String, car: CarState): List<SeatValue> {
        val sat = { v: SeatValue -> car.seats[v.name] == true }
        val rear = REAR.filter(sat)
        val front = SeatValue.ROW_1_RIGHT
        val bothRear = listOf(SeatValue.ROW_2_LEFT, SeatValue.ROW_2_RIGHT)
        return when (who) {
            "front" -> listOf(front)
            "rear" -> rear.ifEmpty { bothRear }
            // 아이는 뒷자리부터, 어른은 옆자리부터 본다(어른인데 옆자리·뒷자리에 다 사람이 있으면 at이 먼저 묻는다)
            "kid" -> rear.ifEmpty { if (sat(front)) listOf(front) else bothRear }
            else -> if (sat(front)) listOf(front) else rear.ifEmpty { listOf(front) }
        }
    }

    /**
     * 동승자가 춥다(warm)·덥다(cool)는 그 자리를 바꾼다. 그 자리 열선·통풍이 있으면 한 단, 없으면 그 쪽 구역 온도를 1도
     * (운전석이 든 구역은 건드리지 않는다, CarHub.tempZoneOf). "옆자리 열선 꺼"(heat_off)·"뒤에 통풍 꺼"(vent_off)는 그 자리만 끈다.
     * 한 일은 한 줄로 알린다
     */
    fun at(kind: String, who: String, done: (String) -> Unit) {
        val h = hub ?: return done("차와 아직 연결되지 않았어요")
        // 자리 말 없이 어른을 가리켰는데("할머니가 추우시대") 옆자리와 뒷자리에 다 사람이 있으면 어느 자리인지 한 번 묻는다
        // (검토 11/07 멋대로 판단: 어른이면 옆자리부터 골라 다른 사람 자리를 데웠다). 한쪽에만 사람이 있으면 전처럼 그 자리로
        if (who == "adult" && bothSides(h.state)) return pick(kind, done)
        val seats = seatsFor(who, h.state)
        val what = when (kind) { "warm" -> "추움"; "cool" -> "더움"; "heat_off" -> "열선 끔"; else -> "통풍 끔" }
        Prend.log("◆ 동승자 $what: ${seats.joinToString { it.name }}($who)")
        val said = mutableListOf<Pair<SeatValue, String>>()
        val zones = mutableSetOf<SeatArea>()
        fun next(i: Int) {
            if (i == seats.size) return done(summary(said))
            one(h, kind, seats[i], zones) { what -> what?.let { said += seats[i] to it }; next(i + 1) }
        }
        next(0)
    }

    /** 옆자리와 뒷자리에 다 사람이 앉아 있나(자리 값을 모르면 앉지 않은 것으로 본다) */
    private fun bothSides(car: CarState) = car.seats[SeatValue.ROW_1_RIGHT.name] == true && REAR.any { car.seats[it.name] == true }

    /** [옆자리]·[뒷자리] 카드로 묻고 고른 쪽만 바꾼다. 대답이 없으면 아무것도 바꾸지 않는다 */
    private fun pick(kind: String, done: (String) -> Unit) {
        val (title, text) = when (kind) {
            "warm" -> "어느 자리를 데울까요?" to "그 자리 열선을 켤게요"
            "cool" -> "어느 자리를 시원하게 할까요?" to "그 자리를 시원하게 할게요"
            "heat_off" -> "어느 자리 열선을 끌까요?" to "그 자리 열선만 끌게요"
            else -> "어느 자리 통풍을 끌까요?" to "그 자리 통풍만 끌게요"
        }
        Prend.log("◆ 동승자 자리 물음(옆자리·뒷자리에 다 사람)")
        Stage.confirm("SEAT", title, text, "옆자리", "뒷자리", pick = listOf("옆", "앞", "조수") to listOf("뒤", "뒷")) { a ->
            when (a) {
                true -> at(kind, "front", done)
                false -> at(kind, "rear", done)
                null -> Prend.log("· 동승자 자리 물음: 대답 없음 → 바꾸지 않음")
            }
        }
    }

    /** 한 자리. done의 글은 {자리} 틀(여러 자리를 한 줄로 묶으려고). 같은 구역을 두 번 바꾸지 않으면 null */
    private fun one(h: CarHub, kind: String, seat: SeatValue, zones: MutableSet<SeatArea>, done: (String?) -> Unit) {
        val heat = h.heatAt[seat.name]
        val vent = h.ventAt[seat.name]
        // 끄라는 말은 그 자리만 끈다(단계를 못 읽으면 그냥 끈다). 그 자리를 따로 바꿀 수 없는 차면 운전석이나 구역 온도로 대신하지 않는다
        if (kind == "heat_off") {
            if (heat == null || heat.last <= 0) return done("{자리} 열선은 바꿀 수 없어요")
            return h.readSeatAt(seat) { cur ->
                if (cur != null && cur <= 0) done("{자리} 열선은 꺼져 있어요")
                else h.setSeatAt(seat, heat = true, level = 0) { ok -> done(if (ok) "{자리} 열선을 껐어요" else "{자리} 열선을 끄지 못했어요") }
            }
        }
        if (kind == "vent_off") {
            if (vent != null && vent.last > 0) return h.readSeatVentAt(seat) { cur ->
                if (cur != null && cur <= 0) done("{자리} 통풍은 꺼져 있어요")
                else h.setSeatAt(seat, heat = false, level = 0) { ok -> done(if (ok) "{자리} 통풍을 껐어요" else "{자리} 통풍을 끄지 못했어요") }
            }
            // 통풍 대신 시트 냉방(시트 온도 음수)만 있는 차
            if (heat != null && heat.first < 0) return h.readSeatAt(seat) { cur ->
                if (cur != null && cur >= 0) done("{자리} 통풍은 꺼져 있어요")
                else h.setSeatAt(seat, heat = true, level = 0) { ok -> done(if (ok) "{자리} 통풍을 껐어요" else "{자리} 통풍을 끄지 못했어요") }
            }
            return done("{자리} 통풍은 바꿀 수 없어요")
        }
        if (kind == "warm" && heat != null && heat.last > 0) return h.readSeatAt(seat) { cur ->
            val c = cur ?: 0
            if (c >= heat.last) return@readSeatAt done("{자리} 열선이 이미 가장 세요")
            val to = if (c <= 0) 1 else c + 1
            // 통풍이 켜져 있으면 먼저 끈다(실패해도 열선은 켠다)
            if (vent != null) h.setSeatAt(seat, heat = false, level = 0) { }
            h.setSeatAt(seat, heat = true, level = to) { ok -> done(if (!ok) "{자리} 열선을 켜지 못했어요" else if (c <= 0) "{자리} 열선을 켰어요" else "{자리} 열선을 한 단 올렸어요") }
        }
        if (kind == "cool" && vent != null && vent.last > 0) return h.readSeatAt(seat) { hot ->
            if ((hot ?: 0) > 0) h.setSeatAt(seat, heat = true, level = 0) { }
            h.readSeatVentAt(seat) { cur ->
                val c = cur ?: 0
                if (c >= vent.last) return@readSeatVentAt done("{자리} 통풍이 이미 가장 세요")
                val to = if (c <= 0) 1 else c + 1
                h.setSeatAt(seat, heat = false, level = to) { ok -> done(if (!ok) "{자리} 통풍을 켜지 못했어요" else if (c <= 0) "{자리} 통풍을 켰어요" else "{자리} 통풍을 한 단 올렸어요") }
            }
        }
        // 통풍 대신 시트 냉방(시트 온도 음수)만 있는 차
        if (kind == "cool" && heat != null && heat.first < 0) return h.readSeatAt(seat) { cur ->
            val c = cur ?: 0
            if (c <= heat.first) return@readSeatAt done("{자리} 시트가 이미 가장 시원해요")
            h.setSeatAt(seat, heat = true, level = if (c >= 0) -1 else c - 1) { ok -> done(if (ok) "{자리} 시트를 시원하게 했어요" else "{자리} 시트를 바꾸지 못했어요") }
        }
        val zone = h.tempZoneOf(seat) ?: return done("{자리} 쪽은 바꿀 수 없어요")
        if (!zones.add(zone)) return done(null)
        h.nudgeTemp(zone, if (kind == "warm") 1f else -1f) { to ->
            val where = if (seat in zone.values) "{자리} 쪽" else "동승석 쪽"
            done(if (to == null) "$where 온도를 바꾸지 못했어요" else "$where 온도를 1도 ${if (kind == "warm") "올렸어요" else "내렸어요"}")
        }
    }

    /** 자리마다의 한 일을 한 줄로: 같은 일을 한 뒷자리 둘은 '뒷자리'로 묶는다 */
    private fun summary(said: List<Pair<SeatValue, String>>): String {
        if (said.isEmpty()) return "바꾼 게 없어요"
        return said.groupBy({ it.second }, { it.first }).entries.joinToString(" · ") { (t, seats) ->
            t.replace("{자리}", if (seats.size > 1 && seats.all { it in REAR }) "뒷자리" else seats.joinToString("·") { NAME[it] ?: "그 자리" })
        }
    }

    companion object {
        private val REAR = listOf(SeatValue.ROW_2_LEFT, SeatValue.ROW_2_CENTER, SeatValue.ROW_2_RIGHT)
        private val NAME = mapOf(
            SeatValue.ROW_1_RIGHT to "옆자리", SeatValue.ROW_2_LEFT to "뒷자리 왼쪽",
            SeatValue.ROW_2_CENTER to "뒷자리 가운데", SeatValue.ROW_2_RIGHT to "뒷자리 오른쪽",
        )
    }
}
