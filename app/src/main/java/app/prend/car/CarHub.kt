package app.prend.car

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.FanDirectionType
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import app.prend.Prend

/** 차의 지금 상태. 값은 모두 메인 스레드에서 바뀐다 */
data class CarState(
    val speedKmh: Float = 0f,
    val gear: Int = VehicleGear.GEAR_PARK,
    val brake: Boolean = false,
    val turnSignal: String = "OFF",
    val wiperOn: Boolean = false,
    /** OFF, MIST, INTERMITTENT_LEVEL_n, CONTINUOUS_LEVEL_n, AUTO, SERVICE, OTHER */
    val wiperSwitch: String = "OFF",
    /** 자리별 창 열림 비율 0~1 */
    val windows: Map<String, Float> = emptyMap(),
    val outsideC: Float? = null,
    val night: Boolean = false,
    /** 자리별 점유 */
    val seats: Map<String, Boolean> = emptyMap(),
    /** 자리별 문 위치(0 닫힘) */
    val doors: Map<String, Int> = emptyMap(),
    /** 운전석 시트 온도 단계(양수 열선, 음수 냉방, 0 꺼짐)와 통풍 단계. 모르면 null */
    val heat: Int? = null,
    val vent: Int? = null,
    /** 앞자리 에어백이 터졌다(한 번 터지면 이번 운행 내내 true) */
    val airbag: Boolean = false,
    /**
     * 비상등이 깜빡이는 중(AAOS HAZARD_LIGHTS_STATE). 급제동 경보(ESS)가 켠 것도 사람이 켠 것도 같다.
     * 일반 앱에는 권한(CAR_EXTERIOR_LIGHTS, signature|privileged)이 막혀 이 에뮬레이터에서는 늘 false다(CarHub.hazardReadable)
     */
    val hazard: Boolean = false,
    /** 자리별 등받이 위치(전동 시트가 주는 차만, 작을수록 젖힘). 쪽잠·잘래요를 알아채는 데 쓴다 */
    val recline: Map<String, Int> = emptyMap(),
    /** 핸들 각도(도, Pleos SDK 값 그대로: 오른쪽이 음수, 이 차는 −470~500). 주차 코치가 켜 둔 동안만 읽는다. 모르면 null */
    val steer: Float? = null,
    /** 내기 순환(안쪽 공기만 돌림)인가. 모르면 null */
    val recirc: Boolean? = null,
    /**
     * 차가 '운전 중이라 화면을 줄여야 한다'고 알리는 중(AAOS CarUxRestrictions의 isRequiresDistractionOptimization).
     * 화면은 기어와 함께 이 값을 본다: 기어가 P여도 이 값이 켜져 있으면 달리는 동안의 화면으로 줄인다(Stage.parked). 못 받으면 false
     */
    val uxLimited: Boolean = false,
) {
    val driving get() = gear == VehicleGear.GEAR_DRIVE
    val parked get() = gear == VehicleGear.GEAR_PARK
    val anyWindowOpen get() = windows.values.any { it >= 0.3f }
    /** 모시는 길: 조수석이 비고 뒷자리에만 사람이 있다(별첨 2 §5-4, 먼저 건네는 말 없음) */
    val chauffeured get() = seats["ROW_1_RIGHT"] != true && seats.any { (k, v) -> v && k.startsWith("ROW_2") }
}

/**
 * 차량 연결 층. Pleos Vehicle SDK(공식 문서의 메서드 이름)와 AAOS 표준 속성을 한데 모아
 * CarState 하나로 내보낸다. 화면 없이 음악 서비스 안에서 돈다.
 * - AAOS: 차속, 기어, 바깥 기온, 야간 모드
 * - Pleos: 브레이크, 방향지시등, 와이퍼(켜짐·단계), 창 위치, 좌석 점유, 문 위치
 * 와이퍼 단계는 구독 메서드가 없어 켜져 있는 동안 2초마다 다시 읽는다(1주차 시험 5).
 * 운전석 열선·통풍은 차가 지원하는 단계를 먼저 묻고, 사람이 버튼으로 바꾼 것도 알도록 5초마다 다시 읽는다.
 */
class CarHub(private val context: Context, private val onChange: (old: CarState, now: CarState) -> Unit) {

    private val ui = Handler(Looper.getMainLooper())
    private var car: Car? = null
    @Volatile private var vehicle: Vehicle? = null
    private val windowMax = mutableMapOf<String, Int>()

    /** 운전석 열선(시트 온도)·통풍이 지원하는 단계. 없거나 아직 모르면 null */
    var heatRange: IntRange? = null
        private set
    var ventRange: IntRange? = null
        private set
    /** 자리별(SeatValue 이름) 열선·통풍 단계("엄마가 추우시대"를 그 자리로). 차가 알려 준 자리만 든다 */
    var heatAt: Map<String, IntRange> = emptyMap()
        private set
    var ventAt: Map<String, IntRange> = emptyMap()
        private set
    /** 지원 여부를 물어 답을 받았는가(답이 와야 '없음'과 '아직 모름'을 가른다) */
    var seatChecked = false
        private set

    @Volatile var state = CarState()
        private set

    /**
     * 되풀이해 읽는 Pleos 값(좌석·등받이·핸들·와이퍼·주행거리)은 Pleos 준비 스레드에서 부른다. SDK 읽기는 콜백으로 답하지만
     * 부른 스레드에서 IPC를 기다리고, 앱을 켤 때 SDK가 준비 중이면 1초 넘게 기다려 메인에서 부르면 첫 화면이 굳었다(11/04 시험 27).
     * 답은 지금처럼 프록시·ui.post가 메인으로 넘긴다
     */
    private val io by lazy { Handler(pleosThread.looper) }

    /** 리플렉션으로 찾은 메서드(핸들 각도처럼 0.5초마다 부르는 것도 있어, 메서드 목록을 매번 새로 만들지 않게) */
    private val methods = java.util.concurrent.ConcurrentHashMap<String, java.lang.reflect.Method>()
    private fun method(api: Any, name: String): java.lang.reflect.Method? {
        val key = api.javaClass.name + "#" + name
        return methods[key] ?: api.javaClass.methods.firstOrNull { it.name == name }?.also { methods[key] = it }
    }

    /**
     * 좌석 점유 값의 뜻. 에뮬레이터 시험에서 Pleos SDK 2.3.2는 콘솔이 1(Empty)을 보내면 true(앉음)로 알려 왔지만,
     * 안드로이드 표준(VehicleSeatOccupancyState)은 1이 빈자리(VACANT)·2가 앉음(OCCUPIED)이고 Virtual Console도 Empty=1·Occupied=2를 보낸다(11/01 시험).
     * 실차가 어느 뜻으로 보내는지 문서가 없어, 달리는 동안의 운전석으로 맞춘다: 달리는데 운전석이 SDK에서 '앉음'이면 SDK 그대로, '빔'이면 뒤집어 읽는다.
     * 맞춘 뜻은 이 차에 적어 두고, 아직 맞춘 적이 없으면 표준(뒤집음)으로 읽는다. SDK가 준 값 그대로는 rawSeats
     */
    private val carPrefs = context.getSharedPreferences("car", Context.MODE_PRIVATE)
    private var seatFlip = carPrefs.getBoolean("seatFlip", true)
    private val rawSeats = mutableMapOf<String, Boolean>()

    /** 시험 기록용: 좌석 값을 어떻게 읽는가 */
    val seatMeaning get() = if (seatFlip) "뒤집어 읽음(표준)" else "SDK 그대로"

    private fun applySeats() = update { it.copy(seats = it.seats + rawSeats.mapValues { (_, v) -> v != seatFlip }) }

    /** 달리는 동안: 운전석 값으로 좌석 값의 뜻을 맞춘다(운전석은 늘 사람이 있다) */
    private fun senseSeats(now: CarState) {
        if (!now.driving || now.speedKmh < 10f) return
        val d = rawSeats["ROW_1_LEFT"] ?: return
        val flip = !d
        if (flip == seatFlip) return
        seatFlip = flip
        carPrefs.edit().putBoolean("seatFlip", flip).apply()
        Prend.log("◆ 좌석 값 맞춤: 달리는 중 운전석이 SDK '${if (d) "앉음" else "빔"}' → ${if (flip) "뒤집어 읽음(표준: 1 빔·2 앉음)" else "SDK 그대로(1 앉음)"}")
        applySeats()
    }

    private fun update(change: (CarState) -> CarState) {
        val old = state
        val now = change(old)
        if (now == old) return
        state = now
        if (old.wiperOn != now.wiperOn || old.wiperSwitch != now.wiperSwitch) Prend.log("· 와이퍼 ${if (now.wiperOn) "켜짐" else "꺼짐"} ${now.wiperSwitch}")
        if (old.gear != now.gear) Prend.log("· 기어 ${now.gear}")
        if (old.heat != now.heat || old.vent != now.vent) Prend.log("· 운전석 시트 온도 ${now.heat} 통풍 ${now.vent}")
        if (old.hazard != now.hazard) Prend.log("· 비상등 ${if (now.hazard) "켜짐" else "꺼짐"}")
        if (old.uxLimited != now.uxLimited) Prend.log("· 주행 제한 ${if (now.uxLimited) "걸림" else "풀림"}")
        onChange(old, now)
        senseSeats(now)
    }

