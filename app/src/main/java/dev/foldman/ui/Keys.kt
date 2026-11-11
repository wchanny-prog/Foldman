package dev.foldman.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.foldman.deck.DeckUi
import dev.foldman.deck.Transport

enum class KeySymbol { STOP, REW, PLAY, FF, PAUSE, PREV, NEXT }

/**
 * The transport keys. Latched keys stay down, exactly as the mechanism would hold them. [vertical]
 * stacks them down the side strip, as on the folded phone.
 */
@Composable
fun TransportKeys(
    ui: DeckUi,
    onStop: () -> Unit,
    onRewind: () -> Unit,
    onPlay: () -> Unit,
    onFastForward: () -> Unit,
    onPause: () -> Unit,
    modifier: Modifier = Modifier,
    keyHeight: Dp = 86.dp,
    vertical: Boolean = false,
) {
    val t = ui.transport
    // The keys sit in a dark well cut into the metal, with their captions printed inside it.
    val cc = Color(0xFFA6A9AF)
    val keys: @Composable (Modifier, Modifier) -> Unit = { k, play ->
        DeckKey(KeySymbol.REW, "REW", latched = t == Transport.REWINDING, onPress = onRewind, modifier = k, height = keyHeight, captionColor = cc)
        DeckKey(
            KeySymbol.PLAY, "PLAY", latched = t == Transport.PLAYING || t == Transport.PAUSED, onPress = onPlay,
            modifier = play, height = keyHeight, captionColor = cc, accent = true,
        )
        DeckKey(KeySymbol.FF, "FF", latched = t == Transport.FAST_FORWARDING, onPress = onFastForward, modifier = k, height = keyHeight, captionColor = cc)
        DeckKey(KeySymbol.PAUSE, "PAUSE", latched = t == Transport.PAUSED, onPress = onPause, modifier = k, height = keyHeight, captionColor = cc, mark = Color(0xFF2F7D49))
        DeckKey(KeySymbol.STOP, "STOP·EJECT", latched = false, onPress = onStop, modifier = k, height = keyHeight, captionColor = cc, mark = Color(0xFFB92F26))
    }
    if (vertical) {
        Column(
            modifier.recess(10.dp).padding(horizontal = 6.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) { keys(Modifier.fillMaxWidth(), Modifier.fillMaxWidth()) }
    } else {
        Row(
            modifier.recess(16.dp).padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) { keys(Modifier.weight(1f), Modifier.weight(1.35f)) }
    }
}

/**
 * One key: a metal cap whose front edge disappears into the slot as it goes down. It acts on
 * touch-down, as a mechanical key does, and clicks and buzzes on the way down and up.
 */
@Composable
fun DeckKey(
    symbol: KeySymbol,
    caption: String,
    latched: Boolean,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 86.dp,
    accent: Boolean = false,
    mark: Color? = null,
    captionColor: Color = Color(0xFF9EA1A7),
) {
    var pressed by remember { mutableStateOf(false) }
    val view = LocalView.current
    val sounds = LocalMechSounds.current
    val haptics = LocalHaptics.current
    // The gesture loop outlives recompositions; it calls whatever the latest action is.
    val action by rememberUpdatedState(onPress)
    val rubber = LocalSkin.current.look.rubberKeys
    val depth by animateFloatAsState(
        when {
            pressed -> 1f
            latched -> 0.72f
            else -> 0f
        },
        spring(dampingRatio = 0.5f, stiffness = 2200f), label = "key",
    )
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            caption, style = EngravedCaps.copy(color = captionColor, fontSize = 8.sp, letterSpacing = 1.sp),
            textAlign = TextAlign.Center, maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .semantics {
                    role = Role.Button
                    contentDescription = caption
                    stateDescription = if (latched) "Down" else "Up"
                }
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        pressed = true
                        if (haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_PRESS)
                        sounds?.keyDown()
                        action()
                        tryAwaitRelease()
                        pressed = false
                        if (haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_RELEASE)
                        sounds?.keyUp()
                    })
                },
        ) { drawKey(symbol, depth, accent, latched, mark, rubber) }
    }
}

