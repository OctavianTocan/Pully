#!/usr/bin/env python3
"""Rebuilds past versions of the Android app from git, for Settings, Advanced, Past versions.

Each version is built from the last commit on the first-parent history that still carried its versionName, so it is
the app as it was when the next version replaced it. Every build is changed in the same few ways and nothing else:

- its own package, `com.octaviantocan.pulls.v0_20_0`, so it installs beside the current app and the others;
- a versionCode of 1000000 plus its own, so no update the server offers is ever newer and its update screen stays away;
- the label "Pully 0.20.0", and no launcher icon, link handling or share target: its activity answers only the
  PAST_UI category the current app opens it with, behind a signature permission only the current app holds;
- `PastUiBoot`, which takes the server address and Access token from the current app when it opens the old one;
- the pinned debug key, so a rebuild on any machine installs over the last one.

The APKs go to `<android dir>/past/<version>.apk` with `index.json` beside them, where the server serves them under
`/android/past/` (backend/src/transport.rs). The version the checkout is on is the current app, so it is never
built here.

    android/scripts/build-past-ui.py               build every version not built yet
    android/scripts/build-past-ui.py 0.20.0 0.9.0  build (or rebuild) these
    android/scripts/build-past-ui.py --list        print each version and the commit it is built from
    android/scripts/build-past-ui.py --index       only rewrite index.json from what is built
"""
import hashlib
import fcntl
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ANDROID = Path(__file__).resolve().parent.parent
REPO = ANDROID.parent
GRADLE_FILE = "android/app/build.gradle.kts"
MANIFEST = "src/main/AndroidManifest.xml"
BOOT = ANDROID / "scripts" / "past-ui" / "PastUiBoot.kt"
CATEGORY = "com.octaviantocan.pulls.category.PAST_UI"
PERMISSION = "com.octaviantocan.pulls.permission.PAST_UI"
PACKAGE = "com.octaviantocan.pulls"
CODE_OFFSET = 1_000_000


def out_dir() -> Path:
    base = os.environ.get("PULLS_REVIEW_ANDROID_DIR") or str(Path.home() / ".local/share/pulls-review/android")
    return Path(base) / "past"


def git(*args: str) -> str:
    return subprocess.run(["git", "-C", str(REPO), *args], check=True, capture_output=True, text=True).stdout


def version_of(commit: str) -> tuple[str, int] | None:
    try:
        text = git("show", f"{commit}:{GRADLE_FILE}")
    except subprocess.CalledProcessError:
        return None
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    code = re.search(r"versionCode\s*=\s*(\d+)", text)
    return (name.group(1), int(code.group(1))) if name and code else None


def versions() -> list[dict]:
    """Every versionName on the first-parent history, oldest first, with the last commit that carried it."""
    commits = git("rev-list", "--first-parent", "HEAD", "--", GRADLE_FILE).split()
    found: list[dict] = []
    newer_start: str | None = None  # the oldest commit seen so far of the version above
    current: dict | None = None
    for commit in commits:  # newest first
        version = version_of(commit)
        if version is None:
            continue
        name, code = version
        if current is None or current["versionName"] != name:
            end = "HEAD" if newer_start is None else f"{newer_start}^"
            current = {"versionName": name, "versionCode": code, "commit": git("rev-parse", end).strip()}
            found.append(current)
        newer_start = commit
    found.reverse()
    # A version that came back later (a revert) keeps its newest build only.
    seen: dict[str, dict] = {}
    for item in found:
        seen[item["versionName"]] = item
    return sorted(seen.values(), key=lambda item: item["versionCode"])


def changelog_summaries() -> dict[str, str]:
    """The summary sentence of each section of the current CHANGELOG.md, as publish-apk.sh reads it."""
    path = ANDROID / "CHANGELOG.md"
    if not path.exists():
        return {}
    text = re.sub(r"<!--.*?-->", "", path.read_text(), flags=re.S)
    summaries: dict[str, str] = {}
    version, lines = None, []
    for line in text.splitlines() + ["## end"]:
        heading = re.match(r"^##\s+(\S+)\s*$", line)
        if heading:
            if version and lines:
                summaries[version] = " ".join(lines)
            version, lines = heading.group(1), []
            continue
        if version is None or version in summaries:
            continue
        if re.match(r"^\s*([-*]|#)", line):
            summaries[version] = " ".join(lines)
        elif line.strip():
            lines.append(line.strip())
        elif lines:
            summaries[version] = " ".join(lines)
    return {name: summary for name, summary in summaries.items() if summary}


