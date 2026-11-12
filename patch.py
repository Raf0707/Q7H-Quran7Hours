# -*- coding: utf-8 -*-
"""Conservative and idempotent Hafiz original QPC patch for Quran7Hours."""
from __future__ import annotations
from pathlib import Path
from datetime import datetime
import os
import shutil
import sys
import tempfile

UI_PATH = Path('app/src/main/java/raf/console/quran7hours/ui')
MARK = 'Q7H_HAFIZ_QPC_DIARY_20261112_V1'
FATIHA_MARK = 'Q7H_FATIHA_SPLIT_6_7_20261112'

class PatchError(Exception): pass

def exactly_once(source: str, old: str, new: str, label: str) -> str:
    count = source.count(old)
    if count != 1:
        raise PatchError(f'{label}: expected exactly 1 anchor, got {count}.')
    return source.replace(old, new, 1)

def find_root() -> Path:
    starts = ([Path(sys.argv[1]).expanduser()] if len(sys.argv)>1 and sys.argv[1].strip() else []) + [Path.cwd(), Path(__file__).resolve().parent]
    for start in starts:
        for base in [start.resolve(), *start.resolve().parents]:
            if (base / UI_PATH / 'StudyFeatures.kt').is_file() and (base / 'app/src/main').is_dir():
                return base
    raise PatchError('Cannot find Q7H project root (with app/). Extract the ZIP into that root.')

def patch_study(source: str) -> str:
    if MARK in source:
        for anchor in ('QpcPreparedPages.page(context, tafsir, page)', 'OriginalQpcTafsirText(qpc, tajweed)'):
            if anchor not in source: raise PatchError('Hafiz integration marker found but code is incomplete.')
        print('StudyFeatures.kt: already patched')
        return source
    source=exactly_once(source,
        '''    db: LearningDatabase,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    val verses by produceState<List<TafsirStore.Verse>?>(null, page) { value = tafsir.page(page) }
''',
        '''    db: LearningDatabase,
    tajweed: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    // Q7H_HAFIZ_QPC_DIARY_20261112_V1
    // One shared TafsirStore source and atomic QPC glyph/font preparation.
    val context = androidx.compose.ui.platform.LocalContext.current
    val prepared by produceState<QpcPreparedPage?>(null, page, tafsir) {
        value = QpcPreparedPages.page(context, tafsir, page)
    }
    val verses = prepared?.verses
''', 'HafizDiaryPanel: parameters/source')
    source=exactly_once(source,
        '''            items(loaded, key = { "${it.surah}:${it.ayah}" }) { verse ->
                HafizVerseCard(verse, db)
            }
''',
        '''            items(loaded, key = { "${it.surah}:${it.ayah}" }) { verse ->
                HafizVerseCard(verse, db, prepared?.qpc?.get(verse.surah to verse.ayah), tajweed)
            }
''', 'HafizDiaryPanel: verse cards')
    source=exactly_once(source,
        'private fun HafizVerseCard(verse: TafsirStore.Verse, db: LearningDatabase) {',
        '''private fun HafizVerseCard(
    verse: TafsirStore.Verse,
    db: LearningDatabase,
    qpc: OriginalQpcAyah?,
    tajweed: Boolean
) {''', 'HafizVerseCard: parameters')
    source=exactly_once(source,
        '''            Text(verse.arabic, fontSize = 25.sp, lineHeight = 40.sp,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Right,
                style = TextStyle(textDirection = TextDirection.Rtl))''',
        '''            if (qpc != null) {
                // Original page-specific QPC: V4 embedded Tajweed when ON, V2 when OFF.
                OriginalQpcTafsirText(qpc, tajweed)
            } else {
                // Preserve readable Azan Arabic; never display QPC PUA codes without fonts.
                TafsirQuranText(arabicFromAzan = verse.arabic, verified = null, tajweed = false)
            }''', 'HafizVerseCard: Arabic rendering')
    return source

