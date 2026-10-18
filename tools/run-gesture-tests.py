#!/usr/bin/env python3
"""Run the existing pure-Java gesture tests with the exported production sources."""
from pathlib import Path
import shutil,subprocess,tempfile

bundle=Path(__file__).resolve().parent.parent
src=bundle/'source/frameworks/base/libs/WindowManager/Shell/src/com/android/wm/shell/windowdecor/aura'
files=[src/name for name in ('AuraWindowInteraction.java','AuraWindowGestureRecognizer.java','AuraWindowResizeGeometry.java')]
files+=sorted((bundle/'tests/gesture/src').rglob('*.java'))
assert len(files)==11 and all(p.is_file() for p in files)
for name in ('javac','java'):
    if not shutil.which(name):raise SystemExit('JDK 21+ required on PATH: '+name+' not found')
for p in files[:3]:
    assert not any(line.strip().startswith(('import android.','import androidx.','import java.awt.')) for line in p.read_text(encoding='utf-8').splitlines()),p
with tempfile.TemporaryDirectory(prefix='aura-small-window-gesture-') as temporary:
    task_dir=Path(temporary).resolve()
    assert task_dir.parent==Path(tempfile.gettempdir()).resolve() and task_dir.name.startswith('aura-small-window-gesture-')
    subprocess.run(['javac','-Xlint:all','-encoding','UTF-8','-d',str(task_dir),*[str(p) for p in files]],check=True)
    subprocess.run(['java','-cp',str(task_dir),'com.android.wm.shell.windowdecor.aura.GestureProbeMain'],check=True)
