package raf.console.quran7hours.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import kotlinx.coroutines.launch

/** The number in a [5] marker is NOT a global destination.
 *  Each verse (and each surah introduction, ayah 0) owns a separate navigator.
 */
internal data class Q7HFootnoteMarker(
    val id: String,
    val number: Int,
    val source: Boolean,
    val start: Int,
    val end: Int
)

internal object Q7HFootnoteMarkers {
    private val reference = Regex("""\[(\d{1,5})\]""")
    private val sourceAtStart = Regex("""^\s*\[\d{1,5}\]""")

    /** Preserve the exact text. Only recognise bracketed decimal footnote numbers.
     *  A paragraph beginning with [n] is a source entry; all other occurrences
     *  are in-text references. Every card is parsed independently.
     */
    fun parse(text: String, paragraphId: String): List<Q7HFootnoteMarker> {
        val source = sourceAtStart.containsMatchIn(text)
        return reference.findAll(text).mapIndexedNotNull { ordinal, match ->
            val n = match.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return@mapIndexedNotNull null
            Q7HFootnoteMarker("$paragraphId:$ordinal", n, source, match.range.first, match.range.last + 1)
        }.toList()
    }
}

internal class Q7HFootnoteLayout {
    var result: TextLayoutResult? = null
}

internal class Q7HFootnoteNavigator {
    internal data class Target(
        val marker: Q7HFootnoteMarker,
        val requester: BringIntoViewRequester,
        val layout: Q7HFootnoteLayout
    )

    // This map exists ONLY inside one remembered ayah/intro card.
    private val localMarkers = linkedMapOf<String, Target>()
    private val returnToExactReference = mutableMapOf<Int, String>()
    var highlightedId by mutableStateOf<String?>(null)
        private set

    internal fun register(target: Target) { localMarkers[target.marker.id] = target }
    internal fun unregister(id: String) { localMarkers.remove(id) }

    internal suspend fun onMarkerClicked(clicked: Q7HFootnoteMarker) {
        val destination = if (clicked.source) {
            val prior = returnToExactReference[clicked.number]?.let { localMarkers[it] }
            prior ?: localMarkers.values.firstOrNull {
                !it.marker.source && it.marker.number == clicked.number
            }
        } else {
            returnToExactReference[clicked.number] = clicked.id
            localMarkers.values.firstOrNull {
                it.marker.source && it.marker.number == clicked.number
            }
        }
        if (destination == null) {
            // Some Azan pages have orphan numbers: do not jump to another ayah.
            highlightedId = clicked.id
            return
        }
        highlightedId = destination.marker.id
        val measured = destination.layout.result
        val box = measured?.getBoundingBox(destination.marker.start)
        val tail = measured?.getBoundingBox((destination.marker.end - 1).coerceAtLeast(0))
        if (measured != null && box != null && tail != null) {
            // Scroll to the precise [n] INSIDE its paragraph, rather than the
            // beginning of the page or even the beginning of a long paragraph.
            destination.requester.bringIntoView(
                Rect(0f, (box.top - 12f).coerceAtLeast(0f),
                    measured.size.width.toFloat(), tail.bottom + 12f)
            )
        } else {
            destination.requester.bringIntoView()
        }
    }
}

/** A drop-in Material3 Text with accurate [n] hit testing and beige target flash.
 *  Works in body, quotes, headings, translation and zero-ayah introductions.
 */
@Composable
internal fun Q7HFootnoteText(
    text: String,
    navigator: Q7HFootnoteNavigator,
    paragraphId: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified
) {
    val markers = remember(text, paragraphId) { Q7HFootnoteMarkers.parse(text, paragraphId) }
    if (markers.isEmpty()) {
        Text(text = text, modifier = modifier, style = style, color = color)
        return
    }
    val requester = remember(paragraphId) { BringIntoViewRequester() }
    val layout = remember(paragraphId) { Q7HFootnoteLayout() }
    val coroutineScope = rememberCoroutineScope()
    val active = navigator.highlightedId
    val colors = MaterialTheme.colorScheme
    val ink = if (colors.background.luminance() < 0.35f) Color(0xFFE7C79F) else Color(0xFF886543)
    val beige = if (colors.background.luminance() < 0.35f) Color(0x885A4631) else Color(0x66DCC7A6)
    val richText = remember(text, markers, active, ink, beige) {
        buildAnnotatedString {
            append(text)
            markers.forEach { marker ->
                addStyle(
                    SpanStyle(
                        color = ink,
                        fontWeight = FontWeight.SemiBold,
                        textDecoration = TextDecoration.Underline,
                        background = if (active == marker.id) beige else Color.Transparent
                    ),
                    marker.start, marker.end
                )
                addStringAnnotation("q7h_footnote", marker.id, marker.start, marker.end)
            }
        }
    }
    DisposableEffect(navigator, markers, requester, layout) {
        markers.forEach { navigator.register(Q7HFootnoteNavigator.Target(it, requester, layout)) }
        onDispose { markers.forEach { navigator.unregister(it.id) } }
    }
    Text(
        text = richText,
        modifier = modifier
            .bringIntoViewRequester(requester)
            .pointerInput(richText) {
                detectTapGestures { tap ->
                    val snapshot = layout.result ?: return@detectTapGestures
                    if (richText.isEmpty()) return@detectTapGestures
                    val offset = snapshot.getOffsetForPosition(tap)
                        .coerceIn(0, richText.length - 1)
                    val marker = markers.firstOrNull { offset in it.start until it.end }
                        ?: return@detectTapGestures
                    // Avoid activating the nearest marker after a tap in blank space.
                    if (!snapshot.getBoundingBox(offset).contains(tap)) return@detectTapGestures
                    coroutineScope.launch { navigator.onMarkerClicked(marker) }
                }
            },
        onTextLayout = { layout.result = it },
        style = style,
        color = color
    )
}
