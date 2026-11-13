package app.prend.ui

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.fill.ShieldCheck
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.prend.Bridge
import app.prend.PlaybackService
import app.prend.RideService
import app.prend.car.CarLink
import app.prend.Prend
import app.prend.music.Library
import app.prend.music.OtherMusic
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.flow.update

/**
 * PREND의 화면(시안 3-B). 바탕 층(곡 색 번짐, 곡, 큰 글)은 유리가 비출 원본이 되고,
 * 앞 층의 판은 젖빛 유리, 위 막대의 캡슐은 굴절 유리로 그린다.
 * 화면 아무 데나 1.2초 꾹 누르면 [프렌드]를 부른다. 시연 패널은 이름 'P R E N D'을 다섯 번 누르면 열린다.
 * 촬영·시연: am start --user 10 -n app.prend/.ui.PrendActivity --es scene drive [--ei step 2] [--es heard 졸려]
 */
class PrendActivity : ComponentActivity() {

    private var controller: MediaController? = null
    private val main = Handler(Looper.getMainLooper())

    /**
     * 차속과 알림은 처음에 허락받는다(차속: 급제동 뒤 멈춤으로 묻는 혹시 사고·시속 100km 넘으면 말 삼키기 등, 알림: 화면을 닫아 둔 운행의 물음과 급할 때 걸음).
     * 허락 창 앞에 까닭 판 한 장을 둔다(검토 11/06: 설명 없이 창이 떠 거절하면 혹시 사고가 조용히 꺼졌다). 판은 두 번까지 띄우고, 그 뒤 거절돼 있으면
     * 세워 둔 첫 화면에 한 줄과 [설정 열기]를 둔다(Stage.speedOff·notifOff).
     * 판은 세워 둔 첫 화면에 물음 카드가 없을 때만 그린다(급할 때·달리는 중에는 숨겼다가 다음 첫 화면·P에서, 검토 11/06).
     * 비상등(CAR_EXTERIOR_LIGHTS)은 signature|privileged라 묻지 않는다. 매니페스트에만 적어 두어 차가 열어 주면 읽는다
     * 허락되면 바로 다시 구독한다
     */
    private val carPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        asking = false
        Prend.log("· 허락: ${r.entries.joinToString { "${it.key.substringAfterLast('.')} ${if (it.value) "허락" else "거절"}" }}")
        if (r[SPEED] == true) CarLink.hubOrNull?.resubscribe()
        syncPerm()
        syncPanel()
        // 첫 화면 한 줄의 [설정 열기]로 띄운 창이 곧바로 거절로 돌아왔다: 안드로이드가 창 없이 거절한 것이라 설정 화면을 연다
        val line = lineAsk
        lineAsk = null
        if (line != null && r[line.first] == false && android.os.SystemClock.elapsedRealtime() - line.second < QUICK_DENY_MS) {
            Prend.log("· 허락 창이 뜨지 않고 바로 거절됨 → 설정 화면")
            openAppSettings(line.first)
        }
        // 차속 허락 창이 떠 있는 동안 미뤄 둔 마이크 허락을 이제 묻는다
        if (micWaiting) { micWaiting = false; askMic() }
    }

    /** [설정 열기]로 띄운 허락 창: 그 허락과 띄운 때(창 없이 곧바로 거절이 돌아오면 설정 화면으로) */
    private var lineAsk: Pair<String, Long>? = null

    /** 안드로이드 허락 창을 띄운다. 띄운 적이 있음을 허락마다 적어 둔다(예·아니오, [설정 열기]가 창부터 띄울지 가른다) */
    private fun launchPerms(perms: Array<String>): Boolean {
        if (asking) return false
        asking = true
        val ok = runCatching { carPermissions.launch(perms) }.onFailure { Prend.log("✗ 허락 묻기 실패 $it") }.isSuccess
        if (ok) permPrefs.edit().apply { perms.forEach { putBoolean(shownKey(it), true) } }.apply() else asking = false
        syncPanel()
        return ok
    }

    private fun shownKey(perm: String) = "shown_${perm.substringAfterLast('.')}"

    /**
     * 까닭 판을 띄울 차례이거나 허락 창이 떠 있음을 Stage에 알린다(Stage.permPanel). 그동안 위치 알림 카드가 기다린다:
     * 판이나 창 뒤에 깔려 읽지도 누르지도 못한 채 '건넸다'로 세지 않게(검토 11/06)
     */
    private fun syncPanel() {
        val on = permNeed.value.isNotEmpty() || asking
        if (Stage.ui.value.permPanel != on) Stage.ui.update { it.copy(permPanel = on) }
    }

    /** 허락 창 앞의 까닭 판에 적을 허락(비어 있으면 판을 띄우지 않는다) */
    private val permNeed = mutableStateOf<List<String>>(emptyList())
    private val permPrefs get() = getSharedPreferences("perm", MODE_PRIVATE)

    /** 아직 허락받지 못한 것(차 속도, 안드로이드 13부터는 알림) */
    private fun missing(): List<String> = listOfNotNull(
        SPEED.takeIf { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED },
        NOTIF.takeIf { android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED },
    )

    /** 첫 화면의 한 줄(허락이 없을 때)을 맞춘다. 설정에서 차 속도를 허락하고 돌아왔으면 다시 구독한다 */
    private fun syncPerm() {
        val m = missing()
        val speedOff = SPEED in m
        val notifOff = NOTIF in m
        val was = Stage.ui.value.speedOff
        if (Stage.ui.value.speedOff != speedOff || Stage.ui.value.notifOff != notifOff) {
            Stage.ui.value = Stage.ui.value.copy(speedOff = speedOff, notifOff = notifOff)
            Prend.log("· 허락 상태: 차 속도 ${if (speedOff) "없음" else "있음"} · 알림 ${if (notifOff) "없음" else "있음"}")
        }
        if (was && !speedOff) CarLink.hubOrNull?.resubscribe()
    }

    /** 까닭 판의 [허락하기]: 안드로이드 허락 창을 띄운다(판을 띄운 횟수를 센다) */
    private fun askPerms() {
        val m = missing()
        permNeed.value = emptyList()
        permPrefs.edit().putInt("asks", permPrefs.getInt("asks", 0) + 1).apply()
        if (m.isNotEmpty()) launchPerms(m.toTypedArray())
        syncPanel()
    }

    /** 까닭 판의 [나중에]: 창은 띄우지 않고 첫 화면의 한 줄로 남긴다(판은 다음에 화면을 열 때 한 번 더) */
    private fun permLater() {
        permNeed.value = emptyList()
        permPrefs.edit().putInt("asks", permPrefs.getInt("asks", 0) + 1).apply()
        Prend.log("· 허락 까닭 판: 나중에")
        syncPanel()
    }

    /**
     * 첫 화면 한 줄의 [설정 열기]. 그 허락 창을 한 번도 띄운 적이 없거나(까닭 판에서 [나중에]만 누름) 안드로이드가 창을 한 번 더 띄울 수 있으면(한 번 거절) 창부터,
     * 띄운 적이 있는데 더 띄울 수 없으면(두 번 거절) 이 앱의 설정 화면(알림은 알림 설정)을 연다(검토 11/06: 한 번도 묻지 않은 허락도 설정 화면으로 보냈다)
     */
    private fun openPermSettings(what: String) {
        val perm = if (what == "notif") NOTIF else SPEED
        val shown = permPrefs.getBoolean(shownKey(perm), false)
        if (!shown || shouldShowRequestPermissionRationale(perm)) {
            if (launchPerms(arrayOf(perm))) { lineAsk = perm to android.os.SystemClock.elapsedRealtime(); return }
            if (asking) return
        }
        openAppSettings(perm)
    }

    /** 이 앱의 설정 화면(알림은 알림 설정)을 연다. 열지 못하면 앱 목록에서 고르라고 알린다 */
    private fun openAppSettings(perm: String) {
        val notifPerm = perm == NOTIF
        val app = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null))
        val notif = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
        val opened = listOfNotNull(notif.takeIf { notifPerm }, app).any { runCatching { startActivity(it) }.isSuccess }
        Prend.log(if (opened) "· 허락 설정 화면 엶(${if (notifPerm) "알림" else "차 속도"})" else "✗ 허락 설정 화면을 열지 못함")
        if (!opened) Stage.toast("차 설정의 앱 목록에서 ${Prend.NAME}를 골라 허락해 주세요", 5_000)
    }

    /** 허락 창은 한 번에 하나만 뜬다(겹치면 둘째가 바로 거절로 돌아온다) */
    private var asking = false
    private var micWaiting = false

    private fun askMic() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return
        if (asking) { micWaiting = true; return }
        runCatching { micPermission.launch(android.Manifest.permission.RECORD_AUDIO) }.onFailure { Prend.log("✗ 마이크 허락 묻기 실패 $it") }
    }

    /** 이야기에 비켜 서기를 켰을 때만 묻는 마이크 허락(말소리 여부만 판정한다) */
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        Prend.log("· 마이크 권한: ${if (ok) "허락" else "거절"}")
        if (!ok) Stage.toast("마이크를 허락하지 않아 대화 중엔 소리 낮추기는 쉬어요", 4_000)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prend.init(this)
        Stage.needMic = ::askMic
        // 운행 서비스를 깨운다(부팅 때 이미 깨어 있으면 그대로). 차의 값은 거기서 화면으로 온다
        runCatching { startForegroundService(Intent(this, RideService::class.java).putExtra("from", "화면")) }
            .onFailure { Prend.log("✗ 화면에서 운행 서비스 시작 실패: $it") }
        // 차 속도·알림 허락: 창 앞에 까닭 판 한 장(두 번까지. 안드로이드도 두 번 거절하면 창을 다시 띄우지 않는다). 그 뒤로는 첫 화면의 한 줄 [설정 열기]
        Stage.openSettings = ::openPermSettings
        syncPerm()
        missing().takeIf { it.isNotEmpty() && permPrefs.getInt("asks", 0) < PERM_ASKS }?.let { permNeed.value = it; Prend.log("· 허락 까닭 판(${it.joinToString { p -> p.substringAfterLast('.') }})") }
        syncPanel()
        // 보험사·119 전화: 전화 걸기 화면을 연다(차에 연결된 폰으로 건다)
        Stage.dial = { tel ->
            runCatching { startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$tel"))) }
                .onSuccess { Prend.log("· 전화 걸기 화면 엶"); Stage.toast(Stage.DIAL_HINT, 6_000) }
                .onFailure { Prend.log("✗ 전화 걸기 화면 없음 $it"); Stage.toast("이 화면에서는 전화를 열 수 없어요. 폰으로 걸어 주세요 · $tel", 5_000) }
        }
        // 음악 서비스를 깨운다(소리 엔진은 서비스 안에서 돈다)
        val future = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        future.addListener({
            controller = runCatching { future.get() }.getOrNull()?.also { c ->
                // 곡·재생 여부·길이가 바뀌었다는 알림을 받을 때만 화면의 곡을 새로 읽는다(재생 위치는 위치 막대가 따로 읽는다)
                c.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) = refreshSong()
                })
            }
            refreshSong()
        }, mainExecutor)
        refreshSong()

        // 귀(Pleos STT)와 차임은 운행 서비스가 쥔다(화면 없이 PREND가 물을 때도 들어야 해서)
        handle(intent)

        val acts = Acts(
            friend = { Stage.call() },
            sos = { Stage.emergency() },
            secret = { if (app.prend.BuildConfig.DEMO) startActivity(Intent(this, DebugActivity::class.java)) },
            playPause = ::playPause,
            prev = { if (showingOther) OtherMusic.prev() else controller?.seekTo(0) },
            next = { if (showingOther) OtherMusic.next() else controller?.seekToNextMediaItem() },
        )
        setContent {
            // 다른 음악 앱의 곡이 바뀌면(알림 액세스를 켰을 때만) 화면의 곡을 새로 읽는다
            LaunchedEffect(Unit) { OtherMusic.now.collect { refreshSong() } }
            App(song.value, position, acts, permNeed.value, ::askPerms, ::permLater)
        }
    }

    /** 화면의 곡(제목·재생 여부·길이·표지). 0.3초마다 읽지 않고, 바뀌었다는 알림을 받을 때만 새로 읽는다 */
    private val song = mutableStateOf(Song("", "", playing = false, durMs = 0L))

    /** 같은 곡이면 같은 값이라 화면을 다시 짜지 않는다 */
    private fun refreshSong() { song.value = readSong() }

    /** 지금 재생 위치. 위치 막대만 1초마다 읽는다 */
    private val position: () -> Long = { if (showingOther) OtherMusic.positionMs() else controller?.currentPosition ?: 0L }

    /** 다른 앱이 준 표지를 화면용으로 바꾼 것. 그 앱이 표지를 바꿀 때만 다시 바꾼다(매번 바꾸면 같은 곡도 다른 곡으로 보고 다시 그린다) */
    private var artFrom: android.graphics.Bitmap? = null
    private var art: ImageBitmap? = null

    // 화면이 보이는 동안은 PREND 화면이 묻고 알리고, 가려지면 운행 서비스의 배너가 맡는다
    override fun onStart() {
        super.onStart()
        Stage.visible(true)
        // 설정에서 허락하고 돌아왔으면 첫 화면의 한 줄을 거두고 차 속도를 다시 구독한다
        syncPerm()
    }

    override fun onStop() {
        Stage.visible(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /**
     * 시연·촬영용 장면 바꾸기. demo는 정 과장의 하루 값(촬영용)을 켜고 끈다.
     * heard는 사람 목소리 없이 '들은 말'을 넣어 본다
     */
    private fun handle(i: Intent) {
        // 시연 값(기어·기온 고정, 시연 위치 문구)과 아무 장면 열기는 개발용(디버그 빌드)만. 출시 빌드는 배너가 여는 장면(지금 장면·같이 고르기)만 받는다
        val debug = app.prend.BuildConfig.DEMO
        if (debug && i.hasExtra("demo")) Stage.demo(i.getBooleanExtra("demo", false))
        i.getStringExtra("scene")?.let { name ->
            val scene = Scene.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return@let
            // 사고 화면은 혹시 사고 물음(배너의 [사고가 났어요])이나 그 뒤의 "혹시 사고였나요?" 배너가 열 때만 받는다
            val bumpOpen = scene == Scene.ACCIDENT && (Stage.ui.value.ask?.opens == Scene.ACCIDENT || Stage.ui.value.bumpHint)
            // 떠 있는 물음 카드가 여는 장면(차 돌봄 카드의 [자세히])도 받는다
            val askOpen = Stage.ui.value.ask?.takeIf { it.opens == scene && scene != Scene.ACCIDENT }
            if (!debug && scene != Stage.ui.value.scene && scene != Scene.PICK && !bumpOpen && askOpen == null) return@let
            // 물음의 [예]로 열었으면 물음에 대답한 것으로(Care가 카드를 거두고 지켜보기를 끝낸 뒤 사고 화면으로)
            val bumpAsk = Stage.ui.value.ask?.takeIf { bumpOpen && it.opens == Scene.ACCIDENT }
            if (bumpAsk != null) { Stage.answer(bumpAsk.id, true); return@let }
            // 같은 길로 물음에 [예]라고 대답한 것으로(차 돌봄은 CarCare가 카드를 거두고 그 항목을 연다). 같이 고르기는 아래에서 연다
            if (askOpen != null && scene != Scene.PICK) { Stage.answer(askOpen.id, true); return@let }
            when (scene) {
                Scene.NAP -> Stage.napStart(i.getIntExtra("min", 15))
                // 배너의 [골라 볼게요]도 여기로 온다
                Scene.PICK -> Stage.openPick(i.getStringExtra("kind") ?: "food")
                Scene.DRIVE -> {
                    if (Stage.ui.value.demo) Stage.ui.value = Stage.ui.value.copy(driveMinutes = i.getIntExtra("min", 124))
                    Stage.go(scene)
                }
                else -> Stage.go(scene, i.getIntExtra("step", 1))
            }
        }
        // 들은 말 넣기는 개발용(디버그 빌드)만. 출시 빌드에서는 다른 앱이 말을 넣지 못한다
        if (app.prend.BuildConfig.DEMO) i.getStringExtra("heard")?.let { said ->
            Stage.call(useEars = false)
            main.postDelayed({ Stage.onHeard(said, true) }, 1_400)
        }
        if (i.getBooleanExtra("call", false)) Stage.call()
        i.removeExtra("scene"); i.removeExtra("heard"); i.removeExtra("call"); i.removeExtra("demo")
    }

    /** 화면의 곡이 다른 음악 앱의 것인가(그러면 재생 버튼도 그 앱을 누른다) */
    private var showingOther = false

    private fun readSong(): Song {
        val c = controller
        // PREND 사운드가 흐르지 않고 다른 음악 앱이 곡을 쥐고 있으면 그 곡을 보여 준다(알림 액세스를 켰을 때만 안다)
        val other = OtherMusic.now.value
        showingOther = c?.isPlaying != true && other != null
        if (showingOther && other != null) {
            if (other.art !== artFrom) { artFrom = other.art; art = other.art?.asImageBitmap() }
            return Song(other.title, listOf(other.app, other.artist).filter { it.isNotBlank() }.joinToString(" · "),
                other.playing, other.durMs, other = true, art = art)
        }
        val tr = Library.tracks.first()
        val title = c?.mediaMetadata?.title?.toString()?.takeIf { it.isNotBlank() } ?: tr.title
        return Song(title, "${Prend.NAME} 사운드 · ${tr.moods.first()}", c?.isPlaying == true, c?.duration?.takeIf { it > 0 } ?: 0L)
    }

    private fun playPause() {
        if (showingOther) { OtherMusic.playPause(); return }
        val c = controller
        if (c?.isPlaying == true) { c.pause(); return }
        Bridge.brain?.play() ?: c?.run {
            if (mediaItemCount == 0) setMediaItem(MediaItem.Builder().setMediaId(Library.tracks.first().id).build())
            prepare(); play()
        }
    }

    override fun onDestroy() {
        Stage.dial = null
        // 사라진 화면의 허락 창을 운행 서비스가 부르지 않게(부르면 등록이 풀린 창이 예외를 던진다)
        Stage.needMic = null
        Stage.openSettings = null
        // 화면이 사라지면 판도 없다(위치 알림 카드가 기다리지 않게. 다시 열면 onCreate가 다시 맞춘다)
        if (Stage.ui.value.permPanel) Stage.ui.update { it.copy(permPanel = false) }
        controller?.release()
        super.onDestroy()
    }
}

private const val SPEED = "android.car.permission.CAR_SPEED"
private const val NOTIF = "android.permission.POST_NOTIFICATIONS"
/** 허락 까닭 판을 띄우는 횟수(안드로이드도 두 번 거절하면 창을 다시 띄우지 않는다) */
private const val PERM_ASKS = 2
/** 허락 창을 띄운 뒤 이 안에 거절이 돌아오면 사람이 누른 것이 아니라 안드로이드가 창 없이 거절한 것으로 본다 */
private const val QUICK_DENY_MS = 500L

/** perm은 허락 까닭 판에 적을 허락(비면 판이 없다), allow·later는 판의 두 단추 */
@Composable
private fun App(song: Song, pos: () -> Long, acts: Acts, perm: List<String>, allow: () -> Unit, later: () -> Unit) {
    val state = Stage.ui.collectAsState()
    // 듣는 동안의 목소리 크기(초당 10~25번)는 막대가 그릴 때만, 알림 글은 알림 캡슐만 읽는다. 장면은 그 밖의 값이 바뀔 때만 다시 짠다
    val ui by remember { derivedStateOf(structuralEqualityPolicy()) { state.value.forScenes() } }
    val toast by remember { derivedStateOf { state.value.toast } }
    val level = remember { { state.value.listen?.level ?: 0f } }
    val haze = remember { HazeState() }
    val glass = remember(haze) { Glass(haze) }
    val hold = remember { HoldState() }
    // 다른 화면(차량 제어 등)이 PREND를 덮으면 누르던 고리를 지운다
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { hold.at = null }
    // 차의 뒤로 가기(있으면): 기억·차 돌봄·같이 고르기는 화면 안에서 한 단계 위로. 첫 화면·급할 때·쪽잠·달리는 중은 그대로 시스템에 맡긴다
    // (11/08 오류 점검 E6: 받지 않아 하위 화면에서 앱 전체가 뒤로 갔고, 다시 열면 그 하위 화면이 그대로 남아 있었다)
    BackHandler(enabled = ui.scene == Scene.MEMO || ui.scene == Scene.CARE || ui.scene == Scene.PICK) {
        val u = Stage.ui.value
        when {
            u.scene == Scene.MEMO && u.about -> Stage.openAbout(false)
            u.scene == Scene.MEMO && u.memoMore -> Stage.openMemoMore(false)
            u.scene == Scene.MEMO && u.insurerPick -> Stage.openInsurer(false)
            u.scene == Scene.CARE && u.care.shops != null -> Stage.care("back")
            u.scene == Scene.CARE && u.care.lamp != null -> Stage.care("lamps")
            u.scene == Scene.CARE && u.care.lamps -> Stage.care("list")
            u.scene == Scene.CARE && u.care.open != null -> Stage.care("list")
            else -> Stage.go(Scene.HOME)
        }
    }
    // 얼굴마다 마지막으로 그린 화면 상태: 사라지는 장면은 닫기 직전 모습 그대로 옅어지게 한다. 지금 상태를 넘기면 아플 때 화면을 닫는 0.45초 동안
    // 장면이 이미 첫 화면이라 사고 안내 1걸음('비상등을 켜세요', 1/8)이 섞여 보였다(11/08 오류 점검 E9). 그리기용 그릇이라 상태가 아니다
    val last = remember { mutableMapOf<Face, StageUi>() }
    CompositionLocalProvider(LocalGlass provides glass) {
        Box(Modifier.fillMaxSize().background(C.Base).holdToCall(hold) { Stage.call() }) {
            // 얼굴(장면, 첫 화면이면 달리는 중인가)이 바뀔 때만 섞는다: P→D면 첫 화면이 달리는 동안의 판으로 0.45초에 바뀐다
            val face = ui.face
            last[face] = ui
            Box(Modifier.fillMaxSize().hazeSource(haze)) {
                // 바탕 색은 바탕이 달라질 때만 섞는다(첫 화면·같이 고르기·기억 사이는 그대로 두고 글만 바뀐다)
                Crossfade(face.ground, animationSpec = tween(450), label = "ground") { SceneGround(it) }
                Crossfade(face, animationSpec = tween(450), label = "back") { SceneBack(it.scene, if (it == face) ui else last[it] ?: ui, song, acts, it.driving) }
            }
            // 위 막대는 장면과 함께 섞지 않는다(사라졌다 나타나지 않게). 기어 캡슐만 미끄러진다
            SceneTop(ui, acts)
            Crossfade(face, animationSpec = tween(450), label = "front") { SceneFront(it.scene, if (it == face) ui else last[it] ?: ui, song, pos, level, acts, it.driving) }
            HoldRing(hold)
            ListenLayer(ui, level)
            ToastLayer(toast, toastTop(ui))
            // 허락 까닭 판은 세워 둔 첫 화면에 물음 카드가 없을 때만(검토 11/06: 급할 때 배너로 처음 연 화면에서 [119 전화]·물음 띠·정차 큰 판을 덮었다).
            // 급할 때·달리는 중·물음 카드가 떠 있는 동안은 숨겼다가 다음 첫 화면·P에서 보인다. 판 횟수는 단추를 눌렀을 때만 센다
            if (perm.isNotEmpty() && ui.scene == Scene.HOME && ui.parked && ui.ask == null) PermPanel(perm, allow, later)
        }
    }
}

/** 장면이 쓰는 화면 상태: 목소리 크기와 알림 글을 뺀다(그 둘만 바뀐 상태는 같은 값이 되어 장면을 다시 짜지 않는다) */
private fun StageUi.forScenes(): StageUi {
    val l = listen?.takeIf { it.level != 0f }?.copy(level = 0f) ?: listen
    return if (l === listen && toast == null) this else copy(listen = l, toast = null)
}

/**
 * 허락 창 앞의 까닭 판(검토 11/06). 차 속도는 혹시 사고와 말할 때 고르기에, 알림은 화면을 닫아 둔 운행의 물음과 급할 때 걸음에 쓴다.
 * 판 밖을 눌러도 뒤의 화면이 눌리지 않는다. [허락하기]면 안드로이드 허락 창, [나중에]면 첫 화면의 한 줄로 남긴다
 */
@Composable
private fun PermPanel(perm: List<String>, allow: () -> Unit, later: () -> Unit) {
    val speed = SPEED in perm
    val notif = NOTIF in perm
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = .6f)).clickable(remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        val card = sq(56)
        Column(Modifier.width(1180.dp).frosted(card).padding(horizontal = 64.dp, vertical = 56.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                IconTile(PhosphorIcons.Fill.ShieldCheck, C.Mint, C.Mint.copy(alpha = .16f), 80, 26, 44)
                Text("허락이 필요해요", style = t(34, FontWeight.W700, C.Mint))
            }
            Text(
                when { speed && notif -> "차 속도와 알림을\n허락해 주세요"; speed -> "차 속도를 허락해 주세요"; else -> "알림을 허락해 주세요" },
                style = t(64, FontWeight.W800, tracking = -.035, line = 1.15), modifier = Modifier.padding(top = 32.dp),
            )
            Column(Modifier.padding(top = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (speed) Text("급제동 뒤 멈추면 혹시 사고인지 묻고, 바쁠 땐 말을 미루려면 차 속도를 봐야 해요", style = t(32, FontWeight.W500, C.L2, line = 1.3))
                // 차에서는 차 속도 허락이 위치 묶음이라 창에는 "위치 정보에 액세스"로 뜬다(11/07 에뮬레이터). 판과 창의 말이 달라 거절하지 않게 미리 알린다
                if (speed) Text("차에서는 이 허락이 안드로이드 창에 '위치 정보'로 나와요", style = t(28, FontWeight.W500, C.L3, line = 1.3))
                if (notif) Text("화면을 닫아 둬도 물음과 급할 때 안내를 알림으로 띄워요", style = t(32, FontWeight.W500, C.L2, line = 1.3))
            }
            Row(Modifier.padding(top = 44.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Btn("허락하기", primary = true, height = 110, size = 38, pad = 56, onClick = allow)
                Btn("나중에", primary = false, height = 110, size = 38, pad = 56, onClick = later)
            }
        }
    }
}