def patch_main(source: str) -> str:
    old='''                    SheetSection.HAFIZ -> HafizDiaryPanel(
                        page = page, tafsir = tafsir, prefs = prefs, db = learningDb,
                        onPrevious = { goToPage(page - 1) },
'''
    new='''                    SheetSection.HAFIZ -> HafizDiaryPanel(
                        page = page, tafsir = tafsir, prefs = prefs, db = learningDb,
                        tajweed = tajweed,
                        onPrevious = { goToPage(page - 1) },
'''
    if new in source:
        print('MainActivity.kt: already patched')
        return source
    return exactly_once(source,old,new,'MainActivity: Hafiz call')

def patch_qpc(source: str, ui_dir: Path) -> str:
    if FATIHA_MARK in source:
        print('OriginalQpcTafsir.kt: Fatiha explicit 1:6/1:7 split already present')
    elif 'hanafiFatihahGlyphs' in source:
        raise PatchError('Unexpected automatic Fatiha matcher. Refusing to overwrite it.')
    else:
        start='''        for (verse in verses) {
            val id = verseId(verse.surah, verse.ayah)
'''
        end='''            val qpcArabic = words.joinToString(" ") { it.word }
'''
        if source.count(start)!=1 or source.count(end)!=1:
            raise PatchError('OriginalQpcTafsir.kt QPC loop not recognized; refused unsafe edit')
        i=source.index(start)
        j=source.index(end,i)+len(end)
        original=source[i:j]
        for needed in ('val pages = index[id]', 'words.sortWith(compareBy<OriginalQpcWord>', 'words.indices.any { words[it].wordNumber != it + 1 }'):
            if needed not in original: raise PatchError('Unexpected QPC sequence: '+needed)
        replacement='''        for (verse in verses) {
            // Q7H_FATIHA_SPLIT_6_7_20261112
            // Hanafi 1:1..5 -> QPC 1:2..6; Hanafi 1:6 and 1:7 -> QPC 1:7.
            val key = verse.surah to verse.ayah
            val qpcAyah = when {
                verse.surah == 1 && verse.ayah in 1..5 -> verse.ayah + 1
                verse.surah == 1 && verse.ayah in 6..7 -> 7
                else -> verse.ayah
            }
            val pages = index[verseId(verse.surah, qpcAyah)]
            if (pages.isNullOrEmpty()) {
                Log.w(LOG_TAG, "No QPC layout for ${verse.surah}:${verse.ayah} (QPC $qpcAyah)")
                continue
            }
            val canonicalWords = ArrayList<OriginalQpcWord>()
            for (p in pages) {
                canonicalWords.addAll(wordsForPage(p).filter { it.surah == verse.surah && it.ayah == qpcAyah })
            }
            canonicalWords.sortWith(compareBy<OriginalQpcWord> { it.wordNumber }.thenBy { it.page })
            if (canonicalWords.isEmpty() || canonicalWords[0].wordNumber != 1 ||
                canonicalWords.indices.any { canonicalWords[it].wordNumber != it + 1 }) {
                Log.w(LOG_TAG, "Incomplete QPC words for ${verse.surah}:$qpcAyah")
                continue
            }
            val words: List<OriginalQpcWord> = if (verse.surah == 1 && verse.ayah in 6..7) {
                // Canonical QPC 1:7 has nine words. Hanafi 1:6 = first four,
                // Hanafi 1:7 = remaining five. Use only exact page font glyphs.
                if (canonicalWords.size != 9 ||
                    !qpcSkeleton(canonicalWords[0].word).startsWith("صر") ||
                    !qpcSkeleton(canonicalWords[4].word).startsWith("غير")) {
                    Log.w(LOG_TAG, "Unexpected QPC Fatiha 1:7 sequence, safe Unicode fallback")
                    continue
                }
                if (verse.ayah == 6) canonicalWords.subList(0, 4)
                else canonicalWords.subList(4, 9)
            } else canonicalWords
            val qpcArabic = words.joinToString(" ") { it.word }
'''
        source=source[:i]+replacement+source[j:]
    if 'fun warmOriginalQpcFonts(' not in source:
        other=any('fun warmOriginalQpcFonts(' in f.read_text(encoding='utf-8-sig')
                  for f in ui_dir.glob('*.kt') if f.name != 'OriginalQpcTafsir.kt')
        if not other:
            source+='''

/** Warm original V4/V2 page-specific font faces on Dispatchers.IO. */
internal fun warmOriginalQpcFonts(context: Context, ayahs: Collection<OriginalQpcAyah>) {
    val fonts = QpcSharedFonts.forContext(context)
    ayahs.asSequence().flatMap { it.parts.asSequence() }.map { it.page }.distinct().forEach { page ->
        fonts.get(page, true)
        fonts.get(page, false)
    }
}
'''
    return source

