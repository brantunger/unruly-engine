# Runs check_docs.py and check_style.py on the pages in config/docs-checks/, each holding a case the scripts once got
# wrong, and compares every line they print with the verdicts below: a missing line is a case they no longer catch, an
# extra one a false report. The pages are stored as .md.txt, since check_docs.py would report their broken links
# otherwise, and are copied as .md into a temporary git repository with a copy of the scripts.
# Usage: python docs/scripts/check_fixtures.py [directory]  (from the repository root; the directory holding the
# scripts to run, docs/scripts by default). Exit code 1 when a verdict differs.
import glob
import io
import os
import shutil
import subprocess
import sys
import tempfile

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
ROOT = os.path.realpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
FIXTURES = os.path.join(ROOT, 'config', 'docs-checks')
PROBE = 'docs/probe/'
# Per page, what check_docs.py prints about it, then what check_style.py prints when given it alone.
VERDICTS = {
    'angle-brackets': (
        [':5: no such page.md: no file docs/probe/no such page.md'],
        [': 8 words of prose']),
    'backtick-code-span-line': (
        [':5: no-such-page-5.md: no file docs/probe/no-such-page-5.md'],
        [': 32 words of prose']),
    'classdef-semicolon': (
        [],
        [':7: classDef fail differs from the palette in docs/contributing/style.md', ': 7 words of prose']),
    'fence-in-list-item': (
        [':9: no-such-page-6.md: no file docs/probe/no-such-page-6.md'],
        [': 20 words of prose']),
    'four-backtick-fence': (
        [':7: no-such-page-4.md: no file docs/probe/no-such-page-4.md'],
        [': 12 words of prose']),
    'heading-after-tilde-fence': (
        [],
        [': 10 words of prose']),
    'inline-link': (
        [':3: no-such-plain.md: no file docs/probe/no-such-plain.md'],
        [': 7 words of prose']),
    'link-titles-and-html': (
        [':3: no-such-page.md: no file docs/probe/no-such-page.md',
         ':5: no-such-page-2.md: no file docs/probe/no-such-page-2.md',
         ':7: no-such-page-3.md: no file docs/probe/no-such-page-3.md'],
        [': 17 words of prose']),
    'links-to-headings': (
        [':7: repeated-h2.md#notes-2: no heading in docs/probe/repeated-h2.md has the anchor #notes-2'],
        [': 13 words of prose']),
    'one-h2-two-callouts': (
        [],
        [':3: 2 callouts under ## Notes (style.md: one at most)', ': 17 words of prose']),
    'reference-definitions': (
        [':5: no-such-page.md: no file docs/probe/no-such-page.md',
         ':6: tilde-fence-backtick-pair.md#no-such-anchor: no heading in docs/probe/tilde-fence-backtick-pair.md has '
         'the anchor #no-such-anchor'],
        [': 30 words of prose']),
    'repeated-h2': (
        [],
        [':3: 2 callouts under ## Notes (style.md: one at most)', ': 24 words of prose']),
    'tilde-block-words': (
        [],
        [': 7 words of prose']),
    'tilde-fence-backtick-pair': (
        [],
        [': 9 words of prose']),
    'tilde-fence-one-backtick-line': (
        [':7: no-such-page.md: no file docs/probe/no-such-page.md'],
        [':9: 135 characters (the limit is 120 outside tables and callouts)', ': 37 words of prose']),
}


def run(cwd, *args):
    env = {k: v for k, v in os.environ.items() if k != 'GITHUB_ACTIONS'}
    env['PYTHONUTF8'] = '1'
    out = subprocess.run([sys.executable, *args], cwd=cwd, env=env, capture_output=True, text=True, encoding='utf-8')
    return out.returncode, out.stdout.splitlines() + out.stderr.splitlines()


def compare(what, expected, code, actual):
    """Both scripts end with one line that isn't a problem, and exit with 1 when they report one."""
    missing = [line for line in expected if line not in actual]
    extra = [line for line in actual if line not in expected]
    for line in missing:
        print(f'{what}: missing  {line}')
    for line in extra:
        print(f'{what}: extra    {line}')
    if code != (1 if len(expected) > 1 else 0):
        print(f'{what}: exit code {code}')
        return False
    return not missing and not extra


scripts = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, 'docs', 'scripts')
pages = sorted(os.path.basename(f)[:-len('.md.txt')] for f in glob.glob(os.path.join(FIXTURES, '*.md.txt')))
if pages != sorted(VERDICTS):
    print(f'{FIXTURES} holds {pages}, but the verdicts are for {sorted(VERDICTS)}')
    sys.exit(1)

repo = tempfile.mkdtemp()
try:
    subprocess.run(['git', 'init', '-q'], cwd=repo, check=True)
    os.makedirs(os.path.join(repo, 'docs', 'scripts'))
    os.makedirs(os.path.join(repo, PROBE))
    for script in glob.glob(os.path.join(scripts, '*.py')):
        shutil.copy(script, os.path.join(repo, 'docs', 'scripts'))
    with open(os.path.join(repo, 'docs', 'scripts', 'long-pages.txt'), 'w', encoding='utf-8', newline='\n') as f:
        f.write('# No page is listed: the fixtures are short.\n')
    for page in pages:
        shutil.copy(os.path.join(FIXTURES, page + '.md.txt'), os.path.join(repo, PROBE, page + '.md'))

    ok = True
    code, actual = run(repo, 'docs/scripts/check_docs.py')
    expected = [PROBE + page + '.md' + line for page in pages for line in VERDICTS[page][0]]
    expected.append(f'{len(pages)} pages checked, {len(expected)} problems')
    ok &= compare('check_docs.py', expected, code, actual)
    for page in pages:
        code, actual = run(repo, 'docs/scripts/check_style.py', PROBE + page + '.md')
        expected = [PROBE + page + '.md' + line for line in VERDICTS[page][1]]
        ok &= compare(f'check_style.py {page}', expected, code, actual)
finally:
    shutil.rmtree(repo, ignore_errors=True)

print(f'{len(pages)} fixture pages: ' + ('every verdict as expected' if ok else 'some verdicts differ'))
sys.exit(0 if ok else 1)
