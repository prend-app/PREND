package app.prend.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.prend.R
import com.kyant.capsule.ContinuousCapsule
import com.kyant.capsule.ContinuousRoundedRectangle
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint

/**
 * PREND 화면의 바탕 값(시안 3-B). 에뮬레이터는 밀도 160이라 시안의 px가 그대로 dp·sp다.
 * 글자는 두 벌: 평소는 Pleos 글자 크기에 맞춘 친구 크기, E는 크고 굵게.
 */
object C {
    val L1 = Color(0xFFF5F5F7)
    val L2 = Color(0xFFEBEBF5).copy(alpha = .62f)
    /** 작은 정보 글(시각·출처·설명 한 줄). 어두운 유리 위에서 약 4.5:1이라 밤 차 안에서도 읽힌다 */
    val L2b = Color(0xFFEBEBF5).copy(alpha = .50f)
    /** 꺼진 것·꾸밈만(정보 글에 쓰면 어두운 유리 위에서 2.5:1로 묻힌다) */
    val L3 = Color(0xFFEBEBF5).copy(alpha = .34f)
    val L4 = Color(0xFFEBEBF5).copy(alpha = .18f)
    val Red = Color(0xFFFF4F45)
    val RedInk = Color(0xFFFF6A61)
    val Amber = Color(0xFFFFB340)
    val Mint = Color(0xFF63E6BE)
    /** 통풍(시원하게) */
    val Sky = Color(0xFF7CC4FF)
    val Lilac = Color(0xFFA5B4FC)
    val LilacInk = Color(0xFFC7D2FE)
    val Ink = Color(0xFF0B0B0E)
    val Base = Color(0xFF050507)
    val EBase = Color(0xFF08080A)
    val Hair = Color.White.copy(alpha = .09f)
    val Fill = Color(0xFF787880).copy(alpha = .32f)
}

val Pretendard = FontFamily(
    Font(R.font.pretendard_light, FontWeight.Light),
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_medium, FontWeight.Medium),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    Font(R.font.pretendard_bold, FontWeight.Bold),
    Font(R.font.pretendard_extrabold, FontWeight.ExtraBold),
)

/** 글자 하나의 모양. 큰 글자일수록 자간을 조금 더 좁힌다(시안의 -0.012em ~ -0.04em) */
fun t(size: Int, weight: FontWeight = FontWeight.Normal, color: Color = C.L1, tracking: Double = -0.012, line: Double? = null) =
    TextStyle(
        fontFamily = Pretendard, fontSize = size.sp, fontWeight = weight, color = color,
        letterSpacing = tracking.em, lineHeight = line?.let { (size * it).sp } ?: TextStyle.Default.lineHeight,
        lineBreak = BY_WORD,
    )

/** 한국어 줄바꿈은 어절 단위로('직영\n점'처럼 낱말 가운데서 끊지 않는다. 안드로이드 13부터, 한 어절이 줄보다 길면 그 안에서 끊는다) */
private val BY_WORD = LineBreak.Simple.copy(wordBreak = LineBreak.WordBreak.Phrase)

val W500 = FontWeight.Medium
val W600 = FontWeight.SemiBold
val W700 = FontWeight.Bold
val W800 = FontWeight.ExtraBold

fun sq(r: Int): Shape = ContinuousRoundedRectangle(r.dp)
val Pill: Shape = ContinuousCapsule

/** 유리가 비출 바탕. 화면은 바탕 층(비춰지는 것)과 앞 층(유리와 글)으로 나눠 그린다 */
class Glass(val haze: HazeState)

val LocalGlass = staticCompositionLocalOf<Glass?> { null }

/**
 * 판 모서리의 빛(11/09 스모크 유리): 왼쪽 위에서 가장 밝고 가운데서 사라졌다가 오른쪽 아래에 조금 다시 맺힌다.
 * light는 빛의 색(긴급은 빨강), strong은 빛이 센 판(긴급)
 */
