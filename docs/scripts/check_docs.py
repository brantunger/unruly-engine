# Checks every Markdown page in the repository, and the Javadoc's links to them:
# - a relative link whose file doesn't exist, or whose #anchor matches no heading of the page it points to: an inline
#   link, with or without <> around its destination, spaces inside its parentheses or a title, a reference
#   definition ([name]: target), and an HTML <a href> or <img src>, its value quoted or not, in either letter case and with
#   spaces around = allowed; each tag is read attribute by attribute, so data-href, data-src, src on <a> and text
#   inside another attribute's value are never taken for a link (a tag split over two lines isn't checked);
# - a link to https://github.com/brantunger/unruly-engine/blob/main/<page>.md#<anchor>, from a page, a .java file or
#   the Javadoc overview, whose page or anchor doesn't exist on this branch;
# - a table row holding `||` outside code, which is two rows joined into one line;
# - an SVG a link or <img src> points at whose root <svg> has no role="img", or no non-empty <title> or <desc> among
#   its children (docs/contributing/style.md asks for all three). Not checked: an SVG shown only through
#   <picture><source>, a raw.githubusercontent.com URL, or an <img> tag split over two lines.
# Usage: python docs/scripts/check_docs.py  (from the repository root). Exit code 1 when anything is reported.
import io
import os
import re
import subprocess
import sys
import unicodedata
from xml.etree import ElementTree

from fences import fence_flags

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
BLOB = 'https://github.com/brantunger/unruly-engine/blob/main/'
# An <a> or <img> tag, whose attributes may hold a quoted '>', then each attribute of it, its value quoted or not.
HTML_TAG = re.compile(r'''<(a|img)(\s(?:"[^"]*"|'[^']*'|[^"'>])*)>''', re.IGNORECASE)
HTML_ATTRIBUTE = re.compile(r'''([^\s"'>/=]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+)))?''')
HTML_LINK = {'a': 'href', 'img': 'src'}
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
    lines = open(path, encoding='utf-8').read().splitlines()
    return ['' if fenced else line for line, fenced in zip(lines, fence_flags(lines))]


pages = files('*.md')
anchors = {}
svg_gaps = {}
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


def svg_problem(target):
    """What is wrong with an SVG: "lacks" and what it lacks of role="img" on its root <svg> and a non-empty <title>
    and <desc> among the root's children, or that it isn't well-formed XML; empty when nothing is. XML comments, the
    XML declaration and a doctype are skipped, as a parser skips them."""
    if target not in svg_gaps:
        try:
            root = ElementTree.parse(target).getroot()
        except ElementTree.ParseError as e:
            svg_gaps[target] = f"isn't well-formed XML: {e}"
            return svg_gaps[target]
        local = root.tag.rpartition('}')[2]
        missing = [] if local == 'svg' and root.get('role') == 'img' else ['role="img"']
        children = {child.tag.rpartition('}')[2]: ''.join(child.itertext()).strip() for child in reversed(root)
                    if isinstance(child.tag, str)}
        missing += [f'<{tag}>' for tag in ('title', 'desc') if not children.get(tag)]
        svg_gaps[target] = 'lacks ' + ', '.join(missing) if missing else ''
    return svg_gaps[target]


def check_target(path, line, link, target, fragment):
    if not os.path.exists(target):
        report(path, line, f'{link}: no file {target}')
    elif fragment and target.endswith('.md') and fragment not in anchors.get(target, set()):
        report(path, line, f'{link}: no heading in {target} has the anchor #{fragment}')
    elif target.lower().endswith('.svg') and svg_problem(target):
        report(path, line, f'{link}: {target} {svg_problem(target)} (style.md: role="img", a <title> and a <desc>, '
                           'neither empty)')


def check_absolute(path, line, text):
    for link in re.findall(re.escape(BLOB) + r'([^\s"\'<>)]+)', text):
        link = link.rstrip('.,;:')
        target, _, fragment = link.partition('#')
        check_target(path, line, BLOB + link, target, fragment)


for page in pages:
    for i, line in enumerate(outside_fences(page), 1):
        line = re.sub(r'`[^`]*`', '', line)
        check_absolute(page, i, line)
        links = [a or b for a, b in
                 re.findall(r'''\]\(\s*(?:<([^<>]+)>|([^)\s<]+))(?:\s+(?:"[^"]*"|'[^']*'|\([^)]*\)))?\s*\)''', line)]
        m = re.match(r'^\s{0,3}\[(?!\^)[^\]]+\]:\s*(?:<([^<>]+)>|(\S+))', line)  # not a footnote: [^1]: text
        if m:
            links.append(m.group(1) or m.group(2))
        for tag in HTML_TAG.finditer(line):
            links += [double or single or bare for name, double, single, bare in HTML_ATTRIBUTE.findall(tag.group(2))
                      if name.lower() == HTML_LINK[tag.group(1).lower()]]
        for link in links:
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
