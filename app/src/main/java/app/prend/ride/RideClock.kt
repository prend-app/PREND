package app.prend.ride

import android.car.VehicleGear
import android.content.Context
import app.prend.Prend

/**
 * 연속 운전 시간(쉬지 않고 달린 시간). 기어가 P가 아니면 세고, P로 15분 넘게 서 있으면 쉰 것으로 보고 0으로 돌린다
 * (사업용 운전자의 '2시간 운전 뒤 15분 휴식'과 같은 눈금). 한 시간에 묻고 두 시간에 쉼터를 권하는 기준이 된다.
 * 앱이 꺼졌다 켜져도 이어 세도록 파일에 둔다. 꺼져 있던 동안은 세워 둔 것으로 본다.
 * onRested는 운행 중 P로 15분 쉬어 0으로 돌릴 때 쉬기 전 분을 받는다(소리 없이 0이 되면 쉰 것이 보이지 않아서. 시동을 꺼 둔 동안 쉰 것은 부르지 않는다)
 */
class RideClock(context: Context, private val onRested: (Int) -> Unit = {}) {

    private val prefs = context.getSharedPreferences("ride_clock", Context.MODE_PRIVATE)
    private var lastWall = System.currentTimeMillis()
    private var lastSave = 0L
    private var parked = true

    var driveMs = prefs.getLong("driveMs", 0L)
        private set
    private var parkedMs = prefs.getLong("parkedMs", 0L)

    /** 시연 배속(1시간을 1분으로 = 60). PT에서 밝히고 쓴다 */
    var speed = 1.0

    val minutes: Int get() = (driveMs / 60_000).toInt()

    init {
        val saved = prefs.getLong("wall", 0L)
        if (saved > 0) parkedMs += (lastWall - saved).coerceAtLeast(0)
        if (parkedMs >= REST_MS && driveMs > 0) {
            Prend.log("◆ 운전 시간: 꺼져 있던 동안 쉬었다(${driveMs / 60_000}분 → 0)")
            driveMs = 0
        }
        Prend.log("· 운전 시간 이어 세기: ${driveMs / 60_000}분")
    }

    fun onGear(gear: Int) { parked = gear == VehicleGear.GEAR_PARK }

    /** 1초마다 부른다 */
    fun tick() {
        val now = System.currentTimeMillis()
        val gap = (now - lastWall).coerceAtLeast(0)
        lastWall = now
        // 틱 사이가 5초 넘게 비었으면 차가 잠들어 있던 것(시동을 껐다 켬): 그동안은 쉰 시간으로 센다
        if (gap > 5_000) {
            parkedMs += gap
            if (parkedMs >= REST_MS && driveMs > 0) {
                Prend.log("◆ 운전 시간: 꺼져 있던 동안 쉬었다(${driveMs / 60_000}분 → 0)")
                driveMs = 0
            }
            save()
            return
        }
        val dt = (gap * speed).toLong()
        if (parked) {
            parkedMs += dt
            if (parkedMs >= REST_MS && driveMs > 0) {
                val was = (driveMs / 60_000).toInt()
                Prend.log("◆ 운전 시간: ${REST_MS / 60_000}분 쉬었다(${was}분 → 0)")
                driveMs = 0
                onRested(was)
            }
        } else {
            driveMs += dt
            parkedMs = 0
        }
        if (now - lastSave >= 10_000) save()
    }

    /** 시험: 차가 ms만큼 잠들어 있던 것처럼(다음 틱이 그 공백을 쉰 시간으로 센다. 15분이 넘으면 연속 운전이 0) */
    fun pretendGap(ms: Long) { lastWall -= ms; Prend.log("· 시험: 운전 시간에 ${ms / 60_000}분 잠 공백") }

    /** 시연·시험: 연속 운전 시간을 정해 둔다. seconds는 PT 킥의 '1시간 59분 50초'처럼 두 시간 문턱 바로 앞에 세울 때 */
    fun set(minutes: Int, seconds: Int = 0) {
        driveMs = minutes * 60_000L + seconds * 1_000L; parkedMs = 0
        Prend.log("· 운전 시간 맞춤: ${minutes}분${if (seconds > 0) " ${seconds}초" else ""}")
        save()
    }

    fun save() {
        lastSave = System.currentTimeMillis()
        prefs.edit().putLong("driveMs", driveMs).putLong("parkedMs", parkedMs).putLong("wall", lastSave).apply()
    }

    companion object {
        const val REST_MS = 15 * 60_000L
    }
}
