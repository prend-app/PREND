package app.prend.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import app.prend.Bridge
import app.prend.BuildConfig
import app.prend.PlaybackService
import ai.pleos.playground.stt.constant.Mode
import app.prend.Prend
import app.prend.car.NaviProbe
import app.prend.voice.SttProbe
import app.prend.sound.Occupancy
import app.prend.sound.WavCapture
import java.io.File

/**
 * 시연 패널(개발용). 음악을 틀고, 소리 엔진의 상태를 보고, 시연용 덮어쓰기(구성·대화·밤·작은 음량·잠·아기)를 건다.
 * adb로도 부른다: am start -n app.prend/.ui.DebugActivity --es cmd <명령>
 * 명령: play, pause, seek_<ms>, rec_on, rec_off, solo, pair, group, auto, talk_on, talk_off, rich_on, rich_off,
 *       width_on, loud_on, compare_off, compare_auto, sleep_on, sleep_off, baby_on, baby_off, rain_on, rain_off,
 *       cold, arrive, fast_on, fast_off, ratio_<dB>, settle, navi_*, stt_*
 * PT 대본의 시연 버튼(디버그·PT 빌드만: 운행 서비스의 시험 명령으로 넘긴다, 11/05 E 중심, 기획안 v10 별첨 B):
 *       pt_e_ready(① 장면 준비: 새 운행·PT 기준 값), pt_highway(②), pt_bluelink(③), pt_swallow(④), pt_toilet(⑤ 화장실, 11/07). ②~⑤는 ①부터 부른다(11/06),
 *       pt_clean(시험 값 모두 끔), pt_values_off(시연 값 끔). 옛 장면 pt_pair·pt_basement·pt_night_kid·pt_drive·pt_values_on도 받는다
 * 맨 위에는 콘솔의 문이 열려 있으면 큰 빨간 줄(혹시 사고는 운전석 문이 '닫힘 → 열림'일 때만 묻는다),
 * 그 아래 PT 기준 값 한 줄(가족 정보 · 대답 창 · 천천히 · 조용히 · 앉은 자리 · 연속 운전. ①이 맞추는 값과 다르면 노랗게)
 */
class DebugActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private lateinit var status: TextView
    private lateinit var doorWarn: TextView
    private lateinit var basis: TextView
    private val pending = mutableListOf<String>()
    private var navi: NaviProbe? = null
    private var stt: SttProbe? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prend.init(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 30, 40, 30) }
        // 문이 열린 채 다음 장면을 하면 차가 묻지 않는다(검토 11/06 10): 열려 있는 동안 맨 위에 크게
        doorWarn = TextView(this).apply {
            textSize = 30f; setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(200, 30, 30)); typeface = Typeface.DEFAULT_BOLD
            setPadding(24, 16, 24, 16); visibility = android.view.View.GONE
        }
        col.addView(doorWarn)
        basis = TextView(this).apply { textSize = 22f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; setPadding(0, 12, 0, 12) }
        col.addView(basis)
        status = TextView(this).apply {
            textSize = 17f; setTextColor(Color.WHITE); typeface = Typeface.MONOSPACE
        }
        col.addView(status)
        fun row(vararg pairs: Pair<String, String>) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pairs.forEach { (label, cmd) ->
                r.addView(Button(this).apply {
                    text = label; textSize = 16f
                    setOnClickListener { run(cmd) }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            col.addView(r)
        }
        // 녹음(곡을 파일로 받기)은 소리 시험용이라 디버그 빌드에만(출시판에서 눌러 두면 상한 없이 파일이 커진다, 11/04 점검)
        if (BuildConfig.DEMO) row("▶ 재생" to "play", "■ 멈춤" to "pause", "● 녹음" to "rec_on", "녹음 끝" to "rec_off") else row("▶ 재생" to "play", "■ 멈춤" to "pause")
        row("자동 구성" to "auto", "혼자" to "solo", "둘" to "pair", "셋 이상" to "group")
        row("대화 시작" to "talk_on", "대화 끝" to "talk_off", "시연 배속" to "fast_on", "배속 끔" to "fast_off")
        row("설정 풍성하게 켬" to "rich_on", "설정 풍성하게 끔" to "rich_off")
        row("넓게만(비교)" to "width_on", "보정만(비교)" to "loud_on", "비교 끔" to "compare_off")
        row("잘래요" to "sleep_on", "잘래요 끝" to "sleep_off", "아이" to "baby_on", "아이 끝" to "baby_off")
        row("빗소리 얹기(말)" to "rain_on", "빗소리 걷기(말)" to "rain_off", "시동 흉내" to "cold", "도착 흉내" to "arrive")
        // PT 대본이 부르는 시연 버튼(11/05 E 중심, 기획안-v10 별첨 B). 운행 서비스의 시험 명령 수신기가 디버그·PT 빌드에만 있어 버튼도 그 빌드에만
        if (BuildConfig.DEMO) {
            row("① 장면 준비(새 운행·기준 값)" to "pt_e_ready", "시험 값 모두 끔" to "pt_clean")
            row("② 고속도로(①부터)" to "pt_highway", "③ 블루링크 통화 + 에어백(①부터)" to "pt_bluelink")
            row("④ 말 삼키기(①부터, 1시간 59분 50초)" to "pt_swallow", "시연 값 끔" to "pt_values_off")
            row("⑤ 화장실(①부터, 수원 시내 도로)" to "pt_toilet")
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(col) })

        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            controller = runCatching { future.get() }.getOrNull()
            pending.toList().forEach { run(it) }; pending.clear()
        }, MoreExecutors.directExecutor())
        intent.getStringExtra("cmd")?.let { run(it) }
        ui.post(tick)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("cmd")?.let { run(it) }
    }

    private val tick = object : Runnable {
        override fun run() {
            val car = Bridge.hub?.state
            val d = Bridge.director
            val open = car?.let { openDoors(it.doors) }.orEmpty()
            doorWarn.visibility = if (open.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            if (open.isNotEmpty()) doorWarn.text = "문이 열려 있어요: ${open.joinToString()} → 콘솔에서 닫기"
            // PT 기준 값(①이 맞추는 값: 아이 없음 · 5초 · 끔 · 조용히 끔 · 운전석만)과 다르면 노랗게
            val s = Stage.ui.value
            val sat = car?.seats?.filterValues { it }?.keys.orEmpty()
            val kids = when (s.kids) { true -> "아이 있음"; false -> "아이 없음"; null -> "모름" }
            val quiet = if (s.alwaysQuiet) "늘 켬" else if (s.quietRide) "켬" else "끔"
            basis.text = "가족 정보 $kids · 대답 창 ${Stage.answerMs / 1000}초 · 천천히 ${if (s.slowTalk) "켬" else "끔"} · 조용히 $quiet · " +
                "앉은 자리 ${sat.map { SEATS[it] ?: it }.ifEmpty { listOf("없음") }.joinToString()} · 연속 운전 ${s.driveMinutes}분"
            val fit = s.kids == false && Stage.answerMs == Stage.ANSWER_MS && !s.slowTalk && !s.quietRide && !s.alwaysQuiet && sat == setOf("ROW_1_LEFT")
            basis.setTextColor(if (fit) Color.WHITE else Color.rgb(255, 200, 60))
            status.text = buildString {
                append("${Prend.NAME} 시연 패널\n")
                if (car != null) {
                    append("차: ${"%.0f".format(car.speedKmh)}km/h 기어 ${car.gear} 브레이크 ${if (car.brake) "밟음" else "뗌"} 깜빡이 ${car.turnSignal}\n")
                    append("날씨: 와이퍼 ${if (car.wiperOn) car.wiperSwitch else "꺼짐"} 창 ${car.windows.filterValues { it > 0 }.ifEmpty { "닫힘" }} 바깥 ${car.outsideC ?: "?"}°C 야간 ${car.night}\n")
                    append("자리: ${car.seats.filterValues { it }.keys.ifEmpty { "없음" }} 문 ${car.doors.filterValues { it > 0 }.keys.ifEmpty { "닫힘" }}\n")
                }
                if (d != null) append("판단: ${d.describe()}\n")
                append(app.prend.ride.Stats.line() + "\n")
                if (BuildConfig.DEMO) append("시연 값: ${if (app.prend.ride.Timing.ptValues) "켬(안정 주행 5초 · 깜빡이 뒤 2초)" else "끔(제품 값)"}\n")
                Bridge.engine?.snapshot()?.let { s -> append("소리: ${s.entries.joinToString(" · ") { "${it.key} ${it.value}" }}\n") }
                append("재생: ${controller?.isPlaying} ${controller?.currentMediaItem?.mediaId ?: ""}")
            }
            ui.postDelayed(this, 500)
        }
    }

    private fun run(cmd: String) {
        val c = controller
        if (c == null && cmd in setOf("play", "pause", "cold")) { pending += cmd; return }
        val d = Bridge.director
        Prend.log("· 시연 패널: $cmd")
        when (cmd) {
            "play" -> c?.run {
                if (mediaItemCount == 0) setMediaItem(MediaItem.Builder().setMediaId("lofi_a").build())
                repeatMode = Player.REPEAT_MODE_ONE
                prepare(); play()
            }
            "pause" -> c?.pause()
            "rec_on" -> if (BuildConfig.DEMO) Bridge.engine?.let { e ->
                e.capture?.close()
                e.capture = WavCapture(File(filesDir, "capture.wav"), 44100, 2)
            }
            "rec_off" -> Bridge.engine?.let { e -> e.capture?.close(); e.capture = null }
            "auto" -> d?.occupancyOverride = null
            "solo" -> d?.occupancyOverride = Occupancy.SOLO
            "pair" -> d?.occupancyOverride = Occupancy.PAIR
            "group" -> d?.occupancyOverride = Occupancy.GROUP
            "talk_on" -> d?.setTalking(true)
            "talk_off" -> d?.setTalking(false)
            "rich_on" -> d?.rich = true
            "rich_off" -> d?.rich = false
            "width_on" -> d?.run { widthOverride = true; loudOverride = false }
            "loud_on" -> d?.run { widthOverride = false; loudOverride = true }
            "compare_off" -> d?.run { widthOverride = false; loudOverride = false }
            "compare_auto" -> d?.run { widthOverride = null; loudOverride = null }
            "sleep_on" -> d?.sleep = true
            "sleep_off" -> d?.sleep = false
            "baby_on" -> d?.baby = true
            "baby_off" -> d?.baby = false
            "rain_on" -> d?.manualRain = true
            "rain_off" -> d?.manualRain = false
            "cold" -> {
                c?.pause()
                ui.postDelayed({ Bridge.director?.simulateColdStart(); c?.seekTo(0); run("play") }, 400)
            }
            "arrive" -> d?.arriveNow()
            else -> when {
                cmd.startsWith("seek_") -> c?.seekTo(cmd.removePrefix("seek_").toLongOrNull() ?: 0L)
                // 시험: 마이크로 잰 '목소리가 배경보다 몇 dB 큰가'를 대신 넣는다(ratio_none이면 모름)
                cmd.startsWith("ratio_") -> d?.voiceOverBackgroundDb = cmd.removePrefix("ratio_").toDoubleOrNull()
                // PT 시연 버튼은 운행 서비스가 맡는다(새 운행·자리·운전 시간이 그쪽에 있다). 이 앱 안으로만 보낸다
                cmd.startsWith("pt_") && BuildConfig.DEMO -> sendBroadcast(Intent("app.prend.RIDE").setPackage(packageName).putExtra("cmd", cmd))
            }
        }
        when (cmd) {
            "fast_on" -> d?.demoFast = true
            "fast_off" -> d?.demoFast = false
            "settle" -> Bridge.engine?.settle()
            "consent" -> Bridge.brain?.consent(true)
            "card_baby" -> Bridge.brain?.answerCard(true, true)
            "card_nobaby" -> Bridge.brain?.answerCard(false, true)
            "clock60" -> Bridge.brain?.clockSpeed = 60.0
            "clock1" -> Bridge.brain?.clockSpeed = 1.0
            "okay" -> Bridge.brain?.sayOkay()
            "song_info" -> Bridge.brain?.songInfo()
            "telltale" -> Bridge.hub?.probeTelltales()
            "airbag" -> Bridge.hub?.probeAirbag()
            "navi_init" -> (navi ?: NaviProbe(this).also { navi = it }).init()
            "navi_route" -> navi?.route()
            "navi_wp" -> navi?.waypoint()
            "navi_wpinfo" -> navi?.waypointInfo()
            "navi_loc" -> navi?.location()
            "navi_cancel" -> navi?.cancel()
            "stt_init" -> (stt ?: SttProbe(this).also { stt = it }).init()
            "stt_hybrid" -> { stt?.release(); stt = SttProbe(this, Mode.HYBRID).also { it.init() } }
            "stt_listen" -> stt?.listen()
            "stt_stop" -> stt?.stop()
            // 시험 9-2: 운행 서비스에서 듣기(이 화면을 닫은 뒤 2초 뒤에 듣는다)
            "stt_bg" -> { startForegroundService(Intent(this, app.prend.RideService::class.java).putExtra("stt", "listen")); finish() }
            else -> if (cmd.startsWith("stt_feed_")) {
                // 시험 9: files/<이름>.wav(16kHz 모노 16비트)의 머리 44바이트를 떼고 STT에 보낸다
                val f = File(filesDir, cmd.removePrefix("stt_feed_") + ".wav")
                if (f.exists()) stt?.feed(f.readBytes().copyOfRange(44, f.length().toInt())) else Prend.log("✗ STT: 파일 없음 ${f.name}")
            } else if (cmd.startsWith("card_cat_")) {
                startService(Intent(this, app.prend.RideService::class.java).putExtra("card", cmd.removePrefix("card_cat_")))
            }
        }
    }

    override fun onDestroy() {
        navi?.release(); stt?.release()
        ui.removeCallbacks(tick)
        controller?.release()
        super.onDestroy()
    }

    companion object {
        /** 문 이름(괄호 안은 Virtual Console의 문 카드 이름) */
        private val DOORS = linkedMapOf("ROW_1_LEFT" to "운전석(Front left)", "ROW_1_RIGHT" to "동승석(Front right)", "ROW_2_LEFT" to "뒷문 왼쪽(Rear left)", "ROW_2_RIGHT" to "뒷문 오른쪽(Rear right)")
        private val SEATS = mapOf("ROW_1_LEFT" to "운전석", "ROW_1_RIGHT" to "동승석", "ROW_2_LEFT" to "뒷자리 왼쪽", "ROW_2_CENTER" to "뒷자리 가운데", "ROW_2_RIGHT" to "뒷자리 오른쪽")

        /** 열려 있는 문의 이름(시연 패널 맨 위, 운행 서비스의 ① 장면 준비 기록) */
        fun openDoors(doors: Map<String, Int>): List<String> = doors.filterValues { it > 0 }.keys.map { DOORS[it] ?: it }
    }
}
