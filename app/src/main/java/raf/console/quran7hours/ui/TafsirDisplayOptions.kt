package raf.console.quran7hours.ui

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.WeakHashMap
import kotlin.math.roundToInt

internal val Q7H_TAFSIR_ORDER = listOf("dictionary", "translation", "tafsir")
internal val Q7H_HAFIZ_ORDER = listOf("dictionary", "translation", "tafsir", "counter", "notes")
private val q7hTitles = mapOf("dictionary" to "Словарь аята", "translation" to "Перевод",
    "tafsir" to "Тафсир", "counter" to "Счётчик и цель", "notes" to "Заметки и ошибки")

internal fun q7hOrder(raw: String?, allowed: List<String>): List<String> {
    val parsed = raw.orEmpty().split(',').filter { it in allowed }.distinct()
    return parsed + allowed.filterNot { it in parsed }
}

internal data class TafsirDisplayOptions(
    val showArabic: Boolean = true,
    val showDictionary: Boolean = true,
    val showTranslation: Boolean = true,
    val showTafsir: Boolean = true,
    val arabicSp: Float = 29f,
    val dictionarySp: Float = 19f,
    val translationSp: Float = 16f,
    val tafsirSp: Float = 14f,
    val blockOrder: List<String> = Q7H_TAFSIR_ORDER
)

internal data class HafizDisplayOptions(
    val showAudio: Boolean = false,
    val showDictionary: Boolean = false,
    val showTafsir: Boolean = false,
    val arabicSp: Float = 29f,
    val dictionarySp: Float = 19f,
    val translationSp: Float = 16f,
    val tafsirSp: Float = 14f,
    val blockOrder: List<String> = Q7H_HAFIZ_ORDER
)

internal class TafsirDisplayPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("q7h_tafsir_display", Context.MODE_PRIVATE)
    private fun read() = TafsirDisplayOptions(
        showArabic = prefs.getBoolean("arabic", true),
        showDictionary = prefs.getBoolean("dictionary", true),
        showTranslation = prefs.getBoolean("translation", true),
        showTafsir = prefs.getBoolean("tafsir", true),
        arabicSp = prefs.getFloat("arabic_sp", 29f).coerceIn(18f, 48f),
        dictionarySp = prefs.getFloat("dictionary_sp", 19f).coerceIn(12f, 32f),
        translationSp = prefs.getFloat("translation_sp", 16f).coerceIn(11f, 30f),
        tafsirSp = prefs.getFloat("tafsir_sp", 14f).coerceIn(11f, 30f),
        blockOrder = q7hOrder(prefs.getString("block_order", null), Q7H_TAFSIR_ORDER)
    )
    var value by mutableStateOf(read())
        private set

    fun change(update: (TafsirDisplayOptions) -> TafsirDisplayOptions) {
        val v = update(value)
        value = v
        prefs.edit().putBoolean("arabic", v.showArabic)
            .putBoolean("dictionary", v.showDictionary)
            .putBoolean("translation", v.showTranslation)
            .putBoolean("tafsir", v.showTafsir)
            .putFloat("arabic_sp", v.arabicSp)
            .putFloat("dictionary_sp", v.dictionarySp)
            .putFloat("translation_sp", v.translationSp)
            .putFloat("tafsir_sp", v.tafsirSp)
            .putString("block_order", v.blockOrder.joinToString(",")).apply()
    }
    fun reset() { prefs.edit().clear().apply(); value = TafsirDisplayOptions() }
    fun copyFontsFrom(diary: HafizDisplayOptions) = change { it.copy(
        arabicSp = diary.arabicSp, dictionarySp = diary.dictionarySp,
        translationSp = diary.translationSp, tafsirSp = diary.tafsirSp) }
}

internal class HafizDisplayPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("q7h_hafiz_display", Context.MODE_PRIVATE)
    private fun read() = HafizDisplayOptions(
        showAudio = prefs.getBoolean("audio", false),
        showDictionary = prefs.getBoolean("dictionary", false),
        showTafsir = prefs.getBoolean("tafsir", false),
        arabicSp = prefs.getFloat("arabic_sp", 29f).coerceIn(18f, 48f),
        dictionarySp = prefs.getFloat("dictionary_sp", 19f).coerceIn(12f, 32f),
        translationSp = prefs.getFloat("translation_sp", 16f).coerceIn(11f, 30f),
        tafsirSp = prefs.getFloat("tafsir_sp", 14f).coerceIn(11f, 30f),
        blockOrder = q7hOrder(prefs.getString("block_order", null), Q7H_HAFIZ_ORDER)
    )
    var value by mutableStateOf(read())
        private set
    fun change(update: (HafizDisplayOptions) -> HafizDisplayOptions) {
        val v = update(value)
        value = v
        prefs.edit().putBoolean("audio", v.showAudio)
            .putBoolean("dictionary", v.showDictionary)
            .putBoolean("tafsir", v.showTafsir)
            .putFloat("arabic_sp", v.arabicSp)
            .putFloat("dictionary_sp", v.dictionarySp)
            .putFloat("translation_sp", v.translationSp)
            .putFloat("tafsir_sp", v.tafsirSp)
            .putString("block_order", v.blockOrder.joinToString(",")).apply()
    }
    fun reset() { prefs.edit().clear().apply(); value = HafizDisplayOptions() }
    fun copyFontsFrom(tafsir: TafsirDisplayOptions) = change { it.copy(
        arabicSp = tafsir.arabicSp, dictionarySp = tafsir.dictionarySp,
        translationSp = tafsir.translationSp, tafsirSp = tafsir.tafsirSp) }
}

