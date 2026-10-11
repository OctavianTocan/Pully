#!/usr/bin/env bash
# Builds the debug APK and publishes it where the pulls.review server serves app updates
# (`GET /android/pulls.apk` and `/android/latest.json`, see packages/cli/src/native-api.ts).
# The installed app compares versionCode with the manifest and offers the update.
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
python3 - "$out/latest.json" "$code" "$name" "$sha" "${base%/}/android/pulls.apk" "$notes" <<'EOF'
import json, sys
path, code, name, sha, url, notes = sys.argv[1:]
json.dump({"versionCode": int(code), "versionName": name, "sha256": sha, "apkUrl": url, "notes": notes}, open(path, "w"), indent=2)
EOF
echo "published $name ($code) sha256=$sha -> $out"