private fun DrawScope.drawKey(symbol: KeySymbol, depth: Float, accent: Boolean, latched: Boolean, mark: Color?, rubber: Boolean) {
    val w = size.width
    val h = size.height
    val travel = h * 0.14f
    val frontFull = h * 0.2f
    val front = frontFull * (1f - depth * 0.85f)
    val top = travel * depth
    val faceH = h - travel - frontFull
    // Machined, nearly square corners: these are milled metal keys, not soft plastic ones.
    val r = CornerRadius(minOf(w, h) * 0.07f)
    val shade = 0.14f * depth
    fun metal(c: Long) = lerp(if (rubber) lerp(Color(0xFF16171A), Color(0xFF3F4145), (Color(c).red - 0.55f).coerceIn(0f, 1f)) else Color(c), Color.Black, shade)
    val lit = if (rubber) 0.16f else 1f
    // Slot shadow under the key.
    drawRoundRect(Color.Black.copy(alpha = 0.8f), topLeft = Offset(-1.5f, top + 2f), size = Size(w + 3f, faceH + front + 3f), cornerRadius = r)
    // Front edge, seen as the key stands proud of the slot.
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFF7C7F85), Color(0xFF3C3F43)), top + faceH - r.y, top + faceH + front),
        topLeft = Offset(0f, top + faceH - r.y), size = Size(w, front + r.y), cornerRadius = r,
    )
    drawRoundRect(
        Brush.verticalGradient(listOf(metal(0xFFF7F8F9), metal(0xFFD3D5D9), metal(0xFFB3B6BB)), top, top + faceH),
        topLeft = Offset(0f, top), size = Size(w, faceH), cornerRadius = r,
    )
    // Brushing along the cap.
    val lines = (faceH / 3.2f).toInt().coerceAtLeast(2)
    for (i in 1 until lines) {
        val y = top + faceH * i / lines
        drawLine(if (i % 2 == 0) Color.White.copy(alpha = 0.16f * lit) else Color.Black.copy(alpha = 0.05f), Offset(w * 0.03f, y), Offset(w * 0.97f, y), 0.8f)
    }
    // The finger dish: a shallow hollow milled into the cap, dark at its upper lip and bright at its lower one.
    val inset = minOf(w, faceH) * 0.13f
    val dishTop = top + inset * 0.75f
    val dish = Size(w - inset * 2f, faceH - inset * 1.5f)
    val dr = CornerRadius(r.x * 0.75f)
    drawRoundRect(
        Brush.verticalGradient(listOf(metal(0xFFA4A7AC), metal(0xFFCBCDD1), metal(0xFFECEDEF)), dishTop, dishTop + dish.height),
        topLeft = Offset(inset, dishTop), size = dish, cornerRadius = dr,
    )
    drawRoundRect(Color.White.copy(alpha = 0.75f * lit), topLeft = Offset(inset, dishTop + 1.4f), size = dish, cornerRadius = dr, style = Stroke(1.1f))
    drawRoundRect(Color.Black.copy(alpha = 0.3f), topLeft = Offset(inset, dishTop), size = dish, cornerRadius = dr, style = Stroke(1.1f))
    drawRoundRect(Color.White.copy(alpha = 0.85f * lit), topLeft = Offset(0.8f, top + 0.8f), size = Size(w - 1.6f, faceH - 1.6f), cornerRadius = r, style = Stroke(1.2f))
    // The symbol is engraved and filled with paint: a bright lower lip, then the paint.
    val c = Offset(w / 2f, dishTop + dish.height * 0.5f)
    val s = minOf(w, dish.height) * 0.2f
    val ink = when {
        mark != null -> mark
        accent -> if (latched) DeckColors.Accent else DeckColors.AccentDeep
        else -> if (rubber) Color(0xFFD5D7DA) else DeckColors.Engrave
    }
    drawSymbol(symbol, c + Offset(0f, 1.4f), s, Color.White.copy(alpha = 0.85f * lit))
    drawSymbol(symbol, c, s, ink)
}

