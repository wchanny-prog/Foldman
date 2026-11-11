package dev.foldman.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.foldman.deck.TapeCase

private val CaseHand = TextStyle(fontFamily = LabelFont, color = DeckColors.Ink, fontSize = 10.sp)
private val CaseTyped = TextStyle(fontFamily = TypeFont, color = DeckColors.Ink, fontSize = 8.sp)
private val ShelfCaps = EngravedCaps.copy(color = Color(0xFFB59B80), fontSize = 9.sp, letterSpacing = 2.sp)

/** A Compact Cassette case is 70 mm wide and 109 mm tall when it stands on the shelf. */
private const val CASE_ASPECT = 70f / 109f

class CaseActions(
    /** Loads the tape into the deck. The rectangle is where its case sits on screen, when known. */
    val play: (TapeCase, Rect?) -> Unit,
    val remove: (TapeCase) -> Unit,
    val cover: suspend (TapeCase) -> ImageBitmap?,
)

/**
 * The shelf of cassette cases: one for every album that has been through the deck. Stood facing
 * out so you see the J-cards, or stacked spine-on like a tape rack. Tap a case to load it; press
 * and hold to take it off the shelf.
 */
@Composable
fun CaseShelf(
    cases: List<TapeCase>,
    inDeckId: String?,
    actions: CaseActions,
    modifier: Modifier = Modifier,
    caseWidth: Dp = 84.dp,
    showHeader: Boolean = true,
    /** Open on the spines, as a rack of tapes, and draw them this tall. */
    startOnSpines: Boolean = false,
    spineHeight: Dp = 34.dp,
) {
    var spines by rememberSaveable { mutableStateOf(startOnSpines) }
    var armed by remember { mutableStateOf<String?>(null) }
    Column(modifier) {
        if (showHeader) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (cases.isEmpty()) "TAPE CASES" else "TAPE CASES · ${cases.size}",
                    style = ShelfCaps, modifier = Modifier.weight(1f), maxLines = 1,
                )
                ViewToggle("FRONTS", selected = !spines) { spines = false }
                Spacer(Modifier.width(4.dp))
                ViewToggle("SPINES", selected = spines) { spines = true }
            }
            Spacer(Modifier.height(8.dp))
        }
        when {
            cases.isEmpty() -> EmptyShelf()
            // The rack: a wooden holder with a slot for each tape, spine out.
            spines -> LazyColumn(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Brush.horizontalGradient(listOf(Color(0xFF3A2616), Color(0xFF5A3D24), Color(0xFF3A2616))))
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp, end = 12.dp),
            ) {
                items(cases, key = { it.id }) { case ->
                    CaseSlot(case, armed == case.id, { armed = it }, actions) {
                        CaseSpine(case, inDeck = case.id == inDeckId, height = spineHeight)
                    }
                }
            }
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                if (maxHeight < 230.dp) {
                    // A short panel holds one shelf: the cases stand full height and scroll sideways.
                    Column(Modifier.fillMaxSize()) {
                        LazyRow(
                            Modifier.weight(1f).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 8.dp),
                        ) {
                            items(cases, key = { it.id }) { case ->
                                CaseSlot(case, armed == case.id, { armed = it }, actions) {
                                    CaseFront(
                                        case, inDeck = case.id == inDeckId, cover = actions.cover,
                                        modifier = Modifier.fillMaxHeight().aspectRatio(CASE_ASPECT, matchHeightConstraintsFirst = true),
                                    )
                                }
                            }
                        }
                        ShelfPlank()
                    }
                } else {
                    LazyVerticalGrid(columns = GridCells.Adaptive(caseWidth + 10.dp)) {
                        items(cases, key = { it.id }) { case ->
                            Column {
                                Box(Modifier.padding(start = 5.dp, end = 5.dp, top = 8.dp)) {
                                    CaseSlot(case, armed == case.id, { armed = it }, actions) {
                                        CaseFront(case, inDeck = case.id == inDeckId, cover = actions.cover)
                                    }
                                }
                                ShelfPlank()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewToggle(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = ShelfCaps.copy(fontSize = 8.sp, color = if (selected) Color(0xFF1C1510) else Color(0xFFB59B80)),
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(if (selected) DeckColors.Accent else Color.White.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    )
}

@Composable
private fun EmptyShelf() {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "Your shelf is empty. Play an album and its tape case lands here, ready to load again.",
            style = CaseTyped.copy(color = Color(0xFFB59B80), fontSize = 11.sp, lineHeight = 16.sp),
        )
        Spacer(Modifier.height(10.dp))
        ShelfPlank()
    }
}

/** The lip of the wooden shelf the cases stand on. */
@Composable
private fun ShelfPlank() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(Brush.verticalGradient(listOf(Color(0xFF8A6238), Color(0xFF5E4026), Color(0xFF3A2616)))),
    )
}

