# The fenced code blocks of a Markdown page, for check_docs.py and check_style.py, which import it.
# A fence opens on a line of three or more backticks or tildes (a backtick fence whose info string holds a backtick
# isn't one), and closes, as in CommonMark, only on a line of the same character, at least as many, with nothing
# after. So a ~~~ block may show a ``` line, and a ```` block a ``` pair. Any indent is accepted, so a fence inside a
# list item counts.
import re


def fence_flags(lines):
    """For each line, True when it is a fence line or inside a fenced code block."""
    flags, fence = [], None
    for line in lines:
        if fence is None:
            m = re.match(r'^\s*(`{3,}|~{3,})(.*)$', line)
            if m and not (m.group(1)[0] == '`' and '`' in m.group(2)):
                fence = m.group(1)
                flags.append(True)
                continue
            flags.append(False)
        else:
            m = re.match(r'^\s*(`{3,}|~{3,})\s*$', line)
            if m and m.group(1)[0] == fence[0] and len(m.group(1)) >= len(fence):
                fence = None
            flags.append(True)
    return flags