fun DrawScope.drawSymbol(symbol: KeySymbol, c: Offset, s: Float, ink: Color) {
    fun tri(cx: Float, dir: Float) = Path().apply {
        moveTo(cx - s * 0.8f * dir, c.y - s)
        lineTo(cx + s * 0.8f * dir, c.y)
        lineTo(cx - s * 0.8f * dir, c.y + s)
        close()
    }
    when (symbol) {
        KeySymbol.STOP -> drawRect(ink, Offset(c.x - s * 0.85f, c.y - s * 0.85f), Size(s * 1.7f, s * 1.7f))
        KeySymbol.PLAY -> drawPath(tri(c.x + s * 0.1f, 1f), ink)
        KeySymbol.FF -> {
            drawPath(tri(c.x - s * 0.7f, 1f), ink); drawPath(tri(c.x + s * 0.7f, 1f), ink)
        }
        KeySymbol.REW -> {
            drawPath(tri(c.x - s * 0.7f, -1f), ink); drawPath(tri(c.x + s * 0.7f, -1f), ink)
        }
        KeySymbol.PAUSE -> {
            drawRect(ink, Offset(c.x - s * 0.8f, c.y - s), Size(s * 0.55f, s * 2f))
            drawRect(ink, Offset(c.x + s * 0.25f, c.y - s), Size(s * 0.55f, s * 2f))
        }
        KeySymbol.PREV -> {
            drawRect(ink, Offset(c.x - s * 1.25f, c.y - s), Size(s * 0.3f, s * 2f))
            drawPath(tri(c.x - s * 0.15f, -1f), ink); drawPath(tri(c.x + s * 1.0f, -1f), ink)
        }
        KeySymbol.NEXT -> {
            drawPath(tri(c.x - s * 1.0f, 1f), ink); drawPath(tri(c.x + s * 0.15f, 1f), ink)
            drawRect(ink, Offset(c.x + s * 0.95f, c.y - s), Size(s * 0.3f, s * 2f))
        }
    }
}

/**
 * A key for the side strip: a ribbed metal cap with no caption of its own. What it does is
 * engraved on the strip beside it. Sized entirely by [modifier].
 */
@Composable
fun StripKey(description: String, latched: Boolean, onPress: () -> Unit, modifier: Modifier = Modifier) {
    var pressed by remember { mutableStateOf(false) }
    val view = LocalView.current
    val sounds = LocalMechSounds.current
    val haptics = LocalHaptics.current
    val action by rememberUpdatedState(onPress)
    val rubber = LocalSkin.current.look.rubberKeys
    val depth by animateFloatAsState(
        when {
            pressed -> 1f
            latched -> 0.72f
            else -> 0f
        },
        spring(dampingRatio = 0.5f, stiffness = 2200f), label = "stripkey",
    )
    Canvas(
        modifier
            .semantics {
                role = Role.Button
                contentDescription = description
                stateDescription = if (latched) "Down" else "Up"
            }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed = true
                    if (haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_PRESS)
                    sounds?.keyDown()
                    action()
                    tryAwaitRelease()
                    pressed = false
                    if (haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_RELEASE)
                    sounds?.keyUp()
                })
            },
    ) { drawRibbedKey(depth, rubber) }
}

