package raf.console.quran7hours.ui

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.graphics.toArgb
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import raf.console.quran7hours.data.*
import raf.console.quran7hours.media.*
import raf.console.quran7hours.media.PrefacePart

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView)
            .hide(WindowInsetsCompat.Type.statusBars())
        // Restore validated user data BEFORE opening any preferences or SQLite databases.
        // If restoration fails, Q7HBackupManager restores the pre-import files.
        try {
            Q7HBackupManager.applyPendingRestore(this)
        } catch (error: Exception) {
            Log.e("Q7HBackup", "Cannot restore pending backup", error)
        }
        // Restore may have replaced the backup schedule preferences.
        // Reconcile WorkManager with restored settings before rendering UI.
        try { Q7HBackupSchedule.refresh(this) }
        catch (e: Exception) { Log.w("Q7HBackup", "Unable to schedule backups", e) }
        setContent { Quran7HoursScreen(this) }
    }
}

enum class SheetSection { AUDIO, TAFSIR, HAFIZ, SETTINGS }
private enum class DrawerSection { CONTENTS, BOOKMARKS, NOTES }
private data class Q7HReaderSelectedAyah(val surah: Int, val ayah: Int, val page: Int)

/**
 * Normalized positions are stored in SharedPreferences (and included in user backups).
 * The floating square has a visible edge margin, while the full editor uses all
 * available drag space. These same bounds are used for BOTH placement and dragging,
 * preventing the square from jumping when the saved position is restored.
 */
private data class Q7HInkPositionBounds(
    val startX: Float,
    val startY: Float,
    val travelX: Float,
    val travelY: Float
) {
    fun xAt(normalized: Float): Float = startX + normalized.coerceIn(0f, 1f) * travelX
    fun yAt(normalized: Float): Float = startY + normalized.coerceIn(0f, 1f) * travelY
    fun normalizedX(pixel: Float): Float =
        if (travelX > 0f) ((pixel - startX) / travelX).coerceIn(0f, 1f) else .5f
    fun normalizedY(pixel: Float): Float =
        if (travelY > 0f) ((pixel - startY) / travelY).coerceIn(0f, 1f) else .5f
}

private fun q7hInkPositionBounds(
    host: IntSize,
    panel: IntSize,
    square: Boolean,
    density: Float
): Q7HInkPositionBounds {
    val roomX = (host.width - panel.width).coerceAtLeast(0).toFloat()
    val roomY = (host.height - panel.height).coerceAtLeast(0).toFloat()
    if (!square) return Q7HInkPositionBounds(0f, 0f, roomX, roomY)
    // Reserve margins INSIDE the viewport, also on devices with display cutouts.
    // If the viewport is very small, scale margins down rather than hiding content.
    val left = minOf(16f * density, roomX / 2f)
    val right = minOf(16f * density, (roomX - left).coerceAtLeast(0f))
    val top = minOf(12f * density, roomY / 2f)
    val bottom = minOf(28f * density, (roomY - top).coerceAtLeast(0f))
    return Q7HInkPositionBounds(
        left, top,
        (roomX - left - right).coerceAtLeast(0f),
        (roomY - top - bottom).coerceAtLeast(0f)
    )
}

/** A UI snapshot ensures both players are observed independently by Compose. */
data class PlaybackSnapshot(
    val mediaId: String = "",
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val ended: Boolean = false,
    val seekable: Boolean = false,
    val error: String? = null
)

private fun snapshot(player: MediaController?): PlaybackSnapshot = if (player == null) PlaybackSnapshot() else
    PlaybackSnapshot(
        mediaId = player.currentMediaItem?.mediaId.orEmpty(),
        playing = player.isPlaying,
        positionMs = player.currentPosition.coerceAtLeast(0L),
        durationMs = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L,
        ended = player.playbackState == Player.STATE_ENDED,
        seekable = player.isCurrentMediaItemSeekable,
        error = player.playerError?.let { error ->
            val cause = generateSequence(error as Throwable) { it.cause }.last()
            when (cause) {
                is java.io.EOFException ->
                    "Запись MP3 прочитана не полностью. Проверьте загруженный архив в разделе ресурсов."
                else -> cause.localizedMessage ?: error.localizedMessage ?: "Неизвестная ошибка плеера"
            }
        }
    )

