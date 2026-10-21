"""Synthetic Mach-O parser/policy cases; never substitutes for the real Security test."""

import importlib.util
from pathlib import Path
import plistlib
import struct
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("keychain_host_verifier", Path(__file__).with_name("verify-keychain-test-host.py"))
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


def image(entitlements=None, *, sections=None, platform=7, cpu=0x0100000C, kind=2):
    """Minimal format fixture, not a runnable/signed executable or an Apple DER encoder."""
    if sections is None:
        sections = [(b"__entitlements", plistlib.dumps(entitlements if entitlements is not None else VERIFIER.PRIVATE_ENTITLEMENTS)),
                    (b"__ents_der", b"opaque DER section fixture")]
    command_size = 72 + 80 * len(sections)
    payload_offset = 32 + command_size + 24
    payload = b""
    descriptors = b""
    for name, content in sections:
        descriptors += struct.pack("<16s16sQQ8I", name, b"__TEXT", 0, len(content), payload_offset + len(payload), 0, 0, 0, 0, 0, 0, 0)
        payload += content
    total = payload_offset + len(payload)
    header = struct.pack("<8I", 0xFEEDFACF, cpu, 0, kind, 2, command_size + 24, 0, 0)
    segment = struct.pack("<II16sQQQQiiII", 0x19, command_size, b"__TEXT", 0, total, 0, total, 5, 5, len(sections), 0)
    build = struct.pack("<6I", 0x32, 24, platform, 0, 0, 0)
    return header + segment + descriptors + build + payload


class KeychainTestHostVerifierTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.app = Path(self.temp.name) / "Host.app"
        self.app.mkdir()
        self.info = {"CFBundleIdentifier": VERIFIER.IDENTIFIER, "CFBundleExecutable": VERIFIER.EXECUTABLE,
                     "DTPlatformName": "iphonesimulator"}
        self.install(image())

    def install(self, binary):
        (self.app / "Info.plist").write_bytes(plistlib.dumps(self.info))
        (self.app / VERIFIER.EXECUTABLE).write_bytes(binary)

    def verify(self, signature=None):
        return VERIFIER.verified_sections(self.app, plistlib.dumps(signature if signature is not None else {}))

    def test_empty_signature_dictionary_uses_actual_embedded_identity(self):
        xml, der = self.verify()
        self.assertEqual(plistlib.loads(xml), VERIFIER.PRIVATE_ENTITLEMENTS)
        self.assertEqual(der, b"opaque DER section fixture")

    def test_signature_claims_cannot_widen_or_replace_simulator_identity(self):
        for signature in [{"application-identifier": "another.host"}, {"keychain-access-groups": ["shared"]},
                          {"get-task-allow": 1}, {"com.apple.security.application-groups": ["group.shared"]}]:
            with self.subTest(signature=signature), self.assertRaises(ValueError):
                self.verify(signature)

    def test_embedded_identity_must_have_exactly_the_two_private_claims(self):
        for entitlements in [{}, {**VERIFIER.PRIVATE_ENTITLEMENTS, "application-identifier": "another.host"},
                             {**VERIFIER.PRIVATE_ENTITLEMENTS, "get-task-allow": False},
                             {**VERIFIER.PRIVATE_ENTITLEMENTS, "get-task-allow": 1},
                             {**VERIFIER.PRIVATE_ENTITLEMENTS, "keychain-access-groups": [VERIFIER.IDENTIFIER]},
                             {**VERIFIER.PRIVATE_ENTITLEMENTS, "com.apple.security.application-groups": ["group.shared"]}]:
            with self.subTest(entitlements=entitlements):
                self.install(image(entitlements))
                with self.assertRaises(ValueError):
                    self.verify()

    def test_valid_signature_cannot_substitute_for_missing_simulator_sections(self):
        self.install(image(sections=[]))
        with self.assertRaisesRegex(ValueError, "both actual"):
            self.verify(VERIFIER.PRIVATE_ENTITLEMENTS)

    def test_both_unique_simulator_sections_are_required(self):
        xml = plistlib.dumps(VERIFIER.PRIVATE_ENTITLEMENTS)
        for sections in [[(b"__entitlements", xml)], [(b"__ents_der", b"DER")],
                         [(b"__entitlements", xml), (b"__entitlements", xml), (b"__ents_der", b"DER")]]:
            with self.subTest(sections=[name for name, _ in sections]), self.assertRaises(ValueError):
                VERIFIER.simulator_sections(image(sections=sections))

    def test_host_bundle_and_executable_must_match_the_simulator_fixture(self):
        for key, value in [("CFBundleIdentifier", "another.host"), ("CFBundleExecutable", "../other"),
                           ("DTPlatformName", "iphoneos")]:
            with self.subTest(key=key):
                original = self.info[key]
                self.info[key] = value
                self.install(image())
                with self.assertRaises(ValueError):
                    self.verify()
                self.info[key] = original

    def test_non_arm64_non_executable_and_non_simulator_images_are_rejected(self):
        for binary in [image(cpu=0x01000007), image(kind=6), image(platform=2), image(platform=1), b"\xca\xfe\xba\xbe" + image()]:
            with self.subTest(header=binary[:32]), self.assertRaises(ValueError):
                VERIFIER.simulator_sections(binary)

    def test_truncated_commands_and_out_of_bounds_sections_are_rejected(self):
        valid = image()
        bad_command = bytearray(valid)
        struct.pack_into("<I", bad_command, 36, 0xFFFFFFF8)
        bad_segment = bytearray(valid)
        struct.pack_into("<Q", bad_segment, 32 + 48, len(valid) + 1)
        bad_section = bytearray(valid)
        struct.pack_into("<I", bad_section, 32 + 72 + 48, len(valid) - 1)
        header_section = bytearray(valid)
        struct.pack_into("<I", header_section, 32 + 72 + 48, 0)
        for binary in [valid[:20], valid[:100], valid[:-1], bad_command, bad_segment, bad_section, header_section]:
            with self.subTest(length=len(binary)), self.assertRaises(ValueError):
                VERIFIER.simulator_sections(bytes(binary))


if __name__ == "__main__":
    unittest.main()