private fun DrawScope.drawRibbedKey(depth: Float, rubber: Boolean) {
    val w = size.width
    val h = size.height
    val travel = minOf(h * 0.12f, 5.dp.toPx())
    val frontFull = minOf(h * 0.18f, 7.dp.toPx())
    val front = frontFull * (1f - depth * 0.85f)
    val top = travel * depth
    val faceH = h - travel - frontFull
    val r = CornerRadius(minOf(w, h) * 0.08f)
    val shade = 0.14f * depth
    // Rubber is the same key in a dark, matte material.
    fun metal(c: Long) = lerp(if (rubber) lerp(Color(0xFF16171A), Color(0xFF3F4145), (Color(c).red - 0.55f).coerceIn(0f, 1f)) else Color(c), Color.Black, shade)
    val lit = if (rubber) 0.16f else 1f
    // The slot the key rides in.
    drawRoundRect(Color.Black.copy(alpha = 0.55f), topLeft = Offset(-2f, -1f), size = Size(w + 4f, h + 2f), cornerRadius = r)
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFF7C7F85), Color(0xFF3C3F43)), top + faceH - r.y, top + faceH + front),
        topLeft = Offset(0f, top + faceH - r.y), size = Size(w, front + r.y), cornerRadius = r,
    )
    drawRoundRect(
        Brush.verticalGradient(listOf(metal(0xFFF4F5F7), metal(0xFFD0D2D6), metal(0xFFB6B9BE)), top, top + faceH),
        topLeft = Offset(0f, top), size = Size(w, faceH), cornerRadius = r,
    )
    // Fine ribs milled across the cap, for grip.
    val mx = w * 0.1f
    val my = minOf(faceH * 0.12f, 5.dp.toPx())
    var y = top + my
    val pitch = 2.4.dp.toPx()
    while (y < top + faceH - my) {
        drawLine(Color.Black.copy(alpha = if (rubber) 0.5f else 0.26f), Offset(mx, y), Offset(w - mx, y), 1.1f)
        drawLine(Color.White.copy(alpha = 0.7f * lit), Offset(mx, y + 1.3f), Offset(w - mx, y + 1.3f), 1f)
        y += pitch
    }
    drawRoundRect(Color.White.copy(alpha = 0.85f * lit), topLeft = Offset(0.8f, top + 0.8f), size = Size(w - 1.6f, faceH - 1.6f), cornerRadius = r, style = Stroke(1.2f))
}

/** A function mark engraved on the strip beside its key. */
@Composable
fun KeyMark(symbol: KeySymbol, modifier: Modifier = Modifier, color: Color = DeckColors.Engrave) {
    Canvas(modifier) { drawSymbol(symbol, Offset(size.width / 2f, size.height / 2f), minOf(size.width, size.height) * 0.42f, color) }
}

/** A small round push button (AMS skip, counter reset, tape shelf). */
@Composable
fun MiniButton(
    caption: String,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
    symbol: KeySymbol? = null,
    diameter: Dp = 34.dp,
    enabled: Boolean = true,
    captionColor: Color = DeckColors.Engrave,
) {
    var pressed by remember { mutableStateOf(false) }
    val view = LocalView.current
    val sounds = LocalMechSounds.current
    val haptics = LocalHaptics.current
    val action by rememberUpdatedState(onPress)
    val depth by animateFloatAsState(if (pressed) 1f else 0f, spring(stiffness = 3000f), label = "mini")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(diameter)
                .semantics { role = Role.Button; contentDescription = caption }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(onPress = {
                        pressed = true
                        if (haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        sounds?.keyUp()
                        action()
                        tryAwaitRelease()
                        pressed = false
                    })
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val c = center
                val r = size.minDimension / 2f
                drawCircle(Color.Black.copy(alpha = 0.6f), r, c)
                drawCircle(Color.White.copy(alpha = 0.5f), r, c + Offset(0f, 1.2f), style = Stroke(1f))
                val cap = r * (0.84f - depth * 0.05f)
                drawCircle(
                    Brush.radialGradient(
                        listOf(lerp(Color(0xFFF2F3F5), Color.Black, depth * 0.15f), Color(0xFF9B9EA3)),
                        c - Offset(cap * 0.35f, cap * 0.45f), cap * 1.7f,
                    ),
                    cap, c + Offset(0f, depth * 1.5f),
                )
                symbol?.let { drawSymbol(it, c + Offset(0f, depth * 1.5f), cap * 0.3f, if (enabled) DeckColors.Engrave else DeckColors.EngraveLight) }
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(caption, style = EngravedCaps.copy(fontSize = 7.sp, letterSpacing = 1.sp, color = captionColor), maxLines = 1)
    }
}
