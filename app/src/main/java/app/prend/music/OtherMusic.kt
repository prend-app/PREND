package app.prend.music

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import app.prend.Prend
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 안드로이드는 다른 앱의 재생 정보를 '알림 액세스'를 켠 앱에만 준다. 이 서비스는 그 열쇠로만 쓰고,
 * 알림의 내용은 읽지 않는다(onNotificationPosted를 받지 않는다). 사용자가 설정 > 앱 > 특별한 앱 액세스 > 알림 액세스에서 PREND를 켜면 붙는다.
 */
class MusicWatch : NotificationListenerService() {
    override fun onListenerConnected() = OtherMusic.start(this)
    override fun onListenerDisconnected() = OtherMusic.stop()
}

/** 다른 음악 앱이 지금 트는 곡. app은 앱 이름, art는 그 앱이 준 표지 */
data class OtherSong(val app: String, val title: String, val artist: String, val playing: Boolean, val durMs: Long, val art: android.graphics.Bitmap?)

/**
 * 다른 음악 앱(멜론·유튜브 뮤직 등)이 지금 트는 곡을 읽고, 재생·멈춤·앞뒤 곡을 대신 누른다(MediaSession).
 * 알림 액세스를 켜지 않았으면 아무것도 하지 않고, PREND 화면은 PREND 사운드만 보여 준다.
 * 곡 제목은 화면에만 쓰고 기록에는 앱 이름만 남긴다.
 */
object OtherMusic {

    val now = MutableStateFlow<OtherSong?>(null)

    private val main = Handler(Looper.getMainLooper())
    private var msm: MediaSessionManager? = null
    private var self: String? = null
    private var packages: android.content.pm.PackageManager? = null
    private var watched: List<MediaController> = emptyList()
    private var current: MediaController? = null

    private val changed = MediaSessionManager.OnActiveSessionsChangedListener { list -> watch(list.orEmpty()) }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish(fresh = true)
        override fun onSessionDestroyed() = publish()
    }

    fun start(context: Context) {
        val c = context.applicationContext
        self = c.packageName
        packages = c.packageManager
        val me = ComponentName(c, MusicWatch::class.java)
        runCatching {
            val m = c.getSystemService(MediaSessionManager::class.java)
            msm = m
            m.addOnActiveSessionsChangedListener(changed, me, main)
            watch(m.getActiveSessions(me))
            Prend.log("◆ 다른 음악 앱 읽기 켜짐(알림 액세스)")
        }.onFailure { Prend.log("✗ 다른 음악 앱 읽기 실패 $it") }
    }

    fun stop() {
        runCatching { msm?.removeOnActiveSessionsChangedListener(changed) }
        watched.forEach { it.unregisterCallback(callback) }
        watched = emptyList(); current = null
        now.value = null
        Prend.log("· 다른 음악 앱 읽기 꺼짐")
    }

    private fun watch(list: List<MediaController>) {
        watched.forEach { it.unregisterCallback(callback) }
        watched = list.filter { it.packageName != self }
        watched.forEach { it.registerCallback(callback, main) }
        Prend.log("· 다른 음악 앱 ${watched.size}개: ${watched.joinToString { it.packageName }}")
        publish()
    }

    /** fresh면 표지를 새로 받는다(메타데이터가 바뀐 때. 같은 곡이어도 앱이 나중에 진짜 표지를 보낼 수 있다) */
    private fun publish(fresh: Boolean = false) {
        // 재생 중인 곳이 있으면 그곳, 없으면 가장 최근에 쓴 곳(목록의 맨 앞)
        val c = watched.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: watched.firstOrNull()
        current = c
        val md = c?.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val prev = now.value
        val app = c?.let { label(it.packageName) }
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        // 같은 앱·같은 곡이면 지난 표지를 그대로 쓴다: 알림마다 새로 온 Bitmap은 내용이 같아도 다른 값이라 화면이 매번 표지를 다시 올렸다(11/04 점검)
        val same = !fresh && prev != null && prev.app == app && prev.title == title && prev.artist == artist
        now.value = if (c == null || md == null || title.isNullOrBlank() || app == null) null else OtherSong(
            app = app,
            title = title,
            artist = artist,
            playing = c.playbackState?.state == PlaybackState.STATE_PLAYING,
            durMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION),
            art = if (same && prev!!.art != null) prev.art else md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART),
        )
        // 흐르는 곡은 "이 노래 뭐야?"에 답하려고 지금 곡으로 적는다(메모리에만)
        now.value?.takeIf { it.playing }?.let { NowPlaying.playing(Heard(it.title, it.artist, it.app, prend = false)) }
    }

    /** 지금 재생 위치. 재생 중이면 마지막 알림 뒤로 흐른 시간을 더한다 */
    fun positionMs(): Long {
        val s = current?.playbackState ?: return 0L
        if (s.state != PlaybackState.STATE_PLAYING) return s.position
        return s.position + ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
    }

    fun playPause() {
        val c = current ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() { current?.transportControls?.skipToNext() }
    fun prev() { current?.transportControls?.skipToPrevious() }

    /** 앱 이름(패키지마다 한 번만 묻는다) */
    private val labels = HashMap<String, String>()
    private fun label(pkg: String): String = labels[pkg] ?: runCatching {
        val pm = packages ?: return pkg
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().also { labels[pkg] = it }
    }.getOrDefault(pkg)
}
