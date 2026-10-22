#!/usr/bin/env python3
"""Verify signed simulator identity from the built Mach-O before exercising Security."""

from pathlib import Path
import plistlib
import struct
import subprocess
import sys

IDENTIFIER = "com.octaviantocan.pully.native-keychain-tests"
EXECUTABLE = "PullyNativeKeychainHost"
PRIVATE_ENTITLEMENTS = {"application-identifier": IDENTIFIER, "get-task-allow": True}
PROFILES = {
    "keychain-test": (IDENTIFIER, EXECUTABLE),
    "pully-app": ("com.octaviantocan.pully.preview", "Pully"),
}


def simulator_sections(image: bytes) -> tuple[bytes, bytes]:
    """Read only the pinned thin arm64 simulator executable format, failing closed.

    Field layouts: Apple's mach_header_64, segment_command_64, section_64 and
    build_version_command in xnu/EXTERNAL_HEADERS/mach-o/loader.h.
    """
    if len(image) < 32:
        raise ValueError("truncated Mach-O header")
    magic, cpu, _, kind, count, command_bytes, _, _ = struct.unpack_from("<8I", image)
    if (magic, cpu, kind) != (0xFEEDFACF, 0x0100000C, 2):
        raise ValueError("expected the thin arm64 Mach-O main executable")
    limit = 32 + command_bytes
    if limit > len(image) or count > command_bytes // 8:
        raise ValueError("truncated Mach-O load commands")
    sections = {}
    platforms = []
    position = 32
    for _ in range(count):
        if position + 8 > limit:
            raise ValueError("truncated Mach-O load command")
        command, size = struct.unpack_from("<2I", image, position)
        if size < 8 or size % 8 or position + size > limit:
            raise ValueError("invalid Mach-O load command bounds")
        if command == 0x32:  # LC_BUILD_VERSION
            if size < 24:
                raise ValueError("truncated Mach-O platform command")
            platforms.append(struct.unpack_from("<I", image, position + 8)[0])
        elif command == 0x19:  # LC_SEGMENT_64
            if size < 72:
                raise ValueError("truncated Mach-O segment")
            segment = image[position + 8:position + 24].rstrip(b"\0")
            file_offset, file_size = struct.unpack_from("<2Q", image, position + 40)
            section_count = struct.unpack_from("<I", image, position + 64)[0]
            if size != 72 + section_count * 80 or file_offset + file_size > len(image):
                raise ValueError("invalid Mach-O segment bounds")
            for index in range(section_count):
                start = position + 72 + index * 80
                name = image[start:start + 16].rstrip(b"\0")
                owner = image[start + 16:start + 32].rstrip(b"\0")
                if segment != b"__TEXT" or name not in (b"__entitlements", b"__ents_der"):
                    continue
                length, offset = struct.unpack_from("<QI", image, start + 40)
                if owner != segment or name in sections or not length or offset < limit \
                        or offset < file_offset or offset + length > file_offset + file_size:
                    raise ValueError("invalid or duplicate simulator entitlement section")
                sections[name] = image[offset:offset + length]
        position += size
    if position != limit or platforms != [7]:  # PLATFORM_IOSSIMULATOR, never a device/macOS binary
        raise ValueError("expected exactly one iOS simulator platform command")
    if set(sections) != {b"__entitlements", b"__ents_der"}:
        raise ValueError("both actual simulator entitlement sections must be present")
    return sections[b"__entitlements"], sections[b"__ents_der"]


def verified_sections(app: Path, signature: bytes, profile: str = "keychain-test") -> tuple[bytes, bytes]:
    if profile not in PROFILES:
        raise ValueError("unknown private simulator identity profile")
    identifier, executable = PROFILES[profile]
    private_entitlements = {"application-identifier": identifier, "get-task-allow": True}
    info = plistlib.loads((app / "Info.plist").read_bytes())
    if info.get("CFBundleIdentifier") != identifier or info.get("CFBundleExecutable") != executable:
        raise ValueError("simulator host must have its exact selected private application identity")
    if info.get("DTPlatformName") != "iphonesimulator":
        raise ValueError("this ad-hoc test identity is only for the simulator")
    signed = plistlib.loads(signature)
    # Xcode signs with an empty dictionary here; iOS simulator privileges live in __TEXT.
    # Reject unknown or widened code-signature claims even though they are not that identity.
    if not isinstance(signed, dict) or any(key not in private_entitlements or
            type(value) is not type(private_entitlements[key]) or value != private_entitlements[key]
            for key, value in signed.items()):
        raise ValueError("code signature must not widen the test host's private entitlements")
    xml, der = simulator_sections((app / executable).read_bytes())
    entitlements = plistlib.loads(xml)
    if entitlements != private_entitlements or entitlements.get("get-task-allow") is not True:
        raise ValueError("embedded simulator entitlements must be exactly the private host identity and debugger attachment")
    return xml, der


def main() -> None:
    if len(sys.argv) not in (4, 5):
        raise SystemExit("usage: verify-keychain-test-host.py Host.app signed-entitlements.plist evidence-directory [pully-app]")
    app = Path(sys.argv[1])
    xml, der = verified_sections(app, Path(sys.argv[2]).read_bytes(), sys.argv[4] if len(sys.argv) == 5 else "keychain-test")
    evidence = Path(sys.argv[3])
    evidence.mkdir(parents=True, exist_ok=True)
    xml_path = evidence / "embedded-entitlements.plist"
    der_path = evidence / "embedded-entitlements.der"
    encoded_path = evidence / "regenerated-entitlements.der"
    xml_path.write_bytes(xml)
    der_path.write_bytes(der)
    # The same Apple encoder/command used by Xcode's ProcessProductPackagingDER.
    # Actual modern simulator DER must match the exact inspected XML claims.
    subprocess.run(["/usr/bin/derq", "query", "-f", "xml", "-i", str(xml_path),
                    "-o", str(encoded_path), "--raw"], check=True)
    if encoded_path.read_bytes() != der:
        raise ValueError("actual embedded simulator DER entitlements differ from the inspected XML")
    print("Verified signed arm64 simulator executable and matching private XML/DER identity")


if __name__ == "__main__":
    main()
