package app.prend.ride

import android.os.SystemClock
import app.prend.Prend
import app.prend.car.CarState

/**
 * 에어백이 터지지 않은 가벼운 사고를 알아채고 "혹시 사고 나셨어요?"를 한 번 묻는다(사용자 결정 10/27).
 * - 급제동(1초에 15km/h 넘게 줄어듦)으로 5초 안에 멈췄다
 * - 비상등을 읽을 수 있는 차면: 그 뒤 2분 안에 비상등이 켜지고, 켜진 뒤 1분 안에 운전석 문이 열리면 묻는다.
 *   급제동 경보(ESS)는 급제동 뒤 속도가 떨어지면 비상등을 저절로 켜고(기아 설명서), 사람이 직접 켜도 같다
 * - 비상등을 읽을 수 없는 차면: 멈춘 뒤 1분 안에 P에 두고 운전석 문이 열리면 묻는다.
 *   이 에뮬레이터(Pleos Connect)는 비상등을 일반 앱에 주지 않는다: AAOS HAZARD_LIGHTS_STATE는 CAR_EXTERIOR_LIGHTS(signature|privileged),
 *   Pleos SDK에는 비상등 메서드가 없고 방향지시등 값은 비상등을 켜도 OFF다(10/27 시험)
 * 급제동만으로는 묻지 않는다(급정거 뒤 그냥 달리는 일이 흔하다)
 * 충격도 읽는다(11/13, car/Jolt: 가속도 센서가 있는 차): 옆으로 1g 넘게 튄 충격은 급제동과 같은 자리에서 살핌을 시작한다.
 * - 달리다 받은 충격은 10초 안에 멈추면, 서 있다 받은 충격(신호 대기 중 뒤에서 받힘)은 그 자리에서 멈춘 것으로 본다
 * - 그 뒤는 같다: 비상등 또는 P, 그리고 운전석 문. 충격만으로는 묻지 않는다(턱·팬 구멍을 지난 뒤 그냥 달리는 일이 흔하다)
 */
class Bump(private val hazardReadable: () -> Boolean, private val onBump: (jolted: Boolean) -> Unit) {

    private val speeds = ArrayDeque<Pair<Long, Float>>()
    private var brakeAt = 0L
    private var stopAt = 0L
    private var hazardAt = 0L
    private var jolted = false
    private var lastSpeed = 0f
    private var lastHazard = false

    fun onCar(old: CarState, now: CarState) {
        val t = SystemClock.elapsedRealtime()
        lastSpeed = now.speedKmh; lastHazard = now.hazard
        speeds.addLast(t to now.speedKmh)
        while (speeds.size > 2 && t - speeds.first().first > 1_200) speeds.removeFirst()
        val ref = speeds.firstOrNull { t - it.first >= 900 }
        if (ref != null && ref.second - now.speedKmh > 15f && t - brakeAt > 5_000) {
            brakeAt = t
            jolted = false
            stopAt = 0L
            // 비상등을 이미 켜 둔 채였으면 급제동 때부터 센다
            hazardAt = if (now.hazard) t else 0L
            Prend.log("· 접촉 사고 살핌: 급제동(1초에 ${(ref.second - now.speedKmh).toInt()}km/h)")
        }
        if (brakeAt == 0L) return
        if (stopAt == 0L && now.speedKmh < 3f) {
            if (t - brakeAt <= (if (jolted) 10_000 else 5_000)) { stopAt = t; Prend.log("· 접촉 사고 살핌: ${if (jolted) "충격 뒤" else "급제동으로"} 멈춤") } else { reset(); return }
        }
        if (stopAt == 0L) return
        val readable = hazardReadable()
        if (readable && !old.hazard && now.hazard && hazardAt == 0L) { hazardAt = t; Prend.log("· 접촉 사고 살핌: 멈춘 뒤 비상등") }
        // 기다리는 시간: 비상등을 읽는 차는 멈춘 뒤 2분 안에 비상등, 켜진 뒤 1분 안에 문. 아니면 멈춘 뒤 1분 안에 문
        val expired = if (readable) (hazardAt == 0L && t - stopAt > 2 * 60_000) || (hazardAt > 0 && t - hazardAt > 60_000) else t - stopAt > 60_000
        if (expired) { reset(); return }
        val driverOpened = (old.doors["ROW_1_LEFT"] ?: 0) == 0 && (now.doors["ROW_1_LEFT"] ?: 0) > 0
        val ready = if (readable) hazardAt > 0 && now.hazard else now.parked
        if (driverOpened && ready) {
            Prend.log("◆ 접촉 사고일 수 있음: ${if (jolted) "충격" else "급제동으로 멈춤"} → ${if (readable) "비상등 → " else "P → "}${(t - stopAt) / 1000}초 만에 운전석 문")
            val j = jolted
            reset()
            onBump(j)
        }
    }

    /** 충격을 받았다(car/Jolt). 급제동 뒤에 받은 충격이면 충격 쪽으로 바꿔 다시 센다 */
    fun onJolt(g: Float) {
        val t = SystemClock.elapsedRealtime()
        brakeAt = t
        jolted = true
        val still = lastSpeed < 3f
        stopAt = if (still) t else 0L
        hazardAt = if (lastHazard) t else 0L
        Prend.log("· 접촉 사고 살핌: 충격(${"%.1f".format(g)}g)${if (still) " · 서 있던 중" else ""}")
    }

    private fun reset() { brakeAt = 0L; stopAt = 0L; hazardAt = 0L; jolted = false }

    /** 급제동으로 멈춘 뒤 운전석 문을 기다리는 중인가(고속도로 정차 카드가 이 동안만 비켜 선다, 11/05) */
    // 차 값이 바뀔 때만 onCar가 불려 만료가 늦게 반영될 수 있어 시간으로도 본다(비상등을 읽는 차의 2분이 가장 길다)
    val armed get() = stopAt > 0 && SystemClock.elapsedRealtime() - stopAt <= 2 * 60_000
}
