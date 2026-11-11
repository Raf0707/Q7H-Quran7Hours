package raf.console.quran7hours.ui

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
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
        setContent { Quran7HoursScreen(this) }
    }
}

internal enum class SheetSection { AUDIO, TAFSIR, SETTINGS }
private enum class DrawerSection { CONTENTS, BOOKMARKS }

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Quran7HoursScreen(activity: MainActivity) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { UserPreferences(context) }
    val mushaf = remember { MushafStore(context) }
    val bookmarksStore = remember { BookmarkStore(context) }
    val catalogDetails = remember { CatalogDetails(context) }
    val tafsir = remember { TafsirStore(context) }
    val wordStore = remember { WordByWordStore(context) }
    val timings = remember { TimingCatalog(context) }
    val hadrConnection = remember { AudioConnection(context, AudioChannel.HADR) }
    val ayahConnection = remember { AudioConnection(context, AudioChannel.AYAH) }

    var hadrController by remember { mutableStateOf<MediaController?>(null) }
    var ayahController by remember { mutableStateOf<MediaController?>(null) }
    var hadrSnapshot by remember { mutableStateOf(PlaybackSnapshot()) }
    var ayahSnapshot by remember { mutableStateOf(PlaybackSnapshot()) }
    var reciters by remember { mutableStateOf<List<Reciter>>(emptyList()) }
    var reciterError by remember { mutableStateOf("") }
    var recitersRequested by remember { mutableStateOf(false) }
    var selectedReciter by remember { mutableStateOf(prefs.selectedReciter) }
    var ayahMode by remember { mutableStateOf(AyahEndMode.entries.firstOrNull { it.name == prefs.ayahEndMode } ?: AyahEndMode.STOP) }

    // The displayed sheet is nullable; the selected section is independent and persistent.
    var sheet by remember { mutableStateOf<SheetSection?>(null) }
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
    var bookmarks by remember { mutableStateOf(bookmarksStore.read()) }
    var bookmarkMenu by remember { mutableStateOf(false) }
    var showToolbarPageJump by rememberSaveable { mutableStateOf(false) }
    var drawerTab by remember { mutableStateOf(DrawerSection.CONTENTS) }
    var drawerSegment by remember { mutableStateOf(QuranCatalog.Segment.SURAH) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val pager = rememberPagerState(initialPage = prefs.lastPage - 1, pageCount = { QuranCatalog.TOTAL_PAGES })
    val page = pager.currentPage + 1
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) { "dark" -> true; "light" -> false; else -> systemDark }
    val variant = mushaf.variant(dark, tajweed)
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
    DisposableEffect(Unit) { onDispose { hadrConnection.close(); ayahConnection.close() } }

    LaunchedEffect(hadrController, ayahController) {
        while (isActive) {
            hadrSnapshot = snapshot(hadrController)
            ayahSnapshot = snapshot(ayahController)
            delay(450)
        }
    }
    // Load the HTTPS-only reciter list only while Tafsir is visible. A cancelled request
    // (the user closes the sheet) can be retried upon reopening.
    LaunchedEffect(sheet) {
        if (sheet != SheetSection.TAFSIR || recitersRequested || reciters.isNotEmpty()) return@LaunchedEffect
        recitersRequested = true
        try {
            ReciterRepository().list().fold(
                onSuccess = { list ->
                    reciters = list
                    reciterError = if (list.size == 43) "" else "Получено ${list.size} репозиториев вместо 43"
                    if (list.isNotEmpty() && list.none { it.repository == selectedReciter }) {
                        selectedReciter = list.first().repository
                        prefs.selectedReciter = selectedReciter
                    }
                },
                onFailure = { reciterError = it.localizedMessage ?: "Ошибка загрузки чтецов" }
            )
        } finally {
            if (reciters.isEmpty()) recitersRequested = false
        }
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
            notice = "Не найден несжатый assets/quran/quran_7_hours.mp3. " +
                "Проверьте файл и androidResources.noCompress в Gradle."
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
                    notice = "Не найден аят $surah:$ayah в JSON тафсира"
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
    fun playVerse(surah: Int, verse: Int) {
        val reciter = reciters.firstOrNull { it.repository == selectedReciter }
        if (reciter == null) { notice = "Выберите чтеца, проверьте подключение к сети"; return }
        if (ayahController == null) { notice = "Плеер аятов ещё подключается"; return }
        val mediaId = "ayah:$surah:$verse:${reciter.repository}:${reciter.branch}"
        if (ayahSnapshot.mediaId == mediaId) {
            if (ayahSnapshot.playing) ayahController?.pause()
            else {
                if (ayahSnapshot.ended) ayahController?.seekTo(0L)
                ayahController?.play()
            }
        } else ayahConnection.playAyah(reciter, surah, verse, ayahMode)
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
                        listOf(DrawerSection.CONTENTS to "Содержание", DrawerSection.BOOKMARKS to "Закладки")
                            .forEachIndexed { index, (target, title) ->
                                SegmentedButton(selected = drawerTab == target, onClick = { drawerTab = target },
                                    shape = SegmentedButtonDefaults.itemShape(index, 2)) { Text(title) }
                            }
                    }
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider()
                    if (drawerTab == DrawerSection.CONTENTS) NavigationContent(
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
                    ) else ColoredBookmarksContent(bookmarks,
                        onSelect = { goToPage(it); scope.launch { drawerState.close() } },
                        onRemove = { bookmarks = bookmarksStore.remove(it) })
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
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    HorizontalPager(
                        state = pager,
                        modifier = Modifier.fillMaxSize(),
                        pageSpacing = 0.dp,
                        contentPadding = PaddingValues(0.dp),
                        beyondViewportPageCount = 1,
                        userScrollEnabled = true
                    ) { index ->
                        val bitmap by produceState<android.graphics.Bitmap?>(null, index, variant) {
                            value = mushaf.load(index + 1, variant)
                        }
                        LaunchedEffect(bitmap, page, variant) {
                            if (index + 1 == page && bitmap != null) {
                                canvasColor = Color(mushaf.backdropColor(bitmap!!))
                            }
                        }
                        // Pager handles the touch gesture only. The OVERLAPPING leaf is
                        // rendered once above it, never in the Pager's sliding rectangles.
                        Box(
                            Modifier.fillMaxSize()
                                .background(canvasColor)
                                // Tap: toggle controls; long press: lock/unlock the controls.
                                // Horizontal drags are left to the pager (real RTL).
                                .pointerInput(controlsVisible, controlsLocked) {
                                    detectTapGestures(
                                        onTap = { if (!controlsLocked) controlsVisible = !controlsVisible },
                                        onLongPress = {
                                            if (controlsLocked) {
                                                controlsLocked = false
                                                controlsVisible = true
                                            } else {
                                                controlsVisible = false
                                                controlsLocked = true
                                            }
                                        }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (bitmap != null) {
                                // Exact unwarped bitmap beneath the overlay. The overlay
                                // covers BOTH pages while the book leaf is being turned.
                                Image(
                                    bitmap = bitmap!!.asImageBitmap(),
                                    contentDescription = "Мусхаф, страница ${index + 1}",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            } else {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.padding(16.dp)
                                ) {
                                    Icon(Icons.Default.MenuBook, null, modifier = Modifier.size(42.dp))
                                    Text("Страница ${index + 1}")
                                    Text(
                                        "Не найдена ${"%03d".format(index + 1)} в ${mushaf.folderName(variant)}",
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
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
                AnimatedVisibility(
                    visible = controlsVisible && !controlsLocked,
                    enter = fadeIn(), exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 7.dp, end = 7.dp)
                ) {
                    Surface(
                        color = if (dark) Color.Black.copy(alpha = 0.68f)
                            else Color.White.copy(alpha = 0.90f),
                        contentColor = if (dark) Color.White else Color(0xFF172024),
                        shape = RoundedCornerShape(22.dp),
                        shadowElevation = 2.dp
                    ) {
                        // All five buttons are grouped on the RIGHT in one compact row.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                drawerTab = DrawerSection.CONTENTS
                                scope.launch { drawerState.open() }
                            }) {
                                Icon(Icons.Default.FormatListBulleted, "Содержание и закладки",
                                    tint = if (dark) Color.White else Color(0xFF172024))
                            }
                            IconButton(onClick = { showToolbarPageJump = true }) {
                                Icon(Icons.Default.FindInPage, "Перейти",
                                    tint = if (dark) Color.White else Color(0xFF172024))
                            }
                            Box {
                                IconButton(onClick = { bookmarkMenu = true }) {
                                    val saved = bookmarks.firstOrNull { it.page == page }
                                    Icon(
                                        if (saved == null) Icons.Default.BookmarkBorder else Icons.Default.Bookmark,
                                        "Закладка страницы",
                                        tint = saved?.let { Color(it.color.toInt()) }
                                            ?: if (dark) Color.White else Color(0xFF172024)
                                    )
                                }
                                DropdownMenu(
                                    expanded = bookmarkMenu,
                                    onDismissRequest = { bookmarkMenu = false }
                                ) {
                                    Text("Закладка · страница $page", modifier = Modifier.padding(12.dp))
                                    bookmarksStore.palette.forEach { (name, argb) ->
                                        DropdownMenuItem(
                                            text = {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                                ) {
                                                    Box(
                                                        Modifier.size(22.dp)
                                                            .background(Color(argb.toInt()), RoundedCornerShape(50))
                                                    )
                                                    Text(name)
                                                }
                                            },
                                            onClick = {
                                                bookmarks = bookmarksStore.put(page, argb)
                                                bookmarkMenu = false
                                            }
                                        )
                                    }
                                    if (bookmarks.any { it.page == page }) {
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text("Удалить закладку") },
                                            leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                                            onClick = {
                                                bookmarks = bookmarksStore.remove(page)
                                                bookmarkMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = { changeAppearance(nextTajweed = !tajweed) }) {
                                Icon(
                                    if (tajweed) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    if (tajweed) "Отключить таджвид" else "Включить таджвид",
                                    tint = if (dark) Color.White else Color(0xFF172024)
                                )
                            }
                            IconButton(onClick = {
                                changeAppearance(nextMode = if (dark) "light" else "dark")
                            }) {
                                Icon(
                                    if (dark) Icons.Default.LightMode else Icons.Default.DarkMode,
                                    "Сменить тему",
                                    tint = if (dark) Color.White else Color(0xFF172024)
                                )
                            }
                        }
                    }
                }
                AnimatedVisibility(
                    visible = controlsVisible && !controlsLocked,
                    enter = fadeIn(), exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
                ) {
                    Surface(
                        onClick = { sheet = preferredSheet },
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
                    IconButton(onClick = { sheet = null }) {
                        Icon(Icons.Default.Close, "Закрыть панель")
                    }
                }
            }
        ) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.93f)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp)) {
                    listOf(SheetSection.AUDIO to "Аудио", SheetSection.TAFSIR to "Тафсир", SheetSection.SETTINGS to "Настройки")
                        .forEachIndexed { index, (target, title) ->
                            SegmentedButton(selected = sheet == target, onClick = {
                                preferredSheet = target
                                prefs.lastSheetSection = target.name
                                sheet = target
                            },
                                shape = SegmentedButtonDefaults.itemShape(index, 3)) { Text(title) }
                        }
                }
                HorizontalDivider()
                when (sheet) {
                    SheetSection.AUDIO -> AudioPanel(
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
                        onJump = { segment, index ->
                            val time = timings.seekMs(segment, index)
                            if (time == null) notice = "Тайминг ${segment.title.lowercase()} №$index пока не задан"
                            else if (hadrSnapshot.mediaId != "hadr" || hadrSnapshot.error != null) {
                                // A SINGLE atomic start+seek; do not call seekTo before prepare completes.
                                beginHadr(time)
                            } else if (!hadrSnapshot.seekable) {
                                notice = "Запись MP3 ещё не готова к перемотке"
                            } else {
                                hadrController?.seekTo(HadrSeekPolicy.clampMs(time, hadrSnapshot.durationMs))
                            }
                        }
                    )
                    SheetSection.TAFSIR -> TafsirPanel(
                        prefs = prefs,
                        page = page, store = tafsir, wordStore = wordStore,
                        playback = ayahSnapshot, controller = ayahController,
                        reciters = reciters, reciterError = reciterError,
                        selectedReciter = selectedReciter,
                        onReciter = { chosen ->
                            selectedReciter = chosen
                            prefs.selectedReciter = chosen
                            if (ayahSnapshot.playing && ayahSnapshot.mediaId.startsWith("ayah:")) {
                                val parts = ayahSnapshot.mediaId.split(':')
                                val reciter = reciters.firstOrNull { it.repository == chosen }
                                if (parts.size >= 3 && reciter != null) {
                                    val s = parts[1].toIntOrNull()
                                    val a = parts[2].toIntOrNull()
                                    if (s != null && a != null) ayahConnection.playAyah(reciter, s, a, ayahMode)
                                }
                            }
                        },
                        endMode = ayahMode, onEndMode = { mode ->
                            ayahMode = mode; prefs.ayahEndMode = mode.name
                            ayahConnection.setAyahEndMode(mode)
                        },
                        onPlayVerse = ::playVerse,
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
                                    notice = "Не найден локальный файл assets/quran/${part.fileName}"
                                } else {
                                    ayahConnection.playPreface(part)
                                }
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
                        mushaf = mushaf
                    )
                    null -> Unit
                }
            }
        }
    }
}

