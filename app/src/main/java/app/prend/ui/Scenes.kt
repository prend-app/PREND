package app.prend.ui

import app.prend.ride.CareBook
import app.prend.ride.SlowTalk
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.prend.Prend
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.adamglin.phosphoricons.regular.Check
import com.adamglin.phosphoricons.regular.List
import com.adamglin.phosphoricons.regular.Wrench
import com.adamglin.phosphoricons.fill.Baby
import com.adamglin.phosphoricons.fill.Ear
import com.adamglin.phosphoricons.fill.Wrench
import com.adamglin.phosphoricons.fill.Fire
import com.adamglin.phosphoricons.fill.Toilet
import com.adamglin.phosphoricons.fill.DotsThreeCircle
import com.adamglin.phosphoricons.fill.SpeakerSimpleSlash
import com.adamglin.phosphoricons.fill.Wind
import com.adamglin.phosphoricons.fill.CheckSquare
import com.adamglin.phosphoricons.regular.Square
import com.adamglin.phosphoricons.fill.Camera
import com.adamglin.phosphoricons.fill.CarProfile
import com.adamglin.phosphoricons.fill.Handshake
import com.adamglin.phosphoricons.fill.Notepad
import com.adamglin.phosphoricons.fill.Phone
import com.adamglin.phosphoricons.fill.FirstAidKit
import com.adamglin.phosphoricons.fill.PhoneCall
import com.adamglin.phosphoricons.fill.Siren
import com.adamglin.phosphoricons.fill.Warning
import com.adamglin.phosphoricons.fill.Coffee
import com.adamglin.phosphoricons.fill.Heartbeat
import com.adamglin.phosphoricons.fill.Hospital
import com.adamglin.phosphoricons.fill.Lamp
import com.adamglin.phosphoricons.fill.MapPin
import com.adamglin.phosphoricons.fill.MoonStars
import com.adamglin.phosphoricons.fill.CloudRain
import com.adamglin.phosphoricons.fill.ArrowBendUpRight
import com.adamglin.phosphoricons.fill.MusicNotes
import com.adamglin.phosphoricons.fill.Pause
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.fill.SkipBack
import com.adamglin.phosphoricons.fill.SkipForward
import com.adamglin.phosphoricons.fill.SteeringWheel
import com.adamglin.phosphoricons.fill.Thermometer
import com.adamglin.phosphoricons.fill.ThermometerCold
import com.adamglin.phosphoricons.fill.ThermometerHot
import com.adamglin.phosphoricons.regular.Alarm
import com.adamglin.phosphoricons.regular.CaretLeft
import com.adamglin.phosphoricons.regular.CaretRight
import com.adamglin.phosphoricons.regular.Fan
import com.adamglin.phosphoricons.regular.Flame
import com.adamglin.phosphoricons.regular.MapPin
import com.adamglin.phosphoricons.regular.Microphone
import com.adamglin.phosphoricons.regular.NavigationArrow
import com.adamglin.phosphoricons.regular.Info
import com.adamglin.phosphoricons.fill.Info
import com.adamglin.phosphoricons.fill.Database
import com.adamglin.phosphoricons.fill.Code
import com.adamglin.phosphoricons.fill.ChatCircleDots
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import com.adamglin.phosphoricons.fill.ForkKnife
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 지금 흐르는 곡(화면이 MediaController에서 읽는다). other면 다른 음악 앱의 곡이고 art는 그 앱이 준 표지.
 * 재생 위치는 넣지 않는다(위치 막대만 따로 읽는다. 넣으면 곡이 흐르는 내내 화면 전체를 다시 짠다)
 */
data class Song(val title: String, val sub: String, val playing: Boolean, val durMs: Long, val other: Boolean = false, val art: ImageBitmap? = null)

/** 화면의 손짓을 받는 곳 */
class Acts(
    val friend: () -> Unit = {},
    val sos: () -> Unit = {},
    val secret: () -> Unit = {},
    val playPause: () -> Unit = {},
    val prev: () -> Unit = {},
    val next: () -> Unit = {},
)

private val tnum = "tnum"

private fun mmss(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

private fun hm(min: Int) = if (min >= 60) "${min / 60}시간 ${min % 60}분" else "${min}분"

// ── 바탕 층: 유리가 비출 것(색 번짐, 곡, 큰 글) ──

/** 장면의 바탕(색 번짐·붉은 빛). 첫 화면·같이 고르기·기억은 같은 바탕이라 그 사이를 옮겨도 바탕은 다시 섞지 않는다 */
enum class Ground { NIGHT, NIGHT_DRIVE, E_GLOW, E, NONE }

val Scene.ground: Ground get() = when (this) {
    Scene.HOME, Scene.PICK, Scene.MEMO, Scene.CARE -> Ground.NIGHT
    Scene.DRIVE -> Ground.NIGHT_DRIVE
    Scene.EMERGENCY -> Ground.E_GLOW
    Scene.ACCIDENT, Scene.ILLNESS, Scene.AID -> Ground.E
    // 쪽잠은 제 바탕(검정)을 Nap이 그린다
    Scene.NAP -> Ground.NONE
}

/**
 * 화면의 얼굴: 장면과, 첫 화면이면 달리는 중인가(D·R·N, 시연 값이 아닐 때). 이것이 바뀔 때만 바탕과 글을 섞어 바꾼다.
 * 달리는 동안의 첫 화면은 멈춘 곡 판과 판 하나만 둔다(접수양식 '멈춘 글 한 줄과 큰 버튼 두 개까지')
 */
data class Face(val scene: Scene, val driving: Boolean) {
    val ground get() = if (driving) Ground.NIGHT_DRIVE else scene.ground
}

val StageUi.face get() = Face(scene, scene == Scene.HOME && brief)

/** 움직이지 않는 바탕은 한 장으로 구워 둔다(위의 글·막대가 바뀔 때마다 큰 원 넷과 결을 다시 그리지 않게) */
@Composable
fun SceneGround(g: Ground) {
    if (g == Ground.NONE) return
    Box(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        when (g) {
            Ground.NIGHT -> Mesh(NIGHT)
            Ground.NIGHT_DRIVE -> Mesh(NIGHT_DRIVE)
            Ground.E_GLOW -> EBack(glow = true)
            Ground.E -> EBack(glow = false)
            Ground.NONE -> Unit
        }
    }
}

@Composable
fun SceneBack(scene: Scene, ui: StageUi, song: Song, a: Acts, driving: Boolean = false) {
    // 장면 층은 늘 화면 크기로: 바뀌는 동안(반투명) 이 층만큼만 그려져, 곡 표지 그림자처럼 줄 밖으로 번진 것이 잘렸다 나타나지 않게
    Box(Modifier.fillMaxSize()) {
        when (scene) {
            // 11/05 큰 틀(얼굴은 E): 세워 둔 첫 화면 가운데는 '급할 때' 판(앞 층의 HomeE, 위치 줄이 바뀌어도 유리를 다시 흐리지 않게), 달리는 동안은 급할 때 한 줄(누르면 E 첫 화면)
            Scene.HOME -> if (driving) DriveE(a) else Unit
            Scene.DRIVE -> DriveE(a)
            Scene.EMERGENCY -> EmergencyHead()
            Scene.ACCIDENT, Scene.ILLNESS -> if (ui.rescue) RescueHead(ui) else StepSlide(ui) { StepHead(scene, it) }
            Scene.AID -> StepSlide(ui) { AidHead(it) }
            Scene.NAP -> Nap(ui)
            Scene.PICK -> PickHead(ui)
            Scene.MEMO -> if (ui.about) AboutHead() else if (ui.memoMore && !ui.daeri) MemoMoreHead() else MemoHead()
            Scene.CARE -> CareHead(ui)
        }
    }
}

// ── 앞 층: 위 막대, 유리 판, 유리 위의 글과 버튼 ──

/**
 * 위 막대(앞 층 맨 밑). 장면이 바뀌어도 섞이지 않게 장면 층 밖에 한 번만 둔다: 기어 캡슐이 미끄러지고 오른쪽 캡슐만 바뀐다
 */
@Composable
fun SceneTop(ui: StageUi, a: Acts) {
    val scene = ui.scene
    val right = when {
        scene.isE || scene == Scene.PICK || scene == Scene.MEMO || scene == Scene.CARE -> TopRight.CLOSE
        scene == Scene.NAP -> TopRight.NONE
        else -> TopRight.NORMAL
    }
    // 마이크로 말소리 여부를 재는 동안은 늘 알린다(별첨 1: 켜진 동안 "말소리 여부만 · 저장 안 함")
    val moment = ui.moment + if (ui.talkLive) "   ● 말소리 여부만 재는 중 · 저장 안 함" else ""
    TopBar(ui.lit, moment, right, a.friend, a.sos, onClose = { Stage.go(Scene.HOME) }, onSecret = a.secret, presence = ui.presence, tag = ui.demoTag)
}

/** pos는 지금 재생 위치(위치 막대만 읽는다), level은 듣는 동안의 목소리 크기(말하기 막대가 그릴 때만 읽는다), driving은 달리는 동안의 첫 화면 */
@Composable
fun SceneFront(scene: Scene, ui: StageUi, song: Song, pos: () -> Long, level: () -> Float, a: Acts, driving: Boolean = false) {
    Box(Modifier.fillMaxSize()) {
        when (scene) {
            Scene.HOME -> {
                if (!driving) HomeCenter(ui, a)
                AskSlot(ui, driving, song, a)
            }
            Scene.DRIVE -> {
                // 쉼터 카드는 쉼터를 묻는 동안만. 그 위에 확인 카드(Q_)가 뜨면 같은 자리에 그 카드를 그린다: 쉼터 카드만 그려 확인 카드가 보이지 않았고,
                // 쉼터에 답한다고 한 "응"이 보이지 않는 카드로 갔다. 묻는 것이 없으면 쉼터 카드의 죽은 단추 대신 달리는 동안의 판(11/08 오류 점검 A4·E1)
                val at = Modifier.align(Alignment.BottomStart)
                val ask = ui.ask
                when {
                    ask?.id == "REST" -> DriveCard(ui, at)
                    ask != null && homeAsk(ask.id) -> DriveAsk(ui, ask, at)
                    ui.demo -> DriveCard(ui, at)
                    else -> DrivePlate(ui, at)
                }
            }
            Scene.EMERGENCY -> { EmergencyListen(ui, level); EmergencyCards() }
            Scene.ACCIDENT, Scene.ILLNESS -> if (ui.rescue) RescueFront(ui) else StepFront(scene, ui)
            Scene.AID -> { AidFront(ui); SideAsk(ui, AID_LOW) }
            // 깬 뒤 3분 같은 쪽잠 화면에서 뜬 첫 화면 물음도 띠로(11/08 오류 점검 E2: 확인 카드만 그려 다른 물음은 보이지 않았다)
            Scene.NAP -> SideAsk(ui, 40.dp, any = true)
            Scene.PICK -> { PickFront(ui); TopAsk(ui) }
            Scene.MEMO -> { MemoFront(ui); TopAsk(ui) }
            Scene.CARE -> { CareFront(ui); TopAsk(ui) }
        }
    }
}

/** 5 → "5°", −3 → "−3°" */
private fun deg(v: Float) = v.roundToInt().let { (if (it < 0) "−" else "") + "${kotlin.math.abs(it)}°" }

// ── 1. 첫 화면(P): 가운데는 '급할 때'(11/05 큰 틀: 얼굴은 E, 매일은 말할 때 고르기) ──

/** 세워 둔 첫 화면에서 '급할 때' 판 자리를 차지하는 물음: 혹시 사고·다시 앉음·고속도로 정차(검토 11/06: 차가 먼저 알아챈 순간이 평범한 카드로 판 아래에 작게 떴다) */
private val BIG_ASKS = setOf("BUMP", "BUMPBACK", "HSTOP")

/**
 * 세워 둔 첫 화면의 가운데: 평소에는 '급할 때' 판, 차가 먼저 알아챈 물음(BIG_ASKS)이 떠 있는 동안은 그 자리에서 아래 위젯 자리까지 크게.
 * 물음이 끝나면 '급할 때' 판으로 돌아온다(옅게 바뀐다)
 */
@Composable
private fun HomeCenter(ui: StageUi, a: Acts) {
    AnimatedContent(
        ui.ask?.takeIf { it.id in BIG_ASKS },
        modifier = Modifier.fillMaxSize(),
        contentKey = { it?.id },
        transitionSpec = { fadeIn(tween(280)) togetherWith fadeOut(tween(160)) },
        label = "homeCenter",
    ) { big ->
        // 판들은 제 높이(HomeE 440)로 서야 한다. AnimatedContent는 꽉 찬 크기를 최소값으로 넘겨 height(440)이 화면 끝까지 늘어났다(11/07 시험: 아래 위젯과 겹침). Box가 최소값을 풀어 준다
        Box(Modifier.fillMaxSize()) { if (big == null) HomeE(ui, a) else BigAsk(ui, big) }
    }
    // 차 속도·알림 허락이 없으면 '급할 때' 판 아래 한 줄과 [설정 열기](검토 11/06). 물음 카드가 떠 있는 동안은 비킨다(아래에서 떠오르는 카드와 겹치지 않게)
    if (ui.ask == null && (ui.speedOff || ui.notifOff)) PermLine(ui)
}

/** 허락이 없을 때의 한 줄(판 아래 740~840, 위젯 위 876 전까지). 차 속도가 먼저: 없으면 혹시 사고가 조용히 꺼진다 */
@Composable
private fun PermLine(ui: StageUi) {
    val speed = ui.speedOff
    val shape = sq(40)
    Row(
        Modifier.padding(start = 72.dp, end = 72.dp, top = 740.dp).fillMaxWidth().height(100.dp).clip(shape)
            .background(C.Amber.copy(alpha = .12f)).border(1.dp, C.Amber.copy(alpha = .3f), shape).padding(start = 40.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Icon(PhosphorIcons.Fill.Warning, null, tint = C.Amber, modifier = Modifier.size(40.dp))
        Text(if (speed) "차 속도를 허락해야 혹시 사고를 물을 수 있어요" else "알림을 허락해야 화면을 닫아도 물음이 떠요",
            style = t(30, W600), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Btn("설정 열기", primary = true, height = 76, size = 28, pad = 36) {
            Stage.openSettings?.invoke(if (speed) "speed" else "notif") ?: Stage.toast("차 설정의 앱 목록에서 ${Prend.NAME}를 골라 허락해 주세요", 4_000)
        }
    }
}

/**
 * 차가 먼저 알아챈 물음을 크게: 붉은 판, 큰 제목, 꼭 알아야 할 줄(고속도로 대피·견인 번호)은 큰 글씨로, 큰 단추 둘과 듣는 중 고리.
 * 고속도로 정차는 '차 뒤 말고 가드레일 밖으로'를 제목으로, 견인 번호를 큰 줄로 둔다(배너는 Ask의 제목·설명 그대로)
 */
@Composable
private fun BigAsk(ui: StageUi, ask: Ask) {
    val hstop = ask.id == "HSTOP"
    // 고속도로 혹시 사고는 설명 줄이 대피 문장이다(ride/Care: "차 뒤로 가지 말고 가드레일 밖으로 나가세요")
    val evac = !hstop && "가드레일" in ask.text
    val card = sq(56)
    Column(
        Modifier.padding(start = 72.dp, end = 72.dp, top = 276.dp, bottom = 72.dp).fillMaxSize()
            .frosted(card).glowOnce(C.Red, card).border(2.dp, C.Red.copy(alpha = .45f), card)
            .padding(horizontal = 56.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                IconTile(PhosphorIcons.Fill.Warning, C.Red, C.Red.copy(alpha = .16f), 80, 26, 44)
                Text(if (hstop) ask.title else askLook(ask).third, style = t(34, W700, C.Red))
            }
            Text(if (hstop) "차 뒤 말고\n가드레일 밖으로" else ask.title, style = t(84, W800, tracking = -.035, line = 1.12), modifier = Modifier.padding(top = 36.dp))
            when {
                // 한국도로공사 긴급견인(Guide.BREAK_HW와 같은 사실)
                hstop -> {
                    Text("견인 1588-2504", style = t(64, W800, tracking = -.03).copy(fontFeatureSettings = tnum), modifier = Modifier.padding(top = 30.dp))
                    Text("비상등을 켜세요 · 한국도로공사 무료 긴급견인 · 24시간", style = t(32, W500, C.L2), modifier = Modifier.padding(top = 12.dp))
                }
                evac -> Text(ask.text, style = t(52, W700, tracking = -.025, line = 1.15), modifier = Modifier.padding(top = 30.dp))
                else -> Text(ask.text, style = t(34, W500, C.L2), modifier = Modifier.padding(top = 24.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            // 사고 쪽 대답은 빨간 단추(한 화면에 하나). 고속도로 정차의 [고장 났어요]는 흰 단추
            Btn(ask.yes, primary = true, danger = !hstop, height = 120, size = 40, pad = 56) { Stage.answer(ask.id, true) }
            if (ask.no.isNotBlank()) Btn(ask.no, primary = false, height = 120, size = 40, pad = 56) { Stage.answer(ask.id, false) }
            Listening(ui)
        }
    }
}

/**
 * 세워 둔 첫 화면의 가운데. 왼쪽 큰 판을 누르면 E 첫 화면([긴급]과 같다), 오른쪽은 부르지 않아도 PREND가 먼저 알아채는 셋과 119에 말할 위치,
 * 맨 아래에 말할 때 고르기 한 줄. 위치 줄은 10초마다 바뀌어 앞 층에 둔다(바탕 층이면 유리 판이 그때마다 다시 흐려진다)
 */
@Composable
private fun HomeE(ui: StageUi, a: Acts) {
    Row(Modifier.padding(start = 72.dp, end = 72.dp, top = 276.dp).fillMaxWidth().height(440.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        val card = sq(56)
        Box(Modifier.weight(1.5f).fillMaxHeight().press(card, onClick = a.sos).frosted(card).border(2.dp, C.Red.copy(alpha = .45f), card)) {
            Column(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 48.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    IconTile(PhosphorIcons.Fill.Warning, C.Red, C.Red.copy(alpha = .16f), 96, 30, 52)
                    Text("급할 때", style = t(34, W700, C.Red))
                }
                Column {
                    Text("사고·고장·갑자기 아플 때\n여기를 누르세요", style = t(66, W800, tracking = -.035, line = 1.12))
                    Text("무엇부터 할지 한 걸음씩 말해 드려요", style = t(30, color = C.L2), modifier = Modifier.padding(top = 18.dp))
                }
            }
            Icon(PhosphorIcons.Regular.CaretRight, null, tint = C.L3, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 44.dp).size(72.dp))
        }
        // 맨 아래(위치 줄 뒤)에 늘 같은 한 줄('깜빡이·후진·회전 앞에선 말을 미뤄요')을 두려고 위아래 여백을 40 → 34로
        Column(Modifier.weight(1f).fillMaxHeight().frosted(card).padding(horizontal = 44.dp, vertical = 34.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text("부르지 않아도 먼저", style = t(26, W600, C.L2b))
            // 차가 먼저 알아채는 셋(별첨 C 3: 급제동 뒤 멈춤·에어백·고속도로 위 정차). '운전하다 아프면'은 사람이 눌러야 시작해 뺐다(검토 11/06).
            // 첫 줄은 조건을 다 적는다(급제동할 때마다 묻는 것이 아니다, ride/Bump)
            listOf(
                PhosphorIcons.Fill.CarProfile to "급제동 뒤 서서 문을 열면\n혹시 사고인지 물어요",
                PhosphorIcons.Fill.Warning to "고속도로에 서면\n대피부터 알려요",
                PhosphorIcons.Fill.Siren to "에어백이 터지면\n블루링크 통화 뒤 이어서 안내해요",
            ).forEach { (icon, line) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Icon(icon, null, tint = C.L2, modifier = Modifier.size(34.dp))
                    Text(line, style = t(26, W500, C.L1, line = 1.2))
                }
            }
            // 이름표와 위치를 두 줄로: 고속도로 이정("○○고속도로 ○○ 방향 144.6km 지점")이 잘리지 않게(검토 11/05)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(PhosphorIcons.Fill.MapPin, null, tint = C.L2b, modifier = Modifier.padding(top = 2.dp).size(28.dp))
                Column {
                    Text("119에 말할 위치", style = t(22, W600, C.L2b))
                    // 위치가 둘이면(고속도로인지 애매: 동네 한 줄 + "고속도로 위라면 …km") 이정 숫자가 잘리지 않게 한 줄 더, 글은 조금 작게(11/08 에뮬레이터: "…일산 방…"에서 잘림)
                    val both = Guide.placeBoth(ui.place)
                    Text(ui.place ?: "찾는 중이에요", style = t(if (both) 22 else 24, W500, C.L1), maxLines = if (both) 3 else 2, overflow = TextOverflow.Ellipsis)
                }
            }
            // 말할 때 고르기를 늘 같은 한 줄로(사용자 결정 11/06). 말을 삼키는 그 순간에는 글을 띄우지 않는다(가장 바쁠 때 눈을 끌지 않게)
            Text("깜빡이·후진·회전 앞에선 말을 미뤄요", style = t(24, W500, C.L2))
        }
    }
}

/** 곡 표지. 다른 음악 앱의 곡은 그 앱이 준 표지를, 없으면 음표 판을 쓴다 */
@Composable
private fun SongCover(song: Song, s: Dp) {
    if (!song.other) { Cover(s); return }
    val shape = sq((s.value * .12f).toInt())
    Box(
        Modifier.size(s).clip(shape).background(Color.White.copy(alpha = .08f)).border(1.dp, Color.White.copy(alpha = .12f), shape),
        contentAlignment = Alignment.Center,
    ) {
        val art = song.art
        if (art != null) Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(PhosphorIcons.Fill.MusicNotes, null, tint = C.L2, modifier = Modifier.size(s * .32f))
    }
}

/** 곡 버튼: 아이콘은 그대로 두고 누름 칸만 96으로(앞뒤 56·재생 84 아이콘이 작은 칸이 되지 않게) */
@Composable
private fun Tap(icon: ImageVector, size: Int, onClick: () -> Unit) {
    Box(Modifier.size(96.dp).press(CircleShape, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = C.L1, modifier = Modifier.size(size.dp))
    }
}

/** 첫 화면 위젯. 새로 배운 것은 알림과 기억 화면의 '바뀜'으로만 알린다(위젯 위의 '새로 기억함' 줄은 뺐다, 검토-방향노인초보 2-2) */
@Composable
private fun HomeWidgets(ui: StageUi, song: Song, a: Acts, modifier: Modifier) {
    Column(modifier.padding(start = 72.dp, end = 72.dp, bottom = 64.dp).fillMaxWidth()) {
        HomeWidgetRow(ui, song, a)
    }
}

@Composable
private fun HomeWidgetRow(ui: StageUi, song: Song, a: Acts) {
    Row(
        Modifier.fillMaxWidth().height(270.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        val card = sq(56)
        // 연속 운전(P로 15분 쉬면 0): 두 시간이면 쉼터를 알려 준다(고리는 두 시간 중 얼마나 왔나). 한 시간 넘게 달린 뒤 막 쉬었으면 그 말을 민트로
        Box(Modifier.weight(1.25f).fillMaxHeight().frosted(card)) {
            Column(Modifier.padding(horizontal = 38.dp, vertical = 34.dp)) {
                WidgetHead(PhosphorIcons.Fill.SteeringWheel, "연속 운전", C.Mint)
                DriveTime(ui.driveMinutes, 68, Modifier.padding(top = 18.dp))
                val rested = ui.restedAt > 0 && ui.driveMinutes == 0
                Text(if (rested) "방금 15분 쉬셨어요" else "2시간이면 쉼터를 알려 드려요", style = t(24, color = if (rested) C.Mint else C.L2), modifier = Modifier.padding(top = 10.dp).fillMaxWidth(.66f))
            }
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 38.dp)) { Ring((ui.driveMinutes / 120f).coerceIn(0f, 1f), 132.dp, 12.dp, C.Mint) }
        }
        // 둘째 칸: 위는 바깥(기온과 차가 해 둔 일, 누르면 기억 화면), 아래는 따로 떼어 낸 가족 정보 칸(사용자 결정 11/06: 날씨 옆에 있어 '오늘 아이가 탔다'로 읽혔다)
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            OutsideTile(ui, Modifier.weight(1f).fillMaxWidth().press(card) { Stage.openMemo() }.frosted(card))
            KidsTile(ui, Modifier.fillMaxWidth().height(96.dp))
        }
        // 음악 칸은 뺐다(검토 11/07, 사용자 "추천대로": 세워 둔 첫 화면 아래 줄의 곡 칸이 급할 때 앱에 군더더기로 읽혔다). 틀기·멈춤은 '그 밖에' 칸 안에.
        // 셋째 칸: 잘래요 중이면 그 칸, 대리 운행이면 대리 칸, 사고 메모가 있으면 사고 뒤 남은 일, 아니면 '그 밖에'(쪽잠·같이 고르기·차 돌봄·음악).
        // 기억 화면은 둘째 칸(바깥)이 연다
        val sleeper = ui.sleeper
        when {
            sleeper != null -> Column(Modifier.weight(1.25f).fillMaxHeight().frosted(card).padding(horizontal = 38.dp, vertical = 34.dp)) {
                WidgetHead(PhosphorIcons.Fill.MoonStars, "잘래요", C.Lilac)
                Text("$sleeper 쉬는 중", style = t(24, W600, C.LilacInk), modifier = Modifier.padding(top = 12.dp))
                ui.sleepWake?.let { Text(it, style = t(24, color = C.L2), modifier = Modifier.padding(top = 6.dp)) }
                Spacer(Modifier.weight(1f))
                Chip("그만 쉬기", C.Lilac, Modifier.fillMaxWidth()) { Stage.sleep(false) }
            }
            ui.daeri -> DaeriWidget(Modifier.weight(1.25f).fillMaxHeight().frosted(card))
            ui.note != null -> AfterWidget(ui, Modifier.weight(1.25f).fillMaxHeight().frosted(card))
            else -> Column(Modifier.weight(1.25f).fillMaxHeight().frosted(card).padding(horizontal = 38.dp, vertical = 34.dp)) {
                WidgetHead(PhosphorIcons.Fill.DotsThreeCircle, "그 밖에", C.Sky)
                Spacer(Modifier.weight(1f))
                val nap = ui.habits.napMin.takeIf { it in setOf(10, 15, 20) } ?: 15
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Chip("쪽잠 ${nap}분", C.Lilac, Modifier.weight(1f), height = 70, size = 25) { Stage.napStart(nap) }
                    Chip("같이 고르기", C.Sky, Modifier.weight(1f), height = 70, size = 25) { Stage.openPick("food") }
                }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Chip("차 돌봄", C.Amber, Modifier.weight(1f), height = 70, size = 25) { Stage.openCare() }
                    Chip(if (song.playing) "음악 멈춤" else "음악 틀기", C.L2, Modifier.weight(1f), height = 70, size = 25, onClick = a.playPause)
                }
            }
        }
    }
}

