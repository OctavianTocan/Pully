#!/usr/bin/env bash
# Copies published Android files from a server's Android directory (what
# android/scripts/publish-apk.sh writes) into the Worker's pully-android R2 bucket, under the
# same names. The APKs and images go first and latest.json last, so the update manifest never
# names an APK the bucket does not have yet. Pass --local to fill the `wrangler dev` bucket.
set -euo pipefail

dir="${1:?usage: upload-android.sh <android-dir> [--local]}"
where="${2:---remote}"
cd "$(dirname "$0")/.."

put() {
  npx wrangler r2 object put "pully-android/$1" --file "$dir/$1" "$where" >/dev/null
  echo "uploaded $1"
}

[ -f "$dir/pulls.apk" ] && put pulls.apk
if [ -d "$dir/past" ]; then
  (cd "$dir" && find past -maxdepth 1 -name '*.apk' -type f) | sort | while read -r name; do put "$name"; done
fi
if [ -d "$dir/changelog" ]; then
  (cd "$dir" && find changelog -mindepth 2 -maxdepth 2 -type f) | sort | while read -r name; do put "$name"; done
fi
[ -f "$dir/past/index.json" ] && put past/index.json
[ -f "$dir/latest.json" ] && put latest.json
