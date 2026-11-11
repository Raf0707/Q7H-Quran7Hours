package raf.console.quran7hours.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import raf.console.quran7hours.media.PrefacePart

/**
 * 100% offline tafsir card using native Compose views only.
 * Before publishing, generate assets/tafsir_azan_ru/istiaza_basmala.json
 * with tools/import_azan_istiaza.py on the developer's computer.
 */
@Composable
fun IstiazaBasmalaCard(
    onPlay: (PrefacePart) -> Unit,
    playing: PrefacePart? = null
) {
    val context = LocalContext.current
    val article = remember(context) { OfflinePrefaceArticle.read(context) }
    var expanded by rememberSaveable { mutableStateOf(true) }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Text("Перед первым аятом суры «Аль-Фатиха»",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
            Text("Истиаза и басмала", style = MaterialTheme.typography.headlineSmall)
            PrefaceAudioRow(
                title = "Истиаза",
                arabic = "أَعُوذُ بِاللَّهِ مِنَ الشَّيْطَانِ الرَّجِيمِ",
                translation = "Прибегаю к защите Аллаха от проклятого шайтана.",
                part = PrefacePart.ISTIAZA,
                playing = playing == PrefacePart.ISTIAZA,
                onPlay = onPlay
            )
            PrefaceAudioRow(
                title = "Басмала",
                arabic = "بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ",
                translation = "С именем Аллаха, Милостивого, Милосердного.",
                part = PrefacePart.BASMALA,
                playing = playing == PrefacePart.BASMALA,
                onPlay = onPlay
            )
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    "В принятой для этого приложения нумерации басмала " +
                        "читается отдельно перед «Аль-Фатихой». Первый аят " +
                        "начинается словами «Аль-хамду ли-Ллях».",
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            HorizontalDivider()
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { expanded = !expanded }
            ) {
                Text("Полный тафсир «Истиаза и басмала»",
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Свернуть" else "Развернуть")
            }
            if (expanded) {
                if (article == null) {
                    Text(
                        "Полный текст ещё не включён в assets. Перед сборкой запустите " +
                            "tools/import_azan_istiaza.py, чтобы создать " +
                            "tafsir_azan_ru/istiaza_basmala.json. " +
                            "Онлайн-загрузка в приложении отключена.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    article.blocks.forEachIndexed { index, block ->
                        key(index) { OfflinePrefaceBlock(block) }
                    }
                    Text("Тафсир: Азан.ру",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private class OfflinePrefaceArticle(val blocks: List<Block>) {
    data class Block(val type: String, val text: String)

    companion object {
        fun read(context: Context): OfflinePrefaceArticle? = runCatching {
            val raw = context.assets.open("tafsir_azan_ru/istiaza_basmala.json")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(raw)
            val array = root.getJSONArray("blocks")
            require(array.length() >= 12) { "Неполный раздел тафсира" }
            val blocks = (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                Block(obj.optString("type", "body"), obj.getString("text"))
            }.filter { it.text.isNotBlank() }
            require(blocks.size >= 12 && blocks.sumOf { it.text.length } >= 3000) {
                "В assets попал неполный текст статьи"
            }
            OfflinePrefaceArticle(blocks)
        }.getOrNull()
    }
}

@Composable
private fun OfflinePrefaceBlock(block: OfflinePrefaceArticle.Block) {
    when (block.type) {
        "heading" -> Text(block.text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 7.dp))
        "arabic" -> Text(block.text, modifier = Modifier.fillMaxWidth(),
            style = TextStyle(textDirection = TextDirection.Rtl),
            fontSize = 23.sp, lineHeight = 38.sp, textAlign = TextAlign.Right)
        "quote" -> Surface(color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(12.dp)) {
            Text(block.text, modifier = Modifier.fillMaxWidth().padding(13.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        "bullet" -> Text("• ${block.text}", modifier = Modifier.padding(start = 14.dp),
            style = MaterialTheme.typography.bodyMedium)
        "footnote" -> Text(block.text, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> Text(block.text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PrefaceAudioRow(
    title: String,
    arabic: String,
    translation: String,
    part: PrefacePart,
    playing: Boolean,
    onPlay: (PrefacePart) -> Unit
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary)
            Text(arabic, modifier = Modifier.fillMaxWidth(),
                fontSize = 24.sp, lineHeight = 38.sp,
                textAlign = TextAlign.Right,
                style = TextStyle(textDirection = TextDirection.Rtl))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(translation, modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { onPlay(part) }) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playing) "Пауза: $title" else "Воспроизвести: $title",
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
