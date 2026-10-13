#! /usr/bin/env python3
# -*- coding: utf-8 -*-
"""Validate a manually specified release version.

usage: validate-version.py X.Y.Z < tags
stdin: existing `v*` tags, one per line, ordered by `sort -V` (ascending).
"""
import re
import sys


NUM = r"(0|[1-9][0-9]*)"


def parse(text):
    m = re.fullmatch(rf"{NUM}\.{NUM}\.{NUM}", text)
    return tuple(int(g) for g in m.groups()) if m else None


def validate_version(version, tags):
    target = parse(version)
    if target is None:
        raise ValueError(f"version must be X.Y.Z (digits only): {version!r}")
    tags = [t.strip() for t in tags if t.strip()]
    if f"v{version}" in tags:
        raise ValueError(f"tag v{version} already exists")
    if tags:
        m = re.match(rf"v{NUM}\.{NUM}\.{NUM}", tags[-1])
        if m is None:
            raise ValueError(f"cannot parse the latest tag: {tags[-1]!r}")
        latest = tuple(int(g) for g in m.groups())
        if target <= latest:
            raise ValueError(f"version {version} must be greater than the latest tag {tags[-1]}")
    return version


if __name__ == "__main__":
    try:
        print(validate_version(sys.argv[1], sys.stdin.read().splitlines()))
    except (ValueError, IndexError) as e:
        print(f"error: {e}", file=sys.stderr)
        sys.exit(1)
