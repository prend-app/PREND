package app.prend.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Microphone
import com.adamglin.phosphoricons.regular.Check
import com.adamglin.phosphoricons.regular.Info
import com.adamglin.phosphoricons.regular.WarningCircle
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** 꾹 누르는 시간. 짧으면 잘못 눌리고, 길면 달리는 중에 손이 오래 머문다 */
const val HOLD_MS = 1_200L

@Stable
class HoldState {
    var at by mutableStateOf<Offset?>(null)
    var id by mutableIntStateOf(0)
    var done by mutableStateOf(false)
}

/**
 * 화면 아무 데나 1.2초 꾹 누르면 [프렌드]를 부른다. 보지 않고 겨냥하지 않아도 된다.
 * 버튼·위젯은 누른 순간 자기 몫으로 가져가므로(down을 소비) 여기에는 빈 곳의 누름만 온다.
 * 손가락이 움직이거나 두 손가락이거나 1.2초 전에 떼면 취소.
 */
fun Modifier.holdToCall(hold: HoldState, onCall: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true)
        hold.done = false; hold.id++; hold.at = down.position
        // 누르는 도중 다른 화면이 덮어 손짓이 끊겨도 고리가 남지 않게
        try {
            val slop = viewConfiguration.touchSlop * 1.5f
            val held = withTimeoutOrNull(HOLD_MS) {
                while (true) {
                    val ev = awaitPointerEvent()
                    if (ev.changes.size > 1) break
                    val c = ev.changes.first()
                    if (!c.pressed || c.isConsumed || (c.position - down.position).getDistance() > slop) break
                }
                false
            } == null
            if (held) {
                hold.done = true
                onCall()
                // 손을 뗄 때까지의 움직임은 여기서 먹는다(아래의 다른 손짓으로 새지 않게)
                do {
                    val ev = awaitPointerEvent()
                    ev.changes.forEach { it.consume() }
                } while (ev.changes.any { it.pressed })
            }
        } finally {
            hold.at = null
        }
    }
}

