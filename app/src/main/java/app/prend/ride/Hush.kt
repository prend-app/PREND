package app.prend.ride

import android.car.VehicleGear
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import app.prend.Bridge
import app.prend.Prend
import app.prend.car.CarState
import app.prend.ui.Stage

/**
 * 소리 물러서기(별첨 4 §2-2 후진, §4-1 드라이브스루, §4-3 급제동).
 * - 드라이브스루: 운전석 창이 닫힘(10% 아래)에서 열림(30% 이상)으로 바뀔 때 기어가 D이고 시속 10km 이하면 1초에 걸쳐 15dB 물러선다.
 *   창이 20% 아래로 닫히거나, 시속 20km를 넘거나, 3분이 지나면 3초에 걸쳐 돌아온다.
 *   닫힘에서 열림으로 바뀌는 순간만 보므로 열어 둔 채 달리던 창에는 반응하지 않고, 동승석 창만 열 때와 P(셀프 주유 등)는 하지 않는다.
 * - 후진·주차: R에 들어가면 1초에 걸쳐 12dB 물러서고, P에 넣거나 시속 15km를 넘거나 D로 25초 이어서 앞으로 갈 때까지(R과 D를 오가는 주차 내내) 그대로 둔다.
 *   끝나면 3초에 걸쳐 돌아온다(11/02: R에서만 멈추면 앞뒤로 오가는 동안 소리가 켜졌다 꺼졌다 해 정신을 흩뜨린다.
 *   차의 주차 거리 경고음이 들리게 하는 것, 주차사고의 53.8%가 후진 중(보험개발원). 페달 실수를 막는다고는 쓰지 않는다)
 * - 급제동: 1초에 15km/h 넘게 줄면 0.3초에 10dB 물러서고, 4초 머문 뒤 3초에 걸쳐 돌아온다. 급가속에는 그대로 둔다.
 * PREND 사운드가 흐르면 그 소리 안에서만 물러선다(차 음량은 건드리지 않는다). 다른 음악 앱의 소리는 낮출 수 없어(차 음량 권한이 막힘)
 * 드라이브스루와 후진 동안만 짧은 오디오 포커스를 잡아 멈췄다가 돌려준다(시험 49-2와 같은 방법). 급제동 때 다른 앱은 그대로 둔다.
 * 드라이브스루로 음악을 줄일 때만 화면 한 줄로 알리고(목소리 없이), 후진·급제동은 운전자가 바쁜 때라 그때도 나중에도 알리지 않는다(11/01 사용자: 끝나고 모아 보여 주면 생색).
 */
