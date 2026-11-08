package app.prend.car

import android.content.Context

/**
 * 차 신호 연결을 프로세스에 하나만 둔다. 음악 서비스(소리)와 운행 서비스(챙김)가 같은 CarHub를 나눠 듣는다.
 * 마지막 듣는 쪽이 떠나면 연결을 닫는다. 모두 메인 스레드에서 부른다.
 */
object CarLink {
    private var hub: CarHub? = null
    private val listeners = mutableListOf<(CarState, CarState) -> Unit>()

    val state: CarState get() = hub?.state ?: CarState()
    val hubOrNull: CarHub? get() = hub

    /**
     * PREND가 무드등·바람을 바꾼 뒤 이만큼은 다르게 읽혀도 사람이 바꾼 것으로 보지 않는다(감사 F109).
     * 에뮬레이터는 앱이 바꾼 값을 25초쯤 늦게 반영해, 그동안 옛 값이 읽힌다
     */
    const val HANDS_GRACE_MS = 40_000L

    /** 듣기 시작. 돌려받은 함수를 부르면 그만 듣는다 */
    fun attach(context: Context, listener: (old: CarState, now: CarState) -> Unit): () -> Unit {
        listeners += listener
        if (hub == null) {
            // 닫은 옛 연결의 늦은 신호(Pleos 리스너는 떼지 못한다)가 새 듣는 쪽으로 흘러가지 않게, 지금 연결의 신호만 넘긴다
            lateinit var h: CarHub
            h = CarHub(context.applicationContext) { old, now -> if (hub === h) listeners.toList().forEach { it(old, now) } }
            hub = h
            h.start()
        }
        return {
            listeners -= listener
            if (listeners.isEmpty()) { hub?.stop(); hub = null }
        }
    }
}
