#!/usr/bin/env python3
"""Hash a Git working tree or commit's source files without including .git/build output."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]


def git(*args: str) -> bytes:
    return subprocess.run(
        ["git", *args], cwd=ROOT, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE
    ).stdout


def excluded(path: str, prefixes: list[str]) -> bool:
    normalized = path.replace(os.sep, "/")
    return any(normalized == prefix.rstrip("/") or normalized.startswith(prefix.rstrip("/") + "/") for prefix in prefixes)


def working_tree_entries(excludes: list[str]) -> list[tuple[bytes, bytes, bytes]]:
    output = git("ls-files", "--cached", "--others", "--exclude-standard", "-z")
    entries: list[tuple[bytes, bytes, bytes]] = []
    for raw_path in sorted(path for path in output.split(b"\0") if path):
        relative = os.fsdecode(raw_path)
        if excluded(relative, excludes):
            continue
        path = ROOT / relative
        if not path.exists() and not path.is_symlink():
            raise FileNotFoundError(f"tracked source is missing: {relative}")
        file_mode = path.lstat().st_mode
        mode = b"120000" if stat.S_ISLNK(file_mode) else b"100755" if file_mode & 0o111 else b"100644"
        contents = os.readlink(path).encode("utf-8") if stat.S_ISLNK(file_mode) else path.read_bytes()
        entries.append((raw_path, mode, contents))
    return entries


def commit_entries(commit: str, excludes: list[str]) -> list[tuple[bytes, bytes, bytes]]:
    output = git("ls-tree", "-rz", "--full-tree", commit)
    entries: list[tuple[bytes, bytes, bytes]] = []
    for record in output.split(b"\0"):
        if not record:
            continue
        metadata, raw_path = record.split(b"\t", 1)
        mode, object_type, object_id = metadata.split(b" ", 2)
        if object_type != b"blob":
            continue
        relative = os.fsdecode(raw_path)
        if excluded(relative, excludes):
            continue
        contents = git("cat-file", "blob", object_id.decode("ascii"))
        entries.append((raw_path, mode, contents))
    return sorted(entries, key=lambda entry: entry[0])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--commit", help="hash committed files at this revision instead of the working tree")
    parser.add_argument("--exclude", action="append", default=[], help="exclude an exact path or directory prefix")
    args = parser.parse_args()
    try:
        entries = commit_entries(args.commit, args.exclude) if args.commit else working_tree_entries(args.exclude)
    except (OSError, subprocess.CalledProcessError, ValueError) as failure:
        print(f"source manifest failed: {failure}", file=sys.stderr)
        return 1

    manifest = hashlib.sha256()
    byte_count = 0
    for path, mode, contents in entries:
        manifest.update(path + b"\0" + mode + b"\0" + hashlib.sha256(contents).hexdigest().encode("ascii") + b"\n")
        byte_count += len(contents)
    print(json.dumps({
        "sha256": manifest.hexdigest(),
        "files": len(entries),
        "bytes": byte_count,
        "revision": args.commit,
        "excluded": args.exclude,
        "algorithm": "SHA-256 of sorted path NUL normalized-mode NUL file-SHA256 LF records",
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
