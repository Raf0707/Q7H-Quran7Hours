#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Restore files from backup created by install_patch.py. Run in project root."""
from pathlib import Path
import argparse, shutil

p = argparse.ArgumentParser()
p.add_argument('--project', type=Path, default=Path.cwd())
a = p.parse_args()
root = a.project.resolve()
b = root / 'Q7H_BACKUP_BEFORE_TAFSIR_SETTINGS_STEP2_2026_11_12'
if not b.is_dir():
    raise SystemExit('Backup not found; nothing to restore.')
for old in b.rglob('*.kt'):
    dst = root / old.relative_to(b)
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(old,dst)
    print('restored:',old.relative_to(b))
new_folder = root / 'app/src/main/java/raf/console/quran7hours/ui'
for name in ('QpcPreparedPages.kt','TafsirDisplayOptions.kt','HafizQpcAyah.kt'):
    dest=new_folder/name
    if dest.exists():
        dest.unlink()
        print('removed:',dest.relative_to(root))
print('RESTORED. Backups kept at',b)
