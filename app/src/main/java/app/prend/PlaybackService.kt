package app.prend

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import app.prend.car.CarHub
import app.prend.car.CarLink
import app.prend.music.Library
import app.prend.sound.BreathEngine
import app.prend.sound.SoundDirector
import app.prend.ui.Stage
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** 같은 프로세스의 화면(시연 패널)이 음악 서비스의 부품을 보게 하는 연결 고리 */
object Bridge {
    var director: SoundDirector? = null
    var engine: BreathEngine? = null
    val hub: CarHub? get() = CarLink.hubOrNull
    var brain: app.prend.ride.RideBrain? = null
    /** 잘래요로 음악을 잔잔하게 두는 중(ride/Nap이 넣는다). 음악 서비스가 나중에 떠도 이어받는다 */
    var sleep = false
    /** 말로 켜고 끈 빗소리(true 깖, false 뺌, null 와이퍼대로. 운행 서비스가 넣고 새 운행에 비운다). 음악 서비스가 나중에 떠도 이어받는다 */
    var rain: Boolean? = null
    /** 멀미("애가 멀미해")로 PREND 곡을 한 단 낮춘 운행(ride/Care가 넣고 새 운행에 비운다). 음악 서비스가 나중에 떠도 이어받는다 */
    var motion = false
    /**
     * PREND가 다른 앱 음악을 멈추려고 스스로 포커스를 잡은 때(elapsedRealtime, ride/Hush 후진·드라이브스루).
     * 그 바로 뒤 한마디가 잃는 포커스는 통화가 가져간 것이 아니라서 끊지 않는다(voice/Speaker)
     */
    var ownFocusAt = 0L
}