class Hush(context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)
    /** 운전석 창이 닫혀 있는 것을 보았는가(닫힘에서 열림으로 바뀌는 순간만 본다) */
    private var windowClosed = false
    /** 드라이브스루로 물러선 때(elapsedRealtime). 0이면 아님 */
    private var driveThruAt = 0L
    private var reverse = false
    /** 후진 뒤 D로 멈추지 않고 앞으로 가기 시작한 때(elapsedRealtime). 0이면 아님 */
    private var fwdSince = 0L
    private var brakeUntil = 0L
    private val speeds = ArrayDeque<Pair<Long, Float>>()
    private var focus: AudioFocusRequest? = null
    private var lastDb = 0.0

    fun onCar(old: CarState, now: CarState) {
        val t = SystemClock.elapsedRealtime()
        // 창 위치를 아직 모르면 보지 않는다(처음 받은 값이 열림이면 '열린 순간'으로 치지 않는다)
        val win = now.windows["ROW_1_LEFT"]
        if (win != null) {
            if (win < 0.1f) windowClosed = true
            if (win >= 0.3f && windowClosed) {
                windowClosed = false
                if (now.driving && now.speedKmh <= 10f && driveThruAt == 0L) {
                    driveThruAt = t
                    Prend.log("◆ 물러서기: 운전석 창 열림(드라이브스루)")
                    // 소리 없이 음악이 멎으면 '고장 났나'로 읽힌다: 한 일을 화면 한 줄로(목소리 없이. 화면이 안 보이면 배너가 같은 줄을 띄운다)
                    // 다른 앱 음악은 이제 멈추지 않는다(후진만, 사용자 결정 11/04): PREND 곡을 줄였을 때만 알린다
                    if (music() == true) Stage.toast("창문을 열어서 음악을 잠깐 줄였어요 · 닫으면 다시", 4_000)
                }
            }
            if (driveThruAt > 0 && (win < 0.2f || now.speedKmh > 20f)) end(if (win < 0.2f) "창 닫힘" else "시속 20km 넘음")
        }

        val r = now.gear == VehicleGear.GEAR_REVERSE
        // 후진 뒤 D로 앞으로 가기 시작한 때. 25초 이어 가면 주차가 끝난 것으로 본다(검토 11/07 멋대로 판단: 후진으로 빠져나와 출구까지 천천히 가는
        // 몇 분 동안 음악이 멈춰 있다가 큰길에서 갑자기 나왔다). 멈춰 서거나 D가 아니면 처음부터 다시 센다: R과 D를 짧게 오가는 동안은 그대로 조용하다
        val fwd = reverse && now.driving && now.speedKmh >= 1f
        if (!fwd) fwdSince = 0L else if (fwdSince == 0L) fwdSince = t
        // R에 한 번 들어가면 P에 넣거나, 시속 15km를 넘거나, D로 25초 이어서 앞으로 갈 때까지 주차 중으로 본다(R↔D를 오가는 동안 소리가 들쑥날쑥하지 않게)
        val parking = when {
            r -> true
            !reverse -> false
            now.gear == VehicleGear.GEAR_PARK -> false
            now.speedKmh > PARK_END_KMH -> false
            fwdSince > 0 && t - fwdSince >= PARK_FWD_MS -> false
            else -> true
        }
        if (parking != reverse) {
            reverse = parking
            if (!parking) fwdSince = 0L
            Prend.log(if (parking) "◆ 물러서기: 후진(주차 끝까지)" else "· 물러서기: 주차 끝(${if (now.gear == VehicleGear.GEAR_PARK) "P" else if (now.speedKmh > PARK_END_KMH) "시속 ${PARK_END_KMH.toInt()}km 넘음" else "D로 ${PARK_FWD_MS / 1000}초 앞으로"})")
        }

        speeds.addLast(t to now.speedKmh)
        while (speeds.size > 2 && t - speeds.first().first > 1_200) speeds.removeFirst()
        val ref = speeds.firstOrNull { t - it.first >= 900 }
        if (ref != null && ref.second - now.speedKmh > 15f && t > brakeUntil) {
            brakeUntil = t + 4_300
            Prend.log("◆ 물러서기: 급제동(1초에 ${(ref.second - now.speedKmh).toInt()}km/h 줄어듦)")
            // 급제동에는 PREND 곡만 줄인다(다른 앱은 그대로)
        }
        apply()
    }

    /** 지금 줄이거나 멈출 음악: PREND 곡이면 true(곡 안에서 줄임), 다른 앱 음악이면 false(잠깐 멈춤), 없으면 null */
    private fun music(): Boolean? = when {
        Bridge.director?.playing == true -> true
        runCatching { audio.activePlaybackConfigurations.any { it.audioAttributes.usage == AudioAttributes.USAGE_MEDIA } }.getOrDefault(false) -> false
        else -> null
    }

    /** 1초마다: 드라이브스루 3분, 급제동 4초, 후진 뒤 D로 앞으로 25초가 지났는지(같은 속도로 가면 차 값이 바뀌지 않아 onCar가 오지 않는다) */
    fun tick() {
        val t = SystemClock.elapsedRealtime()
        if (driveThruAt > 0 && t - driveThruAt > 3 * 60_000) end("3분 지남")
        if (reverse && fwdSince > 0 && t - fwdSince >= PARK_FWD_MS) {
            reverse = false; fwdSince = 0L
            Prend.log("· 물러서기: 주차 끝(D로 ${PARK_FWD_MS / 1000}초 앞으로)")
        }
        if (brakeUntil > 0 && t > brakeUntil) brakeUntil = 0
        apply()
    }

    private fun end(why: String) {
        driveThruAt = 0
        Prend.log("· 물러서기 끝: 드라이브스루($why)")
    }

    private fun apply() {
        val driveThru = driveThruAt > 0
        val brake = brakeUntil > 0
        // 가장 크게 물러설 까닭을 따른다(내려가는 속도도 그 까닭의 것)
        val (db, down) = when {
            driveThru -> -15.0 to 15.0
            reverse -> -12.0 to 12.0
            brake -> -10.0 to 33.0
            else -> 0.0 to 15.0
        }
        if (db != lastDb) {
            // 돌아올 때는 3초에 걸쳐
            Bridge.director?.hush(db, down, maxOf(-lastDb, 1.0) / 3.0)
            lastDb = db
        }
        // 다른 앱 음악은 후진할 때만 멈춘다(주차 거리 경고음이 들리게). 창문을 열 때(드라이브스루·주차권·학원 앞 인사)는 멈추지 않는다(사용자 결정 11/04)
        val pauseOthers = reverse && Bridge.director?.playing != true
        if (pauseOthers && focus == null) grab() else if (!pauseOthers && focus != null) release()
    }

    /** 다른 음악 앱을 멈춘다(잠깐 포커스를 잡는다. 놓으면 그 앱이 다시 튼다) */
    private fun grab() {
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { Prend.log("· 물러서기 포커스 변화 $it") }
            .build()
        focus = req
        // 주차 코치·혹시 사고처럼 후진 중에도 이어 하는 한마디가 이 포커스에 끊기지 않게 알린다(voice/Speaker.onFocus)
        Bridge.ownFocusAt = SystemClock.elapsedRealtime()
        val r = audio.requestAudioFocus(req)
        Prend.log("◆ 물러서기: 다른 음악 멈춤(포커스 ${if (r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "허락" else "거절 $r"})")
    }

    fun release() {
        val f = focus ?: return
        audio.abandonAudioFocusRequest(f)
        focus = null
        Prend.log("· 물러서기: 다른 음악 돌려줌")
    }

    companion object {
        /** 이 속도를 넘으면 주차가 끝난 것으로 본다(11/02) */
        const val PARK_END_KMH = 15f
        /** 후진 뒤 D로 이만큼 이어서 앞으로 가면 주차가 끝난 것으로 본다(검토 11/07) */
        const val PARK_FWD_MS = 25_000L
    }
}
