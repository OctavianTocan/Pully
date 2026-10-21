#!/usr/bin/env python3
"""Verify the built simulator host's real signing identity before exercising Security."""

from pathlib import Path
import plistlib
import sys


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("usage: verify-keychain-test-host.py Host.app signed-entitlements.plist")
    app = Path(sys.argv[1])
    info = plistlib.loads((app / "Info.plist").read_bytes())
    entitlements = plistlib.loads(Path(sys.argv[2]).read_bytes())
    identifier = "com.octaviantocan.pully.native-keychain-tests"
    if info.get("CFBundleIdentifier") != identifier or entitlements.get("application-identifier") != identifier:
        raise SystemExit("Keychain test host must have its own exact signed application identifier")
    if info.get("DTPlatformName") != "iphonesimulator":
        raise SystemExit("this ad-hoc test identity is only for the simulator")
    if entitlements.get("get-task-allow") is not True:
        raise SystemExit("the simulator test host must allow XCTest attachment")
    groups = entitlements.get("keychain-access-groups", [])
    if groups not in ([], [identifier]) or entitlements.get("com.apple.security.application-groups"):
        raise SystemExit("Keychain test host must not widen its private application access")
    print("Verified the ad-hoc simulator test host's private application identity")


if __name__ == "__main__":
    main()