/** 바깥 칸(첫 화면 둘째 칸의 위): 기온과 그 옆의 운전석 열선·통풍 단계. 칸 전체가 기억 화면을 연다 */
@Composable
private fun OutsideTile(ui: StageUi, modifier: Modifier) {
    Column(modifier.padding(horizontal = 38.dp, vertical = 22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 온도계는 기온을 따른다: 15° 넘으면 더운 빨강 쪽, 5° 아래면 찬 하늘색, 그 사이는 무채색
            val c = ui.outsideC
            val (thermo, warm) = when {
                c == null -> PhosphorIcons.Fill.Thermometer to C.L2
                c >= 15 -> PhosphorIcons.Fill.ThermometerHot to C.Amber
                c < 5 -> PhosphorIcons.Fill.ThermometerCold to C.Sky
                else -> PhosphorIcons.Fill.Thermometer to C.L2
            }
            WidgetHead(thermo, "바깥", warm)
            Spacer(Modifier.weight(1f))
            Text("기억", style = t(24, W600, C.L2b))
            Icon(PhosphorIcons.Regular.CaretRight, null, tint = C.L2b, modifier = Modifier.size(26.dp))
        }
        // 기온과, 그 옆에 차가 알려 주는 운전석 열선·통풍 단계(사람이 버튼으로 켠 것도 보인다). 칸이 낮아져 한 줄에 둔다
        Row(Modifier.padding(top = 6.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(ui.outsideC?.let { (if (it < 0) "−" else "") + "${kotlin.math.abs(it)}°" } ?: "—", style = t(60, W600, tracking = -.03).copy(fontFeatureSettings = tnum))
            Spacer(Modifier.weight(1f))
            val heat = ui.heat ?: 0
            // 통풍 단계, 또는 시트 온도의 음수(냉방)
            val cool = maxOf(ui.vent ?: 0, -heat)
            if (heat > 0 || cool > 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (heat > 0) PhosphorIcons.Regular.Flame else PhosphorIcons.Regular.Fan, null, tint = if (heat > 0) C.Amber else C.Sky, modifier = Modifier.size(24.dp))
                Text(if (heat > 0) "열선 ${heat}단" else "통풍 ${cool}단", style = t(22, color = C.L2), maxLines = 1)
            }
        }
    }
}

/**
 * 가족 정보 칸(첫 화면 둘째 칸의 아래, 사용자 결정 11/06: 바깥 칸에서 떼어 늘 그대로인 정보임을 보인다). "아이가 있어요"를 체크해 두면
 * 사고 때 뒷좌석에 아이가 있는지 살피라고 알린다(처음 한 번 묻고, 여기와 기억 화면에서 바꾼다). "아이 탔어"·"애 잔다"로 이번 운행만 켠
 * 아이 탑승이면 머리가 '이번 운행'으로 바뀌고, 누르면 아이 탑승을 끈다
 */
