package app.prend.music

import app.prend.Prend
import app.prend.R

/**
 * 시연 곡. 곡마다 템포·박자·첫 박 위치를 적어 둔다(박에 맞춰 피어나기, 마디 끝에서 내려앉기).
 * 곡은 직접 합성한 것만 쓴다(저작권 문제 없음). 곡 이야기(story)는 사실일 때만 적는다.
 */
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val raw: Int,
    val bpm: Double,
    val firstBeatMs: Long,
    val beatsPerBar: Int,
    val moods: Set<String>,
    val story: String? = null,
) {
    val beatMs get() = 60000.0 / bpm
    val barMs get() = beatMs * beatsPerBar
}

object Library {
    val tracks = listOf(
        Track("lofi_a", "비 오는 저녁", "${Prend.NAME} 시연곡", R.raw.lofi_a, 84.0, 0, 4, setOf("가볍게", "혼자")),
    )

    fun byId(id: String?): Track? = tracks.firstOrNull { it.id == id }
}
