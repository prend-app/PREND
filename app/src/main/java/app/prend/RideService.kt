package app.prend

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.prend.car.CarLink
import app.prend.car.CarState
import app.prend.car.Navi
import app.prend.ride.AfterCrash
import app.prend.ride.Baby
import app.prend.ride.Banner
import app.prend.ride.Toilets
import app.prend.ride.Floor
import app.prend.ride.Bump
import app.prend.ride.CarCare
import app.prend.ride.Coach
import app.prend.ride.Care
import app.prend.ride.Crash
import app.prend.ride.Hush
import app.prend.ride.JamHelp
import app.prend.ride.Memory
import app.prend.ride.Nap
import app.prend.ride.Novice
import app.prend.ride.Together
import app.prend.music.Library
import android.content.ComponentName
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.prend.ride.RestAreas
import app.prend.ride.RideClock
import app.prend.ride.RideKeep
import app.prend.ride.Timing
import app.prend.sound.Chime
import app.prend.voice.Ears
import app.prend.voice.Speaker
import app.prend.voice.TalkSense
import app.prend.ui.Stage
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 시동(부팅)과 함께 PREND를 깨운다(시험 52). 운전자가 화면을 열지 않아도 운행을 챙긴다. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Prend.init(context)
        Prend.log("◆ 부팅 신호: ${intent.action}")
        runCatching { context.startForegroundService(Intent(context, RideService::class.java).putExtra("from", "부팅")) }
            .onFailure { Prend.log("✗ 부팅 때 운행 서비스 시작 실패: $it") }
    }
}

/**
 * 운행 내내 깨어 있는 서비스(PREND의 몸통). 시동과 함께(부팅) 또는 화면을 열 때 깨어나,
 * 차 신호를 듣고 연속 운전 시간을 세어 화면에 넘긴다. 화면이 없어도 돈다.
 * 다음 단계에서 여기에 '말할 때 고르기', 한 시간·두 시간 챙김, 시동 카드가 들어온다.
 *
 * 시험 명령: adb shell am broadcast --user 10 -a app.prend.RIDE --es cmd <clock60|clock1|drive_분>
 */
class RideService : Service() {

