# Checks every Markdown page in the repository, and the Javadoc's links to them:
# - a relative link whose file doesn't exist, or whose #anchor matches no heading of the page it points to;
# - a link to https://github.com/brantunger/unruly-engine/blob/main/<page>.md#<anchor>, from a page, a .java file or
#   the Javadoc overview, whose page or anchor doesn't exist on this branch;
# - a table row holding `||` outside code, which is two rows joined into one line.
# Usage: python scripts/docs/check_docs.py  (from the repository root). Exit code 1 when anything is reported.
import io
import os
import re
import subprocess
import sys
import unicodedata

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
BLOB = 'https://github.com/brantunger/unruly-engine/blob/main/'
problems = 0


def report(path, line, message):
    global problems
    problems += 1
    if os.environ.get('GITHUB_ACTIONS') == 'true':
        print(f'::error file={path},line={line}::{message}')
    else:
        print(f'{path}:{line}: {message}')


def files(pattern):
    out = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', pattern],
                                  text=True, encoding='utf-8')
    return [f for f in out.splitlines() if os.path.exists(f)]


def slug(heading):
    """The anchor github.com gives a heading. It keeps U+FE0F, the emoji variation selector (checked 2026-09-16)."""
    h = heading.strip().lower()
    h = re.sub(r'<[^>]+>', '', h).replace('`', '')
    h = re.sub(r'\[([^\]]*)\]\([^)]*\)', r'\1', h)
    kept = [c for c in h
            if c in ' -_' or unicodedata.category(c)[0] in 'LN' or unicodedata.category(c) in ('Mn', 'Mc')]
    return ''.join(kept).replace(' ', '-')


def outside_fences(path):
    """The page's lines, with every line inside a fenced code block blanked, so line numbers stay right."""
    lines, inside = [], False
    for line in open(path, encoding='utf-8').read().splitlines():
        if re.match(r'^\s*(```|~~~)', line):
            inside = not inside
            lines.append('')
            continue
        lines.append('' if inside else line)
    return lines


pages = files('*.md')
anchors = {}
for page in pages:
    seen, found = {}, set()
    for line in outside_fences(page):
        m = re.match(r'^(#{1,6})\s+(.*?)\s*#*\s*$', line)
        if m:
            a = slug(m.group(2))
            n = seen.get(a, 0)
            seen[a] = n + 1
            found.add(a if n == 0 else f'{a}-{n}')
    anchors[page] = found


def check_target(path, line, link, target, fragment):
    if not os.path.exists(target):
        report(path, line, f'{link}: no file {target}')
    elif fragment and target.endswith('.md') and fragment not in anchors.get(target, set()):
        report(path, line, f'{link}: no heading in {target} has the anchor #{fragment}')


def check_absolute(path, line, text):
    for link in re.findall(re.escape(BLOB) + r'([^\s"\'<>)]+)', text):
        link = link.rstrip('.,;:')
        target, _, fragment = link.partition('#')
        check_target(path, line, BLOB + link, target, fragment)


for page in pages:
    for i, line in enumerate(outside_fences(page), 1):
        line = re.sub(r'`[^`]*`', '', line)
        check_absolute(page, i, line)
        for link in re.findall(r'\]\(([^)\s]+)(?:\s+"[^"]*")?\)', line):
            if link.startswith(('http:', 'https:', 'mailto:')):
                continue
            path, _, fragment = link.partition('#')
            target = os.path.normpath(os.path.join(os.path.dirname(page), path)).replace(os.sep, '/') if path else page
            check_target(page, i, link, target, fragment)
        if line.lstrip().startswith('|') and '||' in line.replace('\\|', ''):
            report(page, i, 'a table row holds "||": two rows joined into one line?')

for source in files('*.java') + files('config/javadoc/*.html'):
    for i, line in enumerate(open(source, encoding='utf-8').read().splitlines(), 1):
        check_absolute(source, i, line)

print(f'{len(pages)} pages checked, {problems} problems')
sys.exit(1 if problems else 0)
