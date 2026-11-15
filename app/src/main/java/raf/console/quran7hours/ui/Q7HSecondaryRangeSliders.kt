package raf.console.quran7hours.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import raf.console.quran7hours.data.QuranCatalog
import raf.console.quran7hours.data.QuranNavigationIndex
import raf.console.quran7hours.data.TimingCatalog
import raf.console.quran7hours.media.Q7HRangeState
import kotlin.math.roundToInt

private fun smallerUnits(parent:QuranCatalog.Segment):List<QuranCatalog.Segment> = when(parent){
    QuranCatalog.Segment.MANZIL -> listOf(QuranCatalog.Segment.JUZ,QuranCatalog.Segment.HIZB,
        QuranCatalog.Segment.SURAH,QuranCatalog.Segment.PAGE)
    QuranCatalog.Segment.JUZ -> listOf(QuranCatalog.Segment.HIZB,QuranCatalog.Segment.SURAH,QuranCatalog.Segment.PAGE)
    QuranCatalog.Segment.HIZB -> listOf(QuranCatalog.Segment.SURAH,QuranCatalog.Segment.PAGE)
    QuranCatalog.Segment.SURAH -> listOf(QuranCatalog.Segment.PAGE)
    QuranCatalog.Segment.PAGE -> listOf(QuranCatalog.Segment.PAGE)
}

/** A second seek surface, independent of the main MP3 position slider. */
@Composable
internal fun Q7HHadrSecondaryRangeSlider(
    playback:PlaybackSnapshot, controller:MediaController?,timings:TimingCatalog
) {
    val context=LocalContext.current
    val directions = remember(context.applicationContext) { Q7HSliderDirections.get(context.applicationContext) }
    val rtl = directions.rtl(Q7HSliderKeys.HADR_RANGE)
    val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    val range=Q7HRangeState.hadrRange(context) ?: return
    if (playback.mediaId!="hadr") return
    val types=remember(range.segment){smallerUnits(range.segment)}
    var unit by remember(range.segment){mutableStateOf(types.first())}
    val start=timings.seekMs(range.segment,range.from) ?: return
    val finish=timings.seekMs(range.segment,range.to+1)
        ?: playback.durationMs.takeIf { it>start } ?: return
    if (finish<=start) return
    val ticks=remember(range.segment,range.from,range.to,unit,timings,finish){
        timings.marks(unit).filter {mark->
            val ms=mark.positionMs
            ms!=null && ms>=start && ms<finish
        }
    }
    if(ticks.isEmpty()) return
    val current=ticks.indexOfLast { (it.positionMs ?: Long.MAX_VALUE)<=playback.positionMs }.coerceAtLeast(0)
    var drag by remember(unit,range.segment,range.from,range.to){mutableStateOf<Int?>(null)}
    val index=drag ?: current
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(2.dp)){
        Text("Отрезок ${range.from}–${range.to} · быстрый переход",style=MaterialTheme.typography.labelSmall)
        if (types.size>1) {
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(5.dp)){
                types.forEach { type->
                    FilterChip(selected=unit==type,onClick={unit=type;drag=null},label={Text(type.title)})
                }
            }
        }
        val enabled=controller!=null && playback.seekable
        CompositionLocalProvider(LocalLayoutDirection provides direction){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                IconButton(onClick={controller?.seekTo(ticks[(index-1).coerceAtLeast(0)].positionMs ?: 0L)},
                    enabled=enabled && index>0) {
                    Icon(if (rtl) Icons.Default.ChevronRight else Icons.Default.ChevronLeft,"Назад на один пункт")
                }
                Slider(value=index.toFloat(),
                    onValueChange={drag=it.roundToInt().coerceIn(0,ticks.lastIndex)},
                    onValueChangeFinished={
                        drag?.let { controller?.seekTo(ticks[it].positionMs ?: 0L) }
                        drag=null
                    },valueRange=0f..ticks.lastIndex.coerceAtLeast(1).toFloat(),
                    steps=(ticks.size-2).coerceAtLeast(0),
                    enabled=enabled && ticks.size>1,
                    modifier=Modifier.weight(1f))
                IconButton(onClick={controller?.seekTo(ticks[(index+1).coerceAtMost(ticks.lastIndex)].positionMs ?: 0L)},
                    enabled=enabled && index<ticks.lastIndex){
                    Icon(if (rtl) Icons.Default.ChevronLeft else Icons.Default.ChevronRight,"Вперёд на один пункт")
                }
            }
        }
        Text("${unit.title}: ${ticks[index.coerceIn(0,ticks.lastIndex)].number} · " +
            "${ticks.size} отметок",modifier=Modifier.fillMaxWidth(),
            style=MaterialTheme.typography.labelSmall,textAlign=TextAlign.Center)
    }
}

/** Ayah-range transport; requests are consumed by PlaybackService without
 * altering the range endpoints or restarting PERSON/N from the first reciter.
 */
@Composable
internal fun Q7HAyahSecondaryRangeSlider(playback:PlaybackSnapshot) {
    val context=LocalContext.current
    val directions = remember(context.applicationContext) { Q7HSliderDirections.get(context.applicationContext) }
    val rtl = directions.rtl(Q7HSliderKeys.AYAH_RANGE)
    val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    val range=Q7HRangeState.ayahRange(context) ?: return
    if (!playback.mediaId.startsWith("ayah:")) return
    val first=QuranNavigationIndex.absolute(QuranNavigationIndex.Ref(range.fromSurah,range.fromAyah))
    val last=QuranNavigationIndex.absolute(QuranNavigationIndex.Ref(range.toSurah,range.toAyah))
    if(last<first) return
    val total=last-first+1
    val parts=playback.mediaId.split(':')
    val verse=parts.getOrNull(1)?.toIntOrNull()?.let{surah->
        parts.getOrNull(2)?.toIntOrNull()?.let{ayah->
            QuranNavigationIndex.absolute(QuranNavigationIndex.Ref(surah,ayah))
        }
    } ?: first
    var drag by remember(first,last){ mutableStateOf<Int?>(null) }
    val index=drag ?: (verse-first).coerceIn(0,total-1)
    fun choose(n:Int) {
        val position=QuranNavigationIndex.ref(first+n.coerceIn(0,total-1))
        Q7HRangeState.requestAyahSeek(context,position.surah,position.ayah)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal=8.dp)){
        Text("Отрезок аятов · быстрое переключение",style=MaterialTheme.typography.labelSmall)
        CompositionLocalProvider(LocalLayoutDirection provides direction){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                IconButton(onClick={choose(index-1)},enabled=index>0){Icon(if (rtl) Icons.Default.ChevronRight else Icons.Default.ChevronLeft,"Предыдущий аят")}
                Slider(value=index.toFloat(),onValueChange={drag=it.roundToInt().coerceIn(0,total-1)},
                    onValueChangeFinished={drag?.let { choose(it) };drag=null},
                    valueRange=0f..(total-1).coerceAtLeast(1).toFloat(),
                    steps=if(total in 2..120) total-2 else 0,enabled=total>1,
                    modifier=Modifier.weight(1f))
                IconButton(onClick={choose(index+1)},enabled=index<total-1){Icon(if (rtl) Icons.Default.ChevronLeft else Icons.Default.ChevronRight,"Следующий аят")}
            }
        }
        val current=QuranNavigationIndex.ref(first+index)
        Text("Аят ${current.surah}:${current.ayah} · $total аятов",modifier=Modifier.fillMaxWidth(),
            textAlign=TextAlign.Center,style=MaterialTheme.typography.labelSmall)
    }
}