internal object SharedTafsirDisplayOptions {
    private val instances = WeakHashMap<Context, TafsirDisplayPreferences>()
    fun get(context: Context): TafsirDisplayPreferences = synchronized(instances) {
        val app = context.applicationContext
        instances.getOrPut(app) { TafsirDisplayPreferences(app) }
    }
}
internal object SharedHafizDisplayOptions {
    private val instances = WeakHashMap<Context, HafizDisplayPreferences>()
    fun get(context: Context): HafizDisplayPreferences = synchronized(instances) {
        val app = context.applicationContext
        instances.getOrPut(app) { HafizDisplayPreferences(app) }
    }
}

/** Smooth drag, live sibling displacement, and spring-free predictable drop. */
@Composable
private fun Q7HOrderEditor(order: List<String>, onChange: (List<String>) -> Unit) {
    Text("Порядок элементов · удерживайте шесть точек и перетаскивайте",
        style = MaterialTheme.typography.labelMedium)
    val rowHeight = 52.dp
    val density = LocalDensity.current
    val rowPixels = with(density) { rowHeight.toPx() }
    var visualOrder by remember(order) { mutableStateOf(order) }
    var draggedKey by remember { mutableStateOf<String?>(null) }
    var draggedStart by remember { mutableIntStateOf(0) }
    var distance by remember { mutableFloatStateOf(0f) }
    val safeOrder = if (draggedKey == null) order else visualOrder
    Box(Modifier.fillMaxWidth().height(rowHeight * safeOrder.size)) {
        // Explicit keys preserve each row's animation state when its index changes.
        safeOrder.forEach { key ->
            androidx.compose.runtime.key(key) {
                val isDragged = draggedKey == key
                val slot = safeOrder.indexOf(key)
                val destination = slot * rowPixels
                val animatedY by animateFloatAsState(
                    targetValue = destination,
                    animationSpec = tween(durationMillis = 190),
                    label = "q7h-order-$key"
                )
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isDragged) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f),
                    shadowElevation = if (isDragged) 8.dp else 0.dp,
                    modifier = Modifier.fillMaxWidth()
                        .height(48.dp)
                        .zIndex(if (isDragged) 2f else 0f)
                        .graphicsLayer {
                            translationY = if (isDragged) draggedStart * rowPixels + distance else animatedY
                            scaleX = if (isDragged) 1.025f else 1f
                            scaleY = if (isDragged) 1.025f else 1f
                        }
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(q7hTitles[key] ?: key, modifier = Modifier.weight(1f))
                        Icon(Icons.Default.DragIndicator,
                            contentDescription = "Перетащить ${q7hTitles[key] ?: key}",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(42.dp).pointerInput(key, order) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        visualOrder = order
                                        draggedStart = order.indexOf(key).coerceAtLeast(0)
                                        draggedKey = key
                                        distance = 0f
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        distance += amount.y
                                        val target = (draggedStart + (distance / rowPixels).roundToInt())
                                            .coerceIn(0, visualOrder.lastIndex)
                                        val current = visualOrder.indexOf(key)
                                        if (current >= 0 && target != current) {
                                            val reordered = visualOrder.toMutableList()
                                            reordered.removeAt(current)
                                            reordered.add(target, key)
                                            visualOrder = reordered
                                        }
                                    },
                                    onDragEnd = {
                                        val result = visualOrder
                                        draggedKey = null
                                        distance = 0f
                                        if (result != order) onChange(result)
                                    },
                                    onDragCancel = {
                                        draggedKey = null
                                        distance = 0f
                                        visualOrder = order
                                    }
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Q7HCheckbox(text: String, checked:Boolean, enabled:Boolean=true, onChange:(Boolean)->Unit={}) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
        Checkbox(checked=checked,enabled=enabled,onCheckedChange=onChange)
        Text(text,modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Q7HFontSlider(text:String, directionKey:String, value:Float, min:Float, max:Float,
                             reset:()->Unit, update:(Float)->Unit) {
    val context = LocalContext.current
    val directions = remember(context.applicationContext) { Q7HSliderDirections.get(context.applicationContext) }
    val direction = if (directions.rtl(directionKey)) LayoutDirection.Rtl else LayoutDirection.Ltr
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
        Text(text,modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
        Text("${value.roundToInt()} sp",fontSize=12.sp)
        IconButton(onClick=reset) { Icon(Icons.Default.Refresh,"Сбросить $text") }
    }
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        Slider(value=value.coerceIn(min,max),onValueChange={update(it.roundToInt().toFloat())},
            valueRange=min..max,steps=(max-min).toInt()-1)
    }
}

/** Two independent reader sections; names and settings preserve earlier saved prefs. */
@Composable
internal fun Q7HReadingSettingsSection(tafsir:TafsirDisplayPreferences, diary:HafizDisplayPreferences) {
    val t=tafsir.value
    val h=diary.value
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(9.dp)){
        Text("Тафсир",style=MaterialTheme.typography.titleLarge)
        Q7HCheckbox("Аудио (всегда доступно)",true,false)
        Q7HCheckbox("Арабский текст",t.showArabic){tafsir.change { o->o.copy(showArabic=it) }}
        Q7HCheckbox("Словарь аята",t.showDictionary){tafsir.change { o->o.copy(showDictionary=it) }}
        Q7HCheckbox("Перевод",t.showTranslation){tafsir.change { o->o.copy(showTranslation=it,showTafsir=if(it) o.showTafsir else false) }}
        Q7HCheckbox("Тафсир (только с переводом)",t.showTafsir,t.showTranslation){tafsir.change { o->o.copy(showTafsir=it) }}
        Q7HOrderEditor(t.blockOrder){ order->tafsir.change{it.copy(blockOrder=order)} }
        Q7HFontSlider("Арабский",Q7HSliderKeys.TAFSIR_ARABIC,t.arabicSp,18f,48f,{tafsir.change{it.copy(arabicSp=29f)}}){x->tafsir.change{it.copy(arabicSp=x)}}
        Q7HFontSlider("Словарь",Q7HSliderKeys.TAFSIR_DICTIONARY,t.dictionarySp,12f,32f,{tafsir.change{it.copy(dictionarySp=19f)}}){x->tafsir.change{it.copy(dictionarySp=x)}}
        Q7HFontSlider("Перевод",Q7HSliderKeys.TAFSIR_TRANSLATION,t.translationSp,11f,30f,{tafsir.change{it.copy(translationSp=16f)}}){x->tafsir.change{it.copy(translationSp=x)}}
        Q7HFontSlider("Толкование",Q7HSliderKeys.TAFSIR_TAFSIR,t.tafsirSp,11f,30f,{tafsir.change{it.copy(tafsirSp=14f)}}){x->tafsir.change{it.copy(tafsirSp=x)}}
        OutlinedButton(onClick={tafsir.copyFontsFrom(diary.value)},modifier=Modifier.fillMaxWidth()) {Text("Скопировать шрифты из Дневника хафиза")}
        OutlinedButton(onClick={tafsir.reset()},modifier=Modifier.fillMaxWidth()){Text("Заводские настройки тафсира")}
        HorizontalDivider()
        Text("Дневник хафиза",style=MaterialTheme.typography.titleLarge)
        Q7HCheckbox("Арабский текст · обязательный",true,false)
        Q7HCheckbox("Перевод · обязательный",true,false)
        Q7HCheckbox("Счётчик и цель · обязательный",true,false)
        Q7HCheckbox("Заметки и ошибки · обязательные",true,false)
        Q7HCheckbox("Аудио: Play и выбор чтеца",h.showAudio){diary.change {o->o.copy(showAudio=it)}}
        Q7HCheckbox("Словарь аята",h.showDictionary){diary.change {o->o.copy(showDictionary=it)}}
        Q7HCheckbox("Тафсир",h.showTafsir){diary.change {o->o.copy(showTafsir=it)}}
        Q7HOrderEditor(h.blockOrder){ order->diary.change{it.copy(blockOrder=order)} }
        Q7HFontSlider("Арабский",Q7HSliderKeys.HAFIZ_ARABIC,h.arabicSp,18f,48f,{diary.change{it.copy(arabicSp=29f)}}){x->diary.change{it.copy(arabicSp=x)}}
        Q7HFontSlider("Словарь",Q7HSliderKeys.HAFIZ_DICTIONARY,h.dictionarySp,12f,32f,{diary.change{it.copy(dictionarySp=19f)}}){x->diary.change{it.copy(dictionarySp=x)}}
        Q7HFontSlider("Перевод",Q7HSliderKeys.HAFIZ_TRANSLATION,h.translationSp,11f,30f,{diary.change{it.copy(translationSp=16f)}}){x->diary.change{it.copy(translationSp=x)}}
        Q7HFontSlider("Толкование",Q7HSliderKeys.HAFIZ_TAFSIR,h.tafsirSp,11f,30f,{diary.change{it.copy(tafsirSp=14f)}}){x->diary.change{it.copy(tafsirSp=x)}}
        OutlinedButton(onClick={diary.copyFontsFrom(tafsir.value)},modifier=Modifier.fillMaxWidth()){Text("Скопировать шрифты из Тафсира")}
        OutlinedButton(onClick={diary.reset()},modifier=Modifier.fillMaxWidth()){Text("Заводские настройки дневника")}
    }
}

/** Retained for older call sites. */
@Composable
internal fun TafsirDisplaySettingsSection(state: TafsirDisplayPreferences) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val diary = remember(context.applicationContext){SharedHafizDisplayOptions.get(context.applicationContext)}
    Q7HReadingSettingsSection(state,diary)
}
