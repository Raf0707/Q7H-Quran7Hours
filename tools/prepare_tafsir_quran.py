#!/usr/bin/env python3
"""Download *MIT* Quran Tajweed Engine text + spans, validate, pack offline Android assets.

No external Python packages required. This is a build-time command, NEVER run in the app.
Source: https://github.com/TheAbubakrAbu/Quran-Tajweed-Engine
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import json
from pathlib import Path
import sys
import time
import struct
from urllib.request import Request, urlopen

BASE = "https://raw.githubusercontent.com/TheAbubakrAbu/Quran-Tajweed-Engine/main/data"
OUT = Path("app/src/main/assets/tafsir_quran")
EXPECTED_VERSES = 6236
FONT_URL = "https://mirrors.ibiblio.org/CTAN/fonts/amiri/AmiriQuran.ttf"
FONT_OUT = Path("app/src/main/res/font/tafsir_quran.ttf")
REQUIRED_RULES = {
    "lamShamsiyah", "droppedLetter", "hamzatWaslSilent", "idghamBilaGhunnah",
    "idghamGhunnah", "generalGhunnah", "ikhfaaLight", "ikhfaaHeavy", "iqlaab",
    "qalqalah", "tafkhim", "maddNatural", "maddNaturalMiniature", "maddSukoon",
    "maddSeparated", "maddConnected", "maddNecessary",
}


def fetch_json(path: str):
    url = f"{BASE}/{path}"
    last_error = None
    for attempt in range(5):
        try:
            req = Request(url, headers={"User-Agent": "Quran7Hours-build-assets/1.0", "Accept": "application/json"})
            with urlopen(req, timeout=45) as fp:
                raw = fp.read(6 * 1024 * 1024 + 1)
            if len(raw) > 6 * 1024 * 1024:
                raise ValueError(f"Exceeded per-file limit: {path}")
            return json.loads(raw.decode("utf-8-sig"))
        except Exception as exc:
            last_error = exc
            if attempt < 4:
                time.sleep(min(2 ** attempt, 8))
    raise RuntimeError(f"Failed to download {url}: {last_error}")



def get_font_bytes(project: Path) -> bytes:
    """Download the OFL Unicode Quran font on the developer's machine."""
    target = project / FONT_OUT
    if target.is_file() and target.stat().st_size >= 50_000:
        raw = target.read_bytes()
        try:
            cmap_codepoints(raw)
            print("Using existing Quran font:", target)
            return raw
        except ValueError:
            print("Existing font is corrupt; downloading a fresh version")
    req = Request(FONT_URL, headers={"User-Agent": "Quran7Hours-build-assets/1.0"})
    try:
        with urlopen(req, timeout=90) as fp:
            raw = fp.read(4 * 1024 * 1024 + 1)
    except Exception as exc:
        raise RuntimeError(f"Cannot download the OFL Quran font from {FONT_URL}: {exc}") from exc
    if len(raw) < 50_000 or len(raw) > 4 * 1024 * 1024:
        raise ValueError(f"Suspicious font download ({len(raw)} bytes); refusing to install")
    cmap_codepoints(raw)  # Must be real OpenType/TrueType, not an HTML error page.
    return raw


