#!/usr/bin/env bash
# Builds the debug APK and publishes it where the pulls.review server serves app updates
# (`GET /android/pulls.apk` and `/android/latest.json`, see packages/cli/src/native-api.ts).
# The installed app compares versionCode with the manifest and offers the update.
#
# latest.json carries android/CHANGELOG.md whole (`changelog`), so the phone can list every
# version it has not installed, and this version's summary sentence as `notes`, which the
# notification and apps older than 0.17.0 show. A notes argument replaces the summary.
#
# Once past versions have been built (android/scripts/build-past-ui.py, which makes `past/`), each publish also
# builds the version this one replaces, so the Past versions slider in Settings stays complete. That runs after
# the update is out, and a failure there does not fail the publish.
#
#   android/scripts/publish-apk.sh [public-base-url] [notes]
set -euo pipefail
cd "$(dirname "$0")/.."
base="${1:-https://prs.octaviantocan.com}"
notes="${2:-}"
out="${PULLS_REVIEW_ANDROID_DIR:-$HOME/.local/share/pulls-review/android}"

export JAVA_HOME="${JAVA_HOME:-$(mise where java)}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
./gradlew -q :app:assembleDebug

apk=app/build/outputs/apk/debug/app-debug.apk
badging="$("$ANDROID_HOME"/build-tools/*/aapt2 dump badging "$apk" 2>/dev/null | head -1 || true)"
code="$(sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" <<<"$badging")"
name="$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$badging")"
if [ -z "$code" ]; then
  code="$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts)"
  name="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)"
fi
sha="$(sha256sum "$apk" | cut -d' ' -f1)"

mkdir -p "$out"
cp "$apk" "$out/pulls.apk.tmp" && mv "$out/pulls.apk.tmp" "$out/pulls.apk"
python3 - "$out/latest.json" "$code" "$name" "$sha" "${base%/}/android/pulls.apk" "$notes" CHANGELOG.md <<'EOF'
import json, os, re, sys
path, code, name, sha, url, notes, changelog_path = sys.argv[1:]
changelog = open(changelog_path).read() if os.path.exists(changelog_path) else ""

def summary(text, version):
    """The first paragraph under `## <version>`, as the app reads it (core Changelog.kt)."""
    lines, inside, found = [], False, []
    for line in re.sub(r"<!--.*?-->", "", text, flags=re.S).splitlines():
        heading = re.match(r"^##\s+(\S+)\s*$", line)
        if heading:
            inside = heading.group(1) == version
            continue
        if not inside:
            continue
        if re.match(r"^\s*[-*]\s+", line):
            break
        if line.strip():
            found.append(line.strip())
        elif found:
            break
    return " ".join(found)

if not notes:
    notes = summary(changelog, name)
    if not notes:
        print(f"warning: android/CHANGELOG.md has no section for {name}; the update goes out without notes", file=sys.stderr)
json.dump({"versionCode": int(code), "versionName": name, "sha256": sha, "apkUrl": url, "notes": notes, "changelog": changelog}, open(path, "w"), indent=2)
EOF
echo "published $name ($code) sha256=$sha -> $out"
# Past versions (Settings, Advanced): every publish builds the versions not built yet, newest first, in the background
# so the publish is not held up; the slider fills in as each one lands. Only one such run at a time (flock), and its
# log is past/build.log. Before this ran on every publish, past/ was never made and the page said "The server did not
# answer" (recording 16 Oct 09:50, 01:09 to 01:14; M3).
mkdir -p "$out/past"
PULLS_REVIEW_ANDROID_DIR="$out" python3 scripts/build-past-ui.py --index >/dev/null
nohup flock -n "$out/past/.lock" env PULLS_REVIEW_ANDROID_DIR="$out" nice python3 scripts/build-past-ui.py > "$out/past/build.log" 2>&1 &
echo "building past versions in the background; log: $out/past/build.log"