@Composable
private fun KidsTile(ui: StageUi, modifier: Modifier) {
    val kids = ui.kids == true
    val on = ui.baby || kids
    val shape = sq(56)
    Row(
        modifier.press(shape) { if (ui.baby) Stage.baby(false) else Stage.kids(!kids) }.clip(shape)
            .background(if (on) C.Lilac.copy(alpha = .20f) else Color.White.copy(alpha = .06f))
            .border(1.dp, if (on) C.Lilac.copy(alpha = .4f) else Color.White.copy(alpha = .12f), shape)
            .padding(horizontal = 30.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(if (on) PhosphorIcons.Fill.CheckSquare else PhosphorIcons.Regular.Square, null, tint = if (on) C.LilacInk else C.L2, modifier = Modifier.size(36.dp))
        Column {
            Text(if (ui.baby) "이번 운행" else "가족 정보", style = t(20, W600, if (on) C.LilacInk else C.L2b), maxLines = 1)
            Text(if (ui.baby) "아이가 탔어요" else "아이가 있어요", style = t(26, W700, if (on) C.L1 else C.L2), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 대리 운행 칸: 이번 운행에 하는 것 한 줄과 [대리 끝](기사님이 내리면 주인이 끈다. 시동을 끄면 저절로 풀린다) */
@Composable
private fun DaeriWidget(modifier: Modifier) {
    Column(modifier.padding(horizontal = 38.dp, vertical = 34.dp)) {
        WidgetHead(PhosphorIcons.Fill.SteeringWheel, "대리 운행", C.Lilac)
        Text("먼저 말 걸지 않고 음악은 낮게 깔아요", style = t(24, color = C.L2), modifier = Modifier.padding(top = 12.dp))
        Spacer(Modifier.weight(1f))
        Chip("대리 끝", C.Lilac, Modifier.fillMaxWidth()) { Stage.daeri(false) }
    }
}

/** 사고 뒤 남은 일: 보험 접수와 블랙박스 영상, 사고 메모 한 줄, [전화]·[다 했어요] */
@Composable
private fun AfterWidget(ui: StageUi, modifier: Modifier) {
    val note = ui.note ?: return
    val tel = Guide.INSURERS.firstOrNull { it.name == ui.insurer }?.tel
    Column(modifier.padding(horizontal = 38.dp, vertical = 34.dp)) {
        WidgetHead(PhosphorIcons.Fill.Notepad, "사고 뒤 남은 일", C.Amber)
        Text("보험 접수 · 영상 저장", style = t(24, color = C.L2), modifier = Modifier.padding(top = 12.dp))
        Text(remember(note) { noteShort(note) }, style = t(24, color = C.L2b), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (tel != null) Chip("전화", C.Amber, Modifier.weight(1f)) { Stage.dial(tel) }
            Chip("다 했어요", C.Amber, Modifier.weight(1f)) { Stage.noteDone() }
        }
    }
}

@Composable
private fun Chip(label: String, tint: Color, modifier: Modifier, height: Int = 80, size: Int = 28, onClick: () -> Unit) {
    Box(
        modifier.height(height.dp).press(sq(26), onClick = onClick).clip(sq(26)).background(tint.copy(alpha = .16f)).border(1.dp, tint.copy(alpha = .22f), sq(26)),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = t(size, W600, tint), maxLines = 1) }
}

/** 연속 운전 시간: 큰 숫자와 작은 단위("1시간 12분"). 첫 화면 위젯과 달리는 동안의 판이 같이 쓴다 */
@Composable
private fun DriveTime(minutes: Int, size: Int, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        val big = t(size, W600, tracking = -.03).copy(fontFeatureSettings = tnum)
        val unit = t(size / 2, W600, C.L2)
        val lift = Modifier.padding(bottom = (size * .13f).dp)
        if (minutes >= 60) {
            Text("${minutes / 60}", style = big)
            Text("시간 ", style = unit, modifier = lift)
        }
        Text("${minutes % 60}", style = big)
        Text("분", style = unit, modifier = lift)
    }
}

/** 사고 메모 한 줄: "10월 27일 오후 2:05 · 서울 강남구 … · 낮 · 12°C". 날짜 형식을 매번 만들지 않게 부르는 곳은 메모가 바뀔 때만 부른다 */
private fun noteLine(n: CrashNote, withPlace: Boolean = true): String =
    listOfNotNull(SimpleDateFormat("M월 d일 a h:mm", Locale.KOREA).format(Date(n.at)), n.place?.takeIf { withPlace }, n.weather).joinToString(" · ")

/** 첫 화면 위젯의 사고 메모(장소 없이 짧게): 오늘·어제는 날짜 대신 그 말로("어제 오전 8:29 · 낮 · 12°C") */
private fun noteShort(n: CrashNote): String {
    fun midnight(ms: Long) = java.util.Calendar.getInstance().apply {
        timeInMillis = ms
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    val days = ((midnight(System.currentTimeMillis()) - midnight(n.at)) / 86_400_000.0).roundToInt()
    val day = when (days) { 0 -> "오늘"; 1 -> "어제"; else -> SimpleDateFormat("M월 d일", Locale.KOREA).format(Date(n.at)) }
    return listOfNotNull("$day ${SimpleDateFormat("a h:mm", Locale.KOREA).format(Date(n.at))}", n.weather).joinToString(" · ")
}

// ── 첫 화면 위젯 자리에서 묻는 것: 가족 정보, 혹시 사고, 사고 뒤 한 번 ──

/**
 * 첫 화면 위젯 자리에 제 카드를 띄우는 물음(화면이 보일 때 같이 고르기도 카드로 묻는다. 빠지면 목소리만 나왔다).
 * 듣는 중 캡슐(Hold의 ListenLayer)도 이 목록을 본다: 카드가 제 '듣는 중'을 그리므로 첫 화면이면 캡슐을 따로 띄우지 않는다
 */
internal val HOME_ASKS =setOf("JAM", "AUTO", "KIDS", "GEO", "BUMP", "BUMPBACK", "AFTER", "NAP", "SLEEP", "FOG", "HOUR", "SPOT", "DAERI", "TALK", "PICK", "QUIET", "HAUTO", "CARE", "ALLEY", "DUSK", "SLOW", "SHOP", "HSTOP", "WET", "RIGHT", "TOILET")

/** 첫 화면 물음 카드 자리에 그리는 물음인가: 위 목록과, 애매한 말에 한 번 묻는 확인 카드(Stage.confirm, id "Q_", 검토 11/07 멋대로 판단) */
internal fun homeAsk(id: String?) = id != null && (id in HOME_ASKS || id.startsWith("Q_"))

/**
 * 첫 화면 아래 자리: 위젯(세워 둔 동안)·판 하나(달리는 동안), 또는 PREND가 먼저 묻는 카드.
 * 카드는 한 프레임에 갈아 끼우지 않는다: 세워 둔 동안은 아래에서 살짝 떠오르고, 달리는 동안은 옅게 나타나기만 한다(움직임을 두지 않는다).
 * 나타날 때 테두리가 카드 색으로 한 번 빛난다(glowOnce, 달리는 동안은 빛 없이). 같은 물음의 글만 바뀌면 다시 띄우지 않는다
 */
@Composable
private fun BoxScope.AskSlot(ui: StageUi, driving: Boolean, song: Song, a: Acts) {
    val rise = with(LocalDensity.current) { 64.dp.roundToPx() }
    AnimatedContent(
        ui.ask?.takeIf { homeAsk(it.id) },
        modifier = Modifier.matchParentSize(),
        contentKey = { it?.id },
        transitionSpec = {
            if (driving) fadeIn(tween(200)) togetherWith fadeOut(tween(160))
            else (slideInVertically(tween(360, easing = FastOutSlowInEasing)) { rise } + fadeIn(tween(280))) togetherWith fadeOut(tween(160))
        },
        label = "ask",
    ) { ask ->
        Box(Modifier.fillMaxSize()) {
            val at = Modifier.align(Alignment.BottomStart)
            when {
                ask == null -> if (driving) DrivePlate(ui, at) else HomeWidgets(ui, song, a, at)
                driving -> DriveAsk(ui, ask, at)
                // 세워 둔 동안의 혹시 사고·고속도로 정차는 '급할 때' 판 자리에서 이 자리까지 크게(HomeCenter). 위젯은 그동안 비킨다
                ask.id in BIG_ASKS -> Unit
                ask.id == "JAM" -> JamCard(ui, at)
                ask.id == "AUTO" -> AutoCard(ui, ask, at)
                else -> AskCard(ui, ask, at)
            }
        }
    }
}

/** 물음 카드의 머리: 아이콘, 카드 색, 이름 */
private fun askLook(ask: Ask): Triple<ImageVector, Color, String> = when (ask.id) {
    "KIDS" -> Triple(PhosphorIcons.Fill.Baby, C.Lilac, "가족 정보")
    "GEO" -> Triple(PhosphorIcons.Fill.MapPin, C.Sky, "위치를 이렇게 써요")
    "BUMP", "BUMPBACK" -> Triple(PhosphorIcons.Fill.Warning, C.Red, "혹시 사고")
    "NAP" -> Triple(PhosphorIcons.Fill.MoonStars, C.Lilac, "쪽잠")
    "SLEEP" -> Triple(PhosphorIcons.Fill.MoonStars, C.Lilac, "잘래요")
    "FOG" -> Triple(PhosphorIcons.Regular.Fan, C.Sky, "김서림")
    "HOUR" -> Triple(PhosphorIcons.Fill.Coffee, C.Mint, "한 시간")
    "HAUTO" -> Triple(PhosphorIcons.Fill.Wind, C.Sky, "기억대로")
    "QUIET" -> Triple(PhosphorIcons.Fill.SpeakerSimpleSlash, C.Lilac, "기억대로")
    "SPOT" -> Triple(PhosphorIcons.Fill.MapPin, C.Sky, "주차 자리")
    "DAERI" -> Triple(PhosphorIcons.Fill.SteeringWheel, C.Lilac, "대리 운행")
    "TALK" -> Triple(PhosphorIcons.Regular.Microphone, C.Mint, "이야기에 비켜 서기")
    "AFTER" -> Triple(PhosphorIcons.Fill.Notepad, C.Amber, "사고 뒤")
    "PICK" -> Triple(PhosphorIcons.Fill.ForkKnife, C.Sky, "같이 고르기")
    "JAM" -> Triple(PhosphorIcons.Fill.Lamp, C.Amber, "오래 막히는 길")
    "CARE" -> Triple(PhosphorIcons.Regular.Wrench, C.Amber, "차 돌봄")
    "ALLEY" -> Triple(PhosphorIcons.Fill.MapPin, C.Sky, "골목 목적지")
    "DUSK" -> if ("예보" in ask.title) Triple(PhosphorIcons.Fill.CloudRain, C.Sky, "출발 전") else Triple(PhosphorIcons.Fill.MoonStars, C.Amber, "출발 전")
    "SLOW" -> Triple(PhosphorIcons.Regular.Microphone, C.Mint, "천천히 말하기")
    "SHOP" -> Triple(PhosphorIcons.Regular.Wrench, C.Amber, "정비소")
    "HSTOP" -> Triple(PhosphorIcons.Fill.Warning, C.Red, "고속도로")
    "TOILET" -> Triple(PhosphorIcons.Fill.Toilet, C.Sky, "화장실")
    "WET" -> Triple(PhosphorIcons.Fill.CloudRain, C.Sky, "젖은 길")
    "RIGHT" -> Triple(PhosphorIcons.Fill.ArrowBendUpRight, C.Amber, "우회전")
    // 기억대로 한 일: 한 일에 맞는 아이콘(열선·통풍·무드등)
    "AUTO" -> when {
        ask.title.startsWith("열선") -> Triple(PhosphorIcons.Regular.Flame, C.Amber, "기억대로")
        ask.title.startsWith("통풍") -> Triple(PhosphorIcons.Regular.Fan, C.Sky, "기억대로")
        else -> Triple(PhosphorIcons.Fill.Lamp, C.Amber, "기억대로")
    }
    else -> Triple(PhosphorIcons.Fill.Notepad, C.Amber, Prend.NAME)
}

@Composable
private fun AskCard(ui: StageUi, ask: Ask, modifier: Modifier) {
    val (icon, tint, label) = askLook(ask)
    Column(modifier.padding(start = 72.dp, end = 72.dp, bottom = 72.dp).fillMaxWidth().frosted(sq(56)).glowOnce(tint, sq(56)).padding(horizontal = 56.dp, vertical = 40.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconTile(icon, tint, tint.copy(alpha = .18f), 54, 17, 30)
            Text(label, style = t(26, W600, tint))
            Text(ask.text, style = t(26, color = C.L2))
        }
        Text(ask.title, style = t(48, W700, tracking = -.03, line = 1.2), modifier = Modifier.padding(top = 22.dp))
        Row(Modifier.padding(top = 30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Btn(ask.yes, primary = true, height = 100, size = 33) { Stage.answer(ask.id, true) }
            // 버튼 하나짜리 카드(우회전 [알고 있어요])는 no가 비어 있다
            if (ask.no.isNotBlank()) Btn(ask.no, primary = false, height = 100, size = 33) { Stage.answer(ask.id, false) }
            Listening(ui)
        }
    }
}

@Composable
private fun WidgetHead(icon: ImageVector, label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(28.dp))
        Text(label, style = t(24, W600, color))
    }
}

// ── 3. 달리는 중(D·R·N): 멈춘 글과 큰 버튼 둘. 한눈에 읽히는 크기만 남긴다 ──

/**
 * 달리는 동안의 가운데(11/05 큰 틀): 급할 때 한 줄. 줄 전체(아이콘과 글)가 단추라 누르면 [긴급]과 같이 E 첫 화면이 열린다
 * (검토 11/06: 전에는 눌러도 아무 일이 없어 오른쪽 위 작은 [긴급]을 찾아야 했다). 이 줄 위에서는 꾹 눌러 [프렌드] 부르기가 먹히지 않는다(줄 밖 빈 곳에서 된다).
 * 곡 멈춤 단추는 뺐다: 줄이 단추가 되어 달리는 동안 큰 단추가 둘을 넘지 않게(곡은 세우면 위젯에서, 달리는 동안은 말로)
 */
@Composable
private fun DriveE(a: Acts) {
    Row(
        Modifier.padding(start = 72.dp, end = 72.dp, top = 260.dp).fillMaxWidth().press(sq(44), onClick = a.sos).padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(PhosphorIcons.Fill.Warning, C.Red, C.Red.copy(alpha = .16f), 140, 44, 76)
        Column(Modifier.weight(1f).padding(start = 48.dp, end = 40.dp)) {
            Text("급할 때는 여기를 누르세요", style = t(56, W700, tracking = -.03), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("사고로 멈춰 서면 ${Prend.NAME}가 먼저 물어요", style = t(30, W500, C.L2), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        }
        Icon(PhosphorIcons.Regular.CaretRight, null, tint = C.L3, modifier = Modifier.padding(end = 12.dp).size(72.dp))
    }
}

/**
 * 달리는 동안의 첫 화면 판 하나: 연속 운전(고리와 큰 숫자)과, 함께 탄 사람이 있으면 [잘래요](쉬는 중이면 [그만 쉬기]).
 * 쉼터 안내 글·기온·가족 정보·같이 고르기는 세우면 보인다
 */
@Composable
private fun DrivePlate(ui: StageUi, modifier: Modifier) {
    Row(
        modifier.padding(start = 72.dp, end = 72.dp, bottom = 64.dp).fillMaxWidth().frosted(sq(56)).padding(horizontal = 56.dp, vertical = 40.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        Ring((ui.driveMinutes / 120f).coerceIn(0f, 1f), 132.dp, 12.dp, C.Mint)
        Column(Modifier.weight(1f)) {
            WidgetHead(PhosphorIcons.Fill.SteeringWheel, "연속 운전", C.Mint)
            DriveTime(ui.driveMinutes, 64, Modifier.padding(top = 10.dp))
        }
        when {
            ui.sleeper != null -> Btn("그만 쉬기", primary = false, height = 100, size = 33, icon = PhosphorIcons.Fill.MoonStars) { Stage.sleep(false) }
            ui.others -> Btn("잘래요", primary = false, height = 100, size = 33, icon = PhosphorIcons.Fill.MoonStars) { Stage.sleep(true) }
        }
    }
}

/**
 * 달리는 동안 PREND가 묻는 카드: 물음 한 줄과 큰 버튼 둘(말로 대답해도 된다). 설명 줄은 세워 둔 동안의 카드에만.
 * 한 줄은 버튼이 대답하는 물음으로: 설명 줄이 물음이면 그것("차 안 분위기를 좀 바꿔 드릴까요?"·"보험 접수는 끝내셨어요?"), 아니면 제목
 */
@Composable
private fun DriveAsk(ui: StageUi, ask: Ask, modifier: Modifier) {
    val (icon, tint) = askLook(ask)
    val line = if (ask.text.endsWith("?")) ask.text else ask.title
    Column(modifier.padding(start = 72.dp, end = 72.dp, bottom = 72.dp).fillMaxWidth().frosted(sq(56)).glowOnce(tint, sq(56), still = true).padding(horizontal = 56.dp, vertical = 40.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            IconTile(icon, tint, tint.copy(alpha = .18f), 64, 20, 36)
            Text(line, style = t(48, W700, tracking = -.03), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // 화장실은 고른 곳(이름·거리)을 한 줄 더 보인다(쉼터 카드의 다음 쉼터 줄과 같은 모양)
        if (ask.id == "TOILET") Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(PhosphorIcons.Regular.MapPin, null, tint = C.L2, modifier = Modifier.size(30.dp))
            Text(ask.text, style = t(28, color = C.L2).copy(fontFeatureSettings = tnum), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.padding(top = 30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Btn(ask.yes, primary = true, height = 100, size = 33) { Stage.answer(ask.id, true) }
            if (ask.no.isNotBlank()) Btn(ask.no, primary = false, height = 100, size = 33) { Stage.answer(ask.id, false) }
            Listening(ui)
        }
    }
}

@Composable
private fun DriveCard(ui: StageUi, modifier: Modifier) {
    Column(modifier.padding(start = 72.dp, end = 72.dp, bottom = 96.dp).fillMaxWidth().frosted(sq(56)).glowOnce(C.Mint, sq(56), still = ui.brief).padding(horizontal = 56.dp, vertical = 48.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconTile(PhosphorIcons.Fill.Coffee, C.Mint, C.Mint.copy(alpha = .18f), 54, 17, 30)
            Text("운전 ${hm(ui.driveMinutes)}", style = t(26, W600, C.Mint).copy(fontFeatureSettings = tnum))
        }
        // 두 시간이 되어 먼저 권할 때와, 그 전에 "졸려"라고 했을 때
        val ask = if (ui.driveMinutes >= 120) "두 시간 넘게 달리셨어요.\n앞쪽 쉼터에서 쉬어 가실래요?" else "잠깐 쉬어 가요.\n가장 가까운 쉼터를 경유지로 넣을까요?"
        Text(ask, style = t(54, W700, tracking = -.03, line = 1.2), modifier = Modifier.padding(top = 26.dp))
        // 다음 쉼터(이름·거리): 쉼터 좌표를 붙인 뒤 운행 서비스가 넣는다. 촬영용 값일 때는 시안 문구
        (if (ui.demo) "다음 졸음쉼터 · 12km · 약 8분" else ui.restLine)?.let { line ->
            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(PhosphorIcons.Regular.MapPin, null, tint = C.L2, modifier = Modifier.size(30.dp))
                Text(line, style = t(28, color = C.L2).copy(fontFeatureSettings = tnum))
            }
        }
        Row(Modifier.padding(top = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Btn("경유지로", primary = true, height = 100, size = 33, icon = PhosphorIcons.Regular.NavigationArrow) { Stage.restAnswer(true) }
            Btn("괜찮아요", primary = false, height = 100, size = 33) { Stage.restAnswer(false) }
            Listening(ui)
        }
    }
}

/**
 * 지금 듣기의 창 길이(Stage.call에 넘기는 값과 같게, 11/08 오류 점검 E4: 고리가 늘 대답 창으로 줄어 [프렌드]의 8초·확인 카드의 창과 어긋났다).
 * [프렌드]로 부른 듣기는 8초(그때만 hints가 찬다), "괜찮으세요?"는 대답 창, 확인 카드의 말 대답은 6초와 대답 창 가운데 긴 쪽(Stage.hearConfirm),
 * 그 밖의 물음은 대답 창(5초, 넉넉히 기다리기면 8초)
 */
private fun listenMs(ui: StageUi): Long = when {
    ui.listen?.hints?.isNotEmpty() == true -> 8_000L
    ui.okAsk && ui.scene.isE -> Stage.answerMs
    ui.ask?.id?.startsWith("Q_") == true -> maxOf(6_000L, Stage.answerMs)
    else -> Stage.answerMs
}

/** 대답을 기다리는 몇 초(대답 창: 5초, 넉넉히 기다리기면 8초. 창 길이는 listenMs): 고리가 줄어든다. 알아들으면 그 말을 보인다(촬영용 값일 때는 고리만) */
@Composable
private fun Listening(ui: StageUi) {
    val l = ui.listen
    val heard = l?.heard.orEmpty()
    val listening = (l != null && !l.done) || ui.demo
    val left = remember(listening) { Animatable(1f) }
    LaunchedEffect(listening) { if (listening) left.animateTo(0f, tween(if (ui.demo) 9_000 else listenMs(ui).toInt(), easing = LinearEasing)) }
    // 줄어드는 값은 그릴 때만 읽는다(5~9초 동안 카드를 매 프레임 다시 짜지 않게). 다 줄었을 때만 한 번 다시 짠다
    val running by remember(left) { derivedStateOf { left.value > 0f } }
    if (heard.isNotBlank()) Text("“$heard”", style = t(32, W600), modifier = Modifier.padding(start = 22.dp))
    else if (listening && running) Row(
        Modifier.padding(start = 22.dp).graphicsLayer { val a = (left.value * 6f).coerceAtMost(1f); alpha = a; clip = a < 1f },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(PhosphorIcons.Regular.Microphone, null, tint = C.L1, modifier = Modifier.size(36.dp))
        Text("듣는 중", style = t(30, W600))
        Ring({ left.value }, 46.dp, 5.dp, C.L1)
    }
}

// ── 3-1. 정체 한마디(달리는 중, 첫 화면 위젯 자리): 공기를 바꿔 드릴지 ──

@Composable
private fun JamCard(ui: StageUi, modifier: Modifier) {
    // 첫 화면의 곡 버튼을 가리지 않게 위젯 높이에 가깝게 낮춘다
    Column(modifier.padding(start = 72.dp, end = 72.dp, bottom = 72.dp).fillMaxWidth().frosted(sq(56)).glowOnce(C.Amber, sq(56)).padding(horizontal = 56.dp, vertical = 40.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconTile(PhosphorIcons.Fill.Lamp, C.Amber, C.Amber.copy(alpha = .18f), 54, 17, 30)
            Text("오래 막히는 길", style = t(26, W600, C.Amber))
            Text("무드등을 따뜻하게, 조용하면 잔잔한 곡도", style = t(26, color = C.L2))
        }
        Text("길이 많이 막히네요. 차 안 분위기를 좀 바꿔 드릴까요?", style = t(48, W700, tracking = -.03, line = 1.2), modifier = Modifier.padding(top = 22.dp))
        // "응"을 두 번 이어 했으면 셋째 물음에서 미리 알린다(감사 F77 ①, 세 번이면 알아서: ride/Memory.AUTO_AFTER). 민트는 쓰면서 맞춰지는 좋은 변화.
        // 달리는 동안의 카드(DriveAsk)는 글 한 줄과 버튼 둘까지라 여기(세워 둔 화면·시연)에만 둔다
        val jam = ui.habits.jam
        if (jam.yes == app.prend.ride.Memory.AUTO_AFTER - 1 && !jam.auto && !jam.quiet) Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(C.Mint))
            Text("이번에도 “응”이면 다음부턴 알아서 바꿔 둘게요", style = t(26, W600, C.Mint))
        }
        Row(Modifier.padding(top = 30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Btn("바꿔 주세요", primary = true, height = 100, size = 33) { Stage.answer("JAM", true) }
            Btn("괜찮아요", primary = false, height = 100, size = 33) { Stage.answer("JAM", false) }
            Listening(ui)
        }
    }
}

// ── 3-1-2. 기억대로 알아서 한 일(첫 화면 위젯 자리): 한 일 한 줄과 [좋아요]·[끄기] ──

@Composable
private fun AutoCard(ui: StageUi, ask: Ask, modifier: Modifier) {
    val (icon, tint) = askLook(ask)
    Column(modifier.padding(start = 72.dp, end = 72.dp, bottom = 72.dp).fillMaxWidth().frosted(sq(56)).glowOnce(tint, sq(56)).padding(horizontal = 56.dp, vertical = 40.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconTile(icon, tint, tint.copy(alpha = .18f), 54, 17, 30)
            Text("기억대로", style = t(26, W600, C.Mint))
            Text(ask.text, style = t(26, color = C.L2))
        }
        Text("${ask.title}.", style = t(48, W700, tracking = -.03, line = 1.2), modifier = Modifier.padding(top = 22.dp))
        Row(Modifier.padding(top = 30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Btn(ask.yes, primary = true, height = 100, size = 33) { Stage.answer("AUTO", true) }
            Btn(ask.no, primary = false, height = 100, size = 33) { Stage.answer("AUTO", false) }
            Listening(ui)
        }
    }
}

// ── 3-2. 같이 고르기(세워 둔 동안): 큰 화면에 다섯 곳을 나란히, 같이 보고 이야기하며 고른다 ──

@Composable
private fun PickHead(ui: StageUi) {
    Column(Modifier.padding(start = 72.dp, end = 72.dp, top = 196.dp)) {
        Text("같이 고르기", style = t(84, W700, tracking = -.035))
        Text("${ui.pick.area ?: "지금 위치"} 근처 · 같이 보고 골라요", style = t(32, W500, C.L2), modifier = Modifier.padding(top = 14.dp))
    }
}

@Composable
private fun PickFront(ui: StageUi) {
    val p = ui.pick
    Box(Modifier.fillMaxSize()) {
        // 밥집·카페. 제목 자리에 물음 띠(TopAsk)가 떠 있는 동안은 비킨다(띠 밑에서 눌리지 않게, 11/08 오류 점검 E2)
        if (!homeAsk(ui.ask?.id)) Row(Modifier.align(Alignment.TopEnd).padding(top = 214.dp, end = 72.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            listOf(Triple("food", "밥집", PhosphorIcons.Fill.ForkKnife), Triple("cafe", "카페", PhosphorIcons.Fill.Coffee)).forEach { (k, label, icon) ->
                Btn(label, primary = p.kind == k, height = 88, size = 30, pad = 38, icon = icon) { if (p.kind != k) Stage.pick("kind", k) }
            }
        }
        val area = Modifier.align(Alignment.BottomStart).padding(start = 72.dp, end = 72.dp, bottom = 200.dp).fillMaxWidth().height(400.dp)
        when {
            p.loading -> Column(area, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Bars(6, C.L1)
                Text("근처를 찾는 중이에요", style = t(40, W600), modifier = Modifier.padding(top = 22.dp))
            }
            p.error != null -> Column(area, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(p.error, style = t(40, W600))
                Box(Modifier.padding(top = 28.dp)) { Btn("다시 찾기", primary = false, height = 92, size = 32) { Stage.pick("retry") } }
            }
            else -> Row(area, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                p.items.forEachIndexed { i, place -> PlaceCard(i + 1, place, i == p.chosen, Modifier.weight(1f).fillMaxHeight()) { Stage.pick("choose", "$i") } }
            }
        }
        Row(
            Modifier.align(Alignment.BottomStart).padding(start = 72.dp, end = 72.dp, bottom = 56.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("NAVER 지역·블로그 검색", style = t(24, color = C.L2b))
            Spacer(Modifier.weight(1f))
            p.items.getOrNull(p.chosen)?.let { chosen ->
                Text(chosen.name, style = t(32, W600, C.L2), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 10.dp))
                Btn("여기로 가요", primary = true, height = 104, size = 36, pad = 48, icon = PhosphorIcons.Regular.NavigationArrow) { Stage.pick("go") }
            }
        }
    }
}

/** no는 1~5 번호: 뒷자리도 "세 번째로 가자"·"3번"으로 고른다 */
@Composable
private fun PlaceCard(no: Int, place: Place, chosen: Boolean, modifier: Modifier, onClick: () -> Unit) {
    // 상호와 지점을 나눠 적는다(긴 이름이 '직영\n점'처럼 글자 가운데서 끊기지 않게)
    val (brand, branch) = remember(place.name) { splitBranch(place.name) }
    Column(
        modifier.press(sq(48), onClick = onClick).clip(sq(48)).frosted(sq(48), if (chosen) Color(0xFF3A3A40) else Color(0xFF1E1E22), if (chosen) .75f else .45f)
            .border(if (chosen) 3.dp else 1.dp, if (chosen) C.L1 else Color.White.copy(alpha = .10f), sq(48))
            .padding(horizontal = 34.dp, vertical = 34.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(if (chosen) C.L1 else Color.White.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
                    Text("$no", style = t(24, W700, if (chosen) C.Ink else C.L1).copy(fontFeatureSettings = tnum))
                }
                Text(place.category, style = t(24, W600, C.Amber), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(brand, style = t(40, W700, tracking = -.03, line = 1.15), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
            branch?.let { Text(it, style = t(26, W500, C.L2), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)) }
        }
        Column {
            place.meters?.let { m -> Text(if (m < 1000) "${m}m" else "%.1fkm".format(m / 1000.0), style = t(36, W600).copy(fontFeatureSettings = tnum)) }
            // 중계 서버가 세는 것은 그 이름이 나온 블로그 글 수다(별점·후기가 아니다)
            place.reviews?.let { r -> Text("블로그 글 ${"%,d".format(r)}", style = t(26, color = C.L2).copy(fontFeatureSettings = tnum), modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}

/** "구좌리얼크니손칼국수 성남사송직영점" → 상호 "구좌리얼크니손칼국수" + 지점 "성남사송직영점". 마지막 낱말이 '…점'일 때만 나눈다 */
private fun splitBranch(name: String): Pair<String, String?> {
    val s = name.trim()
    val i = s.lastIndexOf(' ')
    if (i <= 0) return s to null
    val last = s.substring(i + 1)
    return if (last.length >= 2 && last.endsWith("점")) s.substring(0, i).trimEnd() to last else s to null
}

// ── 차 돌봄(세워 둔 동안): 무엇을 언제 갈고, 어디서 얼마인지(ride/CarCare·CareBook) ──

@Composable
private fun CareHead(ui: StageUi) {
    val c = ui.care
    val page = c.open?.let { CareBook.page(it) }
    val shops = c.shops
    Column(Modifier.padding(start = 72.dp, end = 72.dp, top = 196.dp)) {
        when {
            shops != null -> {
                Text("가까운 블루핸즈", style = t(84, W700, tracking = -.035))
                Text("${shops.area ?: "지금 위치"} 근처 · 현대차 공식 정비 협력점이에요", style = t(32, W500, C.L2), modifier = Modifier.padding(top = 14.dp))
            }
            page != null -> {
                val row = c.rows.firstOrNull { it.key == page.key }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Text(page.label, style = t(84, W700, tracking = -.035))
                    // 주기가 없는 항목(와이퍼·워셔액)은 상태 대신 아래 한 줄만
                    row?.takeIf { page.km != null || page.months != null || page.yearly }?.let { CareState(it, 36) }
                }
                Text(page.whenText, style = t(32, W500, C.L2), modifier = Modifier.padding(top = 14.dp))
            }
            else -> {
                Text("차 돌봄", style = t(84, W700, tracking = -.035))
                val km = c.km?.let { "지금 ${"%,d".format(it)}km · " } ?: ""
                // 값은 숫자 대신 보는 곳을 알려 준다(부품값·공임 숫자는 넣지 않음, ride/CareBook)
                Text("${km}${c.visit?.let { "마지막 정비 $it · " } ?: ""}언제 갈고, 어디에 맡기고, 값은 어디서 보는지 알려 드려요", style = t(32, W500, C.L2), modifier = Modifier.padding(top = 14.dp))
            }
        }
    }
}

/** 갈 때면 호박색, 곧이면 옅은 호박색, 아니면 옅은 글 */
@Composable
private fun CareState(r: CareRow, size: Int) {
    val color = when { r.due -> C.Amber; r.soon -> C.Amber.copy(alpha = .75f); else -> C.L2 }
    Text(r.state, style = t(size, W600, color).copy(fontFeatureSettings = tnum), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun CareFront(ui: StageUi) {
    val c = ui.care
    val page = c.open?.let { CareBook.page(it) }
    val shops = c.shops
    Box(Modifier.fillMaxSize()) {
        when {
            shops != null -> CareShops(shops)
            page != null -> CarePage(page, c.km)
            else -> CareList(c)
        }
    }
}

@Composable
private fun BoxScope.CareList(c: CareUi) {
    // 두 줄(한 줄에 다섯). 늘 같은 자리에 두어 찾기 쉽게 하고, 갈 때인 칸만 호박색 테두리
    Column(
        Modifier.align(Alignment.BottomStart).padding(start = 72.dp, end = 72.dp, bottom = 200.dp).fillMaxWidth().height(560.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        c.rows.chunked(5).forEach { line ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                line.forEach { r -> CareCell(r, Modifier.weight(1f).fillMaxHeight()) { Stage.care("item", r.key) } }
                repeat(5 - line.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    Row(
        Modifier.align(Alignment.BottomStart).padding(start = 72.dp, end = 72.dp, bottom = 56.dp).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(CareBook.FOOT, style = t(24, color = C.L2b), modifier = Modifier.weight(1f))
        Btn("가까운 블루핸즈", primary = false, height = 104, size = 34, pad = 44, icon = PhosphorIcons.Regular.Wrench) { Stage.care("shops") }
    }
}

@Composable
private fun CareCell(r: CareRow, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.press(sq(44), onClick = onClick).clip(sq(44)).frosted(sq(44), Color(0xFF1E1E22), .45f)
            .border(if (r.due) 2.dp else 1.dp, if (r.due) C.Amber.copy(alpha = .7f) else Color.White.copy(alpha = .10f), sq(44))
            .padding(horizontal = 32.dp, vertical = 26.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(r.label, style = t(38, W700, tracking = -.03), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (r.diy) Text("직접 할 수 있어요", style = t(24, W600, C.Sky), modifier = Modifier.padding(top = 6.dp))
        }
        CareState(r, 30)
    }
}

/** 한 항목: 이럴 때 · 직접 하기(또는 맡길 때) · 알아 둘 것(값을 보는 곳과 정비소에서의 권리), 그리고 [목록]·[갈았어요]·[가까운 블루핸즈] */
@Composable
private fun BoxScope.CarePage(p: CareBook.Page, km: Int?) {
    Row(
        Modifier.align(Alignment.BottomStart).padding(start = 72.dp, end = 72.dp, bottom = 200.dp).fillMaxWidth().height(620.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        val mod = Modifier.weight(1f).fillMaxHeight()
        CareBox("이럴 때 살펴요", C.Amber, p.signs, mod)
        CareBox(if (p.diy != null) "직접 할 수 있어요" else "맡길 때", C.Sky, p.diy ?: p.shop, mod)
        CareBox("알아 둘 것", C.Lilac, p.more + CareBook.rights(km, p.simple), mod)
    }
    Row(
        Modifier.align(Alignment.BottomStart).padding(start = 72.dp, end = 72.dp, bottom = 56.dp).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(p.source, style = t(24, color = C.L2b), modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Btn("목록", primary = false, height = 104, size = 34, pad = 40, icon = PhosphorIcons.Regular.List) { Stage.care("list") }
        Btn(p.doneLabel, primary = false, height = 104, size = 34, pad = 40, icon = PhosphorIcons.Regular.Check) { Stage.care("done", p.key) }
        Btn("가까운 블루핸즈", primary = true, height = 104, size = 34, pad = 44, icon = PhosphorIcons.Regular.Wrench) { Stage.care("shops") }
    }
}

@Composable
private fun CareBox(title: String, color: Color, lines: List<String>, modifier: Modifier) {
    // 엔진오일·브레이크의 '알아 둘 것'은 28sp로 13줄이라 칸(안쪽 552)을 넘어 무료 재정비 기간 줄이 잘렸다: 글을 26sp·줄 간격 1.3으로 줄이고,
    // 그래도 넘치는 항목은 칸 안에서 밀어 본다(11/08 오류 점검 E7)
    Column(
        modifier.clip(sq(48)).frosted(sq(48), Color(0xFF1E1E22), .45f).border(1.dp, Color.White.copy(alpha = .10f), sq(48))
            .padding(horizontal = 38.dp, vertical = 34.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, style = t(28, W700, color))
        lines.forEach { l -> Text(l, style = t(26, color = C.L1, line = 1.3)) }
    }
}

@Composable
private fun BoxScope.CareShops(p: Pick) {
    val area = Modifier.align(Alignment.BottomStart).padding(start = 72.dp, end = 72.dp, bottom = 200.dp).fillMaxWidth().height(400.dp)
    when {
        p.loading -> Column(area, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Bars(6, C.L1)
            Text("가까운 블루핸즈를 찾는 중이에요", style = t(40, W600), modifier = Modifier.padding(top = 22.dp))
        }
        p.error != null -> Column(area, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(p.error, style = t(40, W600))
            Box(Modifier.padding(top = 28.dp)) { Btn("다시 찾기", primary = false, height = 92, size = 32) { Stage.care("shops") } }
        }
        else -> Row(area, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            p.items.forEachIndexed { i, place -> PlaceCard(i + 1, place, i == p.chosen, Modifier.weight(1f).fillMaxHeight()) { Stage.care("choose", "$i") } }
            repeat(3 - p.items.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    Row(
        Modifier.align(Alignment.BottomStart).padding(start = 72.dp, end = 72.dp, bottom = 56.dp).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("NAVER 지역 검색 · 예약은 마이현대 앱이나 그곳 전화로", style = t(24, color = C.L2b), modifier = Modifier.weight(1f))
        Btn("뒤로", primary = false, height = 104, size = 34, pad = 40, icon = PhosphorIcons.Regular.ArrowLeft) { Stage.care("back") }
        p.items.getOrNull(p.chosen)?.let { Btn("여기로 가요", primary = true, height = 104, size = 36, pad = 48, icon = PhosphorIcons.Regular.NavigationArrow) { Stage.care("go") } }
    }
}

// ── 3-3. 기억(세워 둔 동안): 켜 두는 것(운전 도움·듣고 보기·조용히 가기·가족 정보·먼저 묻기·위치 보내기)과 차 돌봄, 그 밖에 적어 둔 것, 모두 잊기 ──

@Composable
private fun MemoHead() {
    Column(Modifier.padding(start = 72.dp, end = 72.dp, top = 196.dp)) {
        Text("${Prend.NAME}가 기억하는 것", style = t(84, W700, tracking = -.035))
        Text("세워 둔 동안 여기서 켜고 꺼요", style = t(32, W500, C.L2), modifier = Modifier.padding(top = 14.dp))
    }
}

/** 기억 화면 안의 '그 밖에': 쓰면서 맞춘 버릇들 */
@Composable
private fun MemoMoreHead() {
    Column(Modifier.padding(start = 72.dp, end = 72.dp, top = 196.dp)) {
        Text("그 밖에 기억하는 것", style = t(84, W700, tracking = -.035))
        Text("대답하신 것을 이 차 안에 적어 두고, 다음엔 더 맞게 해요", style = t(32, W500, C.L2), modifier = Modifier.padding(top = 14.dp))
    }
}

/**
 * 기억 화면(검토-방향노인초보 3-9·2-2): 큰 칸 몇 개로. 운전 도움·가족 정보 / 듣고 보기 / 조용히 가기·차 돌봄 / 먼저 묻기(곁들임 물음 다섯 켜고 끄기, 사용자 결정 11/06),
 * 맨 아래에 남기는 것과 [위치 보내기]·[모두 잊기]. 1706×1210(160dpi라 dp = px)에서 넘치지 않게 띠 높이(646)와 칸 폭(330·460·340, 남은 폭은 먼저 묻기)을 정해 둔다.
 * 띠 위 끝(약 388)은 제목 아래 한 줄(약 350)을 가리지 않는다. 버릇 2×2와 쪽잠·오래 막힐 때·같이 고르기 카드는 '그 밖에' 화면(먼저 묻기 칸의 [그 밖에])으로 옮겼다
 */
@Composable
private fun MemoFront(ui: StageUi) {
    if (ui.about) { AboutFront(); return }
    // 대리 운행 중엔 주인의 버릇·설정을 기사님 앞에 펼치지 않는다(매력 제안 13)
    if (ui.daeri) { MemoHidden(); return }
    if (ui.memoMore) { MemoMorePage(ui); return }
    Box(Modifier.fillMaxSize()) {
        // 앱 정보로 가는 조용한 입구(감사 F124·F125). 기억과 다른 글이라 제목 옆의 옅은 작은 버튼 하나로만.
        // 제목 자리에 물음 띠(TopAsk)가 떠 있는 동안은 비킨다(띠 밑에서 눌리지 않게, 11/08 오류 점검 E2)
        if (!homeAsk(ui.ask?.id)) Box(Modifier.align(Alignment.TopEnd).padding(top = 196.dp, end = 72.dp)) {
            Btn("앱 정보", primary = false, height = 80, size = 28, pad = 32, icon = PhosphorIcons.Regular.Info) { Stage.openAbout() }
        }
        Row(
            Modifier.align(Alignment.BottomStart).padding(start = 72.dp, end = 72.dp, bottom = 176.dp).fillMaxWidth().height(646.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(Modifier.width(330.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                val mod = Modifier.weight(1f).fillMaxWidth()
                // 운전 도움(옛 초보 운전, 11/02 사용자: 이름만 바꾸고 본인이 켜고 익숙해지면 본인이 끈다)
                ToggleTile(
                    PhosphorIcons.Fill.SteeringWheel, "운전 도움", C.Mint, mod,
                    now = if (ui.novice) "켜 뒀어요" else "꺼 뒀어요", line = "주차·회전교차로에서\n한마디씩 도와요",
                    check = "켜 두기", on = ui.novice,
                ) { Stage.novice(!ui.novice) }
                KidsCard(ui, mod)
            }
            ListenTile(ui, Modifier.width(460.dp).fillMaxHeight())
            Column(Modifier.width(340.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                val mod = Modifier.weight(1f).fillMaxWidth()
                QuietTile(ui, mod)
                CareTile(ui.care, mod)
            }
            MoreTile(ui, Modifier.weight(1f).fillMaxHeight())
        }
        ForgetRow(ui)
    }
}

/** 켜고 끄는 큰 칸(운전 도움·가족 정보·조용히 가기): 머리, 지금 한 줄, 하는 일 두 줄까지, 체크 버튼 하나 */
@Composable
private fun ToggleTile(icon: ImageVector, label: String, color: Color, modifier: Modifier, now: String, line: String, check: String, on: Boolean, onClick: () -> Unit) {
    Column(modifier.frosted(sq(44)).padding(horizontal = 28.dp, vertical = 26.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Column {
            WidgetHead(icon, label, color)
            Text(now, style = t(36, W700, tracking = -.03), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp))
            Text(line, style = t(24, color = C.L2), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        }
        Btn(check, primary = on, height = 76, size = 24, pad = 22, icon = if (on) PhosphorIcons.Fill.CheckSquare else PhosphorIcons.Regular.Square, onClick = onClick)
    }
}

/**
 * 듣고 보기(검토-방향노인초보 3-9): 천천히 말하기·넉넉히 기다리기·해 지기 전에 알려 주기를 한 판에서 켜고 끈다.
 * 셋 다 쓰는 모습으로 저절로 켜지기도 해서(ride/SlowTalk·Dusk) 켜진 까닭을 한 줄 옅게 붙인다. 나이는 말하지 않는다
 */
@Composable
private fun ListenTile(ui: StageUi, modifier: Modifier) {
    Column(modifier.frosted(sq(44)).padding(horizontal = 28.dp, vertical = 26.dp)) {
        WidgetHead(PhosphorIcons.Fill.Ear, "듣고 보기", C.Sky)
        Column(Modifier.padding(top = 16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val mod = Modifier.weight(1f).fillMaxWidth()
            CheckRow("천천히 말하기", "모든 말을 조금 천천히 해요", ui.slowWhy, ui.slowTalk, mod) { Stage.slow(!ui.slowTalk) }
            CheckRow("넉넉히 기다리기", "대답을 ${Stage.ANSWER_MS / 1000}초 대신 ${SlowTalk.LONG_WAIT_MS / 1000}초까지 기다려요", ui.waitWhy, ui.longWait, mod) { Stage.longWait(!ui.longWait) }
            CheckRow("해 지기 전에 알려 주기", "도착이 해 진 뒤면 출발 전에 알려요", ui.duskWhy, ui.dusk, mod) { Stage.dusk(!ui.dusk) }
        }
    }
}

/** 듣고 보기의 한 줄: 줄 전체가 누름 칸. 체크, 이름, 하는 일, 켜진 까닭(있으면) */
@Composable
private fun CheckRow(label: String, line: String, why: String?, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = sq(32)
    Row(
        modifier.press(shape, onClick = onClick).clip(shape).background(if (on) Color.White.copy(alpha = .07f) else Color.Transparent)
            .border(1.dp, Color.White.copy(alpha = if (on) .16f else .08f), shape).padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Icon(if (on) PhosphorIcons.Fill.CheckSquare else PhosphorIcons.Regular.Square, null, tint = if (on) C.Sky else C.L2, modifier = Modifier.size(40.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = t(28, W700, tracking = -.02), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, style = t(24, color = C.L2), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            why?.let { Text(it, style = t(24, color = C.L2b), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
        }
    }
}

/**
 * 조용히 가기(이번 운행만, 새 운행이면 꺼진다): 먼저 건네는 물음은 카드로만, 쉼터·혹시 사고·급할 때만 목소리(ride/Care).
 * 같은 때에 두 번 부른 조용히 가기는 다음엔 먼저 조용히 둔다(그 때와 [처음대로]는 '그 밖에' 화면)
 */
@Composable
private fun QuietTile(ui: StageUi, modifier: Modifier) {
    val learned = ui.habits.quietLearned
    // 늘 조용히(사용자 결정 11/04): [이번 운행]은 이 운행만, [늘]은 운행마다. 늘이 켜져 있어도 [이번 운행]을 끄면 이 운행만 평소대로
    Column(modifier.frosted(sq(44)).padding(horizontal = 28.dp, vertical = 26.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Column {
            WidgetHead(PhosphorIcons.Fill.SpeakerSimpleSlash, "조용히 가기", C.Lilac)
            Text(when { ui.alwaysQuiet && ui.quietRide -> "늘 조용히"; ui.quietRide -> "이번 운행 조용히"; else -> "꺼짐" },
                style = t(36, W700, tracking = -.03), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp))
            Text(when {
                ui.alwaysQuiet -> "운행마다 쉼터와\n급한 일만 말해요"
                ui.quietRide -> "이번 운행은 쉼터와\n급한 일만 말해요"
                learned.isNotEmpty() -> "${learned.first()}엔 먼저 조용히 가요"
                else -> "켜면 쉼터와\n급한 일만 말해요"
            }, style = t(24, color = C.L2), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("이번 운행", primary = ui.quietRide, height = 76, size = 22, pad = 18,
                icon = if (ui.quietRide) PhosphorIcons.Fill.CheckSquare else PhosphorIcons.Regular.Square) { Stage.quietRide(!ui.quietRide) }
            Btn("늘", primary = ui.alwaysQuiet, height = 76, size = 22, pad = 18,
                icon = if (ui.alwaysQuiet) PhosphorIcons.Fill.CheckSquare else PhosphorIcons.Regular.Square) { Stage.alwaysQuiet(!ui.alwaysQuiet) }
        }
    }
}

/** 차 돌봄 칸: 칸 전체가 차 돌봄을 연다. 갈 때가 된 항목 수와 이름(없으면 곧 될 것 하나). 줄은 세울 때·새 운행에 다시 센다(ride/CarCare) */
@Composable
private fun CareTile(c: CareUi, modifier: Modifier) {
    val due = c.rows.filter { it.due }
    val soon = c.rows.firstOrNull { it.soon }
    Column(
        modifier.press(sq(44)) { Stage.openCare() }.frosted(sq(44)).padding(horizontal = 28.dp, vertical = 26.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            WidgetHead(PhosphorIcons.Fill.Wrench, "차 돌봄", C.Amber)
            Text(
                when { c.rows.isEmpty() -> "정비 때 보기"; due.isEmpty() -> "때가 된 것 없어요"; else -> "때가 된 것 ${due.size}개" },
                style = t(36, W700, tracking = -.03), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp),
            )
            Text(
                when {
                    due.isNotEmpty() -> due.joinToString(" · ") { it.label }
                    soon != null -> "곧 ${soon.label} · ${soon.state}"
                    else -> "때가 되면 세웠을 때 알려요"
                },
                style = t(24, color = if (due.isNotEmpty()) C.Amber else C.L2), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
            )
        }
        Btn("자세히", primary = false, height = 76, size = 24, pad = 26, icon = PhosphorIcons.Regular.CaretRight, iconAfter = true) { Stage.openCare() }
    }
}

/** 곁들임 물음이 지금 묻는 중인가: 먼저 묻기에서 켜 두었고, "괜찮아요" 두 번으로 묻지 않게 되지 않았다(ride/Memory) */
private fun asking(ui: StageUi, key: String): Boolean = key in ui.asks && when (key) {
    "HOUR" -> !ui.habits.hour.quiet
    "DAERI" -> !ui.habits.daeri.quiet
    "JAM" -> !ui.habits.jam.quiet
    "PICK" -> !ui.habits.pick.quiet
    else -> true
}

/**
 * 먼저 묻기(사용자 결정 11/06): 곁들임 물음 다섯(한 시간·대리 운행·이야기에 비켜 서기·오래 막힐 때·같이 고르기)을 한 줄씩 켜고 끈다.
 * 새로 깐 앱은 모두 꺼져 있다. 줄 전체가 누름 칸이다. "괜찮아요" 두 번으로 묻지 않게 된 물음은 꺼진 것으로 보이고, 켜면 처음처럼 묻는다.
 * 주차 자리·쪽잠·배운 버릇과 [처음대로]는 아래 [그 밖에]가 여는 화면에(전에는 이 칸이 일곱 줄 요약이었다).
 * 이야기에 비켜 서기가 이미 켜져 있으면 물을 일이 없어 그 줄은 체크 대신 '켜 둠'을 보이고, 누르면 끄는 단추가 있는 [그 밖에]를 연다
 * (검토 11/06: 기능이 켜져 있는데 빈 체크로 보였고, 눌러도 물음만 바뀌어 마이크 재기를 끄는 줄로 잘못 읽혔다)
 */
@Composable
private fun MoreTile(ui: StageUi, modifier: Modifier) {
    Column(modifier.frosted(sq(44)).padding(horizontal = 28.dp, vertical = 26.dp)) {
        WidgetHead(PhosphorIcons.Fill.ChatCircleDots, "먼저 묻기", C.Mint)
        Text("켜 둔 것만 먼저 여쭤요", style = t(22, color = C.L2b), maxLines = 1, modifier = Modifier.padding(top = 4.dp))
        Column(Modifier.padding(top = 12.dp, bottom = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Stage.ASK_LINES.forEach { (key, line) ->
                val featureOn = key == "TALK" && ui.talkOn
                val on = featureOn || asking(ui, key)
                val shape = sq(28)
                Row(
                    Modifier.weight(1f).fillMaxWidth().press(shape) { if (featureOn) Stage.openMemoMore() else Stage.asks(key, !on) }.clip(shape)
                        .background(if (on) Color.White.copy(alpha = .07f) else Color.Transparent)
                        .border(1.dp, Color.White.copy(alpha = if (on) .16f else .08f), shape).padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(if (featureOn) PhosphorIcons.Regular.Microphone else if (on) PhosphorIcons.Fill.CheckSquare else PhosphorIcons.Regular.Square, null, tint = if (on) C.Mint else C.L2, modifier = Modifier.size(34.dp))
                    Text(line.first, style = t(24, W600, if (on) C.L1 else C.L2), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (featureOn) Text("켜 둠", style = t(22, W600, C.Mint), maxLines = 1)
                }
            }
        }
        Btn("그 밖에", primary = false, height = 76, size = 24, pad = 26, icon = PhosphorIcons.Regular.CaretRight, iconAfter = true) { Stage.openMemoMore() }
    }
}

/**
 * 기억 화면 맨 아래: 남기는 것과 보내는 것, [위치 보내기](사용자 결정 11/06, ride/GeoShare), [모두 잊기].
 * 모두 잊기는 그 자리에서 한 번 더 누르게 한다(묻는 카드는 첫 화면에만 뜬다), 8초 안에 누르지 않으면 되돌린다.
 * 켜고 끄는 설정(운전 도움·먼저 묻기·위치 보내기·이야기에 비켜 서기)과 사고 메모는 잊지 않는다(ride/Care·RideService의 onForget)
 */
@Composable
private fun BoxScope.ForgetRow(ui: StageUi) {
    var sure by remember { mutableStateOf(false) }
    LaunchedEffect(sure) { if (sure) { delay(8_000); sure = false } }
    Row(
        Modifier.align(Alignment.BottomStart).padding(start = 72.dp, end = 72.dp, bottom = 56.dp).fillMaxWidth().height(96.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (sure) {
            Column(Modifier.weight(1f)) {
                Text("한 번 더 누르면 모두 잊고 처음처럼 물어요", style = t(28, W700), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("켜고 끈 설정(운전 도움·먼저 묻기·위치 보내기 등)과 사고 메모는 그대로 둬요", style = t(24, color = C.L2), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
            Btn("그만두기", primary = false, height = 96, size = 30, pad = 36) { sure = false }
            Btn("모두 잊기", primary = true, height = 96, size = 30, pad = 36) { sure = false; Stage.forget(null) }
        } else {
            // 실제로 적는 것(ride/Memory·Baby·RideKeep·RideClock·AfterCrash·CarCare·AlleyDest·SlowTalk·Dusk·GeoShare, 설정): 대답과 그 시각, 설정, 운전 시간, 사고 메모(시각·장소·날씨), 정비한 때(거리·날짜), 골목 목적지 ‘그대로’ 횟수, 고속도로 정차 카드에 [괜찮아요]를 받은 자리(노선·이정 km, ride/HighwayStop).
            // 보내는 것(ride/Road·Crash·Dusk·Together·CarCare·AlleyDest): 위치는 골목·큰길 가리기와 병원·화장실·날씨·가게 찾기에만, 위치 보내기를 끄면 보내지 않는다
            Text(
                "대답과 그 시각, 설정, 운전 시간, 사고 메모, 정비한 때, 고속도로에서 [괜찮아요]를 누른 자리만 이 차에 남기고 들은 말은 남기지 않아요. 못 알아들은 말은 AI 서버로 보내요. " +
                    if (ui.geoSend) "위치는 골목·큰길을 가리고 병원·화장실·날씨·가게를 찾을 때만 보내고 저장하지 않아요"
                    else "위치는 보내지 않아요 · 병원은 앱 안 목록으로, 골목은 차 신호로 가려요",
                style = t(24, color = C.L2b), maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Btn("위치 보내기", primary = ui.geoSend, height = 96, size = 28, pad = 30,
                icon = if (ui.geoSend) PhosphorIcons.Fill.CheckSquare else PhosphorIcons.Regular.Square) { Stage.geo(!ui.geoSend) }
            Btn("모두 잊기", primary = false, height = 96, size = 30, pad = 36) { sure = true }
        }
    }
}

/**
 * 기억 화면 안의 '그 밖에'(검토-방향노인초보 2-2): 버릇 2×2(한 시간·조용히 가기·주차 자리·대리 운행)와 큰 카드 셋(쪽잠·오래 막힐 때·같이 고르기),
 * 이야기에 비켜 서기, [기억으로]. 카드 높이는 내용에 맞추되 셋을 같은 높이로 두고 330 아래로는 줄이지 않는다(판이 두 줄이 되어 제목 아래 한 줄을 가리지 않게, 11/01)
 */
@Composable
private fun MemoMorePage(ui: StageUi) {
    val h = ui.habits
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.BottomStart).padding(start = 72.dp, end = 72.dp, bottom = 196.dp).fillMaxWidth()) {
            MemoMore(ui)
            Row(
                Modifier.padding(top = 16.dp).fillMaxWidth().height(IntrinsicSize.Max).heightIn(min = 330.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                val mod = Modifier.weight(1f).fillMaxHeight()
                MemoCard(
                    PhosphorIcons.Fill.MoonStars, "쪽잠", C.Lilac, mod,
                    now = "${h.napMin ?: 15}분 쉬고\n${h.napLead / 60}분 앞서 깨워요",
                    why = when {
                        h.napLead > 120 -> "어렵게 깨신 날이 이어져서"
                        h.napMin != null -> "지난번에 고르신 길이"
                        else -> "처음 그대로"
                    },
                    progress = if (h.napLead >= 240) null else Progress(h.napHard, 2, if (h.napHard > 0) "한 번 더 어렵게 깨면 1분 일찍" else "두 번 어렵게 깨면 1분 일찍"),
                    changed = h.napChanged, kind = "NAP",
                )
                val jam = h.jam
                MemoCard(
                    PhosphorIcons.Fill.Lamp, "오래 막힐 때", C.Amber, mod,
                    now = when { "JAM" !in ui.asks -> OFF_NOW; jam.quiet -> "막혀도\n묻지 않아요"; jam.auto -> "10분 넘게 막히면\n알아서 바꿔요"; else -> "10분 넘게 막히면\n물어요" },
                    why = jam.why ?: "처음 그대로",
                    progress = if ("JAM" in ui.asks) progress(jam, "알아서 바꿔요", "묻지 않아요") else null,
                    changed = jam.changed, kind = "JAM",
                )
                val pick = h.pick
                MemoCard(
                    PhosphorIcons.Fill.ForkKnife, "같이 고르기", C.Sky, mod,
                    now = when { "PICK" !in ui.asks -> OFF_NOW; pick.quiet -> "밥때에도\n먼저 묻지 않아요"; else -> "밥때 같이 세우면\n물어요" },
                    why = pick.why ?: "처음 그대로",
                    progress = if ("PICK" in ui.asks) progress(pick, null, "먼저 묻지 않아요") else null,
                    changed = pick.changed, kind = "PICK",
                )
            }
        }
        Row(
            Modifier.align(Alignment.BottomStart).padding(start = 72.dp, end = 72.dp, bottom = 56.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("졸음쉼터는 졸음과 이어져 있어 기억과 상관없이 두 시간마다 물어요", style = t(24, color = C.L2b), modifier = Modifier.weight(1f))
            Btn(if (ui.talkOn) "이야기에 비켜 서기 · 켬" else "이야기에 비켜 서기 · 끔", primary = ui.talkOn, height = 96, size = 30, pad = 36) { Stage.talk(!ui.talkOn) }
            Btn("기억으로", primary = false, height = 96, size = 30, pad = 36, icon = PhosphorIcons.Regular.CaretLeft) { Stage.openMemoMore(false) }
        }
    }
}

/** 대리 운행 중의 기억 화면: 가려 둔다는 한 줄과 [대리 운행 끄기] */
@Composable
private fun MemoHidden() {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.BottomStart).padding(start = 72.dp, end = 72.dp, bottom = 196.dp).fillMaxWidth().frosted(sq(48)).padding(horizontal = 56.dp, vertical = 48.dp)) {
            WidgetHead(PhosphorIcons.Fill.SteeringWheel, "대리 운행", C.Lilac)
            Text("대리 운행 중엔 기억을 가려 둬요", style = t(48, W700, tracking = -.03, line = 1.2), modifier = Modifier.padding(top = 22.dp))
            Text("대리 운행을 끄면 다시 보여요", style = t(26, color = C.L2), modifier = Modifier.padding(top = 12.dp))
            Box(Modifier.padding(top = 30.dp)) { Btn("대리 운행 끄기", primary = true, height = 100, size = 33) { Stage.daeri(false) } }
        }
    }
}

// ── 3-4. 앱 정보(세워 둔 동안, 기억 화면 안): 시연 안내, 자료 출처, 함께 쓴 오픈소스 ──

@Composable
private fun AboutHead() {
    Column(Modifier.padding(start = 72.dp, end = 72.dp, top = 196.dp)) {
        Text("앱 정보", style = t(84, W700, tracking = -.035))
        Text("시연에서 대신한 것과 화면에 쓴 자료, 함께 쓴 오픈소스예요", style = t(32, W500, C.L2), modifier = Modifier.padding(top = 14.dp))
    }
}

/**
 * 앱 정보 판 셋(감사 F124·F125). 글은 확인한 사실만 쓴다: 시연 안내는 별첨 v9의 앱 정보·시연 표시 문구,
 * 출처는 각 기능의 자료(assets/rest·hospitals의 SOURCES.txt, 소방청 카드), 라이선스는 각 라이브러리의 POM과 LICENSE 파일(전문은 assets/licenses)
 */
@Composable
private fun AboutFront() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // 병원 목록을 모은 날(첫 줄 #asof=)만 읽는다. 목록 전체는 급할 때 처음 읽는다
    val asOf = remember { runCatching { ctx.assets.open("hospitals/hospitals.csv").bufferedReader().use { it.readLine() }.removePrefix("#asof=").replace('-', '.') }.getOrNull() }
    Box(Modifier.fillMaxSize()) {
        // 판 셋은 제목 아래(372)와 아래 줄 위(196) 사이에 가둔다. 내용 높이로 위로 자라게 두었더니 '자료 출처'가 20줄이 넘어 '앱 정보' 제목과 설명을 덮었다.
        // 넘치는 줄은 판 안에서 밀어 본다(AboutCard, 11/08 오류 점검 E5)
        Row(
            Modifier.align(Alignment.BottomStart).padding(start = 72.dp, end = 72.dp, top = 372.dp, bottom = 196.dp).fillMaxWidth().fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            val mod = Modifier.weight(1f).fillMaxHeight()
            AboutCard(
                PhosphorIcons.Fill.Info, "시연 안내", C.Sky, mod, "Gleo AI 역할은\nGemini API가 대신합니다",
                listOf(
                    "시연 음악과 빗소리는 ${Prend.NAME} 팀이 직접 만든 곡이에요",
                    "일반 가요에 같은 동작을 걸려면 음악 앱 연동이 필요해요",
                    "차의 목소리는 Gemini TTS로 미리 만든 AI 합성 음성이에요",
                ),
            )
            AboutCard(
                PhosphorIcons.Fill.Database, "자료 출처", C.Amber, mod, null,
                listOf(
                    "쉼터 · 한국도로공사 졸음쉼터 설치현황·휴게소정보·고속도로 암거 좌표, 공공데이터포털. 졸음쉼터 좌표는 팀이 이정으로 맞춰 붙인 값이에요",
                    "병원 · 국립중앙의료원 응급의료기관·달빛어린이병원 정보, 공공데이터포털${asOf?.let { ". 앱 안 목록은 $it 기준" } ?: ""}",
                    "응급처치 그림 · 소방청 교육자료, 공공누리 제1유형. 문장은 2025 한국 심폐소생술 가이드라인과 맞춰 다듬었어요",
                    "사고 안내 · 한국도로공사 2차사고 예방 안내, 행정안전부 국민안전24 교통사고 행동요령",
                    "화장실 · 전국주유소표준데이터, 공공데이터포털. 위치 보내기를 켜면 TMAP 장소 검색으로 빠진 주유소를 채워요(주유소는 등록할 때 공중화장실을 두어야 해요)",
                    "날씨 · 기상청 단기예보 조회서비스, 공공누리 제1유형, 공공데이터포털",
                ),
            )
            AboutCard(
                PhosphorIcons.Fill.Code, "함께 쓴 오픈소스", C.Lilac, mod, null,
                listOf(
                    "Pretendard · SIL OFL 1.1",
                    "ONNX Runtime · MIT",
                    "Silero VAD · MIT",
                    "Phosphor 아이콘 · MIT",
                    "Jetpack Compose · Media3 · AndroidX · Apache 2.0",
                    "Haze · Backdrop · Capsule · Apache 2.0",
                    "Kotlin · kotlinx.coroutines · Apache 2.0",
                ),
                foot = "라이선스 전문은 앱 안 licenses 폴더에 함께 넣어 두었어요",
            )
        }
        Row(
            Modifier.align(Alignment.BottomStart).padding(start = 72.dp, end = 72.dp, bottom = 56.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("세워 둔 동안만 열려요", style = t(24, color = C.L2b), modifier = Modifier.weight(1f))
            Btn("기억으로", primary = false, height = 96, size = 30, pad = 36, icon = PhosphorIcons.Regular.CaretLeft) { Stage.openAbout(false) }
        }
    }
}

/** 앱 정보 판 하나: 머리, 큰 글 한 줄(있으면), 줄마다 한 가지, 맨 아래 옅은 한 줄 */
@Composable
private fun AboutCard(icon: ImageVector, label: String, color: Color, modifier: Modifier, big: String?, lines: List<String>, foot: String? = null) {
    Column(modifier.frosted(sq(48)).padding(horizontal = 38.dp, vertical = 34.dp)) {
        WidgetHead(icon, label, color)
        big?.let { Text(it, style = t(40, W700, tracking = -.03, line = 1.2), modifier = Modifier.padding(top = 22.dp)) }
        // 판 높이가 정해져 있어(AboutFront) 줄이 넘치면 이 안에서 밀어 본다. 맨 아래 한 줄(foot)은 늘 보이게 남은 높이만 쓴다(11/08 오류 점검 E5)
        Column(
            Modifier.padding(top = if (big != null) 18.dp else 22.dp).weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            lines.forEach { Text(it, style = t(26, W500, C.L2, line = 1.35)) }
        }
        foot?.let { Text(it, style = t(22, color = C.L2b), modifier = Modifier.padding(top = 18.dp)) }
    }
}

/** 세는 중인 대답: 점(채운 수/모을 수)과 한 줄. 이미 알아서 하거나 묻지 않으면 null */
private data class Progress(val filled: Int, val of: Int, val text: String)

private fun progress(h: Habit, auto: String?, less: String): Progress? = when {
    h.quiet || h.auto -> null
    h.no > 0 -> Progress(h.no, 2, "“괜찮아요” ${h.no}번 · 한 번 더면 $less")
    auto != null && h.yes > 0 -> Progress(h.yes, 3, "“응” ${h.yes}번 · ${if (h.yes >= 2) "한 번 더면" else "세 번이면"} $auto")
    auto != null -> Progress(0, 3, "“응” 세 번이면 $auto")
    else -> Progress(0, 2, "“괜찮아요” 두 번이면 $less")
}

/**
 * 기억 카드 하나: 지금 하는 것, 바뀌었으면 '바뀜'과 그 까닭, 세는 중인 대답(점과 한 줄), 바뀌었으면 [처음대로].
 * 안 바뀐 카드는 '처음 그대로'를 적지 않는다(바뀐 카드만 눈에 띄게). 점과 한 줄은 지금 하는 것 바로 아래에 둔다
 */
@Composable
private fun MemoCard(icon: ImageVector, label: String, color: Color, modifier: Modifier, now: String, why: String, progress: Progress?, extra: String? = null, changed: Boolean, kind: String) {
    Column(modifier.frosted(sq(48)).padding(horizontal = 38.dp, vertical = 34.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                WidgetHead(icon, label, color)
                if (changed) ChangedTag()
            }
            Text(now, style = t(44, W700, tracking = -.03, line = 1.18), modifier = Modifier.padding(top = 22.dp))
            if (changed) Text(why, style = t(24, color = C.L2), modifier = Modifier.padding(top = 12.dp))
            extra?.let { Text(it, style = t(24, color = C.L2), modifier = Modifier.padding(top = 6.dp)) }
            progress?.let { p ->
                Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(p.of) { i -> Box(Modifier.size(18.dp).clip(CircleShape).background(if (i < p.filled) C.Mint else Color.White.copy(alpha = .16f))) }
                }
                Text(p.text, style = t(26, color = C.L2), modifier = Modifier.padding(top = 8.dp))
            }
        }
        if (changed) Box(Modifier.padding(top = 18.dp)) { Btn("처음대로", primary = false, height = 80, size = 26, pad = 32) { Stage.forget(kind) } }
    }
}

/**
 * 기억 화면의 가족 정보. 쓰면서 배우는 버릇이 아니라 사람이 정해 두는 것이라 '바뀜'·[처음대로] 대신 체크 하나로 바로 고친다.
 * 체크해 두면 사고 때 뒷좌석에 아이가 있는지 살피라고 알린다(사용자 결정 11/01, ride/Baby). 기억 화면의 큰 칸 하나(운전 도움 아래)
 */
@Composable
private fun KidsCard(ui: StageUi, modifier: Modifier) {
    val kids = ui.kids == true
    ToggleTile(
        PhosphorIcons.Fill.Baby, "가족 정보", C.Lilac, modifier,
        now = when (ui.kids) { true -> "아이가 있어요"; false -> "아이는 없어요"; null -> "아직 안 정했어요" },
        line = if (kids) "사고가 나면 뒷좌석을\n살피라고 알려요" else "체크해 두면 사고 때\n뒷좌석을 살피라고 알려요",
        check = "아이가 있어요", on = kids,
    ) { Stage.kids(!kids) }
}

/** 기억이 처음과 달라졌다는 꼬리표(민트: 쓰면서 맞춰진 좋은 변화) */
@Composable
private fun ChangedTag() {
    Box(Modifier.clip(Pill).background(C.Mint.copy(alpha = .16f)).border(1.dp, C.Mint.copy(alpha = .3f), Pill).padding(horizontal = 14.dp, vertical = 2.dp)) {
        Text("바뀜", style = t(24, W600, C.Mint))
    }
}

/** 먼저 묻기에서 꺼 둔 물음의 지금 한 줄(카드는 두 줄, 칸은 한 줄). 켜는 곳은 기억 화면의 '먼저 묻기' */
private const val OFF_NOW = "꺼 뒀어요\n먼저 묻기에서 켜요"
private const val OFF_ONE = "꺼 뒀어요 · 먼저 묻기에서 켜요"

/** 한 시간 물음에서 지난번 고른 것(Memory.hourPicked의 이름)을 사람 말로(조사까지) */
private val HOUR_PICKS = mapOf("AIR" to "바깥 공기를", "HEAT" to "열선을", "VENT" to "통풍을", "MUSIC" to "잔잔한 곡을")

/**
 * 그 밖의 버릇 한 판(두 줄 2×2): 한 시간 물음·조용히 가기 / 주차 자리·대리 운행. 지금 하는 것 한 줄, 바뀌었으면 '바뀜'과 까닭, [처음대로].
 * 한 시간 물음은 지난번 고른 것을 먼저 내미는 것도 배운 것으로 본다
 */
@Composable
private fun MemoMore(ui: StageUi) {
    val h = ui.habits
    Column(Modifier.fillMaxWidth().frosted(sq(48)).padding(horizontal = 38.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        val pick = h.hourPick?.let { HOUR_PICKS[it] }
        MemoMoreCell(
            PhosphorIcons.Fill.Coffee, "한 시간", C.Mint,
            now = when { "HOUR" !in ui.asks -> OFF_ONE; h.hour.quiet -> "묻지 않아요"; h.hour.auto -> "바깥 공기를 넣어 둬요"; else -> "한 시간 달리면 물어요" },
            why = if (h.hour.quiet || h.hour.auto) h.hour.why else pick?.let { "$it 먼저 권해요" },
            changed = h.hour.quiet || h.hour.auto || pick != null, kind = "HOUR",
        )
        // 부른 것은 다음엔 부르기 전에: 같은 칸에서 두 번 부른 조용히 가기(11/01 추천 2)
        val quiet = h.quietLearned
        MemoMoreCell(
            PhosphorIcons.Fill.SpeakerSimpleSlash, "조용히 가기", C.Lilac,
            now = if (quiet.isEmpty()) "부르면 그 운행만" else "${quiet.first()}엔 먼저 조용히${if (quiet.size > 1) " 외 ${quiet.size - 1}" else ""}",
            why = if (quiet.isEmpty()) null else "그 시간에 두 번 조용히 가자고 하셔서",
            changed = quiet.isNotEmpty(), kind = "QUIET",
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        MemoMoreCell(
            PhosphorIcons.Fill.MapPin, "주차 자리", C.Sky,
            now = if (h.spot.quiet) "알리지 않아요" else "어두운 곳에 세우면 알려요",
            why = h.spot.why, changed = h.spot.quiet, kind = "SPOT",
        )
        // 대리 운행은 P에서 버튼으로도 켠다(별첨 2 §5-9 'PREND 화면의 메뉴'). 켜면 기억 화면은 가려지고 첫 화면에 [대리 끝]이 생긴다
        MemoMoreCell(
            PhosphorIcons.Fill.SteeringWheel, "대리 운행", C.Lilac,
            now = when { "DAERI" !in ui.asks -> OFF_ONE; h.daeri.quiet -> "묻지 않아요"; else -> "밤에 동승자가 타면 물어요" },
            why = h.daeri.why, changed = h.daeri.quiet, kind = "DAERI",
            action = "지금 켜기" to { Stage.daeri(true) },
        )
    }
    }
}

/** action은 [처음대로] 앞에 둘 버튼 하나(이름, 누르면 할 일) */
@Composable
private fun RowScope.MemoMoreCell(icon: ImageVector, label: String, color: Color, now: String, why: String?, changed: Boolean, kind: String, action: Pair<String, () -> Unit>? = null) {
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                WidgetHead(icon, label, color)
                if (changed) ChangedTag()
            }
            Text(now, style = t(30, W700, tracking = -.02), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            if (changed && why != null) Text(why, style = t(24, color = C.L2), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        action?.let { (name, go) -> Btn(name, primary = false, height = 68, size = 24, pad = 24, onClick = go) }
        if (changed) Btn("처음대로", primary = false, height = 68, size = 24, pad = 24) { Stage.forget(kind) }
    }
}

// ── 4. E 첫 화면: 고르거나 말한다 ──

@Composable
private fun EmergencyHead() {
    // 듣는 표시 줄과 아래 빨간 줄·[119 전화]는 앞 층(EmergencyListen)에 둔다(듣는 동안 바뀌는 글이 유리 카드를 다시 흐리지 않게)
    Column(Modifier.padding(start = 72.dp, end = 72.dp, top = 196.dp)) {
        Text("무슨 일인가요?", style = t(84, W700, tracking = -.035))
    }
}

private const val E_EXAMPLES = "“사고 났어” · “아이가 열이 나”"

/**
 * E 첫 화면의 듣는 표시(앞 층). E에서는 위쪽 듣는 중 캡슐을 띄우지 않으니 이 줄이 그 몫을 한다.
 * 들을 때만 막대가 목소리 크기를 따라 움직이고, 알아들은 말은 같은 줄에 흰 글씨로 나타난다. 듣지 않을 때는 멈춘 마이크와 말하는 법.
 * 자리는 바탕 층의 제목을 보이지 않게 놓아 맞춘다
 */
@Composable
private fun EmergencyListen(ui: StageUi, level: () -> Float) {
    val l = ui.listen
    val listening = l != null && !l.done
    Column(Modifier.padding(start = 72.dp, end = 72.dp, top = 196.dp)) {
        Text("무슨 일인가요?", style = t(84, W700, tracking = -.035), modifier = Modifier.alpha(0f))
        Row(Modifier.padding(top = 22.dp).height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            when {
                l != null && l.heard.isNotBlank() -> {
                    if (listening) Bars(6, C.L1, level = level)
                    HeardLine(l.heard)
                }
                listening -> {
                    Bars(6, C.L1, level = level)
                    Text("듣고 있어요", style = t(32, W500))
                    Text(E_EXAMPLES, style = t(32, W500, C.L2b))
                }
                l != null -> Text("잘 못 들었어요", style = t(32, W500, C.L2))
                else -> {
                    Icon(PhosphorIcons.Regular.Microphone, null, tint = C.L3, modifier = Modifier.size(32.dp))
                    Text("화면을 꾹 누르고 말해도 돼요", style = t(32, W500))
                    Text(E_EXAMPLES, style = t(32, W500, C.L2b))
                }
            }
        }
    }
    // 고장·연기는 큰 칸 둘보다 작게, 제목 오른쪽에(11/05: 전에는 말로만 열려 버튼으로 찾을 수 없었다).
    // 달리는 동안은 큰 버튼 둘까지라 두지 않는다(말 "연기 나"·"고장 났어"로 연다)
    if (!ui.brief) Row(Modifier.fillMaxWidth().padding(start = 72.dp, end = 72.dp, top = 196.dp), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.End)) {
        Btn("고장 났어요", primary = false, height = 84, size = 30, pad = 36, icon = PhosphorIcons.Fill.Wrench) { Stage.breakdown() }
        Btn("연기가 나요", primary = false, height = 84, size = 30, pad = 36, icon = PhosphorIcons.Fill.Fire) { Stage.fire() }
    }
    Row(
        Modifier.fillMaxSize().padding(start = 72.dp, end = 72.dp, bottom = 60.dp),
        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.weight(1f).height(96.dp), contentAlignment = Alignment.CenterStart) { RedLine("숨이나 의식이 걱정되면 먼저 119") }
        // 달리는 동안은 큰 버튼 둘([사고가 났어요] [갑자기 아파요])까지라 [119 전화]는 세웠을 때만
        if (!ui.brief) Btn("119 전화", primary = true, danger = true, height = 96, size = 34, pad = 44, icon = PhosphorIcons.Fill.Phone) { Stage.dial("119") }
    }
}

/** 알아들은 말: 0.3초에 걸쳐 흰 글씨로 나타난다(화면에만, 기록에는 남기지 않는다) */
@Composable
private fun HeardLine(heard: String) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(300)) }
    Text("“$heard”", style = t(32, W600), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.graphicsLayer { alpha = a.value })
}

@Composable
private fun EmergencyCards() {
    Row(Modifier.padding(start = 72.dp, end = 72.dp, top = 470.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        listOf(
            Triple(PhosphorIcons.Fill.CarProfile, "사고가 났어요", "대피 순서와 119에 말할 위치") to Scene.ACCIDENT,
            Triple(PhosphorIcons.Fill.Heartbeat, "갑자기 아파요", "119 안내와 가까운 응급실") to Scene.ILLNESS,
        ).forEach { (c, target) ->
            val (icon, title, desc) = c
            // [갑자기 아파요]는 누른 때를 남긴다: 말없이 단추만 눌렀고 혼자일 때만 운전자가 아픈 것으로 본다(검토 11/07 멋대로 판단, ride/Crash)
            Box(Modifier.weight(1f).height(420.dp).press(sq(56)) { if (target == Scene.ILLNESS) Stage.illButton() else Stage.go(target) }.frosted(sq(56), Color(0xFF2C2C30), .6f)) {
                Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    IconTile(icon, C.Red, C.Red.copy(alpha = .16f), 120, 36, 66)
                    Column {
                        Text(title, style = t(78, W800, tracking = -.035))
                        Text(desc, style = t(32, color = C.L2), modifier = Modifier.padding(top = 12.dp))
                    }
                }
                Icon(PhosphorIcons.Regular.CaretRight, null, tint = C.L3, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 44.dp).size(72.dp))
            }
        }
    }
}

// ── 5. E 안내의 한 걸음: 한 화면에 한 가지(걸음은 Stage.kt의 Guide) ──

/**
 * E 걸음이 바뀌면 옆으로 넘어간다: 앞으로는 오른쪽에서, 뒤로는 왼쪽에서 48dp, 0.24초(튕기지 않는다). 1/3에서 2/3으로 넘어간 것이 보이게.
 * 달리는 동안은 옅게 바뀌기만 한다(움직임을 두지 않는다). 걸음 글은 넘어가는 걸음(s)으로 그린다
 */
@Composable
private fun StepSlide(ui: StageUi, content: @Composable (StageUi) -> Unit) {
    val dx = with(LocalDensity.current) { 48.dp.roundToPx() }
    val still = ui.brief
    AnimatedContent(
        ui.step,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            if (still) fadeIn(tween(200)) togetherWith fadeOut(tween(160))
            else {
                val dir = if (targetState >= initialState) 1 else -1
                val ease = tween<IntOffset>(240, easing = FastOutSlowInEasing)
                (slideInHorizontally(ease) { dir * dx } + fadeIn(tween(240))) togetherWith (slideOutHorizontally(ease) { -dir * dx } + fadeOut(tween(160)))
            }
        },
        label = "step",
    ) { s -> content(ui.copy(step = s)) }
}

/**
 * E 걸음 맨 위의 띠 자리 하나(앞의 것이 먼저): "괜찮으세요?" 물음 띠(앞 층, 듣는 동안), 통화를 기다리는 한 줄(앞 층), SOS 띠(바탕 층).
 * 앞 층에 그리는 둘은 고리·단추가 움직여 유리 판을 다시 흐리지 않게 앞 층에 두고, 바탕 층의 걸음 글은 그 높이만큼 비워 둔다
 */
private enum class TopBand { NONE, OK, ASK, CALL, SOS }

private fun topBand(scene: Scene, ui: StageUi) = when {
    // 대답 창이 닫히면(듣기를 거둠) 띠도 거둔다
    ui.okAsk && ui.listen != null && (scene == Scene.ACCIDENT || scene == Scene.ILLNESS) -> TopBand.OK
    // 사고·아플 때 안내 중에 뜬 확인 카드("누가 아프세요?", Stage.confirm). E 화면에는 물음 카드 자리가 없어 이 띠 자리에 둔다(검토 11/07 멋대로 판단)
    ui.ask?.id?.startsWith("Q_") == true && (scene == Scene.ACCIDENT || scene == Scene.ILLNESS) && !ui.rescue -> TopBand.ASK
    ui.callWait -> TopBand.CALL
    scene == Scene.ACCIDENT && ui.sos -> TopBand.SOS
    else -> TopBand.NONE
}

/** 물음 띠와 통화 한 줄의 높이(바탕 층의 걸음 글이 이만큼 비워 둔다) */
private val OK_BAND_H = 112.dp
private val CALL_LINE_H = 64.dp

/**
 * 급할 때 안내가 "괜찮으세요?"를 묻고 듣는 동안 걸음 위의 띠(검토 11/06: 화면에는 물음도 듣는 표시도 대답 단추도 없었다).
 * [괜찮아요]·[다쳤어요](아플 때 운전자 확인은 [아파요])를 누르면 그 말을 들은 것처럼 넘긴다. 듣는 중 고리는 물음 카드와 같다
 */
@Composable
private fun OkBand(scene: Scene, ui: StageUi, modifier: Modifier) {
    val ill = scene == Scene.ILLNESS
    Row(
        modifier.height(OK_BAND_H).frosted(sq(40), Color(0xFF2C2C30), .6f).border(2.dp, Color.White.copy(alpha = .35f), sq(40)).padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("괜찮으세요?", style = t(48, W800, tracking = -.03), maxLines = 1)
        Btn("괜찮아요", primary = true, height = 84, size = 32, pad = 36) { Stage.okAnswer("괜찮아요") }
        // 아플 때는 운전자에게 묻는 말이라 "제가 아파요"로 넘긴다(누가 아픈지 몰랐으면 운전자로 바뀐다, ride/Crash.answeredDriver)
        Btn(if (ill) "아파요" else "다쳤어요", primary = false, height = 84, size = 32, pad = 36) { Stage.okAnswer(if (ill) "제가 아파요" else "다쳤어요") }
        Listening(ui)
    }
}

/**
 * 사고·아플 때 걸음 위의 확인 카드(Stage.confirm, 검토 11/07 멋대로 판단): 물음과 그 까닭 한 줄, [yes]·[no]. "괜찮으세요?" 띠와 같은 자리·크기.
 * 말 대답을 듣는 동안 고리가 돈다. 단추는 물음 카드처럼 Stage.answer로 넘긴다
 */
@Composable
private fun AskBand(ask: Ask, ui: StageUi, modifier: Modifier) {
    Row(
        modifier.height(OK_BAND_H).frosted(sq(40), Color(0xFF2C2C30), .6f).border(2.dp, Color.White.copy(alpha = .35f), sq(40)).padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(Modifier.weight(1f, fill = false)) {
            Text(ask.title, style = t(40, W800, tracking = -.03), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(ask.text, style = t(22, W500, C.L2), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        Btn(ask.yes, primary = true, height = 84, size = 30, pad = 32) { Stage.answer(ask.id, true) }
        if (ask.no.isNotBlank()) Btn(ask.no, primary = false, height = 84, size = 30, pad = 32) { Stage.answer(ask.id, false) }
        Listening(ui)
    }
}

/** 응급처치 화면에서 '119에 말할 위치' 칸 위로 띄우는 높이(확인 띠) */
private val AID_LOW = 376.dp

/**
 * 쪽잠·응급처치 화면의 확인 카드(Stage.confirm). 이 화면에는 물음 카드 자리가 없어 듣는 표시만 뜨고 말로만 대답할 수 있었다(시험 33).
 * 사고·아플 때 걸음의 물음 띠와 같은 꼴로, 쪽잠은 화면 아래, 응급처치는 위치 칸 위에 둔다
 */
@Composable
private fun BoxScope.SideAsk(ui: StageUi, bottom: Dp, any: Boolean = false) {
    // any면 확인 카드 말고 첫 화면 물음(HOME_ASKS)도 같은 띠로 그린다(쪽잠, 11/08 오류 점검 E2)
    val ask = ui.ask?.takeIf { it.id.startsWith("Q_") || any && homeAsk(it.id) } ?: return
    AskBand(ask, ui, Modifier.align(Alignment.BottomStart).padding(start = 72.dp, end = 72.dp, bottom = bottom).fillMaxWidth())
}

/**
 * 기억·차 돌봄·같이 고르기 화면에 뜬 물음(11/08 오류 점검 E2): 이 화면들은 물음을 그리지 않아, 목소리 없는 카드(이야기에 비켜 서기 등)는 보이지 않은 채
 * 30초 뒤 '대답 없음'으로 거둬졌고 목소리 물음은 단추가 없었다. 제목 자리에 물음 띠와 같은 꼴로 둔다(그동안 제목 옆 단추는 비킨다: 띠 밑으로 눌리지 않게)
 */
@Composable
private fun BoxScope.TopAsk(ui: StageUi) {
    val ask = ui.ask?.takeIf { homeAsk(it.id) } ?: return
    AskBand(ask, ui, Modifier.align(Alignment.TopStart).padding(start = 72.dp, end = 72.dp, top = 196.dp).fillMaxWidth())
}

/**
 * 지금 화면이 제 자리에 듣는 표시를 그리는가(물음 카드·띠의 고리, E 첫 화면의 듣기 줄). Hold의 듣는 중 캡슐은 이것이 거짓일 때만 뜬다
 * (11/08 오류 점검 E8·B9: 사고·아플 때·응급처치 걸음에서 꾹 눌러 부른 [프렌드]는 듣는 표시가 하나도 없었다. 쪽잠의 확인 띠는 캡슐과 '듣는 중'이 겹쳤다)
 */
internal fun drawsListen(ui: StageUi): Boolean {
    val id = ui.ask?.id
    return when (ui.scene) {
        Scene.HOME, Scene.PICK, Scene.MEMO, Scene.CARE, Scene.NAP -> homeAsk(id)
        Scene.DRIVE -> id == "REST" || homeAsk(id) || ui.demo
        Scene.EMERGENCY -> true
        Scene.ACCIDENT, Scene.ILLNESS -> !ui.rescue && topBand(ui.scene, ui).let { it == TopBand.OK || it == TopBand.ASK }
        Scene.AID -> id?.startsWith("Q_") == true
    }
}

/**
 * 한 줄 알림의 위 여백(Hold.ToastLayer). 사고·아플 때·응급처치 걸음에서 160에 두면 SOS 띠·물음 띠·'알아들었어요' 줄을 3초쯤 가렸고,
 * 아래로 내리면 '119에 말할 위치' 칸을 가렸다(시험 33): 위 막대 가운데(기어 글자와 [닫기] 사이의 빈자리)에 둔다. 다른 화면은 그대로
 */
internal fun toastTop(ui: StageUi): Dp = when (ui.scene) {
    // 급할 때 첫 화면도 위 막대로("무슨 일인가요?" 제목을 가렸다, 11/08 오류 점검)
    Scene.EMERGENCY, Scene.ACCIDENT, Scene.ILLNESS, Scene.AID -> 42.dp
    else -> 160.dp
}

/** 통화(블루링크 상담원·전화)가 끝나기를 기다리는 동안 걸음 위의 한 줄: 말이 없는 까닭(검토 11/06) */
@Composable
private fun CallWaitLine(modifier: Modifier) {
    Row(modifier.height(CALL_LINE_H), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(PhosphorIcons.Fill.PhoneCall, null, tint = C.L1, modifier = Modifier.size(40.dp))
        Text(CALL_WAIT, style = t(38, W700, tracking = -.02))
    }
}

/** 걸음 위 띠 자리(앞 층): 물음 띠나 통화 한 줄. 아플 때는 오른쪽 위 응급처치 카드 목록에 닿지 않게 그 폭만큼 비킨다 */
@Composable
private fun BoxScope.TopBandFront(scene: Scene, ui: StageUi) {
    val end = if (scene == Scene.ILLNESS) (72 + (if (ui.brief) 560 else 520) + 24).dp else 72.dp
    val at = Modifier.align(Alignment.TopStart).padding(start = 72.dp, end = end, top = 196.dp)
    when (topBand(scene, ui)) {
        TopBand.OK -> OkBand(scene, ui, at)
        TopBand.ASK -> ui.ask?.let { AskBand(it, ui, at) }
        TopBand.CALL -> CallWaitLine(at)
        else -> Unit
    }
}

/**
 * 사고 걸음 1·2의 [안 다쳤어요](SOS 띠 오른쪽, 세워 둔 동안): 5초 안에 대답하지 못했거나 잘못 알아들어 SOS로 간 안내를 늦게 바로잡는다
 * (검토 11/06 corr: 카드의 늦은 대답과 같은 생각). 일반 도로면 가벼운 사고 순서로 바뀐다(ride/Crash.notHurt)
 */
@Composable
private fun BoxScope.NotHurtBtn(scene: Scene, ui: StageUi) {
    if (scene != Scene.ACCIDENT || topBand(scene, ui) != TopBand.SOS || ui.step > 2 || ui.fire || ui.breakdown || ui.rescue) return
    Box(Modifier.align(Alignment.TopEnd).padding(top = 196.dp, end = 72.dp)) {
        Btn("안 다쳤어요", primary = false, height = 76, size = 30, pad = 36) { Stage.notHurt() }
    }
}

/**
 * 가벼운 사고 순서의 [다쳤어요](오른쪽 위, 세워 둔 동안, 검토 11/07): 괜찮다고 답한 뒤 다친 것을 알았을 때. 누르면 SOS 띠와 [119 전화],
 * 다친 사람을 옮기지 않는 일반 도로 순서로 바뀐다(ride/Crash.hurtLate)
 */
@Composable
private fun BoxScope.HurtLateBtn(scene: Scene, ui: StageUi) {
    if (scene != Scene.ACCIDENT || !ui.minor || topBand(scene, ui).let { it != TopBand.NONE && it != TopBand.CALL } || ui.fire || ui.breakdown || ui.rescue) return
    Box(Modifier.align(Alignment.TopEnd).padding(top = 180.dp, end = 72.dp)) {
        Btn("다쳤어요", primary = false, height = 76, size = 30, pad = 36, icon = PhosphorIcons.Fill.FirstAidKit) { Stage.hurtLate() }
    }
}

@Composable
private fun StepHead(scene: Scene, ui: StageUi) {
    val list = Guide.steps(ui)
    val s = list[(ui.step - 1).coerceIn(0, list.lastIndex)]
    if (ui.brief) { BriefHead(scene, ui, list, s); return }
    // 다쳤거나 대답이 없어 "SOS 버튼을 누르거나 119에 알리세요"를 말했으면 사고 안내의 모든 걸음 맨 위에.
    // "괜찮으세요?"를 듣는 동안·통화를 기다리는 동안은 그 자리를 앞 층의 띠가 쓰고, 여기서는 높이만큼 비운다
    val band = topBand(scene, ui)
    val banded = band != TopBand.NONE
    Column(Modifier.padding(start = 72.dp, end = 72.dp, top = 196.dp)) {
        when (band) {
            TopBand.SOS -> SosBand(Modifier.padding(bottom = 24.dp))
            TopBand.OK, TopBand.ASK -> Spacer(Modifier.height(OK_BAND_H + 24.dp))
            TopBand.CALL -> Spacer(Modifier.height(CALL_LINE_H + 24.dp))
            TopBand.NONE -> Unit
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            list.indices.forEach { i ->
                Box(Modifier.width(120.dp).height(10.dp).clip(CircleShape).background(if (i < ui.step) C.L1 else Color.White.copy(alpha = .16f)))
            }
            Text("${ui.step} / ${list.size}", style = t(28, W600, C.L2).copy(fontFeatureSettings = tnum), modifier = Modifier.padding(start = 14.dp))
            // 아이 탑승·가족 정보에 아이가 있으면 모든 걸음 맨 위에
            if (scene == Scene.ACCIDENT && (ui.baby || ui.kids == true)) BabyPill(ui.baby, Modifier.padding(start = 24.dp))
        }
        // 아플 때는 오른쪽 위 응급처치 카드 목록에 닿지 않게, 위 띠(SOS·물음·통화)가 있으면 아래 판에 닿지 않게 조금 작게
        Text(s.title, style = t(if (scene == Scene.ILLNESS || banded) 96 else 108, W800, tracking = -.04, line = 1.1), modifier = Modifier.padding(top = if (banded) 30.dp else 46.dp))
        // "괜찮으세요?" 물음 띠(136dp)가 떠 있는 5~8초는 설명 줄을 감춘다: 세 줄 제목이면 아래 위치 판에 닿고(검토 11/06), 묻는 동안 읽을 것도 줄어든다
        if (band != TopBand.OK && band != TopBand.ASK) s.sub?.let { Text(it, style = t(36, W500, C.L2), modifier = Modifier.padding(top = 28.dp)) }
    }
}

/** 119·보험사·도로공사에 그대로 읽으면 되는 위치 한 줄. 모르면 null */
private fun whereLine(scene: Scene, ui: StageUi): String? = when {
    // 시연 값(정 과장의 하루)은 촬영 때만. 실제 운행에서는 지금 위치를
    ui.demo && scene == Scene.ACCIDENT && ui.highway -> "경부고속도로 부산 방향 144.6km 지점"
    ui.demo -> "대전 서구 청사로 189 앞"
    else -> ui.place
}

/**
 * 달리는 동안의 E 걸음(바탕 층): 큰 글씨 한 줄만. 119 걸음이고 위치를 알면 119에 말할 위치를, 아니면 지금 할 일을.
 * 목록·판·사진 칸은 세우면 보인다. 걸음은 목소리가 넘긴다. 아플 때 오른쪽 위 한 칸에 닿지 않게 조금 아래에서 시작한다
 */
@Composable
private fun BriefHead(scene: Scene, ui: StageUi, list: List<Step>, s: Step) {
    // 한 상자로 감싼다: 걸음 넘김(StepSlide의 AnimatedContent)은 안의 것에 화면 전체 크기를 그대로 넘겨, 감싸지 않으면
    // SOS 띠가 화면 아래 끝까지 빨갛게 늘어나 걸음 글을 덮었다(11/08 에뮬레이터, 달리는 동안 사고 안내)
    Box(Modifier.fillMaxSize()) {
        // 같은 자리에 물음 띠·통화 한 줄이 있으면 그것이 먼저(앞 층의 TopBandFront)
        if (topBand(scene, ui) == TopBand.SOS) SosBand(Modifier.padding(start = 72.dp, top = 196.dp))
        val where = whereLine(scene, ui).takeIf { s.dial == "119" }
        // 위치가 둘이면(고속도로인지 애매) 둘 다 끝까지 보이게 조금 작게, 줄을 더 준다(큰 글씨 두 줄이면 이정 숫자가 잘렸다)
        val both = Guide.placeBoth(where)
        Column(Modifier.padding(start = 72.dp, end = 72.dp, top = 340.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (both) "둘 중 맞는 곳을 말하세요" else if (where != null) "119에 말할 위치" else "지금 할 일 · ${ui.step} / ${list.size}", style = t(30, W600, C.L2).copy(fontFeatureSettings = tnum))
                // 아이 탑승·가족 정보에 아이가 있으면 한 줄 판에도(119·구조대에 알릴 일)
                if (scene == Scene.ACCIDENT && (ui.baby || ui.kids == true)) BabyPill(ui.baby, Modifier.padding(start = 24.dp))
            }
            Text(where ?: s.title.replace("\n", " "), style = t(if (both) 64 else 96, W800, tracking = -.04, line = 1.1).copy(fontFeatureSettings = tnum),
                maxLines = if (both) 4 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 24.dp))
        }
    }
}

/** E 걸음 아래의 빨간 한 줄 */
private fun redLine(scene: Scene, ui: StageUi) = when {
    // 누가 아픈지 모를 때도(버튼만 눌렀고 말로 가르지 않음, 응급처치 카드 아님) 운전자 줄을 둔다(11/06: 세운 부모가 병원 길을 받아 직접 몰고 갈 수 있었다)
    scene == Scene.ILLNESS && (ui.driverIll || !ui.kid && ui.illOther != true && ui.aidWant == null) -> "운전하는 분이 아프면 직접 몰고 가지 말고 119"
    // 고장(11/02): 한국도로공사 2차사고 예방 신고처는 112·119·1588-2504
    scene == Scene.ACCIDENT && ui.fire -> "연기가 나면 먼저 멀리 · 차로 다시 가지 마세요"
    scene == Scene.ACCIDENT && ui.breakdown && ui.highway -> "먼저 가드레일 밖으로 · 대피한 뒤 1588-2504·112에 알리세요"
    scene == Scene.ACCIDENT && ui.breakdown -> "먼저 길 밖 안전한 곳으로 · 몸이 아프면 바로 119"
    scene == Scene.ILLNESS -> "숨이나 의식이 걱정되면 먼저 119"
    ui.minor -> "다친 사람이 있거나 몸이 아프면 바로 119"
    // 한국도로공사 2차사고 예방 수칙: 가드레일 밖으로 대피한 뒤 112·119·1588-2504
    ui.highway -> "먼저 가드레일 밖으로 · 대피한 뒤 112·119에 알리세요"
    else -> "먼저 안전한 곳으로 · 대피한 뒤 119에 알리세요"
}

/**
 * 그 걸음의 전화 버튼. 119는 빨간 버튼(한 화면에 하나), 긴급견인은 옅은 버튼.
 * sos(SOS 띠가 켜진 사고 안내)면 119가 없는 걸음에도 빨간 [119 전화]를 먼저 둔다(검토 11/06: 띠는 119를 말하는데 단추는 3걸음에만 있었다).
 * 이미 119가 있는 걸음에는 두 번 두지 않는다
 */
@Composable
private fun DialBtn(s: Step, sos: Boolean = false) {
    // 누르면 쓴 안내로 센다(짐작 줄을 거두고 사고 메모를 남긴다, 검토 11/07 멋대로 판단)
    if (sos && s.dial != "119") Btn("119 전화", primary = true, danger = true, height = 104, size = 36, pad = 44, icon = PhosphorIcons.Fill.Phone) { Stage.guideTouched(); Stage.dial("119") }
    val tel = s.dial ?: return
    if (tel == "119") Btn("119 전화", primary = true, danger = true, height = 104, size = 36, pad = 44, icon = PhosphorIcons.Fill.Phone) { Stage.guideTouched(); Stage.dial(tel) }
    else Btn("전화 걸기", primary = false, height = 104, size = 34, pad = 40, icon = PhosphorIcons.Fill.Phone) { Stage.guideTouched(); Stage.dial(tel) }
}

/** 아플 때 둘째 걸음에서 먼저 권하는 곳이 달빛어린이병원인가(아이 이야기이거나 응급실을 못 찾음) */
private fun moonFirst(ui: StageUi) = ui.hosp.moonFirst(ui.kid) || (ui.hosp.er == null && ui.hosp.moon != null)

@Composable
private fun StepFront(scene: Scene, ui: StageUi) {
    val list = Guide.steps(ui)
    val last = ui.step >= list.size
    val step = list[(ui.step - 1).coerceIn(0, list.lastIndex)]
    if (ui.brief) { BriefFront(scene, ui, step); return }
    Box(Modifier.fillMaxSize()) {
        // 아플 때: 오른쪽 위에 응급처치 카드 넷(세워 둔 동안만 열림)
        if (scene == Scene.ILLNESS) AidPanel(ui, Modifier.align(Alignment.TopEnd).padding(top = 196.dp, end = 72.dp))
        // 걸음 맨 위: "괜찮으세요?" 물음 띠·통화 한 줄, SOS 띠 오른쪽의 [안 다쳤어요]
        TopBandFront(scene, ui)
        NotHurtBtn(scene, ui)
        HurtLateBtn(scene, ui)
        // AI 대역이 짐작으로 연 안내면 위 막대 바로 아래에 '‘사고’로 알아들었어요 [아니에요]'(검토 11/07 멋대로 판단)
        GuessLine(scene, ui)
        // 119에 말할 위치(아플 때 둘째 걸음부터는 병원)
        val hospital = scene == Scene.ILLNESS && ui.step >= 2
        val card = Modifier.align(Alignment.BottomStart).padding(start = 72.dp, end = 72.dp, bottom = 210.dp).fillMaxWidth()
            .frosted(sq(56), Color(0xFF2C2C30), .6f).padding(horizontal = 48.dp, vertical = 36.dp)
        if (hospital) HospitalCard(ui, card)
        else if (scene == Scene.ACCIDENT && step.card == Card.INSURER) InsurerCard(ui, card)
        else if (scene == Scene.ACCIDENT && step.card == Card.PHOTO) ListCard(PhosphorIcons.Fill.Camera, "찍어 둘 것", photoItems(ui), null, card)
        else if (scene == Scene.ACCIDENT && step.card == Card.SWAP) ListCard(PhosphorIcons.Fill.Handshake, "주고받을 것", listOf("연락처", "차 번호", "보험사"), "더 자세한 행동요령 · 행정안전부 국민안전24 ‘교통사고’", card)
        else Row(card, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            IconTile(PhosphorIcons.Fill.MapPin, C.L1, Color.White.copy(alpha = .10f), 96, 30, 52)
            Column {
                // 119·보험사·도로공사에 그대로 읽으면 되는 한 줄(위치를 모르면 모른다고)
                val where = whereLine(scene, ui)
                // 위치가 둘이면(고속도로인지 애매, 검토 11/07 멋대로 판단) 말할 문장으로 감싸지 않고 둘을 그대로 보여 맞는 곳을 고르게 한다
                val both = Guide.placeBoth(where)
                Text(if (both) "둘 중 맞는 곳을 말하세요" else if (scene == Scene.ACCIDENT) "119·보험사에 이렇게 말하세요" else "119에 말할 위치", style = t(26, W600, C.L2))
                Text(
                    when {
                        where == null -> "위치를 찾는 중이에요"
                        both -> where
                        scene == Scene.ACCIDENT -> "“여기는 $where${if (ui.highway) "이에요" else " 근처예요"}”"
                        else -> where
                    },
                    style = t(54, W700, tracking = -.025).copy(fontFeatureSettings = tnum), modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        Row(
            Modifier.align(Alignment.BottomStart).padding(start = 72.dp, end = 72.dp, bottom = 56.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 빨간 한 줄은 버튼이 쓰고 남은 폭만(119 걸음은 버튼이 셋이라 두 줄로 접힐 수 있다)
            Box(Modifier.weight(1f)) { RedLine(redLine(scene, ui)) }
            if (scene == Scene.ILLNESS && ui.step == 2) {
                // 찾은 곳만 버튼으로(시연 값일 때는 둘 다). 먼저 권하는 곳이 흰 버튼. 버튼은 할 일을 말한다('응급실로')
                // [이전]은 늘 둔다(119에 말할 위치로 돌아가기). 못 찾았으면(인터넷·위치) [다시 찾기]
                val h = ui.hosp
                val first = moonFirst(ui)
                Btn("이전", primary = false, height = 104, size = 36, pad = 44, icon = PhosphorIcons.Regular.CaretLeft) { Stage.guideTouched(); Stage.step(-1, list.size) }
                // 시연 값은 응급실 한 칸뿐이라 달빛어린이병원 버튼도 두지 않는다
                if (h.moon != null) Btn("달빛어린이병원으로", primary = first, height = 104, size = 34, pad = 40, icon = PhosphorIcons.Regular.NavigationArrow, iconAfter = true) { Stage.hospital("moon") }
                if (ui.demo || h.er != null) Btn("응급실로", primary = !first, height = 104, size = 34, pad = 44, icon = PhosphorIcons.Regular.NavigationArrow, iconAfter = true) { Stage.hospital("er") }
                if (!ui.demo && !h.loading && h.moon == null && h.er == null) Btn("다시 찾기", primary = true, height = 104, size = 36, pad = 48) { Stage.hospitalAgain() }
            } else {
                if (ui.step > 1) Btn("이전", primary = false, height = 104, size = 36, pad = 44, icon = PhosphorIcons.Regular.CaretLeft) { Stage.guideTouched(); Stage.step(-1, list.size) }
                // 119 걸음은 [다음] 왼쪽에 빨간 [119 전화], 견인 걸음은 [전화 걸기]. 버튼 이름은 응급처치 카드와 같게([다음]·[마치기]).
                // SOS 띠가 켜져 있으면 모든 걸음에 [119 전화]
                DialBtn(step, sos = scene == Scene.ACCIDENT && ui.sos)
                Btn(if (last) "마치기" else "다음", primary = true, height = 104, size = 36, pad = 48, icon = if (last) null else PhosphorIcons.Regular.CaretRight, iconAfter = true) {
                    // 걸음 단추는 쓴 안내로 센다(짐작 줄을 거두고 사고 메모를 남긴다)
                    if (last) Stage.go(Scene.HOME) else { Stage.guideTouched(); Stage.step(1, list.size) }
                }
            }
        }
    }
}

/**
 * 달리는 동안의 E 걸음(앞 층): 빨간 줄과 큰 버튼 두 개까지. [이전]·[다음]은 두지 않는다(걸음은 목소리가 넘긴다).
 * 119 걸음은 [119 전화](SOS 띠가 켜져 있으면 모든 걸음에), 아플 때 병원 걸음은 먼저 권하는 곳 하나. 아플 때 오른쪽 위에는 '세우면 바로 열려요' 한 칸.
 * "괜찮으세요?"를 듣는 동안은 위의 물음 띠([괜찮아요]·[다쳤어요])가 큰 버튼 둘이라 아래 [119 전화]는 그 뒤에 둔다
 */
@Composable
private fun BriefFront(scene: Scene, ui: StageUi, step: Step) {
    Box(Modifier.fillMaxSize()) {
        if (scene == Scene.ILLNESS) AidPanel(ui, Modifier.align(Alignment.TopEnd).padding(top = 196.dp, end = 72.dp))
        TopBandFront(scene, ui)
        // 짐작 줄은 달리는 동안에도 둔다(작은 글과 작은 단추 하나. 잘못 열린 안내를 손 한 번에 거두게)
        GuessLine(scene, ui)
        Row(
            Modifier.align(Alignment.BottomStart).padding(start = 72.dp, end = 72.dp, bottom = 56.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.weight(1f)) { RedLine(redLine(scene, ui)) }
            // 물음 띠·확인 카드가 떠 있으면 그 단추 둘이 큰 버튼이라 [119 전화]는 그 뒤에
            if (topBand(scene, ui) != TopBand.OK && topBand(scene, ui) != TopBand.ASK) DialBtn(step, sos = scene == Scene.ACCIDENT && ui.sos)
            if (scene == Scene.ILLNESS && ui.step == 2) {
                // 달리는 동안은 시연 값이 아니다(brief). 찾은 곳 가운데 먼저 권하는 곳 하나만
                val h = ui.hosp
                when {
                    moonFirst(ui) && h.moon != null -> Btn("달빛어린이병원으로", primary = true, height = 104, size = 34, pad = 40, icon = PhosphorIcons.Regular.NavigationArrow, iconAfter = true) { Stage.hospital("moon") }
                    h.er != null -> Btn("응급실로", primary = true, height = 104, size = 34, pad = 44, icon = PhosphorIcons.Regular.NavigationArrow, iconAfter = true) { Stage.hospital("er") }
                    !h.loading -> Btn("다시 찾기", primary = true, height = 104, size = 36, pad = 48) { Stage.hospitalAgain() }
                }
            }
        }
    }
}

/**
 * AI 대역이 짐작으로 연 사고·아플 때 안내의 한 줄(검토 11/07 멋대로 판단): "‘사고’로 알아들었어요" [아니에요]. 누르면 안내를 거두고 첫 화면으로(Stage.guessWrong).
 * 위 막대(40~140)와 걸음 글(196~) 사이에 두어 SOS 띠·물음 띠·[안 다쳤어요]·[119 전화]를 가리지 않는다.
 * 짐작한 갈래의 화면에서만 보인다(연기·고장 안내로 바뀌었으면 감춘다). 20초가 지나거나 대답·걸음 단추를 누르면 ride/Crash가 거둔다
 */
@Composable
private fun BoxScope.GuessLine(scene: Scene, ui: StageUi) {
    val g = ui.guess ?: return
    val fits = (g == "사고" && scene == Scene.ACCIDENT && !ui.fire && !ui.breakdown) || (g == "아플 때" && scene == Scene.ILLNESS)
    if (!fits || ui.rescue) return
    Row(
        Modifier.align(Alignment.TopStart).padding(start = 72.dp, top = 140.dp).height(54.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("‘$g’로 알아들었어요", style = t(26, W600, C.L2), maxLines = 1)
        Btn("아니에요", primary = false, height = 50, size = 24, pad = 26) { Stage.guessWrong() }
    }
}

@Composable
/** sure: 이번 운행에 아이가 탔다고 했다("아이 탔어"). 아니면 가족 정보에 아이가 있어 살펴보라고만 한다 */
private fun BabyPill(sure: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(CircleShape).background(C.Red).padding(horizontal = 26.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(PhosphorIcons.Fill.Baby, null, tint = Color.White, modifier = Modifier.size(30.dp))
        Text(if (sure) "뒷좌석에 아이가 있어요" else "뒷좌석 아이 확인", style = t(28, W700, Color.White))
    }
}

/** 사진 걸음의 목록. 고속도로에서는 길로 다시 들어가지 않는 것만 */
private fun photoItems(ui: StageUi) =
    if (ui.highway) listOf("블랙박스 영상 따로 저장", "본 사람의 연락처", "사진은 가드레일 밖에서만")
    else listOf("차 네 방향", "부서진 곳", "상대 번호판", "바닥 흔적과 표지판", "블랙박스 영상 따로 저장")

/** 걸음 아래 판: 아이콘, 머리말, 낱말 칸들, 맨 아래 한 줄 */
@Composable
private fun ListCard(icon: ImageVector, label: String, items: List<String>, foot: String?, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        IconTile(icon, C.L1, Color.White.copy(alpha = .10f), 96, 30, 52)
        Column {
            Text(label, style = t(26, W600, C.L2))
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items.forEach { Text(it, style = t(34, W600), modifier = Modifier.clip(sq(20)).background(Color.White.copy(alpha = .08f)).padding(horizontal = 22.dp, vertical = 10.dp)) }
            }
            foot?.let { Text(it, style = t(24, color = C.L2b), modifier = Modifier.padding(top = 14.dp)) }
        }
    }
}

/**
 * 보험사 걸음의 판. 내 보험사를 골라 두었으면 그 접수 번호와 [전화 걸기], 아니면 보험사를 고르는 칸(한 번 고르면 기억).
 * 아래에 사고 메모(시각·장소·날씨) 한 줄: 보험사에 그대로 말하면 된다
 */
@Composable
private fun InsurerCard(ui: StageUi, modifier: Modifier) {
    val mine = Guide.INSURERS.firstOrNull { it.name == ui.insurer }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            IconTile(PhosphorIcons.Fill.Phone, C.L1, Color.White.copy(alpha = .10f), 96, 30, 52)
            Column(Modifier.weight(1f)) {
                if (mine != null) {
                    Text("내 보험사 · 사고 접수", style = t(26, W600, C.L2))
                    Text(listOfNotNull(mine.name, mine.tel).joinToString("  "), style = t(54, W700, tracking = -.025).copy(fontFeatureSettings = tnum), modifier = Modifier.padding(top = 6.dp))
                    if (mine.tel == null) Text("보험증권이나 보험사 앱의 사고 접수 번호로 전화하세요", style = t(26, color = C.L2), modifier = Modifier.padding(top = 6.dp))
                    mine.ars?.let { Text("연결되면 사고 접수는 $it · 24시간", style = t(26, color = C.L2), modifier = Modifier.padding(top = 6.dp)) }
                } else {
                    Text("내 보험사를 고르면 접수 번호를 바로 띄워요 · 한 번 고르면 기억해요", style = t(26, W600, C.L2))
                }
            }
            if (mine?.tel != null) Btn("전화 걸기", primary = true, height = 96, size = 34, pad = 44, icon = PhosphorIcons.Fill.Phone) { Stage.guideTouched(); Stage.dial(mine.tel) }
            if (mine != null) Btn("바꾸기", primary = false, height = 96, size = 30, pad = 32) { Stage.insurer(null) }
        }
        // 보험사 칸은 높이 80(급할 때 누르는 칸일수록 크게). 폭이 모자라면 다음 줄로
        if (mine == null) FlowRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Guide.INSURERS.forEach { i ->
                Box(
                    Modifier.height(80.dp).press(sq(22)) { Stage.guideTouched(); Stage.insurer(i.name) }.clip(sq(22)).background(Color.White.copy(alpha = .10f)).padding(horizontal = 20.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(i.name.removeSuffix("손해보험").removeSuffix("화재").ifBlank { i.name }, style = t(26, W600)) }
            }
        }
        ui.note?.let { Text("사고 메모 · ${remember(it) { noteLine(it) }}", style = t(26, color = C.L2), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 18.dp)) }
    }
}

// ── 5-1. 구조 요청: 에어백 뒤 대답이 없을 때, 구조하러 온 사람이 읽는 화면 ──

@Composable
private fun RescueHead(ui: StageUi) {
    Column(Modifier.padding(start = 72.dp, end = 72.dp, top = 196.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(PhosphorIcons.Fill.Warning, null, tint = C.Red, modifier = Modifier.size(40.dp))
            Text(ui.rescueWhy, style = t(32, W600, C.Red).copy(fontFeatureSettings = tnum))
        }
        Text(if (ui.baby) "이 차 뒷좌석에\n아이가 타고 있어요" else "운전자가\n대답하지 않아요", style = t(116, W800, tracking = -.04, line = 1.08), modifier = Modifier.padding(top = 36.dp))
        // 가족 정보에 아이가 있으면(그날 탔는지는 모른다) 뒷좌석을 살펴 달라고 먼저
        if (!ui.baby && ui.kids == true) Text("뒷좌석에 아이가 있는지 살펴봐 주세요", style = t(52, W700, C.L1), modifier = Modifier.padding(top = 28.dp))
        Text("119에 신고해 주세요", style = t(52, W700, C.L2), modifier = Modifier.padding(top = if (!ui.baby && ui.kids == true) 10.dp else 28.dp))
    }
}

@Composable
private fun RescueFront(ui: StageUi) {
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier.align(Alignment.BottomStart).padding(start = 72.dp, end = 72.dp, bottom = 210.dp).fillMaxWidth()
                .frosted(sq(56), Color(0xFF2C2C30), .6f).padding(horizontal = 48.dp, vertical = 36.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            IconTile(PhosphorIcons.Fill.MapPin, C.L1, Color.White.copy(alpha = .10f), 96, 30, 52)
            Column {
                Text("119에 말할 위치", style = t(26, W600, C.L2))
                Text(ui.place ?: "위치를 찾는 중이에요", style = t(54, W700, tracking = -.025).copy(fontFeatureSettings = tnum), modifier = Modifier.padding(top = 6.dp))
            }
        }
        Row(
            Modifier.align(Alignment.BottomStart).padding(start = 72.dp, end = 72.dp, bottom = 56.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 아플 때의 구조 화면은 119 상황요원이 전화로 할 일을 알려 준다는 한 줄(119 전화 응급처치 지도, 질병관리청)
            RedLine(if (ui.scene == Scene.ILLNESS) "119에 전화하면 상황요원이 할 일을 알려 줘요" else "다친 사람은 불이 나지 않았다면 옮기지 마세요")
            Spacer(Modifier.weight(1f))
            Btn("괜찮아요 · 안내 보기", primary = false, height = 104, size = 34, pad = 44) { Stage.rescueOff() }
            Btn("119 전화", primary = true, danger = true, height = 104, size = 36, pad = 48, icon = PhosphorIcons.Fill.Phone) { Stage.dial("119") }
        }
    }
}

/**
 * 아플 때 둘째·셋째 걸음의 병원 판: 찾는 중 → 응급실(남은 병상)·달빛어린이병원(오늘 진료 시간) → 경유지로 넣은 곳.
 * 둘째 걸음에서 찾았으면 두 칸으로 나눠 거리를 크게 견준다(먼저 권하는 곳이 왼쪽, 흰 테두리)
 */
@Composable
private fun HospitalCard(ui: StageUi, modifier: Modifier) {
    val h = ui.hosp
    if (ui.step < 3 && (ui.demo || (!h.loading && h.error == null && (h.er != null || h.moon != null)))) { HospitalPick(ui, modifier); return }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        IconTile(PhosphorIcons.Fill.Hospital, C.Red, Color.White.copy(alpha = .10f), 96, 30, 52)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val big = t(44, W700, tracking = -.025).copy(fontFeatureSettings = tnum)
            when {
                ui.demo -> {
                    Text(if (ui.step >= 3) "경유지" else "가장 가까운 응급실", style = t(26, W600, C.L2))
                    Text(if (ui.step >= 3) "가까운 응급실 · 3.2km · 약 9분" else "3.2km · 약 9분", style = big)
                }
                ui.step >= 3 && h.chosen != null -> {
                    Text("경유지로 넣은 곳", style = t(26, W600, C.L2))
                    Text("${h.chosen.name} · ${km(h.chosen.km)}", style = big)
                    // 앱 안 목록에서 고른 곳은 그 뒤 바뀌었을 수 있다(감사 F40)
                    val call = if (h.asOf != null) "가기 전에 전화로 확인하세요" else null
                    listOfNotNull(call, h.chosen.tel?.let { "전화 $it" }).takeIf { it.isNotEmpty() }?.let {
                        Text(it.joinToString(" · "), style = t(28, if (call != null) W600 else FontWeight.Normal, if (call != null) C.L1 else C.L2).copy(fontFeatureSettings = tnum))
                    }
                }
                h.loading -> {
                    Text("가까운 병원", style = t(26, W600, C.L2))
                    Text("찾는 중이에요", style = big)
                }
                h.error != null -> {
                    Text("가까운 병원", style = t(26, W600, C.L2))
                    Text(h.error, style = big)
                }
                else -> {
                    Text("가까운 병원", style = t(26, W600, C.L2))
                    Text("아직 고르지 않았어요", style = big)
                }
            }
        }
    }
}

/** 병원 고르기 두 칸: 응급실(남은 병상)과 달빛어린이병원(오늘 진료 시간). 칸을 눌러도 아래 버튼과 같다 */
@Composable
private fun HospitalPick(ui: StageUi, modifier: Modifier) {
    val h = ui.hosp
    val first = moonFirst(ui)
    // 인터넷이 안 돼 앱 안 목록에서 골랐다(감사 F40): 칸마다 전화번호, 위에 '가기 전에 전화로 확인하세요'
    val offline = !ui.demo && h.asOf != null
    // 시연 값(정 과장의 하루)은 응급실 한 곳만
    val er = if (ui.demo) Hospital("가까운 응급실", "", null, 0.0, 0.0, 3.2) else h.er
    val erCell: @Composable RowScope.() -> Unit = {
        er?.let { e ->
            // 앱 안 목록에서 고른 곳은 남은 병상 대신 분류(지역응급의료센터 등)
            val note = when { ui.demo -> "약 9분"; e.beds == null -> e.kind; e.beds > 0 -> "남은 병상 ${e.beds}"; else -> "지금 붐빔" }
            HospCell("응급실", e, note, !first, Modifier.weight(1f).fillMaxHeight(), tel = if (offline) e.tel else null) { Stage.hospital("er") }
        }
    }
    val moonCell: @Composable RowScope.() -> Unit = {
        val m = h.moon
        if (m != null) HospCell("달빛어린이병원", m, "오늘 ${hour(m.open)}~${hour(m.close)}", first, Modifier.weight(1f).fillMaxHeight(), tel = if (offline) m.tel else null) { Stage.hospital("moon") }
        // 아이 이야기인데 진료 중인 곳이 없으면 빈 칸 대신 그렇다고 적는다
        else if (ui.kid && !ui.demo) Box(
            Modifier.weight(1f).fillMaxHeight().clip(sq(36)).background(Color.White.copy(alpha = .04f)).padding(horizontal = 32.dp, vertical = 24.dp),
            contentAlignment = Alignment.CenterStart,
        ) { Text("지금 진료 중인 달빛어린이병원은 가까이 없어요", style = t(28, W600, C.L2)) }
    }
    Column(modifier) {
        // 시연 값은 자료에서 온 것이 아니니 출처를 붙이지 않는다.
        // 앱 안 목록이면 '가기 전에 전화로 확인하세요'를 같은 줄 앞에 굵게(두 줄이면 판이 올라가 응급처치 카드 목록을 덮는다)
        Text(
            buildAnnotatedString {
                if (offline) withStyle(SpanStyle(color = C.L1, fontWeight = W700)) { append("가기 전에 전화로 확인하세요  ·  ") }
                append(when {
                    ui.demo -> "가까운 병원"
                    // '인터넷이 안 돼'는 서버가 답하지 않았을 때만(서버 결과가 비어 목록으로 왔으면 빼고)
                    offline -> "${if (h.noNet) "인터넷이 안 돼 " else ""}앱에 넣어 둔 목록 · 국립중앙의료원 자료 · ${h.asOf?.replace('-', '.')} 기준"
                    else -> "가까운 병원 · 국립중앙의료원 자료${h.er?.bedsAt?.let { " · ${hhmm(it.takeLast(6).take(4))} 기준" } ?: ""}"
                })
            },
            style = t(24, W600, C.L2), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.padding(top = 18.dp).fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            if (first) { moonCell(); erCell() } else { erCell(); moonCell() }
        }
    }
}

/** 병원 한 칸: 구분, 이름 한 줄, 거리(크게), 남은 병상이나 진료 시간, 앱 안 목록이면 전화번호. 먼저 권하는 칸은 흰 테두리와 '먼저 권해요' */
@Composable
private fun HospCell(kind: String, place: Hospital, note: String?, first: Boolean, modifier: Modifier, tel: String? = null, onClick: () -> Unit) {
    Column(
        modifier.press(sq(36), onClick = onClick).clip(sq(36)).background(Color.White.copy(alpha = if (first) .10f else .05f))
            .border(if (first) 2.dp else 1.dp, if (first) C.L1.copy(alpha = .7f) else Color.White.copy(alpha = .08f), sq(36))
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        // 앱 안 목록의 전화번호는 맨 위 줄 오른쪽에(줄을 늘리지 않게)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(kind, style = t(24, W600, C.L2))
            if (first) Text("먼저 권해요", style = t(24, W700))
            tel?.let {
                Spacer(Modifier.weight(1f))
                Text("전화 $it", style = t(26, W600, C.L1).copy(fontFeatureSettings = tnum), maxLines = 1)
            }
        }
        Text(place.name, style = t(34, W700, tracking = -.02), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(km(place.km), style = t(60, W700, tracking = -.03, line = 1.1).copy(fontFeatureSettings = tnum))
            note?.let { Text(it, style = t(28, W600, C.L2).copy(fontFeatureSettings = tnum), modifier = Modifier.padding(bottom = 8.dp)) }
        }
    }
}

// ── 5-2. 응급처치 카드: 왼쪽에 소방청 카드의 그림, 오른쪽에 한 걸음. 세워 둔 동안만(달리면 가린다) ──

/**
 * 아플 때 화면 오른쪽 위의 카드 고르기(세워 둔 동안). 달리는 동안은 눌러도 안 되는 목록 대신 '세우면 바로 열려요' 한 칸만:
 * 달리며 말한 카드(aidWant)가 있으면 그 카드를 세우면 연다고 적고 테두리를 밝힌다
 */
@Composable
private fun AidPanel(ui: StageUi, modifier: Modifier) {
    if (ui.brief) {
        val want = ui.aidWant
        Row(
            modifier.width(560.dp).frosted(sq(44), Color(0xFF2C2C30), .6f)
                .then(if (want != null) Modifier.border(2.dp, Color.White.copy(alpha = .3f), sq(44)) else Modifier)
                .padding(horizontal = 28.dp, vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Icon(PhosphorIcons.Fill.Heartbeat, null, tint = C.Red, modifier = Modifier.size(40.dp))
            Column {
                Text("응급처치 카드", style = t(26, W600, C.L2))
                Text(want?.let { "세우면 ${it.label} 카드를 열어요" } ?: "세우면 바로 열려요", style = t(32, W700), modifier = Modifier.padding(top = 4.dp))
            }
        }
        return
    }
    Column(modifier.width(520.dp).frosted(sq(44), Color(0xFF2C2C30), .6f).padding(horizontal = 28.dp, vertical = 26.dp)) {
        Text("응급처치 카드", style = t(24, W600, C.L2))
        Aid.entries.forEach { a ->
            Row(
                Modifier.padding(top = 12.dp).fillMaxWidth().heightIn(min = 80.dp).press(sq(22)) { Stage.openAid(a) }.clip(sq(22))
                    .background(Color.White.copy(alpha = if (ui.aidWant == a) .20f else .08f)).padding(horizontal = 24.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(a.label, style = t(30, W700), modifier = Modifier.weight(1f))
                Icon(PhosphorIcons.Regular.CaretRight, null, tint = C.L3, modifier = Modifier.size(32.dp))
            }
        }
    }
}

@Composable
private fun AidHead(ui: StageUi) {
    if (!ui.parked && !ui.demo) {
        Column(Modifier.padding(start = 72.dp, end = 72.dp, top = 236.dp)) {
            Text("달리는 동안은\n카드를 가려요", style = t(108, W800, tracking = -.04, line = 1.1))
            Text("차를 세우면 다시 보여 드려요", style = t(40, W500, C.L2), modifier = Modifier.padding(top = 32.dp))
        }
        return
    }
    val list = Guide.aid(ui.aid)
    val s = list[(ui.step - 1).coerceIn(0, list.lastIndex)]
    // 차량 패널이 왼쪽에 붙으면 PREND 창은 폭 약 1700·높이 약 1210이다. 그 안에서 위치 카드(아래)와 겹치지 않게
    Row(Modifier.padding(start = 72.dp, end = 72.dp, top = 196.dp), horizontalArrangement = Arrangement.spacedBy(56.dp)) {
        Column(Modifier.weight(.46f)) {
            AidPicture(s, Modifier.fillMaxWidth().height(500.dp))
            // 공공누리 제1유형: 출처를 자료 이름으로만 밝힌다(로고·추천처럼 보이는 문구는 쓰지 않는다). 밤에도 읽히게 L2b
            Text("그림·순서 ${ui.aid.source} · 공공누리 제1유형", style = t(22, color = C.L2b), modifier = Modifier.padding(top = 12.dp))
            Text("문장은 2025 한국 심폐소생술 가이드라인과 맞춰 다듬음", style = t(22, color = C.L2b), modifier = Modifier.padding(top = 2.dp))
        }
        // 경련 시계는 이 글 아래에 앞 층(SeizureClockFront)이 그린다
        Column(Modifier.weight(.54f)) { AidStepText(ui, list, s) }
    }
}

/** 응급처치 카드 오른쪽의 걸음 글(점, 제목, 설명) */
@Composable
private fun AidStepText(ui: StageUi, list: List<Step>, s: Step) {
    Column {
        // 목에 걸렸을 때는 위에 [1세 이상][돌 전 아기]가 앞 층에 놓인다
        if (ui.aid == Aid.CHOKE || ui.aid == Aid.INFANT) Spacer(Modifier.height(86.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            list.indices.forEach { i ->
                Box(Modifier.width(56.dp).height(10.dp).clip(CircleShape).background(if (i < ui.step) C.L1 else Color.White.copy(alpha = .16f)))
            }
            Text("${ui.step} / ${list.size}", style = t(28, W600, C.L2).copy(fontFeatureSettings = tnum), maxLines = 1, modifier = Modifier.padding(start = 12.dp))
        }
        Text(s.title, style = t(84, W800, tracking = -.04, line = 1.1), modifier = Modifier.padding(top = 32.dp))
        s.sub?.let { Text(it, style = t(32, W500, C.L2), modifier = Modifier.padding(top = 20.dp)) }
    }
}

/**
 * 경련 시계(앞 층). 1초마다 바뀌어 바탕 층에 두면 그때마다 위치 카드와 닫기 캡슐의 유리가 다시 흐려진다.
 * 자리는 바탕 층과 같은 걸음 글을 보이지 않게 놓아 맞춘다(설명이 몇 줄이든 그 바로 아래)
 */
@Composable
private fun SeizureClockFront(ui: StageUi) {
    val list = Guide.aid(ui.aid)
    val s = list[(ui.step - 1).coerceIn(0, list.lastIndex)]
    Row(Modifier.padding(start = 72.dp, end = 72.dp, top = 196.dp), horizontalArrangement = Arrangement.spacedBy(56.dp)) {
        Spacer(Modifier.weight(.46f))
        Column(Modifier.weight(.54f)) {
            Box(Modifier.alpha(0f)) { AidStepText(ui, list, s) }
            SeizureClock(ui, Modifier.padding(top = 28.dp))
        }
    }
}

/**
 * 걸음의 그림(따뜻한 회백 판). 그림이 둘이면 나란히, 없으면 그 걸음의 표시(“등 ×5”·“119”)를 크게.
 * 순백 판은 E의 검은 바탕 위에서 제목보다 먼저 눈을 찔러 판과 그림을 8% 낮춘다(밤 차 안)
 */
@Composable
private fun AidPicture(s: Step, modifier: Modifier) {
    if (s.pic == null) {
        Box(modifier.clip(sq(48)).background(Color.White.copy(alpha = .07f)), contentAlignment = Alignment.Center) {
            val m = s.mark ?: ""
            Text(m, style = t(if ('\n' in m) 130 else 150, W800, if (m == "119") C.Red else C.L1, tracking = -.04, line = 1.15)
                .copy(fontFeatureSettings = tnum, textAlign = androidx.compose.ui.text.style.TextAlign.Center))
        }
        return
    }
    Row(modifier.clip(sq(48)).background(AID_PAPER).padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        listOfNotNull(s.pic, s.pic2).forEach { id ->
            Image(painterResource(id), null, contentScale = ContentScale.Fit, colorFilter = AID_DIM, modifier = Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/** 응급처치 그림 판의 따뜻한 회백(#EDEAE4)과, 그림의 흰 바탕을 그 색으로 낮추는 거름(밝기 약 0.92, 흰색 → 판과 같은 색) */
private val AID_PAPER = Color(0xFFEDEAE4)
private val AID_DIM = ColorFilter.colorMatrix(ColorMatrix().apply { setToScale(.929f, .918f, .894f, 1f) })

/** 경련 카드: 카드를 연 뒤 지난 시간(시작 시각과 길이는 119·의사에게 말할 것). 5분이 넘으면 빨갛게 */
@Composable
private fun SeizureClock(ui: StageUi, modifier: Modifier) {
    // 보이는 초가 바뀔 때(카드를 연 때부터 1초마다)에만 깨운다
    val now by produceState(System.currentTimeMillis(), ui.aidSince) {
        while (true) { value = System.currentTimeMillis(); delay(1000 - Math.floorMod(value - ui.aidSince, 1000L)) }
    }
    val ms = (now - ui.aidSince).coerceAtLeast(0)
    val over = ms >= 5 * 60_000L
    // 시작 시각 글은 시작 시각이 바뀔 때만 만든다(날짜 형식을 1초마다 새로 만들지 않게)
    val since = remember(ui.aidSince) { SimpleDateFormat("a h시 m분", Locale.KOREA).format(Date(ui.aidSince)) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Text(mmss(ms), style = t(80, FontWeight.Light, if (over) C.Red else C.L1, tracking = -.03, line = 1.0).copy(fontFeatureSettings = tnum))
        Column(Modifier.weight(1f)) {
            Text("${since}부터 · 시작 시각과 길이를 119에 말하세요", style = t(26, color = C.L2))
            Text(if (over) "5분이 지났어요 · 아직 경련 중이면 바로 119" else "5분이 넘으면 바로 119", style = t(30, W700, if (over) C.Red else C.L1), modifier = Modifier.padding(top = 6.dp))
        }
        Btn("다시 재기", primary = false, height = 80, size = 28, pad = 34) { Stage.aidRestart() }
    }
}

@Composable
private fun AidFront(ui: StageUi) {
    val list = Guide.aid(ui.aid)
    val last = ui.step >= list.size
    val can = ui.parked || ui.demo
    Box(Modifier.fillMaxSize()) {
        if (can && ui.aid == Aid.SEIZURE) SeizureClockFront(ui)
        // 1세 이상과 돌 전 아기는 처치가 달라 위에서 바로 바꾼다
        if (can && (ui.aid == Aid.CHOKE || ui.aid == Aid.INFANT)) Row(
            Modifier.align(Alignment.TopEnd).padding(top = 186.dp, end = 72.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 누름 칸 80: 급할 때 바꾸는 칸이라 작게 두지 않는다(아래 걸음 글은 이만큼 비우고 시작한다)
            listOf(Aid.CHOKE to "1세 이상", Aid.INFANT to "돌 전 아기").forEach { (a, label) ->
                Btn(label, primary = ui.aid == a, height = 80, size = 28, pad = 30) { if (ui.aid != a) Stage.openAid(a) }
            }
        }
        Row(
            Modifier.align(Alignment.BottomStart).padding(start = 72.dp, end = 72.dp, bottom = 210.dp).fillMaxWidth()
                .frosted(sq(56), Color(0xFF2C2C30), .6f).padding(horizontal = 48.dp, vertical = 30.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            IconTile(PhosphorIcons.Fill.MapPin, C.L1, Color.White.copy(alpha = .10f), 84, 28, 46)
            Column {
                Text("119에 말할 위치", style = t(24, W600, C.L2))
                Text((if (ui.demo) "대전 서구 청사로 189 앞" else ui.place) ?: "위치를 찾는 중이에요",
                    style = t(46, W700, tracking = -.025).copy(fontFeatureSettings = tnum), modifier = Modifier.padding(top = 4.dp))
            }
        }
        Row(
            Modifier.align(Alignment.BottomStart).padding(start = 72.dp, end = 72.dp, bottom = 56.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 빨간 한 줄은 버튼이 쓰고 남은 폭만(마지막 걸음은 버튼이 넷까지라 두 줄로 접힐 수 있다)
            Box(Modifier.weight(1f)) { RedLine(ui.aid.red) }
            if (!can) return@Row
            if (ui.step > 1) Btn("이전", primary = false, height = 104, size = 34, pad = 40, icon = PhosphorIcons.Regular.CaretLeft) { Stage.step(-1, list.size) }
            if (!last) Btn("다음", primary = true, height = 104, size = 36, pad = 48, icon = PhosphorIcons.Regular.CaretRight, iconAfter = true) { Stage.step(1, list.size) }
            else {
                if (ui.aid == Aid.CHOKE || ui.aid == Aid.SEIZURE) Btn("심폐소생술", primary = false, height = 104, size = 32, pad = 34) { Stage.openAid(Aid.CPR) }
                Btn("가까운 병원", primary = false, height = 104, size = 32, pad = 34) { Stage.go(Scene.ILLNESS, 2) }
                Btn("마치기", primary = true, height = 104, size = 34, pad = 44) { Stage.go(Scene.HOME) }
            }
        }
    }
}

private fun km(v: Double) = if (!v.isFinite()) "거리 모름" else if (v < 10) "%.1fkm".format(v) else "${v.roundToInt()}km"

/** "0015" → "00:15" */
private fun hhmm(s: String) = if (s.length == 4) "${s.take(2)}:${s.drop(2)}" else s

/** "0900" → "9시", "1830" → "18시 30분", "2400" → "24시" */
private fun hour(s: String?): String {
    val v = s?.toIntOrNull() ?: return "?"
    return if (v % 100 == 0) "${v / 100}시" else "${v / 100}시 ${v % 100}분"
}

// ── 6. 쪽잠: 검은 바탕, 둥근 시계. 남은 시간이 고리로 줄어든다. 깨우는 중 → 깬 뒤 3분은 따로 ──

@Composable
private fun Nap(ui: StageUi) {
    // 남은 시간 글자가 바뀔 때(끝 시각에 맞춘 초 경계)에만 깨운다. 0.25초마다 깨우면 글은 그대로인데 화면만 네 번 다시 짠다
    val until = if (ui.napPhase == NapPhase.AFTER) ui.napAfterUntil else ui.napEndAt
    val now by produceState(System.currentTimeMillis(), until) {
        while (true) {
            value = System.currentTimeMillis()
            val r = Math.floorMod(until - value, 1000L)
            delay(if (r == 0L) 1000L else r)
        }
    }
    val total = ui.napMinutes * 60_000L
    val left = if (ui.napEndAt > 0) (ui.napEndAt - now).coerceAtLeast(0) else total
    when (ui.napPhase) {
        NapPhase.WAKE -> { NapWake(left); return }
        NapPhase.AFTER -> { NapAfter((ui.napAfterUntil - now).coerceAtLeast(0)); return }
        else -> Unit
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxWidth().padding(top = 176.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(540.dp), contentAlignment = Alignment.Center) {
                Ring(left.toFloat() / total, 540.dp, 10.dp, Color(0xFFC9965C).copy(alpha = .55f), Color.White.copy(alpha = .05f))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("깨울 때까지", style = t(28, W600, C.L3))
                    Text(mmss(left + 999), style = t(160, FontWeight.Light, C.L1.copy(alpha = .82f), tracking = -.04, line = 1.0).copy(fontFeatureSettings = tnum), modifier = Modifier.padding(top = 10.dp))
                    Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(PhosphorIcons.Regular.Alarm, null, tint = C.L3, modifier = Modifier.size(28.dp))
                        val end = if (ui.napEndAt > 0) ui.napEndAt else now + total
                        // 깨울 시각 글은 분이 바뀔 때만 만든다
                        Text(remember(end / 60_000) { SimpleDateFormat("a h시 m분", Locale.KOREA).format(Date(end)) }, style = t(26, color = C.L3))
                    }
                }
            }
            // 10·15·20분: 고른 칸이 미끄러져 옮겨 간다
            val mins = listOf(10, 15, 20)
            val x by animateDpAsState((mins.indexOf(ui.napMinutes).coerceAtLeast(0) * 184).dp, spring(dampingRatio = .82f, stiffness = 500f), label = "seg")
            Box(Modifier.padding(top = 48.dp).clip(sq(44)).background(Color(0xFF767680).copy(alpha = .24f)).padding(8.dp)) {
                Box(Modifier.offset(x = x).width(180.dp).height(80.dp).clip(Pill).background(Color(0xFF636366)))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    mins.forEach { m ->
                        Box(Modifier.width(180.dp).height(80.dp).press(Pill) { Stage.napStart(m) }, contentAlignment = Alignment.Center) {
                            Text("${m}분", style = t(30, W600, if (m == ui.napMinutes) C.L1 else C.L2))
                        }
                    }
                }
            }
            Box(
                Modifier.padding(top = 30.dp).height(88.dp).press(Pill) { Stage.napStop() }.clip(Pill).background(Color(0xFF767680).copy(alpha = .20f)).padding(horizontal = 48.dp),
                contentAlignment = Alignment.Center,
            ) { Text("그만 쉬기", style = t(30, W600, C.L2)) }
            Text("자는 동안 열선은 1단까지만 · 시동을 끄면 깨워 드릴 수 없어요 · 막힌 곳에서는 시동을 켠 채 쉬지 마세요", style = t(24, color = C.L2b), modifier = Modifier.padding(top = 22.dp))
        }
    }
}

/** 깨우는 중: 빛·바람·곡이 차례로 오고, 끝에 부른다. [일어났어요]·[5분만 더] */
@Composable
private fun NapWake(left: Long) {
    Box(Modifier.fillMaxSize().background(Color(0xFF14100C))) {
        Column(Modifier.fillMaxWidth().padding(top = 236.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (left > 0) "곧 깨워 드려요" else "일어날 시간이에요", style = t(96, W700, tracking = -.035))
            Text(if (left > 0) "빛이 밝아지고 바람이 불어요 · ${mmss(left + 999)}" else "천천히 눈을 뜨세요", style = t(36, W500, C.L2).copy(fontFeatureSettings = tnum), modifier = Modifier.padding(top = 24.dp))
            Row(Modifier.padding(top = 96.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Btn("일어났어요", primary = true, height = 112, size = 38, pad = 64) { Stage.napWoke() }
                Btn("5분만 더", primary = false, height = 112, size = 38, pad = 56) { Stage.napMore() }
            }
        }
    }
}

/** 깬 뒤 3분: 깬 직후가 가장 둔하다. 출발을 막지는 않는다 */
@Composable
private fun NapAfter(left: Long) {
    Box(Modifier.fillMaxSize().background(Color(0xFF14100C))) {
        Column(Modifier.fillMaxWidth().padding(top = 236.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("천천히 깨는 중", style = t(96, W700, tracking = -.035))
            Text(mmss(left + 999), style = t(120, FontWeight.Light, C.L1.copy(alpha = .82f), tracking = -.04, line = 1.0).copy(fontFeatureSettings = tnum), modifier = Modifier.padding(top = 28.dp))
            Text("깬 직후 몇 분은 몸이 가장 둔해요 · 기지개를 켜고 물 한 모금", style = t(32, W500, C.L2), modifier = Modifier.padding(top = 28.dp))
            Box(Modifier.padding(top = 64.dp)) { Btn("이제 갈게요", primary = false, height = 100, size = 34, pad = 56) { Stage.napStop() } }
        }
    }
}
