package app.prend.ride

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.car.VehicleGear
import android.content.Context
import android.content.Intent
import app.prend.Prend
import app.prend.RideService
import app.prend.ui.CALL_WAIT
import app.prend.ui.Guide
import app.prend.ui.PrendActivity
import app.prend.ui.SOS_LINE
import app.prend.ui.Scene
import app.prend.ui.StageUi

/**
 * PREND 화면이 보이지 않을 때의 화면 위 알림(배너) 한 자리. 그때그때 한 가지만 띄운다.
 * - PREND가 물을 때: 질문과 버튼 둘(말로 대답해도 된다)
 * - [프렌드]를 부른 뒤: 듣는 중, 그다음 한 일 한 줄
 * - 급할 때(E): 지금 걸음 한 줄과 그 화면 열기. 쪽잠: 그 화면 열기
 * - 그 밖에는 [프렌드]. 세워 둔 동안(P)은 [프렌드 부르기] 버튼 배너를 띄워 두고, 달리는 동안은 작은 알림(버튼 없음)으로
 *   10초쯤 보였다가 위쪽 종 목록으로 들어가 내비 길 안내를 가리지 않는다. 종 목록에서 누르면 화면을 바꾸지 않고 바로 듣는다
 * - 후진하는 동안은 아무것도 띄우지 않는다
 * Pleos의 알림(10/26 시험 52-2): 분류가 navigation·msg·call인 것만 화면 위에 뜨고(나머지 16가지는 안 뜸), 분류가 없으면 목록에도 없다.
 * 분류가 있으면 중요도를 낮추거나 묶음으로 조용히 올려도 화면 위에 뜬다. navigation 배너는 버튼이 있든 없든 누르거나 앱이 걷을 때까지 떠 있고,
 * msg 분류의 버튼 없는 알림만 10초쯤 뒤 저절로 목록으로 들어간다. 사용자가 위로 밀어도 목록으로 들어가 버튼째 남는다.
 * 지울 수 없는(ongoing) 알림은 목록에도 보이지 않는다. 들은 말은 배너에 싣지 않는다(알림은 시스템에 남는다).
 */
class Banner(private val context: Context) {

    private val nm = context.getSystemService(NotificationManager::class.java)

    /**
     * 띄울 모양. 지난번과 같으면 다시 올리지 않는다(듣는 소리 크기처럼 자주 바뀌는 값은 넣지 않는다).
     * small이면 달리는 동안의 작은 알림: 버튼 없이 누르면 [프렌드]를 부르고, 10초쯤 뒤 종 목록으로 들어간다
     */
    private data class Look(val title: String, val text: String, val actions: List<Pair<String, String>>, val open: Scene? = null, val small: Boolean = false, val alert: Boolean = true)

    private var shown: Look? = null

