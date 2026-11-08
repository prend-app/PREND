package app.prend.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Warning
import com.adamglin.phosphoricons.regular.Microphone
import app.prend.Prend
import kotlin.random.Random

// ── 이름: 기어 표시처럼 'P R E N D'. 지금 순간의 글자들을 불빛 캡슐 하나로 켠다(왼쪽 계기판의 'P' 상자와 짝) ──

/** 기어 글자 한 칸의 폭과 칸 사이. 글자 자리는 늘 같고 캡슐만 움직인다(칸 사이를 두어 캡슐 끝이 옆 글자에 붙지 않게) */
private const val GEAR_W = 42
private const val GEAR_GAP = 8
/** 캡슐이 켠 글자 바깥으로 나오는 여백 */
private const val GEAR_PAD = 10

/** 기어가 바뀌면 캡슐이 0.35초에 미끄러진다(PR ↔ ND, E로 가면 빨개진다). 튕기지 않고 빠르게 붙는다 */
private val GearEase = CubicBezierEasing(.2f, .9f, .25f, 1f)

/** presence면 캡슐 오른쪽에 작은 민트 점: PREND가 2초 뒤 말을 건넨다(말하기 전 기척). 삼키거나 말이 끝나면 꺼진다 */
@Composable
fun Gear(lit: String, presence: Boolean = false, onSecret: () -> Unit) {
    // 시연 패널: 이름을 3초 안에 다섯 번 누르면 열린다(꾹 누르기는 프렌드 몫)
    var taps by remember { mutableIntStateOf(0) }
    var first by remember { mutableLongStateOf(0L) }
    val e = lit == "E"
    val letters = GEAR_LETTERS
    val on = letters.indices.filter { letters[it] in lit }
    val from = on.firstOrNull() ?: 0
    val to = on.lastOrNull() ?: 0
    val slide = tween<Dp>(350, easing = GearEase)
    // 캡슐의 왼쪽과 폭. 그릴 때만 읽는다(미끄러지는 동안 이름을 다시 짜지 않고 캡슐만 다시 그린다)
    val x by animateDpAsState((from * (GEAR_W + GEAR_GAP)).dp, slide, label = "gearX")
    val w by animateDpAsState(((to - from) * (GEAR_W + GEAR_GAP) + GEAR_W + GEAR_PAD * 2).dp, slide, label = "gearW")
    val fill by animateColorAsState(if (e) C.Red.copy(alpha = .18f) else Color.White.copy(alpha = .14f), tween(350), label = "gearFill")
    val edge by animateColorAsState(if (e) C.Red.copy(alpha = .28f) else Color.White.copy(alpha = .10f), tween(350), label = "gearEdge")
    // 기척 점은 알파만 바뀌고 그릴 때만 읽는다(자리를 차지하지 않아 막대의 글이 움직이지 않는다)
    val dot by animateFloatAsState(if (presence) 1f else 0f, tween(if (presence) 300 else 600), label = "presence")
    Row(
        Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
            val now = System.currentTimeMillis()
            if (now - first > 3000) { first = now; taps = 0 }
            if (++taps >= 5) { taps = 0; onSecret() }
        }
            .drawBehind {
                val r = CornerRadius(22.dp.toPx())
                val tl = Offset(x.toPx(), 0f)
                val sz = Size(w.toPx(), size.height)
                drawRoundRect(fill, tl, sz, r)
                drawRoundRect(edge, Offset(tl.x + .5.dp.toPx(), .5.dp.toPx()), Size(sz.width - 1.dp.toPx(), sz.height - 1.dp.toPx()), r, style = Stroke(1.dp.toPx()))
            }
            // 캡슐 오른쪽 빈자리(구분선까지 26) 가운데에 점과 옅은 번짐
            .drawWithContent {
                drawContent()
                if (dot > 0f) {
                    val c = Offset(size.width + 13.dp.toPx(), size.height / 2)
                    drawCircle(C.Mint.copy(alpha = .22f * dot), 12.dp.toPx(), c)
                    drawCircle(C.Mint.copy(alpha = dot), 5.dp.toPx(), c)
                }
            }
            .padding(horizontal = GEAR_PAD.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GEAR_GAP.dp),
    ) {
        letters.forEachIndexed { i, ch -> GearLetter(ch, on = i in on, red = ch == 'E') }
    }
}