def commit(changes: dict[Path, tuple[str,str]], root: Path) -> None:
    changed={p:(old,new) for p,(old,new) in changes.items() if old!=new}
    if not changed:
        print('No edits needed. Patch already applied.')
        return
    backup=root/'q7h_patch_backups'/('hafiz_qpc_'+datetime.now().strftime('%Y%m%d_%H%M%S_%f'))
    backup.mkdir(parents=True)
    try:
        for p in changed:
            target=backup/p.relative_to(root)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(p,target)
        written=[]
        try:
            for p,(_,new) in changed.items():
                with tempfile.NamedTemporaryFile(mode='w',encoding='utf-8',newline='\n',dir=p.parent,delete=False,suffix='.q7h_tmp') as f:
                    f.write(new)
                    tmp=Path(f.name)
                try:
                    os.replace(tmp,p)
                finally:
                    tmp.unlink(missing_ok=True)
                written.append(p)
                print('PATCHED:',p.relative_to(root))
        except Exception:
            for p in reversed(written):
                shutil.copy2(backup/p.relative_to(root),p)
            raise
    finally:
        print('Backup:',backup)

def main() -> int:
    try:
        root=find_root(); ui=root/UI_PATH
        study=ui/'StudyFeatures.kt'; mainfile=ui/'MainActivity.kt'
        original=ui/'OriginalQpcTafsir.kt'; prepared=ui/'QpcPreparedPages.kt'
        for file in (study,mainfile,original,prepared):
            if not file.is_file(): raise PatchError(f'Required file missing: {file}')
        if 'warmOriginalQpcFonts(app, qpc.values)' not in prepared.read_text(encoding='utf-8-sig'):
            raise PatchError('QpcPreparedPages.kt unexpected, no changes made')
        changes={}
        for path,fix in ((study,patch_study),(mainfile,patch_main)):
            old=path.read_text(encoding='utf-8-sig')
            changes[path]=(old,fix(old))
        old=original.read_text(encoding='utf-8-sig')
        new=patch_qpc(old,ui)
        changes[original]=(old,new)
        if 'fun warmOriginalQpcFonts(' not in new and not any('fun warmOriginalQpcFonts(' in x.read_text(encoding='utf-8-sig') for x in ui.glob('*.kt') if x != original):
            raise PatchError('QPC font preloader still unresolved')
        if not all(needle in changes[study][1] for needle in (MARK,'QpcPreparedPages.page(context, tafsir, page)','OriginalQpcTafsirText(qpc, tajweed)')):
            raise PatchError('Diary renderer verification failed')
        commit(changes,root)
        print('SUCCESS: Hafiz Diary now displays verified original QPC V4 (tajweed) or V2 (classic).')
        print('Hanafi Fatiha 1:6/1:7 are separate: QPC 1:7 split into 4+5 words.')
        assets=root/'app/src/main/assets/quran/qpc-v4'
        if not assets.is_dir():
            print('WARNING: No QPC assets directory found in project. Install all QPC layouts/fonts.')
        return 0
    except PatchError as exc:
        print('ERROR:',exc); print('No source files changed.'); return 2
    except Exception as exc:
        print('ERROR:',repr(exc)); return 3

if __name__=='__main__': sys.exit(main())