def subject(commit: str) -> str:
    """A commit's subject without the version prefix, the conventional-commit type or the PR number."""
    text = git("log", "-1", "--format=%s", commit).strip()
    text = re.sub(r"\s*\(#\d+\)$", "", text)
    text = re.sub(r"^(Android( \d+\.\d+\.\d+)?|android|feat|fix|chore)(\([^)]*\))?:\s*", "", text)
    text = re.sub(r"[;:]?\s*\(?\d+\.\d+\.\d+\)?$", "", text).strip()
    return text[:1].upper() + text[1:]


def package_of(name: str) -> str:
    return f"{PACKAGE}.v{name.replace('.', '_').replace('-', '_')}"


def patch(checkout: Path, name: str, code: int) -> None:
    """Turns the checkout into the past-version app described at the top of this file."""
    app = checkout / "app"
    gradle = app / "build.gradle.kts"
    gradle.write_text(
        gradle.read_text()
        + f"""
// Added by build-past-ui.py: Pulls {name} as a past version beside the current app.
android {{
    signingConfigs {{
        named("debug") {{
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }}
    }}
    defaultConfig {{
        applicationId = "{package_of(name)}"
        versionCode = {CODE_OFFSET + code}
    }}
}}
"""
    )
    # Versions before 0.6.1 signed with whatever key the machine had; every past build signs with today's pinned one.
    shutil.copy(ANDROID / "app" / "debug.keystore", app / "debug.keystore")
    manifest = app / MANIFEST
    text = manifest.read_text()
    text = re.sub(r"\s*<intent-filter\b.*?</intent-filter>", "", text, flags=re.S)
    activity = re.search(r'<activity\b[^>]*android:name="(?:\.|com\.octaviantocan\.pulls\.)MainActivity"[^>]*?(/?)>', text, flags=re.S)
    if activity is None:
        raise SystemExit(f"{name}: no MainActivity in the manifest")
    head = activity.group(0)
    if activity.group(1) == "/":
        head = head[:-2].rstrip() + ">"
    opener = f"""{head}
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="{CATEGORY}" />
            </intent-filter>"""
    if activity.group(1) == "/":
        opener += "\n        </activity>"
    text = text[: activity.start()] + opener + text[activity.end() :]
    text = re.sub(r'(<application\b[^>]*?android:label=)"[^"]*"', rf'\1"Pully {name}"', text, count=1, flags=re.S)
    # Only an app signed with the same key, the current Pulls, may open it (and so hand it a server).
    text = text.replace(head, head.replace("<activity", f'<activity android:permission="{PERMISSION}"', 1), 1)
    permission = f"""<permission
        android:name="{PERMISSION}"
        android:protectionLevel="signature" />

    <application"""
    text = re.sub(r"<application\b", lambda _: permission, text, count=1)
    provider = """        <provider
            android:name="com.octaviantocan.pulls.pastui.PastUiBoot"
            android:authorities="${applicationId}.pastui"
            android:exported="false" />
    </application>"""
    text = text.replace("</application>", provider, 1)
    manifest.write_text(text)
    target = app / "src/main/kotlin/com/octaviantocan/pulls/pastui"
    target.mkdir(parents=True, exist_ok=True)
    shutil.copy(BOOT, target / "PastUiBoot.kt")


