The Darwin integration binary sends actual `NSURLSession` requests to a nonprivate
HTTP fixture bound only to `127.0.0.1`. Its second ephemeral port detects any
cross-origin redirect forwarding. The fixture sends headers without a body for
cancellation, native deadline and oversized Content-Length checks, so the bound
test never allocates a 64 MiB payload. Production ATS, TLS and authentication
configuration are unchanged.

On a Mac with the project's pinned toolchain and a booted iOS simulator:

```sh
pully_fixture_dir=$(mktemp -d /tmp/pully-rpc-fixture.XXXXXX)
python3 ios/scripts/rpc-fixture.py --state-dir "$pully_fixture_dir" &
pully_fixture_pid=$!
trap 'kill "$pully_fixture_pid"' EXIT
for pully_attempt in $(seq 1 100); do
  test -s "$pully_fixture_dir/url" && break
  kill -0 "$pully_fixture_pid" || exit 1
  sleep 0.1
done
test -s "$pully_fixture_dir/url" || exit 1
cd mobile
./gradlew :shared-client:iosSimulatorArm64IntegrationTest \
  -Ppully.rpcFixtureUrl="$(cat "$pully_fixture_dir/url")" \
  -Ppully.iosSimulatorId='<booted simulator UDID>' --no-daemon
```

The integration run requires the explicit loopback fixture property and fails
when it is missing or the fixture cannot be reached. Ordinary `jvmTest` and
`iosSimulatorArm64Test` do not include these integration tests or need a server.
The iOS verification workflow starts the fixture, runs the integration binary,
stops it with a shell trap and retains its log and Native XML/HTML test reports.