private val GEAR_LETTERS = listOf('P', 'R', 'E', 'N', 'D')

@Composable
private fun GearLetter(ch: Char, on: Boolean, red: Boolean) {
    // 캡슐이 지나가며 켜고 끈다(색만 0.35초에 바뀐다)
    val color by animateColorAsState(
        when {
            on && red -> C.Red
            on -> C.L1
            red -> C.Red.copy(alpha = .30f)
            else -> C.L1.copy(alpha = .25f)
        },
        tween(350), label = "gearInk",
    )
    Box(Modifier.width(GEAR_W.dp), contentAlignment = Alignment.Center) {
        Text(ch.toString(), style = t(38, if (on) W800 else W600, color, tracking = 0.0))
    }
}

enum class TopRight { NORMAL, CLOSE, NONE }

/**
 * 위 막대: 이름 | 지금 순간 ……… (시연 표) [프렌드] [긴급].
 * 장면이 바뀌어도 막대는 그대로 있고(깜빡이지 않게) 기어 캡슐이 미끄러지며, 지금 순간 글과 오른쪽 캡슐만 0.2초에 바뀐다.
 * tag는 디버그·PT 빌드에서 켜 둔 시험 값("시연 · 에어백"): 작은 회색 표로 밝힌다(에뮬레이터에 없는 값을 실제처럼 보이지 않게, 별첨 C 10)
 */
@Composable
fun TopBar(lit: String, moment: String, right: TopRight, onFriend: () -> Unit, onSos: () -> Unit, onClose: () -> Unit, onSecret: () -> Unit, presence: Boolean = false, tag: String? = null) {
    Row(
        Modifier.padding(start = 64.dp, end = 56.dp, top = 40.dp).fillMaxWidth().height(100.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        Gear(lit, presence, onSecret)
        Box(Modifier.width(1.dp).height(30.dp).background(C.L4))
        Crossfade(moment, animationSpec = tween(200), label = "moment") { Text(it, style = t(24, W500, C.L2)) }
        Spacer(Modifier.weight(1f))
        if (tag != null) Box(
            Modifier.height(44.dp).clip(Pill).background(Color.White.copy(alpha = .06f)).border(1.dp, C.L4, Pill).padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) { Text(tag, style = t(20, W600, C.L2b), maxLines = 1) }
        AnimatedContent(
            right,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) using SizeTransform(clip = false) },
            contentAlignment = Alignment.CenterEnd, label = "right",
        ) { r ->
            // [프렌드]와 [긴급] 사이를 넓힌다(26 → 48dp: 사람 시험 11/04에서 셋이 잘못 눌렀다, 별첨 C 6)
            Row(horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
                when (r) {
                    TopRight.NORMAL -> {
                        Capsule(onFriend, Modifier.liquid(Pill)) {
                            Icon(PhosphorIcons.Regular.Microphone, null, tint = C.L1, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(Prend.CALL, style = t(25, W600))
                        }
                        Capsule(onSos, Modifier.liquid(Pill, surface = Color(0xFFDC2626).copy(alpha = .20f), edge = C.Red.copy(alpha = .38f)), padStart = 28) {
                            // 앱 안에서는 '긴급'(11/02 사용자: Emergency는 앱 이름 PR·E·ND를 소개할 때만)
                            Text("긴급", style = t(25, W700, C.RedInk, tracking = -0.005))
                        }
                    }
                    TopRight.CLOSE -> Capsule(onClose, Modifier.liquid(Pill), padStart = 34, padEnd = 34) { Text("닫기", style = t(25, W600)) }
                    TopRight.NONE -> Unit
                }
            }
        }
    }
}