def cmap_codepoints(raw: bytes) -> set[int]:
    """Read TTF/OTF Unicode cmap using only Python standard library."""
    def u16(pos: int) -> int:
        if pos < 0 or pos + 2 > len(raw): raise ValueError('Bad font bounds')
        return struct.unpack_from('>H', raw, pos)[0]
    def u32(pos: int) -> int:
        if pos < 0 or pos + 4 > len(raw): raise ValueError('Bad font bounds')
        return struct.unpack_from('>I', raw, pos)[0]
    if raw[:4] not in (b'\x00\x01\x00\x00', b'OTTO', b'true'):
        raise ValueError('Not a TrueType/OpenType font')
    tables = u16(4)
    if tables < 1 or tables > 60: raise ValueError('Invalid table count')
    cmap = None
    for i in range(tables):
        pos = 12 + i * 16
        if raw[pos:pos+4] == b'cmap':
            cmap = u32(pos + 8)
            break
    if cmap is None: raise ValueError('No cmap table')
    num_subtables = u16(cmap + 2)
    result: set[int] = set()
    for i in range(num_subtables):
        pos = cmap + 4 + i * 8
        platform, encoding = u16(pos), u16(pos+2)
        if platform != 0 and not (platform == 3 and encoding in (1, 10)):
            continue
        pos = cmap + u32(pos + 4)
        fmt = u16(pos)
        if fmt == 4:
            segments = u16(pos + 6) // 2
            if segments > 20000: raise ValueError('Bad segment count')
            ends = pos + 14
            starts = ends + segments * 2 + 2
            deltas = starts + segments * 2
            ranges = deltas + segments * 2
            for j in range(segments):
                first, last = u16(starts + j*2), u16(ends + j*2)
                if last < first: continue
                delta = u16(deltas + j*2)
                range_off = u16(ranges + j*2)
                for cp in range(first, last+1):
                    if cp == 0xFFFF: continue
                    if range_off:
                        glyph = u16(ranges + j*2 + range_off + (cp-first)*2)
                        if glyph: glyph = (glyph + delta) & 0xffff
                    else:
                        glyph = (cp + delta) & 0xffff
                    if glyph: result.add(cp)
        elif fmt == 12:
            groups = u32(pos + 12)
            if groups > 100000: raise ValueError('Bad cmap group count')
            for j in range(groups):
                start = u32(pos + 16 + 12*j)
                end = u32(pos + 20 + 12*j)
                glyph = u32(pos + 24 + 12*j)
                if end < start or end > 0x10ffff: raise ValueError('Bad cmap range')
                result.update(range(start + (glyph == 0), end+1))
    if not (0x0627 in result and 0x0645 in result and 0x0644 in result):
        raise ValueError('Font has no usable Arabic Quranic glyph set')
    return result

def utf16_units(text: str) -> int:
    return len(text.encode("utf-16-le")) // 2


def check_arabic(text: str, label: str) -> None:
    if not text or '\ufffd' in text or '\x00' in text:
        raise ValueError(f"Corrupt or missing Quran text: {label}")
    if any(ord(c) > 0xFFFF for c in text):
        raise ValueError(f"Supplementary character offsets not supported: {label}")
    if not any('\u0621' <= c <= '\u064a' for c in text):
        raise ValueError(f"Not Quranic Arabic: {label}")


def process_surah(n: int, source_dir: Path | None = None):
    if source_dir:
        chapter = json.loads((source_dir / 'surahs' / f'{n:03}.json').read_text(encoding='utf-8'))
        marks = json.loads((source_dir / 'tajweed' / f'{n:03}.json').read_text(encoding='utf-8'))
    else:
        chapter = fetch_json(f"surahs/{n:03}.json")
        marks = fetch_json(f"tajweed/{n:03}.json")
    if chapter.get("id") != n:
        raise ValueError(f"Wrong surah id for {n}")
    ayahs = chapter.get("ayahs", [])
    expected = chapter.get("numberOfAyahs")
    if expected != len(ayahs) or expected < 1:
        raise ValueError(f"Incorrect ayah count in surah {n}")
    annotated = {}
    for entry in marks:
        if entry['surah'] != n or not (1 <= entry['ayah'] <= expected):
            raise ValueError(f"Wrong tajweed verse id in surah {n}: {entry}")
        if entry['ayah'] in annotated:
            raise ValueError(f"Duplicate annotations in surah {n}")
        annotated[entry['ayah']] = entry['annotations']
    output = []
    span_count = 0
    seen = set()
    for item in ayahs:
        ayah = item['id']
        if ayah in seen or not (1 <= ayah <= expected):
            raise ValueError(f"Invalid ayah numbering {n}:{ayah}")
        seen.add(ayah)
        arabic = item['textArabic']
        check_arabic(arabic, f"{n}:{ayah}")
        spans = []
        for entry in annotated.get(ayah, []):
            start, end, rule = entry['start'], entry['end'], entry['rule']
            if rule not in REQUIRED_RULES:
                raise ValueError(f"Unknown rule at {n}:{ayah}: {rule}")
            if not isinstance(start, int) or not isinstance(end, int):
                raise ValueError(f"Noninteger offset: {n}:{ayah}")
            if not 0 <= start < end <= utf16_units(arabic):
                raise ValueError(f"Out-of-range tajweed position at {n}:{ayah}: {start}:{end}")
            if (arabic[start:end].strip() == ''):
                raise ValueError(f"Tajweed span marks only whitespace at {n}:{ayah}")
            spans.append([start, end, rule])
        spans.sort(key=lambda row: (row[0], row[1]))
        if any(spans[j][0] < spans[j-1][1] for j in range(1, len(spans))):
            raise ValueError(f"Overlapping tajweed colors at {n}:{ayah}")
        span_count += len(spans)
        output.append({"ayah": ayah, "ar": arabic, "spans": spans})
    if seen != set(range(1, expected + 1)):
        raise ValueError(f"Missing ayah in surah {n}")
    if len(annotated) != expected:
        raise ValueError(f"Incomplete tajweed annotation map in surah {n} (got {len(annotated)})")
    return n, {"surah": n, "ayahs": output}, span_count


