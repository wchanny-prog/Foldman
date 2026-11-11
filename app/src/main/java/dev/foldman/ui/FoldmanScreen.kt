package dev.foldman.ui

import dev.foldman.deck.LabelPalette
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Job
import dev.foldman.deck.TapeCase
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.Animatable
import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowLayoutInfo
import dev.foldman.deck.DeckEvent
import dev.foldman.deck.DeckUi
import dev.foldman.deck.DeckViewModel
import dev.foldman.deck.Source
import dev.foldman.deck.Transport
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private enum class Posture { Cover, Unfolded, Tabletop }

/** Body finish, from the skin. */
private val Enamel: Color
    @Composable get() = LocalSkin.current.look.body

/**
 * Picks a layout from the hinge:
 *  - folded (cover screen): the portable player stood upright, tape on end behind its window,
 *    keys and volume down a silver side strip;
 *  - unfolded: the same player laid down as a deck, with the library above and now-playing below;
 *  - half-open on a table (Flex mode): the deck on the upper half, the library on the lower.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldmanScreen(
    vm: DeckViewModel,
    windowLayout: WindowLayoutInfo?,
    onPickFolder: () -> Unit,
    onInstallApp: (Source) -> Unit,
    onAppleSignIn: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val anim = rememberDeckAnimations(ui.transport, ui.progress)
    val sounds = remember { MechSounds() }
    DisposableEffect(sounds) { onDispose { sounds.release() } }
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var shelfOpen by rememberSaveable { mutableStateOf(false) }
    val feel by rememberUpdatedState(ui)

    // Choosing a tape from the rack: the lid swings open, the old tape lifts out, the new one
    // travels over from its slot and drops in, and the lid shuts on it as it starts to play.
    var loading by remember { mutableStateOf(false) }
    var loadJob by remember { mutableStateOf<Job?>(null) }
    var flight by remember { mutableStateOf<TapeFlight?>(null) }
    val flightT = remember { Animatable(0f) }
    val loadCase: (TapeCase, Rect?) -> Unit = remember(vm, anim) {
        { case, from ->
            // From the pull-up shelf on the cover screen the player is hidden until the shelf closes,
            // so only a tape picked from the rack beside the player is shown making the trip.
            val fly = from != null && !shelfOpen
            shelfOpen = false
            loadJob?.cancel()
            loadJob = scope.launch {
                loading = true
                var played = false
                try {
                    withTimeoutOrNull(4000) {
                        anim.door.animateTo(1f, tween(320))
                        anim.insert.animateTo(1f, tween(240))
                        if (fly) {
                            flightT.snapTo(0f)
                            flight = TapeFlight(case, from!!, vm.caseCover(case))
                            flightT.animateTo(1f, tween(620))
                            flight = null
                        }
                        played = true
                        vm.playCase(case)
                        anim.insert.animateTo(0f, spring(dampingRatio = 0.62f, stiffness = 220f))
                        if (feel.keySounds) sounds.autoStop()
                        anim.door.animateTo(0f, tween(300))
                    }
                    // Animations switched off or stalled: the tape still goes in and plays.
                    if (!played) vm.playCase(case)
                } finally {
                    withContext(NonCancellable) {
                        flight = null
                        anim.insert.snapTo(0f)
                        anim.door.snapTo(0f)
                        loading = false
                    }
                }
            }
        }
    }

    // A new tape drops into the deck whenever the source changes (and when the app opens).
    LaunchedEffect(ui.source) {
        // A tape being loaded from the rack runs its own, longer sequence.
        if (loading) return@LaunchedEffect
        try {
            anim.insert.snapTo(1f)
            // The drop takes well under a second. If the animation can't run (animations switched
            // off, or frames stalled) the tape is seated anyway, never left out of the window.
            withTimeoutOrNull(1500) { anim.insert.animateTo(0f, spring(dampingRatio = 0.62f, stiffness = 220f)) }
        } finally {
            withContext(NonCancellable) { anim.insert.snapTo(0f) }
        }
        if (feel.keySounds) sounds.autoStop()
    }
    LaunchedEffect(vm) {
        vm.events.collect { e ->
            if (feel.keySounds) sounds.autoStop()
            when (e) {
                DeckEvent.TapeEnd -> if (feel.haptics) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                DeckEvent.Eject -> {
                    if (feel.haptics) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    scope.launch {
                        anim.pop.animateTo(1f, tween(90))
                        anim.pop.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = 500f))
                    }
                    shelfOpen = true
                }
            }
        }
    }

    val appleActions = remember(vm) {
        AppleActions(
            signIn = onAppleSignIn,
            signOut = vm::signOutOfApple,
            setDeveloperToken = vm::setAppleDeveloperToken,
            refresh = vm::loadApplePlaylists,
            openPlaylist = vm::openApplePlaylist,
            closePlaylist = vm::closeApplePlaylist,
            play = vm::playApplePlaylist,
        )
    }
    val caseActions = remember(vm) {
        CaseActions(play = loadCase, remove = vm::removeCase, cover = { vm.caseCover(it) })
    }
    val shelfActions = remember(vm) {
        ShelfActions(
            selectSource = vm::selectSource,
            pickFolder = onPickFolder,
            playTrack = vm::playTrack,
            openAccess = vm::openAccessSettings,
            openApp = vm::openSourceApp,
            installApp = onInstallApp,
            apple = appleActions,
            cases = caseActions,
            cycleSkin = vm::cycleSkin,
        )
    }
    val deck = remember(vm) { DeckControls(vm) }
    val fold = windowLayout?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull()

    CompositionLocalProvider(
        LocalMechSounds provides sounds.takeIf { ui.keySounds },
        LocalHaptics provides ui.haptics,
        LocalSkin provides ui.skin,
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Brush.radialGradient(listOf(Color(0xFF2A2420), DeckColors.Backdrop), radius = 1800f)),
        ) {
            val density = LocalDensity.current
            val sheetHeight = maxHeight * 0.8f
            // Held sideways the inner screen is short, so the library gets a bigger share.
            val landscape = maxWidth > maxHeight
            val coverScreen = fold == null && minOf(maxWidth, maxHeight) < 600.dp
            val posture = when {
                fold != null && fold.state == FoldingFeature.State.HALF_OPENED && fold.orientation == FoldingFeature.Orientation.HORIZONTAL -> Posture.Tabletop
                fold != null && fold.orientation == FoldingFeature.Orientation.VERTICAL -> Posture.Unfolded
                // The cover screen is the upright player however the phone is tilted.
                coverScreen -> Posture.Cover
                maxWidth >= 600.dp && maxWidth > maxHeight * 0.7f -> Posture.Unfolded
                else -> Posture.Cover
            }
            // Held to portrait on the cover screen, so tilting it doesn't turn the player on its side.
            val activity = LocalContext.current as? Activity
            LaunchedEffect(coverScreen, activity) {
                activity?.requestedOrientation =
                    if (coverScreen) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            LaunchedEffect(posture) { view.showSystemBars(posture == Posture.Tabletop) }
            when (posture) {
                // The cover screen is the player itself: edge to edge, system bars hidden.
                Posture.Cover -> CoverWalkman(ui, anim, deck, onTapes = { shelfOpen = true }, modifier = Modifier.fillMaxSize())
                // Opened flat: the rack of tapes on the left page, the same upright player as the
                // cover screen on the right, split at the hinge.
                Posture.Unfolded -> Row(Modifier.fillMaxSize()) {
                    TapeShelf(
                        ui, shelfActions,
                        Modifier.weight(1f).fillMaxHeight().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top + WindowInsetsSides.Start)),
                        rack = true,
                    )
                    CoverWalkman(ui, anim, deck, onTapes = null, modifier = Modifier.weight(1f).fillMaxHeight())
                }
                Posture.Tabletop -> {
                    val (topH, gap) = with(density) {
                        fold!!.bounds.top.toDp() to (fold.bounds.bottom - fold.bounds.top).toDp()
                    }
                    Column(Modifier.fillMaxSize()) {
                        DeckBand(
                            ui, anim, deck, vm,
                            Modifier
                                .height(topH)
                                .fillMaxWidth()
                                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
                        )
                        Spacer(Modifier.height(gap))
                        LibraryBand(
                            ui, shelfActions, vm::playQueueItem,
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
                        )
                    }
                }
            }
            if (shelfOpen && posture == Posture.Cover) {
                ModalBottomSheet(
                    onDismissRequest = { shelfOpen = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = Color(0xFF1C1510),
                ) {
                    TapeShelf(
                        ui,
                        ShelfActions(
                            selectSource = { vm.selectSource(it) },
                            pickFolder = onPickFolder,
                            playTrack = { vm.playTrack(it); shelfOpen = false },
                            openAccess = vm::openAccessSettings,
                            openApp = vm::openSourceApp,
                            installApp = onInstallApp,
                            apple = appleActions,
                            // Loading a tape closes the shelf, so the player is back in view as it starts.
                            cases = CaseActions(
                                play = loadCase,
                                remove = vm::removeCase,
                                cover = { vm.caseCover(it) },
                            ),
                            cycleSkin = vm::cycleSkin,
                        ),
                        Modifier.fillMaxWidth().height(sheetHeight),
                    )
                }
            }
            flight?.let { f -> FlyingTape(f, flightT.value, anim.windowBounds, ui, anim.reels) }
        }
    }
}

/** Hides the status and navigation bars (a swipe from the edge brings them back briefly), or restores them. */
private fun android.view.View.showSystemBars(show: Boolean) {
    var ctx = context
    while (ctx is android.content.ContextWrapper && ctx !is android.app.Activity) ctx = ctx.baseContext
    val window = (ctx as? android.app.Activity)?.window ?: return
    WindowCompat.getInsetsController(window, this).run {
        if (show) {
            show(WindowInsetsCompat.Type.systemBars())
        } else {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/** Key actions, bundled so the layouts don't each need the view model. */
private class DeckControls(vm: DeckViewModel) {
    val play = vm::play
    val pause = vm::pauseKey
    val stop = vm::stop
    val rewind = vm::rewind
    val fastForward = vm::fastForward
    val next = vm::next
    val previous = vm::previous
    val volume = vm::setVolume
}

private val OnEnamel: TextStyle
    @Composable get() = EngravedCaps.copy(color = LocalSkin.current.look.onBody)

/** The folded phone: an upright portable player. */
@Composable
private fun CoverWalkman(ui: DeckUi, anim: DeckAnimations, deck: DeckControls, onTapes: (() -> Unit)?, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val capH = maxHeight * 0.085f
        val look = LocalSkin.current.look
        Row(Modifier.fillMaxSize().paintedBody(Enamel, capHeight = capH, corner = 0.dp, framed = false, brushed = look.brushed)) {
            Column(
                Modifier
                    .weight(0.7f)
                    .fillMaxHeight()
                    .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top + WindowInsetsSides.Start))
                    .padding(start = 14.dp, end = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Inset from the corner so the display's rounded corner doesn't clip it.
                Row(Modifier.fillMaxWidth().height(capH).padding(start = 8.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Folded, this opens the tape shelf. Opened flat the shelf is already beside the player.
                    if (onTapes != null) {
                        HotlineButton(onTapes, Modifier.size(44.dp, 22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("TAPES", style = OnEnamel.copy(fontSize = 8.sp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                BrandMark(30.sp)
                Spacer(Modifier.height(10.dp))
                // The window takes everything the lettering leaves: this is a cassette player first.
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TapeWindow(ui, anim, vertical = true)
                }
                Spacer(Modifier.height(10.dp))
                ModelMark(38.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    look.tag?.let { "$it · STEREO CASSETTE PLAYER" } ?: "STEREO CASSETTE PLAYER",
                    style = OnEnamel.copy(fontSize = 7.sp, letterSpacing = 3.sp, color = if (look.tag != null) look.tagColor else OnEnamel.color),
                )
                Spacer(Modifier.height(18.dp))
            }
            SideStrip(ui, deck, Modifier.weight(0.3f).fillMaxHeight())
        }
    }
}

/**
 * The silver strip down the side of the case, laid out the way such players arranged them: the
 * mic at the top, a long stop bar beside a column of ribbed keys with their marks engraved
 * between, then the twin volume sliders and the jack at the foot.
 */
@Composable
private fun SideStrip(ui: DeckUi, deck: DeckControls, modifier: Modifier) {
    val t = ui.transport
    // Runs the full height of the screen; the padding keeps controls clear of the rounded corners.
    Column(
        modifier
            .stripFinish(LocalSkin.current.look, 0.dp)
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top + WindowInsetsSides.End))
            .padding(start = 9.dp, end = 11.dp, top = 26.dp, bottom = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                MicGrille(Modifier.size(36.dp, 30.dp))
                Text("MIC", style = EngravedCaps.copy(fontSize = 6.sp))
            }
            Spacer(Modifier.weight(1f))
            StatusLed(ui.transport)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().weight(0.9f)) {
            // The long bar: stop, and eject when the tape is already stopped.
            Column(Modifier.weight(0.3f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                StripKey("Stop, eject", latched = false, onPress = deck.stop, modifier = Modifier.fillMaxWidth().weight(1f))
                Spacer(Modifier.height(5.dp))
                Text("STOP\nEJECT", style = EngravedCaps.copy(fontSize = 6.sp, letterSpacing = 0.5.sp, lineHeight = 8.sp), textAlign = TextAlign.Center)
            }
            // Marks for the keys beside them.
            Column(Modifier.weight(0.2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                KeyMark(KeySymbol.FF, Modifier.fillMaxWidth().weight(1f))
                KeyMark(KeySymbol.REW, Modifier.fillMaxWidth().weight(1f))
                KeyMark(KeySymbol.PAUSE, Modifier.fillMaxWidth().weight(1f), color = Color(0xFF2F7D49))
                KeyMark(KeySymbol.PLAY, Modifier.fillMaxWidth().weight(2.2f), color = DeckColors.AccentDeep)
            }
            Column(Modifier.weight(0.5f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                StripKey("Fast forward", latched = t == Transport.FAST_FORWARDING, onPress = deck.fastForward, modifier = Modifier.fillMaxWidth().weight(1f))
                StripKey("Rewind", latched = t == Transport.REWINDING, onPress = deck.rewind, modifier = Modifier.fillMaxWidth().weight(1f))
                StripKey("Pause", latched = t == Transport.PAUSED, onPress = deck.pause, modifier = Modifier.fillMaxWidth().weight(1f))
                StripKey("Play", latched = t == Transport.PLAYING || t == Transport.PAUSED, onPress = deck.play, modifier = Modifier.fillMaxWidth().weight(2.2f))
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            MiniButton("AMS", deck.previous, symbol = KeySymbol.PREV, diameter = 26.dp)
            MiniButton("AMS", deck.next, symbol = KeySymbol.NEXT, diameter = 26.dp, enabled = ui.hasNext)
        }
        Spacer(Modifier.height(10.dp))
        VolumeSlider(ui.volume, deck.volume, Modifier.fillMaxWidth().weight(1f))
        Text("VOLUME", style = EngravedCaps.copy(fontSize = 7.sp))
        Spacer(Modifier.height(10.dp))
        PhonesJack(Modifier.size(PhonesJackSize))
        Text("PHONES", style = EngravedCaps.copy(fontSize = 6.sp))
    }
}

/** The unfolded phone: the player laid down as a deck. */
@Composable
private fun DeckBand(ui: DeckUi, anim: DeckAnimations, deck: DeckControls, vm: DeckViewModel, modifier: Modifier) {
    val look = LocalSkin.current.look
    Row(modifier.paintedBody(Enamel, corner = 22.dp, brushed = look.brushed)) {
        Column(Modifier.weight(0.74f).fillMaxHeight().padding(horizontal = 20.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                BrandMark(22.sp)
                Text(
                    "WB-F8 · STEREO CASSETTE PLAYER", style = OnEnamel.copy(fontSize = 8.sp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                )
                ModelMark(26.sp)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(150.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
                    Row(Modifier.fillMaxWidth().weight(1f).bezel().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        VuMeter(ui.transport, ui.source, Modifier.weight(1f).fillMaxHeight(), channel = 0)
                        VuMeter(ui.transport, ui.source, Modifier.weight(1f).fillMaxHeight(), channel = 1)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.bezel().padding(5.dp)) { TapeCounter(anim.reels, digitWidth = 16.dp) }
                        Spacer(Modifier.width(8.dp))
                        MiniButton("RESET", { anim.reels.resetCounter() }, diameter = 20.dp, captionColor = OnEnamel.color)
                        Spacer(Modifier.width(6.dp))
                        StatusLed(ui.transport, captionColor = OnEnamel.color)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    TapeWindow(ui, anim)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                MiniButton("AMS", deck.previous, symbol = KeySymbol.PREV, diameter = 30.dp, captionColor = OnEnamel.color)
                Spacer(Modifier.width(10.dp))
                TransportKeys(ui, deck.stop, deck.rewind, deck.play, deck.fastForward, deck.pause, Modifier.weight(1f), keyHeight = 58.dp)
                Spacer(Modifier.width(10.dp))
                MiniButton("AMS", deck.next, symbol = KeySymbol.NEXT, diameter = 30.dp, enabled = ui.hasNext, captionColor = OnEnamel.color)
            }
        }
        // Silver end strip: mic, switches, volume and phones.
        Row(
            Modifier
                .weight(0.26f)
                .fillMaxHeight()
                .padding(6.dp)
                .stripFinish(look, 18.dp)
                .padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                MicGrille(Modifier.fillMaxWidth().height(30.dp))
                Text("MIC", style = EngravedCaps.copy(fontSize = 7.sp))
                Spacer(Modifier.weight(1f))
                SlideSwitch(ui.keySounds, vm::setKeySounds, "CLICK", height = 54.dp)
                Spacer(Modifier.height(8.dp))
                SlideSwitch(ui.haptics, vm::setHaptics, "HAPTIC", height = 54.dp)
                Spacer(Modifier.height(8.dp))
                MiniButton("SKIN", vm::cycleSkin, diameter = 20.dp)
                Spacer(Modifier.weight(1f))
                PhonesJack(Modifier.size(PhonesJackSize))
                Text("PHONES", style = EngravedCaps.copy(fontSize = 6.sp))
            }
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("VOLUME", style = EngravedCaps.copy(fontSize = 7.sp))
                VolumeSlider(ui.volume, deck.volume, Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

/** A tape on its way from the rack to the player. */
private class TapeFlight(val case: TapeCase, val from: Rect, val cover: ImageBitmap?)

/**
 * The tape in mid-air. It is the real cassette, wearing the case's cover and colours: it slips
 * out of its slot in the rack, grows to full size and turns on end as it crosses, and arrives
 * above the open lid ready to drop in.
 */
@Composable
private fun FlyingTape(flight: TapeFlight, t: Float, to: Rect?, ui: DeckUi, reels: ReelMotion) {
    if (to == null) return
    val measurer = rememberTextMeasurer()
    val case = flight.case
    // The same drawing as the tape in the window, labelled for the case it came from.
    val tape = remember(case.id, flight.cover) {
        ui.copy(
            source = case.source,
            artwork = flight.cover,
            label = LabelPalette(LabelPalette.Default.paper, Color(case.stripe), Color(case.stripe2), LabelPalette.Default.ink),
        )
    }
    val shell = DeckColors.shell(case.source)
    Canvas(Modifier.fillMaxSize()) {
        val from = flight.from
        val ease = t * t * (3f - 2f * t)
        // It leaves the slot no taller than the spine and ends the size of the tape in the window,
        // whose glass shows 84 of the cassette's 100 mm.
        val startLong = from.height * 0.95f * (100f / 64f)
        val endLong = to.height * (100f / 84f)
        val long = startLong + (endLong - startLong) * ease
        val short = long * 0.64f
        val lift = kotlin.math.sin(t * Math.PI).toFloat() * 40.dp.toPx()
        val cx = from.center.x + (to.center.x - from.center.x) * ease
        val cy = from.center.y + (to.center.y - to.height * 0.5f - from.center.y) * ease - lift
        // Fades in as it clears the slot, and out as the drop into the bay takes over.
        val alpha = (t / 0.12f).coerceAtMost(1f) * ((1f - t) / 0.14f).coerceIn(0f, 1f)
        drawContext.canvas.saveLayer(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height), Paint().apply { this.alpha = alpha })
        // Stood on end the same way round as the tape in the player.
        rotate(-90f * ease, Offset(cx, cy)) {
            translate(cx - long / 2f, cy - short / 2f) {
                drawLooseCassette(tape, reels, shell, measurer, long)
            }
        }
        drawContext.canvas.restore()
    }
}
