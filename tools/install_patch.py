#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Apply targeted updates to user's *current* Quran7Hours sources.

Never overwrite unrecognized sources. Verify all anchors, then write atomically
with backups. Run in project root, from either a ZIP merged into root or via
python tools/install_patch.py --project PATH.
"""
from __future__ import annotations
import argparse
import hashlib
import re
import shutil
import sys
from pathlib import Path

SRC = Path(__file__).resolve().parents[1] / 'src'
CANONICAL = Path('app/src/main/java/raf/console/quran7hours/ui')


def replace_once(source: str, old: str, new: str, label: str) -> str:
    count = source.count(old)
    if count != 1:
        raise ValueError(f'{label}: expected exactly one match, found {count}. Installation stopped.')
    return source.replace(old, new, 1)


def replace_block(source: str, begin: str, until: str, new: str, label: str) -> str:
    if source.count(begin) != 1 or source.count(until) < 1:
        raise ValueError(f'{label}: missing or nonunique anchors; installation stopped')
    start = source.index(begin)
    end = source.index(until, start)
    return source[:start] + new + source[end:]


def patch_panels(s: str) -> str:
    if 'TafsirDisplaySettingsSection' in s or 'QpcPreparedPages.page(context, store' in s:
        raise ValueError('Panels.kt already includes this patch; do not install twice')
    start = '    val verses by produceState<List<TafsirStore.Verse>?>(null, page) {'
    stop = '    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp)) {'
    replacement = '''    val context = androidx.compose.ui.platform.LocalContext.current
    val displayState = remember(context) { SharedTafsirDisplayOptions.get(context) }
    val displayOptions = displayState.value
    var showTafsirDisplaySettings by remember { mutableStateOf(false) }
    // Wait for BOTH verses and original page-glyph fonts before first Arabic frame.
    val readyPage by produceState<QpcPreparedPage?>(null, page, store) {
        value = QpcPreparedPages.page(context, store, page)
    }
    val verses = readyPage?.verses
    val qpcVerses = readyPage?.qpc.orEmpty()
    val dictionaries by produceState<Map<Pair<Int, Int>, List<WordByWordStore.Word>>>(
        emptyMap(), page, verses
    ) {
        value = verses?.let { wordStore.forPage(page, it) }.orEmpty()
    }
    // The fallback uses the untouched Azan.ru verse; avoid second source swapping.
    val originalQpc = remember(context) { OriginalQpcTafsirRepository(context) }
'''
    s = replace_block(s, start, stop, replacement, 'Tafsir atomic load')
    # The audio list is always opened at the current reading position, for all 5 tabs.
    # Previously it restored an unrelated saved viewport; the user could land far away.
    audio_start = '''    val viewportKey = remember(category) { "audio_${category.name.lowercase()}" }
    val listState = remember(category) {
        val (index, offset) = prefs.panelViewport(viewportKey)
        LazyListState(index.coerceIn(0, (marks.size - 1).coerceAtLeast(0)), offset)
    }
    PersistLazyViewport(listState, prefs, viewportKey)
    val timedActive = if (playback.mediaId == "hadr") timings.at(category, playback.positionMs) else null
    val active = timedActive ?: QuranCatalog.segmentForPage(category, currentPage)
'''
    audio_end = '''    val readingPosition = QuranCatalog.segmentForPage(category, currentPage)
    val listState = remember(category) {
        LazyListState((readingPosition - 1).coerceIn(0, (marks.size - 1).coerceAtLeast(0)), 0)
    }
    LaunchedEffect(category, currentPage) {
        if (readingPosition in 1..marks.size) listState.scrollToItem(readingPosition - 1)
    }
    val timedActive = if (playback.mediaId == "hadr") timings.at(category, playback.positionMs) else null
    val active = timedActive ?: readingPosition
'''
    s = replace_once(s, audio_start, audio_end, 'audio auto-scroll: current surah/page/juz/hizb/manzil')
    # Add a settings gear next to the Tafsir page title.
    title = '''            Text("Тафсир · страница $page", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
'''
    title_new = title + '''            Box {
                IconButton(onClick = { showTafsirDisplaySettings = !showTafsirDisplaySettings }) {
                    Icon(Icons.Default.Tune, "Настройки отображения тафсира")
                }
                DropdownMenu(
                    expanded = showTafsirDisplaySettings,
                    onDismissRequest = { showTafsirDisplaySettings = false },
                    modifier = Modifier.widthIn(min = 280.dp, max = 430.dp).heightIn(max = 570.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        TafsirDisplaySettingsSection(displayState)
                    }
                }
            }
'''
    s = replace_once(s, title, title_new, 'Tafsir menu')
    s = replace_once(s,
        'if (verse.arabic.isNotBlank() || quranVerses[verse.surah to verse.ayah] != null) {',
        'if (displayOptions.showArabic && (verse.arabic.isNotBlank() || qpcVerses[verse.surah to verse.ayah] != null)) {',
        'Arabic visibility')
    s = replace_once(s, 'OriginalQpcTafsirText(original, tajweed)',
        'OriginalQpcTafsirText(original, tajweed, displayOptions.arabicSp)', 'Arabic size')
    s = replace_once(s, 'verified = quranVerses[key],',
        'verified = null,', 'fallback content')
    s = replace_once(s, '''                        AyahWordDictionary(
                            verse.surah, verse.ayah,
                            dictionaries[verse.surah to verse.ayah].orEmpty()
                        )''', '''                        if (displayOptions.showDictionary) {
                            AyahWordDictionary(
                                verse.surah, verse.ayah,
                                dictionaries[verse.surah to verse.ayah].orEmpty(),
                                displayOptions.dictionarySp
                            )
                        }''', 'dictionary visibility')
    s = replace_once(s, 'if (verse.translation.isNotBlank()) {',
        'if (displayOptions.showTranslation && verse.translation.isNotBlank()) {', 'translation visibility')
    s = replace_once(s, 'Text(verse.translation, style = MaterialTheme.typography.bodyLarge)',
        'Text(verse.translation, style = MaterialTheme.typography.bodyLarge.copy(fontSize = displayOptions.translationSp.sp))',
        'translation size')
    # Wrap formatted exposition ONLY, not verse heading and translation.
    s = replace_once(s, '                        val formatted = remember(verse.explanation) { store.format(verse.explanation) }',
        '                        if (displayOptions.showTafsir) {\n                        val formatted = remember(verse.explanation) { store.format(verse.explanation) }',
        'tafsir visibility opening')
    s = replace_once(s, '''                            }
                        }
                    }
                }
            }
        }
    }
        }
}''', '''                            }
                        }
                        } // end of tafsir visibility
                    }
                }
            }
        }
    }
        }
}''', 'tafsir visibility closing')
    # Only text of the formatted paragraphs changes; headings and other labels unaffected.
    s = replace_once(s, 'style = MaterialTheme.typography.bodyMedium)\n                                }\n                                TafsirStore.Paragraph.Type.HEADER',
        'style = MaterialTheme.typography.bodyMedium.copy(fontSize = displayOptions.tafsirSp.sp))\n                                }\n                                TafsirStore.Paragraph.Type.HEADER', 'quote size')
    s = replace_once(s, 'style = MaterialTheme.typography.titleSmall,\n                                    color = MaterialTheme.colorScheme.primary)',
        'style = MaterialTheme.typography.titleSmall.copy(fontSize = (displayOptions.tafsirSp + 1f).sp),\n                                    color = MaterialTheme.colorScheme.primary)', 'heading size')
    s = replace_once(s, 'style = MaterialTheme.typography.bodyMedium)\n                                TafsirStore.Paragraph.Type.BODY',
        'style = MaterialTheme.typography.bodyMedium.copy(fontSize = displayOptions.tafsirSp.sp))\n                                TafsirStore.Paragraph.Type.BODY', 'bullet size')
    s = replace_once(s, 'TafsirStore.Paragraph.Type.BODY -> Text(paragraph.text,\n                                    style = MaterialTheme.typography.bodyMedium)',
        'TafsirStore.Paragraph.Type.BODY -> Text(paragraph.text,\n                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = displayOptions.tafsirSp.sp))', 'body size')
    s = replace_once(s, 'private fun AyahWordDictionary(surah: Int, ayah: Int, words: List<WordByWordStore.Word>) {',
        'private fun AyahWordDictionary(surah: Int, ayah: Int, words: List<WordByWordStore.Word>, fontSizeSp: Float) {',
        'dictionary signature')
    s = replace_once(s, 'Text(word.arabic, fontSize = 19.sp,',
        'Text(word.arabic, fontSize = fontSizeSp.sp,', 'dictionary Arabic size')
    s = replace_once(s, 'style = MaterialTheme.typography.bodyMedium)\n                            }\n                        }',
        'style = MaterialTheme.typography.bodyMedium.copy(fontSize = (fontSizeSp * 0.78f).coerceAtLeast(11f).sp))\n                            }\n                        }', 'dictionary translation size')
    # Save scroll position when the same Tafsir page is reopened, but on ANY page
    # transition start at top, even if that page was read and scrolled earlier.
    s = replace_once(s, '''        val viewportKey = "tafsir_$page"
        val listState = remember(page) {
            val (index, offset) = prefs.panelViewport(viewportKey)
            LazyListState(index.coerceIn(0, (loadedVerses.size - 1 + if (page == 1) 1 else 0).coerceAtLeast(0)), offset)
        }
        PersistLazyViewport(listState, prefs, viewportKey)''', '''        val viewportKey = "tafsir_$page"
        val listState = remember(page) {
            val (index, offset) = if (prefs.lastTafsirViewedPage == page)
                prefs.panelViewport(viewportKey) else (0 to 0)
            LazyListState(index.coerceIn(0, (loadedVerses.size - 1 + if (page == 1) 1 else 0).coerceAtLeast(0)), offset)
        }
        LaunchedEffect(page) { prefs.lastTafsirViewedPage = page }
        PersistLazyViewport(listState, prefs, viewportKey)''', 'Tafsir open-same-page restore / page-turn reset')
    # Add exact same settings section under Settings → Tajweed.
    s = replace_once(s, '''    val scroll = rememberScrollState(initial = prefs.panelViewport("settings").second)
    PersistSettingsViewport(scroll, prefs)''',
        '''    val scroll = rememberScrollState(initial = prefs.panelViewport("settings").second)
    val displayPrefs = SharedTafsirDisplayOptions.get(androidx.compose.ui.platform.LocalContext.current)
    PersistSettingsViewport(scroll, prefs)''', 'settings prefs')
    s = replace_once(s, '''        SettingsSwitch("Таджвид", tajweed, setTajweed)''',
        '''        SettingsSwitch("Таджвид", tajweed, setTajweed)
        HorizontalDivider()
        TafsirDisplaySettingsSection(displayPrefs)''', 'settings section')
    return s


def patch_preferences(s: str) -> str:
    if 'lastTafsirViewedPage' in s:
        raise ValueError('UserPreferences already has step 2 patch')
    marker = '    /** Exact lazy-list viewport: item index + pixel offset, stored per section. */'
    block = '''    /** Last Tafsir page actually visited: distinguishes reopening from page turning. */
    var lastTafsirViewedPage: Int
        get() = p.getInt("tafsir_last_viewed_page", 0).coerceIn(0, 604)
        set(value) = p.edit().putInt("tafsir_last_viewed_page", value.coerceIn(0, 604)).apply()
'''
    return replace_once(s, marker, block + marker, 'last viewed Tafsir page preference')


def patch_main(s: str) -> str:
    target = '    val page = pager.currentPage + 1\n'
    addition = target + '''    // Background preload starts on the first composition, well before opening the sheet.
    LaunchedEffect(page, tafsir) { QpcPreparedPages.start(context, tafsir, page) }
'''
    return replace_once(s, target, addition, 'app startup preloading')


def find_hafiz_source(kotlin_dir: Path) -> list[Path]:
    result = []
    for path in kotlin_dir.rglob('*.kt'):
        text = path.read_text('utf-8')
        if re.search(r'\bfun\s+HafizDiaryPanel\s*\(', text):
            result.append(path)
    return result


def matching_close(text: str, opening: int, left: str = '(', right: str = ')') -> int:
    depth = 0
    quoted = False
    escaped = False
    for i in range(opening, len(text)):
        c = text[i]
        if quoted:
            if escaped: escaped = False
            elif c == '\\': escaped = True
            elif c == '"': quoted = False
            continue
        if c == '"': quoted = True
        elif c == left: depth += 1
        elif c == right:
            depth -= 1
            if depth == 0: return i
    raise ValueError('Unbalanced parentheses in HafizDiaryPanel')


def patch_hafiz(s: str) -> str:
    if 'HafizOriginalQpcAyah(' in s:
        raise ValueError('HafizDiaryPanel already patched')
    # Only a *literal Arabic verse Text* can be safely swapped. Do not modify
    # annotated/interactive/masked text (that would damage learner markings).
    matches = list(re.finditer(r'\b(?:Text|BasicText)\s*\(', s))
    choices = []
    for m in matches:
        close = matching_close(s, s.index('(', m.start()))
        body = s[m.end():close]
        candidate = re.search(r'\b(?:text\s*=\s*)?([A-Za-z_]\w*)\.arabic\b', body)
        if candidate and 'onTextLayout' not in body and 'buildAnnotatedString' not in body:
            var = candidate.group(1)
            # Prefer verses from TafsirStore, known to contain .surah/.ayah.
            if var in {'verse', 'ayah', 'item', 'v'}:
                choices.append((m.start(), close + 1, var))
    if not choices:
        raise ValueError('HafizDiaryPanel: not a simple Text(verse.arabic). '
                         'Do not risk overwriting interactive highlighting; manual integration required.')
    # If found multiple Arabic labels, choose all full-verse labels within Hafiz function scope.
    funstart = re.search(r'\bfun\s+HafizDiaryPanel\s*\(', s).start()
    fbody = s.find('{', matching_close(s, s.find('(', funstart)) + 1)
    if fbody < 0:
        raise ValueError('Cannot find HafizDiaryPanel body')
    fend = matching_close(s, fbody, '{', '}')
    choices = [(start, end, var) for start, end, var in choices if fbody < start < fend]
    if not choices:
        raise ValueError('No simple Arabic Text inside HafizDiaryPanel')
    for start, end, var in reversed(choices):
        replacement = f'''HafizOriginalQpcAyah(
                                    page = page, tafsir = tafsir,
                                    surah = {var}.surah, ayah = {var}.ayah,
                                    fallbackArabic = {var}.arabic, tajweed = prefs.tajweed
                                )'''
        s = s[:start] + replacement + s[end:]
    return s


def installed_preflight(project: Path):
    target = project / CANONICAL
    if not (target / 'Panels.kt').is_file() or not (target / 'OriginalQpcTafsir.kt').is_file():
        raise ValueError('Use the project WITH the successful QPC V4 fix installed. Missing Panels.kt or OriginalQpcTafsir.kt.')
    if not (target / 'MainActivity.kt').is_file():
        raise ValueError('Missing MainActivity.kt')
    return target


def apply(project: Path, allow_partial: bool = False) -> None:
    target = installed_preflight(project)
    edits: dict[Path, str] = {}
    panels = target / 'Panels.kt'
    main = target / 'MainActivity.kt'
    preferences = project / 'app/src/main/java/raf/console/quran7hours/data/UserPreferences.kt'
    if not preferences.is_file():
        raise ValueError('Missing real UserPreferences.kt; refusing to change application sources')
    edits[preferences] = patch_preferences(preferences.read_text('utf-8'))
    edits[panels] = patch_panels(panels.read_text('utf-8'))
    edits[main] = patch_main(main.read_text('utf-8'))
    hafiz_sources = find_hafiz_source(target)
    if len(hafiz_sources) != 1:
        raise ValueError(f'Expected exactly 1 HafizDiaryPanel definition, found {len(hafiz_sources)}. No files were changed.')
    try:
        edits[hafiz_sources[0]] = patch_hafiz(hafiz_sources[0].read_text('utf-8'))
    except Exception as exc:
        # Do NOT block the fixes to Arabic Fatiha, Tafsir, Audio and Settings.
        # An ink-annotated diary must be modified manually without destroying
        # user's pen strokes. Never replace it with a fake plain-text Text().
        print('WARNING: Diary has a custom ink renderer; it was preserved:', exc)
        print('The other 4 fixes WILL be installed. See DIARY_INTEGRATION_NOTE.txt.')
    originals = {p: p.read_bytes() for p in edits}
    current_renderer = target / 'OriginalQpcTafsir.kt'
    expected = SRC / 'OriginalQpcTafsir.kt'
    data = current_renderer.read_text('utf-8')
    if 'QpcInkLineHeight' not in data or 'private object QpcSharedFonts' not in data:
        raise ValueError('Not the expected latest QPC V4 fix. Aborting to keep your working version.')
    if 'warmOriginalQpcFonts' in data:
        raise ValueError('Renderer already patched: aborting to avoid overwriting edits.')
    to_install = {target / file: (SRC / file).read_text('utf-8') for file in
                  ('OriginalQpcTafsir.kt', 'QpcPreparedPages.kt', 'TafsirDisplayOptions.kt', 'HafizQpcAyah.kt')}
    for dest in to_install:
        if dest.is_file() and dest != current_renderer:
            raise ValueError(f'Target already exists: {dest}. Do not overwrite.')
    # Only reach writes if all anchors and files were checked successfully.
    backup = project / 'Q7H_BACKUP_BEFORE_TAFSIR_SETTINGS_STEP2_2026_11_12'
    if backup.exists():
        raise ValueError(f'Backup already exists: {backup}. Aborting.')
    for original in list(edits) + [current_renderer]:
        copy_to = backup / original.relative_to(project)
        copy_to.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(original, copy_to)
    for dest, content in {**edits, **to_install}.items():
        dest.parent.mkdir(parents=True, exist_ok=True)
        temp = dest.with_name(dest.name + '.q7h_tmp')
        temp.write_text(content, 'utf-8')
        temp.replace(dest)
    print('SUCCESS. Modified:')
    for p in [*edits, *to_install]:
        print(' -', p.relative_to(project))
    if hafiz_sources[0] not in edits:
        (project / 'Q7H_DIARY_INTEGRATION_NOTE.txt').write_text(
            'HafizDiaryPanel uses a custom Arabic/ink renderer. Its original code has not been changed.\n'
            'To preserve all existing ink markings, integrate QPC in that renderer rather than replacing annotated Text with a plain Text.\n'
            'The Tafsir, Fatiha, preload, settings and scroll changes were installed.\n'
            'To prepare a safe full diary patch, run EXPORT_HAFIZ_SOURCE.bat and share its output.\n', 'utf-8')
    print('Backup:', backup)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--project', type=Path, default=Path.cwd())
    parser.add_argument('--allow-partial', action='store_true',
                        help='Install Tafsir and preload even if Hafiz source is too customized. Use intentionally only.')
    a = parser.parse_args()
    try:
        apply(a.project.resolve(), a.allow_partial)
    except Exception as exc:
        print('ERROR:', exc, file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