/** 손가락 자리에서 차오르는 고리. 0.2초 뒤에 나타나 1.2초에 다 찬다(짧게 누를 때는 보이지 않는다) */
@Composable
fun BoxScope.HoldRing(hold: HoldState) {
    val at = hold.at ?: return
    val fill = remember(hold.id) { Animatable(0f) }
    val show = remember(hold.id) { Animatable(0f) }
    LaunchedEffect(hold.id) {
        delay(200)
        show.animateTo(1f, tween(160))
    }
    LaunchedEffect(hold.id) {
        delay(200)
        fill.animateTo(1f, tween((HOLD_MS - 200).toInt(), easing = LinearEasing))
    }
    val pop = remember(hold.id) { Animatable(0f) }
    LaunchedEffect(hold.done, hold.id) { if (hold.done) pop.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) }
    // 안전장치: 다 찰 시간이 지났는데 부르지도 떼지도 않은 고리는 끊긴 손짓이다
    LaunchedEffect(hold.id) {
        delay(HOLD_MS + 600)
        if (!hold.done) hold.at = null
    }
    val size = 200.dp
    val half = with(LocalDensity.current) { (size / 2).toPx() }
    // 흐려짐도 그릴 때 읽는다(차오르는 1초 동안 매 프레임 다시 짜지 않게)
    Canvas(
        Modifier.offset { IntOffset((at.x - half).roundToInt(), (at.y - half).roundToInt()) }.size(size)
            .graphicsLayer { val a = show.value * (1f - pop.value * .6f); alpha = a; clip = a < 1f },
    ) {
        val sw = 7.dp.toPx()
        val r = this.size.minDimension / 2 - sw - 18.dp.toPx() * pop.value
        val c = center
        // 손가락 둘레의 옅은 빛
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = .10f + .12f * pop.value), Color.Transparent), c, r + 40.dp.toPx()), r + 40.dp.toPx(), c)
        drawCircle(Color.White.copy(alpha = .16f), r, c, style = Stroke(sw))
        rotate(-90f, c) {
            drawArc(
                if (hold.done) C.Mint else C.L1, 0f, 360f * fill.value, false,
                Offset(c.x - r, c.y - r), androidx.compose.ui.geometry.Size(r * 2, r * 2), style = Stroke(sw, cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * 듣는 동안: 화면 가장자리에 옅은 민트 빛, 위에 '듣는 중'과 알아들은 말. 쉼터 카드, 시동 카드, 정체 카드는 제 화면에 듣는 표시가 있어 캡슐을 띄우지 않는다.
 * E 장면에서는 빛을 띄우지 않는다: E 첫 화면은 제 듣기 줄(EmergencyListen), 사고·아플 때 걸음은 "괜찮으세요?"를 듣는 동안 걸음 위의 물음 띠(OkBand,
 * 고리와 [괜찮아요]·[다쳤어요])가 그 몫을 한다(11/06). 띠가 없는 걸음에서 [프렌드]를 부르면 위 막대 가운데에 캡슐만 띄운다(11/08). level(목소리 크기)은 막대가 그릴 때만 읽는다
 */
@Composable
fun BoxScope.ListenLayer(ui: StageUi, level: () -> Float) {
    val l = ui.listen
    val on = l != null && !ui.scene.isE
    AnimatedVisibility(on, Modifier.matchParentSize(), enter = fadeIn(tween(260)), exit = fadeOut(tween(420))) {
        val tr = rememberInfiniteTransition(label = "edge")
        val breathe by tr.animateFloat(.55f, 1f, infiniteRepeatable(tween(1300), RepeatMode.Reverse), label = "breathe")
        // 숨쉬기는 층의 투명도로만 바꾼다(그림을 다시 기록하지 않게). 띠 네 개는 제 크기만 칠한다(전에는 띠마다 화면 전체를 칠해 듣는 몇 초 동안 GPU가 화면 몇 장 분량을 더 칠했다, 11/04 점검)
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = breathe }) {
            val w = 140.dp.toPx(); val a = .20f
            drawRect(Brush.verticalGradient(listOf(C.Mint.copy(alpha = a), Color.Transparent), 0f, w), size = androidx.compose.ui.geometry.Size(size.width, w))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, C.Mint.copy(alpha = a)), size.height - w, size.height), topLeft = Offset(0f, size.height - w), size = androidx.compose.ui.geometry.Size(size.width, w))
            drawRect(Brush.horizontalGradient(listOf(C.Mint.copy(alpha = a * .8f), Color.Transparent), 0f, w), size = androidx.compose.ui.geometry.Size(w, size.height))
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, C.Mint.copy(alpha = a * .8f)), size.width - w, size.width), topLeft = Offset(size.width - w, 0f), size = androidx.compose.ui.geometry.Size(w, size.height))
        }
    }
    // 화면이 제 카드·띠(듣는 중 표시 포함)로 물음을 그리면 캡슐을 따로 띄우지 않는다. 어느 화면이 그리는지는 Scenes.drawsListen 하나가 정한다
    // (따로 두었더니 위치 알림 카드가 빠져 '듣는 중'이 두 번 보였다, 검토 11/06). 그리지 않는 화면에서는 캡슐을 띄운다.
    // 사고·아플 때·응급처치 걸음도 물음 띠가 없으면 위 막대 가운데(Scenes.toastTop, SOS 띠·걸음 글을 가리지 않는 자리)에 띄운다:
    // 꾹 눌러 부른 [프렌드]가 차임만 울리고 듣는지·무엇을 알아들었는지 보이지 않았다(11/08 오류 점검 E8·B9). 가장자리 빛은 E에서 그대로 끈다
    val e = ui.scene.isE
    AnimatedVisibility(
        l != null && !drawsListen(ui), Modifier.align(Alignment.TopCenter).padding(top = toastTop(ui)),
        enter = fadeIn(tween(240)) + scaleIn(tween(320, easing = FastOutSlowInEasing), initialScale = .9f),
        exit = fadeOut(tween(300)) + scaleOut(tween(300), targetScale = .96f),
    ) {
        val cur = l ?: Listen()
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // E 걸음에서는 위 막대 가운데라 기어 글자·[닫기]에 닿지 않게 좁게
            Row(
                Modifier.frosted(Pill).height(96.dp).widthIn(max = if (e) 760.dp else 1200.dp).padding(start = 34.dp, end = 40.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(PhosphorIcons.Fill.Microphone, null, tint = C.Mint, modifier = Modifier.size(34.dp))
                when {
                    cur.heard.isNotBlank() -> Text("“${cur.heard}”", style = t(32, W600), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    cur.done -> Text("잘 못 들었어요", style = t(30, W600, C.L2))
                    else -> { Text("듣는 중", style = t(30, W600)); Bars(5, C.Mint, level = level) }
                }
            }
            // 무엇을 말해도 되는지: 지금 맞는 말 한두 개를 옅게(움직이지 않는다). 말소리가 잡히면 숨긴다
            if (cur.hints.isNotEmpty() && cur.heard.isBlank() && !cur.done) Text(
                cur.hints.joinToString("   ·   ") { "“$it”" }, style = t(26, W500, C.L2b), maxLines = 1,
                modifier = Modifier.padding(top = 18.dp),
            )
        }
    }
}

