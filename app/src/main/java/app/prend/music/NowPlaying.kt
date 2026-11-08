package app.prend.music

/** 흐른 곡 하나. app은 다른 음악 앱의 이름(PREND 곡이면 null), prend는 PREND가 만든 곡인가 */
data class Heard(val title: String, val artist: String, val app: String?, val prend: Boolean)

/**
 * 지금 곡과 바로 전 곡("이 노래 뭐야?"·"아까 그 노래", 감사 F89). PREND 곡은 음악 서비스가, 다른 앱 곡은 OtherMusic이 넣는다.
 * 메모리에만 두고 앱 저장소·기록에는 남기지 않는다(곡 제목은 화면에만)
 */
object NowPlaying {
    var now: Heard? = null
        private set
    var before: Heard? = null
        private set

    /** 곡이 흐르기 시작했다. 같은 곡이면 그대로 둔다(멈췄다 다시 틀어도 바로 전 곡이 지워지지 않게) */
    fun playing(h: Heard) {
        if (h == now) return
        now?.let { before = it }
        now = h
    }
}
