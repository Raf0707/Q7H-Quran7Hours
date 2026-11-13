package raf.console.quran7hours.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import raf.console.quran7hours.data.CatalogDetails
import raf.console.quran7hours.data.QuranCatalog
import raf.console.quran7hours.data.QuranNavigationIndex
import raf.console.quran7hours.data.QuranNavigationPageSource

private typealias Ref = QuranNavigationIndex.Ref

private enum class NavKind { MANZIL, JUZ, HIZB, HALF, SURAH, PAGE }
private data class NavNode(
    val kind: NavKind,
    val number: Int,
    val from: Int,
    val until: Int,
    val caption: String = ""
) {
    val key: String get() = "${kind.name}:$number:$from:$until:$caption"
}

private fun range(start: Ref, end: Ref): Pair<Int, Int> =
    QuranNavigationIndex.absolute(start) to QuranNavigationIndex.absolute(end)

private fun segmentNodes(kind: NavKind, manzilStarts: List<Ref>): List<NavNode> = when (kind) {
    NavKind.JUZ -> (1..30).map { n ->
        val (a,b) = range(QuranNavigationIndex.startOfJuz(n), QuranNavigationIndex.endOfJuz(n))
        NavNode(kind,n,a,b)
    }
    NavKind.HIZB -> (1..60).map { n ->
        val (a,b) = range(QuranNavigationIndex.startOfHizb(n), QuranNavigationIndex.endOfHizb(n))
        NavNode(kind,n,a,b)
    }
    NavKind.HALF -> (1..120).map { n ->
        val (a,b) = range(QuranNavigationIndex.startOfHalf(n), QuranNavigationIndex.endOfHalf(n))
        NavNode(kind,n,a,b)
    }
    NavKind.SURAH -> (1..114).map { n ->
        val (a,b) = range(Ref(n,1),Ref(n,QuranCatalog.ayahCounts[n-1]))
        NavNode(kind,n,a,b)
    }
    NavKind.MANZIL -> manzilStarts.mapIndexed { i, ref ->
        val end = manzilStarts.getOrNull(i+1)?.let(QuranNavigationIndex::lastBefore) ?: Ref(114,6)
        val (a,b) = range(ref,end)
        NavNode(kind,i+1,a,b)
    }
    NavKind.PAGE -> (1..604).map { NavNode(NavKind.PAGE,it,0,0) }
}

private fun clipped(nodes: List<NavNode>, parent: NavNode): List<NavNode> =
    nodes.mapNotNull { node ->
        val a = maxOf(node.from,parent.from)
        val b = minOf(node.until,parent.until)
        if (a <= b) node.copy(from=a,until=b) else null
    }

private fun nodeTitle(node: NavNode, mode: String): String = when (node.kind) {
    NavKind.MANZIL -> "Манзиль ${node.number}"
    NavKind.JUZ -> "Джуз ${node.number}"
    NavKind.HIZB -> "Хизб ${node.number}"
    NavKind.HALF -> {
        if (mode == "juz") "Четверть ${(node.number-1)%4+1}/4 · Хизб ${(node.number-1)/2+1}, ${(node.number-1)%2+1}/2"
        else "Половина ${(node.number-1)%2+1}/2 · Хизб ${(node.number-1)/2+1}"
    }
    NavKind.SURAH -> "${node.number}. ${QuranCatalog.surahNames[node.number-1]}"
    NavKind.PAGE -> "Страница ${node.number}"
}

/** Juz and Hizb trees use true verse boundaries; no 5-page approximations. */
private fun childKind(parent: NavKind, mode: String): NavKind? = when {
    mode == "juz" -> if (parent == NavKind.JUZ) NavKind.HALF else null
    mode == "hizb" -> if (parent == NavKind.HIZB) NavKind.HALF else null
    mode != "tree" -> null
    parent == NavKind.MANZIL -> NavKind.JUZ
    parent == NavKind.JUZ -> NavKind.HIZB
    parent == NavKind.HIZB -> NavKind.HALF
    parent == NavKind.HALF -> NavKind.SURAH
    parent == NavKind.SURAH -> NavKind.PAGE
    else -> null
}