/**
 * PREND의 음악 서비스. 차의 음악 앱(미디어 브라우저 서비스)으로 등록되고(시험 4), 곡은 PREND가 직접 튼다.
 * 재생 직전의 소리는 숨 쉬는 음악 엔진(BreathEngine)이 다듬고, 차의 신호(CarHub)는 SoundDirector가 엔진의 목표로 옮긴다.
 * 화면이 없어도 돈다.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private var detachCar: (() -> Unit)? = null
    private var director: SoundDirector? = null
    private var brain: app.prend.ride.RideBrain? = null
    private var speaker: app.prend.voice.Speaker? = null
    private val scope = MainScope()

    /**
     * 잘래요 동안 곡 정보 부제에 '옆자리 손님 쉬는 중'·'뒷자리 손님 쉬는 중'(별첨 3 §4: 부탁이 아니라 상태로, 작게, 소리 없이).
     * 차의 미디어 화면이 읽는 지금 곡의 정보만 바꾼다(같은 곡이라 재생은 끊기지 않는다). 끝나면 부제를 지운다
     */
    private fun subtitle(player: Player) {
        val item = player.currentMediaItem ?: return
        val want = Stage.ui.value.sleeper?.let { "$it 손님 쉬는 중" }
        if (item.mediaMetadata.subtitle?.toString() == want) return
        player.replaceMediaItem(player.currentMediaItemIndex, item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setSubtitle(want).build()).build())
        Prend.log("· 곡 부제: ${want ?: "지움"}")
    }

    /** 흐르기 시작한 PREND 곡을 지금 곡으로 적는다("이 노래 뭐야?", 메모리에만) */
    private fun heard(player: Player) {
        val t = Library.byId(player.currentMediaItem?.mediaId) ?: return
        app.prend.music.NowPlaying.playing(app.prend.music.Heard(t.title, t.artist, app = null, prend = true))
    }

    private val debug = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val cmd = intent.getStringExtra("cmd") ?: return
            Prend.log("· 서비스 시험 명령: $cmd")
            if (cmd.startsWith("speakover_")) {
                val name = cmd.removePrefix("speakover_")
                val usage = app.prend.voice.Speaker.USAGES[name] ?: return Prend.log("✗ 모르는 용도 $name")
                speaker?.speak("voice/rest_2h.wav", usage, name)
            }
            // 시험 49-2: 음악 위에 말하고, 끝나면 5초 동안 다른 음악을 멈췄다가 돌려준다
            if (cmd.startsWith("speakask_")) {
                val name = cmd.removePrefix("speakask_")
                val usage = app.prend.voice.Speaker.USAGES[name] ?: return Prend.log("✗ 모르는 용도 $name")
                speaker?.speak("voice/rest_2h.wav", usage, name, answerMs = 5000L)
            }
        }
    }

    private fun item(t: app.prend.music.Track): MediaItem =
        MediaItem.Builder()
            .setMediaId(t.id)
            .setUri(Uri.parse("android.resource://$packageName/${t.raw}"))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(t.title)
                    .setArtist(t.artist)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            ).build()

    private val items by lazy { Library.tracks.map { item(it) } }

    private val root = MediaItem.Builder()
        .setMediaId("root")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(Prend.NAME)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build()
        ).build()

    override fun onCreate() {
        super.onCreate()
        Prend.init(this)
        Prend.log("◆ 음악 서비스 시작")

        // 소리 엔진 상태는 1초마다 온다. 출시 빌드는 30초에 한 줄만 남긴다(1초마다 쓰면 한 시간 남짓에 기록 상한을 채워 앞 기록이 밀렸다, 11/04 점검)
        var meters = 0
        val engine = BreathEngine(this) { if (BuildConfig.DEBUG || !it.startsWith("차단 ") || meters++ % 30 == 0) Prend.log("· $it") }
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf<AudioProcessor>(engine))
                    .build()
        }
        val player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .build()

        val d = SoundDirector(this, engine, player)
        director = d
        val b = app.prend.ride.RideBrain(this, d, player) { items }
        brain = b
        d.onTalkingChanged = { b.onTalking(it) }
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Prend.log("◆ 재생 중=$isPlaying (${player.currentMediaItem?.mediaId})")
                d.onPlaying(isPlaying)
                b.onPlaying(isPlaying)
                if (isPlaying) heard(player)
            }

            override fun onPlayerError(error: PlaybackException) = Prend.log("✗ 재생 오류: ${error.errorCodeName}")

            // 곡이 바뀌거나 목록이 처음 들어오면 잘래요 부제를 다시 단다
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { subtitle(player); if (player.isPlaying) heard(player) }
        })
        // 잘래요가 켜지고 꺼질 때 곡 부제를 바꾼다(운행 서비스가 Stage에 쉬는 자리를 넣는다)
        scope.launch { Stage.ui.map { it.sleeper }.distinctUntilChanged().collect { subtitle(player) } }
        // 대리 운행이면 곡을 6dB 낮게 깔고 빗소리 결·풍성하게를 끈다(별첨 4 §1 'D · 대리 운행'). 켜고 끄는 대로 따라간다
        scope.launch { Stage.ui.map { it.daeri }.distinctUntilChanged().collect { d.daeri = it } }

        // 차 신호는 운행 서비스와 같은 연결을 나눠 듣는다
        detachCar = CarLink.attach(this) { old, now -> d.onCar(old, now); b.onCar(old, now) }
        // 붙을 때 지금 차 상태로 한 번 맞춘다(11/08 오류 점검 D4: 세워 둔 채 새로 뜨면 다음 차 신호까지 빈 값이라 동승자가 있어도 '혼자'로 보고
        // 대화 비켜 서기·빗소리를 하지 않았다). 앞뒤가 같은 값이라 문 열림·출발로 보지 않는다
        CarLink.state.let { s -> d.onCar(s, s); b.onCar(s, s) }
        d.start()
        b.start()
        // 운행 서비스가 먼저 정해 둔 사람 상태를 이어받는다(부팅 때 켠 아이 탑승은 이 서비스가 없어 전해지지 못했다): 아이 −3dB, 잘래요 잔잔하게, 이야기 중
        Stage.ui.value.let { s -> if (s.baby) d.baby = true; if (s.talking) d.setTalking(true) }
        if (Bridge.sleep) d.sleep = true
        if (Bridge.motion) d.motion = true
        Bridge.rain?.let { d.manualRain = it; d.rainOff = !it }
        Bridge.director = d; Bridge.engine = engine; Bridge.brain = b

        // 시험 49: 화면을 앞으로 부르지 않고(다른 앱이 앞에 있는 채로) 서비스에서 한마디를 튼다
        // adb shell am broadcast --user 10 -a app.prend.DEBUG --es cmd speakover_nav
        // 출시 빌드에서는 받지 않는다(다른 앱이 PREND에 말을 시키지 못하게)
        speaker = app.prend.voice.Speaker(this)
        if (BuildConfig.DEMO) registerReceiver(debug, IntentFilter("app.prend.DEBUG"), Context.RECEIVER_EXPORTED)

        session = MediaLibrarySession.Builder(this, player, object : MediaLibrarySession.Callback {
            override fun onGetLibraryRoot(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                params: LibraryParams?,
            ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(root, params))

            override fun onGetChildren(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                parentId: String,
                page: Int,
                pageSize: Int,
                params: LibraryParams?,
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
                Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))

            override fun onGetItem(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                mediaId: String,
            ): ListenableFuture<LibraryResult<MediaItem>> =
                Futures.immediateFuture(LibraryResult.ofItem(items.firstOrNull { it.mediaId == mediaId } ?: root, null))

            /** 이름이 맞으면 그 곡, 검색어만 오면 첫 곡 */
            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>,
            ): ListenableFuture<MutableList<MediaItem>> =
                Futures.immediateFuture(mediaItems.map { want -> items.firstOrNull { it.mediaId == want.mediaId } ?: items.first() }.toMutableList())
        }).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        Prend.log("◆ 음악 서비스 끝")
        if (BuildConfig.DEMO) runCatching { unregisterReceiver(debug) }
        speaker?.stop()
        Bridge.director = null; Bridge.engine = null; Bridge.brain = null
        scope.cancel()
        brain?.stop()
        director?.stop()
        detachCar?.invoke()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
