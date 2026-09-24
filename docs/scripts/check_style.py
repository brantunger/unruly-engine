# The mechanical checks from docs/contributing/style.md, for the pages a pull request changes.
# Usage: python docs/scripts/check_style.py <page.md>...  (from the repository root)
#        python docs/scripts/check_style.py --write-baseline  (rewrites docs/scripts/long-pages.txt)
# Reports: more than one callout under an H2, or two in a row; a paragraph or list over 90 words; a line over 120
# characters outside tables and callouts; a flowchart classDef that differs from style.md's palette; a page over
# 2,500 words of prose (style.md: split past 2,500) unless long-pages.txt lists it, and a listed page that grew past
# its recorded number; a long-pages.txt entry whose page no longer exists. Prints each page's word count too.
# Exit code 1 when anything is reported.
import io
import os
import re
import subprocess
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
PALETTE = {
    'step': 'fill:#e0e7ff,stroke:#6366f1,color:#1e1b4b',
    'decision': 'fill:#fef3c7,stroke:#d97706,color:#451a03',
    'ok': 'fill:#d1fae5,stroke:#059669,color:#064e3b',
    'fail': 'fill:#ffe4e6,stroke:#e11d48,color:#4c0519',
    'yours': 'fill:#f1f5f9,stroke:#64748b,color:#0f172a,stroke-dasharray:4 3',
}
LIMIT = 2500
ROOT = os.path.realpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
BASELINE = 'docs/scripts/long-pages.txt'
BASELINE_HEADER = '''\
# Pages over 2,500 words of prose that were written before check_style.py enforced docs/contributing/style.md's
# length limit ("split a guide that grows past about 2,500 words"). One "path words" pair per line, the path
# relative to the repository root. Of the pages it's given, check_style.py reports a listed page whose count goes
# above its number and any other page over 2,500; it also reports a listed page that no longer exists. A number may
# only go down: when a page shrinks, lower it, and remove the line once the page is at or under 2,500. Never add a
# page or raise a number: split the page. To lower the numbers, run python docs/scripts/check_style.py
# --write-baseline, which rewrites the list with every git-tracked page except CHANGELOG.md that is over 2,500, at
# its current count; then check that the diff only lowers numbers or removes lines.
'''
problems = 0


def report(path, line, message):
    global problems
    problems += 1
    if os.environ.get('GITHUB_ACTIONS') == 'true':
        print(f'::warning file={path},line={line}::{message}')
    else:
        print(f'{path}:{line}: {message}')


def prose_words(text):
    return len(re.sub(r'```.*?```', '', text, flags=re.S).split())


def repo_path(page):
    try:
        return os.path.relpath(os.path.realpath(page), ROOT).replace(os.sep, '/')
    except ValueError:  # another drive on Windows: not in the repository
        return page


if sys.argv[1:] == ['--write-baseline']:
    tracked = subprocess.run(['git', 'ls-files', '*.md'], cwd=ROOT, capture_output=True, text=True, check=True)
    lines = []
    for path in tracked.stdout.splitlines():
        if path != 'CHANGELOG.md' and os.path.exists(os.path.join(ROOT, path)):
            n = prose_words(open(os.path.join(ROOT, path), encoding='utf-8').read())
            if n > LIMIT:
                lines.append(f'{path} {n}\n')
    with open(os.path.join(ROOT, BASELINE), 'w', encoding='utf-8', newline='\n') as f:
        f.write(BASELINE_HEADER + ''.join(lines))
    print(f'{BASELINE}: {len(lines)} pages over {LIMIT:,} words of prose')
    sys.exit(0)

baseline = {}
for i, line in enumerate(open(os.path.join(ROOT, BASELINE), encoding='utf-8').read().splitlines(), 1):
    if line.strip() and not line.lstrip().startswith('#'):
        path, n = line.split()
        baseline[path] = int(n)
        if not os.path.exists(os.path.join(ROOT, path)):
            report(BASELINE, i, f'{path} no longer exists: remove its line')

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
                report(page, i, f'classDef {m.group(1)} differs from the palette in docs/contributing/style.md')
            continue
        if line.strip() == '' or line.startswith(('|', '#')):
            if block_words > 90:
                report(page, block_start, f'a {block_words}-word paragraph or list (style.md: about 90 at most)')
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
            report(page, line, f'{n} callouts under {h} (style.md: one at most)')
    words = prose_words(text)
    print(f'{page}: {words} words of prose')
    recorded = baseline.get(repo_path(page))
    if recorded is not None and words > recorded:
        report(page, 1, f'{words} words of prose, more than the {recorded} recorded in {BASELINE}: a long page may '
                        'not grow. Split it or cut it back, and lower its number there when it shrinks')
    elif recorded is None and words > LIMIT:
        report(page, 1, f"{words} words of prose, over style.md's {LIMIT:,} and not in {BASELINE}: split the page")
sys.exit(1 if problems else 0)