@Composable
private fun NavigationNodes(
    kind: NavKind,
    mode: String,
    currentPage: Int,
    pages: QuranNavigationPageSource,
    onPage: (Int) -> Unit,
    onAyah: (Int,Int) -> Unit,
    modifier: Modifier = Modifier,
    selectedNumber: Int = 0
) {
    val starts by produceState<List<Ref>>(emptyList(), pages) {
        value = pages.v4.manzilStartRefs()
    }
    val roots = remember(kind, starts) {
        if (kind == NavKind.MANZIL && starts.size != 7) emptyList()
        else segmentNodes(kind,starts)
    }
    val expanded = remember(kind,mode) { mutableStateMapOf<String,Boolean>() }
    val state = rememberLazyListState()
    LaunchedEffect(kind, selectedNumber, roots.size) {
        if (selectedNumber in 1..roots.size) state.scrollToItem(selectedNumber - 1)
    }
    if (roots.isEmpty()) {
        Box(modifier.fillMaxWidth().heightIn(min=70.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    LazyColumn(state = state, modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom=20.dp)) {
        items(roots, key={it.key}) { item ->
            NavigationNodeRow(
                node=item, depth=0, mode=mode, pages=pages, expanded=expanded,
                manzilStarts=starts, onPage=onPage, onAyah=onAyah,
                active=if (kind == NavKind.PAGE) currentPage == item.number else selectedNumber == item.number
            )
        }
    }
}

@Composable
private fun NavigationNodeRow(
    node: NavNode,
    depth: Int,
    mode: String,
    pages: QuranNavigationPageSource,
    expanded: MutableMap<String,Boolean>,
    manzilStarts: List<Ref>,
    onPage: (Int) -> Unit,
    onAyah: (Int,Int) -> Unit,
    active: Boolean = false
) {
    val next = childKind(node.kind,mode)
    val isExpanded = expanded[node.key] == true
    val details by produceState("Загрузка сведений…",node.key,pages) {
        value = if (node.kind == NavKind.PAGE) pages.summary(node.number)
        else {
            val a = QuranNavigationIndex.ref(node.from)
            val b = QuranNavigationIndex.ref(node.until)
            val startPage = pages.pageOf(a)
            val endPage = pages.pageOf(b)
            buildString {
                append("${a.surah}:${a.ayah} — ${b.surah}:${b.ayah}")
                if (startPage != null && endPage != null) append(" · стр. $startPage–$endPage")
                val sajdas = QuranNavigationIndex.sajdaTilawah.filter {
                    QuranNavigationIndex.absolute(it) in node.from..node.until
                }.sortedBy(QuranNavigationIndex::absolute)
                if (sajdas.isNotEmpty()) append("\nСаджда-тилява: ").append(sajdas.joinToString { "после $it" })
            }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Surface(
            onClick = {
                if (node.kind == NavKind.PAGE) onPage(node.number)
                else QuranNavigationIndex.ref(node.from).let { onAyah(it.surah,it.ayah) }
            },
            modifier=Modifier.fillMaxWidth().padding(start=(depth*13).dp, end=4.dp, top=2.dp,bottom=2.dp),
            color=if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            shape=RoundedCornerShape(12.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(start=11.dp,end=3.dp,top=9.dp,bottom=9.dp),
                verticalAlignment=Alignment.CenterVertically) {
                if (depth == 0) {
                    Text("${node.number}", modifier=Modifier.width(31.dp),
                        style=MaterialTheme.typography.labelLarge,
                        color=MaterialTheme.colorScheme.primary, textAlign=TextAlign.Center)
                } else {
                    Icon(Icons.Default.SubdirectoryArrowRight,null,Modifier.size(19.dp),
                        tint=MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(5.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(nodeTitle(node,mode), style=MaterialTheme.typography.bodyMedium,
                        fontWeight=if (active) FontWeight.Bold else FontWeight.Medium)
                    Text(details,style=MaterialTheme.typography.labelSmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (next != null) {
                    IconButton(onClick={expanded[node.key]=!isExpanded}) {
                        Icon(if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ChevronRight,
                            if (isExpanded) "Свернуть" else "Раскрыть")
                    }
                } else {
                    Icon(Icons.Default.ChevronRight,null,
                        Modifier.padding(end=10.dp).size(18.dp),
                        tint=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (isExpanded && next != null) {
            val children by produceState<List<NavNode>>(emptyList(), node.key, next, pages) {
                if (next == NavKind.PAGE) {
                    val start = pages.pageOf(QuranNavigationIndex.ref(node.from))
                    val end = pages.pageOf(QuranNavigationIndex.ref(node.until))
                    value = if (start == null || end == null) emptyList()
                    else (start..end).map { NavNode(NavKind.PAGE,it,0,0) }
                } else {
                    value = clipped(segmentNodes(next,manzilStarts),node)
                }
            }
            children.forEach { child ->
                NavigationNodeRow(child,depth+1,mode,pages,expanded,manzilStarts,onPage,onAyah)
            }
        }
    }
}

/** Contents: Tree (first), Surahs (default), Pages, Juz, Hizb, Manzil. */
@Composable
internal fun NavigationContent(
    segment: QuranCatalog.Segment,
    onSegment: (QuranCatalog.Segment) -> Unit,
    currentPage: Int,
    highlightedSurah: Int?,
    descriptions: CatalogDetails,
    onClick: (QuranCatalog.Segment,Int) -> Unit,
    onJumpAyah: (Int,Int) -> Unit
) {
    val context = LocalContext.current
    val pages = remember(context.applicationContext) { QuranNavigationPageSource(context.applicationContext) }
    var tree by rememberSaveable { mutableStateOf(false) }
    var treeRoot by rememberSaveable { mutableStateOf("MANZIL") }
    var jump by remember { mutableStateOf(false) }
    val kind = if (tree) NavKind.valueOf(treeRoot) else when (segment) {
        QuranCatalog.Segment.SURAH -> NavKind.SURAH
        QuranCatalog.Segment.PAGE -> NavKind.PAGE
        QuranCatalog.Segment.JUZ -> NavKind.JUZ
        QuranCatalog.Segment.HIZB -> NavKind.HIZB
        QuranCatalog.Segment.MANZIL -> NavKind.MANZIL
    }
    val selected = when (kind) {
        NavKind.SURAH -> highlightedSurah ?: pages.v4.segmentForPage(QuranCatalog.Segment.SURAH,currentPage)
        NavKind.PAGE -> currentPage
        NavKind.JUZ -> pages.v4.segmentForPage(QuranCatalog.Segment.JUZ,currentPage)
        NavKind.HIZB -> pages.v4.segmentForPage(QuranCatalog.Segment.HIZB,currentPage)
        NavKind.MANZIL -> pages.v4.segmentForPage(QuranCatalog.Segment.MANZIL,currentPage)
        NavKind.HALF -> 0
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedButton(onClick={jump=true},
            modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=5.dp)) {
            Icon(Icons.Default.FindInPage,null); Spacer(Modifier.width(8.dp)); Text("Перейти")
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=8.dp),
            horizontalArrangement=Arrangement.spacedBy(5.dp)) {
            FilterChip(selected=tree,onClick={tree=true},label={Text("Дерево")})
            QuranCatalog.Segment.entries.forEach { opt ->
                FilterChip(selected=!tree && segment==opt,onClick={tree=false;onSegment(opt)},
                    label={Text(opt.title)})
            }
        }
        if (tree) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=8.dp),
                horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf(NavKind.MANZIL,NavKind.JUZ,NavKind.HIZB,NavKind.SURAH,NavKind.PAGE).forEach { k ->
                    FilterChip(selected=treeRoot==k.name,onClick={treeRoot=k.name},
                        label={Text(when(k){
                            NavKind.MANZIL->"От манзилей";NavKind.JUZ->"От джузов"
                            NavKind.HIZB->"От хизбов";NavKind.SURAH->"От сур";else->"Страницы"
                        })})
                }
            }
        }
        NavigationNodes(kind=kind,mode=if (tree) "tree" else segment.name.lowercase(),
            currentPage=currentPage,pages=pages,
            onPage={onClick(QuranCatalog.Segment.PAGE,it)},onAyah=onJumpAyah,
            modifier=Modifier.weight(1f),selectedNumber=selected)
    }
    if (jump) PageJumpDialog(currentPage,descriptions,{jump=false},
        {n->jump=false;onClick(QuranCatalog.Segment.PAGE,n)},
        {s,a->jump=false;onJumpAyah(s,a)})
}

/** The original UI shape is preserved; five horizontally scrollable segmented modes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PageJumpDialog(
    initialPage: Int,
    descriptions: CatalogDetails,
    onDismiss: () -> Unit,
    onGo: (Int) -> Unit,
    onGoAyah: (Int,Int) -> Unit
) {
    val context=LocalContext.current
    val pages=remember(context.applicationContext){QuranNavigationPageSource(context.applicationContext)}
    var mode by rememberSaveable { mutableStateOf("page") }
    var input by rememberSaveable(initialPage){mutableStateOf(initialPage.toString())}
    var surah by rememberSaveable(initialPage){mutableIntStateOf(pages.v4.segmentForPage(QuranCatalog.Segment.SURAH,initialPage))}
    var ayah by rememberSaveable(initialPage) { mutableIntStateOf(1) }
    var explicitlySelected by rememberSaveable(initialPage) { mutableStateOf(false) }
    // Navigation page source uses the actual Azan.ru verses on this physical page.
    // Do not default to first ayah of the surah when a page starts mid-surah.
    LaunchedEffect(initialPage, pages) {
        val firstAyah = pages.first(initialPage)
        if (!explicitlySelected && firstAyah != null) {
            surah = firstAyah.surah
            ayah = firstAyah.ayah
        }
    }
    val page=input.toIntOrNull()?.takeIf{it in 1..604}
    val ayahPage by produceState<Int?>(null,surah,ayah,pages){
        value=pages.pageOf(Ref(surah,ayah))
    }
    val selectedPage=if(mode=="ayah") ayahPage else page
    val preview by produceState("Загрузка сведений…",selectedPage,pages){
        value=selectedPage?.let{pages.summary(it)} ?: "Введите допустимое значение"
    }
    val modes=listOf("ayah" to "Аят","page" to "Страница","juz" to "Джуз",
        "hizb" to "Хизб","manzil" to "Манзиль")
    AlertDialog(
        onDismissRequest=onDismiss,
        title={Text("Перейти")},
        text={
            Column(Modifier.fillMaxWidth().heightIn(max=530.dp),
                verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    SingleChoiceSegmentedButtonRow(Modifier.width(475.dp)) {
                        modes.forEachIndexed { i, (id,label) ->
                            SegmentedButton(selected=mode==id,onClick={mode=id},
                                shape=SegmentedButtonDefaults.itemShape(i,5),
                                modifier=Modifier.weight(1f)){
                                Text(label,maxLines=1,fontSize=11.sp)
                            }
                        }
                    }
                }
                when(mode){
                    "ayah" -> {
                        NavigationNumberDropdown("Сура",surah,114,
                            {"$it. ${QuranCatalog.surahNames[it-1]}"}){
                            surah=it; ayah=1; explicitlySelected=true
                        }
                        NavigationNumberDropdown("Аят",ayah,QuranCatalog.ayahCounts[surah-1],
                            {"Аят $it"}){ayah=it; explicitlySelected=true}
                        Text("Страница: ${ayahPage ?: "…"}",fontWeight=FontWeight.SemiBold)
                        val selectedPosition = QuranNavigationIndex.position(Ref(surah,ayah))
                        Text("Выбранный аят: Джуз ${selectedPosition.juz}, ${selectedPosition.juzQuarter}/4 · " +
                            "Хизб ${selectedPosition.hizb}, ${selectedPosition.hizbHalf}/2" +
                            (ayahPage?.let { " · Манзиль ${pages.v4.segmentForPage(QuranCatalog.Segment.MANZIL,it)}" } ?: ""),
                            style=MaterialTheme.typography.bodySmall)
                        Text(preview,style=MaterialTheme.typography.bodySmall)
                    }
                    "page" -> {
                        OutlinedTextField(value=input,onValueChange={input=it.filter(Char::isDigit).take(3)},
                            modifier=Modifier.fillMaxWidth(),label={Text("Страница (1–604)")},
                            singleLine=true,isError=page==null,
                            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                        Text(preview,style=MaterialTheme.typography.bodySmall)
                    }
                    "juz","hizb","manzil" -> {
                        val kind=when(mode){"juz"->NavKind.JUZ;"hizb"->NavKind.HIZB;else->NavKind.MANZIL}
                        Text(when(mode){
                            "juz"->"30 джузов · стрелка раскрывает четыре четверти"
                            "hizb"->"60 хизбов · стрелка раскрывает две половины"
                            else->"7 манзилей · переход в начало манзиля"
                        },style=MaterialTheme.typography.labelSmall)
                        NavigationNodes(kind,mode,initialPage,pages,onGo,onGoAyah,
                            modifier=Modifier.height(340.dp),
                            selectedNumber=when(kind){
                                NavKind.JUZ->pages.v4.segmentForPage(QuranCatalog.Segment.JUZ,initialPage)
                                NavKind.HIZB->pages.v4.segmentForPage(QuranCatalog.Segment.HIZB,initialPage)
                                else->pages.v4.segmentForPage(QuranCatalog.Segment.MANZIL,initialPage)
                            })
                    }
                }
            }
        },
        confirmButton={
            if (mode=="page" || mode=="ayah") TextButton(
                onClick={ if (mode=="page") page?.let(onGo) else onGoAyah(surah,ayah) },
                enabled=if(mode=="page") page!=null else ayahPage!=null
            ){Text("Перейти")}
        },
        dismissButton={TextButton(onClick=onDismiss){Text("Отмена")}}
    )
}

@Composable
private fun NavigationNumberDropdown(
    title:String, value:Int, max:Int, label:(Int)->String, onSelect:(Int)->Unit
){
    var expanded by remember {mutableStateOf(false)}
    Box {
        OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth()){
            Text("$title: ${label(value)}",maxLines=1,modifier=Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown,null)
        }
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false},
            modifier=Modifier.widthIn(min=230.dp)){
            // DropdownMenu has built-in scrolling. Do not nest LazyColumn (intrinsic crash).
            (1..max).forEach { n ->
                DropdownMenuItem(text={Text(label(n),maxLines=1)},
                    onClick={onSelect(n);expanded=false})
            }
        }
    }
}
