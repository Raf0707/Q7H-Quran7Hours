#!/usr/bin/env python3
"""Export ONLY the custom HafizDiaryPanel Kotlin source needed for an exact ink-safe renderer patch."""
from pathlib import Path
import re
import zipfile
root = Path.cwd()
ui = root / 'app/src/main/java/raf/console/quran7hours/ui'
if not ui.is_dir():
    raise SystemExit('Run this from the Android project root.')
found=[]
for item in ui.rglob('*.kt'):
    if re.search(r'\bfun\s+HafizDiaryPanel\s*\(',item.read_text('utf-8')):
        found.append(item)
if len(found)!=1:
    raise SystemExit(f'Expected exactly one HafizDiaryPanel source, found {len(found)}')
output=root/'Q7H_HafizDiary_source_for_patch.zip'
with zipfile.ZipFile(output,'w',compression=zipfile.ZIP_DEFLATED) as z:
    z.write(found[0],str(found[0].relative_to(root)))
print('Saved:',output)
print('Source:',found[0].relative_to(root))
print('Upload this ZIP in ChatGPT if the installer warns that diary ink renderer was preserved.')
