#!/usr/bin/env python3
"""Read-only applicability check. Never copies sources or applies patches."""
from pathlib import Path
import argparse,json,subprocess,sys

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('aosp_root',type=Path)
parser.add_argument('--without-sidebar',action='store_true')
args=parser.parse_args()
bundle=Path(__file__).resolve().parent.parent
target=args.aosp_root.resolve()
series=json.loads((bundle/'patch-series.json').read_text(encoding='utf-8'))
baseline=json.loads((bundle/'baseline.json').read_text(encoding='utf-8'))['repositories']
failed=False;shown=set()
for item in series:
    if item['optional'] or (args.without_sidebar and item['patch'].startswith('patches/06-')):continue
    repo=target/item['repository']
    if not repo.is_dir():
        print('MISSING repository:',repo);failed=True;continue
    if item['repository'] not in shown:
        p=subprocess.run(['git','-C',str(repo),'rev-parse','HEAD'],capture_output=True,text=True)
        commit=p.stdout.strip()
        print(item['repository'],commit,'recorded-baseline' if commit==baseline[item['repository']]['commit'] else 'different baseline; manual review required')
        shown.add(item['repository'])
    p=subprocess.run(['git','-C',str(repo),'apply','--check',str(bundle/item['patch'])],capture_output=True,text=True)
    if p.returncode:
        print('CONFLICT:',item['patch']);print(p.stderr.strip());failed=True
    else:print('CHECK OK:',item['patch'])
print('No source files were changed. Optional patch 07 requires patch 05 already applied.')
sys.exit(1 if failed else 0)
