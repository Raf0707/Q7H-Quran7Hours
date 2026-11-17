package raf.console.quran7hours.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import raf.console.quran7hours.data.QuranCatalog
import kotlin.math.abs

/** Persisted outside Room so legacy backups of SharedPreferences retain these settings. */
internal object Q7HBottomNavSettings {
    const val TWO_ROWS = "two_rows"
    const val SCROLL_FOUR = "scroll_four"
    const val FULL_WIDTH = "full_width"
    const val LEGACY_TOP = "legacy_top"
    const val LEFT = "left"
    const val CENTER = "center"
    const val RIGHT = "right"
    val defaultOrder = listOf("panel", "contents", "theme", "layers", "bookmark", "tajweed", "goto", "draw")
    val modes = listOf(TWO_ROWS, SCROLL_FOUR, FULL_WIDTH)
    val labels = mapOf(
        "panel" to "Открыть панель", "contents" to "Содержание", "theme" to "Тема",
        "layers" to "Пометки", "bookmark" to "Закладки", "tajweed" to "Таджвид",
        "goto" to "Перейти", "draw" to "Рисование"
    )
    private const val STORE = "q7h_bottom_navigation_v1"
    private var initialized = false
    var enabled by mutableStateOf(true)
        private set
    var layout by mutableStateOf(TWO_ROWS)
        private set
    // Replacing these maps triggers recomposition for each layout's changes.
    var orders by mutableStateOf(modes.associateWith { defaultOrder })
        private set
    var alignments by mutableStateOf(modes.associateWith { CENTER })
        private set
    var offsetX by mutableStateOf((modes + LEGACY_TOP).associateWith { 0f })
        private set
    var offsetY by mutableStateOf((modes + LEGACY_TOP).associateWith { 0f })
        private set
    var fullWidthTop by mutableStateOf(false)
        private set
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    @Synchronized fun load(context: Context) {
        if (initialized) return
        val p = prefs(context)
        enabled = p.getBoolean("enabled", true)
        layout = p.getString("layout", TWO_ROWS)?.takeIf { it in modes } ?: TWO_ROWS
        orders = modes.associateWith { mode ->
            val stored = p.getString("order_$mode", null)?.split(',') ?: emptyList()
            if (stored.size == defaultOrder.size && stored.toSet() == defaultOrder.toSet()) stored else defaultOrder
        }
        alignments = modes.associateWith { mode ->
            p.getString("align_$mode", CENTER)?.takeIf { it in listOf(LEFT, CENTER, RIGHT) } ?: CENTER
        }
        offsetX = (modes + LEGACY_TOP).associateWith { p.getFloat("float_x_$it",0f) }
        offsetY = (modes + LEGACY_TOP).associateWith { p.getFloat("float_y_$it",0f) }
        fullWidthTop = p.getBoolean("full_width_top",false)
        initialized = true
    }
    fun savePosition(context: Context, mode: String, x: Float, y: Float) {
        if(mode !in modes && mode != LEGACY_TOP) return
        offsetX = offsetX + (mode to x)
        offsetY = offsetY + (mode to y)
        prefs(context).edit().putFloat("float_x_$mode",x).putFloat("float_y_$mode",y).apply()
    }
    fun setFullWidthTop(context: Context, top: Boolean) {
        fullWidthTop = top
        prefs(context).edit().putBoolean("full_width_top",top).apply()
    }
    fun enable(context: Context, value: Boolean) {
        enabled = value
        prefs(context).edit().putBoolean("enabled", value).apply()
    }
    fun chooseLayout(context: Context, value: String) {
        if (value !in modes) return
        layout = value
        prefs(context).edit().putString("layout", value).apply()
    }
    fun reorder(context: Context, mode: String, index: Int, delta: Int) {
        if (mode !in modes) return
        val order = (orders[mode] ?: defaultOrder).toMutableList()
        val other = index + delta
        if (index !in order.indices || other !in order.indices) return
        val tmp = order[index]
        order[index] = order[other]
        order[other] = tmp
        orders = orders + (mode to order.toList())
        prefs(context).edit().putString("order_$mode", order.joinToString(",")).apply()
    }
    fun align(context: Context, mode: String, value: String) {
        if (mode !in modes || value !in listOf(LEFT,CENTER,RIGHT)) return
        alignments = alignments + (mode to value)
        prefs(context).edit().putString("align_$mode",value).apply()
    }
    fun reset(context: Context, mode: String) {
        if (mode !in modes) return
        orders = orders + (mode to defaultOrder)
        alignments = alignments + (mode to CENTER)
        savePosition(context,mode,0f,0f)
        prefs(context).edit().remove("order_$mode").remove("align_$mode").apply()
    }
}

