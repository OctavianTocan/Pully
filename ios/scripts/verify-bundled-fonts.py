#!/usr/bin/env python3
"""Reject absent or changed branded fonts in an actual built iOS app."""

import hashlib
from pathlib import Path
import sys


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: verify-bundled-fonts.py path/to/Pully.app")
    app = Path(sys.argv[1])
    originals = Path(__file__).resolve().parents[2] / "android/app/src/main/res/font"
    fonts = sorted(originals.glob("*.ttf"))
    if not app.is_dir() or not fonts:
        raise SystemExit("built app or source fonts missing")
    for source in fonts:
        bundled = app / source.name
        if not bundled.is_file():
            raise SystemExit(f"font is not bundled: {source.name}")
        if hashlib.sha256(source.read_bytes()).digest() != hashlib.sha256(bundled.read_bytes()).digest():
            raise SystemExit(f"bundled font differs from Android source: {source.name}")
    license_source = originals.parents[1] / "assets/licenses/code-fonts.txt"
    license_bundled = app / "code-fonts.txt"
    if not license_bundled.is_file() or license_bundled.read_bytes() != license_source.read_bytes():
        raise SystemExit("original code-font license notices are not bundled")
    print(f"Verified {len(fonts)} original Pully fonts in {app.name}")


if __name__ == "__main__":
    main()
