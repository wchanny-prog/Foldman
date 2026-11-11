package dev.foldman.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.Dp
import dev.foldman.deck.Skin

/** Everything that changes with the skin: finishes, key material, lettering and the window surround. */
class SkinLook(
    val body: Color,
    /** Lettering printed on the body. */
    val onBody: Color,
    /** Brushed metal, or smooth moulded plastic. */
    val brushed: Boolean,
    /** The control strip: bare brushed aluminium, or moulded in [strip]. */
    val metalStrip: Boolean,
    val strip: Color,
    /** Rubber keys instead of milled metal ones. */
    val rubberKeys: Boolean,
    val markBrush: Brush,
    val markShadow: Shadow,
    /** The window surround, top to bottom. */
    val windowRim: List<Color>,
    /** A coloured gasket line inside the surround. */
    val windowRing: Color?,
    /** Corner radius of the window, in cassette millimetres. */
    val windowCorner: Float,
    /** A second line of lettering under the model name. */
    val tag: String?,
    val tagColor: Color,
)

private val Gunmetal = SkinLook(
    body = Color(0xFF43464B),
    onBody = Color(0xFFD9DCE0),
    brushed = true,
    metalStrip = true,
    strip = Color(0xFFD9DBDE),
    rubberKeys = false,
    markBrush = Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFE3E6EA), Color(0xFFAAB0B8), Color(0xFFECEEF1))),
    markShadow = Shadow(Color.Black.copy(alpha = 0.5f), Offset(0f, 2.5f), 3f),
    windowRim = listOf(Color(0xFFF4F5F7), Color(0xFF8C9096), Color(0xFFDADCDF)),
    windowRing = null,
    windowCorner = 3.2f,
    tag = null,
    tagColor = Color.Unspecified,
)

/** A splash-proof sports player: bright moulded plastic, rubber keys, a deep rounded window with a gasket. */
private val Sport = SkinLook(
    body = Color(0xFFF2C51B),
    onBody = Color(0xFF3A3833),
    brushed = false,
    metalStrip = false,
    strip = Color(0xFFE9BB12),
    rubberKeys = true,
    markBrush = SolidColor(Color(0xFF3A3833)),
    markShadow = Shadow(Color.White.copy(alpha = 0.4f), Offset(0f, 1.5f), 0f),
    windowRim = listOf(Color(0xFF34363A), Color(0xFF1A1B1D), Color(0xFF2A2C2F)),
    windowRing = Color(0xFF2B6FD6),
    windowCorner = 8f,
    tag = "SPORT",
    tagColor = Color(0xFF2B6FD6),
)

val Skin.look: SkinLook
    get() = when (this) {
        Skin.GUNMETAL -> Gunmetal
        Skin.SPORT -> Sport
    }

val LocalSkin = staticCompositionLocalOf { Skin.GUNMETAL }

/** The finish of the control strip for this skin. */
fun Modifier.stripFinish(look: SkinLook, corner: Dp): Modifier =
    if (look.metalStrip) chassis(corner = corner, screws = false)
    else paintedBody(look.strip, corner = corner, framed = corner.value > 0f, brushed = false)