@OptIn(ExperimentalMaterial3Api::class, androidx.media3.common.util.UnstableApi::class)
@Composable
private fun Quran7HoursScreen(activity: MainActivity) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { UserPreferences(context) }
    val mushaf = remember { MushafStore(context) }
    val learningDb = remember { LearningDatabase(context).also { it.migrateLegacyBookmarks() } }
    val catalogDetails = remember { CatalogDetails(context) }
    val tafsir = remember { TafsirStore(context) }
    val quranScript = remember { TafsirQuranScript(context) }
    val wordStore = remember { WordByWordStore(context) }
    val reciterRepository = remember(context.applicationContext) { ReciterRepository(context.applicationContext) }
    val timings = remember { TimingCatalog(context) }
    val hadrConnection = remember { AudioConnection(context, AudioChannel.HADR) }
    val ayahConnection = remember { AudioConnection(context, AudioChannel.AYAH) }
    val hafizConnection = remember { AudioConnection(context, AudioChannel.HAFIZ) }

    var hadrController by remember { mutableStateOf<MediaController?>(null) }
    var ayahController by remember { mutableStateOf<MediaController?>(null) }
    var hafizController by remember { mutableStateOf<MediaController?>(null) }
    var hadrSnapshot by remember { mutableStateOf(PlaybackSnapshot()) }
    var ayahSnapshot by remember { mutableStateOf(PlaybackSnapshot()) }
    var hafizSnapshot by remember { mutableStateOf(PlaybackSnapshot()) }
    var separateHafiz by remember { mutableStateOf(prefs.separateHafizPlayer) }
    var hafizMode by remember { mutableStateOf(AyahEndMode.entries.firstOrNull {
        it.name == prefs.hafizEndMode } ?: AyahEndMode.STOP) }
    // Persisted catalog is available immediately; GitHub is only a background refresh.
    var reciters by remember { mutableStateOf(reciterRepository.cached()) }
    var reciterError by remember { mutableStateOf("") }
    var recitersRequested by remember { mutableStateOf(false) }
    var selectedReciter by remember { mutableStateOf(prefs.selectedReciter) }
    var ayahMode by remember { mutableStateOf(AyahEndMode.entries.firstOrNull { it.name == prefs.ayahEndMode } ?: AyahEndMode.STOP) }

    // The displayed sheet is nullable; the selected section is independent and persistent.
    var sheet by remember { mutableStateOf<SheetSection?>(null) }
    val sheetVisibility=remember(context.applicationContext){
        SharedQ7HSheetVisibility.get(context.applicationContext)
    }
    val visibleSections=listOf(
        SheetSection.AUDIO to "Аудио",SheetSection.TAFSIR to "Тафсир",
        SheetSection.HAFIZ to "Дневник хафиза",SheetSection.SETTINGS to "Настройки"
    ).filter { sheetVisibility.value.includes(it.first) }
    var preferredSheet by remember {
        mutableStateOf(SheetSection.entries.firstOrNull { it.name == prefs.lastSheetSection }
            ?: SheetSection.AUDIO)
    }
    var audioSegment by remember {
        mutableStateOf(QuranCatalog.Segment.entries.firstOrNull {
            it.name == prefs.lastAudioSegment
        } ?: QuranCatalog.Segment.SURAH)
    }
    var themeMode by remember { mutableStateOf(prefs.nightMode) }
    var palette by remember { mutableIntStateOf(prefs.palette) }
    var contrast by remember { mutableStateOf(prefs.contrastLevel) }
    var tajweed by remember { mutableStateOf(prefs.tajweed) }
    var autoFlip by remember { mutableStateOf(prefs.autoFlip) }
    var keepAwake by remember { mutableStateOf(prefs.keepAwake) }
    var notice by remember { mutableStateOf("") }
    var ayahFeedback by remember { mutableStateOf("") }
    var ayahFeedbackSerial by remember { mutableIntStateOf(0) }
    fun showBriefAyahFeedback(message: String) {
        ayahFeedback = message
        ayahFeedbackSerial++
    }
    LaunchedEffect(ayahFeedbackSerial) {
        if (ayahFeedbackSerial > 0) {
            delay(1200L)
            ayahFeedback = ""
        }
    }
    var bookmarks by remember { mutableStateOf(learningDb.bookmarks()) }
    var bookmarkTypes by remember { mutableStateOf(learningDb.types()) }
    var learningNotes by remember { mutableStateOf(learningDb.notes()) }
    var bookmarkMenu by remember { mutableStateOf(false) }
    var typeEditor by remember { mutableStateOf<BookmarkType?>(null) }
    var newTypeEditor by remember { mutableStateOf(false) }
    var noteEditor by remember { mutableStateOf<LearningNote?>(null) }
    var inkEditing by remember { mutableStateOf(false) }
    // Contextual ayah actions are available directly on the Mushaf page.
    var readerAyah by remember { mutableStateOf<Q7HReaderSelectedAyah?>(null) }
    var readerAudioAyah by remember { mutableStateOf<Q7HReaderSelectedAyah?>(null) }
    var pendingReaderAudio by remember { mutableStateOf<Q7HReaderSelectedAyah?>(null) }
    var readerEraser by remember { mutableStateOf(false) }
    var showInk by remember { mutableStateOf(prefs.inkVisible) }
    var inkTool by remember { mutableStateOf(prefs.inkDefaultTool) }
    var inkColor by remember { mutableLongStateOf(prefs.inkSelectedColor) }
    var inkSize by remember { mutableFloatStateOf(prefs.inkStrokeSize) }
    var inkPalette by remember { mutableStateOf(learningDb.inkColors()) }
    var inkToolbarState by remember { mutableStateOf(prefs.inkToolbarState) }
    var inkPanelX by remember { mutableFloatStateOf(prefs.inkPanelX.takeIf { it.isFinite() }?.coerceIn(0f,1f) ?: 0.5f) }
    var inkPanelY by remember { mutableFloatStateOf(prefs.inkPanelY.takeIf { it.isFinite() }?.coerceIn(0f,1f) ?: 0.5f) }
    var inkSquareX by remember { mutableFloatStateOf(prefs.inkSquareX.takeIf { it.isFinite() }?.coerceIn(0f,1f) ?: 0.5f) }
    var inkSquareY by remember { mutableFloatStateOf(prefs.inkSquareY.takeIf { it.isFinite() }?.coerceIn(0f,1f) ?: 0.5f) }
    var inkPanelSize by remember { mutableStateOf(IntSize.Zero) }
    var inkHostSize by remember { mutableStateOf(IntSize.Zero) }
    var inkDraft by remember { mutableStateOf<InkStroke?>(null) }
    var inkExitPrompt by remember { mutableStateOf(false) }
    var lastInkDraftWrite by remember { mutableLongStateOf(0L) }
    val inkDensity = LocalDensity.current
    fun persistInkPosition() {
        if(inkToolbarState=="square") {
            prefs.inkSquareX=inkSquareX; prefs.inkSquareY=inkSquareY
        } else { prefs.inkPanelX=inkPanelX; prefs.inkPanelY=inkPanelY }
        prefs.inkToolbarState=inkToolbarState
    }
    fun inkMove(dxPx:Float,dyPx:Float) {
        val square = inkToolbarState == "square"
        val bounds = q7hInkPositionBounds(
            inkHostSize, inkPanelSize, square, inkDensity.density
        )
        val posX = if (square) inkSquareX else inkPanelX
        val posY = if (square) inkSquareY else inkPanelY
        val xx = bounds.xAt(posX) + dxPx
        val yy = bounds.yAt(posY) + dyPx
        val beyond = with(inkDensity) { 18.dp.toPx() }
        if (!square && (xx < -beyond || xx > bounds.travelX + beyond ||
                yy < -beyond || yy > bounds.travelY + beyond)) {
            // Keep the user's last square location even when the full palette
            // is dragged outside its safe area.
            inkToolbarState = "square"
            persistInkPosition()
        } else if (square) {
            inkSquareX = bounds.normalizedX(xx)
            inkSquareY = bounds.normalizedY(yy)
        } else {
            inkPanelX = bounds.normalizedX(xx)
            inkPanelY = bounds.normalizedY(yy)
        }
    }
    var clearInkDialog by remember { mutableStateOf(false) }
    var showToolbarPageJump by rememberSaveable { mutableStateOf(false) }
    // Incrementing request numbers lets each panel open its own range dialog from the persistent dock.
    var bottomRangeSerial by remember { mutableIntStateOf(0) }
    var bottomRangeSection by remember { mutableStateOf(SheetSection.AUDIO) }
    fun requestBottomRange(target: SheetSection) {
        bottomRangeSection = target
        bottomRangeSerial++
    }

    var drawerTab by remember { mutableStateOf(DrawerSection.CONTENTS) }
    var notesSegment by remember { mutableIntStateOf(0) }
    var bookmarkSegment by remember { mutableIntStateOf(0) }
    var drawerSegment by remember { mutableStateOf(QuranCatalog.Segment.SURAH) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val pager = rememberPagerState(initialPage = prefs.lastPage - 1, pageCount = { QuranCatalog.TOTAL_PAGES })
    val page = pager.currentPage + 1
    LaunchedEffect(page) {
        readerAyah = null
        readerEraser = false
        pendingReaderAudio = null
    }
    Q7HReaderOptions.load(context)
    Q7HBottomNavSettings.load(context)
    val bottomNavigation = Q7HBottomNavSettings.enabled
    val diaryConnection = if(separateHafiz) hafizConnection else ayahConnection
    val diaryController = if(separateHafiz) hafizController else ayahController
    val diarySnapshot = if(separateHafiz) hafizSnapshot else ayahSnapshot
    val diaryMode = if(separateHafiz) hafizMode else ayahMode
    LaunchedEffect(page) { QpcPreparedPages.start(context.applicationContext,tafsir,page) }
    LaunchedEffect(sheetVisibility.value) {
        if(sheet!=null && !sheetVisibility.value.includes(sheet!!)) sheet=SheetSection.SETTINGS
        if(!sheetVisibility.value.includes(preferredSheet)) preferredSheet=SheetSection.SETTINGS
    }
    // The exact same stroke list is shared by the active page and its pager leaf.
    // This prevents the previous page from losing its covers midway through a swipe.
    val inkPageCache = remember(learningDb) {
        mutableMapOf<Int, androidx.compose.runtime.snapshots.SnapshotStateList<InkStroke>>()
    }
    fun strokesForPage(number: Int): androidx.compose.runtime.snapshots.SnapshotStateList<InkStroke> =
        inkPageCache.getOrPut(number) {
            mutableStateListOf<InkStroke>().also { it.addAll(learningDb.loadInk(number)) }
        }
    val inkStrokes = remember(page) { strokesForPage(page) }
    val inkUndoHistory = remember(page) { mutableStateListOf<List<InkStroke>>() }
    val inkRedoHistory = remember(page) { mutableStateListOf<List<InkStroke>>() }
    var inkCheckpoint by remember(page) { mutableStateOf(inkStrokes.toList()) }
    val inkSessionSnapshots=remember { mutableStateMapOf<Int,List<InkStroke>>() }
    var smartUndoBefore by remember { mutableStateOf<Map<Int,List<InkStroke>>?>(null) }
    var smartUndoAfter by remember { mutableStateOf<Map<Int,List<InkStroke>>?>(null) }
    var smartRedoAfter by remember { mutableStateOf<Map<Int,List<InkStroke>>?>(null) }
    var smartRedoBefore by remember { mutableStateOf<Map<Int,List<InkStroke>>?>(null) }
    var inkSessionBaseline by remember(page) { mutableStateOf(inkStrokes.toList()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentInkPage = rememberUpdatedState(page)
    val currentInkStrokes = rememberUpdatedState(inkStrokes)
    val currentInkDraft = rememberUpdatedState(inkDraft)
    DisposableEffect(lifecycleOwner, learningDb) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                runCatching {
                    learningDb.saveInk(currentInkPage.value,
                        currentInkStrokes.value.toList() + listOfNotNull(currentInkDraft.value))
                }.onFailure { Log.e("Q7HInk", "Unable to flush notes on pause", it) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Back always gives an explicit save/discard choice; Android process death
    // cannot erase completed strokes, which are persisted after each gesture.
    BackHandler(enabled=inkEditing && !inkExitPrompt) { inkExitPrompt=true }
    if(inkExitPrompt) AlertDialog(
        onDismissRequest={inkExitPrompt=false},
        title={Text("Сохранить изменения?")},
        text={Text("Графические пометки можно сохранить или выйти без новых изменений.")},
        confirmButton={TextButton(onClick={
            learningDb.saveInk(page,inkStrokes.toList()+listOfNotNull(inkDraft))
            inkDraft=null; inkEditing=false; inkExitPrompt=false
            inkSessionSnapshots.clear()
            smartUndoBefore=null;smartUndoAfter=null;smartRedoAfter=null;smartRedoBefore=null
        }){Text("Сохранить")}},
        dismissButton={Row {
            TextButton(onClick={
                inkSessionSnapshots.forEach { (changedPage, original) ->
                    val list=strokesForPage(changedPage)
                    list.clear();list.addAll(original)
                    learningDb.saveInk(changedPage,original)
                }
                inkCheckpoint=inkStrokes.toList()
                inkUndoHistory.clear();inkRedoHistory.clear()
                inkDraft=null;inkEditing=false;inkExitPrompt=false
                inkSessionSnapshots.clear()
                smartUndoBefore=null;smartUndoAfter=null;smartRedoAfter=null;smartRedoBefore=null
            }){Text("Без сохранения")}
            TextButton(onClick={inkExitPrompt=false}){Text("Отмена")}
        }}
    )
    LaunchedEffect(inkToolbarState, inkPanelSize, inkHostSize) {
        if(inkHostSize.width>0 && inkHostSize.height>0 &&
            inkPanelSize.width>0 && inkPanelSize.height>0 && inkToolbarState!="square" &&
            (inkPanelSize.width>inkHostSize.width || inkPanelSize.height>inkHostSize.height)) {
            if (inkToolbarState == "expanded") {
                inkToolbarState = "collapsed"
                inkPanelY = 0f
            } else {
                inkToolbarState = "square"
                // The last manually selected square position remains unchanged.
            }
            persistInkPosition()
        }
    }
    LaunchedEffect(inkEditing) {
        if (inkEditing && !prefs.inkPositionRecoveredV5) {
            // One-time recovery: safely show the full palette from its TOP edge.
            // Later user positioning is always respected, including in backups.
            inkToolbarState = "expanded"
            inkPanelX = .5f
            inkPanelY = .03f
            prefs.inkPanelX = inkPanelX
            prefs.inkPanelY = inkPanelY
            persistInkPosition()
            prefs.inkPositionRecoveredV5 = true
        }
    }
    // Sync user-edited Room palette when returning from Settings or opening drawing mode.
    LaunchedEffect(sheet, inkEditing) {
        if (sheet == null || inkEditing) inkPalette = learningDb.inkColors()
    }
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) { "dark" -> true; "light" -> false; else -> systemDark }
    val variant = mushaf.variant(dark, tajweed)
    // Highlight the currently audible verse with the exact same Q7H smart-marker
    // geometry and settings used for double-tap selection. No permanent ink.
    val audibleVerse = remember(ayahSnapshot.mediaId, ayahSnapshot.playing, ayahSnapshot.ended) {
        val fields = ayahSnapshot.mediaId.split(':')
        val surah = fields.getOrNull(1)?.toIntOrNull()
        val ayah = fields.getOrNull(2)?.toIntOrNull()
        if (ayahSnapshot.playing && !ayahSnapshot.ended && fields.firstOrNull()=="ayah" &&
            surah!=null && ayah!=null && QuranCatalog.isValidAyah(surah,ayah))
            Q7HSmartAyahMarker.Ref(surah,ayah)
        else null
    }
    // Live values prevent an asynchronous smart selection from writing to a
    // different page or theme after the reader turns pages / changes theme.
    val liveSmartPage = rememberUpdatedState(page)
    val liveSmartVariant = rememberUpdatedState(variant)
    // Any transition away and back invalidates pending async selection results.
    val smartEnvironment = remember(page, variant, inkEditing) { Any() }
    val liveSmartEnvironment = rememberUpdatedState(smartEnvironment)
    val smartTapEpoch = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    var playingSurah by remember { mutableStateOf<Int?>(null) }
    var controlsVisible by rememberSaveable { mutableStateOf(false) }
    var controlsLocked by rememberSaveable { mutableStateOf(false) }
    var canvasColor by remember { mutableStateOf(if (dark) Color.Black else Color.White) }
    var themeReveal by remember { mutableStateOf<ThemeRevealFrame?>(null) }
    val themeRevealProgress = remember { Animatable(0f) }
    var themeRevealBusy by remember { mutableStateOf(false) }
    SideEffect {
        // Navigation bar is the same shade as the page, not a grey/teal strip.
        @Suppress("DEPRECATION")
        activity.window.navigationBarColor = canvasColor.toArgb()
        WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            .isAppearanceLightNavigationBars = !dark
    }

    LaunchedEffect(Unit) {
        runCatching { hadrConnection.connect() }
            .onSuccess { hadrController = it }
            .onFailure { notice = "Хадр-плеер: ${it.localizedMessage}" }
    }
    LaunchedEffect(Unit) {
        runCatching { ayahConnection.connect() }
            .onSuccess { ayahController = it }
            .onFailure { notice = "Плеер аятов: ${it.localizedMessage}" }
    }
    LaunchedEffect(Unit) {
        runCatching { hafizConnection.connect() }
            .onSuccess { hafizController = it }
            .onFailure { notice = "Плеер Дневника хафиза: ${it.localizedMessage}" }
    }
    DisposableEffect(Unit) { onDispose {
        hadrConnection.close(); ayahConnection.close(); hafizConnection.close()
    } }

    LaunchedEffect(hadrController, ayahController, hafizController) {
        while (isActive) {
            hadrSnapshot = snapshot(hadrController)
            ayahSnapshot = snapshot(ayahController)
            hafizSnapshot = snapshot(hafizController)
            delay(300)
        }
    }
    // Stale-while-revalidate: NEVER wait for GitHub before enabling Play.
    // Existing installations already have the last 43 validated reciters in
    // ayah_reciter_order, which PERSON mode also reads directly.
    LaunchedEffect(Unit) {
        fun applyCatalog(list: List<Reciter>) {
            if (list.isEmpty()) return
            reciters = list
            reciterError = ""
            val shatri = list.firstOrNull {
                it.repository.removePrefix("q7h_").lowercase() in
                    setOf("shatri", "abubakrshatri", "abubakrashshaatree")
            } ?: list.first()
            val oldSuwaid = selectedReciter.removePrefix("q7h_").lowercase() in
                setOf("aymansowaid", "aymansuwaid")
            val manuallySelected = prefs.manuallySelectedReciter
            val desired = when {
                manuallySelected.isNotBlank() && list.any { it.repository == manuallySelected } ->
                    manuallySelected
                selectedReciter.isBlank() || oldSuwaid ||
                    list.none { it.repository == selectedReciter } -> shatri.repository
                else -> selectedReciter
            }
            selectedReciter = desired
            if (prefs.selectedReciter != desired) prefs.selectedReciter = desired
        }
        applyCatalog(reciterRepository.cached())
        if (reciterRepository.refreshNeeded()) {
            recitersRequested = true
            reciterRepository.list().fold(
                onSuccess = { refreshed ->
                    // A transient API failure may return cached data. Never shrink
                    // the list shown in the reader / Tafsir / Hafiz UI.
                    if (refreshed.size >= reciters.size) applyCatalog(refreshed)
                },
                onFailure = { error ->
                    if (reciters.isEmpty())
                        reciterError = error.localizedMessage ?: "Нет доступного каталога чтецов"
                    else Log.w("Q7H-Reciters", "Using cached voices", error)
                }
            )
            recitersRequested = false
        }
    }

    // Priority: 1) current physical page; 2) next page; 3) previous page.
    // All verses on these pages are scheduled (not just the first eight).
    // No player.prepare(), currentMediaItem change, or auto-play is performed.
    // Compose cancels an old page-window request when the reader turns pages.
    LaunchedEffect(page, selectedReciter, reciters) {
        val reciter = reciters.firstOrNull { it.repository == selectedReciter }
            ?: return@LaunchedEffect
        val window = withContext(Dispatchers.IO) {
            val here = tafsir.page(page).map { it.surah to it.ayah }
            val next = if (page < QuranCatalog.TOTAL_PAGES)
                tafsir.page(page + 1).map { it.surah to it.ayah } else emptyList()
            val previous = if (page > 1)
                tafsir.page(page - 1).map { it.surah to it.ayah } else emptyList()
            Triple(here, next, previous)
        }
        Q7HAyahStreamCache.warmWindow(
            reciter, window.first, window.second, window.third, context.applicationContext
        )
    }

    // Double-tapping an arbitrary verse prefetches THAT exact verse separately,
    // even when many neighbouring page verses are still queued for download.
    LaunchedEffect(readerAyah, selectedReciter) {
        val chosen = readerAyah ?: return@LaunchedEffect
        val reciter = reciters.firstOrNull { it.repository == selectedReciter }
            ?: return@LaunchedEffect
        Q7HAyahStreamCache.warmSelected(
            reciter, chosen.surah, chosen.ayah, context.applicationContext
        )
    }

    LaunchedEffect(keepAwake) {
        if (keepAwake) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    LaunchedEffect(page) { prefs.lastPage = page }
    // Decode immediate neighboring pages on IO threads. Swipe animation stays smooth,
    // without allocating dozens of decoded bitmaps or blocking the UI thread.
    LaunchedEffect(page, variant) {
        withContext(Dispatchers.IO) {
            kotlinx.coroutines.coroutineScope {
                if (page > 1) launch { mushaf.load(page - 1, variant) }
                if (page < 604) launch { mushaf.load(page + 1, variant) }
            }
        }
    }

    LaunchedEffect(hadrSnapshot.mediaId, hadrSnapshot.positionMs / 1000L) {
        playingSurah = if (hadrSnapshot.mediaId == "hadr") withContext(Dispatchers.Default) {
            timings.at(QuranCatalog.Segment.SURAH, hadrSnapshot.positionMs)
        } else null
        if (autoFlip && hadrSnapshot.mediaId == "hadr") {
            val timedPage = withContext(Dispatchers.Default) {
                timings.at(QuranCatalog.Segment.PAGE, hadrSnapshot.positionMs)
            }
            if (timedPage != null && timedPage != page) pager.scrollToPage(timedPage - 1)
        }
    }

    /** Only light/dark uses the quick Telegram-style circular reveal.
     *  Tajweed is an immediate bitmap change with NO animation.
     */
    fun changeAppearance(nextMode: String = themeMode, nextTajweed: Boolean = tajweed) {
        if (themeRevealBusy || (nextMode == themeMode && nextTajweed == tajweed)) return
        val nextDark = when (nextMode) { "dark" -> true; "light" -> false; else -> systemDark }
        val nextVariant = mushaf.variant(nextDark, nextTajweed)
        fun commit() {
            themeMode = nextMode
            prefs.nightMode = nextMode
            tajweed = nextTajweed
            prefs.tajweed = nextTajweed
        }
        // Switching Tajweed never creates a visual transition. If two settings
        // change together, a single theme reveal is allowed only for a real dark-mode change.
        if (nextDark == dark || nextVariant == variant) {
            commit()
            return
        }
        themeRevealBusy = true
        scope.launch {
            try {
                val shownPage = pager.currentPage + 1
                val oldFace = mushaf.load(shownPage, variant)
                val newFace = mushaf.load(shownPage, nextVariant)
                if (oldFace == null || newFace == null) {
                    commit()
                    return@launch
                }
                themeRevealProgress.snapTo(0f)
                themeReveal = ThemeRevealFrame(
                    previous = oldFace,
                    next = newFace,
                    previousBackdrop = Color(mushaf.backdropColor(oldFace)),
                    nextBackdrop = Color(mushaf.backdropColor(newFace))
                )
                commit()
                canvasColor = Color(mushaf.backdropColor(newFace))
                // Both bitmaps are predecoded; reveal has no I/O inside its 320ms run.
                withFrameNanos { }
                themeRevealProgress.animateTo(
                    1f, animationSpec = tween(durationMillis = 320)
                )
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Log.e("Q7H-Theme", "Theme reveal failed", error)
                commit()
            } finally {
                themeReveal = null
                themeRevealBusy = false
                themeRevealProgress.snapTo(0f)
            }
        }
    }

    fun goToPage(target: Int) {
        if (target in 1..604) scope.launch { pager.scrollToPage(target - 1) }
    }
    fun beginHadr(startMs: Long = prefs.hadrPositionMs) {
        if (hadrController == null) { notice = "Аудиоплеер ещё подключается"; return }
        // Use MP3 directly in APK assets. AGP noCompress keeps it seekable;
        // no second 700 MB copy and no GitHub download are required.
        val validAsset = runCatching {
            context.assets.openFd("quran/quran_7_hours.mp3").use { it.length > 10_000_000L }
        }.getOrDefault(false)
        if (!validAsset) {
            notice = "Аудиозапись недоступна"
        } else {
            hadrConnection.playHadr(Uri.parse("asset:///quran/quran_7_hours.mp3"), startMs)
        }
    }
    fun goToAyah(surah: Int, ayah: Int) {
        if (!QuranCatalog.isValidAyah(surah, ayah)) {
            notice = "Недопустимый номер суры или аята"
            return
        }
        scope.launch {
            try {
                // Resolve from actual Tafsir rather than guessing from page starts.
                val locatedPage = tafsir.findPageForAyah(surah, ayah)
                if (locatedPage !in 1..604) {
                    notice = "Не удалось найти аят $surah:$ayah"
                } else {
                    // Immediate positioning avoids animating hundreds of pages and
                    // races with closing the page-jump dialog / drawer.
                    locatedPage?.let { pager.scrollToPage(it - 1) }
                    prefs.lastPage = locatedPage!!
                    controlsVisible = false
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Log.e("Q7H-Navigation", "Ошибка перехода к аяту $surah:$ayah", error)
                notice = "Не удалось перейти к аяту $surah:$ayah: ${error.localizedMessage ?: "ошибка данных"}"
            }
        }
    }
    // A single source of truth for the Tafsir and Mushaf ayah player.
    // The same selected reader and STOP/NEXT/REPEAT/N/PERSON mode are used
    // by both surfaces; changing them in either place is immediately persisted.
    fun selectAyahReciter(chosen: String) {
        selectedReciter = chosen
        prefs.selectedReciter = chosen
        if (ayahSnapshot.playing && ayahSnapshot.mediaId.startsWith("ayah:")) {
            val parts = ayahSnapshot.mediaId.split(':')
            val reciter = reciters.firstOrNull { it.repository == chosen }
            val surah = parts.getOrNull(1)?.toIntOrNull()
            val verse = parts.getOrNull(2)?.toIntOrNull()
            if (reciter != null && surah != null && verse != null &&
                QuranCatalog.isValidAyah(surah, verse)) {
                // Preserve the current verse when switching readers, just as in Tafsir.
                ayahConnection.playAyah(reciter, surah, verse, ayahMode)
            }
        }
    }
    fun selectAyahMode(mode: AyahEndMode) {
        ayahMode = mode
        prefs.ayahEndMode = mode.name
        ayahConnection.setAyahEndMode(mode)
    }

    fun playVerse(surah: Int, verse: Int) {
        val reciter = if (ayahMode == AyahEndMode.ALL_RECITERS) {
            val eligible=Q7HPersonQueue.enabled(reciters,
                Q7HPersonQueue.preferences(context.applicationContext))
            if (eligible.isEmpty()) {
                notice="PERSON: выберите хотя бы одного чтеца в настройках";return
            }
            // A new PERSON series always starts with the first enabled voice.
            eligible.first()
        } else reciters.firstOrNull { it.repository == selectedReciter }
        if (reciter == null) { notice = "Выберите чтеца, проверьте подключение к сети"; return }
        if (ayahController == null) { notice = "Плеер аятов ещё подключается"; return }
        val mediaId = "ayah:$surah:$verse:${reciter.repository}:${reciter.branch}"
        val samePersonVerse = ayahMode == AyahEndMode.ALL_RECITERS &&
            ayahSnapshot.mediaId.startsWith("ayah:$surah:$verse:")
        if (ayahSnapshot.mediaId == mediaId || samePersonVerse) {
            if (ayahSnapshot.playing) ayahController?.pause()
            else {
                if (ayahSnapshot.ended) ayahController?.seekTo(0L)
                if (ayahController?.playbackState == Player.STATE_IDLE) ayahController?.prepare()
                ayahController?.play()
            }
        } else ayahConnection.playAyah(reciter, surah, verse, ayahMode)
    }

    fun playReaderVerse(surah: Int, verse: Int) {
        val displayed = readerAudioAyah ?: readerAyah
        readerAudioAyah = Q7HReaderSelectedAyah(surah, verse, displayed?.page ?: page)
        if (reciters.isEmpty() || ayahController == null) {
            pendingReaderAudio = readerAudioAyah
            // Keep the play request pending without a persistent blocking banner.
        } else {
            pendingReaderAudio = null
            playVerse(surah, verse)
        }
    }

    // Reflect Media3 automatic NEXT and PERSON transitions in the Mushaf mini-player.
    // The reader sheet is not opened and the Quran page is not turned by this effect.
    LaunchedEffect(ayahSnapshot.mediaId) {
        val parts = ayahSnapshot.mediaId.split(':')
        val current = readerAudioAyah
        if (current != null && parts.firstOrNull() == "ayah") {
            val surah = parts.getOrNull(1)?.toIntOrNull()
            val verse = parts.getOrNull(2)?.toIntOrNull()
            if (surah != null && verse != null && QuranCatalog.isValidAyah(surah, verse) &&
                (surah != current.surah || verse != current.ayah)) {
                readerAudioAyah = current.copy(surah = surah, ayah = verse)
            }
        }
    }

    // If a reader chooses Play before the online reciter catalog is ready,
    // start that exact ayah automatically once both Media3 and the catalog arrive.
    LaunchedEffect(reciters, ayahController, pendingReaderAudio) {
        val wanted=pendingReaderAudio
        if(wanted!=null && reciters.isNotEmpty() && ayahController!=null) {
            pendingReaderAudio=null
            playVerse(wanted.surah,wanted.ayah)
        }
    }

    fun playDiaryVerse(surah: Int, verse: Int) {
        if(!separateHafiz) { playVerse(surah,verse); return }
        val reciter=reciters.firstOrNull { it.repository==selectedReciter }
        val controller=hafizController
        if(reciter==null || controller==null) {
            notice="Плеер Дневника хафиза ещё не готов";return
        }
        val id="ayah:$surah:$verse:${reciter.repository}:${reciter.branch}"
        if(hafizSnapshot.mediaId==id) {
            if(hafizSnapshot.playing) controller.pause()
            else {
                if(hafizSnapshot.ended) controller.seekTo(0L)
                controller.play()
            }
        } else hafizConnection.playAyah(reciter,surah,verse,hafizMode)
    }
    fun openAyahBookmark(surah: Int, verse: Int) {
        val target=Q7HReaderOptions.bookmarkTarget
        preferredSheet=target
        prefs.lastSheetSection=target.name
        Q7HAyahJumpBus.open(surah,verse,target)
        sheet=target
        goToAyah(surah,verse)
        scope.launch { drawerState.close() }
    }
    fun quoteIntoNote(surah:Int,verse:Int,selected:String) {
        val source=selected.trim()
        if(source.isBlank()) return
        val old=learningNotes.firstOrNull { it.scope==NoteScope.AYAH &&
            it.surah==surah && it.firstAyah==verse }
        val content=if(old?.body.isNullOrBlank()) "> $source"
            else old!!.body.trimEnd()+"\n\n> $source"
        noteEditor=(old ?: LearningNote(scope=NoteScope.AYAH,surah=surah,
            firstAyah=verse,lastAyah=verse)).copy(body=content)
    }
    fun switchSheet(target: SheetSection) {
        // Pending contextual dialogs belong to their originating section only.
        bottomRangeSerial = 0
        // Explicit navigation must always be able to reach all four sections.
        preferredSheet=target
        prefs.lastSheetSection=target.name
        sheet=target
    }
    Q7HTheme(themeMode, palette, contrast, canvasColor) {
        val sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { proposed -> proposed != SheetValue.Hidden }
        )
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(modifier = Modifier.fillMaxWidth(0.9f)) {
                    Text("Навигация", modifier = Modifier.padding(18.dp), style = MaterialTheme.typography.titleLarge)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        listOf(DrawerSection.CONTENTS to "Содержание", DrawerSection.BOOKMARKS to "Закладки",
                            DrawerSection.NOTES to "Заметки")
                            .forEachIndexed { index, (target, title) ->
                                SegmentedButton(selected = drawerTab == target, onClick = { drawerTab = target },
                                    shape = SegmentedButtonDefaults.itemShape(index, 3)) { Text(title, fontSize = 12.sp) }
                            }
                    }
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider()
                    when (drawerTab) {
                        DrawerSection.CONTENTS -> NavigationContent(
                            segment = drawerSegment, onSegment = { drawerSegment = it }, currentPage = page,
                            highlightedSurah = playingSurah, descriptions = catalogDetails,
                            onClick = { segment, index ->
                                goToPage(QuranCatalog.startPages(segment).getOrNull(index - 1) ?: 1)
                                scope.launch { drawerState.close() }
                            },
                            onJumpAyah = { surah, ayah ->
                                goToAyah(surah, ayah)
                                scope.launch { drawerState.close() }
                            }
                        )
                        DrawerSection.BOOKMARKS -> Column(Modifier.fillMaxSize()) {
                            SingleChoiceSegmentedButtonRow(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                listOf("Страницы", "Аяты").forEachIndexed { index, title ->
                                    SegmentedButton(
                                        selected = bookmarkSegment == index,
                                        onClick = { bookmarkSegment = index },
                                        shape = SegmentedButtonDefaults.itemShape(index, 2)
                                    ) { Text(title) }
                                }
                            }
                            if (bookmarkSegment == 0) LearningBookmarksDetailedDrawer(
                                bookmarks = bookmarks,
                                onOpen = { goToPage(it); scope.launch { drawerState.close() } },
                                onRemove = { id ->
                                    // Detach notes first; deleting a bookmark never deletes notes.
                                    learningNotes.filter { it.bookmarkId == id }.forEach { note ->
                                        learningDb.saveNote(note.copy(
                                            bookmarkId = null,
                                            scope = if(note.scope == NoteScope.NONE) NoteScope.PAGE else note.scope,
                                            number = if(note.scope == NoteScope.NONE) bookmarks.firstOrNull { it.id == id }?.page ?: note.number else note.number
                                        ))
                                    }
                                    learningDb.removeBookmark(id)
                                    bookmarks = learningDb.bookmarks()
                                    learningNotes = learningDb.notes()
                                },
                                onNote = { mark ->
                                    noteEditor = learningNotes.firstOrNull { it.bookmarkId == mark.id }
                                        ?: LearningNote(scope = NoteScope.PAGE, number = mark.page,
                                            bookmarkId = mark.id)
                                }
                            ) else Q7HAyahBookmarksDrawer(
                                database = learningDb,
                                onOpen = { s, a -> openAyahBookmark(s,a) },
                                onNote = { s, a ->
                                    noteEditor = learningNotes.firstOrNull {
                                        it.scope == NoteScope.AYAH && it.surah == s && it.firstAyah == a
                                    } ?: LearningNote(scope = NoteScope.AYAH, surah = s,
                                        firstAyah = a, lastAyah = a)
                                }
                            )
                        }
                        DrawerSection.NOTES -> Column(Modifier.fillMaxSize()) {
                            TabRow(selectedTabIndex=notesSegment) {
                                Tab(selected=notesSegment==0,onClick={notesSegment=0},
                                    text={Text("Текстовые")})
                                Tab(selected=notesSegment==1,onClick={notesSegment=1},
                                    text={Text("Графические")})
                            }
                            if (notesSegment==0) LearningNotesDrawer(
                            notes = learningNotes, bookmarks = bookmarks,
                            onCreate = { noteEditor = LearningNote(scope = NoteScope.PAGE, number = page) },
                            onEdit = { noteEditor = it },
                            onDelete = { id -> learningDb.deleteNote(id); learningNotes = learningDb.notes() },
                            onOpen = { note ->
                                if (note.scope == NoteScope.AYAH || note.scope == NoteScope.AYAH_RANGE) {
                                    goToAyah(note.surah, note.firstAyah)
                                } else {
                                    val target = when (note.scope) {
                                        NoteScope.PAGE -> note.number
                                        NoteScope.SURAH -> QuranCatalog.surahPages.getOrNull(note.number - 1)
                                        NoteScope.JUZ -> QuranCatalog.juzPages.getOrNull(note.number - 1)
                                        NoteScope.HIZB -> QuranCatalog.hizbPages.getOrNull(note.number - 1)
                                        NoteScope.MANZIL -> QuranCatalog.manzilPages.getOrNull(note.number - 1)
                                        else -> bookmarks.firstOrNull { it.id == note.bookmarkId }?.page
                                    }
                                    if (target != null) goToPage(target)
                                }
                                scope.launch { drawerState.close() }
                            }
                            ) else Q7HGraphicNotesDrawer(db=learningDb,onOpenGraphic={ graphicPage ->
                                goToPage(graphicPage)
                                scope.launch { drawerState.close() }
                            })
                        }
                    }
                }
            }
        ) {
            // True reader mode: no Scaffold topBar/bottomBar reserves space. Only the
            // image fits the entire available viewport; controls float above the bitmap.
            Box(
                Modifier.fillMaxSize()
                    .background(canvasColor)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .windowInsetsPadding(WindowInsets.displayCutout)
                    .onSizeChanged { inkHostSize = it }
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    HorizontalPager(
                        state = pager,
                        modifier = Modifier.fillMaxSize(),
                        pageSpacing = 0.dp,
                        contentPadding = PaddingValues(0.dp),
                        beyondViewportPageCount = 1,
                        userScrollEnabled = !inkEditing
                    ) { index ->
                        val leafPage = index + 1
                        val leafStrokes = remember(leafPage) { strokesForPage(leafPage) }
                        val hiddenInkStrokes = remember(leafPage) { mutableStateListOf<InkStroke>() }
                        val bitmap by produceState<android.graphics.Bitmap?>(null, index, variant) {
                            // An asset pack may finish extracting AFTER this page first appears.
                            // Do not permanently render "missing page" after one failed lookup.
                            var retryDelay = 800L
                            while (true) {
                                val loaded = mushaf.load(index + 1, variant)
                                if (loaded != null) {
                                    value = loaded
                                    break
                                }
                                kotlinx.coroutines.delay(retryDelay)
                                retryDelay = (retryDelay * 2L).coerceAtMost(30_000L)
                            }
                        }
                        // Playback overlay is ephemeral: use Q7H Stage12 verse
                        // rectangles for this physical page, respecting theme,
                        // letter/harakat bounds, radius and border preferences.
                        // Also observe the editor's preference revision so picking a
                        // playback color updates the visible verse without restarting audio.
                        val playbackColorRevision = prefs.inkSmartSettingsRevision
                        val playbackMarkerColor = prefs.readerPlaybackHighlightColor
                        val audibleHighlight by produceState<List<InkStroke>>(
                            emptyList(), bitmap, leafPage, variant, audibleVerse,
                            playbackMarkerColor, playbackColorRevision
                        ) {
                            val image = bitmap
                            val ref = audibleVerse
                            value = if (image == null || ref == null) emptyList() else
                                withContext(Dispatchers.Default) {
                                    if (!Q7HSmartAyahV10.includes(context.applicationContext, leafPage, ref))
                                        emptyList()
                                    else runCatching {
                                        Q7HSmartAyahV10.select(
                                            context.applicationContext, mushaf, variant,
                                            image, leafPage, 0f, 0f,
                                            playbackMarkerColor, ref
                                        )?.strokes.orEmpty().map { mark ->
                                            mark.copy(text="playback:${ref.surah}:${ref.ayah}:$leafPage")
                                        }
                                    }.getOrDefault(emptyList())
                                }
                        }
                        LaunchedEffect(bitmap, page, variant) {
                            if (index + 1 == page && bitmap != null) {
                                canvasColor = Color(mushaf.backdropColor(bitmap!!))
                            }
                        }
                        // The image AND its marks live inside one pager leaf and share
                        // the same translation throughout a right-to-left swipe.
                        Box(
                            Modifier.fillMaxSize()
                                .background(canvasColor)
                                // Tap: toggle controls; long press: lock/unlock the controls.
                                // Horizontal drags are left to the pager (real RTL).
                                .pointerInput(showInk, inkEditing) {
                                    // Without visible annotations there is no ink layer
                                    // receiving taps, so keep the one-tap toolbar gesture.
                                    if(!showInk) detectTapGestures(onTap={
                                        controlsLocked=false
                                        controlsVisible=!controlsVisible
                                    })
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (bitmap != null) {
                                // Both the printed page and its annotations move together.
                                Image(
                                    bitmap = bitmap!!.asImageBitmap(),
                                    contentDescription = "Мусхаф, страница ${index + 1}",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                                if (showInk || audibleHighlight.isNotEmpty()) {
                                    val inkBackdrop = remember(bitmap) { Color(mushaf.backdropColor(bitmap!!)) }
                                    InkPageLayer(
                                        bitmap = bitmap!!,
                                        strokes = if (showInk) leafStrokes else hiddenInkStrokes,
                                        transientHighlights = audibleHighlight,
                                        tool = inkTool,
                                        inkColor = inkColor,
                                        width = inkSize,
                                        editable = inkEditing && leafPage == page,
                                        // Use the physical page backdrop, not the outgoing page.
                                        coverColor = inkBackdrop,
                                        darkPage = dark,
                                        smartPrefs = prefs,
                                        readerSmartGestures = showInk && !inkEditing && leafPage == page,
                                        readerEraser = !inkEditing && readerEraser && leafPage == page,
                                        onSingleTap = {
                                            if(!inkEditing && leafPage==page) {
                                                controlsLocked=false
                                                controlsVisible=!controlsVisible
                                            }
                                        },
                                        onEraserActivate = {
                                            if(leafPage==page) {
                                                if(inkEditing) inkTool="eraser"
                                                else {readerEraser=true; readerAyah=null}
                                                notice="Ластик включён: удаляются только графические пометки"
                                            }
                                        },
                                        onDraft = { draft ->
                                            if (leafPage == page) {
                                                inkDraft=draft
                                                val now=SystemClock.uptimeMillis()
                                                if(draft==null || now-lastInkDraftWrite>=280L) {
                                                    // Capture even an unfinished gesture before a process kill.
                                                    learningDb.saveInk(leafPage,
                                                        leafStrokes.toList()+listOfNotNull(draft))
                                                    lastInkDraftWrite=now
                                                }
                                            }
                                        },
                                        onSmartTap = { imagePoint ->
                                            if(leafPage==page) scope.launch {
                                                val requestEpoch=smartTapEpoch.incrementAndGet()
                                                val originalPage=leafPage
                                                val originalVariant=variant
                                                val originalEnvironment=smartEnvironment
                                                val tappedBitmap=bitmap ?: return@launch
                                                val originalColor=if (inkEditing) inkColor else 0xFF999999L
                                                // Geometry resolves asynchronously; success gets a brief snackbar.
                                                val selected=withContext(Dispatchers.Default) {
                                                    runCatching { Q7HSmartAyahV10.select(context.applicationContext,
                                                        mushaf,originalVariant,tappedBitmap,originalPage,
                                                        imagePoint.x,imagePoint.y,originalColor) }.getOrNull()
                                                }
                                                if (smartTapEpoch.get()!=requestEpoch ||
                                                    liveSmartEnvironment.value !== originalEnvironment ||
                                                    liveSmartPage.value!=originalPage ||
                                                    liveSmartVariant.value!=originalVariant) return@launch
                                                if(selected==null) notice="Не удалось надёжно определить аят на этой странице"
                                                else {
                                                    if (smartTapEpoch.get()!=requestEpoch ||
                                                        liveSmartEnvironment.value !== originalEnvironment ||
                                                    liveSmartPage.value!=originalPage ||
                                                        liveSmartVariant.value!=originalVariant) return@launch
                                                    // Reopening a previously colored verse must
                                                    // preserve its color, not reset it to grey.
                                                    val tag="smart:${selected.ref.surah}:${selected.ref.ayah}:$originalPage"
                                                    val savedColor=strokesForPage(originalPage)
                                                        .firstOrNull { it.tool=="smart_marker" && it.text==tag }?.color
                                                    if(savedColor!=null) {
                                                        val prefix="smart:${selected.ref.surah}:${selected.ref.ayah}:"
                                                        val affected=listOf(originalPage-1,originalPage,originalPage+1)
                                                            .filter { it in 1..604 && strokesForPage(it).any { mark ->
                                                                mark.tool=="smart_marker" && mark.text=="$prefix$it"
                                                            } }
                                                        smartUndoBefore=affected.associateWith { strokesForPage(it).toList() }
                                                        affected.forEach { targetPage ->
                                                            val list=strokesForPage(targetPage)
                                                            if(inkEditing && targetPage !in inkSessionSnapshots)
                                                                inkSessionSnapshots[targetPage]=list.toList()
                                                            if(inkEditing && targetPage==page) {
                                                                inkUndoHistory.add(list.toList())
                                                                if(inkUndoHistory.size>30) inkUndoHistory.removeAt(0)
                                                                inkRedoHistory.clear()
                                                            }
                                                            list.removeAll { it.tool=="smart_marker" &&
                                                                it.text=="$prefix$targetPage" }
                                                            learningDb.saveInk(targetPage,list.toList())
                                                        }
                                                        smartUndoAfter=affected.associateWith { strokesForPage(it).toList() }
                                                        smartRedoAfter=null;smartRedoBefore=null
                                                        inkCheckpoint=inkStrokes.toList()
                                                        readerAyah=null
                                                        showBriefAyahFeedback("Снято · ${selected.ref.surah}:${selected.ref.ayah}")
                                                        return@launch
                                                    }
                                                    val resolvedColor=savedColor?:originalColor
                                                    val markings=linkedMapOf(originalPage to selected.strokes.map {
                                                        it.copy(color=resolvedColor)
                                                    })
                                                    for(other in listOf(originalPage-1,originalPage+1)) {
                                                        if(other !in 1..604) continue
                                                        val matches=withContext(Dispatchers.IO) {
                                                            Q7HSmartAyahV10.includes(context.applicationContext,
                                                                other,selected.ref)
                                                        }
                                                        if(!matches)continue
                                                        val otherBitmap=withContext(Dispatchers.IO) {
                                                            mushaf.load(other,originalVariant)
                                                        } ?: continue
                                                        val result=withContext(Dispatchers.Default) {
                                                            runCatching { Q7HSmartAyahV10.select(context.applicationContext,
                                                                mushaf,originalVariant,otherBitmap,other,
                                                                0f,0f,originalColor,selected.ref) }.getOrNull()
                                                        }
                                                        if(result!=null) markings[other]=result.strokes.map {
                                                            it.copy(color=resolvedColor)
                                                        }
                                                    }
                                                    if (smartTapEpoch.get()!=requestEpoch ||
                                                        liveSmartEnvironment.value !== originalEnvironment ||
                                                    liveSmartPage.value!=originalPage ||
                                                        liveSmartVariant.value!=originalVariant) return@launch
                                                    smartUndoBefore=markings.keys.associateWith {
                                                        strokesForPage(it).toList()
                                                    }
                                                    markings.forEach { (targetPage,newMarks) ->
                                                        val list=strokesForPage(targetPage)
                                                        if(inkEditing && targetPage !in inkSessionSnapshots)
                                                            inkSessionSnapshots[targetPage]=list.toList()
                                                        if(inkEditing && targetPage==page) {
                                                            inkUndoHistory.add(list.toList())
                                                            if(inkUndoHistory.size>30)inkUndoHistory.removeAt(0)
                                                            inkRedoHistory.clear()
                                                        }
                                                        // Re-highlighting the SAME ayah replaces its
                                                        // previous smart marks instead of stacking copies.
                                                        // Older manually created marks are untouched.
                                                        val newIds = newMarks.map { it.text }
                                                            .filter { it.startsWith("smart:") }.toSet()
                                                        if (newIds.isNotEmpty())
                                                            list.removeAll { it.tool == "smart_marker" &&
                                                                it.text in newIds }
                                                        list.addAll(newMarks)
                                                        learningDb.saveInk(targetPage,list.toList())
                                                    }
                                                    smartUndoAfter=markings.keys.associateWith {
                                                        strokesForPage(it).toList()
                                                    }
                                                    smartRedoAfter=null;smartRedoBefore=null
                                                    inkCheckpoint=inkStrokes.toList()
                                                    if (!inkEditing) {
                                                        readerAyah=Q7HReaderSelectedAyah(selected.ref.surah,selected.ref.ayah,originalPage)
                                                        readerEraser=false
                                                        controlsVisible=false
                                                    }
                                                    showBriefAyahFeedback("Выделен · ${selected.ref.surah}:${selected.ref.ayah}")
                                                }
                                            }
                                        },
                                        onSmartClear = { imagePoint ->
                                            if(leafPage==page) scope.launch {
                                                val requestEpoch=smartTapEpoch.incrementAndGet()
                                                val originalPage=leafPage
                                                val originalVariant=variant
                                                val originalEnvironment=smartEnvironment
                                                val tappedBitmap=bitmap ?: return@launch
                                                val selected=withContext(Dispatchers.Default) {
                                                    runCatching { Q7HSmartAyahV10.select(
                                                        context.applicationContext,mushaf,
                                                        originalVariant,tappedBitmap,originalPage,
                                                        imagePoint.x,imagePoint.y,inkColor)
                                                    }.getOrNull()
                                                }
                                                if(selected==null) {
                                                    notice="Аят для удаления выделения не определён"
                                                    return@launch
                                                }
                                                val ref=selected.ref
                                                val prefix="smart:${ref.surah}:${ref.ayah}:"
                                                val affected=mutableMapOf<Int,List<InkStroke>>()
                                                // Preserve cross-page verse support, but never
                                                // touch the original Quran image or other ink tools.
                                                for(targetPage in listOf(originalPage-1,originalPage,originalPage+1)) {
                                                    if(targetPage !in 1..604)continue
                                                    val list=strokesForPage(targetPage)
                                                    if(list.none { it.tool=="smart_marker" })continue
                                                    val exactTag="$prefix$targetPage"
                                                    val known=list.filter { it.tool=="smart_marker" && it.text==exactTag }
                                                    // Old Stage12 builds saved strokes without their
                                                    // text tags. Recover only geometrically matching
                                                    // smart rectangles; other annotations are safe.
                                                    val untagged=list.filter { it.tool=="smart_marker" && it.text.isBlank() }
                                                    val expected = if(untagged.isNotEmpty()) {
                                                        if(targetPage==originalPage) selected.strokes
                                                        else {
                                                            val otherBitmap=mushaf.load(targetPage,originalVariant)
                                                            if(otherBitmap!=null) withContext(Dispatchers.Default) {
                                                                runCatching { Q7HSmartAyahV10.select(
                                                                    context.applicationContext,mushaf,
                                                                    originalVariant,otherBitmap,targetPage,
                                                                    0f,0f,inkColor,ref)
                                                                }.getOrNull()?.strokes
                                                            } ?: emptyList() else emptyList()
                                                        }
                                                    } else emptyList()
                                                    fun matchesOld(old:InkStroke):Boolean = expected.any { fresh ->
                                                        if(old.points.size<2 || fresh.points.size<2) false
                                                        else {
                                                            val oa=old.points.first();val ob=old.points.last()
                                                            val fa=fresh.points.first();val fb=fresh.points.last()
                                                            val ol=minOf(oa.x,ob.x);val or=maxOf(oa.x,ob.x)
                                                            val fl=minOf(fa.x,fb.x);val fr=maxOf(fa.x,fb.x)
                                                            val intersection=(minOf(or,fr)-maxOf(ol,fl)).coerceAtLeast(0f)
                                                            intersection >= (fr-fl)*.8f &&
                                                                kotlin.math.abs(oa.y-fa.y)<.012f
                                                        }
                                                    }
                                                    val oldMatches=untagged.filter(::matchesOld)
                                                    if(known.isNotEmpty() || oldMatches.isNotEmpty())
                                                        affected[targetPage]=known+oldMatches
                                                }
                                                if(smartTapEpoch.get()!=requestEpoch ||
                                                    liveSmartEnvironment.value !== originalEnvironment ||
                                                    liveSmartPage.value!=originalPage ||
                                                    liveSmartVariant.value!=originalVariant) return@launch
                                                if(affected.isEmpty()) {
                                                    notice="У этого аята нет сохранённого умного выделения"
                                                } else {
                                                    smartUndoBefore=affected.keys.associateWith {
                                                        strokesForPage(it).toList()
                                                    }
                                                    affected.forEach { (targetPage,entries) ->
                                                        val list=strokesForPage(targetPage)
                                                        if(inkEditing && targetPage !in inkSessionSnapshots)
                                                            inkSessionSnapshots[targetPage]=list.toList()
                                                        if(inkEditing && targetPage==page) {
                                                            inkUndoHistory.add(list.toList())
                                                            if(inkUndoHistory.size>30)inkUndoHistory.removeAt(0)
                                                            inkRedoHistory.clear()
                                                        }
                                                        list.removeAll(entries.toSet())
                                                        learningDb.saveInk(targetPage,list.toList())
                                                    }
                                                    smartUndoAfter=affected.keys.associateWith {
                                                        strokesForPage(it).toList()
                                                    }
                                                    smartRedoAfter=null;smartRedoBefore=null
                                                    inkCheckpoint=inkStrokes.toList()
                                                    if(!inkEditing && readerAyah?.surah==ref.surah && readerAyah?.ayah==ref.ayah)
                                                        readerAyah=null
                                                    showBriefAyahFeedback("Снято · ${ref.surah}:${ref.ayah}")
                                                }
                                            }
                                        },
                                        onSmartPlay = { imagePoint ->
                                            if(leafPage==page) scope.launch {
                                                val requestEpoch=smartTapEpoch.incrementAndGet()
                                                val currentPage=leafPage
                                                val selectedVariant=variant
                                                val selectedEnvironment=smartEnvironment
                                                val image=bitmap ?: return@launch
                                                val match=withContext(Dispatchers.Default) {
                                                    runCatching { Q7HSmartAyahV10.select(
                                                        context.applicationContext,mushaf,selectedVariant,
                                                        image,currentPage,imagePoint.x,imagePoint.y,inkColor)
                                                    }.getOrNull()
                                                }
                                                if(match==null) {
                                                    notice="Не удалось определить аят для воспроизведения"
                                                    return@launch
                                                }
                                                if(smartTapEpoch.get()!=requestEpoch ||
                                                    liveSmartEnvironment.value !== selectedEnvironment ||
                                                    liveSmartPage.value!=currentPage ||
                                                    liveSmartVariant.value!=selectedVariant) return@launch
                                                readerAyah=null
                                                playReaderVerse(match.ref.surah,match.ref.ayah)
                                            }
                                        },
                                        onTimedGesture = {
                                            if(leafPage==page) when(prefs.readerTimedGestureAction) {
                                                "eraser" -> {
                                                    if(inkEditing) inkTool="eraser"
                                                    else {readerEraser=true;readerAyah=null}
                                                    notice="Ластик активирован"
                                                }
                                                "drawing" -> {
                                                    if(!inkEditing) {
                                                        inkSessionBaseline=inkStrokes.toList()
                                                        inkSessionSnapshots.clear()
                                                        inkSessionSnapshots[page]=inkSessionBaseline
                                                        inkEditing=true;showInk=true;prefs.inkVisible=true
                                                    }
                                                }
                                                "controls" -> {controlsLocked=false;controlsVisible=!controlsVisible}
                                                "navigation" -> {
                                                    Q7HBottomNavSettings.enable(context,!Q7HBottomNavSettings.enabled)
                                                }
                                            }
                                        },
                                        onChanged = {
                                            if (leafPage == page) {
                                                val next = leafStrokes.toList()
                                                if (next != inkCheckpoint) {
                                                    inkUndoHistory.add(inkCheckpoint)
                                                    if (inkUndoHistory.size > 30) inkUndoHistory.removeAt(0)
                                                    inkCheckpoint = next
                                                    inkRedoHistory.clear()
                                                    learningDb.saveInk(leafPage, next)
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            } else {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.padding(16.dp)
                                ) {
                                    Icon(Icons.Default.MenuBook, null, modifier = Modifier.size(42.dp))
                                    Text("Страница ${index + 1}")
                                    Text(
                                        "Проверяю ${"%03d".format(index + 1)} в ${mushaf.folderName(variant)}. Повторная загрузка выполняется автоматически.",
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
                // Quick verse palette: one double tap on the page creates a
                // grey one-layer highlight; these chips recolor only the tagged
                // selected verse, including a continuation on the next page.
                val directAyah = readerAyah
                if (directAyah != null && sheet == null && !inkEditing) {
                    val tajweedColors=inkPalette.filter { it.tajweed }
                        .sortedBy { it.sortOrder }.take(8)
                    val extraColors=listOf(
                        if(dark) 0xFFFFFFFFL else 0xFF202020L,
                        0xFFD8C5A6L,0xFFAF72DCL
                    ) + inkPalette.filter { it.custom }.sortedBy {it.sortOrder}.map {it.argb}
                    Surface(
                        modifier=Modifier
                            .align(Alignment.BottomCenter)
                            .padding(
                                start = 10.dp,
                                end = 10.dp,
                                bottom = if(controlsVisible) 170.dp else 80.dp
                            ),
                        shape=RoundedCornerShape(24.dp),
                        color=MaterialTheme.colorScheme.surface,
                        contentColor=MaterialTheme.colorScheme.onSurface,
                        shadowElevation=9.dp,
                        tonalElevation=4.dp
                    ) {
                        Row(Modifier.widthIn(max=390.dp).padding(horizontal=7.dp,vertical=5.dp)
                            .horizontalScroll(rememberScrollState()),
                            verticalAlignment=Alignment.CenterVertically,
                            horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                            Text("${directAyah.surah}:${directAyah.ayah}",
                                style=MaterialTheme.typography.labelSmall)
                            val paletteArgb=tajweedColors.map {it.argb} + extraColors
                            paletteArgb.forEachIndexed { index, argb ->
                                if(index==tajweedColors.size) VerticalDivider(
                                    modifier=Modifier.height(28.dp).padding(start=4.dp,end=4.dp),
                                    color=MaterialTheme.colorScheme.outlineVariant)
                                Box(Modifier.size(28.dp)
                                    .background(Color(argb.toInt()),CircleShape)
                                    .border(1.dp,MaterialTheme.colorScheme.outline,CircleShape)
                                    .clickable {
                                        for(targetPage in listOf(directAyah.page-1,directAyah.page,directAyah.page+1)) {
                                            if(targetPage !in 1..604)continue
                                            val markList=strokesForPage(targetPage)
                                            val tag="smart:${directAyah.surah}:${directAyah.ayah}:$targetPage"
                                            var changed=false
                                            for(i in markList.indices) {
                                                val stroke=markList[i]
                                                if(stroke.tool=="smart_marker" && stroke.text==tag) {
                                                    markList[i]=stroke.copy(color=argb)
                                                    changed=true
                                                }
                                            }
                                            if(changed)learningDb.saveInk(targetPage,markList.toList())
                                        }
                                        readerAyah=null
                                    }
                                )
                            }
                            IconButton(onClick={
                                playReaderVerse(directAyah.surah, directAyah.ayah)
                            },modifier=Modifier.size(35.dp)) {
                                Icon(Icons.Default.PlayArrow,"Слушать этот аят",Modifier.size(23.dp))
                            }
                            IconButton(onClick={readerAyah=null},modifier=Modifier.size(34.dp)) {
                                Icon(Icons.Default.Close,"Закрыть цвета",Modifier.size(20.dp))
                            }
                        }
                    }
                }
                // Three movable shapes share the existing Tafsir Media3 channel.
                val audioRef = readerAudioAyah
                if (audioRef != null && sheet == null && !inkEditing) {
                    Q7HReaderFloatingPlayer(
                        target=audioRef.surah to audioRef.ayah,
                        hostSize=inkHostSize,
                        playback=ayahSnapshot,controller=ayahController,prefs=prefs,
                        reciters=reciters,reciterError=reciterError,
                        selectedReciter=selectedReciter,onReciter=::selectAyahReciter,
                        endMode=ayahMode,onEndMode=::selectAyahMode,
                        onPlayVerse=::playReaderVerse,
                        onClose={
                            // Close means STOP; collapsing never stops background audio.
                            pendingReaderAudio=null
                            ayahController?.pause()
                            ayahController?.stop()
                            readerAudioAyah=null
                        }
                    )
                }
                if(readerEraser && !inkEditing && sheet==null) {
                    Surface(
                        modifier=Modifier.align(Alignment.TopCenter).padding(top=16.dp),
                        color=MaterialTheme.colorScheme.secondaryContainer,
                        shape=RoundedCornerShape(16.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically,
                            modifier=Modifier.padding(start=10.dp)) {
                            Icon(Icons.Default.AutoFixOff,null,Modifier.size(18.dp))
                            Text("Ластик включён",style=MaterialTheme.typography.labelSmall)
                            TextButton(onClick={readerEraser=false}) { Text("Выключить") }
                        }
                    }
                }
                // Telegram-like reveal applies only to light/dark changes.
                // Ordinary page swipes are the default, undistorted HorizontalPager.
                themeReveal?.let { frame ->
                    TelegramThemeReveal(
                        frame = frame,
                        progress = themeRevealProgress.value,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                // Always in a measured overlay; panel and square are clamped to viewport.
                if(inkEditing) {
                    val bounds = q7hInkPositionBounds(
                        inkHostSize, inkPanelSize, inkToolbarState == "square",
                        inkDensity.density
                    )
                    val currentX = if (inkToolbarState == "square") inkSquareX else inkPanelX
                    val currentY = if (inkToolbarState == "square") inkSquareY else inkPanelY
                    Box(Modifier.align(Alignment.TopStart)
                        .offset { IntOffset(bounds.xAt(currentX).toInt(), bounds.yAt(currentY).toInt()) }
                        .onSizeChanged { inkPanelSize=it }) {
                        InkToolbar(tool=inkTool,onTool={chosen ->
                            inkTool=chosen
                            inkSize=when(chosen) {
                                "marker","marker_ruler","marker_single",
                                "marker_ruler_single","smart_marker" -> .04f
                                "cover" -> .10f
                                "pen","pen_ruler" -> .008f
                                else -> .010f
                            }
                            prefs.inkStrokeSize=inkSize
                        },
                        color=inkColor,onColor={inkColor=it;prefs.inkSelectedColor=it},
                        thickness=inkSize,onThickness={inkSize=it;prefs.inkStrokeSize=it},
                        palette=inkPalette,pinnedTajweed=prefs.inkPinTajweedRow,
                        orderedTools=prefs.inkToolOrder,
                        smartPrefs=prefs,
                        onCreateColor={chosen,meaning ->
                            learningDb.addInkColor(chosen,meaning)
                            inkPalette=learningDb.inkColors()
                            inkColor=chosen;prefs.inkSelectedColor=chosen
                        },
                        onSave={
                            learningDb.saveInk(page,inkStrokes.toList()+listOfNotNull(inkDraft))
                            inkDraft=null;inkEditing=false
                            inkSessionSnapshots.clear()
                            smartUndoBefore=null;smartUndoAfter=null
                            smartRedoBefore=null;smartRedoAfter=null
                        },
                        canUndo=inkUndoHistory.isNotEmpty(),canRedo=inkRedoHistory.isNotEmpty(),
                        canClear=inkStrokes.isNotEmpty(),
                        state=inkToolbarState,
                        collapsedSlider=prefs.inkCollapsedSlider,
                        collapsedTools=prefs.inkCollapsedTools,
                        collapsedTajweed=prefs.inkCollapsedTajweed,
                        doubleTapSquare=prefs.inkDoubleTapSquare,
                        longPressCompact=prefs.inkLongPressCompact,
                        onCollapse={
                            inkToolbarState=if(inkToolbarState=="collapsed")"expanded" else "collapsed"
                            persistInkPosition()
                        },
                        onMinimize={
                            inkToolbarState=if(inkToolbarState=="square")"expanded" else "square"
                            // Reuse the stored floating-square coordinates on every minimize.
                            // Do not reset the square to the bottom-right on each toggle.
                            persistInkPosition()
                        },
                        onVerticalDrag={dp -> inkMove(0f,with(inkDensity){dp.dp.toPx()})},
                        onHorizontalDrag={dp -> inkMove(with(inkDensity){dp.dp.toPx()},0f)},
                        onDragFinished={persistInkPosition()},
                        maxPanelHeight = if (inkHostSize.height > 0)
                            with(inkDensity) { inkHostSize.height.toDp() - 10.dp }
                                .coerceAtLeast(150.dp)
                        else 440.dp,
                        onUndo = {
                            val before=smartUndoBefore
                            val after=smartUndoAfter
                            if(before!=null && after?.get(page)==inkStrokes.toList()) {
                                before.forEach { (changedPage, marks) ->
                                    val target=strokesForPage(changedPage)
                                    target.clear();target.addAll(marks)
                                    learningDb.saveInk(changedPage,marks)
                                }
                                if(inkUndoHistory.isNotEmpty())inkUndoHistory.removeAt(inkUndoHistory.lastIndex)
                                inkCheckpoint=inkStrokes.toList()
                                smartRedoBefore=before
                                smartRedoAfter=after
                                smartUndoBefore=null;smartUndoAfter=null
                            } else if (inkUndoHistory.isNotEmpty()) {
                                inkRedoHistory.add(inkStrokes.toList())
                                val prior = inkUndoHistory.removeAt(inkUndoHistory.lastIndex)
                                inkStrokes.clear(); inkStrokes.addAll(prior)
                                inkCheckpoint = prior
                                learningDb.saveInk(page, prior)
                            }
                        },
                        onRedo = {
                            val redo=smartRedoAfter
                            if(redo!=null && smartRedoBefore!=null && redo.keys.all { key ->
                                strokesForPage(key).toList()==smartRedoBefore?.get(key)
                            }) {
                                smartUndoBefore=redo.keys.associateWith { strokesForPage(it).toList() }
                                inkUndoHistory.add(inkStrokes.toList())
                                redo.forEach { (changedPage, marks) ->
                                    val target=strokesForPage(changedPage)
                                    target.clear();target.addAll(marks)
                                    learningDb.saveInk(changedPage,marks)
                                }
                                inkCheckpoint=inkStrokes.toList()
                                smartUndoAfter=redo;smartRedoAfter=null;smartRedoBefore=null
                            } else if (inkRedoHistory.isNotEmpty()) {
                                inkUndoHistory.add(inkStrokes.toList())
                                val redo = inkRedoHistory.removeAt(inkRedoHistory.lastIndex)
                                inkStrokes.clear(); inkStrokes.addAll(redo)
                                inkCheckpoint = redo
                                learningDb.saveInk(page, redo)
                            }
                        }, onClear = { clearInkDialog = true })
                    }
                }

                if (!inkEditing) AnimatedVisibility(
                    visible = controlsVisible && !controlsLocked,
                    enter = fadeIn(), exit = fadeOut(),
                    modifier = Modifier.fillMaxSize()
                ) {
                    val actionSlot: @Composable (String) -> Unit = { key ->
                        when(key) {
                            "panel" -> IconButton(onClick = { switchSheet(preferredSheet) }) {
                                Icon(Icons.Default.KeyboardArrowUp,"Открыть панель")
                            }
                            "contents" -> IconButton(onClick = {
                                drawerTab=DrawerSection.CONTENTS
                                scope.launch { drawerState.open() }
                            }) { Icon(Icons.Default.FormatListBulleted,"Содержание") }
                            "theme" -> IconButton(onClick = {
                                changeAppearance(nextMode=if(dark) "light" else "dark")
                            }) { Icon(if(dark) Icons.Default.LightMode else Icons.Default.DarkMode,"Сменить тему") }
                            "layers" -> IconButton(onClick = {
                                showInk=!showInk
                                prefs.inkVisible=showInk
                                if(!showInk) inkEditing=false
                            }) { Icon(if(showInk) Icons.Default.Layers else Icons.Default.LayersClear,
                                if(showInk) "Чистая страница" else "Показать пометки") }
                            "bookmark" -> {
                    Box {
                        IconButton(onClick = { bookmarkMenu = true }) {
                            val saved = bookmarks.firstOrNull { it.page == page }
                            if (saved != null) LearningBookmarkIcon(
                                icon = saved.type.icon, tint = Color(saved.type.color.toInt()),
                                modifier = Modifier.size(24.dp))
                            else Icon(Icons.Default.BookmarkBorder, "Закладка")
                        }
                        LearningBookmarkMenu(
                            expanded = bookmarkMenu, page = page,
                            types = if(bookmarkMenu) learningDb.types() else bookmarkTypes,
                            bookmarks = bookmarks,
                            onDismiss = { bookmarkMenu = false },
                            onToggle = { type ->
                                val current = bookmarks.firstOrNull { it.page == page && it.typeId == type.id }
                                if (current != null) {
                                    learningNotes.filter { it.bookmarkId == current.id }.forEach { note ->
                                        learningDb.saveNote(note.copy(
                                            bookmarkId = null,
                                            scope = if(note.scope == NoteScope.NONE) NoteScope.PAGE else note.scope,
                                            number = if(note.scope == NoteScope.NONE) current.page else note.number
                                        ))
                                    }
                                    learningDb.removeBookmark(current.id)
                                } else learningDb.addBookmark(page, type.id)
                                bookmarks = learningDb.bookmarks()
                                learningNotes = learningDb.notes()
                                bookmarkMenu = false
                            },
                            onEdit = { typeEditor = it; bookmarkMenu = false },
                            onCreate = { newTypeEditor = true; bookmarkMenu = false },
                            onRemove = {
                                // Remove all bookmarks attached to the CURRENT page;
                                // bookmarks elsewhere and notes are left untouched.
                                bookmarks.filter { it.page == page }.forEach { mark ->
                                    learningNotes.filter { it.bookmarkId == mark.id }.forEach { note ->
                                        learningDb.saveNote(note.copy(
                                            bookmarkId = null,
                                            scope = if(note.scope == NoteScope.NONE) NoteScope.PAGE else note.scope,
                                            number = if(note.scope == NoteScope.NONE) mark.page else note.number
                                        ))
                                    }
                                    learningDb.removeBookmark(mark.id)
                                }
                                bookmarks = learningDb.bookmarks()
                                learningNotes = learningDb.notes()
                                bookmarkMenu = false
                            }
                        )
                    }
                            }
                            "tajweed" -> IconButton(onClick={changeAppearance(nextTajweed=!tajweed)}) {
                                Icon(if(tajweed) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    if(tajweed) "Отключить таджвид" else "Включить таджвид")
                            }
                            "goto" -> IconButton(onClick={showToolbarPageJump=true}) {
                                Icon(Icons.Default.FindInPage,"Перейти")
                            }
                            "draw" -> IconButton(onClick={
                                if(!inkEditing) {
                                    inkSessionBaseline=inkStrokes.toList()
                                    inkSessionSnapshots.clear()
                                    inkSessionSnapshots[page]=inkSessionBaseline
                                    smartUndoBefore=null;smartUndoAfter=null
                                    smartRedoBefore=null;smartRedoAfter=null
                                    inkEditing=true
                                    showInk=true; prefs.inkVisible=true
                                } else {
                                    learningDb.saveInk(page,inkStrokes.toList()+listOfNotNull(inkDraft))
                                    inkDraft=null;inkEditing=false
                                    inkSessionSnapshots.clear()
                                    smartUndoBefore=null;smartUndoAfter=null
                                    smartRedoBefore=null;smartRedoAfter=null
                                }
                            }) { Icon(if(inkEditing) Icons.Default.Check else Icons.Default.Edit,
                                if(inkEditing) "Завершить пометки" else "Рисовать на странице") }
                        }
                    }
                    Q7HFloatingMushafToolbar(
                        bottomEnabled=bottomNavigation, inkEditing=inkEditing,
                        readerDark=dark, action=actionSlot
                    )
                }
                AnimatedVisibility(
                    visible = !bottomNavigation && controlsVisible && !controlsLocked && !inkEditing,
                    enter = fadeIn(), exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
                ) {
                    Surface(
                        onClick = { sheet = if (sheetVisibility.value.includes(preferredSheet))
                            preferredSheet else SheetSection.SETTINGS },
                        modifier = Modifier.size(width = 52.dp, height = 28.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.96f),
                        shape = RoundedCornerShape(18.dp),
                        shadowElevation = 2.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.KeyboardArrowUp,
                                contentDescription = "Открыть панель аудио, тафсира и настроек",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
                AnimatedVisibility(
                    visible = ayahFeedback.isNotBlank(),
                    enter = fadeIn(), exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .padding(start=20.dp, end=20.dp,
                            bottom=if(readerAyah!=null) 148.dp else 75.dp)
                ) {
                    Snackbar(
                        modifier = Modifier.widthIn(max=320.dp),
                        shape = RoundedCornerShape(14.dp),
                        containerColor = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface
                    ) { Text(ayahFeedback, style=MaterialTheme.typography.bodySmall) }
                }
                if (notice.isNotBlank()) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopCenter)
                            .fillMaxWidth().padding(top = 62.dp, start = 8.dp, end = 8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                notice, modifier = Modifier.weight(1f).padding(10.dp),
                                style = MaterialTheme.typography.bodySmall
                            )
                            IconButton(onClick = { notice = "" }) {
                                Icon(Icons.Default.Close, "Закрыть уведомление")
                            }
                        }
                    }
                }
            }
        }
        if (typeEditor != null || newTypeEditor) {
            val existing = typeEditor
            BookmarkTypeEditor(initial = existing,
                onDismiss = { typeEditor = null; newTypeEditor = false },
                onSave = { name, purpose, color, icon ->
                    learningDb.saveType(existing?.id, name, purpose, color, icon)
                    bookmarkTypes = learningDb.types()
                    bookmarks = learningDb.bookmarks()
                    typeEditor = null; newTypeEditor = false
                },
                onDelete = existing?.takeIf { it.custom }?.let { removable ->
                    ({
                        learningDb.deleteCustomType(removable.id)
                        bookmarkTypes = learningDb.types()
                        bookmarks = learningDb.bookmarks()
                        learningNotes = learningDb.notes()
                        typeEditor = null
                    })
                })
        }
        noteEditor?.let { editing ->
            LearningNoteEditor(initial = editing, bookmarks = bookmarks,
                onDismiss = { noteEditor = null }, onSave = { result ->
                    learningDb.saveNote(result)
                    learningNotes = learningDb.notes()
                    noteEditor = null
                })
        }
        if (clearInkDialog) AlertDialog(onDismissRequest = { clearInkDialog = false },
            title = { Text("Удалить все пометки?") },
            text = { Text("Будут удалены только рисунки на странице $page. Исходный мусхаф останется неизменным.") },
            confirmButton = { TextButton(onClick = {
                inkUndoHistory.add(inkStrokes.toList())
                if (inkUndoHistory.size > 30) inkUndoHistory.removeAt(0)
                inkStrokes.clear(); inkRedoHistory.clear()
                inkCheckpoint = emptyList()
                learningDb.saveInk(page, emptyList())
                clearInkDialog = false
            }) { Text("Очистить") } },
            dismissButton = { TextButton(onClick = { clearInkDialog = false }) { Text("Отмена") } })
        if (sheet != null) ModalBottomSheet(
            // Always pinned: the reader must never accidentally dismiss a
            // long tafsir or the audio catalog with a vertical swipe/tap.
            onDismissRequest = {},
            sheetState = sheetState,
            sheetGesturesEnabled = false,
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
            dragHandle = {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    if(!bottomNavigation && Q7HReaderOptions.topClose) {
                        IconButton(onClick = { sheet = null }) {
                            Icon(Icons.Default.Close, "Закрыть панель")
                        }
                    }
                }
            }
        ) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.93f)) {
                if (!bottomNavigation) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        SingleChoiceSegmentedButtonRow(
                            Modifier.width((visibleSections.size * 134).dp).padding(horizontal = 8.dp, vertical = 5.dp)
                        ) {
                            visibleSections.forEachIndexed { index, (target, title) ->
                                    SegmentedButton(selected = sheet == target, onClick = {
                                        preferredSheet = target
                                        prefs.lastSheetSection = target.name
                                        sheet = target
                                    }, shape = SegmentedButtonDefaults.itemShape(index, visibleSections.size)) {
                                        Text(title, maxLines = 1, fontSize = 12.sp)
                                    }
                                }
                        }
                    }
                    HorizontalDivider()
                }
                // Sheet content consumes remaining space; the dock sits BELOW audio mini players.
                Box(Modifier.weight(1f).fillMaxWidth()) {
                when (sheet) {
                    SheetSection.AUDIO -> AudioPanel(
                        bottomNavEnabled = bottomNavigation,
                        rangeRequest = if(bottomRangeSection==SheetSection.AUDIO) bottomRangeSerial else 0,
                        onRangeRequestConsumed = { bottomRangeSerial = 0 },
                        category = audioSegment,
                        onCategory = { selected ->
                            audioSegment = selected
                            prefs.lastAudioSegment = selected.name
                        },
                        prefs = prefs,
                        playback = hadrSnapshot, controller = hadrController,
                        timings = timings, activeSurah = playingSurah, currentPage = page,
                        onPlay = {
                            if (hadrSnapshot.mediaId != "hadr") beginHadr()
                            else if (hadrSnapshot.playing) hadrController?.pause()
                            else if (hadrSnapshot.error != null) {
                                // Keep the last valid position and reopen the source.
                                beginHadr(hadrSnapshot.positionMs)
                            } else {
                                if (hadrSnapshot.ended) hadrController?.seekTo(0L)
                                if (hadrController?.playbackState == Player.STATE_IDLE) hadrController?.prepare()
                                hadrController?.play()
                            }
                        },
                        onRangePlay = { segment, from, to, mode ->
                            val start = timings.seekMs(segment,from)
                            val finish = timings.seekMs(segment,to)
                            val after = timings.seekMs(segment,to+1)
                            val isLast = timings.marks(segment).lastOrNull()?.number == to
                            if (start == null || finish == null ||
                                (after == null && !isLast) ||
                                (after != null && after <= start)) {
                                notice = "Для этого отрезка не хватает корректных таймингов"
                            } else if (Q7HRangeState.hadr(context,segment,from,to,mode)) {
                                prefs.setHadrActiveSegment(segment.name,from)
                                if (hadrSnapshot.mediaId != "hadr" || hadrSnapshot.error != null)
                                    beginHadr(start)
                                else if (hadrSnapshot.seekable) {
                                    hadrController?.seekTo(HadrSeekPolicy.clampMs(start,hadrSnapshot.durationMs))
                                    hadrController?.play()
                                } else beginHadr(start)
                            }
                        },
                        onJump = { segment, index ->
                            val time = timings.seekMs(segment, index)
                            if (time == null) notice = "Тайминг ${segment.title.lowercase()} №$index пока не задан"
                            else {
                                Q7HRangeState.clearHadr(context)
                                prefs.setHadrActiveSegment(segment.name, index)
                                if (hadrSnapshot.mediaId != "hadr" || hadrSnapshot.error != null) {
                                    beginHadr(time)
                                } else if (!hadrSnapshot.seekable) {
                                    notice = "Запись MP3 ещё не готова к перемотке"
                                } else {
                                    hadrController?.seekTo(HadrSeekPolicy.clampMs(time, hadrSnapshot.durationMs))
                                }
                            }
                        }
                    )
                    SheetSection.TAFSIR -> TafsirPanel(
                        bottomNavEnabled = bottomNavigation,
                        rangeRequest = if(bottomRangeSection==SheetSection.TAFSIR) bottomRangeSerial else 0,
                        onRangeRequestConsumed = { bottomRangeSerial = 0 },
                        prefs = prefs,
                        page = page, store = tafsir,
                        quranScript = quranScript, tajweed = tajweed,
                        wordStore = wordStore,
                        playback = ayahSnapshot, controller = ayahController,
                        reciters = reciters, reciterError = reciterError,
                        selectedReciter = selectedReciter,
                        onReciter = ::selectAyahReciter,
                        endMode = ayahMode, onEndMode = ::selectAyahMode,
                        onPlayVerse = ::playVerse,
                        onNoteAyah = { s, a ->
                            noteEditor = learningNotes.firstOrNull {
                                it.scope == NoteScope.AYAH && it.surah == s && it.firstAyah == a
                            } ?: LearningNote(scope = NoteScope.AYAH, surah = s, firstAyah = a, lastAyah = a)
                        },
                        onQuoteAyah=::quoteIntoNote,
                        database=learningDb,
                        jumpToAyah=Q7HAyahJumpBus.pending,
                        onNavigate={showToolbarPageJump=true},
                        onPanelClose={sheet=null},
                        onPanelSection=::switchSheet,
                        onPlayRange = { fromSurah, fromAyah, toSurah, toAyah, mode ->
                            val intendedReciter = if (mode == AyahEndMode.ALL_RECITERS) {
                                reciters.firstOrNull { it.repository == prefs.manuallySelectedReciter }
                                    ?: reciters.firstOrNull {
                                        it.repository.removePrefix("q7h_").lowercase() in
                                            setOf("shatri","abubakrshatri","abubakrashshaatree")
                                    } ?: reciters.firstOrNull()
                            } else {
                                reciters.firstOrNull { it.repository == selectedReciter }
                                    ?: reciters.firstOrNull()
                            }
                            if (intendedReciter == null || ayahController == null) {
                                notice = "Плеер аятов или чтецы ещё не загружены"
                            } else {
                                ayahMode = mode
                                prefs.ayahEndMode = mode.name
                                Q7HRangeOverlay.start(SheetSection.TAFSIR)
                                ayahConnection.playAyahRange(intendedReciter,fromSurah,fromAyah,
                                    toSurah,toAyah,mode)
                            }
                        },
                        onPlayPreface = { part: PrefacePart ->
                            if (ayahController == null) {
                                notice = "Плеер аятов ещё подключается"
                            } else if (ayahSnapshot.mediaId == "preface:${part.name}" && ayahSnapshot.playing) {
                                ayahController?.pause()
                            } else {
                                val exists = runCatching {
                                    context.assets.open("quran/${part.fileName}").use { true }
                                }.getOrDefault(false)
                                if (!exists) {
                                    notice = "Аудиозапись недоступна"
                                } else {
                                    ayahConnection.playPreface(part)
                                }
                            }
                        },
                        onPrevious = { goToPage(page - 1) },
                        onNext = { goToPage(page + 1) }
                    )
                    SheetSection.HAFIZ -> HafizDiaryPanelEnhanced(
                        bottomNavEnabled = bottomNavigation,
                        rangeRequest = if(bottomRangeSection==SheetSection.HAFIZ) bottomRangeSerial else 0,
                        onRangeRequestConsumed = { bottomRangeSerial = 0 },
                        page = page, tafsir = tafsir, prefs = prefs, db = learningDb,
                        tajweed = tajweed,wordStore=wordStore,
                        playback=diarySnapshot, controller=diaryController,
                        reciters=reciters,reciterError=reciterError,
                        selectedReciter=selectedReciter,
                        onReciter={ chosen ->
                            selectedReciter=chosen
                            prefs.selectedReciter=chosen
                            if(diarySnapshot.playing && diarySnapshot.mediaId.startsWith("ayah:")) {
                                val parts=diarySnapshot.mediaId.split(':')
                                val reciter=reciters.firstOrNull { it.repository==chosen }
                                if(parts.size>=3 && reciter!=null) {
                                    val s=parts[1].toIntOrNull()
                                    val a=parts[2].toIntOrNull()
                                    if(s!=null && a!=null) diaryConnection.playAyah(reciter,s,a,diaryMode)
                                }
                            }
                        },
                        endMode=diaryMode,onEndMode={ next ->
                            if(separateHafiz) {
                                hafizMode=next; prefs.hafizEndMode=next.name
                            } else {
                                ayahMode=next; prefs.ayahEndMode=next.name
                            }
                            diaryConnection.setAyahEndMode(next)
                        },
                        onPlayVerse=::playDiaryVerse,
                        onNoteAyah = { s, a ->
                            noteEditor = learningNotes.firstOrNull {
                                it.scope == NoteScope.AYAH && it.surah == s && it.firstAyah == a
                            } ?: LearningNote(scope = NoteScope.AYAH, surah = s, firstAyah = a, lastAyah = a)
                        },
                        onQuoteAyah=::quoteIntoNote,
                        jumpToAyah=Q7HAyahJumpBus.pending,
                        onNavigate={showToolbarPageJump=true},
                        onPanelClose={sheet=null},
                        onPanelSection=::switchSheet,
                        onPlayRange={ fromS,fromA,toS,toA,mode ->
                            val reciter=reciters.firstOrNull { it.repository==selectedReciter }
                            if(reciter == null || diaryController == null) {
                                notice="Плеер Дневника хафиза или чтецы не загружены"
                            } else {
                                Q7HRangeOverlay.start(SheetSection.HAFIZ)
                                if(separateHafiz) {
                                    hafizMode=mode; prefs.hafizEndMode=mode.name
                                } else {
                                    ayahMode=mode; prefs.ayahEndMode=mode.name
                                }
                                diaryConnection.playAyahRange(reciter,fromS,fromA,toS,toA,mode)
                            }
                        },
                        onPrevious = { goToPage(page - 1) },
                        onNext = { goToPage(page + 1) }
                    )
                    SheetSection.SETTINGS -> SettingsPanel(
                        prefs = prefs,
                        mode = themeMode, setMode = { changeAppearance(nextMode = it) },
                        palette = palette, setPalette = { palette = it; prefs.palette = it },
                        contrast = contrast, setContrast = { contrast = it; prefs.contrastLevel = it },
                        tajweed = tajweed, setTajweed = { changeAppearance(nextTajweed = it) },
                        autoFlip = autoFlip, setAutoFlip = { autoFlip = it; prefs.autoFlip = it },
                        keepAwake = keepAwake, setKeepAwake = { keepAwake = it; prefs.keepAwake = it },
                        separateHafiz=separateHafiz, setSeparateHafiz={ enabled ->
                            // Stop the previous channel when changing routing, not the other player's state.
                            if(enabled!=separateHafiz) {
                                Q7HRangeOverlay.close(context,
                                    if(separateHafiz) AudioChannel.HAFIZ else AudioChannel.AYAH,
                                    if(separateHafiz) hafizController else ayahController)
                                separateHafiz=enabled
                                prefs.separateHafizPlayer=enabled
                            }
                        },
                        reciters = reciters,
                        mushaf = mushaf
                    )
                    null -> Unit
                }
                }
                if (bottomNavigation) Q7HBottomSheetDock(
                    section = sheet ?: SheetSection.AUDIO,
                    onSection = ::switchSheet,
                    onClose = { bottomRangeSerial = 0; sheet = null },
                    page = page,
                    onPrevious = { goToPage(page - 1) },
                    onNext = { goToPage(page + 1) },
                    onNavigate = { showToolbarPageJump = true },
                    audioCategory = audioSegment,
                    onAudioCategory = { selected ->
                        audioSegment = selected
                        prefs.lastAudioSegment = selected.name
                    },
                    onRange = { sheet?.let(::requestBottomRange) },
                    readerDark = dark
                )
            }
        }
        if (showToolbarPageJump) {
            PageJumpDialog(
                initialPage = page,
                descriptions = catalogDetails,
                onDismiss = { showToolbarPageJump = false },
                onGo = { target ->
                    showToolbarPageJump = false
                    goToPage(target)
                    controlsVisible = false
                },
                onGoAyah = { surah, ayah ->
                    showToolbarPageJump = false
                    goToAyah(surah, ayah)
                }
            )
        }
    }
}
