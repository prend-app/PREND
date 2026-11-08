package app.prend.ui

import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
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
class Glass(val haze: HazeState, val backdrop: LayerBackdrop)

val LocalGlass = staticCompositionLocalOf<Glass?> { null }

/** 머리카락 테두리. 위가 조금 더 밝아 빛이 맺힌 듯하게 */
private val rim = Brush.verticalGradient(listOf(Color.White.copy(alpha = .15f), Color.White.copy(alpha = .06f)))

/**
 * 젖빛 유리(Haze): 글을 읽는 판(위젯, 차의 한마디 카드, E 안내).
 * 시안의 재질(rgba(30,30,34,.52) + 흐림 48 + 채도 1.8)에 맞춘다. Haze에는 채도 올리기가 없어 덮는 색을 조금 옅게 해 곡 색이 비치게 한다.
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
fun Modifier.frosted(shape: Shape, tint: Color = Color(0xFF1E1E22), alpha: Float = .42f): Modifier {
    val g = LocalGlass.current ?: return clip(shape).border(1.dp, rim, shape)
    val style = HazeStyle(backgroundColor = C.Base, tint = HazeTint(tint.copy(alpha = alpha)), blurRadius = 48.dp, noiseFactor = .04f)
    // 뒤를 줄여 담아 흐린다(Haze 기본은 원래 크기 그대로). 흐림이 48dp라 눈으로는 같고, 유리 판을 다시 흐리는 프레임(장면 전환·카드 등장)의 GPU 일이 준다(11/04 시험 27: 에뮬레이터에서 한 프레임 GPU 44ms)
    return clip(shape).hazeEffect(g.haze, style) { inputScale = HazeInputScale.Auto }.border(1.dp, rim, shape)
}

/** 굴절 유리(backdrop lens): 작은 캡슐(프렌드, Emergency, 닫기). 가장자리에서 뒤가 휘고 테두리에 빛이 선다 */
@Composable
fun Modifier.liquid(shape: Shape, surface: Color = Color(0xFF1C1C1E).copy(alpha = .38f), edge: Color? = null): Modifier {
    val g = LocalGlass.current ?: return clip(shape)
    val m = drawBackdrop(
        g.backdrop,
        shape = { shape },
        effects = { vibrancy(); blur(6.dp.toPx()); lens(14.dp.toPx(), 28.dp.toPx()) },
        onDrawSurface = { drawRect(surface) },
    )
    return if (edge != null) m.border(1.dp, edge, shape) else m
}
