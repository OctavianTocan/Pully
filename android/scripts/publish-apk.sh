#!/usr/bin/env bash
# Builds the debug APK and publishes it where the pulls.review server serves app updates
# (`GET /android/pulls.apk` and `/android/latest.json`, see backend/src/transport.rs).
# The installed app compares versionCode with the manifest and offers the update.
#
# latest.json carries android/CHANGELOG.md whole (`changelog`), so the phone can list every
# version it has not installed, and this version's summary sentence as `notes`, which the
# notification and apps older than 0.17.0 show. A notes argument replaces the summary.
#
# Each publish also builds, in the background, every past version not built yet (android/scripts/build-past-ui.py,
# into `past/`), so the Past versions slider in Settings stays complete. That runs after the update is out, and a
# failure there does not fail the publish.
#
#   android/scripts/publish-apk.sh <public-base-url> [notes]
set -euo pipefail
cd "$(dirname "$0")/.."
# Keep an absolute URL for installed versions whose updater predates relative URLs.
# Require the publishing server explicitly: a default host could receive another server's Access token.
base="${1:?Pass the public URL of the server publishing this APK}"
notes="${2:-}"
out="${PULLS_REVIEW_ANDROID_DIR:-$HOME/.local/share/pulls-review/android}"

if ! git diff --quiet || ! git diff --cached --quiet; then
  echo 'Refusing to publish tracked local edits. Commit the release on a branch and publish a clean checkout.' >&2
  exit 1
fi
source_commit="$(git rev-parse HEAD)"
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

# Check before replacing the served APK. A same-code retry is safe only for identical bytes.
python3 - "$out/latest.json" "$code" "$name" "$sha" <<'EOF'
import json, pathlib, sys
path, code, name, sha = sys.argv[1:]
code = int(code)
if not 0 < code <= 2100000000:
    raise ValueError("invalid Android versionCode")
p = pathlib.Path(path)
if p.exists():
    old = json.loads(p.read_text())
    previous = old["versionCode"]
    if type(previous) is not int or previous <= 0:
        raise ValueError("invalid published versionCode")
    identical = code == previous and sha == old.get("sha256") and name == old.get("versionName")
    if code <= previous and not identical:
        raise ValueError(f"refusing to replace published build {previous} with {code}; commit a higher versionCode")
EOF

mkdir -p "$out"
cp "$apk" "$out/pulls.apk.tmp" && mv "$out/pulls.apk.tmp" "$out/pulls.apk"
# The changelog's pictures (android/changelog/<version>/), which the update screen shows for a version not installed yet.
if [ -d changelog ]; then
  mkdir -p "$out/changelog"
  cp -R changelog/. "$out/changelog/"
fi
python3 - "$out/latest.json" "$code" "$name" "$sha" "${base%/}/android/pulls.apk" "$notes" CHANGELOG.md "$source_commit" <<'EOF'
import json, os, re, sys
path, code, name, sha, url, notes, changelog_path, source_commit = sys.argv[1:]
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
with open(path + ".tmp", "w") as f:
    json.dump({"versionCode": int(code), "versionName": name, "sha256": sha, "apkUrl": url, "notes": notes, "changelog": changelog, "sourceCommit": source_commit}, f, indent=2)
os.replace(path + ".tmp", path)
EOF
echo "published $name ($code) sha256=$sha -> $out"
# Past versions (Settings, Advanced): every publish builds the versions not built yet, newest first, in the background
# so the publish is not held up; the slider fills in as each one lands. Only one such run at a time: a publish during a
# run waits for the lock and then makes its own pass, so the version it just replaced is built too. Each pass appends
# to past/build.log. Before this ran on every publish, past/ was never made and the page said "The server did not
# answer" (recording 16 Oct 09:50, 01:09 to 01:14; M3).
mkdir -p "$out/past"
PULLS_REVIEW_ANDROID_DIR="$out" python3 scripts/build-past-ui.py --index >/dev/null
echo "--- $(date -u +%FT%TZ) publish of $name" >> "$out/past/build.log"
nohup flock "$out/past/.lock" env PULLS_REVIEW_ANDROID_DIR="$out" nice python3 scripts/build-past-ui.py >> "$out/past/build.log" 2>&1 &
echo "building past versions in the background; log: $out/past/build.log"
