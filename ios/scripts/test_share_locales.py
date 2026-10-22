"""Check canonical Share catalog data, real resource wiring and the built-bundle verifier."""

import importlib.util
import json
from pathlib import Path
import plistlib
import re
import shutil
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("verify_bundled_fonts", Path(__file__).with_name("verify-bundled-fonts.py"))
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class ShareLocaleTests(unittest.TestCase):
    def test_extension_resource_phase_uses_the_existing_canonical_mirror(self):
        project = (ROOT / "ios/Pully.xcodeproj/project.pbxproj").read_text()
        reference = re.search(r"(\w+) /\* i18n \*/ = \{isa = PBXFileReference;[^\n]*path = ../android/app/src/main/assets/i18n;", project)
        self.assertIsNotNone(reference)
        build = re.search(r"(\w+) /\* i18n in Share Resources \*/ = \{isa = PBXBuildFile; fileRef = " + reference[1], project)
        self.assertIsNotNone(build)
        phase = re.search(r"(\w+) /\* Resources \*/ = \{isa = PBXResourcesBuildPhase;[^\n]*files = \([^\n]*" + build[1], project)
        self.assertIsNotNone(phase)
        target = re.search(r"/\* PullyShare \*/ = \{\s*isa = PBXNativeTarget;.*?\n\t\t\};", project, re.S)
        self.assertIsNotNone(target)
        self.assertIn(phase[1], target[0])
        canonical = ROOT / "packages/app/src/i18n/locales"
        info = plistlib.loads((ROOT / "ios/PullyShare/Info.plist").read_bytes())
        self.assertEqual(set(info["CFBundleLocalizations"]), {p.stem for p in canonical.glob("*.json")})

    def test_every_actual_canonical_catalog_and_android_mirror_contains_share_copy(self):
        canonical = ROOT / "packages/app/src/i18n/locales"
        expected = {"title", "reading", "save", "unsupported", "hint", "unavailable", "busy", "noSource", "staleSource", "invalidRecord", "failure"}
        for source in canonical.glob("*.json"):
            with self.subTest(locale=source.stem):
                data = source.read_bytes()
                self.assertEqual(data, (ROOT / "android/app/src/main/assets/i18n" / source.name).read_bytes())
                catalog = json.loads(data)
                self.assertEqual(set(catalog["nativeShare"]), expected)
                self.assertTrue(all(isinstance(value, str) and value for value in catalog["nativeShare"].values()))
                self.assertTrue(catalog["common"]["cancel"])

    def test_actual_bundle_verifier_accepts_canonical_set_and_rejects_missing_extra_or_changed_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            bundle = Path(temporary) / "PullyShare.appex"
            bundle.mkdir()
            shutil.copy2(ROOT / "ios/PullyShare/Info.plist", bundle / "Info.plist")
            source = ROOT / "packages/app/src/i18n/locales"
            shutil.copytree(source, bundle / "i18n")
            self.assertEqual(verifier.verify_locales(ROOT, bundle), 12)
            for change in ["missing", "extra", "bytes", "declaration"]:
                with self.subTest(change=change):
                    file = bundle / "i18n/en.json"
                    original = file.read_bytes()
                    info = (bundle / "Info.plist").read_bytes()
                    if change == "missing":
                        file.unlink()
                    elif change == "extra":
                        (bundle / "i18n/extra.json").write_text("{}")
                    elif change == "bytes":
                        file.write_bytes(original + b" ")
                    else:
                        altered = plistlib.loads(info)
                        altered["CFBundleLocalizations"] = ["en"]
                        (bundle / "Info.plist").write_bytes(plistlib.dumps(altered))
                    with self.assertRaises(SystemExit):
                        verifier.verify_locales(ROOT, bundle)
                    file.write_bytes(original)
                    (bundle / "i18n/extra.json").unlink(missing_ok=True)
                    (bundle / "Info.plist").write_bytes(info)


if __name__ == "__main__":
    unittest.main()