def prepare(args):
    folder = args.project / OUT
    source_dir = args.source_dir
    print('Quran7Hours · tafsir Arabic Hafs + tajweed (MIT source)')
    print('Source:', BASE if source_dir is None else source_dir)
    print('Output:', folder)
    font_bytes = get_font_bytes(args.project)
    processed = {}
    counts = {}
    with ThreadPoolExecutor(max_workers=max(1, min(args.workers, 8))) as pool:
        futures = {pool.submit(process_surah, n, source_dir): n for n in range(1, 115)}
        for future in as_completed(futures):
            n = futures[future]
            try:
                _, result, count = future.result()
            except Exception as exc:
                for other in futures: other.cancel()
                raise RuntimeError(f"STOP: surah {n} not validated: {exc}") from exc
            processed[n] = result
            counts[n] = count
            print(f"[{len(processed):3}/114] {n:03}: {len(result['ayahs'])} ayahs, {count} spans", flush=True)
    total = sum(len(item['ayahs']) for item in processed.values())
    annotations = sum(counts.values())
    supported = cmap_codepoints(font_bytes)
    used = {ord(ch) for chapter in processed.values() for ayah in chapter['ayahs'] for ch in ayah['ar']
            if not ch.isspace() and ch not in '\u200c\u200d\u2060\ufeff'}
    missing = sorted(used - supported)
    if missing:
        raise ValueError('Selected Quran font is missing these codepoints: ' +
                         ', '.join('U+%04X' % cp for cp in missing) +
                         '. No files replaced; inspect the source/font before installing.')
    if total != EXPECTED_VERSES:
        raise ValueError(f"Wrong total: {total} != {EXPECTED_VERSES}. No files replaced.")
    if annotations < 100000:
        raise ValueError(f"Unexpectedly few Tajweed spans ({annotations}). No files replaced.")
    # Write all data to an adjacent staging folder first; existing assets survive failed validation.
    staging = folder.with_name(folder.name + '_staging')
    staging.mkdir(parents=True, exist_ok=True)
    for n, record in sorted(processed.items()):
        (staging / f'{n:03}.json').write_text(json.dumps(record, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
    # Atomic file replacement; only this namespace, no other app data touched.
    folder.mkdir(parents=True, exist_ok=True)
    for n in range(1, 115):
        (staging / f'{n:03}.json').replace(folder / f'{n:03}.json')
    staging.rmdir()
    font_target = args.project / FONT_OUT
    font_target.parent.mkdir(parents=True, exist_ok=True)
    font_staging = font_target.with_suffix('.ttf.tmp')
    font_staging.write_bytes(font_bytes)
    font_staging.replace(font_target)
    report = {"source": BASE, "font_source": FONT_URL, "surahs": 114, "ayahs": total, "tajweed_spans": annotations}
    (folder / 'validation_report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print('SUCCESS:', json.dumps(report, ensure_ascii=False))
    print('Now Sync/Rebuild in Android Studio. Keep this folder in local sources or your asset pipeline.')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--project', type=Path, default=Path('.'), help='Android project root')
    p.add_argument('--source-dir', type=Path, default=None, help='For offline validation: local engine/data directory')
    p.add_argument('--workers', type=int, default=6)
    args = p.parse_args()
    try:
        prepare(args)
    except Exception as exc:
        print('ERROR:', exc, file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()