@Composable
private fun NavigationContent(
    segment: QuranCatalog.Segment,
    onSegment: (QuranCatalog.Segment) -> Unit,
    currentPage: Int,
    highlightedSurah: Int?,
    descriptions: CatalogDetails,
    onClick: (QuranCatalog.Segment, Int) -> Unit,
    onJumpAyah: (Int, Int) -> Unit
) {
    val count = QuranCatalog.startPages(segment).size
    val active = if (segment == QuranCatalog.Segment.SURAH) highlightedSurah
        ?: QuranCatalog.segmentForPage(segment, currentPage)
    else QuranCatalog.segmentForPage(segment, currentPage)
    val listState = remember(segment) { androidx.compose.foundation.lazy.LazyListState() }
    var showPageDialog by remember { mutableStateOf(false) }
    LaunchedEffect(segment, active) {
        if (active in 1..count) listState.scrollToItem(active - 1)
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedButton(
            onClick = { showPageDialog = true },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.FindInPage, null)
            Spacer(Modifier.width(8.dp))
            Text("Перейти")
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            QuranCatalog.Segment.entries.forEach { option ->
                FilterChip(
                    selected = option == segment,
                    onClick = { onSegment(option) },
                    label = { Text(option.title) }
                )
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)
        ) {
            items(count, key = { "${segment.name}:$it" }) { i ->
                val number = i + 1
                val selected = active == number
                val details by produceState("Загрузка сведений…", segment, number) {
                    value = descriptions.description(segment, number)
                }
                Surface(
                    onClick = { onClick(segment, number) },
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            number.toString(), modifier = Modifier.width(48.dp),
                            maxLines = 1, softWrap = false, fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                QuranCatalog.label(segment, number),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                            Text(
                                details,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
    if (showPageDialog) {
        PageJumpDialog(
            initialPage = currentPage,
            descriptions = descriptions,
            onDismiss = { showPageDialog = false },
            onGo = { target ->
                showPageDialog = false
                onClick(QuranCatalog.Segment.PAGE, target)
            },
            onGoAyah = { surah, ayah ->
                showPageDialog = false
                onJumpAyah(surah, ayah)
            }
        )
    }
}

/** Two real destinations: exact page or (surah, ayah) from the tafsir JSON. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageJumpDialog(
    initialPage: Int,
    descriptions: CatalogDetails,
    onDismiss: () -> Unit,
    onGo: (Int) -> Unit,
    onGoAyah: (Int, Int) -> Unit
) {
    var mode by rememberSaveable { mutableStateOf("page") }
    var input by rememberSaveable { mutableStateOf(initialPage.toString()) }
    var surah by rememberSaveable {
        mutableIntStateOf(QuranCatalog.segmentForPage(QuranCatalog.Segment.SURAH, initialPage))
    }
    var ayah by rememberSaveable { mutableIntStateOf(1) }
    val page = input.toIntOrNull()?.takeIf { it in 1..604 }
    val preview by produceState("Введите страницу от 1 до 604", page) {
        value = if (page == null) "Введите страницу от 1 до 604"
        else descriptions.pageDescription(page)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Перейти") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("page" to "Страница", "ayah" to "Аят").forEachIndexed { i, pair ->
                        SegmentedButton(
                            selected = mode == pair.first,
                            onClick = { mode = pair.first },
                            shape = SegmentedButtonDefaults.itemShape(i, 2)
                        ) { Text(pair.second) }
                    }
                }
                if (mode == "page") {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it.filter(Char::isDigit).take(3) },
                        singleLine = true,
                        label = { Text("Страница (1–604)") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
                        isError = page == null,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(preview, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    JumpNumberDropdown(
                        title = "Сура",
                        value = surah,
                        max = 114,
                        labelFor = { n -> "$n. ${QuranCatalog.surahNames[n - 1]}" },
                        onSelect = { chosen -> surah = chosen; ayah = 1 }
                    )
                    JumpNumberDropdown(
                        title = "Аят",
                        value = ayah,
                        max = QuranCatalog.ayahCounts[surah - 1],
                        labelFor = { n -> "Аят $n" },
                        onSelect = { ayah = it }
                    )
                    Text("Откроется страница мусхафа, содержащая выбранный аят.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (mode == "page") page?.let(onGo)
                    else onGoAyah(surah, ayah)
                },
                enabled = mode != "page" || page != null
            ) { Text("Перейти") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun JumpNumberDropdown(
    title: String,
    value: Int,
    max: Int,
    labelFor: (Int) -> String,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$title: ${labelFor(value)}", modifier = Modifier.weight(1f), maxLines = 1)
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 240.dp)
        ) {
            // DropdownMenu itself provides scrolling. Nested LazyColumn here
            // crashes during intrinsic measurement on some Compose versions.
            for (number in 1..max.coerceAtMost(286)) {
                DropdownMenuItem(
                    text = { Text(labelFor(number), maxLines = 1) },
                    onClick = { onSelect(number); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun ColoredBookmarksContent(
    bookmarks: List<PageBookmark>, onSelect: (Int) -> Unit, onRemove: (Int) -> Unit
) {
    if (bookmarks.isEmpty()) {
        Text("Закладок пока нет. Сохраните страницу через значок наверху.", modifier = Modifier.padding(20.dp))
    } else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
        items(bookmarks.size) { i ->
            val bookmark = bookmarks[i]
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(onClick = { onSelect(bookmark.page) }, modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.Bookmark, null, tint = Color(bookmark.color.toInt()))
                        Text("Страница ${bookmark.page}")
                    }
                }
                IconButton(onClick = { onRemove(bookmark.page) }) { Icon(Icons.Default.DeleteOutline, "Удалить") }
            }
        }
    }
}