    private val ui = Handler(Looper.getMainLooper())
    private var started = false
    private var detachCar: (() -> Unit)? = null
    private var clock: RideClock? = null
    /** 말할 때 고르기. 안정 주행은 길 안내 음성이 끝난 지 10초 뒤부터(별첨 2 §2-1). 다른 음성 비서가 말하는 중이면 먼저 꺼내는 말을 미룬다(검토 11/06) */
    private val timing = Timing(guide = { navVoice() || speaker?.navDucked == true }, slowTraffic = { c -> jamHelp?.slowTraffic(c) == true }, speedLimit = { navi?.speedLimit() }, road = { road?.now() }, turnNear = { navi?.turnNear() }, lost = { (navi?.reroutesWithin(5 * 60_000L) ?: 0) >= 2 },
        assistant = ::otherAssistant)
    /** 골목 판정: 차가 설 때 지나온 자리로 TMAP 도로 등급을 묻는다(중계 서버 /road) */
    private var road: app.prend.ride.Road? = null
    /** 말 차례(11/01): PREND가 먼저 꺼내는 물음·한마디는 한 번 설 때 하나, 말·카드·[프렌드]가 끝난 뒤 20초 */
    private val floor = Floor(timing) { speaker?.speaking == true || Stage.ui.value.listen != null || Stage.ui.value.ask != null }
    private var care: Care? = null
    private var speaker: Speaker? = null
    private var ears: Ears? = null
    private var rests: RestAreas? = null
    private var navi: Navi? = null
    private var lastAsk = 0L
    private var stt: app.prend.voice.SttProbe? = null
    private var banner: Banner? = null
    private val scope = MainScope()
    private var watch: Job? = null
    private var testTemp: Float? = null
    private var hush: Hush? = null
    private var crash: Crash? = null
    private var jamHelp: JamHelp? = null
    private var together: Together? = null
    private var lastTick = 0L
    private var memory: Memory? = null
    private var aliveSaved = 0L
    private var after: AfterCrash? = null
    private var baby: Baby? = null
    private var bump: Bump? = null
    private var jolt: app.prend.car.Jolt? = null
    private var nap: Nap? = null
    private var novice: Novice? = null
    private var coach: Coach? = null
    private var carCare: CarCare? = null
    private var alleyDest: app.prend.ride.AlleyDest? = null
    private var dusk: app.prend.ride.Dusk? = null
    private var slowTalk: app.prend.ride.SlowTalk? = null
    private var shopVisit: app.prend.ride.ShopVisit? = null
    private var highwayStop: app.prend.ride.HighwayStop? = null
    /** 한 운행의 상태를 프로세스 밖에 적어 두는 곳(같은 운행 중 프로세스가 다시 떠도 잇는다, 감사 F02) */
    private var keep: RideKeep? = null
    /** 이야기에 비켜 서기: 말소리 감지, 이번 운행에 마이크 포그라운드로 올렸나, 이야기 때 낮춘 바람(원래 세기 → 낮춘 세기) */
    private var talk: TalkSense? = null
    private var micFgs = false
    private var talkFan: Pair<Int, Int>? = null
    /** 이야기 때 낮춘 바람을 되돌린 때(elapsedRealtime). 그 뒤 2분은 다시 낮추지 않는다(별첨 4 §4-5) */
    private var talkFanBackAt = 0L
    private var talkRetryAt = 0L
    /** 시험: 동승자가 있는 것처럼(에뮬레이터 좌석은 비어 있다) */
    private var testOthers = false

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        Prend.init(this)
        app.prend.ride.Stats.init(this)
        // 시험 값(위 막대의 '시연' 표와 같은 것)이 켜진 동안은 기기 안 숫자를 세지 않는다(검토 11/06 18). 출시판에는 시험 값이 없다
        if (BuildConfig.DEMO) app.prend.ride.Stats.testing = { demoTag() != null }
        // 위치 보내기(사용자 결정 11/06)는 위치를 보내는 어느 부품보다 먼저 읽는다(읽기 전에는 보내지 않는다)
        app.prend.ride.GeoShare.init(this)
        // 이 차(Pleos, 안드로이드 14)는 꺼졌다 다시 띄운 서비스에 시작 요청(onStartCommand)을 보내지 않는다(10/25 시험).
        // 그래서 깨어나는 일은 시작 요청을 기다리지 않고 만들어질 때 한다
        if (foreground()) wake("만들어짐")
    }

    /**
     * 운행 중 알림과 함께 포그라운드로. 부를 때마다 다시 알려도 된다.
     * mic면 마이크 형식도 더한다(안드로이드 14: 화면이 보일 때만 더할 수 있고, 한 번 더하면 화면이 가려져도 이어진다).
     * 마이크 형식으로 실패하면 마이크 없이 다시 올리고 이번 운행의 말소리 감지를 끈다
     */
    private fun foreground(mic: Boolean = micFgs): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        // Pleos는 지울 수 없는 알림을 목록에도 보이지 않는다(시험 52-2). [프렌드 부르기]는 배너(Banner)가 맡는다
        nm.createNotificationChannel(NotificationChannel(QUIET, "운행 중", NotificationManager.IMPORTANCE_LOW))
        val ongoing = Notification.Builder(this, QUIET)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(Prend.NAME)
            .setContentText(if (mic) "이번 운행을 챙기는 중 · 말소리 여부만 재요(저장 안 함)" else "이번 운행을 챙기는 중")
            .build()
        val special = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (mic) {
            val ok = runCatching { startForeground(ONGOING_ID, ongoing, special or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) }
                .onFailure { Prend.log("✗ 마이크 포그라운드 실패(말소리 감지 끔): ${it.javaClass.simpleName}") }.isSuccess
            if (ok) return true
            micFgs = false
            talk?.stop("마이크 포그라운드 실패")
        }
        return runCatching { startForeground(ONGOING_ID, ongoing, special) }
            .onFailure { Prend.log("✗ 운행 서비스 포그라운드 실패: $it") }
            .isSuccess
    }

    // ── 이야기에 비켜 서기(말소리 감지) ──

    private val talkPrefs get() = getSharedPreferences("talk", MODE_PRIVATE)

    private fun micGranted() = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** 조수석이나 뒷자리에 사람이 있다(혼자면 비켜 설 이야기가 없어 마이크를 열지 않는다) */
    private fun others(): Boolean = testOthers || CarLink.state.seats.any { (k, v) -> v && k != "ROW_1_LEFT" }

    /** 켜고 끈다(첫 물음의 대답·기억 화면의 버튼·시험 명령). 켰는데 마이크 허락이 없으면 화면이 허락을 묻는다 */
    private fun setTalk(on: Boolean) {
        talkPrefs.edit().putBoolean("on", on).putBoolean("decided", true).apply()
        Stage.ui.update { it.copy(talkOn = on) }
        Prend.log("◆ 이야기에 비켜 서기 ${if (on) "켬" else "끔"}")
        // 허락 창은 화면이 보일 때만 뜬다(배너로 켰으면 다음에 화면을 열 때 talkTick이 묻는다)
        if (on && !micGranted() && Stage.ui.value.visible) { micAsked = true; Stage.needMic?.invoke() }
        if (!on) talk?.stop("사람이 끔")
    }

    /**
     * 1초마다: 켜 둠·마이크 허락·이번 운행에 화면을 엶·동승자·급할 때 아님이면 듣고, 아니면 멈춘다.
     * 화면이 보일 때 마이크 포그라운드로 올려 둔다(화면을 한 번도 열지 않은 운행에서는 마이크를 쓰지 않는다)
     */
    private fun talkTick() {
        val s = talk ?: return
        if (s.testing) return
        val ui = Stage.ui.value
        val now = SystemClock.elapsedRealtime()
        // 켜 두었는데 마이크 허락이 없으면 화면이 보일 때 이번 운행에 한 번 묻는다(배너로 켰거나 다른 허락 창과 겹쳐 묻지 못했을 때)
        if (ui.talkOn && !micGranted() && ui.visible && !micAsked) { micAsked = true; Stage.needMic?.invoke() }
        // 운전석 문이 열리면(내림) 마이크 포그라운드를 내려놓는다. 다시 화면을 열어야 듣는다
        val door = (CarLink.state.doors["ROW_1_LEFT"] ?: 0) > 0
        if (door && !driverDoor && CarLink.state.parked && micFgs) { talk?.stop("운전석 문 열림"); micFgs = false; foreground(mic = false) }
        driverDoor = door
        if (ui.talkOn && micGranted() && ui.visible && !micFgs && now >= micFgsRetryAt) {
            micFgs = true
            foreground(mic = true)
            // 실패하면 foreground가 micFgs를 되돌린다: 1분 뒤에 다시
            if (micFgs) Prend.log("· 마이크 포그라운드: 이번 운행에 말소리 감지를 쓸 수 있음") else micFgsRetryAt = now + 60_000
        }
        val want = ui.talkOn && micGranted() && micFgs && others() && !ui.scene.isE && ui.listen == null
        if (want && !s.active && now >= talkRetryAt && now - s.diedAt >= 30_000) {
            if (!s.start()) talkRetryAt = now + 30_000
        }
        if (!want && s.active) s.stop(when { !ui.talkOn -> "꺼 둠"; ui.scene.isE -> "급할 때"; !others() -> "혼자"; ui.listen != null -> "말을 듣는 동안"; else -> "조건 바뀜" })
        if (s.active != ui.talkLive) Stage.ui.update { it.copy(talkLive = s.active) }
    }

    /** 새 운행: 마이크 포그라운드를 내려놓는다(이번 운행에 화면을 다시 열어야 말소리 감지를 쓴다) */
    private fun talkNewRide() {
        talk?.stop("새 운행")
        micAsked = false
        micFgsRetryAt = 0L
        talkFanTold = false
        if (micFgs) { micFgs = false; foreground(mic = false) }
    }

    /** 이번 운행에 마이크 허락을 물었다 */
    private var micAsked = false
    /** 마이크 포그라운드 승격이 실패한 뒤 다시 해 볼 때 */
    private var micFgsRetryAt = 0L
    /** 운전석 문이 열려 있었다(열리는 순간을 잡으려고) */
    private var driverDoor = false

    /** 이야기가 시작·끝났다: 말 삼키기(Timing), PREND 곡(SoundDirector), 화면, 바람 */
    private fun onTalking(on: Boolean) {
        timing.onTalking(on)
        Bridge.director?.setTalking(on)
        Stage.ui.update { it.copy(talking = on) }
        ui.removeCallbacks(fanDown); ui.removeCallbacks(fanBack)
        // 이야기가 이어지면 10초 뒤 낮추고, 끝나면 낮춘 바람이 있을 때만 10초 뒤 되돌린다(낮춘 지 2분이면 그 전에 되돌린다: fanBack2m)
        if (on) ui.postDelayed(fanDown, 10_000) else if (talkFan != null) ui.postDelayed(fanBack, 10_000)
    }

    /**
     * 이야기가 10초 넘게 이어지면 바람을 한 단 낮춘다(별첨 4 §4-5, 감사 F68). 한 이야기에 한 번, 3단 이상일 때만.
     * 그대로 두는 때: 급할 때, 대리 운행(기사가 맞춘 공조), 와이퍼 작동, 되돌린 지 2분 안, 공조가 꺼짐, AUTO, 앞유리 성에 제거, 내기 순환(김서림 위험),
     * 다른 앱 소리(음악·라디오)나 통화가 스피커로 나오는 중(그 목소리를 차 안 이야기로 셀 수 있다, 검토 11/07 멋대로 판단).
     * 공조 전원을 먼저 읽는다. 자동 김서림 방지는 SDK가 읽지 못해 보지 않는다. 낮추면 운행에 한 번 화면 한 줄로 까닭을 알린다
     */
    private val fanDown = Runnable {
        if (!Stage.ui.value.talking || talkFan != null) return@Runnable
        val s = Stage.ui.value
        val car = CarLink.state
        val since = SystemClock.elapsedRealtime() - talkFanBackAt
        val skip = when {
            s.scene.isE -> "급할 때"
            s.daeri -> "대리 운행"
            car.wiperOn -> "와이퍼 작동"
            talkFanBackAt > 0 && since < TALK_FAN_MS -> "되돌린 지 ${since / 1000}초"
            else -> speakerVoice()
        }
        if (skip != null) { Prend.log("· 이야기 10초: 바람 그대로($skip)"); return@Runnable }
        val hub = CarLink.hubOrNull ?: return@Runnable
        hub.readClimate { c ->
            val keep = when {
                c.power == false -> "공조 꺼짐"
                c.auto == true -> "공조 AUTO"
                c.defrost == true || c.maxDefrost == true -> "성에 제거"
                c.recirc == true || CarLink.state.recirc == true -> "내기 순환"
                else -> null
            }
            if (keep != null) { Prend.log("· 이야기 10초: 바람 그대로($keep)"); return@readClimate }
            hub.readFan { sp ->
                if (sp == null || sp < TALK_FAN_MIN || !Stage.ui.value.talking || talkFan != null) {
                    if (sp != null && sp < TALK_FAN_MIN) Prend.log("· 이야기 10초: 바람 그대로(${sp}단)")
                    return@readFan
                }
                // 공조를 읽는 사이 다른 앱 소리·통화가 시작됐을 수 있다
                speakerVoice()?.let { why -> Prend.log("· 이야기 10초: 바람 그대로($why)"); return@readFan }
                hub.setFan(sp - 1, face = false) { ok ->
                    if (!ok) return@setFan
                    talkFan = sp to sp - 1
                    Prend.log("◆ 이야기 10초: 바람 한 단 낮춤 $sp → ${sp - 1}")
                    // 공조가 저절로 바뀌는 것처럼 보이지 않게 까닭을 운행에 한 번 한 줄로(검토 11/07)
                    if (!talkFanTold) { talkFanTold = true; Stage.toast("이야기 중이라 바람을 한 단 줄였어요 · 2분 뒤 돌려 놓아요", 4_000) }
                    // 낮춘 지 2분이면 이야기가 이어져도 되돌린다
                    ui.removeCallbacks(fanBack2m); ui.postDelayed(fanBack2m, TALK_FAN_MS)
                }
            }
        }
    }

    /** 이번 운행에 바람을 줄인 까닭을 알렸나 */
    private var talkFanTold = false

    /** 스피커에서 차 안 이야기가 아닌 목소리가 나올 수 있나: 통화 중, 다른 앱 소리(음악·라디오·팟캐스트). 아니면 null */
    private fun speakerVoice(): String? = when {
        speaker?.inCall == true -> "통화 중"
        jamHelp?.otherMusicOn() == true -> "다른 앱 소리"
        else -> null
    }

    /** 이야기가 끝나고 10초 뒤 바람을 되돌린다 */
    private val fanBack = Runnable { restoreTalkFan("이야기 끝 10초") }

    /** 낮춘 지 2분이면 되돌린다 */
    private val fanBack2m = Runnable { restoreTalkFan("낮춘 지 2분") }

    /** 이야기 때 낮춘 바람을 정확히 원래 단으로 되돌린다. 그사이 사람이 세기를 바꿨으면 그대로 둔다. 되돌린 뒤 2분은 다시 낮추지 않는다 */
    private fun restoreTalkFan(why: String) {
        ui.removeCallbacks(fanBack); ui.removeCallbacks(fanBack2m)
        val (orig, low) = talkFan ?: return
        talkFan = null
        talkFanBackAt = SystemClock.elapsedRealtime()
        val hub = CarLink.hubOrNull ?: return
        hub.readFan { sp ->
            when (sp) {
                low -> hub.setFan(orig, face = false) { Prend.log("◆ $why: 바람 되돌림 $low → $orig") }
                orig -> Prend.log("· $why: 바람이 이미 원래 세기($orig)")
                else -> Prend.log("· $why: 바람을 사람이 바꿔 그대로($sp)")
            }
        }
    }

    /** [프렌드] "음악 틀어 줘": 다른 음악이 흐르면 그대로 두고, 아니면 PREND 잔잔한 곡. "노래 좀 꺼 줘"는 PREND 곡만 멈춘다 */
    private fun music(on: Boolean) {
        val am = getSystemService(android.media.AudioManager::class.java)
        if (!on) {
            if (Bridge.director?.playing == true) { stopCalm(); Stage.toast("${Prend.NAME} 곡을 멈췄어요") }
            else Stage.toast(if (am.isMusicActive) "다른 앱의 음악은 그 앱에서 멈춰 주세요" else "나오는 음악이 없어요")
            return
        }
        when {
            Bridge.director?.playing == true -> Stage.toast("${Prend.NAME} 곡이 나오고 있어요")
            am.isMusicActive -> Stage.toast("지금 나오는 음악을 그대로 둘게요")
            else -> { playCalm(); Stage.toast("${Prend.NAME} 곡을 틀게요") }
        }
    }

    /**
     * [프렌드] "이 노래 뭐야?"·"아까 그 노래"(감사 F89): PREND 곡이면 화면에 곡 이름을 띄우고 짧게 한마디(곡 이름은 말로 넣지 않는다),
     * 다른 앱 곡이면 화면 알림만. 지금 곡·바로 전 곡은 메모리에만 있고(music/NowPlaying), 기록에는 곡 제목을 남기지 않는다
     */
    private fun songWhat(before: Boolean) {
        val np = app.prend.music.NowPlaying
        val musicOn = getSystemService(android.media.AudioManager::class.java).isMusicActive
        // 지금 곡을 물었는데 적어 둔 PREND 곡은 멈췄고 다른 앱 음악이 흐르면(알림 액세스가 꺼져 그 곡을 모름) 지난 PREND 곡으로 답하지 않는다
        val h = if (before) np.before ?: np.now else np.now?.takeUnless { it.prend && Bridge.director?.playing != true && musicOn }
        Prend.log("· 노래 물음(${if (before) "아까" else "지금"}) → ${when { h == null -> "모름"; h.prend -> "PREND 곡"; else -> "다른 앱 곡" }}")
        when {
            h == null -> Stage.toast(if (musicOn) "다른 앱 노래 이름은 알림 액세스를 켜면 보여 드려요" else "지금 흐르는 노래가 없어요", 3_500)
            h.prend -> {
                Stage.toast("${Prend.NAME} 곡 · ${h.title}", 5_000)
                runCatching { speaker?.speak("voice/song_prend.wav", android.media.AudioAttributes.USAGE_ASSISTANT, "노래 물음") }
                    .onFailure { Prend.log("✗ 노래 물음 한마디 틀기 실패 $it") }
            }
            else -> Stage.toast("${h.app ?: "다른 앱"} · ${h.title}${h.artist.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}", 5_000)
        }
    }

    /**
     * [프렌드] "빗소리 깔아 줘"·"빗소리 꺼 줘": PREND 곡 위의 빗소리 결(SoundDirector). 곡도 다른 음악도 없으면 한마디 뒤에 잔잔한 곡을 함께 틀고,
     * 다른 앱 음악이 흐르면 그 위에는 깔리지 않는다고 알린다(그 앱을 멈추지 않는다). 새 운행에 되돌린다
     */
    private fun rain(on: Boolean) {
        Bridge.rain = on
        Bridge.director?.let { it.manualRain = on; it.rainOff = !on }
        val am = getSystemService(android.media.AudioManager::class.java)
        val prend = Bridge.director?.playing == true
        val other = !prend && am.isMusicActive
        Prend.log("◆ 빗소리 ${if (on) "깖" else "뺌"}(말)${if (on && other) " · 다른 앱 음악이 흐름" else ""}")
        if (on && other) { Stage.toast("빗소리는 ${Prend.NAME} 곡 위에만 깔려요", 3_500); return }
        val play = on && !prend
        Stage.toast(if (!on) "빗소리를 뺐어요" else if (play) "잔잔한 곡에 빗소리를 깔게요" else "빗소리를 깔았어요", 3_000)
        // 한마디가 끝난 뒤에 곡을 튼다(곡이 먼저 포커스를 잡아 한마디를 덮지 않게). 말하지 못하면 바로 튼다
        val said = runCatching {
            speaker?.speak(if (on) "voice/rain_on.wav" else "voice/rain_off.wav", android.media.AudioAttributes.USAGE_ASSISTANT, if (on) "빗소리 깔기" else "빗소리 빼기") { if (play) playCalm() }
        }.getOrNull() == true
        if (!said && play) playCalm()
    }

    /**
     * P로 15분 쉬어 연속 운전이 0이 됐다(RideClock). 한 시간 넘게 달린 뒤면 한 줄로 알리고, 첫 화면 연속 운전 칸에 '방금 15분 쉬셨어요'를 둔다.
     * 쪽잠으로 자는 동안·급할 때는 알리지 않고(칸에만 남긴다), 차임은 화면을 보고 있을 때만 작게(대리 운행·조용히 가기는 소리 없이)
     */
    private fun rested(min: Int) {
        if (min < Care.HOUR_AT) return
        val s = Stage.ui.value
        Stage.ui.update { it.copy(restedAt = System.currentTimeMillis()) }
        if (s.napPhase == app.prend.ui.NapPhase.SLEEP || s.scene.isE) return
        val drove = "${min / 60}시간" + if (min % 60 > 0) " ${min % 60}분" else ""
        Stage.toast("$drove 달린 뒤 ${RideClock.REST_MS / 60_000}분 쉬셨어요 · 다시 처음부터", 5_000)
        if (s.visible && s.napPhase == null && !s.daeri && !s.quietRide) runCatching { Chime.play(soft = true) }
    }

    /**
     * 1초마다: 첫 화면의 때 지난 줄을 거둔다. '방금 15분 쉬셨어요'는 다시 달려 운전 시간이 1분이 되거나 30분이 지나면,
     * '새로 기억함'은 하루
     */
    private fun expireCards(minutes: Int) {
        val s = Stage.ui.value
        if (s.restedAt > 0 && (minutes > 0 || System.currentTimeMillis() - s.restedAt > 30 * 60_000L)) Stage.ui.update { it.copy(restedAt = 0L) }
        memory?.expire()
    }

    /** 새 운행: 말로 켜고 끈 빗소리를 와이퍼대로 되돌린다 */
    private fun rainNewRide() {
        if (Bridge.rain == null) return
        Bridge.rain = null
        Bridge.director?.let { it.manualRain = false; it.rainOff = false }
        Prend.log("· 빗소리: 새 운행이라 와이퍼대로")
    }

    /**
     * 차 화면이 잠들었다 깨어났다(시동을 껐다 켬). 30분 안이면 같은 운행으로 잇는다(검토 11/07 멋대로 판단): 휴게소에 잠깐 들른 것도 사람에게는 한 운행이라
     * 조용히 가기·대리 운행·이번 운행 아이 탑승·잘래요를 그대로 둔다. 운전자 쪽잠만 조용히 끝낸다(시동을 다시 켰으면 깬 것이다).
     * 그보다 오래면 새 운행이고, 그때 풀린 조용히 가기·대리 운행은 화면 한 줄로 알린다(말없이 풀리지 않게).
     * 연속 운전 시간은 여기와 따로 RideClock이 잠든 동안을 쉰 시간으로 센다: 15분 넘게 잠들었으면 같은 운행이어도 0이 되고 쉼터 권하기도 처음부터다(Care.tick)
     */
    private fun woke(gapMs: Long) {
        val min = (gapMs / 60_000).toInt()
        if (gapMs <= RIDE_GAP_MS) {
            Prend.log("◆ 차 화면이 ${min}분 잠들었다 깸: 같은 운행으로 이어 감(${RIDE_GAP_MS / 60_000}분 안)")
            nap?.resumed()
            return
        }
        val was = Stage.ui.value
        care?.newRide("잠에서 깸", min)
        released(was.quietRide, was.daeri)
    }

    /** 새 운행으로 풀린 지난 운행의 조용히 가기·대리 운행을 한 줄로 알린다('늘 조용히'로 다시 켜졌으면 알리지 않는다) */
    private fun released(quiet: Boolean, daeri: Boolean) {
        val u = Stage.ui.value
        val lines = listOfNotNull(
            "지난 운행의 '급한 말만 하기'를 풀었어요".takeIf { quiet && !u.quietRide },
            "대리 운행을 풀었어요".takeIf { daeri && !u.daeri },
        )
        if (lines.isEmpty()) return
        Prend.log("· 새 운행: 지난 운행의 ${if (quiet && !u.quietRide) "조용히 가기" else ""}${if (lines.size > 1) "·" else ""}${if (daeri && !u.daeri) "대리 운행" else ""} 풀림을 알림")
        Stage.toast(lines.joinToString(" · "), 5_000)
    }

    private val tick = object : Runnable {
        override fun run() {
            // 1초 틱 사이가 5분 넘게 비었으면 차가 잠들었다 깨어난 것(시동을 껐다 켬)으로 본다. 30분 안이면 같은 운행, 넘으면 새 운행(woke)
            val t = SystemClock.elapsedRealtime()
            if (lastTick > 0 && t - lastTick > SLEEP_GAP_MS) woke(t - lastTick)
            lastTick = t
            // 마지막으로 깨어 있던 때(시동을 끈 지 몇 분 만에 다시 탔는지 알려고). 30초마다 적는다.
            // 잠든 시간을 셀 수 있게 부팅 뒤 시간(잠 포함)과 깨어 있던 시간(잠 빼고)도 함께(checkNewRide)
            val wall = System.currentTimeMillis()
            if (wall - aliveSaved > 30_000) {
                aliveSaved = wall
                getSharedPreferences("ride", MODE_PRIVATE).edit().putLong("alive_at", wall).putLong("alive_el", t).putLong("alive_up", SystemClock.uptimeMillis())
                    .putInt("alive_boot", bootCount).apply()
            }
            val c = clock
            if (c != null) {
                c.tick()
                expireCards(c.minutes)
                hush?.tick()
                timing.tick(CarLink.state)
                // 급할 때는 119에 말할 위치를 1초마다 맞춘다(사고 안내가 메모에 적기 전에)
                // 세워 둔 첫 화면의 '급할 때' 판에도 같은 한 줄을(11/05, 위치는 차 안에서만 쓴다). place()는 같은 자리면 지난 값을 돌려줘 싸고,
                // 바뀔 때만 화면을 고친다. 달리는 동안은 비워, 다시 세웠을 때 지난 자리를 보이지 않게(검토 11/05)
                val u = Stage.ui.value
                val homeShown = u.scene == app.prend.ui.Scene.HOME && u.visible && u.parked
                if (u.scene == app.prend.ui.Scene.HOME && !u.parked && !u.demo && u.place != null) Stage.ui.value = u.copy(place = null)
                else if (u.scene.isE || homeShown) place().let { p -> if (p != Stage.ui.value.place) Stage.ui.value = Stage.ui.value.copy(place = p) }
                crash?.tick()
                talkTick()
                floor.tick(CarLink.state)
                nap?.tick(CarLink.state)
                novice?.tick(CarLink.state)
                coach?.tick(CarLink.state)
                carCare?.tick(CarLink.state)
                alleyDest?.tick(CarLink.state)
                dusk?.tick(CarLink.state)
                slowTalk?.tick(CarLink.state)
                shopVisit?.tick(CarLink.state)
                highwayStop?.tick(CarLink.state)
                care?.tick(CarLink.state, c.minutes)
                // 위치는 달리는 동안 10초마다(시속 40km 아래면 2초마다: 골목 판정에 지나온 길을 맞추려고), 세워 두면 1분마다 묻는다
                val now = System.currentTimeMillis()
                if (now - lastAsk >= if (CarLink.state.parked) 60_000 else if (CarLink.state.speedKmh < 40f) 2_000 else 10_000) { lastAsk = now; navi?.ask() }
            }
            publish(CarLink.state)
            ui.postDelayed(this, 1_000)
        }
    }

    private val commands = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val cmd = intent.getStringExtra("cmd") ?: return
            // 들은 말·좌표·주소가 든 명령은 앞머리와 글자 수만 남긴다
            val masked = listOf("heard_", "geo_", "alleydest_", "road_probe_").firstOrNull { cmd.startsWith(it) && cmd != "geo_off" && cmd != "alleydest_off" }
            Prend.log("· 운행 시험 명령: ${masked?.let { "$it(${cmd.length - it.length}자)" } ?: cmd}")
            val c = clock ?: return
            when {
                cmd == "clock60" -> c.speed = 60.0
                cmd == "clock1" -> c.speed = 1.0
                cmd.startsWith("drive_") -> { c.set(cmd.removePrefix("drive_").toIntOrNull() ?: 0); care?.reset() }
                cmd == "rest" -> care?.queueRest()
                cmd == "where" -> where()
                // 시험 위치: geo_위도_경도_진행방위, geo_off
                cmd == "geo_off" -> navi?.pretendOff()
                cmd.startsWith("geo_") -> cmd.removePrefix("geo_").split("_").mapNotNull { it.toDoubleOrNull() }.takeIf { it.size == 3 }?.let { (la, lo, h) -> navi?.pretend(la, lo, h) }
                // 사람 목소리 없이 '들은 말'을 넣는다(듣는 중일 때만)
                cmd.startsWith("heard_") -> Stage.onHeard(cmd.removePrefix("heard_"), true)
                // 배너 버튼을 누른 것처럼(call, yes:REST, no:REST …)
                cmd.startsWith("banner_") -> onBanner(cmd.removePrefix("banner_"))
                // 시동 질문을 지금 살핀다(시동을 다시 건 것처럼)
                cmd == "start" -> care?.newRide("시험")
                // 시동을 끈 지 몇 분 만에 다시 탄 것처럼(20분 안이면 시트가 아직 식지 않았다고 본다)
                cmd.startsWith("start_off_") -> care?.newRide("시험", cmd.removePrefix("start_off_").toIntOrNull())
                // 쓸수록 기억: 모두 잊기, 지금 기억 기록에 남기기
                cmd == "memo_forget" -> memory?.forget(null)
                cmd == "memo_dump" -> Prend.log("· 기억: ${memory?.habits}")
                // 기억 화면 열기, 그 안의 '그 밖에' 열기·닫기(세워 둔 동안만), 듣고 보기 셋의 지금 값
                cmd == "memo_open" -> Stage.openMemo()
                cmd == "memo_more" -> Stage.openMemoMore()
                cmd == "memo_more_off" -> Stage.openMemoMore(false)
                cmd == "listen_dump" -> { slowTalk?.dump(); dusk?.dump(); Stage.ui.value.let { Prend.log("· 듣고 보기 화면: 천천히 ${it.slowTalk}(${it.slowWhy}), 넉넉히 ${it.longWait}(${it.waitWhy}), 해 지기 전에 ${it.dusk}(${it.duskWhy}), 대답 창 ${Stage.answerMs / 1000}초") } }
                cmd.startsWith("memo_auto_") -> runCatching { Memory.Kind.valueOf(cmd.removePrefix("memo_auto_")) }.getOrNull()?.let { memory?.pretendAuto(it) }
                // 시험: 그 순간을 지금 배운 것처럼(memo_learn_JAM·PICK·HOUR·SPOT·DAERI): 알림, 기억 화면 '바뀜'
                cmd.startsWith("memo_learn_") -> runCatching { Memory.Kind.valueOf(cmd.removePrefix("memo_learn_")) }.getOrNull()?.let { k -> memory?.pretendLearn(k)?.let { Stage.toast(it.text, 4_000) } }
                // 시험(감사 F107): PREND 곡 위에 한마디를 틀어 말하는 동안 곡이 −8dB 내려가는지(소리 엔진 기록의 '말' 값, rec_on 녹음)
                cmd == "speak_duck" -> speaker?.speak("voice/rest_2h.wav", android.media.AudioAttributes.USAGE_ASSISTANT, "말하는 동안 낮추기 시험")
                // 쪽잠: 끝나기 n초 전으로 당기기, 쉼터를 경유지로 넣은 것처럼(다음에 세우면 쪽잠 권함)
                cmd.startsWith("nap_skip_") -> cmd.removePrefix("nap_skip_").toIntOrNull()?.let { nap?.skipTo(it) }
                cmd == "rest_accepted" -> care?.pretendRestAccepted()
                // 등받이: recline_ROW_1_RIGHT_20(0 끝까지 젖힘 ~ 100 세움), 도착까지 남은 시간: eta_600, eta_off
                cmd == "recline_dump" -> Prend.log("· 등받이 위치 ${CarLink.state.recline} 범위 ${CarLink.hubOrNull?.reclineRange}")
                cmd.startsWith("recline_") -> cmd.removePrefix("recline_").let { r -> r.substringAfterLast('_').toIntOrNull()?.let { CarLink.hubOrNull?.pretendRecline(r.substringBeforeLast('_'), it) } }
                cmd == "eta_off" -> navi?.pretendEta(null)
                cmd.startsWith("eta_") -> cmd.removePrefix("eta_").toIntOrNull()?.let { navi?.pretendEta(it) }
                // 시험 에어백(에뮬레이터에는 전개 속성이 없다)과 통화 대역(블루링크 상담원 통화 중에는 말하지 않는지)
                cmd == "airbag" -> CarLink.hubOrNull?.pretendAirbag(true)
                cmd == "airbag_reset" -> CarLink.hubOrNull?.pretendAirbag(false)
                cmd == "call_on" -> crash?.fakeCall(true)
                cmd == "call_off" -> crash?.fakeCall(false)
                // 접촉 사고 물음을 지금(급제동·비상등 없이), 가족 정보: 지금 묻기·지우기(다음 운행에 처음처럼), 사고 메모를 하루 전으로
                cmd == "bump" -> { Stage.ui.value = Stage.ui.value.copy(bumpJolt = false); care?.queueBump() }
                cmd == "kids_ask" -> care?.queueKids()
                cmd == "kids_forget" -> baby?.forget()
                // 위치 보내기(사용자 결정 11/06): 처음 알림 카드를 지금 줄에(세워 둔 채 문이 닫히고 10초 뒤 뜬다), 처음 설치한 상태로, 켜고 끄기
                cmd == "locsend_ask" -> care?.queueGeo()
                cmd == "locsend_reset" -> app.prend.ride.GeoShare.reset()
                cmd == "locsend_on" || cmd == "locsend_off" -> app.prend.ride.GeoShare.set(cmd == "locsend_on", "시험")
                // 먼저 묻기(사용자 결정 11/06): asks_HOUR_on·asks_JAM_off…, asks_all_on·asks_all_off(기억 화면에서 누른 것과 같다)
                cmd.startsWith("asks_") -> cmd.removePrefix("asks_").let { r ->
                    val key = r.substringBeforeLast('_')
                    val on = r.substringAfterLast('_') == "on"
                    (if (key == "all") Memory.ASK_KEYS else listOf(key)).forEach { Stage.onAsks?.invoke(it, on) }
                }
                // 다른 음성 비서가 말하는 것처럼(검토 11/06): assist_fake는 6초, assist_fake_10은 10초. 그동안 먼저 꺼내는 말이 미뤄져야 한다
                cmd.startsWith("assist_fake") -> fakeAssistant((cmd.removePrefix("assist_fake").removePrefix("_").toLongOrNull() ?: 6) * 1000)
                cmd == "after_yesterday" -> after?.pretendYesterday()
                cmd == "insurer_off" -> after?.insurer(null)
                cmd == "probe_hazard" -> CarLink.hubOrNull?.probeHazard()
                cmd == "probe_navi" -> navi?.probe()
                cmd == "probe_more" -> CarLink.hubOrNull?.probeMore()
                cmd == "probe_maint" -> CarLink.hubOrNull?.probeMaint()
                // 차 돌봄 시험: care_km_<km>(주행거리 흉내, care_km_off는 차의 값), care_due(다음 정차에 카드), care_reset, care_dump, care_open_<항목>
                cmd.startsWith("care_km_") -> carCare?.pretendKm(cmd.removePrefix("care_km_").toFloatOrNull())
                // 해 지기 전에 시험: dusk_sun_<분>은 해 지는 시각을 지금부터 n분 뒤로(dusk_sun_off로 계산), dusk_days_<n>은 낮 운행 n번, dusk_dump
                cmd == "dusk_sun_off" -> dusk?.fakeSunsetMin = null
                cmd.startsWith("dusk_sun_") -> dusk?.fakeSunsetMin = cmd.removePrefix("dusk_sun_").toIntOrNull()
                cmd.startsWith("dusk_days_") -> cmd.removePrefix("dusk_days_").toIntOrNull()?.let { dusk?.pretendDayRides(it) }
                cmd == "dusk_dump" -> dusk?.dump()
                // 출발 전 날씨 흉내: dusk_wx_snow·dusk_wx_rain·dusk_wx_rainsnow, dusk_wx_off면 실제로 묻기
                cmd == "dusk_wx_off" -> dusk?.fakeWx = null
                cmd.startsWith("dusk_wx_") -> dusk?.fakeWx = cmd.removePrefix("dusk_wx_")
                cmd == "slow_dump" -> slowTalk?.dump()
                cmd == "shop_test" -> shopVisit?.pretendShop()
                cmd == "slow_apart" -> slowTalk?.pretendApart()
                cmd == "care_due" -> carCare?.forceNext()
                cmd == "care_reset" -> carCare?.reset()
                cmd == "care_dump" -> carCare?.dump()
                // 골목 목적지 시험: alleydest_<위도>_<경도>_<주소(빈칸은 _)>로 목적지 자리를 넣는다(alleydest_off로 지움). alley_again은 같은 목적지를 다시 본다
                cmd == "alleydest_off" -> navi?.pretendDestOff()
                cmd.startsWith("alleydest_") -> cmd.removePrefix("alleydest_").split("_", limit = 3).let { p -> val la = p.getOrNull(0)?.toDoubleOrNull(); val lo = p.getOrNull(1)?.toDoubleOrNull(); if (la != null && lo != null) navi?.pretendDestAt(la, lo, "시험 목적지", p.getOrNull(2).orEmpty().replace("_", " ")) }
                cmd == "alley_again" -> alleyDest?.again()
                cmd.startsWith("care_open") -> Stage.openCare(cmd.removePrefix("care_open").removePrefix("_"))
                // 초보 돕기 시험: 핸들 각도 steer_-450, 다음 회전 turn_TURN_TOLL_120, 목적지 dest_○○주유소·dest_off, 경로 재탐색, 김서림 판단 10초
                cmd.startsWith("steer_") -> cmd.removePrefix("steer_").toFloatOrNull()?.let { CarLink.hubOrNull?.pretendSteer(it) }
                cmd.startsWith("turn_") -> cmd.removePrefix("turn_").let { r -> r.substringAfterLast('_').toDoubleOrNull()?.let { navi?.pretendTurn(r.substringBeforeLast('_'), it) } }
                cmd == "dest_off" -> navi?.pretendDest(null)
                cmd.startsWith("dest_") -> navi?.pretendDest(cmd.removePrefix("dest_"))
                cmd == "reroute" -> navi?.pretendReroute()
                cmd == "fog_fast" -> coach?.fogFast()
                // 우회전 한 줄을 처음처럼(목소리 횟수·카드 한 번·[알고 있어요]·이번 운행에 한 것). turn_TURN_RIGHT_400과 comfy_light로 본다
                cmd == "right_reset" -> coach?.rightReset()
                // 시험: 첫 화면으로(급할 때 화면 닫기), PREND 곡 틀기·멈추기
                cmd == "home" -> Stage.go(app.prend.ui.Scene.HOME)
                cmd == "music_play" -> playCalm()
                cmd == "music_pause" -> stopCalm()
                // 시험: "빗소리 깔아 줘"·"빗소리 꺼 줘"를 말한 것처럼(곡이 없으면 잔잔한 곡도), 조용히 가기 켜고 끄기
                cmd == "rain_on" -> rain(true)
                cmd == "rain_off" -> rain(false)
                cmd == "quiet_on" -> Stage.quietRide(true)
                cmd == "quiet_off" -> Stage.quietRide(false)
                // 시험(말하기 전 기척): 무드등 밝기 읽기·바꾸기(ambient_15, 색은 그대로), 기척 차임 듣기
                cmd == "ambient_read" -> CarLink.hubOrNull?.readAmbient { b, c -> Prend.log("· 시험 무드등: 밝기 $b 색 ${c?.let { "#%06X".format(it) }}") }
                cmd.startsWith("ambient_") -> cmd.removePrefix("ambient_").toIntOrNull()?.let { n ->
                    val hub = CarLink.hubOrNull
                    hub?.readAmbient { _, c -> hub.setAmbient(n, c ?: 0xFFFFFF) { Prend.log("· 시험 무드등 밝기 $n 결과 $it") } }
                }
                cmd == "chime_soft" -> runCatching { Chime.play(soft = true) }
                cmd.startsWith("blink_") -> CarLink.hubOrNull?.pretendSignal(cmd.removePrefix("blink_"))
                // 시험: [프렌드]를 부르면 보일 말(지금 상태로), 한 시간 물음의 첫 버튼, 이번 운행에 거절한 물음과 주차 자리 알림
                cmd == "hints" -> Prend.log("· 시험: 프렌드 말 보기 ${Stage.hints(Stage.ui.value)}")
                cmd == "hour_offer" -> care?.offerDump()
                cmd == "declined" -> care?.declinedDump()
                // 시험: 중계 서버를 PC에 띄운 것으로(배포 전 시험, 디버그 빌드만 http 허용), 원래대로
                cmd == "relay_pc" -> app.prend.net.Relay.pretendBase("http://10.0.2.2:8787")
                // 8787을 다른 개발 서버가 쓰고 있을 때: relay_pc_<포트>
                cmd.startsWith("relay_pc_") -> cmd.removePrefix("relay_pc_").toIntOrNull()?.let { app.prend.net.Relay.pretendBase("http://10.0.2.2:$it") }
                cmd == "relay_cloud" -> app.prend.net.Relay.pretendBase(null)
                // 시험: 공조 전원 켜고 끄기, 바람 세기(쪽잠 깨우기의 바람)
                cmd == "hvac_on" -> CarLink.hubOrNull?.setHvacPower(true) { Prend.log("· 시험 공조 켜기 결과 $it") }
                cmd == "hvac_off" -> CarLink.hubOrNull?.setHvacPower(false) { Prend.log("· 시험 공조 끄기 결과 $it") }
                cmd == "fan_read" -> CarLink.hubOrNull?.readFan { Prend.log("· 시험 바람 읽기: $it") }
                cmd.startsWith("fan_") -> cmd.removePrefix("fan_").toIntOrNull()?.let { s -> CarLink.hubOrNull?.setFan(s, face = false) { Prend.log("· 시험 바람 $s 결과 $it") } }
                // 시험: 서울역으로 경로를 넣는다(도착까지 남은 시간 이벤트를 보려고)
                cmd == "route_test" -> navi?.goVia(37.5547, 126.9707, "서울역", "서울 중구 한강대로 405")
                // 정체 한마디를 지금(막힘 판단 없이), 또는 막힘 판단 창을 10분에서 1분으로
                cmd == "jam" -> care?.queueJam()
                cmd == "jam_fast" -> { jamHelp?.windowMs = 60_000; Prend.log("· 시험: 정체 판단 창 1분") }
                // 같이 고르기를 지금 묻기(때 판단 없이), 바로 열기(밥집·카페)
                cmd == "pick" -> care?.queuePick()
                cmd == "pick_food" -> Stage.openPick("food")
                cmd == "pick_cafe" -> Stage.openPick("cafe")
                // 한 시간·대리 운행·주차 자리·이야기 비켜 서기 물음을 지금(ask_hour·ask_daeri·ask_spot·ask_talk), 차를 어둡게(night_on·night_off)
                cmd.startsWith("ask_") -> care?.queueTest(cmd.removePrefix("ask_"))
                cmd == "night_on" -> CarLink.hubOrNull?.pretendNight(true)
                cmd == "night_off" -> CarLink.hubOrNull?.pretendNight(false)
                // 이야기에 비켜 서기: 켜고 끄기, 동승자가 있는 것처럼, 마이크 대신 시험 파일(16kHz WAV, 앱 폴더에 run-as로 넣는다), 판정 없이 이야기 시작·끝
                cmd == "talk_on" -> setTalk(true)
                cmd == "talk_off" -> setTalk(false)
                cmd == "talk_undecided" -> { talkPrefs.edit().clear().apply(); Stage.ui.update { it.copy(talkOn = false) }; Prend.log("· 시험: 이야기 비켜 서기 처음 상태") }
                cmd == "talk_others" -> { testOthers = !testOthers; Prend.log("· 시험: 동승자 ${if (testOthers) "있음" else "없음"}") }
                cmd.startsWith("talk_file_") -> talk?.feedFile(java.io.File(filesDir, cmd.removePrefix("talk_file_") + ".wav"))
                cmd == "talk_fake_on" -> onTalking(true)
                cmd == "talk_fake_off" -> onTalking(false)
                // 급할 때 시험: Emergency를 누른 것처럼(E 첫 화면 + 바로 듣기), 119 전화 걸기 화면, E 동안 늦은 물음(가족 정보·김서림·쪽잠) 넣기,
                // E 소리 상태(첫 화면 포커스·다음 곡 서서히), SOS 띠, 고속도로 사고의 견인 걸음(사고 안내 화면에서), 기어(gear_P·R·N·D: 달리는 동안의 한 줄 판)
                cmd == "emergency" -> Stage.emergency()
                cmd == "dial_119" -> Stage.dial("119")
                cmd == "e_late_asks" -> { care?.queueKids(); care?.queueFog(); care?.queueNap(); Prend.log("· 시험: 늦은 물음 넣음(급할 때가 끝나면 버려져야 함)") }
                cmd == "e_sound" -> Prend.log("· 시험: E 첫 화면 포커스 ${if (crash?.holding == true) "쥠" else "없음"} · 다음 곡 서서히 ${Bridge.director?.softNext}")
                cmd == "e_sos" -> Stage.ui.update { it.copy(sos = true) }
                cmd == "e_tow" -> Stage.ui.update { it.copy(highway = true, minor = false, rescue = false, step = 4) }
                cmd.startsWith("gear_") -> mapOf("P" to android.car.VehicleGear.GEAR_PARK, "R" to android.car.VehicleGear.GEAR_REVERSE, "N" to android.car.VehicleGear.GEAR_NEUTRAL, "D" to android.car.VehicleGear.GEAR_DRIVE)[cmd.removePrefix("gear_")]
                    ?.let { CarLink.hubOrNull?.pretendGear(it) }
                // 화면 시험: 달리는 동안의 첫 화면(기어 D + 첫 화면 + 동승자 있음: 곡 판과 연속 운전 판, [잘래요]). gear_P로 돌아간다
                cmd == "face_drive" -> {
                    testOthers = true
                    CarLink.hubOrNull?.pretendGear(android.car.VehicleGear.GEAR_DRIVE)
                    Stage.go(app.prend.ui.Scene.HOME)
                    Prend.log("· 시험: 달리는 동안의 첫 화면")
                }
                // 화면 시험: 기어 캡슐이 미끄러지는지(P → D → R → P → E 첫 화면 → 첫 화면, 1.5초씩). E는 듣지 않고 화면만 연다
                cmd == "gear_walk" -> {
                    val g = android.car.VehicleGear.GEAR_PARK
                    listOf(
                        0L to { CarLink.hubOrNull?.pretendGear(android.car.VehicleGear.GEAR_DRIVE) },
                        1_500L to { CarLink.hubOrNull?.pretendGear(android.car.VehicleGear.GEAR_REVERSE) },
                        3_000L to { CarLink.hubOrNull?.pretendGear(g) },
                        4_500L to { Stage.go(app.prend.ui.Scene.EMERGENCY) },
                        6_000L to { Stage.go(app.prend.ui.Scene.HOME) },
                    ).forEach { (at, step) -> ui.postDelayed({ step(); publish(CarLink.state) }, at) }
                    Prend.log("· 시험: 기어 캡슐 P→D→R→P→E→P")
                }
                // 화면 시험: PREND가 먼저 묻는 카드가 떠오르는지(한 시간 물음. 세워 두면 아래에서 떠오르고, 달리면 옅게 나타나는 한 줄 카드)
                cmd == "card_rise" -> care?.queueTest("hour")
                // 화면 시험: E 걸음을 앞뒤로 넘기기(옆으로 넘어가는지). 사고·아플 때·응급처치 화면에서
                cmd == "step_next" || cmd == "step_prev" -> Stage.ui.value.let { s ->
                    if (s.scene.isE && s.scene != app.prend.ui.Scene.EMERGENCY) Stage.step(if (cmd == "step_next") 1 else -1, app.prend.ui.Guide.steps(s).size)
                }
                // 화면 시험: 같이 고르기 칸에 긴 이름의 시험 가게를 넣는다(중계 서버 없이 상호·지점 나눔과 '블로그 글'을 보려고. 디버그 빌드만)
                cmd == "pick_fake" -> {
                    val fake = listOf(
                        app.prend.ui.Place("시험가게이름이아주긴손칼국수 시험동직영점", "칼국수", "", 0.0, 0.0, 468, 320),
                        app.prend.ui.Place("시험 분식", "분식", "", 0.0, 0.0, 12, 850),
                        app.prend.ui.Place("시험카페 시험역점", "카페", "", 0.0, 0.0, 1_204, 1_400),
                        app.prend.ui.Place("시험국밥 본점", "국밥", "", 0.0, 0.0, null, 2_300),
                    )
                    Stage.ui.update { it.copy(scene = app.prend.ui.Scene.PICK, pick = app.prend.ui.Pick(kind = "food", area = "시험", items = fake, chosen = 0)) }
                    Prend.log("· 시험: 같이 고르기 시험 가게 ${fake.size}곳")
                }
                // 응급처치 카드 바로 열기(aid_CPR·CHOKE·INFANT·SEIZURE), 경련 카드를 연 시각을 앞당기기(aid_age_초)
                cmd.startsWith("aid_age_") -> cmd.removePrefix("aid_age_").toLongOrNull()?.let { s -> Stage.ui.value = Stage.ui.value.let { it.copy(aidSince = it.aidSince - s * 1000, szSince = if (it.aid == app.prend.ui.Aid.SEIZURE) it.aidSince - s * 1000 else it.szSince) } }
                cmd.startsWith("aid_") -> runCatching { app.prend.ui.Aid.valueOf(cmd.removePrefix("aid_")) }.getOrNull()?.let { Stage.openAid(it) }
                // 시험 기온: 에뮬레이터의 바깥 기온은 25°C로 고정이라 시연 값을 넣는다(영상에서 밝힌다). temp_off로 끈다
                cmd == "temp_off" -> { testTemp = null; Prend.log("· 시험 기온 끔") }
                cmd.startsWith("temp_") -> cmd.removePrefix("temp_").toFloatOrNull()?.let { testTemp = it; Prend.log("· 시험 기온 ${it}°C") }
                // 같은 운행 잇기(감사 F02): 프로세스가 다시 뜬 것처럼 운행 상태를 적고 비운 뒤 다시 읽기,
                // 차가 n분 잠들었다 깬 것처럼(sleep_gap_20: 5분 넘으면 새 운행, 15분 넘으면 연속 운전 0)
                cmd == "keep_reload" -> care?.reloadTest()
                cmd.startsWith("sleep_gap_") -> cmd.removePrefix("sleep_gap_").toLongOrNull()?.let { m -> c.pretendGap(m * 60_000); lastTick -= m * 60_000 }
                // 바로 멈추기(감사 F47·F14): 한 시간 물음을 건네게 하고 말하는 중(stop_test_blink·stop_test_R)이나 대답을 듣는 중(stop_test_answer)에
                // 깜빡이·R을 넣어 멈추기까지 몇 ms인지 적는다. D에서 하고, 끝나면 blink_OFF·gear_D로 되돌린다
                cmd.startsWith("stop_test_") -> stopTest(cmd.removePrefix("stop_test_"))
                // 다른 소리가 포커스를 4초 가져간 것처럼(감사 F112): focus_nav 길 안내(말을 낮춤), focus_call 통화(말을 끊고 줄에 되돌림)
                cmd == "focus_nav" || cmd == "focus_call" -> fakeFocus(cmd.removePrefix("focus_"))
                // 아이 탑승 −3dB가 나중에 뜬 음악 서비스에도 걸리는지(감사 F09): 아이 탑승을 켜고(기억에는 남기지 않음) 곡을 틀어 3초 뒤 소리 상태를 적는다
                cmd == "baby_music" -> {
                    baby?.restore(true, baby?.asked == true, "시험")
                    playCalm()
                    ui.postDelayed({ Prend.log("· 시험: 소리 상태 ${Bridge.director?.describe() ?: "음악 서비스 없음"}") }, 3_000)
                }
                // 잘래요 켜고 끄기(달리는 동안, 감사 F72·F73: 토스트·곡 부제, 그동안 김서림·초보·길 한마디를 하지 않는지)
                cmd == "sleep_on" -> Stage.sleep(true)
                cmd == "sleep_off" -> Stage.sleep(false)
                // P에서 운전석 문을 연 것처럼 마디 끝에서 내려앉기(길어야 한 마디, 별첨 4 §2-3)
                cmd == "arrive" -> Bridge.director?.arriveNow()
                // 편한 때(감사 F45): 60초 동안 신호 대기·안정 주행으로 보기, 흉내 끄기, 지금 판정과 기다리는 말 보기,
                // 기다림 상한을 30초로(comfy_fast)·5분으로(comfy_slow). 줄에 말 넣기는 ask_hour·jam·pick
                cmd == "comfy_light" -> timing.pretend(Timing.LIGHT)
                cmd == "comfy_cruise" -> timing.pretend(Timing.CRUISE)
                cmd == "comfy_off" -> timing.pretend(null)
                cmd == "comfy_dump" -> { timing.dump(CarLink.state); care?.comfyDump(); floor.dump() }
                cmd == "floor_dump" -> { floor.dump(); timing.dump(CarLink.state) }
                cmd == "floor_fast" -> floor.fast(true)
                cmd == "floor_slow" -> floor.fast(false)
                // 이번 운행에 먼저 꺼낸 수를 n으로(floor_firsts_6: 한 운행 여섯 번에 닿은 것처럼, floor_firsts_0: 되돌림)
                cmd.startsWith("floor_firsts_") -> cmd.removePrefix("floor_firsts_").toIntOrNull()?.let { floor.firstsTo(it) }
                cmd == "narrow_on" -> timing.narrow(true)
                cmd == "narrow_off" -> timing.narrow(false)
                cmd.startsWith("limit_") -> cmd.removePrefix("limit_").toIntOrNull()?.let { navi?.pretendLimit(it) }
                cmd == "road_alley" -> road?.pretend(app.prend.ride.Road.Kind.ALLEY)
                cmd == "road_main" -> road?.pretend(app.prend.ride.Road.Kind.MAIN)
                // 도로 등급 고속국도 흉내(고속도로 판단 '확실', 검토 11/07). road_main·road_alley는 '다른 길'로 판단에 든다
                cmd == "road_highway" -> road?.pretendHighway()
                cmd == "road_off" -> road?.pretend(null)
                // road_probe_위도1_경도1_위도2_경도2: 두 자리 사이를 지나온 길처럼 중계 서버에 물어 등급만 기록(좌표는 남기지 않음)
                cmd.startsWith("road_probe_") -> cmd.removePrefix("road_probe_").split('_').mapNotNull { it.toDoubleOrNull() }.takeIf { it.size == 4 }?.let { (a, b, c, d) -> road?.probe(a, b, c, d) }
                cmd == "comfy_fast" -> care?.comfyFast(true)
                cmd == "comfy_slow" -> care?.comfyFast(false)
                // 젖은 길 한 줄: 와이퍼 1분을 5초로(wet_fast)·되돌림(wet_slow), 오늘 한 날·[그만 알려요]·와이퍼 셈 지우기(wet_reset).
                // 숫자는 limit_80과 comfy_light로 본다
                cmd == "wet_fast" -> novice?.fast(true)
                cmd == "wet_slow" -> novice?.fast(false)
                cmd == "wet_reset" -> novice?.reset()
                // 다른 앱 음악 위(감사 F48·F60): 흐르는 것처럼·없는 것처럼·실제 재생대로, 말 앞 알림음 듣기
                cmd == "music_other_on" || cmd == "music_other_off" || cmd == "music_other_real" -> {
                    jamHelp?.pretendOther = when (cmd) { "music_other_on" -> true; "music_other_off" -> false; else -> null }
                    Prend.log("· 시험: 다른 앱 음악 ${jamHelp?.pretendOther?.let { if (it) "흐르는 것처럼" else "없는 것처럼" } ?: "실제 재생대로"}")
                }
                cmd == "chime_alert" -> runCatching { Chime.alert() }
                // "졸려"라고 한 것처럼(감사 F54·F55: 쉼터 카드 → "응"·[경유지로]일 때만 넣음. 세워 두면 쪽잠)
                cmd == "sleepy" -> Stage.onRest?.invoke()
                // "화장실 급해"라고 한 것처럼(화장실 카드 → "응"·[경유지로]일 때만 넣음)
                cmd == "toilet" -> Stage.onToilet?.invoke("")
                // 목소리 포커스를 받지 못한 말을 신호 대기·P까지 미루는 스위치(감사 F94①, 기본 꺼짐)
                cmd == "voice_defer_on" -> care?.deferVoice(true)
                cmd == "voice_defer_off" -> care?.deferVoice(false)
                // 내비 안내로 지금 도착한 것처럼(주차 자리는 그 뒤 10분 안에 P에 넣을 때만 알린다)
                cmd == "navi_arrived" -> navi?.pretendArrived()
                // 중계 서버 전체 4초 상한(감사 F94③): 답이 오지 않는 주소로 바꾸기(relay_cloud로 되돌림), 들은 말 없이 시험 요청 하나
                cmd == "relay_blackhole" -> app.prend.net.Relay.pretendBase("http://10.255.255.1:8787")
                cmd == "relay_post_test" -> scope.launch {
                    val t0 = SystemClock.elapsedRealtime()
                    val j = app.prend.net.Relay.post("/wait_test", org.json.JSONObject().put("test", true))
                    Prend.log("· 시험: 중계 서버 요청 ${SystemClock.elapsedRealtime() - t0}ms에 끝남(${if (j == null) "답 없음" else "답 있음"}, 4초를 넘지 않아야 함)")
                }
                // 이야기 때 바람(감사 F68): 공조 상태 읽기(전원·AUTO·성에 제거·내기 순환), 되돌린 뒤 2분 막기 풀기
                cmd == "climate_read" -> CarLink.hubOrNull?.readClimate { Prend.log("· 시험 공조 상태: $it") }
                cmd == "talk_fan_reset" -> { talkFanBackAt = 0L; Prend.log("· 시험: 이야기 바람 2분 막기 풂") }
                // 동승자의 말은 그 자리로(매력 10)·모시는 길(감사 F88): 자리에 앉은 것처럼(seat_ROW_2_LEFT_on·_off), 자리별 열선·통풍·구역 온도와 탄 사람 보기
                cmd.startsWith("seat_") -> cmd.removePrefix("seat_").let { r -> CarLink.hubOrNull?.pretendSeat(r.substringBeforeLast('_'), r.substringAfterLast('_') == "on") }
                cmd == "seats_dump" -> CarLink.hubOrNull?.let { h ->
                    Prend.log("· 시험: 앉은 자리 ${h.state.seats.filterValues { it }.keys.joinToString().ifEmpty { "없음" }} · 모시는 길 ${h.state.chauffeured} · 소리 ${Bridge.director?.occupancy?.label ?: "음악 서비스 없음"}" +
                        " · 같이 고르기 물을 때 ${together?.shouldAsk(h.state)} · 자리별 열선 ${h.heatAt} 통풍 ${h.ventAt} · 구역 온도 ${h.tempZones.values} · 좌석 값 ${h.seatMeaning}")
                }
                // 멀미(매력 10): "애가 멀미해"라고 한 것처럼(바깥 공기·얼굴 쪽 바람·곡 −3dB, E로 가지 않음)
                cmd == "motion" -> care?.motion()
                // 대리 운행(매력 13·감사 F10~F12·F52·F117·F164) 켜고 끄기: 말·버튼으로 켠 것과 같다
                cmd == "daeri_on" -> Stage.daeri(true)
                cmd == "daeri_off" -> Stage.daeri(false)
                // 쪽잠·잘래요가 사람이 바꾼 무드등·바람을 건드리지 않는 중인가(감사 F109). 사람이 바꾼 것처럼은 ambient_<밝기>로
                cmd == "nap_hands" -> nap?.handsDump()
                // "이 노래 뭐야?"·"아까 그 노래"(감사 F89), 지금 곡·바로 전 곡을 아는지(제목은 남기지 않는다)
                cmd == "song_what" -> songWhat(false)
                cmd == "song_before" -> songWhat(true)
                cmd == "song_dump" -> Prend.log("· 시험: 지금 곡 ${app.prend.music.NowPlaying.now?.let { if (it.prend) "PREND 곡" else "다른 앱 곡" } ?: "모름"} · 바로 전 곡 ${app.prend.music.NowPlaying.before?.let { if (it.prend) "PREND 곡" else "다른 앱 곡" } ?: "모름"}")
                // 알아서 한 일 되돌리기(감사 F77 ②): "되돌려"라고 한 것처럼. 정체 "응"을 두 번 해 둔 것처럼(① 민트 줄 보기)
                cmd == "undo" -> care?.undo()
                cmd == "memo_jam2" -> memory?.pretendYes(Memory.Kind.JAM, Memory.AUTO_AFTER - 1)
                // 인터넷이 없을 때 병원(감사 F40): 중계 서버를 바로 끊기는 주소로(relay_cloud로 되돌림), 끊긴 채 아플 때 화면 열기
                // (먼저 geo_37.5665_126.9780_0처럼 위치를 넣는다), 지금 위치에서 앱 안 목록이 고를 곳 보기(거리·분류만)
                cmd == "relay_down" -> app.prend.net.Relay.pretendBase("https://127.0.0.1:9")
                cmd == "hosp_offline" -> { app.prend.net.Relay.pretendBase("https://127.0.0.1:9"); Stage.go(app.prend.ui.Scene.ILLNESS) }
                cmd == "hosp_book" -> crash?.bookDump()
                // 앱 정보 화면(감사 F124·F125): 세워 둔 동안 기억 화면 안에서 열기·닫기
                cmd == "about" -> Stage.openAbout()
                cmd == "about_off" -> Stage.openAbout(false)
                // PT 대본의 시연 버튼(감사 F127, 별첨 v9 시연과 PT). 시연 패널이 부른다
                cmd.startsWith("pt_") -> ptScene(cmd.removePrefix("pt_"), c)
            }
            publish(CarLink.state)
        }
    }

    /**
     * PT 대본의 시연 버튼(감사 F127, 기획안 v10 별첨 B). 디버그·PT 빌드만(시험 명령 수신기와 같다).
     * - e_ready(①): 장면마다 같은 출발점(PT 기준 값, ptReady). ②③④도 먼저 이것을 부르므로 단추 하나로 장면이 시작된다(검토 11/06 10)
     * - highway(②): ① 뒤에 고속도로 위 위치(①이 시험 위치를 지우므로 그 뒤에 넣는다)
     * - bluelink(③): ① 뒤에 블루링크 상담원 통화 흉내 → 에어백(①이 에어백을 '안 터짐'으로 되돌려 두어 몇 번을 눌러도 '터짐'으로 바뀐다) → 8초 뒤 통화 끝
     * - swallow(④): ① 뒤에 연속 운전 1시간 59분 50초(달리면 10초 뒤 두 시간: 쉼터 말이 나오려는 때)
     * - toilet(⑤): ① 뒤에 수원 시내 도로 위치(화장실 찾기: 앞쪽·같은 쪽 주유소 한 곳을 경유지로)
     * - clean: 시험 값을 모두 끈다(에어백·통화·위치·사고 메모·시연 값). 장면이 끝난 뒤가 아니라 PT를 마칠 때
     * - 옛 장면(pair·basement·night_kid·drive)과 values_on·values_off는 시연 영상·시험용으로 남긴다
     * 자리는 콘솔 값이 들어오면 그 값으로 바뀐다(콘솔과 같은 값을 넣어 두는 것)
     */
    private fun ptScene(what: String, c: RideClock) {
        val hub = CarLink.hubOrNull
        when (what) {
            "pair" -> { care?.newRide("시연"); ptSeats("ROW_1_RIGHT"); hub?.pretendNight(false); playCalm() }
            "basement" -> { care?.newRide("시연"); ptSeats(); hub?.pretendNight(true); navi?.pretendArrived(); playCalm() }
            "night_kid" -> { care?.newRide("시연"); ptSeats("ROW_2_LEFT"); hub?.pretendNight(true); playCalm() }
            "drive" -> { c.set(119, 50); care?.reset() }
            "values_on" -> Timing.ptValues = true
            "values_off" -> Timing.ptValues = false
            "e_ready" -> ptReady(c)
            // 고속도로 위(수도권제1순환선 이정 4.62km, 앱 안 도로공사 자료의 점. 진행 방위 0). 에뮬레이터 내비 위치가 판교에 고정이라 위치만 시험 값.
            // 화면의 위치 줄은 "수도권제1순환고속도로 퇴계원 방향 4.6km 지점"(place, 방향은 앞쪽 성남졸음쉼터의 방향 이름).
            // 고속도로 판단은 이 시험 위치를 쓰는 동안 '확실'(콘솔 속도·도로 등급 없이도 장면이 전과 같게, 검토 11/07 멋대로 판단). 리허설에서 누른 정차 카드 [괜찮아요]도 지운다
            "highway" -> { ptReady(c); navi?.pretend(37.432153, 127.123492, 0.0); ptHighwayAt = navi?.last; highwayStop?.forgetTest() }
            // 블루링크 상담원 통화를 흉내 낸 뒤 에어백(에뮬레이터에 전개 값이 없다): 통화 동안 PREND는 말하지 않고(화면에는 "통화가 끝나면 이어서 안내해요") 8초 뒤 통화가 끝나면 "괜찮으세요?"
            "bluelink" -> { ptReady(c); crash?.fakeCall(true); hub?.pretendAirbag(true); ui.postDelayed(ptCallEnd, 8_000) }
            // 말 삼키기: 연속 운전 1시간 59분 50초 + 시연 값(깜빡이 뒤 2초). ①이 0으로 돌린 뒤에 맞춘다
            "swallow" -> { ptReady(c); c.set(119, 50) }
            // 화장실(사용자 "추천대로" 11/07: PT 4분 30초의 '이런 것도 있습니다' 자리): 수원 시내 도로(진행 방위 130, 앞쪽 1.4km에 주유소).
            // 발표자가 콘솔로 시속 40쯤 달리며 [프렌드]를 누르고 "애가 쉬 마렵대" → 화장실 카드 → [경유지로]. 위치만 시험 값(에뮬레이터 내비 위치 고정)
            "toilet" -> { ptReady(c); navi?.pretend(37.285150, 126.970584, 130.0) }
            "clean" -> { ui.removeCallbacks(ptCallEnd); crash?.fakeCall(false); hub?.pretendAirbag(false); navi?.pretendOff(); after?.done(); Timing.ptValues = false }
            else -> return Prend.log("· 시연 버튼: 모르는 장면 $what")
        }
        Prend.log("· 시연 버튼: $what")
    }

    /** 시연: 동승석·뒷자리를 이 자리만 앉은 것으로(운전석은 그대로) */
    private fun ptSeats(vararg on: String) = listOf("ROW_1_RIGHT", "ROW_2_LEFT", "ROW_2_CENTER", "ROW_2_RIGHT").forEach { CarLink.hubOrNull?.pretendSeat(it, it in on) }

    /** ③의 통화 흉내 끝(다시 누르거나 ①을 누르면 앞의 것을 거둔다: 앞 장면의 8초가 새 통화를 일찍 끊지 않게) */
    private val ptCallEnd = Runnable { crash?.fakeCall(false) }

    /**
     * ① 장면 준비(11/05 E 중심 PT, 검토 11/06 10·11·14): 장면마다 같은 출발점에서. 앞 리허설의 상태가 흐름과 문장을 바꾸지 않게 PT 기준 값으로 맞춘다.
     * - 시험 값 끄기(통화·에어백·위치), 사고 메모 지우기, 새 운행, 말 줄 비우기, 운전석만 앉음, 시연 값 켜기, 첫 화면.
     *   급할 때 안내는 다음 틱을 기다리지 않고 바로 끝낸다(바로 이어 누른 ③의 에어백이 지난 안내에 막히지 않게)
     * - 연속 운전 0(④를 연습한 뒤 다음 킥 도중에 '두 시간 넘게…'가 나오지 않게. 한 시간 물음 표시도 다음 틱에 처음으로 돌아간다)
     * - 가족 정보 '아이 없음'(사용자 결정 11/06: 아플 때 장면이 '운전자' 갈래로 가고 사고 걸음에 아이 한 줄이 붙지 않게)
     * - 듣고 보기 처음대로: 대답 창 5초, 늦은 대답 수 0, 천천히 말하기 끔(리허설에서 늦게 누른 버튼이 대답 창을 8초로 늘리지 않게)
     * - 곁들임 물음 기본값(모두 꺼짐), 위치 알림 카드는 '보내기'로 정한 것으로(장면 도중에 카드가 뜨지 않고, 병원·고속도로 판정이 서버를 거치게)
     * - 조용히 가기 기억 지우기(배운 칸·늘 조용히 끔. 리허설에서 배운 칸이 PT 장면 도중 '조용히' 막대와 카드를 띄우지 않게)
     * 콘솔의 문이 열려 있으면 기록에 남긴다(발표자 화면과 시연 패널 맨 위에 크게. 문은 앱이 닫을 수 없다)
     */
    private fun ptReady(c: RideClock) {
        val hub = CarLink.hubOrNull
        ui.removeCallbacks(ptCallEnd)
        crash?.fakeCall(false); hub?.pretendAirbag(false); navi?.pretendOff(); after?.done()
        app.prend.ride.GeoShare.settleForDemo()
        // 조용히 가기 기억(배운 칸·늘 조용히)도 지운다: 리허설에서 같은 요일·시간대 칸을 배우면 PT 장면 도중 '조용히' 막대와 카드가 끼어든다(검토 11/06).
        // 새 운행 앞에 두어 이번 운행도 조용히 가지 않게
        memory?.let { m -> m.alwaysQuiet = false; m.forget("QUIET") }
        baby?.setKids(false, "시연 장면 준비")
        care?.newRide("시연"); care?.reset(); ptSeats(); hub?.pretendSeat("ROW_1_LEFT", true)
        c.set(0)
        slowTalk?.forget()
        memory?.let { m -> Memory.ASK_KEYS.filter { m.asks(it) }.forEach { m.setAsks(it, false) } }
        Timing.ptValues = true
        Stage.go(app.prend.ui.Scene.HOME)
        crash?.endNow("시연 장면 준비")
        app.prend.ui.DebugActivity.openDoors(CarLink.state.doors).takeIf { it.isNotEmpty() }
            ?.let { Prend.log("✗ 시연 준비: 문이 열려 있어요(${it.joinToString()}) → 콘솔에서 닫기") }
        Prend.log("· 시연 장면 준비: 가족 정보 ${Stage.ui.value.kids?.let { if (it) "아이 있음" else "아이 없음" } ?: "모름"} · 대답 창 ${Stage.answerMs / 1000}초 · 천천히 ${if (Stage.ui.value.slowTalk) "켬" else "끔"} · 조용히 ${if (Stage.ui.value.quietRide) "켬" else "끔"} · 연속 운전 ${c.minutes}분")
    }

    /**
     * 시험(감사 F47·F14): 한 시간 물음을 건네게 하고, 말이 나오면 0.6초 뒤(blink·R) 또는 대답을 듣기 시작하고 1초 뒤(answer)
     * 깜빡이나 R을 넣어 말·듣기가 멈추기까지 몇 ms인지 적는다(0.3초 안이어야 한다)
     */
    private fun stopTest(how: String) {
        val s = speaker ?: return
        val hub = CarLink.hubOrNull ?: return Prend.log("· 시험: 차 연결 없음")
        val answer = how == "answer"
        val what = if (answer) "대답 듣기" else "말"
        val busy = { if (answer) Stage.answering else s.speaking }
        // 한 시간 물음은 편한 때를 기다린다: 시험 동안은 신호 대기로 본다(멈추는 판단은 깜빡이·R이 따로 한다)
        timing.pretend(Timing.LIGHT, 30_000)
        care?.queueTest("hour")
        val start = SystemClock.elapsedRealtime()
        var readyAt = 0L
        var cueAt = 0L
        val step = object : Runnable {
            override fun run() {
                val now = SystemClock.elapsedRealtime()
                when {
                    cueAt > 0 -> {
                        if (!busy()) return Prend.log("· 시험: ${if (how == "R") "R" else "깜빡이"} 뒤 ${now - cueAt}ms에 $what 멈춤(300ms 안이어야 함)")
                        if (now - cueAt > 3_000) return Prend.log("✗ 시험: ${if (how == "R") "R" else "깜빡이"} 뒤 3초 넘게 $what 이어짐")
                    }
                    readyAt > 0 -> {
                        if (!busy()) return Prend.log("· 시험: 넣기 전에 $what 끝남(다시 해 보세요)")
                        if (now - readyAt >= if (answer) 1_000 else 600) { cueAt = now; if (how == "R") hub.pretendGear(android.car.VehicleGear.GEAR_REVERSE) else hub.pretendSignal("LEFT") }
                    }
                    busy() -> readyAt = now
                    now - start > 20_000 -> return Prend.log("· 시험: 20초 안에 $what 시작되지 않아 그만(D에서, 조용히 가기·대리 운행이 아닐 때)")
                }
                ui.postDelayed(this, 20)
            }
        }
        ui.post(step)
    }

    /** 시험(감사 F112): 다른 앱이 포커스를 4초 쥐었다 놓는 것처럼. nav는 길 안내(낮춰 달라 → 말을 낮춤), call은 통화(잠깐 가져감 → 말을 끊음) */
    private fun fakeFocus(kind: String) {
        val am = getSystemService(android.media.AudioManager::class.java)
        val nav = kind == "nav"
        val name = if (nav) "길 안내" else "통화"
        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(if (nav) android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE else android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .build()
        val req = android.media.AudioFocusRequest.Builder(if (nav) android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK else android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs).setOnAudioFocusChangeListener { }.build()
        Prend.log("· 시험: $name 포커스 흉내 → ${am.requestAudioFocus(req)}")
        ui.postDelayed({ am.abandonAudioFocusRequest(req); Prend.log("· 시험: $name 포커스 놓음") }, 4_000)
    }

    /** PREND의 잔잔한 곡을 튼다(정체 챙김). 음악 서비스가 아직 없으면 연결해 깨운 뒤 튼다 */
    private fun playCalm() {
        Bridge.brain?.let { it.play(); return }
        val future = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        future.addListener({
            runCatching { future.get() }
                .onSuccess { c ->
                    Bridge.brain?.play() ?: run {
                        if (c.mediaItemCount == 0) c.setMediaItem(MediaItem.Builder().setMediaId(Library.tracks.first().id).build())
                        c.prepare(); c.play()
                    }
                    ui.postDelayed({ c.release() }, 3_000)
                }
                .onFailure { Prend.log("✗ 잔잔한 곡 틀기 실패 $it") }
        }, mainExecutor)
    }

    /** 정체 때 알아서 튼 곡을 "되돌리기"로 멈춘다 */
    private fun stopCalm() {
        Bridge.brain?.let { it.pause(); return }
        val future = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        future.addListener({
            runCatching { future.get() }
                .onSuccess { c -> c.pause(); ui.postDelayed({ c.release() }, 1_000) }
                .onFailure { Prend.log("✗ 잔잔한 곡 멈추기 실패 $it") }
        }, mainExecutor)
    }

    /**
     * 지금 고속도로 위인가(옛 참·거짓): 확실이거나 애매면 참(혹시 사고·사고 안내·정차 카드가 쓰던 그대로의 모양).
     * 검토 11/07 멋대로 판단: 전에는 쉼터 자료의 도로 선에서 150m 안이면 참이라 방음벽 옆 아파트도 고속도로였다. 이제 근거가 없으면 거짓
     */
    private fun onHighway(): Boolean = highwayState() != app.prend.ride.HighwayState.NO

    /** 고속도로 판단 세 갈래(확실·애매·아님). 사고 안내가 애매를 따로 다루려면 이것을 쓴다 */
    private fun highwayState(): app.prend.ride.HighwayState = highwayCall().state

    /**
     * 고속도로 판단(검토 11/07 멋대로 판단, RestAreas.judge): 도로 선에서 150m 안이고, 도로 등급(위치 보내기를 켠 차, ride/Road)·
     * 고속도로 속도로 달려와 섰나(ride/HighwayStop)·가는 방향이 노선과 맞나를 센다. 위치를 모르면 아님.
     * 시연 ②(pt_highway)가 넣은 고속도로 위 시험 위치는 확실(콘솔 속도 없이도 PT 장면이 전과 같게). 판단이나 까닭이 바뀔 때만 기록에 남긴다(좌표 없이)
     */
    private fun highwayCall(): app.prend.ride.HighwayCall {
        val w = navi?.last
        val call = when {
            w == null -> app.prend.ride.HighwayCall(app.prend.ride.HighwayState.NO, "위치 모름")
            w == ptHighwayAt -> app.prend.ride.HighwayCall(app.prend.ride.HighwayState.SURE, "시연 위치", spot = spotOf(w))
            else -> app.prend.ride.RestAreas.judge(spotOf(w), w.heading, road?.highwayAt(w.lat, w.lon), highwayStop?.cameFast() == true)
        }
        val note = "${call.state.label}(${call.why})"
        if (note != highwayNote) { highwayNote = note; Prend.log("· 고속도로 판단: $note") }
        return call
    }

    /** 마지막으로 남긴 고속도로 판단(바뀔 때만 남기려고) */
    @Volatile private var highwayNote: String? = null

    /** 시연 ②가 넣은 시험 위치(그 위치를 쓰는 동안만 고속도로 확실. 다른 시험 위치나 실제 위치로 바뀌면 저절로 끝난다) */
    @Volatile private var ptHighwayAt: app.prend.car.Where? = null

    /** 마지막으로 맞춘 위치와 그 고속도로 자리(같은 위치면 도로 점 4천여 개를 다시 훑지 않는다. 급할 때·세워 둔 첫 화면은 1초마다 묻는다) */
    @Volatile private var spotAt: Pair<app.prend.car.Where, app.prend.ride.RoadSpot?>? = null

    private fun spotOf(w: app.prend.car.Where): app.prend.ride.RoadSpot? {
        spotAt?.let { (at, s) -> if (at == w) return s }
        // 쉼터 자료를 다 읽기 전이면 맞추지 않고, 남겨 두지도 않는다(다 읽은 뒤 다시 맞춘다)
        val r = rests ?: return null
        return r.match(w.lat, w.lon, w.heading).also { spotAt = w to it }
    }

    /** PREND 화면을 앞으로 띄워 본다(혹시 사고). 이번 운행에 화면을 연 적이 없으면 안드로이드가 막고(BAL_BLOCK) 배너가 그 몫을 한다 */
    private fun openScreen(why: String) {
        if (Stage.ui.value.visible) return
        runCatching { startActivity(Intent(this, app.prend.ui.PrendActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Prend.log("✗ $why: 화면 열기 실패 $it") }
        ui.postDelayed({ Prend.log(if (Stage.ui.value.visible) "· $why: 화면이 열림" else "· $why: 화면 열기는 막힘 → 배너로") }, 2_000)
    }

    /**
     * 119에 말할 위치 한 줄. 위치를 모르면 null. 고속도로 판단(highwayCall)에 따라 쓴다(검토 11/07 멋대로 판단: 고속도로 옆 일반 도로·아파트에서
     * 고속도로 이정을 그대로 읽게 하면 구급차가 엉뚱한 곳으로 간다. 틀리느니 둘 다 보인다):
     * - 확실: "○○고속도로 ○○ 방향 이정 km 지점"(방향은 같은 노선 앞쪽 쉼터의 방향 이름)
     * - 애매: 동네 주소(일반 도로와 같은 줄) 다음 줄에 "고속도로 위라면 ○○고속도로 ○○ 방향 이정 km"(Guide.PLACE_OR).
     *   사고 안내의 119 걸음 설명도 '이 위치를 그대로'가 아니라 '둘 중 맞는 곳을 읽어 주세요'가 된다(Guide.steps)
     * - 아님: 내비의 주소(구·동까지)와 도로 이름
     * 가는 방향이 노선과 맞지 않으면 '○○ 방향'을 붙이지 않는다(그때 어느 쪽으로 가는지는 짐작일 뿐이다)
     */
    private fun place(): String? {
        val w = navi?.last ?: return null
        // 위치는 10~60초마다만 바뀐다. 같은 위치면 도로 선 맞춤은 지난 것을 쓴다(spotOf: 급할 때 1초마다 도로 점 4천여 개를 다시 훑지 않게).
        // 고속도로 판단은 같은 위치에서도 바뀐다(등급 답이 옴, 선 지 30분). 판단·위치·도로 이름이 그대로면 지난 한 줄을 그대로 쓴다.
        // 일반 도로의 도로 이름은 늦게 오므로 이름이 새로 오면 같은 자리여도 다시 만든다
        // 같은 자리여도 급할 때 화면이 열리면 일반 도로의 도로 이름을 묻는다(세워 둔 첫 화면에서 이미 한 줄을 만들어 두었어도, 11/07 시험)
        val hw = highwayCall()
        if (w == placeAt && streetGen == placeGen && hw.state == placeHw && hw.along == placeAlong) { if (placeOnStreet) askStreet(w); return placeLine }
        val spot = if (hw.state == app.prend.ride.HighwayState.NO) null else hw.spot
        // 애매면 동네 주소도 보이므로 도로 이름도 묻는다
        placeOnStreet = spot == null || hw.state != app.prend.ride.HighwayState.SURE
        if (placeOnStreet) askStreet(w)
        val hwLine = spot?.let { highwayLine(w, it, hw.along) }
        val line = when {
            hwLine == null -> streetLine(w)
            hw.state == app.prend.ride.HighwayState.SURE -> "$hwLine 지점"
            // 동네 주소를 먼저(고속도로가 아닐 때 그대로 읽을 줄), 고속도로 이정은 다음 줄에 조건을 붙여
            else -> "${streetLine(w) ?: "주소를 찾는 중이에요"}\n${app.prend.ui.Guide.PLACE_OR}$hwLine"
        }
        placeAt = w; placeLine = line; placeGen = streetGen; placeHw = hw.state; placeAlong = hw.along
        return line
    }

    /** 고속도로 자리 "○○고속도로 ○○ 방향 이정 km"(지점은 붙이지 않는다). 방향 이름은 가는 방향이 노선과 맞을 때만 */
    private fun highwayLine(w: app.prend.car.Where, spot: app.prend.ride.RoadSpot, along: Boolean): String {
        val dir = if (!along) null else (rests?.next(w.lat, w.lon, w.heading, spot = spot)?.takeIf { it.onRoute }?.area?.dir ?: rests?.dirOf(spot))
        // 도로공사 노선 이름(경부선)은 사람이 부르는 이름(경부고속도로)으로. 이정은 그 노선 기점에서 잰 거리(길가 이정표와 같다)
        val route = spot.group.substringBefore('|').let { if (it.endsWith("선")) it.dropLast(1) + "고속도로" else it }
        return "$route${dir?.let { " $it 방향" } ?: ""} ${"%.1f".format(spot.km)}km"
    }

    /** place()가 마지막으로 맞춘 위치와 그 한 줄, 그때의 도로 이름 번호·고속도로 판단·노선 방향과 맞았나 */
    private var placeAt: app.prend.car.Where? = null
    private var placeLine: String? = null
    private var placeGen = 0
    private var placeHw: app.prend.ride.HighwayState? = null
    private var placeAlong = true

    /** place()가 마지막으로 만든 한 줄에 동네 주소가 들었나(일반 도로이거나 고속도로인지 애매함: 도로 이름을 묻는다) */
    private var placeOnStreet = false

    /** 이 자리에서 60m 안에서 받은 도로 이름("" 이면 이름 없는 길이라는 답을 받음: 다시 묻지 않는다) */
    private fun streetNear(w: app.prend.car.Where) = street?.takeIf { app.prend.ride.RestAreas.dist(w.lat, w.lon, streetLat, streetLon) < STREET_SAME_M }

    /** 급할 때 화면이고 이 자리의 도로 이름이 없으면 묻는다(15초에 한 번까지, 위치 보내기를 끈 사람은 Road가 묻지 않는다) */
    private fun askStreet(w: app.prend.car.Where) {
        val t = SystemClock.elapsedRealtime()
        if (!Stage.ui.value.scene.isE || streetAsking || t - streetAskedAt < STREET_GAP_MS || streetNear(w) != null) return
        val r = road ?: return
        streetAsking = true; streetAskedAt = t
        r.nameAt(w.lat, w.lon) { name -> ui.post {
            streetAsking = false
            if (name != null) { street = name; streetLat = w.lat; streetLon = w.lon; streetGen++ }
        } }
    }

    /** 일반 도로에서 받은 도로 이름과 그 자리, 받을 때마다 오르는 번호, 묻는 중인가·마지막으로 물은 때 */
    private var street: String? = null
    private var streetLat = 0.0
    private var streetLon = 0.0
    private var streetGen = 0
    private var streetAsking = false
    private var streetAskedAt = 0L

    /**
     * 일반 도로의 위치 한 줄(검토 11/07·별첨 C 16, 사용자 "추천대로"): 내비가 주는 주소(동 이름까지)에 TMAP 가까운 도로의 이름을 붙인다("… 인계동 정조로").
     * 놀란 사람은 '어디냐'에 동 이름보다 지금 선 길의 이름을 대기 쉽다. 급할 때 화면에서만, 위치 보내기를 켠 사람만 묻고(중계 서버 /road, 4초 안),
     * 60m 안이면 받은 이름을 그대로 쓴다. 못 받으면 주소만. 이름은 화면에만 쓰고 기록에 남기지 않는다
     */
    private fun streetLine(w: app.prend.car.Where): String? {
        val base = w.address.trim()
        val near = streetNear(w)
        return when {
            near.isNullOrEmpty() -> base.ifBlank { null }
            base.isBlank() -> near
            near.replace(" ", "") in base.replace(" ", "") -> base
            else -> "$base $near"
        }
    }

    /** 바깥 기온. 시험 기온을 넣었으면 그 값 */
    private fun outside(): Float? = testTemp ?: CarLink.state.outsideC

    // 함께 탄 사람이 있으면 달리는 동안의 첫 화면에 [잘래요]를 둔다
    private fun publish(car: CarState) {
        Stage.car(car.gear, outside(), clock?.minutes ?: 0, car.heat, car.vent, others(), car.uxLimited)
        if (BuildConfig.DEMO) demoTag().let { tag -> if (Stage.ui.value.demoTag != tag) Stage.ui.update { it.copy(demoTag = tag) } }
    }

    /**
     * 위 막대의 '시연' 표(디버그·PT 빌드만, 검토 11/06 별첨 C 10): 지금 켜 둔 시험 값의 이름. 없으면 null.
     * 에뮬레이터에 없는 값(에어백·통화·위치)과 시간을 줄인 시연 값, 시험 기온, 촬영용 값을 화면에서 밝힌다
     */
    private fun demoTag(): String? = listOfNotNull(
        "에어백".takeIf { CarLink.hubOrNull?.pretendingAirbag == true },
        "통화".takeIf { crash?.faking == true },
        "위치".takeIf { navi?.pretending == true },
        "기온".takeIf { testTemp != null },
        "시간 줄임".takeIf { Timing.ptValues },
        "촬영 값".takeIf { Stage.ui.value.demo },
    ).takeIf { it.isNotEmpty() }?.joinToString(" · ", prefix = "시연 · ")

    /**
     * 새 운행인가: 부팅 뒤 10분 안에 처음 깨어났으면 시동으로 본다(같은 부팅에서 서비스가 다시 떠도 묻지 않는다).
     * 같은 부팅에서 프로세스가 다시 떴으면, 마지막으로 깨어 있던 때부터 기기가 30분 넘게 잠들어 있었을 때만(시동 끔·서스펜드) 새 운행이다(검토 11/07, 전에는 5분).
     * 잠든 시간은 '부팅 뒤 시간(잠 포함)'이 '깨어 있던 시간'보다 더 흐른 만큼으로 잰다: 시동이 켜진 채 프로세스만 꺼졌다 뜨면 잠든 시간이 없어 같은 운행이다.
     * 새 운행을 시작했으면 true. 아니면 같은 운행이 이어진 것(창을 닫아 프로세스만 다시 뜸)이라 부른 쪽이 적어 둔 상태를 잇는다
     */
    private fun checkNewRide(): Boolean {
        val up = SystemClock.elapsedRealtime()
        val now = System.currentTimeMillis()
        val bootAt = now - up
        val prefs = getSharedPreferences("ride", MODE_PRIVATE)
        val asked = prefs.getLong("boot_at", 0L)
        val alive = prefs.getLong("alive_at", 0L)
        if (up < 10 * 60_000 && kotlin.math.abs(asked - bootAt) > 60_000) {
            // 지난번에 마지막으로 깨어 있던 때부터 이번 부팅까지가 시동을 꺼 둔 시간
            val off = if (alive > 0 && bootAt > alive) ((bootAt - alive) / 60_000).toInt() else null
            prefs.edit().putLong("boot_at", bootAt).apply()
            care?.newRide("시동", off)
            return true
        }
        val aliveEl = prefs.getLong("alive_el", 0L)
        val aliveUp = prefs.getLong("alive_up", 0L)
        val aliveBoot = prefs.getInt("alive_boot", -1)
        val off = if (alive > 0 && now > alive) ((now - alive) / 60_000).toInt() else null
        // 그사이 다시 부팅했다(부팅 신호를 10분 넘게 늦게 받음. 부팅 횟수가 다르거나, 적어 둔 부팅 뒤 시간이 지금보다 크다): 시동으로 본다
        if (aliveEl > up || (bootCount >= 0 && aliveBoot >= 0 && bootCount != aliveBoot)) {
            prefs.edit().putLong("boot_at", bootAt).apply()
            care?.newRide("시동", off)
            return true
        }
        val slept = if (aliveEl > 0) (up - aliveEl) - (SystemClock.uptimeMillis() - aliveUp)
            // 예전 판이 벽시계만 적어 둔 때
            else if (alive > 0) now - alive else 0L
        if (slept > SLEEP_GAP_MS) {
            // 30분 안이면 같은 운행(휴게소에 잠깐 들름): 적어 둔 운행 상태를 잇는다(검토 11/07 멋대로 판단, woke와 같은 눈금)
            if (slept <= RIDE_GAP_MS) { Prend.log("◆ 차 화면이 ${slept / 60_000}분 잠들었다 깸: 같은 운행으로 이어 감(${RIDE_GAP_MS / 60_000}분 안)"); sleptBack = true; return false }
            // 새 프로세스라 지난 운행의 조용히 가기·대리 운행은 적어 둔 상태에서 읽는다(새 운행이 지우기 전에)
            val was = keep?.load()
            care?.newRide("잠에서 깸", off)
            released(was?.optBoolean("quietRide") == true, was?.optBoolean("daeri") == true)
            return true
        }
        return false
    }

    /** checkNewRide가 차 화면이 30분 안에 잠들었다 깬 것으로 보고 같은 운행을 이었다(그때 쪽잠은 끝낸다, 11/08 오류 점검 C3) */
    private var sleptBack = false

    /** 이 기기가 부팅한 횟수(Settings.Global.BOOT_COUNT). 읽지 못하면 -1 */
    private val bootCount: Int by lazy { runCatching { android.provider.Settings.Global.getInt(contentResolver, android.provider.Settings.Global.BOOT_COUNT) }.getOrDefault(-1) }

    private fun onCar(old: CarState, now: CarState) {
        clock?.onGear(now.gear)
        road?.onCar(old, now)
        timing.onCar(old, now)
        // 안정 주행(20초 동안 핸들 ±10°)을 보려고 D인 동안 핸들 각도를 읽는다(주차 코치의 읽기와 따로 켜고 끈다)
        CarLink.hubOrNull?.steerWhileDriving(now.driving)
        // 깜빡이·R은 1초 틱을 기다리지 않고 하던 말·대답 듣기를 바로 멈춘다(0.3초 안, 감사 F47). 말할 때 판단(timing)을 먼저 맞춘 뒤
        care?.onCar(old, now)
        hush?.onCar(old, now)
        if (old.turnSignal != now.turnSignal) Prend.log("· 깜빡이 ${now.turnSignal}")
        publish(now)
        if (old.gear != now.gear) banner?.update(Stage.ui.value, now.gear)
        if (!old.airbag && now.airbag) crash?.airbag()
        crash?.onCar(old, now)
        bump?.onCar(old, now)
        if (old.parked != now.parked) jolt?.watch(!now.parked)
        nap?.onCar(old, now)
        coach?.onCar(old, now)
        carCare?.onCar(old, now)
    }

    /** 배너의 버튼. call은 [프렌드] 부르기, yes:id·no:id는 PREND가 물은 것의 대답, alt:id는 배너에만 있는 버튼(한 시간 물음의 [쉬어 갈 곳]) */
    private fun onBanner(what: String) {
        Prend.log("◆ 배너 버튼: ${what.substringBefore(':')}")
        when {
            what == Banner.CALL -> Stage.call()
            what.startsWith("yes:") || what.startsWith("no:") -> Stage.answer(what.substringAfter(':'), what.startsWith("yes:"))
            what.startsWith("alt:") -> Stage.answerAlt(what.substringAfter(':'))
        }
    }

    /** 길 안내 음성이 지금 나오는가(재생 구성의 용도, 일반 앱에는 지금 소리를 내는 것만 보인다) */
    private fun navVoice(): Boolean = runCatching {
        getSystemService(android.media.AudioManager::class.java).activePlaybackConfigurations
            .any { it.audioAttributes.usage == android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE }
    }.getOrDefault(false)

    /**
     * 다른 음성 비서(Gleo 등)가 지금 말하는가(검토 11/06). 재생 중인 소리에 음성 비서 용도가 있고 PREND는 말하지 않는 중일 때.
     * PREND의 말(Speaker·급할 때 안내)도 같은 용도이고 일반 앱은 소리의 주인을 볼 수 없어, PREND가 말하는 동안은 가르지 않는다(추측: 실차 Gleo가 이 용도로 말한다고 볼 때)
     */
    private fun otherAssistant(): Boolean = speaker?.speaking != true && crash?.speaking != true && runCatching {
        getSystemService(android.media.AudioManager::class.java).activePlaybackConfigurations
            .any { it.audioAttributes.usage == android.media.AudioAttributes.USAGE_ASSISTANT }
    }.getOrDefault(false)

    /** 시험: 다른 음성 비서가 ms 동안 말하는 것처럼(음성 비서 용도로 소리 없는 1초를 되풀이해 튼다. 에뮬레이터에는 Gleo가 없다) */
    private fun fakeAssistant(ms: Long) {
        val n = 16_000
        val track = runCatching {
            android.media.AudioTrack.Builder()
                .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANT).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(n).setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(android.media.AudioTrack.MODE_STATIC).setBufferSizeInBytes(n * 2).build()
                .also { t -> t.write(ShortArray(n), 0, n); t.setLoopPoints(0, n, -1); t.play() }
        }.onFailure { Prend.log("✗ 시험: 음성 비서 흉내 실패 $it") }.getOrNull() ?: return
        Prend.log("· 시험: 다른 음성 비서 흉내 ${ms / 1000}초(소리 없음)")
        ui.postDelayed({ runCatching { track.stop(); track.release() }; Prend.log("· 시험: 음성 비서 흉내 끝") }, ms)
    }

    /** 시험: 지금 위치가 어느 고속도로 어디인지, 앞쪽 쉼터는 어디인지 기록한다 */
    private fun where() {
        val w = navi?.last ?: return Prend.log("· 위치: 아직 모름")
        val spot = rests?.match(w.lat, w.lon, w.heading)
        val next = rests?.next(w.lat, w.lon, w.heading)
        // 좌표는 기록에 남기지 않는다(시험 명령도 같다)
        Prend.log("· 위치(좌표는 남기지 않음) 경로 %s → %s → 앞쪽 쉼터 %s".format(if (w.routing) "있음" else "없음",
            spot?.let { "${it.group} ${"%.1f".format(it.km)}km(${if (it.sign > 0) "이정 늘어나는" else "줄어드는"} 쪽, ${it.offM.toInt()}m)" } ?: "고속도로 밖",
            next?.let { "${it.area.name}(${it.area.dir} 방향) ${"%.1f".format(it.alongKm)}km" } ?: "없음"))
        // 고속도로 판단도 늘 한 줄(바뀔 때만 남기는 '고속도로 판단:' 줄과 따로, 검토 11/07)
        highwayCall().let { Prend.log("· 위치: 고속도로 ${it.state.label}(${it.why})") }
    }

    /** 귀(Pleos STT 기기 안)와 입(미리 녹음한 목소리)은 서비스가 쥔다. 화면이 없어도 묻고 듣는다 */
    private fun voice() {
        Thread({ runCatching { app.prend.sound.Chime.warm() } }, "prend-chime").start()
        speaker = Speaker(this)
        ears = Ears(this).also { e ->
            e.onText = { s, done -> Stage.onHeard(s, done) }
            e.onLevel = { Stage.onLevel(it) }
            e.onEnd = { Stage.ui.value.listen?.let { l -> if (!l.done) Stage.finishListen(l.heard) } }
            e.init()
        }
        // Pleos 음성 인식이 들을 때는 말소리 감지가 마이크를 먼저 내려놓는다(입력 장치를 다투지 않게). 듣기가 끝나면 1초 안에 다시 연다
        Stage.ears = { talk?.stop("말을 듣는 동안"); ears?.listen() }
        Stage.stopEars = { ears?.stop() }
        Stage.chime = { runCatching { Chime.play() } }
        Stage.hush = { speaker?.cut(); crash?.holdForListen() }
        // 말소리 감지: PREND가 말하는 동안과 사람이 [프렌드]·대답으로 말하는 동안(Pleos 음성 인식이 마이크를 쓴다)은 판정하지 않는다
        talk = TalkSense(this) { speaker?.speaking == true || Stage.ui.value.listen != null }.also { t ->
            t.onTalking = ::onTalking
            t.onVoiceOverBg = { db -> Bridge.director?.voiceOverBackgroundDb = db }
        }
        // 생성형 AI 대역: 낱말 규칙에 안 맞은 말만 중계 서버로(들은 말은 기록에 남기지 않고 고른 할 일만 적는다)
        Stage.askAi = { text, parked, done ->
            scope.launch {
                val t0 = SystemClock.elapsedRealtime()
                // 전체를 기다릴 만큼만 기다린다(연결·읽기 제한 밖인 주소 찾기가 터널에서 수십 초 걸릴 수 있다). 늦게 끝난 요청은 버린다
                // 서버가 바쁘거나(429) 실패해(5xx) 답했으면 인터넷 탓이라고 하지 않는다(11/08 오류 점검 D5)
                var http: Int? = null
                val req = async { app.prend.net.Relay.post("/intent", org.json.JSONObject().put("text", text).put("parked", parked), onHttp = { http = it }) }
                val j = withTimeoutOrNull(Stage.AI_WAIT_MS) { req.await() } ?: run { req.cancel(); null }
                val code = http
                val picked = j?.optString("intent")?.ifBlank { null } ?: if (code == 429 || (code ?: 0) >= 500) "busy" else null
                Prend.log("· 프렌드(AI 대역): → ${picked ?: "답 없음"} ${SystemClock.elapsedRealtime() - t0}ms")
                done(picked, j?.optBoolean("kid"))
            }
        }
    }

    /** 한 번만 깨어난다. 화면이 다시 불러도 같은 몸통을 쓴다 */
    private fun wake(from: String) {
        if (started) { Prend.log("· 운행 서비스: 이미 깨어 있음($from)"); return }
        started = true
        clock = RideClock(this, ::rested)
        voice()
        // 쉼터 자료(CSV 셋, 4천여 줄)는 배경에서 읽는다. 메인에서 읽으면 앱을 켤 때 첫 화면이 그만큼 늦었다(11/04 시험 27). 다 읽기 전에는 쉼터를 고르지 못할 뿐이다
        scope.launch {
            rests = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { runCatching { RestAreas(this@RideService) }.onFailure { Prend.log("✗ 쉼터 자료 읽기 실패 $it") }.getOrNull() }
            Prend.log("· 쉼터 자료 ${rests?.count ?: 0}곳")
        }
        navi = Navi(this).also { it.init() }
        road = app.prend.ride.Road(navi, scope)
        val jam = JamHelp(this, ::playCalm, ::stopCalm) { ms -> road?.mostlyAlley(ms) }.also { jamHelp = it }
        val pick = Together(this, navi).also { together = it }
        val mem = Memory(this).also { memory = it }
        val aft = AfterCrash(this).also { after = it }
        val bb = Baby(this).also { baby = it }
        val np = Nap(this, speaker!!, mem, navi, { care?.queueNap() }, { care?.queueSleep() }).also { nap = it }
        // 운전 도움의 젖은 길 한마디: 다른 말이 오가지 않고, 급할 때·쪽잠·잘래요·주차 코치가 아니며, 말할 때(후진·깜빡이 아님)일 때만(아니면 다음에 다시).
        // 잘래요 동안은 먼저 말을 걸지 않는다(별첨 2 V9). 11/01: 큰길 신호 대기나 P에서만(골목에서 비켜 서거나 출발하는 순간에 시작하지 않게),
        // 말 차례를 지나고(한 번 설 때 하나), 다른 앱 음악 위에는 얹지 않는다.
        // 11/03: 운전 도움을 끈 사람은 소리 없는 카드(해 지기 전에와 같은 차례 조건, 대리 운행이면 띄우지 않음). 숫자는 내비 제한속도로
        val nv = Novice(this, ::outside, timing, floor, { navi?.speedLimit() }, {
            care?.idle == true && care?.watchingBump != true && coach?.coaching != true && !np.napping && !np.sleeping && speaker?.speaking != true &&
                alleyDest?.busy != true && !Stage.ui.value.daeri
        }, { jamHelp?.otherMusicOn() == true || Stage.ui.value.quietRide }) { asset, label ->
            val s = speaker
            val car = CarLink.state
            val free = s != null && !s.speaking && care?.idle == true && !Stage.ui.value.scene.isE && !np.napping && !np.sleeping && !Stage.ui.value.daeri && !Stage.ui.value.quietRide &&
                coach?.coaching != true && timing.comfy(car).let { it == Timing.PARK || it == Timing.LIGHT } && jamHelp?.otherMusicOn() != true &&
                timing.block(car, starting = true) == null && floor.why(Floor.Kind.TIP) == null
            if (free && s!!.speak(asset, android.media.AudioAttributes.USAGE_ASSISTANT, label, interrupt = false)) { floor.spoke(Floor.Kind.TIP, label); true } else false
        }.also { novice = it }
        Stage.onNovice = { on -> nv.set(on); if (on) { alleyDest?.reset(); speaker?.speak("voice/novice_on.wav", android.media.AudioAttributes.USAGE_ASSISTANT, "운전 도움") } }
        // 이야기에 비켜 서기: 아직 정하지 않았으면 세워 두고 화면을 보는 동안 동승자가 있을 때 한 번 묻는다
        Stage.ui.update { it.copy(talkOn = talkPrefs.getBoolean("on", false)) }
        Stage.onTalk = ::setTalk
        Stage.onAir = { fresh ->
            CarLink.hubOrNull?.setRecirc(!fresh) { ok -> Stage.toast(if (!ok) "바꾸지 못했어요. 공조의 내기 순환 버튼을 눌러 주세요" else if (fresh) "바깥 공기로 바꿨어요" else "안쪽 공기로 돌릴게요", 3_000) }
                ?: Stage.toast("공조에 닿지 못했어요")
        }
        Stage.onMusic = ::music
        Stage.onRain = ::rain
        Stage.onSong = ::songWhat
        // 이야기에 비켜 서기 물음은 동승자가 있을 때. 모시는 길(조수석이 비고 뒷자리에만 사람)은 먼저 건네는 말이 없어 묻지 않는다(별첨 2 §5-4, 감사 F88)
        care = Care(speaker!!, timing, { rests }, navi, jam, pick, mem, bb, aft, np, nv,
            onNewRide = { coach?.newRide(); talkNewRide(); rainNewRide(); floor.newRide(); carCare?.newRide(); dusk?.newRide() }, coaching = { coach?.coaching == true },
            talkAskable = { !talkPrefs.getBoolean("decided", false) && Stage.ui.value.visible && others() && !CarLink.state.chauffeured && !Stage.ui.value.scene.isE },
            onTalkAnswer = ::setTalk, kept = RideKeep(this).also { keep = it }, onHighway = ::onHighway, floor = floor,
            toilets = Toilets(this, highway = { highwayCall() }) { rests }.also { it.preload() },
            // 위치 알림 카드가 보이나: 화면이 보이면 첫 화면이고 허락 판이 없을 때, 닫혀 있으면 알림 허락이 있어 배너가 뜰 때(화면의 notifOff는 화면이 떠 있을 때만 맞춰져 여기서 직접 읽는다)
            geoShown = {
                val u = Stage.ui.value
                if (u.visible) u.scene == app.prend.ui.Scene.HOME && !u.permPanel
                else runCatching { getSystemService(android.app.NotificationManager::class.java)?.areNotificationsEnabled() == true }.getOrDefault(false)
            })
        // 먼저 묻기(사용자 결정 11/06): 기억 화면의 켜고 끄기. 이야기에 비켜 서기 물음을 다시 켰는데 기능이 꺼져 있으면
        // 정한 것을 지워 다음에 세워 둔 채 동승자가 타면 한 번 묻는다
        Stage.onAsks = { key, on ->
            mem.setAsks(key, on)
            if (key == "TALK" && on && !Stage.ui.value.talkOn) talkPrefs.edit().putBoolean("decided", false).apply()
        }
        // 골목 돌려 나오기: 3분 안의 마지막 판정이 골목(단지 안 도로 말고)이고, 길 안내 중이며 목적지까지 1분 넘게 남았고 막 도착한 것이 아님.
        // 길 안내 없이 골목(집 앞 등)에 대는 것은 주차일 수 있어 예전처럼 켠다(11/01 검토).
        // 우회전 카드(11/03)는 해 지기 전에와 같은 차례 조건, 대리 운행이면 띄우지 않는다
        coach = Coach(speaker!!, navi, ::onHighway, ::outside, { care?.queueFog() }, { care?.idle == true }, floor, { jamHelp?.otherMusicOn() == true }, timing, prefs = getSharedPreferences("coach", MODE_PRIVATE),
            cardFree = { care?.idle == true && care?.watchingBump != true && !np.napping && !np.sleeping && speaker?.speaking != true && alleyDest?.busy != true && !Stage.ui.value.daeri }) {
            val arrived = navi?.arrivedAt?.let { it > 0 && System.currentTimeMillis() - it < 10 * 60_000L } == true
            val eta = navi?.etaSec()
            road?.turningAlley(3 * 60_000L) == true && !arrived && eta != null && eta > 60
        }
        // 차 돌봄: 갈 때가 된 항목은 달려 와서 P에 넣고 다른 말·카드·주차 코치·쪽잠이 없을 때만 카드 한 장(목소리 없이)
        carCare = CarCare(this, { CarLink.hubOrNull }, navi, floor) {
            care?.idle == true && care?.watchingBump != true && coach?.coaching != true && !np.napping && !np.sleeping && speaker?.speaking != true &&
                timing.comfy(CarLink.state) == Timing.PARK && alleyDest?.busy != true
        }
        // 골목 목적지(초보): P에서 목적지를 정하면 한 번, 다른 말·카드가 없을 때 카드만
        alleyDest = app.prend.ride.AlleyDest(this, navi, { road }, floor) { care?.idle == true && care?.watchingBump != true && coach?.coaching != true && !np.napping && !np.sleeping && speaker?.speaking != true }
        // [모두 잊기]는 정비한 때와 골목 목적지 '그대로' 횟수도 지운다(기억 화면이 남기는 것으로 적어 둔 것)
        // 해 지기 전에(11/02): 골목 목적지와 같은 차례 조건. 골목 목적지 카드가 먼저다
        dusk = app.prend.ride.Dusk(this, navi, floor) { care?.idle == true && care?.watchingBump != true && coach?.coaching != true && !np.napping && !np.sleeping && speaker?.speaking != true && alleyDest?.busy != true }
        Stage.onDusk = { dusk?.sayOn() }
        // 듣고 보기(11/02): "뭐라고?"면 천천히 다시, 두 번이면 천천히 말하기 카드, 늦게 대답하면 대답 창 8초
        slowTalk = app.prend.ride.SlowTalk(this, speaker, floor) { care?.idle == true && care?.watchingBump != true && coach?.coaching != true && !np.napping && !np.sleeping && speaker?.speaking != true && alleyDest?.busy != true }
        Stage.onAgain = { ans -> slowTalk?.again(ans) }
        Stage.onLate = { slowTalk?.late() }
        Stage.onSlow = { on -> slowTalk?.set(on, "말", "천천히 말해 달라고 하셔서") }
        // 기억 화면 '듣고 보기'(3-9): 셋을 한 판에서 켜고 끈다. 해 지기 전에를 끄면 운전 도움을 켜 둬도 끈 쪽이 이긴다
        Stage.onListen = { what, on ->
            when (what) {
                "slow" -> slowTalk?.set(on, "기억 화면")
                "wait" -> slowTalk?.setWait(on, "기억 화면")
                "dusk" -> dusk?.set(on, "기억 화면")
                else -> Unit
            }
        }
        // 정비소에 간 날(11/02): 도착 카드 [기록하기], "오늘 정비했어", "고친 데가 또 이상해"
        shopVisit = app.prend.ride.ShopVisit(this, navi, floor, { carCare?.kmNow }) { care?.idle == true && care?.watchingBump != true && coach?.coaching != true && !np.napping && !np.sleeping && speaker?.speaking != true }
        Stage.onShop = { what -> if (what == "done") shopVisit?.record("말") else shopVisit?.again() }
        // 고속도로 위에서 P면 대피 카드(11/02). 고속도로인지·졸음쉼터 거리는 앱 안 자료로만
        // 고속도로인지는 근거를 세어 가리고(highwayCall, 검토 11/07 멋대로 판단), [괜찮아요]를 두 번 받은 자리는 이 차 안에 둔다
        highwayStop = app.prend.ride.HighwayStop(navi, ::highwayCall, { navi?.last?.let { rests?.nearestKm(it.lat, it.lon) } }, { c -> jamHelp?.slowTraffic(c) == true }, { bump?.armed == true || care?.watchingBump == true }, floor,
            prefs = getSharedPreferences("hstop", MODE_PRIVATE)) {
            // 그 자리의 안전 한 줄: 말 차례·조용히 가기를 거치지 않고(쉼터·급할 때처럼), 다른 말이 나오는 중이면 카드만
            val s = speaker
            s != null && !s.speaking && s.speak("voice/e_hstop.wav", android.media.AudioAttributes.USAGE_ASSISTANT, "고속도로 정차", interrupt = false)
        }
        Stage.onForget?.let { forget -> Stage.onForget = { k -> forget(k); if (k == null) { carCare?.forget(); alleyDest?.reset(); dusk?.forget(); slowTalk?.forget(); shopVisit?.forget(); novice?.forget(); coach?.forget(); highwayStop?.forget() } } }
        hush = Hush(this)
        // 사고 메모의 날씨는 시험 기온(temp_)도 따른다(화면의 기온과 같은 값)
        crash = Crash(this, ::onHighway, navi, aft, ::place, { speaker?.slow == true }) { CarLink.state.let { s -> testTemp?.let { s.copy(outsideC = it) } ?: s } }
        // 혹시 사고를 물을 때는 에어백처럼 PREND 화면을 앞으로 띄운다(11/05: 내비 위 흰 배너만으로는 놓치기 쉽다). 막히면(BAL_BLOCK) 배너가 그대로 있다
        bump = Bump({ CarLink.hubOrNull?.hazardReadable == true }) { jolted -> Stage.ui.value = Stage.ui.value.copy(bumpJolt = jolted); care?.queueBump(); openScreen("혹시 사고") }
        // 충격 읽기(11/13): 가속도 센서가 있는 차에서만. P가 아닐 때만 켠다
        jolt = app.prend.car.Jolt(this) { g -> bump?.onJolt(g) }
        Prend.log("· 충격 읽기: ${if (jolt?.readable == true) "가속도 센서 있음" else "가속도 센서 없음(급제동으로만 살핌)"}")
        Stage.onInsurer = { aft.insurer(it) }
        Stage.onNoteDone = { aft.done() }
        detachCar = CarLink.attach(this, ::onCar)
        jolt?.watch(!CarLink.state.parked)
        clock?.onGear(CarLink.state.gear)
        // 시험 명령(adb)은 디버그 빌드에서만 받는다. 출시 빌드에서는 다른 앱이 에어백·마이크 같은 명령을 보내지 못한다
        if (BuildConfig.DEMO) registerReceiver(commands, IntentFilter("app.prend.RIDE"), Context.RECEIVER_EXPORTED)
        // PREND 화면이 보이지 않을 때 묻고 알리는 배너는 화면 상태를 따라간다
        val b = Banner(this).also { banner = it }
        watch = scope.launch { Stage.ui.collect { b.update(it, CarLink.state.gear) } }
        ui.post(tick)
        // 새 운행이 아니면 같은 운행 중 프로세스만 다시 뜬 것: 적어 둔 운행 상태(아이 탑승·대리 운행·물은 것 등)를 잇는다
        // 쪽잠·잘래요도 잇는다(11/08 오류 점검 C3: 창을 닫아 프로세스가 다시 뜨면 깨우기가 사라졌다). 차 화면이 잠들었다 깬 것이면 쪽잠은 끝낸다(woke와 같다)
        if (!checkNewRide()) { keep?.ensure(); care?.restore(); nap?.restoreNap(afterSleep = sleptBack) }
        Prend.log("◆ 운행 서비스 깨어남($from)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService로 불렸으면 매번 포그라운드를 다시 알려야 한다(화면에서 불러도, 부팅에서 불러도)
        if (!foreground()) { stopSelf(); return START_NOT_STICKY }

        intent?.getStringExtra(Banner.EXTRA)?.let { what ->
            if (started) onBanner(what) else wake("배너")
            return START_STICKY
        }
        // 시험 9-2(디버그 빌드만): 화면을 열지 않은 운행에서도 Pleos STT가 마이크로 듣기를 시작하는가(마이크는 플랫폼이 쥔다)
        if (BuildConfig.DEMO && intent?.getStringExtra("stt") == "listen") {
            val probe = stt ?: app.prend.voice.SttProbe(this).also { stt = it; it.init() }
            ui.postDelayed({ Prend.log("· 듣기 시험: 화면 없이 듣기 시작"); probe.listen() }, 8_000)
            ui.postDelayed({ probe.stop() }, 16_000)
            return START_STICKY
        }
        // 다시 시작(START_STICKY)이면 intent가 비어 온다
        wake(intent?.getStringExtra("from") ?: "다시 시작")  // 보통은 onCreate에서 이미 깨어 있다
        return START_STICKY
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        clock?.save()
        detachCar?.invoke()
        jolt?.watch(false)
        // 달리는 동안의 핸들 읽기는 이 서비스가 켰다: 음악 서비스가 차 연결을 쥐고 있어도 멈춘다(떼어 낸 뒤라 onCar가 다시 켜지 않는다)
        CarLink.hubOrNull?.steerWhileDriving(false)
        timing.release()
        if (started) runCatching { unregisterReceiver(commands) }
        stt?.release()
        speaker?.stop()
        hush?.release()
        crash?.release()
        nap?.release()
        coach?.release()
        carCare?.release()
        alleyDest?.release()
        novice?.release()
        together?.release()
        ears?.release()
        talk?.release()
        navi?.release()
        watch?.cancel()
        // 기다리던 AI 대역의 답도 버린다(서비스가 끝난 뒤에 화면을 바꾸지 않게)
        scope.cancel()
        banner?.clear()
        Stage.ears = null; Stage.stopEars = null; Stage.hush = null; Stage.askAi = null; Stage.onAsk = null; Stage.onForget = null
        Stage.onBaby = null; Stage.onInsurer = null; Stage.onNoteDone = null; Stage.onNovice = null
        Stage.onTalk = null; Stage.onAir = null; Stage.onMusic = null; Stage.onDaeri = null; Stage.onQuiet = null
        Stage.onRain = null; Stage.onQuietRide = null; Stage.onAlwaysQuiet = null; Stage.onRest = null; Stage.onToilet = null; Stage.onToiletNo = null; Stage.onRestNo = null; Stage.onAlt = null
        Stage.onSeatAt = null; Stage.onMotion = null; Stage.onSong = null; Stage.onUndo = null
        Stage.onAgain = null; Stage.onLate = null; Stage.onLateSaid = null; Stage.onSlow = null; Stage.onDusk = null; Stage.onListen = null
        // 남아 있던 콜백·카드 대답 받기도 뗀다(서비스가 프로세스를 남긴 채 끝나면 옛 몸통을 쥐고 있었다, 11/04 점검)
        Stage.onSeat = null; Stage.onKids = null; Stage.onShop = null; Stage.onAsks = null
        app.prend.ride.Stats.testing = { false }
        ui.removeCallbacks(ptCallEnd)
        dusk?.release()
        listOf("HSTOP", "SHOP", "SLOW").forEach { Stage.sideAsks.remove(it) }
        Prend.log("◆ 운행 서비스 끝")
        super.onDestroy()
    }

    companion object {
        const val QUIET = "ride"
        const val ONGOING_ID = 1
        /** 일반 도로 위치 줄의 도로 이름: 이 거리 안이면 받은 이름을 그대로, 다시 묻는 데 이만큼 띄운다 */
        const val STREET_SAME_M = 60.0
        const val STREET_GAP_MS = 15_000L
        /** 1초 틱이나 마지막으로 깨어 있던 때가 이만큼 비면 잠(시동 끔·서스펜드)에서 깬 것으로 본다 */
        const val SLEEP_GAP_MS = 5 * 60_000L
        /** 잠에서 깬 공백이 이만큼 안이면 같은 운행(휴게소에 잠깐 들름), 넘으면 새 운행(검토 11/07 멋대로 판단) */
        const val RIDE_GAP_MS = 30 * 60_000L
        /** 이야기 때 바람: 이 단 이상일 때만 한 단 낮추고, 낮춘 지 이만큼이면 되돌리고, 되돌린 뒤 이만큼은 다시 낮추지 않는다(별첨 4 §4-5) */
        const val TALK_FAN_MIN = 3
        const val TALK_FAN_MS = 2 * 60_000L
    }
}