/** 차가 한 일을 짧게 알리는 캡슐. top은 위 여백(사고·아플 때·응급처치 걸음은 위 막대 가운데, Scenes.toastTop) */
@Composable
fun BoxScope.ToastLayer(text: String?, top: androidx.compose.ui.unit.Dp = 160.dp) {
    // 사라지는 동안 보일 마지막 글. 상태가 아닌 그릇에 둔다(합성 중에 상태를 쓰면 한 번 더 다시 짠다)
    val keep = remember { LastToast() }
    if (text != null) keep.text = text
    val last = text ?: keep.text
    AnimatedVisibility(
        text != null, Modifier.align(Alignment.TopCenter).padding(top = top),
        enter = fadeIn(tween(240)) + scaleIn(tween(320, easing = FastOutSlowInEasing), initialScale = .9f),
        exit = fadeOut(tween(360)),
    ) {
        Row(
            Modifier.frosted(Pill).height(96.dp).padding(start = 30.dp, end = 40.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val (icon, tint) = when (toastTone(last)) {
                ToastTone.FAIL -> PhosphorIcons.Regular.WarningCircle to C.Amber
                ToastTone.DONE -> PhosphorIcons.Regular.Check to C.Mint
                ToastTone.INFO -> PhosphorIcons.Regular.Info to C.L2
            }
            Icon(icon, null, tint = tint, modifier = Modifier.size(34.dp))
            Text(last, style = t(30, W600))
        }
    }
}

private class LastToast { var text = "" }

private enum class ToastTone { DONE, FAIL, INFO }

/**
 * 알림 앞 표시(11/08 실제 서버 시험: "서버가 바빠요"·"내비에 넣지 못했어요" 앞에도 초록 체크가 붙어 해낸 일처럼 보였다).
 * 못 한 일·위험은 주황 느낌표, 한 일("껐어요"·"넣었어요")과 할 일("둘게요")은 체크, 그 밖의 안내("눌러 주세요"·"꺼져 있어요")는 i
 */
private fun toastTone(text: String): ToastTone = when {
    Regex("못했|못 했|못 찾|바빠요|위험").containsMatchIn(text) -> ToastTone.FAIL
    afterJong(text, "어요", 20) || afterJong(text, "게요", 8) || Regex("안내해요|시작해요|드려요").containsMatchIn(text) -> ToastTone.DONE
    else -> ToastTone.INFO
}

/**
 * 받침이 jong인 글자 뒤에 tail이 오나: ㅆ(20) + "어요"는 한 일(껐어요·넣었어요, '있어요'는 빼고), ㄹ(8) + "게요"는 할 일(둘게요·드릴게요, "닿지 않게요"는 아님)
 */
private fun afterJong(text: String, tail: String, jong: Int) = text.indices.any { i ->
    val c = text[i]
    c in '가'..'힣' && (c - '가') % 28 == jong && c != '있' && text.startsWith(tail, i + 1)
}
