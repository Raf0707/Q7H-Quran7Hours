#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Q7H 2026-11-12: isolated repair of Hanafi Al-Fatihah numbering for QPC V4/V2.

Target: app/src/main/java/raf/console/quran7hours/ui/OriginalQpcTafsir.kt
No external downloads and no changes to tafsir, fonts, media or other Kotlin files.
"""
from __future__ import annotations

import os
import shutil
import sys
import tempfile
from datetime import datetime
from pathlib import Path

RELATIVE = Path("app/src/main/java/raf/console/quran7hours/ui/OriginalQpcTafsir.kt")
MARKER = "Q7H_FATIHA_HANAFI_QPC_MAP_20261112"
START = "            val id = verseId(verse.surah, verse.ayah)\n"
END = '            val qpcArabic = words.joinToString(" ") { it.word }\n'

REPLACEMENT = '''            // Q7H_FATIHA_HANAFI_QPC_MAP_20261112
            // Azan.ru follows the Hanafi division of Al-Fatihah: Basmala is separate,
            // ayahs 1..5 = QPC canonical 2..6, and Hanafi 6+7 = QPC canonical 7.
            // Keep ALL original QPC V4/V2 glyphs and page-specific font files.
            val key = verse.surah to verse.ayah
            val qpcAyah = when {
                verse.surah == 1 && verse.ayah in 1..5 -> verse.ayah + 1
                verse.surah == 1 && verse.ayah in 6..7 -> 7
                else -> verse.ayah
            }
            val pages = index[verseId(verse.surah, qpcAyah)]
            if (pages.isNullOrEmpty()) {
                Log.w(LOG_TAG, "No QPC layout for ${verse.surah}:${verse.ayah} (canonical $qpcAyah)")
                continue
            }
            // Validate the FULL canonical ayah before slicing its last verse.
            val canonicalWords = ArrayList<OriginalQpcWord>()
            for (p in pages) {
                canonicalWords.addAll(wordsForPage(p).filter {
                    it.surah == verse.surah && it.ayah == qpcAyah
                })
            }
            canonicalWords.sortWith(compareBy<OriginalQpcWord> { it.wordNumber }.thenBy { it.page })
            if (canonicalWords.isEmpty() || canonicalWords[0].wordNumber != 1 ||
                canonicalWords.indices.any { canonicalWords[it].wordNumber != it + 1 }) {
                Log.w(LOG_TAG, "QPC word sequence is incomplete/duplicated for ${verse.surah}:$qpcAyah")
                continue
            }
            val words: List<OriginalQpcWord> = if (verse.surah == 1 && verse.ayah in 6..7) {
                // QPC 1:7 is split at the ACTUAL word boundary; never cut a glyph,
                // and never assume a hard-coded number of words.
                val hanafi6 = verses.firstOrNull { it.surah == 1 && it.ayah == 6 }?.arabic.orEmpty()
                val hanafi7 = verses.firstOrNull { it.surah == 1 && it.ayah == 7 }?.arabic.orEmpty()
                val validSplits = (1 until canonicalWords.size).filter { split ->
                    likelySameVerse(
                        hanafi6, canonicalWords.subList(0, split).joinToString(" ") { it.word }
                    ) && likelySameVerse(
                        hanafi7, canonicalWords.subList(split, canonicalWords.size).joinToString(" ") { it.word }
                    )
                }
                if (validSplits.size != 1) {
                    Log.w(LOG_TAG, "Fatiha 1:7 cannot be split safely (${validSplits.size} matches)")
                    continue
                }
                val split = validSplits.single()
                if (verse.ayah == 6) canonicalWords.subList(0, split)
                else canonicalWords.subList(split, canonicalWords.size)
            } else canonicalWords
'''


def root_for(path: Path) -> Path | None:
    path = path.expanduser().resolve()
    for root in (path, *path.parents):
        if (root / RELATIVE).is_file():
            return root
    return None


def discover_root() -> Path | None:
    candidates = []
    if len(sys.argv) > 1 and sys.argv[1].strip():
        candidates.append(Path(sys.argv[1].strip().strip('"')))
    candidates.extend([Path.cwd(), Path(__file__).resolve().parent])
    for item in candidates:
        result = root_for(item)
        if result is not None:
            return result
    try:
        answer = input("Укажите путь к папке проекта Q7H-Quran7Hours (или Enter для выхода): ").strip().strip('"')
    except EOFError:
        answer = ""
    return root_for(Path(answer)) if answer else None


def main() -> int:
    root = discover_root()
    if root is None:
        print("ОШИБКА: не найден файл app/src/main/java/.../ui/OriginalQpcTafsir.kt")
        print("Распакуйте ZIP в корень проекта ИЛИ перетащите папку проекта на INSTALL.bat")
        return 2
    path = root / RELATIVE
    raw = path.read_bytes()
    has_crlf = b"\r\n" in raw
    text = raw.decode("utf-8-sig").replace("\r\n", "\n")
    if MARKER in text:
        print("Патч для Аль-Фатихи уже установлен; повторная установка не требуется.")
        print(f"Файл: {path}")
        return 0
    if text.count(START) != 1 or text.count(END) != 1:
        print("ОШИБКА: код отличается от проверенного исходника, исправления НЕ внесены.")
        print(f"Проверьте файл: {path}")
        return 3
    first = text.index(START)
    last = text.index(END, first)
    old = text[first:last]
    # Fail closed: verify exact structure so an unrelated change cannot be erased.
    safeguards = [
        "val key = verse.surah to verse.ayah",
        "val pages = index[id]",
        "val words = ArrayList<OriginalQpcWord>()",
        "words.sortWith(compareBy<OriginalQpcWord>",
        "words.indices.any { words[it].wordNumber != it + 1 }",
    ]
    if not all(s in old for s in safeguards):
        print("ОШИБКА: исходный блок QPC изменён. Файлы проекта НЕ затронуты.")
        return 4
    updated = text[:first] + REPLACEMENT + text[last:]
    if has_crlf:
        updated = updated.replace("\n", "\r\n")
    # Safety: compile-time dependencies and the ordinary non-Fatiha mapping stay intact.
    assert "else -> verse.ayah" in REPLACEMENT
    assert updated.count(MARKER) == 1
    assert updated.count("private fun likelySameVerse(") == 1
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    backup = path.with_name(path.name + f".before_fatiha_{stamp}.bak")
    if backup.exists():
        print("ОШИБКА: резервная копия уже существует, исправления НЕ внесены.")
        return 5
    shutil.copy2(path, backup)
    temp_name = None
    try:
        with tempfile.NamedTemporaryFile(mode="wb", dir=path.parent, prefix=".q7h_fatiha_", delete=False) as f:
            temp_name = f.name
            f.write(updated.encode("utf-8"))
            f.flush()
            os.fsync(f.fileno())
        os.replace(temp_name, path)
    except Exception:
        if temp_name:
            Path(temp_name).unlink(missing_ok=True)
        # Backup remains available for manual restore.
        raise
    print("УСПЕХ: сопоставление Аль-Фатихи с оригинальным QPC V4/V2 исправлено.")
    print(f"Проект: {root}")
    print(f"Изменён только: {RELATIVE.as_posix()}")
    print(f"Резервная копия: {backup.name}")
    print("Ни один TTF, JSON, другой аят или файл приложения не изменён.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:
        print(f"НЕПРЕДВИДЕННАЯ ОШИБКА: {exc}")
        sys.exit(10)
