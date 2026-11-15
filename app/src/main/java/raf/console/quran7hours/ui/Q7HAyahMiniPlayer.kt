package raf.console.quran7hours.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import raf.console.quran7hours.data.TafsirStore
import raf.console.quran7hours.data.QuranCatalog
import raf.console.quran7hours.data.UserPreferences
import raf.console.quran7hours.media.Reciter
import raf.console.quran7hours.media.AyahEndMode

/** Single mini-player bound to the CURRENT playing ayah; no per-card scrubbers. */
internal fun q7hAyahMiniTarget(verses: List<TafsirStore.Verse>, playback: PlaybackSnapshot): Pair<Int,Int>? {
    val media = playback.mediaId.split(':')
    val surah = media.getOrNull(1)?.toIntOrNull()
    val ayah = media.getOrNull(2)?.toIntOrNull()
    return if (media.firstOrNull() == "ayah" && surah != null && ayah != null &&
        QuranCatalog.isValidAyah(surah, ayah)) surah to ayah
    else verses.firstOrNull()?.let { it.surah to it.ayah }
}

private fun adjacent(verse: Pair<Int,Int>, direction: Int): Pair<Int,Int>? {
    val (s,a) = verse
    if (!QuranCatalog.isValidAyah(s,a)) return null
    return if (direction < 0) {
        if (a > 1) s to (a-1)
        else if (s > 1) (s-1) to QuranCatalog.ayahCounts[s-2]
        else null
    } else {
        if (a < QuranCatalog.ayahCounts[s-1]) s to (a+1)
        else if (s < 114) (s+1) to 1
        else null
    }
}

@Composable
internal fun Q7HAyahMiniPlayer(
    target: Pair<Int,Int>,
    playback: PlaybackSnapshot,
    controller: MediaController?,
    prefs: UserPreferences,
    reciters: List<Reciter>,
    reciterError: String,
    selectedReciter: String,
    onReciter: (String) -> Unit,
    endMode: AyahEndMode,
    onEndMode: (AyahEndMode) -> Unit,
    onPlayVerse: (Int, Int) -> Unit
) {
    val prev = adjacent(target,-1)
    val next = adjacent(target,1)
    val context = LocalContext.current
    val directions = remember(context.applicationContext) { Q7HSliderDirections.get(context.applicationContext) }
    val rtl = directions.rtl(Q7HSliderKeys.AYAH_MINI)
    fun elapsed(ms: Long): String {
        val t = (ms.coerceAtLeast(0L) / 1000L)
        return "%d:%02d".format(t / 60L, t % 60L)
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text("Аят ${target.first}:${target.second}",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 10.dp))
        // Existing AyahAudioControls retains the exact PERSON, N, voice switching
        // and pause/resume semantics; only its position changes.
        AyahAudioControls(
            surah=target.first, ayah=target.second, playback=playback,
            controller=controller, reciters=reciters, reciterError=reciterError,
            selectedReciter=selectedReciter, onReciter=onReciter,
            prefs=prefs,endMode=endMode,onEndMode=onEndMode,
            onPlay={onPlayVerse(target.first,target.second)},
            miniMode=true,
            onPreviousVerse=prev?.let { {onPlayVerse(it.first,it.second)} },
            onNextVerse=next?.let { {onPlayVerse(it.first,it.second)} }
        )
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),
            horizontalArrangement=Arrangement.SpaceBetween) {
            val now = elapsed(playback.positionMs)
            val total = elapsed(playback.durationMs)
            Text(if (rtl) total else now, style=MaterialTheme.typography.labelSmall)
            Text(if (rtl) now else total, style=MaterialTheme.typography.labelSmall)
        }
    }
}
