#!/usr/bin/env python3
"""Verify fonts, notices and canonical locale resources in an actual built iOS app."""

import hashlib
from pathlib import Path
import plistlib
import sys


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: verify-bundled-fonts.py path/to/Pully.app")
    app = Path(sys.argv[1])
    repository = Path(__file__).resolve().parents[2]
    originals = repository / "android/app/src/main/res/font"
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
    for name in ["cuelume/recipes.json", "licenses/cuelume.txt"]:
        source = repository / "android/app/src/main/assets" / name
        bundled = app / ("cuelume.txt" if name.startswith("licenses/") else name)
        if not bundled.is_file() or bundled.read_bytes() != source.read_bytes():
            raise SystemExit("canonical Cuelume recipes or MIT notice are not bundled")

    markdown_notices = repository / "android/app/src/main/assets/licenses/markdown-parsers.txt"
    markdown_bundled = app / markdown_notices.name
    if not markdown_bundled.is_file() or markdown_bundled.read_bytes() != markdown_notices.read_bytes():
        raise SystemExit("original Markdown parser license notices are not bundled")
    locales = verify_locales(repository, app)
    privacy_source = repository / "ios/Pully/PrivacyInfo.xcprivacy"
    privacy_bundled = app / "PrivacyInfo.xcprivacy"
    if not privacy_bundled.is_file() or plistlib.loads(privacy_bundled.read_bytes()) != plistlib.loads(privacy_source.read_bytes()):
        raise SystemExit("app-owned required-reason API manifest is not bundled")
    google_manifests = [path for path in app.rglob("PrivacyInfo.xcprivacy") if "GoogleSignIn" in str(path.relative_to(app))]
    if not google_manifests or not any(plistlib.loads(path.read_bytes()).get("NSPrivacyCollectedDataTypes") for path in google_manifests):
        raise SystemExit("Google Sign-In's own privacy resource is not bundled")
    print(f"Verified {len(fonts)} original Pully fonts and {locales} canonical locales in {app.name}")


def verify_locales(repository: Path, app: Path) -> int:
    canonical = repository / "packages/app/src/i18n/locales"
    android = repository / "android/app/src/main/assets/i18n"
    bundled = app / "i18n"
    expected = {path.relative_to(canonical) for path in canonical.rglob("*.json")}
    if not expected:
        raise SystemExit("canonical web locale sources missing")
    info = app / "Info.plist"
    if not info.is_file():
        raise SystemExit("built iOS Info.plist missing")
    localizations = plistlib.loads(info.read_bytes()).get("CFBundleLocalizations", [])
    if len(localizations) != len(expected) or set(localizations) != {name.stem for name in expected}:
        raise SystemExit("native iOS supported locales differ from canonical web source")
    for label, directory in [("Android", android), ("bundled iOS", bundled)]:
        actual = {path.relative_to(directory) for path in directory.rglob("*.json")}
        if actual != expected:
            missing = ", ".join(str(path) for path in sorted(expected - actual)) or "none"
            extra = ", ".join(str(path) for path in sorted(actual - expected)) or "none"
            raise SystemExit(f"{label} locale set differs from canonical web source (missing: {missing}; extra: {extra})")
        for name in sorted(expected):
            if (directory / name).read_bytes() != (canonical / name).read_bytes():
                raise SystemExit(f"{label} locale differs from canonical web source: {name}")
    return len(expected)


if __name__ == "__main__":
    main()
