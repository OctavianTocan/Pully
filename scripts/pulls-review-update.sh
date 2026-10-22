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
if [ "$(git symbolic-ref --quiet --short HEAD || true)" != release ]; then
    echo 'FAIL: automatic deployment requires the release branch; migrate the installed updater and checkout before resuming its timer' >&2
    exit 1
fi
git fetch -q origin release
git merge -q --ff-only FETCH_HEAD
# This guard also refuses an unverified release branch if protection is unavailable.
head=$(git rev-parse HEAD)
if [ "$(cat "$state/deployed" 2>/dev/null || true)" != "$head" ]; then
    node scripts/release-track.mjs
fi

start_updated_server() {
    active=$(systemctl --user show pulls-review.service --property=ActiveState --value) || return 1
    case "$active" in
        inactive|failed) systemctl --user start pulls-review.service; return $?;;
        active) ;;
        *) echo "DEFER: service is $active; retry after its transition" >&2; return 75;;
    esac
    invocation=$(systemctl --user show pulls-review.service --property=InvocationID --value) || return 1
    if [ -z "$invocation" ] || [ -z "${PULLS_REVIEW_UPDATE_TOKEN_FILE:-}" ]; then
        echo 'DEFER: configure idle-update support on the running server before automatic deployment' >&2
        return 75
    fi
    # Python reads the private token directly: it never appears in argv or logs.
    if python3 - "$PULLS_REVIEW_UPDATE_TOKEN_FILE" "${PULLS_REVIEW_UPDATE_URL:-http://127.0.0.1:${PULLS_REVIEW_PORT:-7390}}" <<'PY'
import json, pathlib, sys, urllib.error, urllib.parse, urllib.request
attempted = False
try:
    token = pathlib.Path(sys.argv[1]).read_text().strip()
    if len(token) != 64 or any(c not in '0123456789abcdef' for c in token):
        raise ValueError('invalid token')
    base = urllib.parse.urlsplit(sys.argv[2])
    if base.scheme != 'http' or base.hostname not in ('127.0.0.1', 'localhost', '::1') or base.username or base.password or base.query or base.fragment or base.path not in ('', '/'):
        raise ValueError('update control must use the local server directly')
    request = urllib.request.Request(sys.argv[2].rstrip('/') + '/api/update/restart', data=b'', method='POST', headers={'Authorization': 'Bearer ' + token})
    # Never forward this host-only token to a redirect target or an environment proxy.
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    attempted = True
    with opener.open(request, timeout=10) as response:
        if response.status != 202 or json.load(response).get('ready') is not True:
            raise ValueError('idle shutdown was not accepted')
except urllib.error.HTTPError:
    print('DEFER: idle shutdown unavailable or AI work is active; no restart requested', file=sys.stderr)
    sys.exit(75)
except Exception:
    print('DEFER: idle shutdown response unavailable; checking service state', file=sys.stderr)
    sys.exit(76 if attempted else 75)
PY
    then
        :
    else
        # Definite busy/auth/unsupported replies defer immediately. A transport
        # failure is ambiguous: shutdown may be draining its final HTTP request,
        # so use the same bounded reconciliation as an acknowledged shutdown.
        if [ "$?" -ne 76 ]; then
            return 75
        fi
    fi
    # The backend owns shutdown after fencing admissions. Restart=always may have
    # already launched its replacement, so START is essential: RESTART could kill
    # AI work that the new process has just accepted.
    attempts=0
    while [ "$attempts" -lt 60 ]; do
        current=$(systemctl --user show pulls-review.service --property=InvocationID --value) || return 1
        active=$(systemctl --user show pulls-review.service --property=ActiveState --value) || return 1
        if [ "$current" != "$invocation" ] || [ "$active" = inactive ] || [ "$active" = failed ]; then
            systemctl --user start pulls-review.service
            return $?
        fi
        attempts=$((attempts + 1))
        sleep 1
    done
    echo 'DEFER: waiting for the previous server to finish graceful shutdown' >&2
    return 75
}

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
        if start_updated_server; then
            :
        else
            result=$?
            # Busy/unavailable is retried by the normal timer, without marking
            # this revision deployed or permanently failed.
            if [ "$result" -eq 75 ]; then exit 0; fi
            exit "$result"
        fi
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
    node scripts/release-track.mjs
    export JAVA_HOME=${JAVA_HOME:-$HOME/opt/jdk-21}
    export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
    export PATH=$HOME/.local/bin:$PATH
    # A failed publish MUST fail the unit. The next timer run retries it even if the server is current.
    android/scripts/publish-apk.sh "$PULLS_REVIEW_PUBLIC_URL"
fi
git diff --exit-code
git diff --cached --exit-code
