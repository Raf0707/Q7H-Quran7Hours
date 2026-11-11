#!/usr/bin/env python3
"""Check all 114 locally installed tafsir-surah assets; never modify content."""
from pathlib import Path
import json
import re
import sys

base = Path(__file__).resolve().parents[1] / 'app/src/main/assets/quran/data/surahs'
opening = re.compile(r'<(/?)([a-zA-Z]+)([^>]*)>', re.I)
cls = re.compile(r"\bclass\s*=\s*(?:\"([^\"]+)\"|'([^']+)'|([\w-]+))", re.I)

def untag(text):
    output, stack, skipping, bad, cursor = [], [], False, False, 0
    for m in opening.finditer(text):
        if not skipping:
            output.append(text[cursor:m.start()])
        closing, tag, attrs = bool(m.group(1)), m.group(2).lower(), m.group(3)
        if tag == 'tajweed':
            if skipping:
                bad = True
            elif closing:
                if not stack: bad = True
                else: stack.pop()
            else:
                rule = cls.search(attrs)
                if not rule: bad = True
                else: stack.append(next((x for x in rule.groups() if x),''))
        elif tag == 'span':
            if closing:
                if not skipping: bad = True
                skipping = False
            else:
                rule = cls.search(attrs)
                if skipping or not rule or 'end' not in rule.groups(): bad = True
                else: skipping = True
        else: bad = True
        cursor = m.end()
    if not skipping:output.append(text[cursor:])
    return ''.join(output), bad or bool(stack) or skipping

def inspect(data_dir):
    total=0;colored=0;broken=[];missing=[];names=set()
    for surah in range(1,115):
        path=data_dir / f'{surah:03}.json'
        if not path.is_file():
            missing.append(path.name);continue
        try:
            data=json.loads(path.read_text(encoding='utf-8'))
            assert data['id']==surah
            for node in data['ayahs']:
                total+=1
                ar=node['ar'];tw=node.get('tw','')
                if not ar or not isinstance(tw,str):
                    broken.append((surah,node.get('a'), 'missing field'));continue
                if '<tajweed ' in tw: colored+=1
                got, invalid=untag(tw)
                if got!=ar or invalid:
                    broken.append((surah,node['a'], 'tw does not reproduce ar'))
                for match in opening.finditer(tw):
                    if match.group(2).lower() == 'tajweed' and not match.group(1):
                        rule=cls.search(match.group(3))
                        if rule:names.add(next((x for x in rule.groups() if x),''))
        except Exception as ex:
            broken.append((surah, 0, str(ex)))
    print('SURAH FILES:',114-len(missing),'/ 114; VERSES:',total,'; VERSES WITH TAJWEED TAGS:',colored)
    print('RULES:',', '.join(sorted(names)))
    if missing:print('MISSING:',', '.join(missing))
    if broken:
        print('INVALID/MISMATCH:',len(broken))
        for x in broken[:40]:print(' ',x)
    else:print('VALIDATION: no text mismatches found')
    if total!=6236:print('NOTE: verse count differs from standard 6236. Confirm Hanafi numbering and JSON source.')
    return 1 if missing or broken else 0

if __name__=='__main__':
    base=Path(sys.argv[1]) if len(sys.argv)>1 else base
    if not base.exists():
        print('MISSING DIRECTORY:',base)
        sys.exit(2)
    sys.exit(inspect(base))
