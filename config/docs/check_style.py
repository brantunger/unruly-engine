# The mechanical checks from docs/STYLE.md, for the pages a pull request changes.
# Usage: python config/docs/check_style.py <page.md>...  (from the repository root)
# Reports: more than one callout under an H2, or two in a row; a paragraph or list over 90 words; a line over 120
# characters outside tables and callouts; a flowchart classDef that differs from STYLE.md's palette. Prints each
# page's word count too (STYLE.md: aim for 1,500, split past 2,500). Exit code 1 when anything is reported.
import io
import os
import re
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
PALETTE = {
    'step': 'fill:#e0e7ff,stroke:#6366f1,color:#1e1b4b',
    'decision': 'fill:#fef3c7,stroke:#d97706,color:#451a03',
    'ok': 'fill:#d1fae5,stroke:#059669,color:#064e3b',
    'fail': 'fill:#ffe4e6,stroke:#e11d48,color:#4c0519',
    'yours': 'fill:#f1f5f9,stroke:#64748b,color:#0f172a,stroke-dasharray:4 3',
}
problems = 0


def report(path, line, message):
    global problems
    problems += 1
    if os.environ.get('GITHUB_ACTIONS') == 'true':
        print(f'::warning file={path},line={line}::{message}')
    else:
        print(f'{path}:{line}: {message}')


for page in sys.argv[1:]:
    text = open(page, encoding='utf-8').read()
    inside = False
    h2 = None
    callouts = {}
    prev_callout = False
    block_start, block_words = None, 0
    for i, line in enumerate(text.splitlines() + [''], 1):
        if re.match(r'^\s*(```|~~~)', line):
            inside = not inside
            prev_callout = False
            block_start, block_words = None, 0
            continue
        if inside:
            m = re.match(r'\s*classDef\s+(\w+)\s+(.*\S)', line)
            if m and m.group(1) in PALETTE and m.group(2) != PALETTE[m.group(1)]:
                report(page, i, f'classDef {m.group(1)} differs from the palette in docs/STYLE.md')
            continue
        if line.strip() == '' or line.startswith(('|', '#')):
            if block_words > 90:
                report(page, block_start, f'a {block_words}-word paragraph or list (STYLE.md: about 90 at most)')
            block_start, block_words = None, 0
        else:
            block_start = block_start or i
            block_words += len(line.split())
        if line.startswith('## '):
            h2 = line.strip()
            callouts[h2] = [0, i]
        m = re.match(r'> \[!(\w+)\]', line)
        if m:
            if h2:
                callouts[h2][0] += 1
            if prev_callout:
                report(page, i, 'two callouts in a row')
            prev_callout = True
        elif line.strip() and not line.startswith('>'):
            prev_callout = False
        if len(line) > 120 and not line.startswith(('|', '>')):
            report(page, i, f'{len(line)} characters (the limit is 120 outside tables and callouts)')
    for h, (n, line) in callouts.items():
        if n > 1:
            report(page, line, f'{n} callouts under {h} (STYLE.md: one at most)')
    prose = re.sub(r'```.*?```', '', text, flags=re.S)
    print(f'{page}: {len(prose.split())} words of prose')
sys.exit(1 if problems else 0)
