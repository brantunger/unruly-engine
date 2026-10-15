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
#   <picture><source>, a raw.githubusercontent.com URL, or an <img> tag split over two lines;
# - a value a page copies from the build that differs from the build's: README.md's mvel2-<version>.jar and the version
#   it applies org.gradlex.extra-java-module-info with, against the mvel2 and module-info-plugin entries of
#   gradle/libs.versions.toml; docs/native-image.md's packages= lines and native-image command for the strict image,
#   and the number of its packages the page writes out ("its six packages", "the library's six packages"), against
#   the .github/workflows/ci.yml step that passes "-H:ThrowMissingRegistrationErrors=$packages". Skipped when the
#   build file isn't there, as in the repository check_fixtures.py makes.
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
CATALOG = 'gradle/libs.versions.toml'
WORKFLOW = '.github/workflows/ci.yml'
STRICT_OPTION = '"-H:ThrowMissingRegistrationErrors=$packages"'
NUMBERS = ['zero', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten', 'eleven', 'twelve']
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


def raw_lines(path):
    """The file's lines, code blocks included: the copies checked below sit inside them."""
    return open(path, encoding='utf-8').read().splitlines()


def check_versions(page):
    """The mvel2 jar name and the extra-java-module-info plugin version the page holds, against the version catalog.
    Each catalog entry is a "group:artifact:version" string; its last part is the version."""
    if not os.path.exists(page):
        return
    if not os.path.exists(CATALOG):
        report(page, 1, f'copies versions from {CATALOG}, which is missing')
        return
    catalog = open(CATALOG, encoding='utf-8').read()
    versions = {}
    for alias in ('mvel2', 'module-info-plugin'):
        m = re.search(rf'^{alias}\s*=\s*"[^"]*:([^:"]+)"\s*$', catalog, re.MULTILINE)
        if m:
            versions[alias] = m.group(1)
        else:
            report(CATALOG, 1, f'no {alias} = "group:artifact:version" line, which {page} copies the version of')
    for i, line in enumerate(raw_lines(page), 1):
        for found in re.findall(r'mvel2-(\d[^\s`]*?)\.jar', line):
            if found != versions.get('mvel2', found):
                report(page, i, f'mvel2-{found}.jar: {CATALOG} has mvel2 {versions["mvel2"]}')
        for found in re.findall(r'''org\.gradlex\.extra-java-module-info['"]?\s+version\s+['"]([^'"]+)['"]''', line):
            if found != versions.get('module-info-plugin', found):
                report(page, i, f'extra-java-module-info {found}: {CATALOG} has {versions["module-info-plugin"]}')


def strict_image(path, fenced_only):
    """The strict image's commands in a file: the packages= lines and the native-image command, every line trimmed,
    around the one line that passes "-H:ThrowMissingRegistrationErrors=$packages", with the number of the first; on a
    page, only a line inside a fenced code block counts. Without exactly one such line, None and the lines found."""
    lines = raw_lines(path)
    inside = fence_flags(lines) if fenced_only else [True] * len(lines)
    lines = [line.strip() for line in lines]
    hits = [i for i, line in enumerate(lines) if inside[i] and STRICT_OPTION in line]
    if len(hits) != 1:
        return None, [i + 1 for i in hits]
    start = end = hits[0]
    while start > 0 and lines[start - 1].endswith('\\'):  # back to the command's first line
        start -= 1
    while start > 0 and lines[start - 1].startswith('packages='):
        start -= 1
    while lines[end].endswith('\\') and end + 1 < len(lines):
        end += 1
    return lines[start:end + 1], start + 1


def check_strict_image(page):
    """The page's copy of the strict image's commands against the workflow's, and each count of packages the page
    writes out, as "its six packages" or "the library's six packages", against the packages= lines."""
    if not os.path.exists(page):
        return
    if not os.path.exists(WORKFLOW):
        report(page, 1, f"copies the strict image's commands from {WORKFLOW}, which is missing")
        return
    (want_lines, want_at), (have_lines, first) = strict_image(WORKFLOW, False), strict_image(page, True)
    if want_lines is None:
        report(WORKFLOW, want_at[0] if want_at else 1, f'{len(want_at)} lines pass {STRICT_OPTION}, not one '
                                                       f'(lines {want_at}); {page} copies that step')
        return
    if have_lines is None:
        report(page, first[0] if first else 1, f'{len(first)} code-block lines pass {STRICT_OPTION}, not one '
                                               f'(lines {first}); {WORKFLOW} has one such step')
        return
    for n in range(max(len(want_lines), len(have_lines))):
        want = want_lines[n] if n < len(want_lines) else ''
        have = have_lines[n] if n < len(have_lines) else ''
        if want != have:
            report(page, first + min(n, len(have_lines) - 1),
                   f'the strict image\'s commands differ from {WORKFLOW}: expected "{want}", found "{have}"')
            break
    packages = [p for line in want_lines if line.startswith('packages=')
                for p in line.partition('=')[2].split(',') if p != '$packages']
    word = NUMBERS[len(packages)] if len(packages) < len(NUMBERS) else str(len(packages))
    prose = '\n'.join(outside_fences(page))  # a phrase may wrap onto the next line
    for m in re.finditer(r"\b(?:its|the library's)\s+(" + '|'.join(NUMBERS) + r'|\d+)\s+packages\b', prose):
        if m.group(1) != word:
            report(page, prose.count('\n', 0, m.start()) + 1,
                   f'"{m.group(1)} packages": {WORKFLOW} lists {len(packages)} for the strict image')


check_versions('README.md')
check_strict_image('docs/native-image.md')

print(f'{len(pages)} pages checked, {problems} problems')
sys.exit(1 if problems else 0)
