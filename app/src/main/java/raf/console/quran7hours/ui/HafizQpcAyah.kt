package raf.console.quran7hours.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import raf.console.quran7hours.data.TafsirStore

/** QPC renderer for a Diary ayah, prepared before showing ANY Arabic. */
@Composable
internal fun HafizOriginalQpcAyah(
    page: Int,
    tafsir: TafsirStore,
    surah: Int,
    ayah: Int,
    fallbackArabic: String,
    tajweed: Boolean
) {
    val context = LocalContext.current
    val prepared by produceState<QpcPreparedPage?>(null, page, tafsir) {
        value = QpcPreparedPages.page(context, tafsir, page)
    }
    when {
        prepared == null -> Box(Modifier.fillMaxWidth().heightIn(min = 32.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        prepared!!.qpc[surah to ayah] != null -> {
            OriginalQpcTafsirText(prepared!!.qpc.getValue(surah to ayah), tajweed)
        }
        else -> {
            // Keep verified Azan Arabic if this ayah cannot be mapped: NEVER display raw PUA codes.
            TafsirQuranText(arabicFromAzan = fallbackArabic, verified = null, tajweed = false)
        }
    }
}