/** 누르면 살짝 눌리는 캡슐(높이 80) */
@Composable
fun Capsule(onClick: () -> Unit, glass: Modifier, padStart: Int = 24, padEnd: Int = 30, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.press(Pill, onClick = onClick).then(glass).height(80.dp).padding(start = padStart.dp, end = padEnd.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * 누름 칸의 눌림 반응 하나: 4% 작아지고 흰 빛이 옅게 덮인다. 버튼·캡슐·칩·카드가 모두 이것만 쓴다(기본 검은 덮개와 섞이지 않게).
 * 작아짐과 덮개는 그릴 때만 읽는다. shape는 덮개 모양(그 칸의 모서리와 같게)
 */
@Composable
fun Modifier.press(shape: Shape, enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val k by animateFloatAsState(if (pressed) .96f else 1f, spring(dampingRatio = .6f, stiffness = 800f), label = "press")
    val lit by animateFloatAsState(if (pressed) .08f else 0f, tween(if (pressed) 60 else 180), label = "pressLit")
    return graphicsLayer { scaleX = k; scaleY = k }
        .clickable(src, indication = null, enabled = enabled, onClick = onClick)
        .drawWithContent {
            drawContent()
            if (lit > 0f) drawOutline(shape.createOutline(size, layoutDirection, this), Color.White.copy(alpha = lit))
        }
}

/**
 * 차가 먼저 말 거는 카드가 나타날 때 테두리가 카드 색으로 한 번 빛난다(차임과 같은 때. 0.9초에 걸쳐 옅어지고 옅은 색 테두리로 남는다).
 * 빛은 그릴 때만 읽는다(카드를 다시 짜지 않는다)
 */
@Composable
fun Modifier.glowOnce(tint: Color, shape: Shape, still: Boolean = false): Modifier {
    // still(달리는 동안)이면 빛나지 않고 처음부터 옅은 테두리만(눈길을 끄는 움직임을 두지 않는다)
    val a = remember { Animatable(if (still) .12f else .6f) }
    LaunchedEffect(Unit) { if (!still) a.animateTo(.12f, tween(900, easing = LinearOutSlowInEasing)) }
    // 카드는 모서리로 잘려 있어 선의 안쪽 절반(1.5)만 보인다.
    // 빛은 제 층(graphicsLayer)에서 그린다: 바깥 유리 층에서 그리면 빛이 옅어지는 0.9초 동안 매 프레임 카드 흐림을 처음부터 다시 계산했다(11/04 점검)
    return graphicsLayer().drawWithContent {
        drawContent()
        drawOutline(shape.createOutline(size, layoutDirection, this), tint.copy(alpha = a.value), style = Stroke(3.dp.toPx()))
    }
}

// ── 버튼: 흰 바탕(할 일)과 옅은 회색(괜찮아요·이전), 빨간 바탕(119 전화. 한 화면에 하나만) ──

@Composable
fun Btn(text: String, primary: Boolean, height: Int = 96, size: Int = 32, pad: Int = 46, icon: ImageVector? = null, iconAfter: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    val shape = Pill
    Row(
        Modifier.press(shape, onClick = onClick).height(height.dp).clip(shape)
            .background(when { danger -> C.Red; primary -> C.L1; else -> C.Fill })
            .padding(horizontal = pad.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val ink = when { danger -> Color.White; primary -> C.Ink; else -> C.L1 }
        if (icon != null && !iconAfter) Icon(icon, null, tint = ink, modifier = Modifier.size((size + 2).dp))
        Text(text, style = t(size, W700, ink, tracking = -0.015))
        if (icon != null && iconAfter) Icon(icon, null, tint = ink, modifier = Modifier.size((size + 2).dp))
    }
}

// ── 듣는 표시: 숨 쉬듯 오르내리는 막대. level이 있으면 목소리 크기를 따른다 ──

private val BAR_BASE = intArrayOf(12, 22, 30, 18, 26, 14, 20)

/** 막대 n개가 차지하는 폭(막대 5 + 사이 5) */
fun barsWidth(n: Int): Dp = (n * 10 - 5).dp

/**
 * moving이 아니면 멈춘 막대로 둔다(듣지 않을 때 듣는 것처럼 보이지 않게).
 * 물결과 목소리 크기는 그릴 때만 읽고 막대는 제 층에 그린다: 움직여도 화면을 다시 짜지 않고 이 막대만 다시 그린다
 */
@Composable
fun Bars(n: Int = 5, color: Color = C.L2, level: (() -> Float)? = null, height: Int = 30, moving: Boolean = true) {
    val phase = if (moving) rememberInfiniteTransition(label = "bars").animateFloat(0f, 6.2832f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "phase") else null
    Canvas(Modifier.size(barsWidth(n), height.dp).graphicsLayer()) {
        val p = phase?.value ?: 0f
        val amp = level?.let { .55f + .45f * it() } ?: 1f
        val w = 5.dp.toPx()
        repeat(n) { i ->
            val wave = .55f + .45f * kotlin.math.sin(p + i * 1.1f)
            val h = (BAR_BASE[i % BAR_BASE.size] * (.6f + .4f * wave) * amp).coerceIn(5f, height.toFloat()).dp.toPx()
            drawRoundRect(color, Offset(i * 10.dp.toPx(), (size.height - h) / 2), Size(w, h), CornerRadius(w / 2))
        }
    }
}

/** 둥근 진행 고리 */
@Composable
fun Ring(frac: Float, size: Dp, stroke: Dp, color: Color, track: Color = Color.White.copy(alpha = .12f)) {
    Canvas(Modifier.size(size)) { ring(frac, stroke, color, track) }
}

/** 줄어드는 동안 매 프레임 바뀌는 고리: 값을 그릴 때만 읽고 제 층에 그린다(화면을 다시 짜지 않고 이 고리만 다시 그린다) */
@Composable
fun Ring(frac: () -> Float, size: Dp, stroke: Dp, color: Color, track: Color = Color.White.copy(alpha = .12f)) {
    Canvas(Modifier.size(size).graphicsLayer()) { ring(frac(), stroke, color, track) }
}

private fun DrawScope.ring(frac: Float, stroke: Dp, color: Color, track: Color) {
    val sw = stroke.toPx()
    val d = size.minDimension - sw
    val tl = Offset(sw / 2, sw / 2)
    drawArc(track, 0f, 360f, false, tl, Size(d, d), style = Stroke(sw))
    rotate(-90f) { drawArc(color, 0f, 360f * frac, false, tl, Size(d, d), style = Stroke(sw, cap = StrokeCap.Round)) }
}

// ── 곡 표지(PREND 사운드): 밤 도로의 빛줄기 ──

@Composable
fun Cover(s: Dp, shadow: Boolean = true) {
    val shape = sq((s.value * .12f).toInt())
    Box(
        Modifier.size(s)
            .then(if (shadow) Modifier.dropShadow(shape, Shadow(radius = 70.dp, color = Color.Black.copy(alpha = .45f), offset = DpOffset(0.dp, 34.dp))) else Modifier)
            .clip(shape)
            .background(Brush.verticalGradient(0f to Color(0xFF1B2A4A), .38f to Color(0xFF2B2D52), .70f to Color(0xFF7A3E4E), 1f to Color(0xFFE08A4C))),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            listOf(Triple(.58f, 2f, .55f), Triple(.64f, 3f, .45f), Triple(.71f, 4f, .35f)).forEachIndexed { i, (y, sw, a) ->
                rotate(-3f * i, Offset(w / 2, h * y)) {
                    drawLine(Color(0xFFFFD6B0).copy(alpha = a), Offset(-w * .1f, h * y), Offset(w * 1.1f, h * y), strokeWidth = sw * w / 440f)
                }
            }
        }
        // 해: 가장자리까지 번지게(흐림이 원 밖으로 나가도록 Unbounded)
        Box(Modifier.padding(start = s * .58f, top = s * .22f).size(s * .18f).blur(s * .02f, BlurredEdgeTreatment.Unbounded).background(Color(0xFFFFE3B8).copy(alpha = .9f), CircleShape))
        if (s >= 200.dp) Text(
            "${Prend.NAME} SOUND", style = t((s.value * .05f).toInt(), W700, Color.White.copy(alpha = .85f), tracking = .18),
            modifier = Modifier.align(Alignment.BottomStart).padding(start = s * .08f, bottom = s * .07f),
        )
    }
}

// ── 바탕: 곡 표지의 색을 넓게 번지게 + 아주 옅은 결 + 아래쪽 어둠 ──

/** 색 덩어리 하나: 시안의 (색, 왼쪽, 위, 지름). 시안처럼 140px 흐림을 반지름에 더한다 */
data class Blob(val color: Long, val x: Int, val y: Int, val d: Int)

val NIGHT = listOf(Blob(0xFF2B3F7A, -120, -160, 900), Blob(0xFF7A3E5A, 820, -120, 820), Blob(0xFFC9703F, 1000, 640, 760), Blob(0xFF1A2448, 80, 600, 900))
val NIGHT_DRIVE = listOf(Blob(0xFF22305E, -120, -160, 900), Blob(0xFF5A2F48, 820, -120, 820), Blob(0xFF8A5232, 1000, 640, 760), Blob(0xFF141B36, 80, 600, 900))

@Composable
fun Mesh(blobs: List<Blob>) {
    // 색 번짐의 붓(그라디언트)은 크기가 같으면 다시 만들지 않는다
    Spacer(Modifier.fillMaxSize().drawWithCache {
        val k = density
        val spots = blobs.map { b ->
            val r = b.d / 2f * k
            val c = Offset((b.x - 200) * k + r, (b.y - 200) * k + r)
            val col = Color(b.color)
            Triple(Brush.radialGradient(0f to col, .5f to col.copy(alpha = .9f), 1f to col.copy(alpha = 0f), center = c, radius = r + 140 * k), r + 140 * k, c)
        }
        val shade = Brush.verticalGradient(0f to Color.Black.copy(alpha = .18f), .3f to Color.Transparent, 1f to Color.Black.copy(alpha = .42f))
        onDrawBehind {
            drawRect(C.Base)
            spots.forEach { (brush, r, c) -> drawCircle(brush, r, c) }
            drawRect(shade)
        }
    })
    Grain()
}

private val noise: ImageBitmap by lazy {
    val n = 240
    val rnd = Random(7)
    val px = IntArray(n * n) { val v = rnd.nextInt(256); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
    android.graphics.Bitmap.createBitmap(px, n, n, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** 결: 큰 색면이 디지털처럼 매끈해 보이지 않게 6%만 */
@Composable
fun Grain(alpha: Float = .05f) {
    val brush = remember { ShaderBrush(ImageShader(noise, TileMode.Repeated, TileMode.Repeated)) }
    Canvas(Modifier.fillMaxSize()) { drawRect(brush, alpha = alpha, blendMode = androidx.compose.ui.graphics.BlendMode.Overlay) }
}

/** E 장면의 바탕: 위에서 아주 옅은 붉은 빛 */
@Composable
fun EBack(glow: Boolean) {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(C.EBase)
        if (glow) drawRect(Brush.radialGradient(0f to C.Red.copy(alpha = .10f), 1f to Color.Transparent, center = Offset(size.width / 2, -size.height * .1f), radius = size.width * .7f))
    }
    Grain()
}

/** 빨간 점 + 한 줄(E 화면 아래) */
@Composable
fun RedLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(C.Red))
        Text(text, style = t(30, W600))
    }
}

/** SOS 띠: 빨간 바탕에 흰 글씨. 사고 안내가 "SOS 버튼을 누르거나 119에 알리세요"를 말한 뒤 E 걸음 맨 위에(목소리를 놓쳐도 보이게) */
@Composable
fun SosBand(modifier: Modifier = Modifier) {
    Row(
        modifier.clip(sq(24)).background(C.Red).padding(horizontal = 30.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(PhosphorIcons.Fill.Warning, null, tint = Color.White, modifier = Modifier.size(40.dp))
        Text(SOS_LINE, style = t(38, W800, Color.White, tracking = -.02))
    }
}

/** 목소리 e_sos와 같은 문장(화면·배너가 함께 쓴다) */
const val SOS_LINE = "SOS 버튼을 누르거나 119에 알리세요"

/** 아이콘 상자(카드 머리의 둥근 네모) */
@Composable
fun IconTile(icon: ImageVector, tint: Color, bg: Color, box: Int, radius: Int, iconSize: Int) {
    Box(Modifier.size(box.dp).clip(sq(radius)).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(iconSize.dp))
    }
}
