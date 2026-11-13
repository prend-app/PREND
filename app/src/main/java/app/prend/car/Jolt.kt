package app.prend.car

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.prend.Prend
import kotlin.math.sqrt

/**
 * 차가 받은 충격을 읽는다(11/13 사용자: "세게 멈추는 건 그냥 급정거인 경우가 많다. 접촉 사고 난 충격을 읽을 수 있다").
 * 기기의 가속도 센서(안드로이드 표준 SensorManager)를 쓴다. Pleos Connect 에뮬레이터(v2.1.0)에는
 * 보정 전 가속도 센서(accelerometer_uncalibrated)가 있다(11/13 dumpsys sensorservice). 센서가 없는 차에서는
 * 아무 일도 하지 않고, 접촉 사고 살핌(ride/Bump)은 예전처럼 급제동으로만 본다.
 *
 * 무엇을 충격으로 보나: 옆(수평)으로 0.1초 안에 1g 넘게 튄 것.
 * - 바탕(0.5초 평균, 중력과 천천히 바뀌는 가감속)을 뺀 나머지만 본다. 브레이크를 끝까지 밟아도 1g 안팎이
 *   0.3초쯤에 걸쳐 올라오므로 바탕을 빼면 절반쯤이다. 범퍼끼리 닿는 사고는 0.1초 안에 그보다 크게 튄다
 * - 중력 방향(위아래) 성분은 뺀다. 과속 방지턱·팬 구멍은 위아래로 튄다. 기기가 기울어 달려 있어도 되게 바탕에서 중력 방향을 잡는다
 * - 실제 차로 맞춘 값이 아니다. 그래서 충격만으로는 묻지 않는다: 충격 → 멈춤 → P → 운전석 문이 이어질 때만 묻는다(Bump)
 * 주차(P) 중에는 읽지 않는다(문 닫는 흔들림·짐 싣기). 값은 기록하지 않고, 충격으로 본 순간의 크기(g)만 한 줄 남긴다.
 */
class Jolt(ctx: Context, private val onJolt: (Float) -> Unit) : SensorEventListener {

    private val sm = ctx.getSystemService(SensorManager::class.java)
    // 가속도 → 보정 전 가속도 → 축이 모자란 가속도(자동차용, API 33의 38·40) 차례로 찾는다
    private val sensor: Sensor? = sm?.let { m -> listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_ACCELEROMETER_UNCALIBRATED, 38, 40).firstNotNullOfOrNull { runCatching { m.getDefaultSensor(it) }.getOrNull() } }
    private val main = Handler(Looper.getMainLooper())

    /** 이 차에서 충격을 읽을 수 있나 */
    val readable get() = sensor != null

    private var on = false
    private val base = FloatArray(3)
    private var lastNs = 0L
    private var warmNs = 0L
    private var joltAt = 0L

    /** 달리는 동안(P가 아닐 때)만 읽는다 */
    fun watch(yes: Boolean) {
        val s = sensor ?: return
        if (yes == on) return
        on = yes
        if (yes) {
            lastNs = 0L
            val ok = runCatching { sm?.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME, main) == true }.getOrDefault(false)
            if (!ok) on = false
            Prend.log("· 충격 읽기 ${if (ok) "켬" else "못 켬"}")
        } else {
            runCatching { sm?.unregisterListener(this) }
            Prend.log("· 충격 읽기 끔")
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.values.size < 3) return
        val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
        if (lastNs == 0L) { base[0] = x; base[1] = y; base[2] = z; lastNs = e.timestamp; warmNs = e.timestamp; return }
        val dt = ((e.timestamp - lastNs) / 1e9f).coerceIn(0f, 0.2f)
        lastNs = e.timestamp
        val rx = x - base[0]; val ry = y - base[1]; val rz = z - base[2]
        // 바탕이 중력을 담고 있으면(가만히 있을 때 약 9.8) 그 방향 성분을 뺀다
        val b2 = base[0] * base[0] + base[1] * base[1] + base[2] * base[2]
        val d = if (b2 > 25f) (rx * base[0] + ry * base[1] + rz * base[2]) / b2 else 0f
        val hx = rx - d * base[0]; val hy = ry - d * base[1]; val hz = rz - d * base[2]
        val h = sqrt(hx * hx + hy * hy + hz * hz)
        val k = (dt / TAU).coerceAtMost(1f)
        base[0] += rx * k; base[1] += ry * k; base[2] += rz * k
        // 켠 뒤 1초는 바탕을 잡는다
        if (e.timestamp - warmNs < 1_000_000_000L) return
        val t = SystemClock.elapsedRealtime()
        if (h >= LIMIT && t - joltAt > 3_000) { joltAt = t; onJolt(h / G) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private companion object {
        const val G = 9.81f
        /** 충격으로 보는 크기(수평, 바탕을 뺀 값): 1g */
        const val LIMIT = 9.81f
        /** 바탕을 잡는 시간 */
        const val TAU = 0.5f
    }
}
