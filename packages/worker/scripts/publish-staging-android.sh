#!/usr/bin/env bash
# Builds Pully Staging from the checked-out main commit and publishes it to the pully-android R2
# bucket under staging/, where the staging server serves it at /download/staging/ and the staging
# app finds its updates (android/CONTRACTS, android-staging-channel). The stable app's files at
# the bucket's top level are never touched. .github/workflows/staging.yml runs it after every
# merge to main; it can also be run by hand from a clean main checkout.
#
#   packages/worker/scripts/publish-staging-android.sh <staging-origin> [--local]
#
# The version code is the number of commits on main, so each merge is newer than the last.
set -euo pipefail

origin="${1:?Pass the staging server origin, for example https://pully-staging.octaviantocan.com}"
where="${2:---remote}"
case "$origin" in
  https://*) ;;
  *) echo "The staging origin must be https://" >&2; exit 1 ;;
esac
origin="${origin%/}"
# The phone passes Access without another browser login (Tavi, 25 Oct 2026). Values stay
# in the build environment; never pass them as Gradle arguments or print them.
for key in PULLY_STAGING_ACCESS_CLIENT_ID PULLY_STAGING_ACCESS_CLIENT_SECRET; do
  [ -n "${!key:-}" ] || { echo "Missing staging Access build credential: $key" >&2; exit 1; }
done
repo="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
cd "$repo"
if ! git diff --quiet || ! git diff --cached --quiet; then
  echo 'Refusing to publish tracked local edits.' >&2
  exit 1
fi
commit="$(git rev-parse HEAD)"
build="$(git rev-list --count HEAD)"
[ "$(git rev-parse --is-shallow-repository)" = false ] || { echo 'Needs the full history for the version code.' >&2; exit 1; }

# Match the version installed by staging.yml; a wildcard passes other installed tools as arguments.
build_tools="${ANDROID_HOME:?Set ANDROID_HOME}/build-tools/37.0.0"
for tool in aapt2 apksigner; do
  [ -x "$build_tools/$tool" ] || { echo "Missing Android build tool: $build_tools/$tool" >&2; exit 1; }
done

(cd android && ./gradlew -q :app:assembleDebug --no-daemon \
  -Ppully.channel=staging -Ppully.stagingBuild="$build" -Ppully.defaultServer="$origin")
apk=android/app/build/outputs/apk/debug/app-debug.apk

# The APK must be the staging package at this build, signed with the pinned key, so it can never
# replace Pully and always installs over the last staging build.
badging="$("$build_tools/aapt2" dump badging "$apk" | sed -n 1p)"
[[ "$badging" == "package: name='com.octaviantocan.pulls.staging' versionCode='$build' "* ]] \
  || { echo "Built APK is not Pully Staging $build: $badging" >&2; exit 1; }
name="$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$badging")"
expected="$(keytool -exportcert -keystore android/app/debug.keystore -alias androiddebugkey -storepass android | sha256sum | cut -d' ' -f1)"
signer="$("$build_tools/apksigner" verify --print-certs "$apk" | sed -n 's/^.*certificate SHA-256 digest: //p' | sort -u)"
[ "$signer" = "$expected" ] || { echo 'Built APK is not signed with the pinned key.' >&2; exit 1; }

out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
cp "$apk" "$out/pulls.apk"
python3 - "$out/latest.json" "$build" "$name" "$(sha256sum "$apk" | cut -d' ' -f1)" \
  "$origin/download/staging/pully.apk" "$commit" "$(git log -1 --format=%s)" android/CHANGELOG.md <<'EOF'
import json, sys
path, code, name, sha, url, commit, subject, changelog = sys.argv[1:]
notes = f"Built from main at {commit[:7]}: {subject}"[:300]
with open(path, "w") as f:
    json.dump({"versionCode": int(code), "versionName": name, "sha256": sha, "apkUrl": url, "notes": notes,
               "changelog": open(changelog).read(), "sourceCommit": commit}, f, indent=2)
EOF

cd packages/worker
# The APK first and latest.json last, so the manifest never names an APK the bucket lacks.
npx wrangler r2 object put pully-android/staging/pulls.apk --file "$out/pulls.apk" "$where" >/dev/null
npx wrangler r2 object put pully-android/staging/latest.json --file "$out/latest.json" \
  --content-type application/json "$where" >/dev/null
echo "published Pully Staging $name ($build) from $commit"
