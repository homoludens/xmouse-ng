#!/usr/bin/env python3
"""Task 1.4 migration audit against the pre-conversion source (no Android runtime).

Run from any directory with Python 3 and Git. This deliberately checks the exact
bounded switch-to-if conversion, not future intentional changes to these actions.
Strings and Java tokens must match; only comments/formatting are ignored.
"""

import re
import subprocess
from pathlib import Path
from xml.etree import ElementTree


ROOT = Path(__file__).resolve().parents[2]
BASELINE = "fa8b529810e5bcc0c2602313751eefc7899bd9b8"
MAIN = "app/src/main/java/com/stripe1/xmouse/MainActivity.java"
TOKEN = re.compile(
    r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|'
    r"'(?:\\.|[^'\\])*'|[A-Za-z_$][\w$]*|\d+|==|\|\||\S"
)


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def git(*args):
    return subprocess.check_output(["git", "-C", str(ROOT), *args])


def tokens(source):
    return [t for t in TOKEN.findall(source) if not t.startswith(("//", "/*"))]


def block_end(source, start):
    require(source[start] == "{", "Expected opening brace")
    depth = 0
    for index in range(start, len(source)):
        depth += (source[index] == "{") - (source[index] == "}")
        if depth == 0:
            return index
    raise AssertionError("Unbalanced block")


def convert_switch(source, method, selector):
    """Derive the only permitted replacement directly from baseline case bodies."""
    start = source.index(method)
    switch = source.index("switch", start)
    opening = source.index("{", switch)
    closing = block_end(source, opening)
    require(source[switch:opening] == tokens(f"switch ({selector})"),
            f"Unexpected selector in {method}")
    labels = []
    depth = 0
    for index in range(opening + 1, closing):
        token = source[index]
        if depth == 0 and token in ("case", "default"):
            labels.append(index)
        depth += (token == "{") - (token == "}")

    replacement = tokens(f"int id = {selector};")
    pending = []
    mappings = []
    for index, label in enumerate(labels):
        colon = source.index(":", label)
        end = labels[index + 1] if index + 1 < len(labels) else closing
        body = source[colon + 1:end]
        is_default = source[label] == "default"
        if not is_default:
            pending.append(source[label + 1:colon])
        if not body:
            continue  # Grouped case labels share the next nonempty body.
        require(body[-2:] == ["break", ";"], f"Missing terminal break in {method}")
        body = body[:-2]
        if is_default:
            require(not body and not pending, f"Nonempty default in {method}")
            continue
        require(pending, f"Missing case labels in {method}")
        if mappings:
            replacement.append("else")
        replacement.extend(["if", "("])
        for number, case in enumerate(pending):
            require(case[:4] == ["R", ".", "id", "."] and len(case) == 5,
                    f"Unexpected resource case: {case}")
            if number:
                replacement.append("||")
            replacement.extend(["id", "==", *case])
        replacement.extend([")", "{", *body, "}"])
        mappings.extend(case[-1] for case in pending)
        pending = []
    require(not pending, f"Dangling labels in {method}")
    return source[:switch] + replacement + source[closing + 1:], mappings


def main():
    expected = tokens(git("show", f"{BASELINE}:{MAIN}").decode())
    expected, mouse = convert_switch(expected, "xMouseClickMouse", "v.getId()")
    expected, menu = convert_switch(expected, "onOptionsItemSelected", "item.getItemId()")
    require(len(mouse) == 10 and len(menu) == 10, "Baseline action count changed")
    require(expected == tokens((ROOT / MAIN).read_text()),
            "MainActivity differs from the branch-preserving conversion")

    # Include unchanged dynamic drawer mappings, command backends and XML bindings.
    paths = git("ls-tree", "-r", "--name-only", BASELINE, "app/src/main").decode().splitlines()
    for path in paths:
        if (path.endswith(".java") and path != MAIN) or "/res/" in path:
            require((ROOT / path).read_bytes() == git("show", f"{BASELINE}:{path}"),
                    f"Unexpected source/resource change: {path}")
    resource_ids = {
        item.attrib["{http://schemas.android.com/apk/res/android}id"].split("/")[-1]
        for item in ElementTree.parse(ROOT / "app/src/main/res/menu/main.xml").iter("item")
    }
    require(set(menu) == resource_ids, "Menu XML and dispatched actions differ")
    print(f"PASS: {len(mouse)} mouse/joystick IDs and {len(menu)} menu actions retain exact bodies")
    print("PASS: grouped cases, unknown-ID no-op, superclass fallback, drawer and desktop commands unchanged")
    print("Menu IDs: " + ", ".join(menu))


if __name__ == "__main__":
    main()