    // ── AAOS 표준 속성 ─────────────────────────────────────────

    private val property = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            ui.post {
                when (value.propertyId) {
                    VehiclePropertyIds.PERF_VEHICLE_SPEED -> update { it.copy(speedKmh = (value.value as Float) * 3.6f) }
                    VehiclePropertyIds.GEAR_SELECTION -> update { it.copy(gear = value.value as Int) }
                    VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE -> update { it.copy(outsideC = value.value as Float) }
                    VehiclePropertyIds.NIGHT_MODE -> update { it.copy(night = value.value as Boolean) }
                    VehiclePropertyIds.HAZARD_LIGHTS_STATE -> update { it.copy(hazard = (value.value as Int) == 1) }
                }
            }
        }

        override fun onErrorEvent(propId: Int, zone: Int) = Prend.log("✗ 차량 속성 오류 ${VehiclePropertyIds.toString(propId)}")
    }

    private val aaosProps = listOf(
        VehiclePropertyIds.PERF_VEHICLE_SPEED to 5f,
        VehiclePropertyIds.GEAR_SELECTION to 0f,
        VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE to 1f,
        VehiclePropertyIds.NIGHT_MODE to 0f,
        VehiclePropertyIds.HAZARD_LIGHTS_STATE to 0f,
    )

    /** 비상등을 읽을 수 있는가(구독이 허락됐나). 막히면 접촉 사고 살핌이 비상등 없이 판단한다(Bump) */
    @Volatile var hazardReadable = false
        private set

    private fun startAaos() {
        try {
            car = Car.createCar(context)
            subscribeAaos()
            watchUx()
        } catch (e: Exception) {
            Prend.log("✗ AAOS 속성 준비 실패: $e")
        }
    }

    /**
     * 주행 제한(Pleos 주행 제한 개발 가이드: 주행 제한이 걸리면 CarUxRestrictions를 보고 화면을 맞춘다). 권한 없이 받는다.
     * 기어 값을 못 받거나 늦게 받아도 차가 알린 제한으로 화면을 줄이려는 것이다. 이 에뮬레이터는 D에서 걸리고 P에서 풀린다(0주차 시험)
     */
    private var uxManager: CarUxRestrictionsManager? = null

    private fun watchUx() {
        val m = runCatching { car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager }.getOrNull()
        if (m == null) { Prend.log("✗ 주행 제한 못 받음(기어로만 판단)"); return }
        uxManager = m
        val apply = { r: CarUxRestrictions? -> val on = r?.isRequiresDistractionOptimization == true; ui.post { update { it.copy(uxLimited = on) } } }
        runCatching {
            m.registerListener { r -> apply(r) }
            apply(m.currentCarUxRestrictions)
        }.onFailure { Prend.log("✗ 주행 제한 구독 안 됨: $it") }
    }

    private fun subscribeAaos() {
        val pm = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager ?: return
        aaosProps.forEach { (id, rate) ->
            val ok = runCatching { pm.registerCallback(property, id, rate) }.getOrElse { false }
            if (!ok) Prend.log("✗ ${VehiclePropertyIds.toString(id)} 구독 안 됨")
            if (id == VehiclePropertyIds.HAZARD_LIGHTS_STATE) hazardReadable = ok
        }
    }

    /** 차속 권한(CAR_SPEED, 처음 한 번 허락)을 받은 뒤 다시 구독한다 */
    fun resubscribe() {
        Prend.log("· 차 속성 다시 구독")
        subscribeAaos()
    }

    // ── Pleos Vehicle SDK ─────────────────────────────────────

    /** 리스너든 Kotlin 콜백이든, 불린 메서드 이름과 인자를 handler로 넘기는 프록시 */
    private fun proxy(type: Class<*>, label: String, handler: (String, Array<out Any?>) -> Unit): Any =
        java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { p, call, a ->
            when (call.name) {
                "toString" -> label
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === a?.getOrNull(0)
                else -> {
                    val args = a ?: emptyArray()
                    ui.post { handler(call.name, args) }
                    if (call.returnType.name == "kotlin.Unit") Unit else null
                }
            }
        }

    /** 공식 문서의 메서드를 이름으로 찾아 부른다. 구역 자리는 fixed에서, 리스너·콜백 자리는 프록시로 채운다 */
    private fun call(api: Any, method: String, fixed: List<Any> = emptyList(), handler: (String, Array<out Any?>) -> Unit) {
        val m = method(api, method) ?: run { Prend.log("✗ $method 없음"); return }
        val args = m.parameterTypes.map { type ->
            fixed.firstOrNull { type.isInstance(it) }
                ?: if (type.isInterface) proxy(type, method, handler)
                else { Prend.log("✗ $method: ${type.simpleName} 자리를 채울 값이 없음"); return }
        }
        runCatching { m.invoke(api, *args.toTypedArray()) }.onFailure { Prend.log("✗ $method 실패: ${it.cause ?: it}") }
    }

    /**
     * 값 알림만 골라 마지막 인자를 넘긴다. 리스너는 on…Updated, Kotlin 콜백(읽기 메서드의 onComplete)은 invoke로 온다.
     * 실패 알림은 기록만 한다
     */
    private fun updates(label: String, block: (Any?) -> Unit): (String, Array<out Any?>) -> Unit = { name, args ->
        when {
            name == "onFailed" || args.firstOrNull() is Exception -> Prend.log("✗ $label: ${args.firstOrNull()}")
            (name.startsWith("on") || name == "invoke") && args.isNotEmpty() -> block(args.last())
        }
    }

    private fun api(v: Vehicle, getter: String): Any? = runCatching {
        val key = v.javaClass.name + "#" + getter + "()"
        (methods[key] ?: v.javaClass.getMethod(getter).also { methods[key] = it }).invoke(v)
    }.getOrNull()

    private val front = WindowArea(WindowValue.FRONT_WINDSHIELD)
    private val windowSeats = listOf(WindowValue.ROW_1_LEFT, WindowValue.ROW_1_RIGHT, WindowValue.ROW_2_LEFT, WindowValue.ROW_2_RIGHT)
    private val seatValues = listOf(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_CENTER, SeatValue.ROW_2_RIGHT)
    private val doorValues = listOf(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT)

    private val pollWiper = object : Runnable {
        override fun run() {
            readWiperSwitch()
            if (state.wiperOn) io.postDelayed(this, 2000)
        }
    }

    private fun readWiperSwitch() {
        val wiper = vehicle?.let { api(it, "getWiper") } ?: return
        call(wiper, "getCurrentWiperSwitch", listOf(front), updates("와이퍼 단계") { v ->
            update { it.copy(wiperSwitch = v?.toString() ?: "OTHER") }
        })
    }

    private fun startPleos() {
        try {
            val v = Vehicle(context).also { it.initialize() }
            vehicle = v
            api(v, "getBrake")?.let { call(it, "registerBrakePedalStatus", handler = updates("브레이크") { b -> update { s -> s.copy(brake = b == true) } }) }
            api(v, "getTurnSignal")?.let { call(it, "registerTurnSignalState", handler = updates("방향지시등") { t -> update { s -> s.copy(turnSignal = t.toString()) } }) }

            api(v, "getWiper")?.let { wiper ->
                call(wiper, "registerWiperState", listOf(front), updates("와이퍼") { w ->
                    val on = w?.toString() == "ON"
                    update { it.copy(wiperOn = on) }
                    // 지우고 다시 넣기를 io에서 차례로(읽기 중에 지우면 줄기가 둘이 된다, 11/04 검토)
                    io.post { io.removeCallbacks(pollWiper); if (on) io.post(pollWiper) }
                    if (!on) update { it.copy(wiperSwitch = "OFF") }
                })
            }

            api(v, "getWindow")?.let { window ->
                call(window, "checkWindowPositionCapabilities", handler = { _, args ->
                    (args.firstOrNull { it is Map<*, *> } as? Map<*, *>)?.forEach { (area, bounds) ->
                        val name = windowSeats.firstOrNull { WindowArea(it) == area }?.name ?: return@forEach
                        // AreaBounds(minValue=0, maxValue=10): 최대값으로 열림 비율을 만든다
                        windowMax[name] = Regex("maxValue=(\\d+)").find(bounds.toString())?.groupValues?.get(1)?.toInt() ?: 10
                    }
                    // 이 답은 메인으로 온다: 창 넷의 알림 등록(SDK IPC)은 Pleos 스레드에서 한다(메인에서 하면 앱을 켤 때 화면이 0.5초쯤 굳었다, 11/04 시험 27)
                    val wins = windowSeats.filter { it.name in windowMax }
                    io.post {
                        wins.forEach { w ->
                            call(window, "registerWindowPosition", listOf(WindowArea(w)), updates("창 ${w.name}") { pos ->
                                val ratio = ((pos as? Int) ?: 0).toFloat() / (windowMax[w.name] ?: 10)
                                update { it.copy(windows = it.windows + (w.name to ratio.coerceIn(0f, 1f))) }
                            })
                        }
                    }
                })
            }

            api(v, "getSeat")?.let { seat ->
                seatValues.forEach { s ->
                    call(seat, "registerSeatOccupancy", listOf(SeatArea(s)), updates("좌석 ${s.name}") { o ->
                        rawSeats[s.name] = o == true
                        applySeats()
                    })
                }
            }
            api(v, "getDoor")?.let { door ->
                doorValues.forEach { d ->
                    call(door, "registerDoorPosition", listOf(DoorArea(d)), updates("문 ${d.name}") { p ->
                        update { it.copy(doors = it.doors + (d.name to ((p as? Int) ?: 0))) }
                    })
                }
            }
            // 에어백 전개(시험 51: 일반 권한 CAR_SAFETY로 열려 있다. 에뮬레이터에는 전개 속성이 없어 '지원 안 됨' 알림만 온다)
            api(v, "getSafety")?.let { safety ->
                listOf(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT).forEach { s ->
                    call(safety, "registerSeatAirbagDeployed", listOf(SeatArea(s)), updates("에어백 전개 ${s.name}") { d ->
                        if (d == true && !state.airbag) { Prend.log("◆ 에어백 전개: ${s.name}"); update { it.copy(airbag = true) } }
                    })
                }
            }
            checkSeatClimate(v)
            checkAmbient(v)
            checkLamps(v)
            checkFan(v)
            checkRecline(v)
            checkAir(v)
            checkTemp(v)
            readFuel()
        } catch (e: Exception) {
            Prend.log("✗ Pleos 준비 실패: $e")
        }
    }

    // ── 운전석 열선·통풍(Pleos Seat. 0주차 시험에서 쓴 호출과 같다) ──

    private val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)

    private val pollSeat = object : Runnable {
        override fun run() {
            readSeatClimate()
            readRecline()
            io.postDelayed(this, 5_000)
        }
    }

    /** 자리별 등받이 위치의 범위(차가 알려 준 것). 비어 있으면 이 차는 등받이 위치를 주지 않는다 */
    var reclineRange: Map<String, IntRange> = emptyMap()
        private set

    private fun checkRecline(v: Vehicle) {
        runCatching {
            v.getSeat().checkSeatBackrestAnglePositionCapabilities({ _: Any?, areas: Any? ->
                val m = (areas as? Map<*, *>)?.entries?.mapNotNull { (k, b) ->
                    val name = seatValues.firstOrNull { SeatArea(it) == k }?.name ?: return@mapNotNull null
                    val s = b.toString()
                    val min = Regex("minValue=(-?\\d+)").find(s)?.groupValues?.get(1)?.toInt() ?: return@mapNotNull null
                    val max = Regex("maxValue=(-?\\d+)").find(s)?.groupValues?.get(1)?.toInt() ?: return@mapNotNull null
                    if (max > min) name to (min..max) else null
                }?.toMap().orEmpty()
                ui.post { if (reclineRange.isEmpty()) reclineRange = m; Prend.log("· 등받이 위치 ${if (m.isEmpty()) "없음" else m.keys.joinToString()}") }
            }, { e: Exception -> ui.post { Prend.log("· 등받이 위치 없음($e)") } })
        }.onFailure { Prend.log("· 등받이 위치 없음($it)") }
    }

    private fun readRecline() {
        if (fakeRecline) return
        val seat = vehicle?.getSeat() ?: return
        reclineRange.keys.forEach { name ->
            val v = SeatValue.valueOf(name)
            runCatching {
                seat.getCurrentSeatBackrestAnglePosition(SeatArea(v), { p: Any? -> ui.post { (p as? Number)?.toInt()?.let { pos -> update { it.copy(recline = it.recline + (name to pos)) } } } }, { _: Exception -> })
            }
        }
    }

    private var fakeRecline = false

    /** 시험: 그 자리 등받이를 pos(0 끝까지 젖힘 ~ 100 세움)로 둔 것처럼(이 에뮬레이터 콘솔에는 시트 위치가 없다) */
    fun pretendRecline(seat: String, pos: Int) {
        fakeRecline = true
        if (seat !in reclineRange) reclineRange = reclineRange + (seat to 0..100)
        Prend.log("· 시험 등받이 $seat → $pos")
        update { it.copy(recline = it.recline + (seat to pos)) }
    }

    /** 운전석이 지원하는 단계를 묻는다. 답은 자리별 AreaBounds(minValue=…, maxValue=…) */
    private fun checkSeatClimate(v: Vehicle) {
        val seat = v.getSeat()
        var left = 2
        val done = {
            if (--left == 0) {
                seatChecked = true
                Prend.log("· 운전석 열선 ${heatRange ?: "없음"}, 통풍 ${ventRange ?: "없음"} · 자리별 열선 ${heatAt.keys.joinToString().ifEmpty { "없음" }}, 통풍 ${ventAt.keys.joinToString().ifEmpty { "없음" }}")
                io.post { io.removeCallbacks(pollSeat); io.post(pollSeat) }
            }
        }
        runCatching {
            seat.checkSeatTemperatureCapabilities({ _: Any?, areas: Any? -> ui.post { heatRange = bounds(areas); heatAt = boundsAll(areas); done() } },
                { e: Exception -> ui.post { Prend.log("✗ 열선 지원 확인 실패 $e"); done() } })
            seat.checkSeatVentilationCapabilities({ _: Any?, areas: Any? -> ui.post { ventRange = bounds(areas); ventAt = boundsAll(areas); done() } },
                { e: Exception -> ui.post { Prend.log("✗ 통풍 지원 확인 실패 $e"); done() } })
        }.onFailure { Prend.log("✗ 시트 지원 확인 실패 $it"); ui.post { seatChecked = true } }
    }

    private fun bounds(areas: Any?): IntRange? {
        val b = (areas as? Map<*, *>)?.entries?.firstOrNull { it.key == driverSeat }?.value?.toString() ?: return null
        val min = Regex("minValue=(-?\\d+)").find(b)?.groupValues?.get(1)?.toInt() ?: return null
        val max = Regex("maxValue=(-?\\d+)").find(b)?.groupValues?.get(1)?.toInt() ?: return null
        return min..max
    }

    /** 자리마다의 단계(SeatValue 이름 → 범위). 한 구역이 여러 자리를 묶으면 그 자리 모두에 같은 범위를 둔다 */
    private fun boundsAll(areas: Any?): Map<String, IntRange> =
        (areas as? Map<*, *>)?.entries?.flatMap { (k, b) ->
            val s = b.toString()
            val min = Regex("minValue=(-?\\d+)").find(s)?.groupValues?.get(1)?.toInt() ?: return@flatMap emptyList()
            val max = Regex("maxValue=(-?\\d+)").find(s)?.groupValues?.get(1)?.toInt() ?: return@flatMap emptyList()
            ((k as? SeatArea)?.values?.toList() ?: emptyList()).map { it.name to (min..max) }
        }?.toMap().orEmpty()

    /** 앱이 바꾼 값을 30초 동안 붙들어 둔다(에뮬레이터가 늦게 반영하는 동안 옛 값으로 깜빡이지 않게). 키는 열선이면 true */
    private val held = mutableMapOf<Boolean, Pair<Int, Long>>()

    private fun read(heat: Boolean, v: Int?) {
        val h = held[heat]
        if (h != null) {
            if (v == h.first || SystemClock.elapsedRealtime() - h.second > 30_000) held.remove(heat) else return
        }
        update { if (heat) it.copy(heat = v) else it.copy(vent = v) }
    }

    /** 운전석 열선·통풍을 다시 읽는다. after는 둘 다 답이 온 뒤(실패해도) 부른다 */
    fun readSeatClimate(after: ((CarState) -> Unit)? = null) {
        val seat = vehicle?.getSeat()
        val reads = listOfNotNull(heatRange?.let { "heat" }, ventRange?.let { "vent" })
        if (seat == null || reads.isEmpty()) { after?.invoke(state); return }
        var left = reads.size
        val one = { if (--left == 0) after?.invoke(state) }
        val level = { v: Any? -> (v as? Number)?.toInt() }
        io.post {
            runCatching {
                if ("heat" in reads) seat.getHvacSeatTemperature(driverSeat,
                    { t: Any? -> ui.post { read(true, level(t)); one() } }, { _: Exception -> ui.post { one() } })
                if ("vent" in reads) seat.getHvacSeatVentilationState(driverSeat,
                    { t: Any? -> ui.post { read(false, level(t)); one() } }, { _: Exception -> ui.post { one() } })
            }.onFailure { ui.post { after?.invoke(state) } }
        }
    }

    /**
     * 운전석 열선(시트 온도) 단계를 바꾼다. done은 차량 서비스가 요청을 받아들였는지(성공 응답).
     * 에뮬레이터는 앱이 바꾼 운전석 값을 25초쯤 뒤에야 다시 읽히게 해서(10/26) 다시 읽어 맞추지 않고, 30초 뒤 읽은 값은 기록만 한다
     */
    fun setSeatHeat(level: Int, done: (Boolean) -> Unit) = setSeat(true, level, done)

    fun setSeatVent(level: Int, done: (Boolean) -> Unit) = setSeat(false, level, done)

    private fun setSeat(heat: Boolean, level: Int, done: (Boolean) -> Unit) {
        val seat = vehicle?.getSeat() ?: return done(false)
        val what = if (heat) "열선" else "통풍"
        val failed = { e: Exception -> ui.post { Prend.log("✗ 운전석 $what $level 실패 $e"); done(false) }; Unit }
        val sent = { _: Any?, result: Any? ->
            val ok = result as? Boolean ?: true
            ui.post {
                Prend.log("· 운전석 $what $level 요청 ${if (ok) "받아들여짐" else "거절됨"}")
                if (ok) {
                    held[heat] = level to SystemClock.elapsedRealtime()
                    update { if (heat) it.copy(heat = level) else it.copy(vent = level) }
                }
                done(ok)
            }
            ui.postDelayed({ readSeatClimate { s -> Prend.log("· 확인: 운전석 $what ${if (heat) s.heat else s.vent}(요청 $level)") } }, 30_000)
            Unit
        }
        runCatching {
            if (heat) seat.setHvacSeatTemperature(driverSeat, level, sent, failed) else seat.setHvacSeatVentilation(driverSeat, level, sent, failed)
            Prend.log("◆ 운전석 $what → $level")
        }.onFailure { Prend.log("✗ 운전석 $what 요청 실패 $it"); done(false) }
    }

    /**
     * 다른 자리(동승석·뒷자리)의 열선(양수)·냉방(음수) 단계를 읽는다(잘래요). 모르면 null.
     * 이 에뮬레이터의 시트 온도는 운전석·동승석만 있다(R5-B)
     */
    fun readSeatAt(seat: SeatValue, done: (Int?) -> Unit) {
        val s = vehicle?.getSeat() ?: return done(null)
        runCatching { s.getHvacSeatTemperature(SeatArea(seat), { t: Any? -> ui.post { done((t as? Number)?.toInt()) } }, { _: Exception -> ui.post { done(null) } }) }
            .onFailure { done(null) }
    }

    /** 다른 자리의 통풍 단계를 읽는다(동승자가 덥다고 할 때 한 단 올리려고). 모르면 null */
    fun readSeatVentAt(seat: SeatValue, done: (Int?) -> Unit) {
        val s = vehicle?.getSeat() ?: return done(null)
        runCatching { s.getHvacSeatVentilationState(SeatArea(seat), { t: Any? -> ui.post { done((t as? Number)?.toInt()) } }, { _: Exception -> ui.post { done(null) } }) }
            .onFailure { done(null) }
    }

    /** 다른 자리의 열선(heat) 또는 통풍 단계를 바꾼다(잘래요). 운전석은 setSeatHeat·setSeatVent를 쓴다 */
    fun setSeatAt(seat: SeatValue, heat: Boolean, level: Int, done: (Boolean) -> Unit) {
        val s = vehicle?.getSeat() ?: return done(false)
        val what = "${seat.name} ${if (heat) "열선" else "통풍"} $level"
        val failed = { e: Exception -> ui.post { Prend.log("✗ $what 실패 $e"); done(false) }; Unit }
        val sent = { _: Any?, r: Any? -> ui.post { val ok = r as? Boolean ?: true; Prend.log("· $what 요청 ${if (ok) "받아들여짐" else "거절됨"}"); done(ok) }; Unit }
        runCatching {
            if (heat) s.setHvacSeatTemperature(SeatArea(seat), level, sent, failed) else s.setHvacSeatVentilation(SeatArea(seat), level, sent, failed)
            Prend.log("◆ $what")
        }.onFailure { Prend.log("✗ $what 요청 실패 $it"); done(false) }
    }

    // ── 바람(Pleos HVAC. 0주차 시험과 같은 호출): 쪽잠에서 깨울 때 얼굴 쪽으로 바람 ──

    /** 바람 세기·방향을 바꿀 구역(차가 알려 준 첫 구역). 없거나 아직 모르면 null */
    var fanArea: SeatArea? = null
        private set

    /** 공조 전원 구역. 공조가 꺼져 있으면 바람 세기를 바꿀 수 없어(PropertyAsyncError 2, 10/27) 먼저 켠다 */
    private var powerArea: SeatArea? = null

    private fun checkFan(v: Vehicle) {
        val first = { areas: Any? -> when (areas) { is Map<*, *> -> areas.keys.firstOrNull(); is Collection<*> -> areas.firstOrNull(); else -> null } as? SeatArea }
        runCatching {
            v.getHVAC().checkHvacFanSpeedCapabilities({ _: Any?, areas: Any? ->
                val a = first(areas)
                ui.post { fanArea = a; Prend.log("· 바람 세기 ${if (a != null) "바꿀 수 있음" else "없음"}") }
            }, { e: Exception -> ui.post { Prend.log("✗ 바람 지원 확인 실패 $e") } })
            v.getHVAC().checkHvacPowerCapabilities({ _: Any?, areas: Any? -> val a = first(areas); ui.post { powerArea = a } },
                { e: Exception -> ui.post { Prend.log("✗ 공조 전원 지원 확인 실패 $e") } })
        }.onFailure { Prend.log("✗ 바람 지원 확인 실패 $it") }
    }

    /** 바람을 바꿀 수 있게 미리: 지금 세기 그대로 한 번 보내 보고, 공조가 꺼져 있으면 켠다. done은 PREND가 켰는지 */
    fun fanReady(done: (Boolean) -> Unit) {
        readFan { now ->
            setFan(now ?: 1, face = false) { ok -> if (ok) done(false) else setHvacPower(true) { on -> done(on) } }
        }
    }

    // ── 핸들 각도(Pleos SteeringWheel, 일반 앱이 읽힘 10/27. AAOS PERF_STEERING_ANGLE은 signature라 막힘) ──

    @Volatile private var steerOn = false
    /** 달리는 동안 읽기(편한 때의 안정 주행: 20초 동안 핸들 ±10°, ride/Timing). 주차 코치와 따로 켜고 끈다 */
    @Volatile private var steerDrive = false
    /**
     * 읽은 각도는 0.5° 넘게 바뀔 때만 넣는다(작은 떨림마다 모든 기능에 onCar가 퍼지지 않게. 판정은 ±10°·바퀴 방향이라 영향 없음).
     * 읽기를 멈춘 뒤에 늦게 온 답은 버린다(멈추며 비운 값을 되살리지 않게)
     */
    private val steerRead = updates("핸들 각도") { a ->
        val d = (a as? Number)?.toFloat() ?: return@updates
        if (!(steerOn || steerDrive) || SystemClock.elapsedRealtime() <= fakeSteerUntil) return@updates
        if (state.steer.let { s -> s == null || kotlin.math.abs(s - d) >= .5f }) update { it.copy(steer = d) }
    }
    private val pollSteer = object : Runnable {
        override fun run() {
            val sw = vehicle?.let { api(it, "getSteeringWheel") }
            if (sw != null) call(sw, "getCurrentSteeringWheelAngle", handler = steerRead)
            // 주차 코치는 바퀴 안내가 늦지 않게 0.25초, 달리는 동안의 판정은 0.5초면 된다
            if (steerOn || steerDrive) io.postDelayed(this, if (steerOn) 250 else 500)
        }
    }

    /** 주차 코치가 켜 둔 동안만 0.25초마다 핸들 각도를 읽는다 */
    fun steerWatch(on: Boolean) {
        if (on == steerOn) return
        steerOn = on
        steerApply()
        Prend.log("· 핸들 각도 읽기 ${if (on) "시작" else "끝"}")
    }

    /** 달리는 동안(D) 0.5초마다 핸들 각도를 읽는다(운행 서비스가 기어에 맞춰 켜고 끈다) */
    fun steerWhileDriving(on: Boolean) {
        if (on == steerDrive) return
        steerDrive = on
        steerApply()
        Prend.log("· 달리는 동안 핸들 각도 읽기 ${if (on) "시작" else "끝"}")
    }

    /** 누구라도 읽고 있으면 돌리고, 아무도 없으면 멈추고 값을 비운다(다음에 읽기 전의 옛 값으로 판정하지 않게) */
    private fun steerApply() {
        io.post { io.removeCallbacks(pollSteer); if (steerOn || steerDrive) io.post(pollSteer) }
        if (!(steerOn || steerDrive)) update { it.copy(steer = null) }
    }

    /** 시험: 핸들 각도를 이 값으로 둔 것처럼(1분 동안 읽은 값은 버린다. 이미 보낸 읽기의 대답이 늦게 와 덮어쓰지 않게) */
    /** 시험: 차가 어두운 곳에 있는 것처럼(NIGHT_MODE, 지하 주차장 판단) */
    fun pretendNight(on: Boolean) { update { it.copy(night = on) }; Prend.log("· 시험: 어두움 $on") }

    /** 시험: 그 자리(SeatValue 이름)에 사람이 앉은 것처럼. 차가 다음에 점유를 알려 오면 그 값으로 바뀐다 */
    fun pretendSeat(seat: String, on: Boolean) { update { it.copy(seats = it.seats + (seat to on)) }; Prend.log("· 시험: 자리 $seat ${if (on) "앉음" else "빔"}") }

    /** 시험: 기어를 이 값으로 둔 것처럼(달리는 동안의 E 한 줄 판을 보려고. 차가 기어 신호를 다시 보내면 그 값으로 돌아간다) */
    fun pretendGear(gear: Int) { update { it.copy(gear = gear) }; Prend.log("· 시험: 기어 $gear") }

    /** 시험: 깜빡이를 켠 것처럼(LEFT·RIGHT·OFF. 말하기 전 기척 중에 켜서 말을 삼키는지 보려고. 차가 신호를 다시 보내면 그 값으로 돌아간다) */
    fun pretendSignal(s: String) { update { it.copy(turnSignal = s) }; Prend.log("· 시험: 깜빡이 $s") }

    fun pretendSteer(deg: Float) { fakeSteerUntil = android.os.SystemClock.elapsedRealtime() + 60_000; update { it.copy(steer = deg) } }
    private var fakeSteerUntil = 0L

    // ── 유종·주유구(AAOS INFO_*, CAR_INFO 일반 권한) ──

    /** 유종(AAOS FuelType 번호들: 1 휘발유, 3·4 경유, 7 LPG, 10 전기, 11 수소 …), 주유구·충전구 위치(PortLocationType). 모르면 빈 것·null */
    var fuelTypes: List<Int> = emptyList()
        private set
    var fuelDoor: Int? = null
        private set
    var evPort: Int? = null
        private set
    /** 차 모델 이름(AAOS INFO_MODEL, 에뮬레이터는 "Connect"). 정비 주기가 차종마다 달라 본다(ride/CareBook) */
    var model: String? = null
        private set

    private fun readFuel() {
        val pm = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager ?: return
        runCatching {
            fuelTypes = (pm.getProperty<Any>(VehiclePropertyIds.INFO_FUEL_TYPE, 0)?.value as? Array<*>)?.mapNotNull { (it as? Number)?.toInt() }.orEmpty()
            fuelDoor = (pm.getProperty<Any>(VehiclePropertyIds.INFO_FUEL_DOOR_LOCATION, 0)?.value as? Number)?.toInt()
            evPort = (pm.getProperty<Any>(VehiclePropertyIds.INFO_EV_PORT_LOCATION, 0)?.value as? Number)?.toInt()
            model = runCatching { pm.getProperty<Any>(VehiclePropertyIds.INFO_MODEL, 0)?.value?.toString() }.getOrNull()
            Prend.log("· 유종 $fuelTypes, 주유구 $fuelDoor, 충전구 $evPort, 모델 $model")
        }.onFailure { Prend.log("✗ 유종·주유구 읽기 실패 $it") }
    }

    /** 전기차인가(유종이 전기 하나뿐). 모르면 null */
    val electric: Boolean? get() = fuelTypes.takeIf { it.isNotEmpty() }?.let { it.all { t -> t == 10 } }

    /** 하이브리드로 보이는가(모델 이름에 하이브리드·HEV, 또는 유종에 휘발유와 전기가 함께) */
    val hybrid: Boolean get() = model?.let { m -> listOf("HEV", "HYBRID", "하이브리드").any { m.uppercase().contains(it) } } == true || (1 in fuelTypes && 10 in fuelTypes)

    // ── 누적 주행거리(Pleos Odometer, 일반 권한으로 읽힘: 11/01 시험에서 콘솔 14800km → 14800.0) ──

    /** 누적 주행거리(km). 차가 알려 주지 않으면 null */
    fun readOdometer(done: (Float?) -> Unit) {
        val v = vehicle ?: return done(null)
        var answered = false
        io.post {
            val od = api(v, "getOdometer")
            if (od == null) { ui.post { done(null) }; return@post }
            call(od, "getCurrentDistanceDriven") { _, args ->
                if (answered) return@call
                answered = true
                done((args.lastOrNull() as? Number)?.toFloat())
            }
        }
    }

    // ── 공기(Pleos HVAC): 내기 순환 읽고 바꾸기, 앞유리 성에 제거, 에어컨 ──

    private var recircArea: SeatArea? = null
    private var acArea: SeatArea? = null
    private val windshield = WindowArea(WindowValue.FRONT_WINDSHIELD)

    private fun checkAir(v: Vehicle) {
        val first = { areas: Any? -> when (areas) { is Map<*, *> -> areas.keys.firstOrNull(); is Collection<*> -> areas.firstOrNull(); else -> null } as? SeatArea }
        runCatching {
            v.getHVAC().checkHvacRecirculationCapabilities({ _: Any?, areas: Any? -> val a = first(areas); ui.post { recircArea = a; Prend.log("· 내기 순환 ${if (a != null) "있음" else "없음"}"); readAir() } },
                { e: Exception -> ui.post { Prend.log("✗ 내기 순환 지원 확인 실패 $e") } })
            v.getHVAC().checkHvacAcCapabilities({ _: Any?, areas: Any? -> val a = first(areas); ui.post { acArea = a } }, { _: Exception -> })
        }.onFailure { Prend.log("✗ 공기 지원 확인 실패 $it") }
    }

    /** 내기 순환 상태를 읽는다(10초마다, 공기 살핌이 부른다) */
    fun readAir() {
        val hvac = vehicle?.getHVAC() ?: return
        val a = recircArea ?: return
        runCatching { hvac.getHvacRecirculationState(a, { v: Any? -> ui.post { (v as? Boolean)?.let { r -> update { it.copy(recirc = r) } } } }, { _: Exception -> }) }
    }

    /** 바깥 공기로(내기 순환 끄기) */
    fun freshAir(done: (Boolean) -> Unit) = setRecirc(false, done)

    /** 내기 순환을 켜고(안쪽 공기) 끈다(바깥 공기) */
    fun setRecirc(on: Boolean, done: (Boolean) -> Unit) {
        val hvac = vehicle?.getHVAC()
        val a = recircArea
        if (hvac == null || a == null) return done(false)
        val what = if (on) "안쪽 공기" else "바깥 공기"
        runCatching {
            hvac.setHvacRecirculation(a, on, { _: Any?, _: Any? -> ui.post { update { it.copy(recirc = on) }; done(true) }; Unit }, { e: Exception -> ui.post { Prend.log("✗ $what 실패 $e"); done(false) }; Unit })
            Prend.log("◆ 공기 → $what")
        }.onFailure { done(false) }
    }

    /**
     * 앞유리 성에 제거를 켠다(바깥 공기·에어컨도 함께). 먼저 바로 보내 보고, 공조가 꺼져 있어 안 되면 공조를 켠 뒤
     * 켜진 것이 반영될 때까지 5초마다 다시 보낸다(fanOn과 같은 까닭: 전원을 켠 직후엔 실패한다, 0주차 시험).
     * live가 false가 되면(부른 쪽이 거둠: "성에 제거 꺼"·"되돌려"·새 운행) 다시 시도를 그만두고 done을 부르지 않는다.
     * 그사이 켜졌으면 다시 끈다(끄라고 한 뒤에 켜지 않게, 11/08 오류 점검 D2)
     */
    fun defrost(live: () -> Boolean = { true }, done: (Boolean) -> Unit) {
        val hvac = vehicle?.getHVAC() ?: return done(false)
        val send = { then: (Boolean) -> Unit ->
            runCatching {
                hvac.setHvacDefroster(windshield, true, { _: Any?, _: Any? -> ui.post { then(true) }; Unit }, { e: Exception -> ui.post { Prend.log("· 성에 제거 보냄 실패 $e"); then(false) }; Unit })
            }.onFailure { then(false) }
            Unit
        }
        val after = { ok: Boolean ->
            if (ok) {
                acArea?.let { a -> runCatching { hvac.setHvacAc(a, true, { _: Any?, _: Any? -> Unit }, { _: Exception -> Unit }) } }
                freshAir { }
                Prend.log("◆ 공기 → 앞유리 성에 제거")
            } else Prend.log("✗ 성에 제거를 켜지 못함")
            done(ok)
        }
        val dropped: (Boolean) -> Unit = { good -> if (good) defrostOff { }; Prend.log("· 성에 제거 다시 시도 그만(부른 쪽이 거둠)") }
        send { ok ->
            if (!live()) return@send dropped(ok)
            if (ok) return@send after(true)
            setHvacPower(true) { on ->
                if (!on) return@setHvacPower after(false)
                var tries = 0
                val again = object : Runnable {
                    override fun run() {
                        if (!live()) { dropped(false); return }
                        send { good ->
                            when {
                                !live() -> dropped(good)
                                good -> after(true)
                                ++tries < 24 -> ui.postDelayed(this, 5_000)
                                else -> after(false)
                            }
                        }
                    }
                }
                ui.postDelayed(again, 2_000)
            }
        }
    }

    /** 앞유리 성에 제거를 끈다("성에 제거 꺼", 말로 켠 것의 "되돌려", 검토 11/07). 함께 켰던 에어컨·바깥 공기는 그대로 둔다 */
    fun defrostOff(done: (Boolean) -> Unit) {
        val hvac = vehicle?.getHVAC() ?: return done(false)
        runCatching {
            hvac.setHvacDefroster(windshield, false, { _: Any?, _: Any? -> ui.post { done(true) }; Unit }, { e: Exception -> ui.post { Prend.log("✗ 성에 제거 끄기 실패 $e"); done(false) }; Unit })
            Prend.log("◆ 공기 → 앞유리 성에 제거 끔")
        }.onFailure { done(false) }
    }

    /** 공조 전원을 켜고 끈다 */
    fun setHvacPower(on: Boolean, done: (Boolean) -> Unit) {
        val hvac = vehicle?.getHVAC()
        val a = powerArea
        if (hvac == null || a == null) return done(false)
        runCatching {
            hvac.setHvacPower(a, on, { _: Any?, _: Any? -> ui.post { done(true) }; Unit }, { e: Exception -> ui.post { Prend.log("✗ 공조 전원 실패 $e"); done(false) }; Unit })
            Prend.log("◆ 공조 전원 → ${if (on) "켬" else "끔"}")
        }.onFailure { done(false) }
    }

    /** 바람을 건드려도 되는지 볼 공조 상태(모르면 null): 전원, AUTO, 앞유리 성에 제거, 최대 성에 제거, 내기 순환 */
    data class Climate(val power: Boolean? = null, val auto: Boolean? = null, val defrost: Boolean? = null, val maxDefrost: Boolean? = null, val recirc: Boolean? = null)

    /**
     * 공조 상태를 차례로 읽는다(이야기 때 바람 비켜 서기, 별첨 4 §4-5). 전원을 먼저 읽고 꺼져 있으면 나머지는 읽지 않는다.
     * 대답이 2초 안에 오지 않는 값은 모르는 것(null)으로 둔다. 자동 김서림 방지는 Pleos SDK에 읽는 메서드가 없다
     */
    fun readClimate(done: (Climate) -> Unit) {
        val hvac = vehicle?.getHVAC()
        val seat = fanArea
        if (hvac == null || seat == null) return done(Climate())
        val one = { ask: ((Any?) -> Unit, (Exception) -> Unit) -> Unit, then: (Boolean?) -> Unit ->
            var answered = false
            val back = { v: Boolean? -> ui.post { if (!answered) { answered = true; then(v) } }; Unit }
            ui.postDelayed({ back(null) }, 2_000)
            runCatching { ask({ v -> back(v as? Boolean) }, { _ -> back(null) }) }.onFailure { back(null) }
        }
        one({ ok, fail -> hvac.getHvacPowerState(powerArea ?: seat, ok, fail) }) { power ->
            if (power == false) done(Climate(power = false))
            else one({ ok, fail -> hvac.getHvacAutoState(seat, ok, fail) }) { auto ->
                one({ ok, fail -> hvac.getHvacDefrosterState(windshield, ok, fail) }) { defrost ->
                    one({ ok, fail -> hvac.getHvacMaxDefrostState(seat, ok, fail) }) { max ->
                        one({ ok, fail -> hvac.getHvacRecirculationState(recircArea ?: seat, ok, fail) }) { recirc ->
                            done(Climate(power, auto, defrost, max, recirc))
                        }
                    }
                }
            }
        }
    }

    /**
     * 바람을 켠다: 바꿀 수 없으면(공조가 꺼짐) 공조를 켜고, 켜진 것이 반영될 때까지 5초마다 다시(2분까지, 에뮬레이터는 80초쯤 걸렸다).
     * 0주차 시험: 전원을 켠 0.8초 뒤엔 실패, 이미 켜져 있으면 성공. done의 둘째 값은 공조를 PREND가 켰는지(되돌릴 때 끈다)
     * live가 false가 되면(부른 쪽이 끝남: 쪽잠을 마침) 다시 시도를 그만둔다. 끝난 뒤에 바람을 3단·얼굴 쪽으로 다시 세우지 않게.
     * 그만둘 때도 done(false, true)로 알린다: 부른 쪽이 PREND가 켠 공조를 끌 수 있게
     */
    fun fanOn(speed: Int, live: () -> Boolean = { true }, done: (Boolean, Boolean) -> Unit) {
        setFan(speed, face = true) { ok ->
            if (ok) return@setFan done(true, false)
            if (!live()) return@setFan
            setHvacPower(true) { on ->
                if (!on) return@setHvacPower done(false, false)
                var tries = 0
                val again = object : Runnable {
                    override fun run() {
                        if (!live()) { Prend.log("· 바람 다시 시도 그만(부른 쪽이 끝남)"); done(false, true); return }
                        setFan(speed, face = true) { good ->
                            when {
                                good -> done(true, true)
                                ++tries < 24 -> ui.postDelayed(this, 5_000)
                                else -> done(false, true)
                            }
                        }
                    }
                }
                ui.postDelayed(again, 5_000)
            }
        }
    }

    /** 바람 세기를 읽는다(모르면 null) */
    fun readFan(done: (Int?) -> Unit) {
        val hvac = vehicle?.getHVAC()
        val a = fanArea
        if (hvac == null || a == null) return done(null)
        runCatching { hvac.getHvacFanSpeed(a, { v: Any? -> ui.post { done((v as? Number)?.toInt()) } }, { _: Exception -> ui.post { done(null) } }) }
            .onFailure { done(null) }
    }

    /** 바람 방향(얼굴·발 등). 모르면 null */
    fun readFanDirection(done: (FanDirectionType?) -> Unit) {
        val hvac = vehicle?.getHVAC()
        val a = fanArea
        if (hvac == null || a == null) return done(null)
        runCatching { hvac.getHvacFanDirection(a, { d: FanDirectionType? -> ui.post { done(d) } }, { _: Exception -> ui.post { done(null) } }) }
            .onFailure { done(null) }
    }

    /** 바람 방향을 돌려놓는다(쪽잠 깨우기에서 얼굴 쪽으로 돌린 것) */
    fun setFanDirection(d: FanDirectionType) {
        val hvac = vehicle?.getHVAC()
        val a = fanArea ?: return
        runCatching { hvac?.setHvacFanDirection(a, d, { _: Any?, _: Any? -> Unit }, { e: Exception -> ui.post { Prend.log("✗ 바람 방향 실패 $e") }; Unit }) }
    }

    /** 바람 세기를 바꾸고, face면 얼굴 쪽으로 돌린다 */
    fun setFan(speed: Int, face: Boolean, done: (Boolean) -> Unit) {
        val hvac = vehicle?.getHVAC()
        val a = fanArea
        if (hvac == null || a == null) return done(false)
        val failed = { e: Exception -> ui.post { Prend.log("✗ 바람 바꾸기 실패 $e"); done(false) }; Unit }
        runCatching {
            hvac.setHvacFanSpeed(a, speed, { _: Any?, r: Any? -> ui.post { done(r as? Boolean ?: true) }; Unit }, failed)
            if (face) hvac.setHvacFanDirection(a, FanDirectionType.FACE, { _: Any?, _: Any? -> Unit }, { e: Exception -> ui.post { Prend.log("✗ 바람 방향 실패 $e") }; Unit })
            Prend.log("◆ 바람 → 세기 $speed${if (face) " · 얼굴 쪽" else ""}")
        }.onFailure { Prend.log("✗ 바람 바꾸기 실패 $it"); done(false) }
    }

    // ── 구역 온도(Pleos HVAC): 동승자가 춥다·덥다는데 그 자리에 열선·통풍이 없을 때 그 쪽 구역을 1도 ──

    /** 온도를 바꿀 수 있는 구역과 범위(°C). 앞 2구역 차면 운전석 쪽·동승석 쪽 둘이다. 없거나 아직 모르면 비어 있다 */
    var tempZones: Map<SeatArea, ClosedFloatingPointRange<Float>> = emptyMap()
        private set

    private fun checkTemp(v: Vehicle) {
        runCatching {
            v.getHVAC().checkHvacTemperatureCapabilities({ _: Any?, areas: Any? ->
                val m = (areas as? Map<*, *>)?.entries?.mapNotNull { (k, b) ->
                    val a = k as? SeatArea ?: return@mapNotNull null
                    val ab = b as? AreaBounds<*> ?: return@mapNotNull null
                    val min = (ab.minValue as? Number)?.toFloat() ?: return@mapNotNull null
                    val max = (ab.maxValue as? Number)?.toFloat() ?: return@mapNotNull null
                    if (max > min) a to (min..max) else null
                }?.toMap().orEmpty()
                ui.post { tempZones = m; Prend.log("· 구역 온도 ${if (m.isEmpty()) "없음" else m.keys.joinToString { z -> z.values.joinToString("+") { it.name } }}") }
            }, { e: Exception -> ui.post { Prend.log("· 구역 온도 없음($e)") } })
        }.onFailure { Prend.log("· 구역 온도 없음($it)") }
    }

    /**
     * 그 자리를 데우거나 식힐 구역. 운전석이 든 구역은 고르지 않는다(동승자 말로 운전자의 온도를 바꾸지 않게).
     * 그 자리가 든 구역이 없으면(앞 2구역 차의 뒷자리) 동승석 쪽 구역(별첨 2 §5-7 '앞 2구역 차는 동승석')
     */
    fun tempZoneOf(seat: SeatValue): SeatArea? {
        val free = tempZones.keys.filter { z -> SeatValue.ROW_1_LEFT !in z.values }
        return free.firstOrNull { seat in it.values } ?: free.firstOrNull { SeatValue.ROW_1_RIGHT in it.values }
    }

    /** 그 구역 온도를 delta(°C)만큼 바꾼다(범위 안에서). done은 바꾼 온도, 못 바꿨거나 이미 끝이면 null */
    fun nudgeTemp(zone: SeatArea, delta: Float, done: (Float?) -> Unit) {
        val hvac = vehicle?.getHVAC() ?: return done(null)
        val range = tempZones[zone] ?: return done(null)
        val name = zone.values.joinToString("+") { it.name }
        runCatching {
            hvac.getHvacTemperature(zone, TemperatureUnit.CELSIUS, { t: Float? ->
                ui.post {
                    val cur = t ?: return@post done(null)
                    val to = (cur + delta).coerceIn(range.start, range.endInclusive)
                    if (to == cur) return@post done(null)
                    runCatching {
                        hvac.setHvacTemperature(zone, to, TemperatureUnit.CELSIUS, { _: Any?, _: Any? -> ui.post { Prend.log("◆ 구역 온도 $name $cur → $to"); done(to) }; Unit },
                            { e: Exception -> ui.post { Prend.log("✗ 구역 온도 $name 실패 $e"); done(null) }; Unit })
                    }.onFailure { done(null) }
                }
            }, { _: Exception -> ui.post { done(null) } })
        }.onFailure { done(null) }
    }

    // ── 무드등(Pleos Light. 이 에뮬레이터는 실내 전체 한 구역, 0주차 시험과 같은 호출) ──

    /** 무드등 구역. 없거나 아직 모르면 null */
    var ambientArea: SeatArea? = null
        private set

    private fun checkAmbient(v: Vehicle) {
        runCatching {
            v.getLight().checkAmbientLightBrightnessCapabilities({ _: Any?, areas: Any? ->
                val a = when (areas) { is Map<*, *> -> areas.keys.firstOrNull(); is Collection<*> -> areas.firstOrNull(); else -> null } as? SeatArea
                ui.post { ambientArea = a; Prend.log("· 무드등 ${if (a != null) "있음" else "없음"}") }
            }, { e: Exception -> ui.post { Prend.log("✗ 무드등 지원 확인 실패 $e") } })
        }.onFailure { Prend.log("✗ 무드등 지원 확인 실패 $it") }
    }

    /** 무드등 밝기와 색(0xRRGGBB)을 읽는다. 모르면 null */
    fun readAmbient(done: (brightness: Int?, color: Int?) -> Unit) {
        val light = vehicle?.getLight()
        val a = ambientArea
        if (light == null || a == null) return done(null, null)
        var b: Int? = null
        var c: Int? = null
        var left = 2
        val one = { if (--left == 0) done(b, c) }
        runCatching {
            light.getAmbientLightBrightness(a, { v: Any? -> ui.post { b = (v as? Number)?.toInt(); one() } }, { _: Exception -> ui.post { one() } })
            light.getAmbientLightBrightnessColor(a, { v: Any? -> ui.post { c = (v as? Number)?.toInt()?.and(0xFFFFFF); one() } }, { _: Exception -> ui.post { one() } })
        }.onFailure { Prend.log("✗ 무드등 읽기 실패 $it"); done(null, null) }
    }

    /** 무드등 밝기와 색을 바꾼다. done은 둘 다 차량 서비스가 받아들였는지 */
    fun setAmbient(brightness: Int, color: Int, done: (Boolean) -> Unit) {
        val light = vehicle?.getLight()
        val a = ambientArea
        if (light == null || a == null) return done(false)
        var ok = true
        var left = 2
        val one = { good: Boolean -> ok = ok && good; if (--left == 0) done(ok) }
        val failed = { e: Exception -> ui.post { Prend.log("✗ 무드등 바꾸기 실패 $e"); one(false) }; Unit }
        runCatching {
            light.setAmbientLightBrightness(a, brightness, { _: Any?, r: Any? -> ui.post { one(r as? Boolean ?: true) }; Unit }, failed)
            light.setAmbientLightBrightnessColor(a, color, { _: Any?, r: Any? -> ui.post { one(r as? Boolean ?: true) }; Unit }, failed)
            Prend.log("◆ 무드등 → 밝기 $brightness 색 #%06X".format(color))
        }.onFailure { Prend.log("✗ 무드등 바꾸기 실패 $it"); done(false) }
    }

    // ── 독서등(Pleos Light): 잘래요 동안 쉬는 자리 독서등이 다시 켜지면 깬 것으로 본다(11/01 추천 4).
    // 끄기(쓰기)는 Pleos 차량 서비스가 기어를 보고 P가 아니면 거절해(50001, 11/01 시험) 읽기만 둔다 ──

    /** 독서등 구역(자리마다 또는 줄마다) */
    private var readingAreas: List<SeatArea> = emptyList()

    private fun checkLamps(v: Vehicle) {
        runCatching {
            v.getLight().checkReadingLightSwitchCapabilities({ _: Any?, areas: Any? ->
                val list = (areas as? Map<*, *>)?.keys?.filterIsInstance<SeatArea>().orEmpty()
                ui.post { readingAreas = list; Prend.log("· 독서등 구역 ${list.size}개") }
            }, { e: Exception -> ui.post { Prend.log("✗ 독서등 확인 실패 $e") } })
        }.onFailure { Prend.log("✗ 독서등 확인 실패 $it") }
    }

    private fun readingArea(seat: SeatValue) = readingAreas.firstOrNull { seat in it.values }

    /** 그 자리 독서등 스위치: 켜짐 true, 꺼짐 false, 모름 null(자동은 켜짐으로 보지 않는다) */
    fun readReading(seat: SeatValue, done: (Boolean?) -> Unit) {
        val light = vehicle?.getLight()
        val a = readingArea(seat)
        if (light == null || a == null) return done(null)
        runCatching { light.getReadingLightSwitch(a, { s: LightSwitch? -> ui.post { done(s?.let { it == LightSwitch.ON }) } }, { _: Exception -> ui.post { done(null) } }) }
            .onFailure { done(null) }
    }

    /**
     * 시험: 경고등(운전자 주의 경고 DRIVER_ATTENTION 등)을 일반 앱이 읽을 수 있는가.
     * 공식 문서의 본문은 PRIVATE_CAR_SAFETY(signature|privileged), getTelltalesInfo 주석은 CAR_SAFETY라 엇갈린다(R14).
     */
    fun probeTelltales() {
        val v = vehicle ?: return Prend.log("✗ 경고등 시험: 차량 연결 없음")
        val safety = api(v, "getSafety") ?: return Prend.log("✗ 경고등 시험: getSafety 없음")
        safety.javaClass.methods.filter { it.name.contains("Telltale") }.forEach { m ->
            Prend.log("· 경고등 메서드 ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
        }
        val log: (String, Array<out Any?>) -> Unit = { name, args -> Prend.log("· 경고등 $name: ${args.joinToString()}") }
        call(safety, "checkTelltalesCapabilities", handler = log)
        call(safety, "getTelltalesInfo", handler = log)
        call(safety, "registerTelltales", handler = log)
    }

    /**
     * 시험: 비상등을 일반 앱이 읽을 수 있는가. AAOS의 HAZARD_LIGHTS_STATE는 CAR_EXTERIOR_LIGHTS(signature|privileged)라 막힌다(10/27).
     * Pleos 차량 SDK의 모든 분야에서 이름에 Hazard·Emergency·TurnSignal이 든 메서드를 적고, 읽기(get·check)는 불러 본다
     */
    fun probeHazard() {
        val v = vehicle ?: return Prend.log("✗ 비상등 시험: 차량 연결 없음")
        v.javaClass.methods.filter { it.name.startsWith("get") && it.parameterCount == 0 }.forEach { g ->
            val a = runCatching { g.invoke(v) }.getOrNull() ?: return@forEach
            a.javaClass.methods.filter { m -> listOf("Hazard", "Emergency", "TurnSignal").any { m.name.contains(it) } }.forEach { m ->
                Prend.log("· 비상등 메서드 ${g.name}.${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
                if ((m.name.startsWith("get") || m.name.startsWith("check")) && m.parameterTypes.all { it.isInterface })
                    call(a, m.name, handler = { name, args -> Prend.log("· 비상등 ${m.name} $name: ${args.joinToString()}") })
            }
        }
    }

    /**
     * 시험(초보 기능 후보, 10/27): 핸들 각도(Pleos SteeringWheel), 유종·주유구 위치(AAOS INFO_*, CAR_INFO 일반 권한),
     * 내기 순환·성에 제거 상태(Pleos HVAC)를 일반 앱이 읽을 수 있는가
     */
    fun probeMore() {
        val v = vehicle ?: return Prend.log("✗ 시험: 차량 연결 없음")
        val log: (String) -> (String, Array<out Any?>) -> Unit = { what -> { name, args -> Prend.log("· 시험 $what $name: ${args.joinToString().take(200)}") } }
        api(v, "getSteeringWheel")?.let { sw ->
            call(sw, "checkSteeringWheelAngleCapabilities", handler = log("핸들 각도 지원"))
            call(sw, "getCurrentSteeringWheelAngle", handler = log("핸들 각도"))
        } ?: Prend.log("✗ 시험: getSteeringWheel 없음")
        val hvac = v.getHVAC()
        listOf("getHvacRecirculationState", "getHvacDefrosterState", "getHvacAcState", "getHvacPowerState").forEach { m ->
            val area = if (m == "getHvacDefrosterState") WindowArea(WindowValue.FRONT_WINDSHIELD) else fanArea
            if (area != null) call(hvac, m, listOf(area), log(m)) else Prend.log("· 시험 $m: 구역 모름")
        }
        val pm = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        listOf(VehiclePropertyIds.INFO_FUEL_TYPE, VehiclePropertyIds.INFO_FUEL_DOOR_LOCATION, VehiclePropertyIds.INFO_EV_PORT_LOCATION, VehiclePropertyIds.INFO_MAKE, VehiclePropertyIds.INFO_MODEL).forEach { id ->
            val r = runCatching { pm?.getProperty<Any>(id, 0)?.value?.let { if (it is IntArray) it.joinToString() else it.toString() } }
            Prend.log("· 시험 ${VehiclePropertyIds.toString(id)}: ${r.getOrElse { "실패 $it" }}")
        }
    }

    /**
     * 시험 51: 에어백이 터졌는지를 일반 앱이 읽을 수 있는가(급할 땐 안내원: 사고를 알아채고 "괜찮으세요?"를 묻는다).
     * Safety의 Airbag 메서드를 모두 적고, 앞자리 둘을 넣어 불러 본다.
     */
    fun probeAirbag() {
        val v = vehicle ?: return Prend.log("✗ 에어백 시험: 차량 연결 없음")
        val safety = api(v, "getSafety") ?: return Prend.log("✗ 에어백 시험: getSafety 없음")
        val methods = safety.javaClass.methods.filter { it.name.contains("Airbag") }
        methods.forEach { m -> Prend.log("· 에어백 메서드 ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})") }
        val log: (String, Array<out Any?>) -> Unit = { name, args -> Prend.log("· 에어백 $name: ${args.joinToString()}") }
        methods.map { it.name }.distinct().forEach { name ->
            if (name.startsWith("check")) call(safety, name, handler = log)
            else listOf(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT).forEach { s ->
                Prend.log("· 에어백 $name(${s.name}) 부름")
                call(safety, name, listOf(SeatArea(s)), log)
            }
        }
    }

    /**
     * 시험(정비 안내 후보, 11/01): 누적 주행거리·엔진오일·브레이크액·워셔액·타이어 공기압·연료량을 일반 앱이 읽을 수 있는가.
     * Pleos Odometer·Safety·Tire·Fuel의 읽기 메서드 가운데 이름이 맞는 것을 부른다. 구역이 필요하면 그 구역 값을 하나씩 넣어 본다.
     * AAOS 표준 속성(PERF_ODOMETER·TIRE_PRESSURE·ENGINE_OIL_LEVEL·FUEL_LEVEL)도 읽어 본다
     */
    fun probeMaint() {
        val v = vehicle ?: return Prend.log("✗ 정비 시험: 차량 연결 없음")
        val keys = listOf("Distance", "Oil", "Washer", "Fluid", "Tire", "Pressure", "FuelLevel", "Range", "Refill", "Capacity")
        listOf("getOdometer", "getSafety", "getTire", "getFuel").forEach { g ->
            val a = api(v, g) ?: return@forEach Prend.log("✗ 정비 시험: $g 없음")
            a.javaClass.methods.filter { m -> (m.name.startsWith("get") || m.name.startsWith("check") || m.name.startsWith("has")) && keys.any { m.name.contains(it) } }
                .distinctBy { it.name }.forEach { m ->
                    val log: (String, Array<out Any?>) -> Unit = { name, args -> Prend.log("· 정비 시험 $g.${m.name} $name: ${args.joinToString().take(240)}") }
                    val plain = m.parameterTypes.filterNot { it.isInterface }
                    if (plain.isEmpty()) return@forEach call(a, m.name, handler = log)
                    // 구역 자리: 열거형 하나를 받는 생성자로 그 열거형 값마다 만들어 넣는다
                    val type = plain.first()
                    val ctor = type.constructors.firstOrNull { it.parameterCount == 1 && it.parameterTypes[0].isEnum }
                    val values = ctor?.parameterTypes?.get(0)?.enumConstants?.take(6).orEmpty()
                    if (values.isEmpty() && m.name == "getLowTirePressure") {
                        // 바퀴 구역은 능력 확인이 돌려준 것을 그대로 쓴다
                        return@forEach call(a, "checkLowTirePressureCapabilities", handler = { _, args ->
                            val areas = args.flatMap { x -> (x as? Collection<*>)?.toList() ?: (x as? Map<*, *>)?.keys?.toList() ?: emptyList() }.filterNotNull().filter { it.javaClass.simpleName == "WheelArea" }
                            areas.forEachIndexed { i, area -> call(a, m.name, listOf(area)) { name, r -> Prend.log("· 정비 시험 $g.${m.name}(바퀴 $i: $area) $name: ${r.joinToString().take(200)}") } }
                        })
                    }
                    if (values.isEmpty()) return@forEach Prend.log("· 정비 시험 $g.${m.name}(${m.parameterTypes.joinToString { it.simpleName }}): 구역을 만들 수 없음")
                    values.forEach { e -> runCatching { ctor!!.newInstance(e) }.getOrNull()?.let { area -> Prend.log("· 정비 시험 $g.${m.name}($e) 부름"); call(a, m.name, listOf(area), log) } }
                }
        }
        val pm = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        listOf(VehiclePropertyIds.PERF_ODOMETER, VehiclePropertyIds.TIRE_PRESSURE, VehiclePropertyIds.ENGINE_OIL_LEVEL, VehiclePropertyIds.FUEL_LEVEL, VehiclePropertyIds.FUEL_LEVEL_LOW, VehiclePropertyIds.INFO_FUEL_CAPACITY).forEach { id ->
            val r = runCatching { pm?.getProperty<Any>(id, 0)?.value?.let { if (it is FloatArray) it.joinToString() else it.toString() } }
            Prend.log("· 정비 시험 ${VehiclePropertyIds.toString(id)}: ${r.getOrElse { "실패 ${it.toString().take(160)}" }}")
        }
    }

    /** 시험: 에어백이 터진 것처럼(에뮬레이터에는 전개 속성도 Virtual Console 조작도 없다. 영상에서는 시연 버튼이라고 밝힌다) */
    fun pretendAirbag(on: Boolean) {
        Prend.log("· 시험 에어백 ${if (on) "전개" else "되돌림"}")
        pretendingAirbag = on
        update { it.copy(airbag = on) }
    }

    /** 시험 에어백을 켜 둔 중인가(위 막대의 '시연' 표, 디버그 빌드만) */
    @Volatile var pretendingAirbag = false
        private set

    /** Pleos 차량 SDK 준비는 3초쯤 걸려(10/25 측정) 메인 스레드를 막지 않게 따로 한다. 값 알림은 프록시가 메인으로 넘긴다 */
    private val pleosThread = HandlerThread("prend-pleos").apply { start() }

    fun start() {
        startAaos()
        // io를 여기서 처음 만든다(스레드가 살아 있을 때. 끝난 스레드로 만들면 Handler(null)로 앱이 죽는다, 11/04 검토)
        io.post {
            val t0 = SystemClock.elapsedRealtime()
            startPleos()
            Prend.log("✓ 차량 연결 층 시작(Pleos 준비 ${SystemClock.elapsedRealtime() - t0}ms)")
        }
    }

    fun stop() {
        pleosThread.quitSafely()
        ui.removeCallbacksAndMessages(null)
        runCatching { (car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager)?.unregisterCallback(property) }
        runCatching { uxManager?.unregisterListener() }
        car?.disconnect()
    }
}
