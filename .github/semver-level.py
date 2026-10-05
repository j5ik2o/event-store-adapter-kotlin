#!/usr/bin/env python3
"""Determine the level from git log --format='%s%x1f%b%x1e'."""

import re
import sys


SUBJECT = re.compile(r"^([a-z]+)(?:\([^\r\n]*\))?(!)?:")
BREAKING = re.compile(r"^BREAKING(?: CHANGE|-CHANGE):", re.MULTILINE)


def semver_level(commits):
    level = None
    for record in commits.split("\x1e"):
        if not record.strip():
            continue
        subject, body = record.lstrip("\r\n").split("\x1f", 1)
        match = SUBJECT.match(subject)
        if BREAKING.match(subject) or BREAKING.search(body) or (match and match.group(2)):
            return "major"
        if match:
            if match.group(1) in ("feat", "revert"):
                level = "minor"
            elif level is None:
                level = "patch"
    if level is None:
        raise ValueError("No eligible commits to determine a version level")
    return level


def main():
    try:
        print(semver_level(sys.stdin.read()))
    except ValueError as error:
        print(error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