    init {
        // 목소리와 차임은 PREND가 따로 내므로 알림 소리와 떨림은 끈다
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, Prend.CALL, NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null); enableVibration(false) },
        )
    }

    fun update(ui: StageUi, gear: Int) {
        val look = if (ui.visible) null else look(ui, gear)
        if (look == shown) return
        val old = shown
        shown = look
        if (look == null) { nm.cancel(ID); Prend.log("· 배너 걷음"); return }
        // 같은 자리를 고쳐 쓴다(떠 있으면 그 자리에서 바뀐다). 큰 배너와 작은 알림 사이를 옮길 때는 걷었다가 새로 올린다
        if (old != null && old.small != look.small) nm.cancel(ID)
        nm.notify(ID, build(look))
        // 알림 글로 만든 배너는 글 대신 글자 수만 남긴다(주차장·정비소 이름 같은 장소가 들어갈 수 있다)
        val label = if (look.title == ui.toast) "알림 글 ${look.title.length}자" else look.title
        Prend.log("· 배너: $label${if (look.small) "(작게)" else ""}")
    }

    fun clear() { shown = null; nm.cancel(ID) }

    private fun look(ui: StageUi, gear: Int): Look? {
        val parked = gear == VehicleGear.GEAR_PARK
        // 세워 둔 동안의 버튼은 [프렌드 부르기] 하나(본문을 누르면 화면이 열린다). [위급상황]은 잘못 누르기 쉬워 두지 않는다:
        // 에어백이 터지면 목소리가 저절로 안내하고(Crash), 그 밖에는 PREND 화면의 Emergency나 "사고 났어"로 연다(사용자 결정 10/26)
        val call = listOf("${Prend.CALL} 부르기" to CALL)
        val ask = ui.ask
        val listening = ui.listen?.done == false
        return when {
            // 구조 요청(에어백 뒤 대답 없음): 화면처럼 구조하러 온 사람이 읽을 말을 둔다(걸음 '1/7 · 비상등'이 아니라)
            // 아플 때의 구조 요청도 같은 배너로(11/05). 화면을 열지 못해도 위치를 읽게 119에 말할 위치를 붙인다
            (ui.scene == Scene.ACCIDENT || ui.scene == Scene.ILLNESS) && ui.rescue -> {
                val why = ui.rescueWhy
                val ask = if (!ui.baby && ui.kids == true) "뒷좌석에 아이가 있는지 살펴보고 119에 신고해 주세요" else "119에 신고해 주세요"
                Look(
                    if (ui.baby) "이 차 뒷좌석에 아이가 타고 있어요" else "운전자가 대답하지 않아요",
                    listOfNotNull(why, ask, ui.place?.let { "119에 말할 위치 · $it" }).joinToString(" · "), listOf("열기" to OPEN), ui.scene,
                )
            }
            // 사고·아플 때 안내 중의 확인 카드("누가 아프세요?", Stage.confirm, 검토 11/07 멋대로 판단): 화면을 열지 않아도 단추로 답하게. 답하면 다시 걸음 배너로
            (ui.scene == Scene.ACCIDENT || ui.scene == Scene.ILLNESS) && ask != null && ask.id.startsWith("Q_") ->
                Look(ask.title, if (listening) "${ask.text} · 듣고 있어요" else ask.text,
                    listOfNotNull(ask.yes to "yes:${ask.id}", ask.no.takeIf { it.isNotBlank() }?.let { it to "no:${ask.id}" }), ui.scene)
            // 급할 때: 지금 걸음을 배너에 그대로 적는다(화면을 열지 못해도 읽을 수 있게). 걸음이 바뀌면 다시 띄운다
            ui.scene.isE -> {
                val steps = if (ui.scene == Scene.EMERGENCY) emptyList() else Guide.steps(ui)
                // 응급처치 카드는 세워 둔 동안만 보인다(화면처럼 배너도 가린다)
                val now = if (ui.scene == Scene.AID && !parked) null else steps.getOrNull(ui.step - 1)
                // "SOS 버튼을 누르거나 119에 알리세요"를 말했으면 그 문장을 제목(가장 큰 글씨)으로 둔다. 걸음은 본문에
                val title = if (ui.sos && ui.scene == Scene.ACCIDENT) SOS_LINE
                    else when (ui.scene) { Scene.ACCIDENT -> if (ui.fire) "연기·불이 날 때 할 일" else if (ui.breakdown) "고장 났을 때 할 일" else "사고 났을 때 할 일"; Scene.ILLNESS -> "갑자기 아플 때 할 일"; Scene.AID -> "응급처치 · ${ui.aid.label}"; else -> "긴급" }
                // 통화(블루링크 상담원)를 기다리는 동안은 말이 없는 까닭을 먼저(11/06)
                val body = ui.toast?.takeIf { it == app.prend.ui.Stage.DIAL_HINT } ?: now?.let { "${ui.step}/${steps.size} · ${it.title.replace("\n", " ")}" } ?: if (ui.scene == Scene.AID) "세우면 카드를 보여 드려요" else "눌러서 차례대로 보세요"
                Look(title, if (ui.callWait) "$CALL_WAIT · $body" else body, listOf("열기" to OPEN), ui.scene)
            }
            // 후진하는 동안은 새로 띄우지 않고 떠 있던 것도 거둔다
            gear == VehicleGear.GEAR_REVERSE -> null
            // 화면을 여는 질문(같이 고르기)의 [yes]는 서비스를 거치지 않고 그 화면을 바로 연다.
            // 배너에만 둘 버튼(한 시간 물음의 [쉬어 갈 곳])이 있으면 [yes] 자리에 둔다(화면을 열지 않은 운행은 말 대신 버튼으로 받는다, 별첨 2 §5-2).
            // 버튼 하나짜리 카드(우회전 [알고 있어요])는 no가 비어 있어 빈 버튼을 두지 않는다
            ask != null -> Look(ask.title, if (listening) "${ask.text} · 듣고 있어요" else ask.text,
                listOfNotNull(ask.alt?.let { it to "alt:${ask.id}" } ?: (ask.yes to (ask.opens?.let { "$SCENE${it.name}" } ?: "yes:${ask.id}")), ask.no.takeIf { it.isNotBlank() }?.let { it to "no:${ask.id}" }))
            // 혹시 사고에 대답이 없었다: 세워 둔 동안 10분, 누르면 사고 났을 때 할 일
            ui.bumpHint && parked -> Look("혹시 사고였나요?", "눌러서 사고 났을 때 할 일 보기", listOf("열기" to "$SCENE${Scene.ACCIDENT.name}"), Scene.ACCIDENT)
            ui.scene == Scene.NAP -> Look("쪽잠 ${ui.napMinutes}분", "눌러서 쪽잠 화면 열기", listOf("열기" to OPEN), Scene.NAP)
            ui.scene == Scene.PICK -> Look("같이 고르기", "눌러서 같이 골라요", listOf("열기" to OPEN), Scene.PICK)
            parked -> when {
                // 세워 둔 동안도 달리는 동안과 같은 작은 알림(큰 것과 작은 것을 오가며 다시 떠 내비를 가리지 않게, 11/04 검토)
                listening -> Look(Prend.CALL, "듣고 있어요", emptyList(), small = true)
                ui.toast != null -> Look(ui.toast, "${Prend.CALL} · 눌러서 말하기", emptyList(), small = true)
                // 세워 둔 동안의 부르기도 작은 알림으로(내비의 목적지 칸을 가리지 않게, 사용자 결정 11/04). 누르면 바로 듣는다
                else -> Look(Prend.CALL, say(ui), emptyList(), small = true, alert = false)
            }
            // 달리는 동안: 작은 알림(종 목록에서 누르면 바로 듣는다)
            listening -> Look(Prend.CALL, "듣고 있어요", emptyList(), small = true)
            ui.toast != null -> Look(ui.toast, "${Prend.CALL} · 눌러서 말하기", emptyList(), small = true)
            else -> Look(Prend.CALL, say(ui), emptyList(), small = true, alert = false)
        }
    }

    /** 가만히 있는 배너의 글. 말소리를 재는 동안은 화면의 위 막대처럼 늘 알린다 */
    private fun say(ui: StageUi) = if (ui.talkLive) "눌러서 말하기 · ● 말소리 여부만 재는 중(저장 안 함)" else "눌러서 말하기"

    private fun build(l: Look): Notification {
        // 분류가 있어야 화면 위에 뜨고 목록에도 들어간다. 작은 알림은 msg(10초쯤 뒤 저절로 목록으로), 나머지는 navigation(누를 때까지 떠 있음)
        val b = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(l.title)
            .setContentText(l.text)
            .setContentIntent(if (l.small) action(CALL) else openApp(l.open))
            .setCategory(if (l.small) Notification.CATEGORY_MESSAGE else Notification.CATEGORY_NAVIGATION)
            // 가만히 있는 [프렌드]로 돌아갈 때는 다시 띄우지 않는다(목록에 들어간 뒤 내비를 다시 가리지 않게). 듣는 중·한 일은 잠깐 보인다
            .setOnlyAlertOnce(!l.alert)
        l.actions.forEach { (label, what) ->
            val pi = when {
                what == OPEN -> openApp(l.open)
                what.startsWith(SCENE) -> openScene(Scene.valueOf(what.removePrefix(SCENE)))
                else -> action(what)
            }
            b.addAction(Notification.Action.Builder(null, label, pi).build())
        }
        return b.build()
    }

    private fun action(what: String): PendingIntent = PendingIntent.getService(
        context, what.hashCode(), Intent(context, RideService::class.java).putExtra(EXTRA, what),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openApp(scene: Scene?): PendingIntent = PendingIntent.getActivity(
        context, scene?.ordinal ?: -1,
        Intent(context, PrendActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply { scene?.let { putExtra("open", it.name) } },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** 그 장면으로 바꾸며 연다(같이 고르기의 [골라 볼게요]). openApp은 지금 장면 그대로 연다 */
    private fun openScene(scene: Scene): PendingIntent = PendingIntent.getActivity(
        context, 100 + scene.ordinal,
        Intent(context, PrendActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("scene", scene.name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val SCENE = "scene:"
        const val CHANNEL = "prend_banner"
        const val ID = 10
        const val EXTRA = "banner"
        const val CALL = "call"
        const val OPEN = "open"
    }
}