/** Gestures shared by both views: tap loads the tape, press-and-hold offers to remove the case. */
@Composable
private fun CaseSlot(
    case: TapeCase,
    armed: Boolean,
    setArmed: (String?) -> Unit,
    actions: CaseActions,
    content: @Composable () -> Unit,
) {
    var bounds by remember { mutableStateOf<Rect?>(null) }
    Box(
        Modifier.onGloballyPositioned { bounds = it.boundsInRoot() }.pointerInput(case.id) {
            detectTapGestures(
                onTap = { actions.play(case, bounds) },
                onLongPress = { setArmed(case.id) },
            )
        },
    ) {
        content()
        if (armed) {
            Row(
                Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.Black.copy(alpha = 0.78f)),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "REMOVE",
                    style = ShelfCaps.copy(color = DeckColors.Led, fontSize = 9.sp, letterSpacing = 1.sp),
                    modifier = Modifier.clickable { setArmed(null); actions.remove(case) }.padding(6.dp),
                )
                Text(
                    "KEEP",
                    style = ShelfCaps.copy(color = Color.White, fontSize = 9.sp, letterSpacing = 1.sp),
                    modifier = Modifier.clickable { setArmed(null) }.padding(6.dp),
                )
            }
        }
    }
}

/** The case facing out: clear plastic over the J-card, with the hinge down the left edge. */
@Composable
private fun CaseFront(
    case: TapeCase,
    inDeck: Boolean,
    cover: suspend (TapeCase) -> ImageBitmap?,
    modifier: Modifier = Modifier.fillMaxWidth().aspectRatio(CASE_ASPECT),
) {
    val stripe = Color(case.stripe)
    val stripe2 = Color(case.stripe2)
    val art by produceState<ImageBitmap?>(null, case.id, case.hasCover) { value = cover(case) }
    Row(
        modifier
            .offset(y = if (inDeck) (-6).dp else 0.dp)
            .shadow(if (inDeck) 10.dp else 5.dp, RoundedCornerShape(3.dp))
            .clip(RoundedCornerShape(3.dp))
            .background(Color(0xFF14110E))
            .plasticSheen(),
    ) {
        // The hinge: a strip of ribbed dark plastic.
        Box(
            Modifier
                .fillMaxHeight()
                .width(6.dp)
                .background(Brush.horizontalGradient(listOf(Color(0xFF2B2622), Color(0xFF0D0B09), Color(0xFF2B2622)))),
        )
        Column(Modifier.weight(1f).fillMaxHeight().padding(top = 2.dp, end = 2.dp, bottom = 2.dp).background(DeckColors.Paper)) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).background(Brush.linearGradient(listOf(stripe, stripe2))),
                contentAlignment = Alignment.Center,
            ) {
                val image = art
                if (image != null) {
                    Image(image, contentDescription = case.album, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text("♪", style = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Black, color = Color.White.copy(alpha = 0.85f)))
                }
            }
            Box(Modifier.fillMaxWidth().height(3.dp).background(stripe))
            Box(Modifier.fillMaxWidth().height(1.5.dp).background(stripe2))
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
                Text(
                    case.album,
                    style = CaseHand.copy(lineHeight = 11.sp),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (case.artist.isNotBlank()) {
                    Text(case.artist, style = CaseTyped, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    if (inDeck) "● IN DECK" else case.source.label.uppercase(),
                    style = CaseTyped.copy(
                        fontSize = 6.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp,
                        color = if (inDeck) DeckColors.AccentDeep else DeckColors.Ink.copy(alpha = 0.5f),
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

/** The case spine-on, the way tapes sit in a rack: colour block, handwritten title, artist. */
@Composable
private fun CaseSpine(case: TapeCase, inDeck: Boolean, height: Dp = 34.dp) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(height)
            .offset(x = if (inDeck) 10.dp else 0.dp)
            .shadow(if (inDeck) 8.dp else 2.dp, RoundedCornerShape(2.dp))
            .clip(RoundedCornerShape(2.dp))
            .background(DeckColors.Paper)
            .plasticSheen(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.fillMaxHeight().width(22.dp).background(Color(case.stripe)))
        Box(Modifier.fillMaxHeight().width(4.dp).background(Color(case.stripe2)))
        Spacer(Modifier.width(8.dp))
        Text(
            case.album,
            style = CaseHand.copy(fontSize = 13.sp),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (case.artist.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                case.artist.uppercase(),
                style = CaseTyped.copy(fontSize = 9.sp, color = DeckColors.Ink.copy(alpha = 0.65f)),
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                modifier = Modifier.weight(0.6f),
            )
        }
        Spacer(Modifier.width(8.dp))
        if (inDeck) {
            Text("IN DECK", style = CaseTyped.copy(fontSize = 7.sp, fontWeight = FontWeight.Bold, color = DeckColors.AccentDeep))
            Spacer(Modifier.width(8.dp))
        }
    }
}

/** A diagonal highlight and a thin bright edge, so the card reads as sitting behind clear plastic. */
private fun Modifier.plasticSheen(): Modifier = drawWithContent {
    drawContent()
    drawRect(
        Brush.linearGradient(
            0f to Color.Transparent,
            0.38f to Color.White.copy(alpha = 0.16f),
            0.5f to Color.Transparent,
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height),
        ),
    )
    drawRect(Color.White.copy(alpha = 0.22f), size = Size(size.width, size.height), style = Stroke(1.5f))
}
