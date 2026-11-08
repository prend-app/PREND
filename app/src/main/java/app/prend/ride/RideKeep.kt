package app.prend.ride

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject

/**
 * 한 운행의 상태를 프로세스 밖에 적어 둔다(감사 F02). Pleos는 창을 닫으면 프로세스를 끄고 1.6초 뒤 서비스만 다시 깨운다(03 문서).
 * 그때 아이 탑승·대리 운행·조용히 가기와 이번 운행에 물은 것·거절한 것이 조용히 풀리지 않게, 운행 id와 함께 적어 두고
 * 같은 운행이면 되살린다(ride/Care.restore). 새 운행(시동·잠에서 깸)이면 id를 새로 매기고 지난 것은 버린다.
 * 운행 id는 그 운행을 시작한 때(currentTimeMillis)다. 적는 것은 예/아니요·시각·분·물음 이름(JAM 등)뿐이고, 위치와 들은 말은 적지 않는다
 */
class RideKeep(context: Context) {

    private val prefs = context.getSharedPreferences("ride_keep", Context.MODE_PRIVATE)
    /** 지난번에 적은 글(같으면 다시 쓰지 않는다. 1초마다 부르므로) */
    private var last: String? = null

    /** 지금 운행의 id. 아직 없으면 0 */
    val rideId get() = prefs.getLong("ride_id", 0L)

    /** 새 운행: id를 새로 매기고 지난 운행의 상태를 버린다 */
    fun newRide() {
        last = null
        prefs.edit().putLong("ride_id", System.currentTimeMillis()).remove("state").apply()
    }

    /** 운행 id가 없으면(처음 깔고 시동 판단 없이 깨어남) 지금을 운행의 시작으로 본다 */
    fun ensure() { if (rideId == 0L) newRide() }

    /** 지금 상태를 적는다. 지난번과 같으면 쓰지 않는다 */
    fun save(state: JSONObject) {
        val s = state.put("ride", rideId).toString()
        if (s == last) return
        last = s
        prefs.edit().putString("state", s).apply()
    }

    /** 이 운행에 적어 둔 상태. 없거나 다른 운행의 것이면 null */
    fun load(): JSONObject? {
        val id = rideId
        val j = runCatching { JSONObject(prefs.getString("state", null) ?: return null) }.getOrNull() ?: return null
        return j.takeIf { id != 0L && it.optLong("ride") == id }
    }

    companion object {
        /**
         * elapsedRealtime의 때를 벽시계로(0은 0). 같은 부팅 안에서 프로세스가 바뀌어도 이어지게 벽시계로 적는다.
         * 초 단위로 자른다: 두 시계를 읽는 틈 때문에 같은 때가 부를 때마다 1ms씩 오락가락해, 그대로 두면 거의 1초마다 '바뀜'으로 보고 다시 썼다
         */
        fun wall(elapsed: Long): Long =
            if (elapsed <= 0L) 0L else (System.currentTimeMillis() - (SystemClock.elapsedRealtime() - elapsed)) / 1000 * 1000

        /** 벽시계의 때를 elapsedRealtime으로(0은 0, 부팅 전이면 1: '있었음'만 남긴다) */
        fun elapsed(wall: Long): Long = if (wall <= 0L) 0L else (SystemClock.elapsedRealtime() - (System.currentTimeMillis() - wall).coerceAtLeast(0)).coerceAtLeast(1)
    }
}
