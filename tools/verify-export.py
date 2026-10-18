#!/usr/bin/env python3
"""Verify the unmodified exported bundle; no device or source-tree access."""
from pathlib import Path,PurePosixPath
import hashlib,json

bundle=Path(__file__).resolve().parent.parent
manifest=json.loads((bundle/'PACKAGE_FILES.json').read_text(encoding='utf-8'))
seen=set()
for item in manifest:
    rel=PurePosixPath(item['path'])
    assert not rel.is_absolute() and '..' not in rel.parts
    p=bundle/str(rel)
    assert p.is_file() and not p.is_symlink(),rel
    assert p.stat().st_size==item['size'],rel
    assert hashlib.sha256(p.read_bytes()).hexdigest()==item['sha256'],rel
    seen.add(str(rel))
actual={p.relative_to(bundle).as_posix() for p in bundle.rglob('*') if p.is_file()}
assert actual==seen|{'PACKAGE_FILES.json'},'Unexpected or missing bundle files'
for path in actual:
    assert Path(path).suffix.lower() not in {'.apk','.jar','.img','.pem','.p12','.mp4','.png','.class'},path
    assert '.git' not in PurePosixPath(path).parts,path
sources=json.loads((bundle/'source-files.json').read_text(encoding='utf-8'))
for item in sources:
    p=bundle/'source'/item['path']
    assert hashlib.sha256(p.read_bytes()).hexdigest()==item['sha256'],item['path']
assert len(sources)==94
print('PASS:',len(sources),'source/resource files;',len(manifest),'manifest entries; no firmware, keys, APK/JAR or Git metadata')