def build(item: dict, dest: Path) -> None:
    name, code, commit = item["versionName"], item["versionCode"], item["commit"]
    print(f"building {name} from {commit[:7]}", flush=True)
    with tempfile.TemporaryDirectory(prefix=f"pulls-{name}-") as tmp:
        checkout = Path(tmp) / "repo"
        git("worktree", "add", "--detach", str(checkout), commit)
        try:
            android = checkout / "android"
            # local.properties is not in git, so a checkout has none; without it the build cannot find the SDK unless
            # ANDROID_HOME is set.
            local = ANDROID / "local.properties"
            if local.exists():
                shutil.copy(local, android / "local.properties")
            patch(android, name, code)
            subprocess.run(["./gradlew", "-q", "--no-daemon", ":app:assembleDebug"], cwd=android, check=True)
            apk = android / "app/build/outputs/apk/debug/app-debug.apk"
            data = apk.read_bytes()
            dest.mkdir(parents=True, exist_ok=True)
            partial = dest / f"{name}.apk.tmp"
            partial.write_bytes(data)
            partial.replace(dest / f"{name}.apk")
            meta = {
                **item,
                "packageName": package_of(name),
                "date": git("log", "-1", "--format=%cI", commit).strip(),
                "subject": subject(commit),
                "sha256": hashlib.sha256(data).hexdigest(),
                "size": len(data),
            }
            (dest / f"{name}.json").write_text(json.dumps(meta, indent=2) + "\n")
        finally:
            git("worktree", "remove", "--force", str(checkout))


def write_index(dest: Path) -> None:
    """index.json: every built version, oldest first, with the summary today's changelog gives it."""
    dest.mkdir(parents=True, exist_ok=True)
    # --index runs beside the historical builder; both rescan while holding the
    # same short index lock, independently of the long-running build lock.
    with (dest / ".index.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        _write_index(dest)


def _write_index(dest: Path) -> None:
    summaries = changelog_summaries()
    entries = []
    for meta_path in dest.glob("*.json"):
        if meta_path.name == "index.json":
            continue
        meta = json.loads(meta_path.read_text())
        if not (dest / f"{meta['versionName']}.apk").exists():
            continue
        entries.append({
            "versionName": meta["versionName"],
            "versionCode": meta["versionCode"],
            "packageName": meta["packageName"],
            "commit": meta["commit"],
            "date": meta["date"],
            "summary": summaries.get(meta["versionName"]) or meta["subject"] or f"Pully {meta['versionName']}",
            "sha256": meta["sha256"],
            "size": meta["size"],
        })
    entries.sort(key=lambda entry: entry["versionCode"])
    dest.mkdir(parents=True, exist_ok=True)
    partial = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", dir=dest, prefix=".index-", suffix=".tmp", delete=False) as output:
            partial = Path(output.name)
            output.write(json.dumps({"versions": entries}, indent=2) + "\n")
        partial.replace(dest / "index.json")
    finally:
        if partial is not None:
            partial.unlink(missing_ok=True)
    print(f"index.json lists {len(entries)} past versions -> {dest}")


def main(args: list[str]) -> int:
    dest = out_dir()
    if "--index" in args:
        write_index(dest)
        return 0
    known = versions()
    current = known[-1]["versionName"] if known else None
    if "--list" in args:
        for item in known:
            note = "  (the current app, not built)" if item["versionName"] == current else ""
            print(f"{item['versionName']:>8}  {item['versionCode']:>3}  {item['commit'][:7]}{note}")
        return 0
    if args:
        by_name = {item["versionName"]: item for item in known}
        unknown = [name for name in args if name not in by_name]
        if unknown:
            print(f"no such version on this history: {', '.join(unknown)}", file=sys.stderr)
            return 2
        wanted = [by_name[name] for name in args]
    else:
        wanted = [item for item in known if item["versionName"] != current and not (dest / f"{item['versionName']}.apk").exists()]
    failed = []
    # The newest first, and the index written after each one, so the slider fills in while the rest still build.
    write_index(dest)
    for item in reversed(wanted):
        try:
            build(item, dest)
            write_index(dest)
        except (subprocess.CalledProcessError, SystemExit, OSError) as error:
            print(f"{item['versionName']} did not build: {error}", file=sys.stderr)
            failed.append(item["versionName"])
    write_index(dest)
    if failed:
        print(f"not built: {', '.join(failed)}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