/** The only section switcher when bottom navigation is enabled. Present in every sheet. */
@Composable
internal fun Q7HBottomSheetDock(
    section: SheetSection,
    onSection: (SheetSection) -> Unit,
    onClose: () -> Unit,
    page: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onNavigate: () -> Unit,
    audioCategory: QuranCatalog.Segment,
    onAudioCategory: (QuranCatalog.Segment) -> Unit,
    onRange: () -> Unit,
    readerDark: Boolean
) {
    val haptic = LocalHapticFeedback.current
    val options = SheetSection.entries
    var expanded by remember { mutableStateOf(false) }
    var gesture by remember { mutableStateOf(false) }
    var selected by remember(section) { mutableIntStateOf(options.indexOf(section).coerceAtLeast(0)) }
    var travel by remember { mutableFloatStateOf(0f) }
    val style = MaterialTheme.colorScheme
    val dockBackground = if (readerDark) Color(0xFF191919) else Color(0xFFFDFDFD)
    val dockInk = if (readerDark) Color.White else Color(0xFF172024)
    CompositionLocalProvider(LocalContentColor provides dockInk) {
    Column(Modifier.fillMaxWidth().background(dockBackground).navigationBarsPadding()) {
        HorizontalDivider(color=style.outlineVariant)
        // The compact contextual strip is ABOVE the fingerprint dock, not over the player.
        when(section) {
            SheetSection.AUDIO -> Row(
                Modifier.fillMaxWidth().padding(horizontal=7.dp, vertical=3.dp),
                horizontalArrangement=Arrangement.spacedBy(5.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                    QuranCatalog.Segment.entries.forEach { kind ->
                        FilterChip(
                            selected=audioCategory == kind,
                            onClick={onAudioCategory(kind)},
                            label={Text(kind.title, fontSize=11.sp, maxLines=1)},
                            modifier=Modifier.height(36.dp)
                        )
                    }
                }
                FilledTonalButton(onClick=onRange, contentPadding=PaddingValues(horizontal=8.dp),
                    modifier=Modifier.height(36.dp)) {
                    Icon(Icons.Default.Tune,null,Modifier.size(16.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("Отрезок",fontSize=11.sp)
                }
            }
            SheetSection.TAFSIR,SheetSection.HAFIZ -> Row(
                Modifier.fillMaxWidth().padding(horizontal=5.dp,vertical=2.dp),
                horizontalArrangement=Arrangement.Center,
                verticalAlignment=Alignment.CenterVertically
            ) {
                IconButton(onClick=onNext,enabled=page<604,modifier=Modifier.size(38.dp)) {
                    Icon(Icons.Default.ChevronLeft,"Следующая страница")
                }
                Text("$page",Modifier.clickable { onNavigate() }
                    .padding(horizontal=12.dp, vertical=8.dp),fontWeight=FontWeight.Bold)
                IconButton(onClick=onPrevious,enabled=page>1,modifier=Modifier.size(38.dp)) {
                    Icon(Icons.Default.ChevronRight,"Предыдущая страница")
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick=onRange,modifier=Modifier.height(36.dp),
                    contentPadding=PaddingValues(horizontal=10.dp,vertical=2.dp)) {
                    Icon(Icons.Default.Tune,null,Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Отрезок",fontSize=12.sp)
                }
            }
            SheetSection.SETTINGS -> Unit
        }
        Box(Modifier.fillMaxWidth().height(54.dp)) {
            if (gesture) {
                Surface(
                    modifier=Modifier.align(Alignment.TopCenter)
                        .offset(y=(-49).dp).padding(horizontal=5.dp),
                    shape=RoundedCornerShape(18.dp),
                    tonalElevation=7.dp,shadowElevation=6.dp
                ) {
                    Row(Modifier.padding(horizontal=4.dp,vertical=3.dp)) {
                        options.forEachIndexed { i, target ->
                            val title = q7hSectionName(target)
                            Text(title,
                                modifier=Modifier.clip(RoundedCornerShape(10.dp))
                                    .background(if(selected==i)style.primaryContainer else Color.Transparent)
                                    .padding(horizontal=9.dp,vertical=9.dp),
                                fontSize=11.sp,fontWeight=if(selected==i)FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
            Box(Modifier.align(Alignment.Center)) {
                Surface(
                    color=style.primaryContainer,
                    contentColor=style.onPrimaryContainer,
                    shape=RoundedCornerShape(20.dp),
                    modifier=Modifier.size(62.dp,46.dp),
                    shadowElevation=3.dp
                ) {
                    Box(contentAlignment=Alignment.Center,
                        modifier=Modifier
                            .pointerInput(section) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart={
                                        gesture=true
                                        expanded=false
                                        selected=options.indexOf(section).coerceAtLeast(0)
                                        travel=0f
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDragCancel={ gesture=false },
                                    onDragEnd={
                                        if(gesture) onSection(options[selected])
                                        gesture=false
                                    }
                                ) { change,drag ->
                                    change.consume()
                                    travel+=drag.x
                                    val step=48.dp.toPx()
                                    while(abs(travel)>=step) {
                                        val dir=if(travel>0f) 1 else -1
                                        val target=(selected+dir).coerceIn(options.indices)
                                        if(target!=selected) {
                                            selected=target
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                        travel-=dir*step
                                    }
                                }
                            }
                            .clickable { expanded=true }
                    ) {
                        Icon(Icons.Default.Fingerprint,"Навигация по разделам — нажмите или удерживайте",
                            Modifier.size(29.dp))
                    }
                }
                DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=6.dp)) {
                        options.forEach { target ->
                            val active=target==section
                            Surface(onClick={expanded=false;onSection(target)},
                                color=if(active)style.primaryContainer else style.surface,
                                shape=RoundedCornerShape(12.dp),
                                modifier=Modifier.padding(2.dp).width(77.dp)) {
                                Column(Modifier.padding(vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                                    Icon(q7hSectionIcon(target),null,Modifier.size(22.dp))
                                    Text(q7hSectionName(target),fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
            IconButton(onClick=onClose,modifier=Modifier.align(Alignment.CenterEnd).padding(end=6.dp)) {
                Icon(Icons.Default.KeyboardArrowDown,"Закрыть панель", tint=dockInk)
            }
        }
    }
    } // LocalContentColor
}

private fun q7hSectionName(section: SheetSection) = when(section) {
    SheetSection.AUDIO -> "Аудио"
    SheetSection.TAFSIR -> "Тафсир"
    SheetSection.HAFIZ -> "Хафиз"
    SheetSection.SETTINGS -> "Настройки"
}
private fun q7hSectionIcon(section:SheetSection) = when(section) {
    SheetSection.AUDIO -> Icons.Default.Headphones
    SheetSection.TAFSIR -> Icons.Default.MenuBook
    SheetSection.HAFIZ -> Icons.Default.School
    SheetSection.SETTINGS -> Icons.Default.Settings
}

/** The compact reader toolbar has no full-width invisible hit target. */
@Composable
internal fun Q7HMushafToolbar(readerDark: Boolean, action: @Composable (String) -> Unit) {
    val settings = Q7HBottomNavSettings
    val mode = settings.layout
    val order = settings.orders[mode] ?: Q7HBottomNavSettings.defaultOrder
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if(readerDark) Color(0xFF202020) else Color(0xFFFDFDFD),
        contentColor = if(readerDark) Color.White else Color(0xFF172024),
        tonalElevation=0.dp,shadowElevation=4.dp,
        modifier = if(mode==Q7HBottomNavSettings.FULL_WIDTH)
            Modifier.fillMaxWidth() else Modifier.wrapContentWidth()
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            when(mode) {
                Q7HBottomNavSettings.TWO_ROWS -> Column(
                    Modifier.width(210.dp).padding(4.dp),
                    horizontalAlignment=Alignment.CenterHorizontally
                ) {
                    order.chunked(4).forEach { group ->
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            group.forEach { key ->
                                Box(Modifier.size(50.dp),contentAlignment=Alignment.Center) {action(key)}
                            }
                        }
                    }
                }
                Q7HBottomNavSettings.SCROLL_FOUR -> Row(
                    Modifier.width(208.dp).horizontalScroll(rememberScrollState())
                        .padding(horizontal=4.dp,vertical=5.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    order.forEach { key ->
                        Box(Modifier.size(48.dp),contentAlignment=Alignment.Center) { action(key) }
                    }
                }
                else -> BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val narrow=maxWidth < (48*order.size).dp
                    Row(Modifier.fillMaxWidth()
                        .then(if(narrow) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
                        .padding(horizontal=1.dp,vertical=4.dp),
                        horizontalArrangement=Arrangement.SpaceEvenly,
                        verticalAlignment=Alignment.CenterVertically) {
                        order.forEach { key ->
                            Box(Modifier.size(48.dp),contentAlignment=Alignment.Center) {action(key)}
                        }
                    }
                }
            }
        }
    }
}

/**
 * Clamp the ENTIRE toolbar to the actual visible reader viewport. The position is
 * stored independently for 2×4 / scrolling 4×1 / legacy top. A full-width bar
 * intentionally has no drag handle, only the top/bottom setting.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Q7HFloatingMushafToolbar(
    bottomEnabled: Boolean,
    inkEditing: Boolean,
    readerDark: Boolean,
    action: @Composable (String) -> Unit
) {
    val ctx=LocalContext.current
    val haptic=LocalHapticFeedback.current
    val density=LocalDensity.current
    val store=Q7HBottomNavSettings
    val mode=if(bottomEnabled)store.layout else Q7HBottomNavSettings.LEGACY_TOP
    var armed by remember(mode) { mutableStateOf(false) }
    var draftX by remember(mode) { mutableFloatStateOf(store.offsetX[mode] ?: 0f) }
    var draftY by remember(mode) { mutableFloatStateOf(store.offsetY[mode] ?: 0f) }
    var positionAlignment by remember(mode) {
        mutableStateOf(store.alignments[mode] ?: if(bottomEnabled)store.alignments[store.layout]
            ?: Q7HBottomNavSettings.CENTER else Q7HBottomNavSettings.RIGHT)
    }
    var measured by remember(mode) { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(mode,store.offsetX[mode],store.offsetY[mode],store.alignments[mode]) {
        if (!armed) {
            draftX=store.offsetX[mode] ?: 0f
            draftY=store.offsetY[mode] ?: 0f
            if(mode!=Q7HBottomNavSettings.LEGACY_TOP)
                positionAlignment=store.alignments[mode] ?: Q7HBottomNavSettings.CENTER
        }
    }
    val unrestricted=mode!=Q7HBottomNavSettings.FULL_WIDTH
    val isBottom=bottomEnabled && (mode!=Q7HBottomNavSettings.FULL_WIDTH || !store.fullWidthTop)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val canvasW=maxWidth.value
        val canvasH=maxHeight.value
        val partW=with(density) { measured.width.toDp().value }.coerceIn(0f,canvasW)
        val partH=with(density) { measured.height.toDp().value }.coerceIn(0f,canvasH)
        val rightEdge=(canvasW-partW).coerceAtLeast(0f)
        val bottomEdge=(canvasH-partH).coerceAtLeast(0f)
        val startX=when {
            mode==Q7HBottomNavSettings.FULL_WIDTH -> 0f
            positionAlignment==Q7HBottomNavSettings.LEFT -> 0f
            positionAlignment==Q7HBottomNavSettings.RIGHT -> rightEdge
            else -> rightEdge/2f
        }
        val startY=if(isBottom) bottomEdge else 0f
        val x=(startX+if(unrestricted)draftX else 0f).coerceIn(0f,rightEdge)
        val y=(startY+if(unrestricted)draftY else 0f).coerceIn(0f,bottomEdge)
        fun commit() {
            store.savePosition(ctx,mode,x-startX,y-startY)
            if(mode in store.modes && mode!=Q7HBottomNavSettings.FULL_WIDTH)
                store.align(ctx,mode,positionAlignment)
            draftX=x-startX;draftY=y-startY
            armed=false
        }
        val canMoveHorizontal=mode==Q7HBottomNavSettings.SCROLL_FOUR
        val moveModifier=if (armed && unrestricted) Modifier.pointerInput(mode,partW,partH,positionAlignment) {
            detectDragGestures { change, delta ->
                change.consume()
                val dx=with(density) { delta.x.toDp().value }
                val dy=with(density) { delta.y.toDp().value }
                if(canMoveHorizontal) draftX=(startX+draftX+dx).coerceIn(0f,rightEdge)-startX
                draftY=(startY+draftY+dy).coerceIn(0f,bottomEdge)-startY
            }
        } else Modifier
        Box(Modifier.align(Alignment.TopStart).absoluteOffset(x=x.dp,y=y.dp)
            .onSizeChanged { if(measured!=it)measured=it }
            .then(moveModifier)) {
            val handle: @Composable () -> Unit = {
                Surface(shape=RoundedCornerShape(16.dp),
                    color=if(readerDark) Color(0xFF404040) else MaterialTheme.colorScheme.primaryContainer,
                    contentColor=if(readerDark) Color.White else MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier=Modifier.padding(3.dp),
                    tonalElevation=4.dp) {
                    Box(Modifier.size(44.dp).combinedClickable(
                        onClick={ if(armed)commit() else armed=true },
                        onLongClick={ armed=true;haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
                    ),contentAlignment=Alignment.Center) {
                        Icon(if(armed)Icons.Default.Check else Icons.Default.Fingerprint,
                            if(armed)"Подтвердить позицию" else "Удерживайте для перемещения",
                            tint=if(readerDark) Color.White else MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
            val buttons: @Composable () -> Unit = {
                if(bottomEnabled) Q7HMushafToolbar(readerDark, action)
                else Surface(shape=RoundedCornerShape(20.dp),
                    color=if(readerDark) Color(0xFF202020) else Color(0xFFFDFDFD),
                    contentColor=if(readerDark) Color.White else Color(0xFF172024),
                    tonalElevation=0.dp,shadowElevation=4.dp) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        Row { listOf("contents","theme","layers","bookmark","tajweed","goto","draw")
                            .forEach { action(it) } }
                    }
                }
            }
            val rightTop=(mode==Q7HBottomNavSettings.TWO_ROWS || mode==Q7HBottomNavSettings.LEGACY_TOP) &&
                positionAlignment==Q7HBottomNavSettings.RIGHT
            Column(horizontalAlignment=Alignment.End) {
                if(rightTop && unrestricted && (bottomEnabled || inkEditing)) handle()
                Row(verticalAlignment=Alignment.CenterVertically) {
                    buttons()
                    if(unrestricted && !rightTop && (bottomEnabled || inkEditing)) handle()
                }
                if(armed && canMoveHorizontal) {
                    // Explicit snap-back choices if a drag was awkward.
                    Row(Modifier.align(Alignment.CenterHorizontally),
                        horizontalArrangement=Arrangement.Center) {
                        listOf(
                            Q7HBottomNavSettings.LEFT to Icons.Default.ArrowBack,
                            Q7HBottomNavSettings.CENTER to Icons.Default.ArrowDownward,
                            Q7HBottomNavSettings.RIGHT to Icons.Default.ArrowForward
                        ).forEach { (edge, icon) ->
                            IconButton(onClick={ positionAlignment=edge;draftX=0f },
                                modifier=Modifier.size(36.dp)) {
                                Icon(icon,when(edge){
                                    Q7HBottomNavSettings.LEFT->"Выровнять слева"
                                    Q7HBottomNavSettings.RIGHT->"Выровнять справа"
                                    else->"По центру"},Modifier.size(22.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Q7HBottomNavigationSettings() {
    val context=LocalContext.current
    Q7HBottomNavSettings.load(context)
    val state=Q7HBottomNavSettings
    HorizontalDivider()
    Text("Нижняя панель навигации",style=MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Нижняя панель навигации")
            Text("Включена по умолчанию. Отпечаток пальца открывает меню разделов; удержание — джойстик.",
                style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked=state.enabled,onCheckedChange={state.enable(context,it)})
    }
    if (!state.enabled) {
        Text("Верхняя панель Мусхафа",style=MaterialTheme.typography.titleMedium)
        Text("Во время графических пометок удерживайте отпечаток пальца рядом с кнопками " +
            "и перемещайте панель вверх или вниз. Галочка сохранит положение.",
            style=MaterialTheme.typography.bodySmall)
        val offset=state.offsetY[Q7HBottomNavSettings.LEGACY_TOP] ?: 0f
        Text("Вертикальный отступ: ${offset.toInt()} dp",style=MaterialTheme.typography.labelMedium)
        Slider(value=offset.coerceIn(0f,500f),valueRange=0f..500f,
            onValueChange={state.savePosition(context,Q7HBottomNavSettings.LEGACY_TOP,0f,it)})
        TextButton(onClick={state.savePosition(context,Q7HBottomNavSettings.LEGACY_TOP,0f,0f)}) {
            Text("Сбросить положение верхней панели")
        }
        return
    }
    Text("Кнопки Мусхафа при закрытой панели",style=MaterialTheme.typography.titleMedium)
    val modes=listOf(
        Q7HBottomNavSettings.TWO_ROWS to "2 × 4",
        Q7HBottomNavSettings.SCROLL_FOUR to "4 + свайп",
        Q7HBottomNavSettings.FULL_WIDTH to "Вся ширина"
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        modes.forEachIndexed { i,(key,title) ->
            SegmentedButton(selected=state.layout==key,onClick={state.chooseLayout(context,key)},
                shape=SegmentedButtonDefaults.itemShape(i,modes.size),
                icon={}) { Text(title,fontSize=11.sp,maxLines=1) }
        }
    }
    if(state.layout==Q7HBottomNavSettings.FULL_WIDTH) {
        Text("Расположение на экране",style=MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(selected=state.fullWidthTop,
                onClick={state.setFullWidthTop(context,true)},label={Text("Сверху")})
            FilterChip(selected=!state.fullWidthTop,
                onClick={state.setFullWidthTop(context,false)},label={Text("Снизу")})
        }
        Text("Полноширинная панель не перемещается жестами.",
            style=MaterialTheme.typography.bodySmall)
    } else {
        Text("Положение: удерживайте отпечаток пальца, передвигайте панель и нажмите галочку.",
            style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text("Расположение кнопок",style=MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
        listOf(Q7HBottomNavSettings.LEFT to "Слева",Q7HBottomNavSettings.CENTER to "По центру",
            Q7HBottomNavSettings.RIGHT to "Справа").forEach { (value,title) ->
            FilterChip(selected=state.alignments[state.layout]==value,
                onClick={state.align(context,state.layout,value)},label={Text(title)})
        }
    }
    if (state.layout != Q7HBottomNavSettings.FULL_WIDTH) {
        val storedY=state.offsetY[state.layout] ?: 0f
        Text("Дополнительная настройка высоты: ${storedY.toInt()} dp",
            style=MaterialTheme.typography.labelMedium)
        Slider(value=storedY.coerceIn(-500f,500f),valueRange=-500f..500f,
            onValueChange={state.savePosition(context,state.layout,
                state.offsetX[state.layout] ?: 0f,it)})
    }
    Text("Порядок кнопок (сверху — справа в RTL). Изменение действует только для выбранного варианта.",
        style=MaterialTheme.typography.bodySmall)
    val selectedOrder=state.orders[state.layout] ?: Q7HBottomNavSettings.defaultOrder
    selectedOrder.forEachIndexed { index,key ->
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("${index+1}. ${Q7HBottomNavSettings.labels[key].orEmpty()}",
                Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
            IconButton(onClick={state.reorder(context,state.layout,index,-1)},enabled=index>0) {
                Icon(Icons.Default.ArrowUpward,"Переместить раньше")
            }
            IconButton(onClick={state.reorder(context,state.layout,index,1)},enabled=index<selectedOrder.lastIndex) {
                Icon(Icons.Default.ArrowDownward,"Переместить позже")
            }
        }
    }
    TextButton(onClick={state.reset(context,state.layout)}) { Text("Восстановить порядок и расположение") }
}
