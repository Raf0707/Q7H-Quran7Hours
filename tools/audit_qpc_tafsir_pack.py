#!/usr/bin/env python3
"""Read-only audit of all QPC Mushaf glyph resources used by the Tafsir panel.

Usage from Android project root:
    py -3 tools/audit_qpc_tafsir_pack.py --project .

Does not download files, alter the Quran text, install fonts, or require external libs.
Checks 1812 assets, expected 6236 verse keys, and every word's ordered index.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
from pathlib import Path
import json
import sys

VERSE_COUNTS = [
    7,286,200,176,120,165,206,75,129,109,123,111,43,52,99,128,111,110,
    98,135,112,78,118,64,77,227,93,88,69,60,34,30,73,54,45,83,182,88,
    75,85,54,53,89,59,37,35,38,29,18,45,60,49,62,55,78,96,29,22,24,
    13,14,11,11,18,12,12,30,52,52,44,28,28,20,56,40,31,50,40,46,42,29,
    19,36,25,22,17,19,26,30,20,15,21,11,8,8,19,5,8,8,11,11,8,3,9,5,4,
    7,3,6,3,5,4,5,6,
]
assert len(VERSE_COUNTS) == 114 and sum(VERSE_COUNTS) == 6236
EXPECTED = {(s, a) for s, count in enumerate(VERSE_COUNTS, 1) for a in range(1, count + 1)}
FONT_SIG = (b'\0\1\0\0', b'OTTO', b'true')


def audit(project: Path) -> dict:
    base = project / 'app/src/main/assets/quran/qpc-v4'
    errors, warnings = [], []
    count_fonts = {'v2': 0, 'v4': 0}
    count_layouts = 0
    by_verse = defaultdict(list)
    for page in range(1, 605):
        # Check every actual font file, not just directory length or first/last.
        for edition in ('v2', 'v4'):
            path = base / 'fonts' / edition / f'p{page}.ttf'
            if not path.is_file():
                errors.append(f'{edition}: page {page} font missing: {path}')
            elif path.stat().st_size < 128:
                errors.append(f'{edition}: page {page} font is too small: {path}')
            else:
                with path.open('rb') as f:
                    if f.read(4) not in FONT_SIG:
                        errors.append(f'{edition}: invalid TTF/OTF signature: {path}')
                    else:
                        count_fonts[edition] += 1
        path = base / 'layout' / f'page-{page:03}.json'
        if not path.is_file():
            errors.append(f'layout: page {page} missing: {path}')
            continue
        try:
            payload = json.loads(path.read_text(encoding='utf-8-sig'))
            if payload.get('page') != page or not isinstance(payload.get('lines'), list):
                raise ValueError('page number or lines incorrect')
            words_found = 0
            for line in payload['lines']:
                if line.get('type') != 'text':
                    continue
                for word in line.get('words') or []:
                    loc = str(word.get('location') or '').split(':')
                    if len(loc) != 3 or not all(x.isdigit() for x in loc):
                        errors.append(f'layout page {page}: malformed location {loc}')
                        continue
                    surah, ayah, num = map(int, loc)
                    key = surah, ayah
                    if key not in EXPECTED:
                        errors.append(f'layout page {page}: invalid verse key {key}')
                        continue
                    if not num:
                        errors.append(f'layout page {page}: word 0 for {surah}:{ayah}')
                        continue
                    if not str(word.get('qpcV2') or '').strip():
                        errors.append(f'layout page {page}: empty qpcV2 for {surah}:{ayah}:{num}')
                        continue
                    by_verse[key].append((num, page))
                    words_found += 1
            if not words_found:
                errors.append(f'layout page {page}: zero usable QPC glyphs')
            count_layouts += 1
        except (UnicodeError, OSError, ValueError, TypeError, KeyError) as exc:
            errors.append(f'layout page {page}: cannot parse ({exc})')

    missing = sorted(EXPECTED.difference(by_verse))
    unexpected = sorted(set(by_verse).difference(EXPECTED))
    for surah, ayah in missing:
        errors.append(f'verse missing entirely: {surah}:{ayah}')
    for surah, ayah in unexpected:
        errors.append(f'unexpected verse: {surah}:{ayah}')
    missing_or_duplicate_index = 0
    multi_page_verses = []
    for (surah, ayah), indices in sorted(by_verse.items()):
        indices.sort()
        received = [i for i, _ in indices]
        if received != list(range(1, len(received) + 1)):
            errors.append(f'{surah}:{ayah} duplicate/missing/out-of-order word index: {received[:50]}')
            missing_or_duplicate_index += 1
        pages = sorted({page for _, page in indices})
        if len(pages) > 1:
            multi_page_verses.append({'verse': f'{surah}:{ayah}', 'pages': pages})

    report = {
        'project': str(project.resolve()),
        'status': 'PASS' if not errors else 'FAIL',
        'layout_count': count_layouts,
        'font_v2_count': count_fonts['v2'],
        'font_v4_count': count_fonts['v4'],
        'found_verse_count': len(by_verse),
        'expected_verse_count': 6236,
        'missing_verse_count': len(missing),
        'verse_index_errors': missing_or_duplicate_index,
        'multi_page_verses': multi_page_verses,
        'errors_total': len(errors),
        'errors': errors,
        'warnings': warnings,
    }
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', default='.', type=Path)
    parser.add_argument('--report', default='QPC_TAFSIR_AUDIT.json', type=Path)
    args = parser.parse_args()
    report = audit(args.project.resolve())
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print('QPC Tafsir resource audit')
    for key in ('status', 'layout_count', 'font_v2_count', 'font_v4_count',
                'found_verse_count', 'expected_verse_count', 'missing_verse_count',
                'verse_index_errors', 'errors_total'):
        print(f'{key}: {report[key]}')
    print('verses spanning page borders:', len(report['multi_page_verses']))
    if report['errors']:
        print('First errors:')
        for error in report['errors'][:20]:
            print(' -', error)
    print('Saved:', args.report.resolve())
    return 0 if report['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
