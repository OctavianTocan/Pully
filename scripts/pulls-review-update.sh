#!/bin/sh
# Installed at ~/bin/pulls-review-update.sh; keep deployment behavior reviewed and tested in Git.
set -eu
export PATH=$HOME/.cargo/bin:$HOME/opt/node-24.12.0/bin:$PATH
state=${PULLS_REVIEW_UPDATE_STATE:-$HOME/.local/state/pulls-review-update}
repo=${PULLS_REVIEW_REPO:-$HOME/apps/pulls-review}
: "${PULLS_REVIEW_PUBLIC_URL:?Set the public URL of the server publishing this APK}"
mkdir -p "$state"
cd "$repo"

# Never discard an operator's changes or publish a release patched only on the host.
if ! git diff --quiet || ! git diff --cached --quiet; then
    echo 'FAIL: tracked deployment files are dirty; preserve the patch and restore a clean checkout before updating' >&2
    exit 1
fi
git fetch -q origin main
git merge -q --ff-only origin/main
head=$(git rev-parse HEAD)
if [ "$(cat "$state/deployed" 2>/dev/null || true)" != "$head" ]; then
    if [ "$(cat "$state/failed" 2>/dev/null || true)" = "$head" ]; then
        echo "FAIL: server build previously failed for $head; fix the source or investigate before retrying" >&2
        exit 1
    fi
    # An explicit chain is necessary: sh ignores set -e inside an if-condition subshell.
    # prepare only installs editor skills and rewrites a tracked lockfile; it is not a build input.
    if pnpm install --frozen-lockfile --ignore-scripts &&
        pnpm run contracts:check &&
        pnpm run android:design &&
        pnpm -F @pulls.review/core run build &&
        cargo build --release --locked --manifest-path backend/Cargo.toml &&
        pnpm -F pulls.review run build
    then
        systemctl --user restart pulls-review.service
        printf '%s\n' "$head" > "$state/deployed"
        echo "deployed $head"
    else
        printf '%s\n' "$head" > "$state/failed"
        echo "FAIL: server build of $head; server left on the previous build" >&2
        exit 1
    fi
fi

# Parse JSON rather than silently accepting missing/corrupt release metadata as build zero.
want=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' android/app/build.gradle.kts)
have=$(python3 - "${PULLS_REVIEW_ANDROID_DIR:-$HOME/.local/share/pulls-review/android}/latest.json" <<'PY'
import json, pathlib, sys
p = pathlib.Path(sys.argv[1])
code = json.loads(p.read_text())["versionCode"] if p.exists() else 0
if type(code) is not int or code < 0:
    raise ValueError("published versionCode must be a non-negative integer")
print(code)
PY
)
case "$want" in ''|*[!0-9]*) echo 'FAIL: missing numeric Android versionCode' >&2; exit 1;; esac
if [ "$want" -lt "$have" ]; then
    echo "FAIL: refusing Android downgrade from $have to $want" >&2
    exit 1
fi
if [ "$want" -gt "$have" ]; then
    export JAVA_HOME=${JAVA_HOME:-$HOME/opt/jdk-21}
    export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
    export PATH=$HOME/.local/bin:$PATH
    # A failed publish MUST fail the unit. The next timer run retries it even if the server is current.
    android/scripts/publish-apk.sh "$PULLS_REVIEW_PUBLIC_URL"
fi
git diff --exit-code
git diff --cached --exit-code