fun rimOf(light: Color = C.L1, strong: Boolean = false): Brush = Brush.linearGradient(
    listOf(light.copy(alpha = if (strong) .55f else .30f), Color.White.copy(alpha = .05f), Color.White.copy(alpha = .03f), light.copy(alpha = .13f)),
    start = Offset.Zero, end = Offset.Infinite,
)

private val rim = rimOf()

/** 판 위쪽의 은은한 광택 */
fun Modifier.sheen(): Modifier = drawBehind {
    drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .06f), Color.Transparent), endY = size.height * .32f))
}

/**
 * 왼쪽 빛줄과 그 번짐(첫 화면 큰 단추, 달리는 동안의 [긴급]): 가운데가 밝고 위아래 끝이 사라지는 가는 빛과, 판 안으로 퍼지는 옅은 빛.
 * strong이면(긴급) 번짐을 조금 더 세게
 */
fun Modifier.lightBar(light: Color, strong: Boolean): Modifier = drawBehind {
    drawRect(Brush.radialGradient(listOf(light.copy(alpha = if (strong) .26f else .14f), Color.Transparent), center = Offset(0f, size.height * .55f), radius = size.width * .75f))
    val w = 6.dp.toPx()
    val top = size.height * .14f
    val h = size.height * .72f
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, light.copy(alpha = .30f), Color.Transparent), startY = top, endY = top + h), topLeft = Offset(0f, top), size = Size(w * 4, h))
    drawRoundRect(Brush.verticalGradient(listOf(Color.Transparent, light, light, Color.Transparent), startY = top, endY = top + h), topLeft = Offset(0f, top), size = Size(w, h), cornerRadius = CornerRadius(w / 2))
}

/**
 * 스모크 유리(Haze, 11/09 사용자가 고른 시안 5): 글을 읽는 판 모두(첫 화면 단추, 카드, 목록, E 안내).
 * 짙은 색 유리라 뒤의 색이 흐리게 비치면서도 글이 읽힌다(빛 유리는 예뻤지만 글이 묻혔다). 위쪽 광택과 모서리 빛(rimOf)을 함께 그린다.
 * edge로 모서리 빛의 색을 바꾼다(긴급은 rimOf(C.Red, true))
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
fun Modifier.frosted(shape: Shape, tint: Color = Color(0xFF0B0C10), alpha: Float = .64f, edge: Brush = rim): Modifier {
    val g = LocalGlass.current ?: return clip(shape).background(tint.copy(alpha = .9f)).sheen().border(1.5.dp, edge, shape)
    val style = HazeStyle(backgroundColor = C.Base, tint = HazeTint(tint.copy(alpha = alpha)), blurRadius = 48.dp, noiseFactor = .04f)
    // 뒤를 줄여 담아 흐린다(Haze 기본은 원래 크기 그대로). 흐림이 48dp라 눈으로는 같고, 유리 판을 다시 흐리는 프레임(장면 전환·카드 등장)의 GPU 일이 준다(11/04 시험 27: 에뮬레이터에서 한 프레임 GPU 44ms)
    return clip(shape).hazeEffect(g.haze, style) { inputScale = HazeInputScale.Auto }.sheen().border(1.5.dp, edge, shape)
}

/**
 * 위 막대의 작은 캡슐([프렌드]·[긴급]·[닫기]). 11/09부터 굴절 유리 대신 판과 같은 스모크 유리(글이 또렷하고 가볍다).
 * edge가 있으면 그 색의 빛이 모서리에 맺히고 유리도 붉게 짙어진다([긴급]). surface는 옛 굴절 유리의 값이라 쓰지 않는다
 */
@Composable
fun Modifier.liquid(shape: Shape, surface: Color = Color(0xFF0B0C10), edge: Color? = null): Modifier =
    frosted(shape, if (edge != null) Color(0xFF2A0C0F) else Color(0xFF0B0C10), if (edge != null) .74f else .64f, edge?.let { rimOf(it.copy(alpha = 1f), true) } ?: rim)
