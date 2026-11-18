package raf.console.quran7hours.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import raf.console.quran7hours.data.QuranCatalog
import raf.console.quran7hours.data.UserPreferences
import raf.console.quran7hours.media.AyahEndMode
import raf.console.quran7hours.media.Reciter
import kotlin.math.roundToInt

/** A single Media3 channel with three persisted shapes; never starts a second player. */
@Composable
internal fun Q7HReaderFloatingPlayer(
    target: Pair<Int, Int>,
    hostSize: IntSize,
    playback: PlaybackSnapshot,
    controller: MediaController?,
    prefs: UserPreferences,
    reciters: List<Reciter>,
    reciterError: String,
    selectedReciter: String,
    onReciter: (String) -> Unit,
    endMode: AyahEndMode,
    onEndMode: (AyahEndMode) -> Unit,
    onPlayVerse: (Int, Int) -> Unit,
    onClose: () -> Unit
) {
    val density = LocalDensity.current
    var display by remember { mutableStateOf(prefs.readerPlayerDisplay) }
    var fullX by remember { mutableFloatStateOf(prefs.readerPlayerFullX) }
    var fullY by remember { mutableFloatStateOf(prefs.readerPlayerFullY) }
    var squareX by remember { mutableFloatStateOf(prefs.readerPlayerSquareX) }
    var squareY by remember { mutableFloatStateOf(prefs.readerPlayerSquareY) }
    var rowTop by remember { mutableStateOf(prefs.readerPlayerRowTop) }
    var measured by remember(display) { mutableStateOf(IntSize.Zero) }
    var rowDrag by remember { mutableFloatStateOf(0f) }
    var reciterMenu by remember { mutableStateOf(false) }
    var sliderDragging by remember { mutableStateOf(false) }
    var sliderFraction by remember { mutableFloatStateOf(0f) }
    val margin = with(density) { 14.dp.roundToPx() }
    val travelX = (hostSize.width - measured.width - margin * 2).coerceAtLeast(0)
    val travelY = (hostSize.height - measured.height - margin * 2).coerceAtLeast(0)
    val xPos = when (display) {
        "square" -> squareX
        "row" -> .5f
        else -> fullX
    }
    val yPos = when (display) {
        "square" -> squareY
        "row" -> if (rowTop) 0f else 1f
        else -> fullY
    }
    val pixelX = margin + (travelX * xPos).roundToInt()
    val pixelY = margin + (travelY * yPos).roundToInt()

    fun mode(next: String) {
        if (next !in setOf("full", "row", "square")) return
        display = next
        prefs.readerPlayerDisplay = next
    }
    fun move(delta: Offset) {
        when (display) {
            "square" -> {
                squareX = if (travelX > 0) (squareX + delta.x / travelX).coerceIn(0f, 1f) else .5f
                squareY = if (travelY > 0) (squareY + delta.y / travelY).coerceIn(0f, 1f) else .5f
                prefs.readerPlayerSquareX = squareX
                prefs.readerPlayerSquareY = squareY
            }
            "row" -> { rowDrag += delta.y }
            else -> {
                fullX = if (travelX > 0) (fullX + delta.x / travelX).coerceIn(0f, 1f) else .5f
                fullY = if (travelY > 0) (fullY + delta.y / travelY).coerceIn(0f, 1f) else .5f
                prefs.readerPlayerFullX = fullX
                prefs.readerPlayerFullY = fullY
            }
        }
    }
    fun snapRow() {
        if (kotlin.math.abs(rowDrag) > with(density) { 22.dp.toPx() }) {
            rowTop = rowDrag < 0f
            prefs.readerPlayerRowTop = rowTop
        }
        rowDrag = 0f
    }
    val grip = Modifier.pointerInput(display, hostSize, measured) {
        detectDragGestures(onDragEnd = { snapRow() }, onDragCancel = { snapRow() }) { change, delta ->
            change.consume()
            move(delta)
        }
    }
    fun toggle() {
        if (playback.playing) {
            controller?.pause()
            if (endMode == AyahEndMode.STOP) controller?.seekTo(0L)
        } else {
            onPlayVerse(target.first, target.second)
        }
    }
    fun adjacent(step: Int): Pair<Int, Int>? {
        val (s, a) = target
        if (!QuranCatalog.isValidAyah(s, a)) return null
        return when {
            step < 0 && a > 1 -> s to (a - 1)
            step < 0 && s > 1 -> (s - 1) to QuranCatalog.ayahCounts[s - 2]
            step > 0 && a < QuranCatalog.ayahCounts[s - 1] -> s to (a + 1)
            step > 0 && s < 114 -> (s + 1) to 1
            else -> null
        }
    }
    val last = adjacent(-1)
    val next = adjacent(1)
    // Snapshot update during seek must not snap the thumb backwards.
    val duration = playback.durationMs.coerceAtLeast(1L)
    val actualFraction = (playback.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    val availableWidth = with(density) { hostSize.width.toDp() }
    val contentWidth = (availableWidth - 28.dp).coerceAtLeast(210.dp).coerceAtMost(395.dp)

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.align(Alignment.TopStart)
            .offset { IntOffset(pixelX.coerceAtLeast(0), pixelY.coerceAtLeast(0)) }
            .onSizeChanged { measured = it }) {
            Surface(shape = RoundedCornerShape(if (display == "square") 16.dp else 20.dp),
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 5.dp, shadowElevation = 8.dp) {
                when (display) {
                    "square" -> {
                        // Direct dragging, not a long press. The tiny corner button only expands.
                        Box(Modifier.size(68.dp).then(grip), contentAlignment = Alignment.Center) {
                            IconButton(onClick = ::toggle, modifier = Modifier.size(53.dp)) {
                                Icon(if (playback.playing) Icons.Default.Stop else Icons.Default.PlayArrow,
                                    if (playback.playing) "Остановить аят" else "Воспроизвести аят",
                                    modifier = Modifier.size(29.dp))
                            }
                            IconButton(onClick = { mode("full") }, modifier = Modifier
                                .align(Alignment.TopEnd).size(23.dp)) {
                                Icon(Icons.Default.OpenInFull, "Развернуть плеер", Modifier.size(15.dp))
                            }
                        }
                    }
                    "row" -> {
                        Row(Modifier.width(contentWidth).height(56.dp)
                            .horizontalScroll(rememberScrollState())
                            .padding(start=4.dp,end=4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                            Icon(Icons.Default.DragHandle,"Переместить строку вверх или вниз",
                                modifier=Modifier.size(24.dp).then(grip))
                            Text("${target.first}:${target.second}",fontSize=11.sp,
                                maxLines=1,modifier=Modifier.padding(start=2.dp,end=2.dp))
                            IconButton(onClick={last?.let { onPlayVerse(it.first,it.second) }},
                                enabled=last!=null,modifier=Modifier.size(30.dp)) {
                                Icon(Icons.Default.SkipPrevious,"Предыдущий аят",Modifier.size(21.dp))
                            }
                            IconButton(onClick=::toggle,modifier=Modifier.size(30.dp)) {
                                Icon(if(playback.playing)Icons.Default.Pause else Icons.Default.PlayArrow,
                                    if(playback.playing)"Пауза" else "Воспроизвести",Modifier.size(21.dp))
                            }
                            Slider(value=if(sliderDragging)sliderFraction else actualFraction,
                                onValueChange={sliderFraction=it;sliderDragging=true},
                                onValueChangeFinished={
                                    controller?.seekTo((sliderFraction*duration).toLong())
                                    sliderDragging=false
                                },enabled=playback.durationMs>0,
                                modifier=Modifier.width(54.dp))
                            IconButton(onClick={next?.let {onPlayVerse(it.first,it.second)}},
                                enabled=next!=null,modifier=Modifier.size(30.dp)) {
                                Icon(Icons.Default.SkipNext,"Следующий аят",Modifier.size(21.dp))
                            }
                            val nextMode = when(endMode) {
                                AyahEndMode.STOP -> AyahEndMode.REPEAT
                                AyahEndMode.REPEAT -> AyahEndMode.NEXT
                                AyahEndMode.NEXT -> AyahEndMode.ALL_RECITERS
                                AyahEndMode.ALL_RECITERS -> AyahEndMode.REPEAT_N
                                AyahEndMode.REPEAT_N -> AyahEndMode.STOP
                            }
                            IconButton(onClick={
                                if(nextMode==AyahEndMode.ALL_RECITERS) {
                                    val preferred=reciters.firstOrNull {
                                        it.repository==prefs.manuallySelectedReciter
                                    } ?: reciters.firstOrNull { it.repository==selectedReciter }
                                    if(preferred!=null) {
                                        prefs.reciterBeforeAllMode=preferred.repository
                                        prefs.personNeedsFirstPlay=!playback.playing
                                        if(preferred.repository!=selectedReciter)onReciter(preferred.repository)
                                    }
                                    onEndMode(nextMode)
                                } else if(endMode==AyahEndMode.ALL_RECITERS) {
                                    val original=reciters.firstOrNull {
                                        it.repository==prefs.reciterBeforeAllMode
                                    }
                                    onEndMode(nextMode)
                                    prefs.personNeedsFirstPlay=false
                                    if(original!=null) onReciter(original.repository)
                                    prefs.reciterBeforeAllMode=""
                                } else onEndMode(nextMode)
                            },modifier=Modifier.size(30.dp)) {
                                if(endMode==AyahEndMode.REPEAT_N) Text("N",fontSize=16.sp)
                                else Icon(when(endMode) {
                                    AyahEndMode.STOP -> Icons.Default.StopCircle
                                    AyahEndMode.REPEAT -> Icons.Default.RepeatOne
                                    AyahEndMode.NEXT -> Icons.Default.SkipNext
                                    AyahEndMode.ALL_RECITERS -> Icons.Default.Person
                                    AyahEndMode.REPEAT_N -> Icons.Default.RepeatOne
                                },endMode.title,Modifier.size(21.dp))
                            }
                            Box {
                                IconButton(onClick={reciterMenu=true},modifier=Modifier.size(30.dp)) {
                                    Icon(Icons.Default.AccountCircle,"Выбрать чтеца",Modifier.size(21.dp))
                                }
                                DropdownMenu(expanded=reciterMenu,onDismissRequest={reciterMenu=false}) {
                                    Column(Modifier.heightIn(max=380.dp).verticalScroll(rememberScrollState())) {
                                        if(reciters.isEmpty()) DropdownMenuItem(
                                            text={Text(reciterError.ifBlank {"Чтецы загружаются"})},onClick={reciterMenu=false})
                                        reciters.forEach { voice ->
                                            DropdownMenuItem(text={Text(voice.title)},onClick={
                                                prefs.manuallySelectedReciter=voice.repository
                                                if(endMode==AyahEndMode.ALL_RECITERS) {
                                                    prefs.reciterBeforeAllMode=voice.repository
                                                    prefs.personNeedsFirstPlay=false
                                                }
                                                onReciter(voice.repository);reciterMenu=false
                                            },leadingIcon=if(voice.repository==selectedReciter)
                                                ({ Icon(Icons.Default.Check,null) }) else null)
                                        }
                                    }
                                }
                            }
                            IconButton(onClick={mode("full")},modifier=Modifier.size(30.dp)) {
                                Icon(Icons.Default.UnfoldMore,"Развернуть плеер",Modifier.size(21.dp))
                            }
                            IconButton(onClick={mode("square")},modifier=Modifier.size(30.dp)) {
                                Icon(Icons.Default.CloseFullscreen,"Свернуть в квадрат",Modifier.size(19.dp))
                            }
                            IconButton(onClick=onClose,modifier=Modifier.size(30.dp)) {
                                Icon(Icons.Default.Close,"Закрыть и остановить",Modifier.size(21.dp))
                            }
                        }
                    }
                    else -> {
                        Column(Modifier.width(contentWidth).padding(start=5.dp,end=5.dp,top=3.dp,bottom=5.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Icon(Icons.Default.DragHandle,"Перетащить плеер",
                                    modifier=Modifier.size(36.dp).then(grip))
                                Text("Плеер аятов",style=MaterialTheme.typography.labelSmall,
                                    modifier=Modifier.weight(1f))
                                IconButton(onClick={mode("row")},modifier=Modifier.size(32.dp)) {
                                    Icon(Icons.Default.UnfoldLess,"Свернуть в строку",Modifier.size(20.dp))
                                }
                                IconButton(onClick={mode("square")},modifier=Modifier.size(32.dp)) {
                                    Icon(Icons.Default.CloseFullscreen,"Свернуть в квадрат",Modifier.size(20.dp))
                                }
                                IconButton(onClick=onClose,modifier=Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Close,"Закрыть и остановить",Modifier.size(20.dp))
                                }
                            }
                            Q7HAyahMiniPlayer(target=target,playback=playback,controller=controller,
                                prefs=prefs,reciters=reciters,reciterError=reciterError,
                                selectedReciter=selectedReciter,onReciter=onReciter,
                                endMode=endMode,onEndMode=onEndMode,onPlayVerse=onPlayVerse)
                            Q7HRangeBelowPlayer(SheetSection.TAFSIR,playback,controller)
                        }
                    }
                }
            }
        }
    }
}
