import assert from 'node:assert/strict'
import { execFileSync, spawnSync } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
// The release CI preflight runs before installing workspace dependencies.
// eslint-disable-next-line test/no-import-node-test
import { afterEach, describe, it } from 'node:test'
import { fileURLToPath } from 'node:url'
import { releaseFixtureGate } from './release-track.test-support.mjs'

const updater = fileURLToPath(new URL('./pulls-review-update.sh', import.meta.url))
const temporary = []
afterEach(() => {
  for (const path of temporary.splice(0))
    rmSync(path, { recursive: true, force: true })
})

function fixture({ have = 65, installed = updater } = {}) {
  const home = mkdtempSync(join(tmpdir(), 'pully-update-test-'))
  temporary.push(home)
  const repo = join(home, 'repo')
  const state = join(home, 'state')
  const out = join(home, 'android')
  const bin = join(home, 'bin')
  const calls = join(home, 'calls')
  for (const path of [repo, state, out, bin, join(repo, 'scripts'), join(repo, 'android/app'), join(repo, 'android/scripts')])
    mkdirSync(path, { recursive: true })
  const git = (...args) => execFileSync('git', args, { cwd: repo, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim()
  git('init', '-b', 'release')
  git('config', 'user.email', 'test@example.com')
  git('config', 'user.name', 'Updater test')
  writeFileSync(join(repo, 'android/app/build.gradle.kts'), 'versionCode = 66\nversionName = "0.52.0"\n')
  writeFileSync(join(repo, 'scripts/release-track.mjs'), '// Fixture runs actual production admission with synthetic CI responses.\n')
  writeFileSync(join(repo, 'android/scripts/publish-apk.sh'), `#!/bin/sh
set -eu
echo publish >> "$CALLS"
if [ "\${APK_PUBLISH_FAIL:-0}" = 1 ]; then exit 17; fi
printf '{"versionCode":66}' > "$PULLS_REVIEW_ANDROID_DIR/latest.json"
`, { mode: 0o755 })
  git('add', '.')
  git('commit', '-m', 'fixture')
  git('branch', 'main')
  git('clone', '--bare', repo, join(home, 'origin.git'))
  git('remote', 'add', 'origin', join(home, 'origin.git'))
  releaseFixtureGate(home)
  writeFileSync(join(out, 'latest.json'), JSON.stringify({ versionCode: have }))
  writeFileSync(calls, '')
  writeFileSync(join(state, 'active'), 'active')
  writeFileSync(join(state, 'invocation'), 'old-server')
  const token = join(state, 'update-token')
  writeFileSync(token, 'a'.repeat(64))
  for (const command of ['pnpm', 'cargo']) {
    writeFileSync(join(bin, command), `#!/bin/sh
echo '${command}' "$@" >> "$CALLS"
if [ '${command}' = pnpm ] && [ "$*" = 'run contracts:check' ]; then
    node scripts/release-track.mjs --deployment-only || exit 1
fi
if [ "\${BUILD_FAIL:-0}" = 1 ] && [ '${command}' = cargo ]; then exit 19; fi
if [ '${command}' = cargo ]; then
    case "\${BUILD_SOURCE_CHANGE:-}" in
        remote)
            next=$(git commit-tree HEAD^{tree} -p HEAD -m 'new remote release')
            git push -q origin "$next:refs/heads/release";;
        checkout) git checkout -q main;;
        dirty) echo 'changed during build' >> android/app/build.gradle.kts;;
    esac
fi
`, { mode: 0o755 })
  }
  writeFileSync(join(bin, 'systemctl'), `#!/bin/sh
echo systemctl "$@" >> "$CALLS"
case "$*" in
    *--property=ActiveState*) cat "$PULLS_REVIEW_UPDATE_STATE/active";;
    *--property=InvocationID*)
        if [ -e "$PULLS_REVIEW_UPDATE_STATE/pending-shutdown" ]; then
            count=$(cat "$PULLS_REVIEW_UPDATE_STATE/pending-shutdown")
            if [ "$count" -gt 0 ]; then
                echo inactive > "$PULLS_REVIEW_UPDATE_STATE/active"
            fi
            echo 1 > "$PULLS_REVIEW_UPDATE_STATE/pending-shutdown"
        fi
        cat "$PULLS_REVIEW_UPDATE_STATE/invocation";;
    *start*)
        if [ "\${START_FAIL:-0}" = 1 ]; then exit 23; fi
        echo active > "$PULLS_REVIEW_UPDATE_STATE/active"
        echo new-server > "$PULLS_REVIEW_UPDATE_STATE/invocation";;
esac
`, { mode: 0o755 })
  writeFileSync(join(bin, 'python3'), `#!/bin/sh
if [ "$2" = "$PULLS_REVIEW_UPDATE_TOKEN_FILE" ]; then
    cat >/dev/null
    echo idle-shutdown >> "$CALLS"
    case "\${SHUTDOWN_MODE:-idle}" in
        busy|unavailable) exit 75;;
        delayed-lost-response)
            echo 0 > "$PULLS_REVIEW_UPDATE_STATE/pending-shutdown"
            exit 76;;
        restarted)
            echo active > "$PULLS_REVIEW_UPDATE_STATE/active"
            echo supervised-replacement > "$PULLS_REVIEW_UPDATE_STATE/invocation";;
        *) echo inactive > "$PULLS_REVIEW_UPDATE_STATE/active";;
    esac
    if [ "\${SHUTDOWN_MODE:-idle}" = lost-response ]; then exit 76; fi
    exit 0
fi
exec /usr/bin/python3 "$@"
`, { mode: 0o755 })
  const run = extra => spawnSync('/bin/sh', [installed], {
    encoding: 'utf8',
    env: { ...process.env, HOME: home, PATH: `${bin}:/usr/bin:/bin`, CALLS: calls, PULLS_REVIEW_REPO: repo, PULLS_REVIEW_UPDATE_STATE: state, PULLS_REVIEW_UPDATE_TOKEN_FILE: token, PULLS_REVIEW_ANDROID_DIR: out, PULLS_REVIEW_PUBLIC_URL: 'https://example.com', ...extra },
  })
  return { repo, state, out, run, git, calls: () => readFileSync(calls, 'utf8') }
}

describe('Pully deployment updater', () => {
  it('refuses a release whose actual CI evidence is incomplete before any build or service action', () => {
    const f = fixture()
    const before = readFileSync(join(f.out, 'latest.json'), 'utf8')
    assert.notEqual(f.run({ RELEASE_FIXTURE_CI: 'skipped' }).status, 0)
    assert.equal(f.calls(), '')
    assert.equal(readFileSync(join(f.out, 'latest.json'), 'utf8'), before)
    assert.equal(existsSync(join(f.state, 'deployed')), false)
  })

  it('fences the captured installed legacy updater before compiling or restarting new unchecked main source', () => {
    const home = mkdtempSync(join(tmpdir(), 'pully-legacy-updater-'))
    temporary.push(home)
    const legacy = join(home, 'installed-legacy.sh')
    const source = execFileSync('git', ['show', '3540fa0aea002689d2b09677d7f1b02b16833363:scripts/pulls-review-update.sh'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
    writeFileSync(legacy, source)
    const f = fixture({ installed: legacy })
    const stable = f.git('rev-parse', 'HEAD')
    f.git('checkout', 'main')
    writeFileSync(join(f.repo, 'unchecked-main.txt'), 'Unshipped integration source.\n')
    f.git('add', '.')
    f.git('commit', '-m', 'unshipped integration')
    f.git('push', 'origin', 'main')
    writeFileSync(join(f.state, 'deployed'), stable)
    writeFileSync(join(f.out, 'pulls.apk'), 'previous APK')
    const before = readFileSync(join(f.out, 'latest.json'), 'utf8')
    const result = f.run()
    assert.equal(result.status, 1)
    assert.match(f.calls(), /pnpm run contracts:check/)
    assert.doesNotMatch(f.calls(), /cargo|systemctl|publish/)
    assert.equal(readFileSync(join(f.state, 'deployed'), 'utf8'), stable)
    assert.equal(readFileSync(join(f.state, 'failed'), 'utf8').trim(), f.git('rev-parse', 'HEAD'))
    assert.equal(readFileSync(join(f.out, 'latest.json'), 'utf8'), before)
    assert.equal(readFileSync(join(f.out, 'pulls.apk'), 'utf8'), 'previous APK')
  })

  it('builds and publishes clean source, without running prepare or dirtying tracked files', () => {
    const f = fixture()
    const result = f.run()
    assert.equal(result.status, 0, result.stderr)
    assert.match(f.calls(), /install --frozen-lockfile --ignore-scripts/)
    assert.match(f.calls(), /systemctl --user start pulls-review.service/)
    assert.doesNotMatch(f.calls(), /systemctl --user restart/)
    assert.match(f.calls(), /publish/)
    assert.equal(f.git('status', '--porcelain'), '')
  })

  it('preserves staged and unstaged local release edits and refuses to deploy them', () => {
    for (const staged of [false, true]) {
      const f = fixture()
      const path = join(f.repo, 'android/app/build.gradle.kts')
      writeFileSync(path, 'versionCode = 67\n')
      if (staged)
        f.git('add', path)
      const result = f.run()
      assert.equal(result.status, 1)
      assert.match(result.stderr, /tracked deployment files are dirty/)
      assert.equal(readFileSync(path, 'utf8'), 'versionCode = 67\n')
      assert.equal(f.calls(), '')
    }
  })

  it('revokes admission after a build if the release ref, checkout or tracked source changed', () => {
    for (const mutation of ['remote', 'checkout', 'dirty']) {
      const f = fixture()
      const before = readFileSync(join(f.out, 'latest.json'), 'utf8')
      const result = f.run({ BUILD_SOURCE_CHANGE: mutation })
      assert.equal(result.status, 1, result.stderr)
      assert.match(f.calls(), /cargo build/)
      assert.doesNotMatch(f.calls(), /idle-shutdown|systemctl|publish/)
      assert.equal(readFileSync(join(f.state, 'invocation'), 'utf8'), 'old-server')
      assert.equal(existsSync(join(f.state, 'deployed')), false)
      assert.equal(readFileSync(join(f.out, 'latest.json'), 'utf8'), before)
    }
  })

  it('fails the unit when APK publishing fails, then retries without rebuilding the server', () => {
    const f = fixture()
    const failed = f.run({ APK_PUBLISH_FAIL: '1' })
    assert.equal(failed.status, 17)
    assert.equal(JSON.parse(readFileSync(join(f.out, 'latest.json'))).versionCode, 65)
    assert.equal(f.run().status, 0)
    assert.equal(f.calls().match(/publish/g).length, 2)
    assert.equal(f.calls().match(/cargo build/g).length, 1)
  })

  it('keeps the previous service when a server build fails and latches the failed revision', () => {
    const f = fixture()
    assert.equal(f.run({ BUILD_FAIL: '1' }).status, 1)
    assert.doesNotMatch(f.calls(), /systemctl|publish/)
    assert.equal(f.run().status, 1)
    assert.equal(f.calls().match(/cargo build/g).length, 1)
    assert.equal(readFileSync(join(f.state, 'failed'), 'utf8').trim(), f.git('rev-parse', 'HEAD'))
  })

  it('refuses downgrades and corrupt published metadata', () => {
    const f = fixture({ have: 67 })
    assert.equal(f.run().status, 1)
    assert.doesNotMatch(f.calls(), /publish/)
    writeFileSync(join(f.out, 'latest.json'), 'broken')
    assert.notEqual(f.run().status, 0)
    assert.doesNotMatch(f.calls(), /publish/)
  })

  it('does not build or publish again when both the server and APK are current', () => {
    const f = fixture()
    assert.equal(f.run().status, 0)
    const before = f.calls()
    assert.equal(f.run().status, 0)
    assert.equal(f.calls(), before)
  })

  it('defers active AI work or unavailable idle support, then retries after work finishes', () => {
    for (const mode of ['busy', 'unavailable']) {
      const f = fixture()
      assert.equal(f.run({ SHUTDOWN_MODE: mode }).status, 0)
      assert.doesNotMatch(f.calls(), /systemctl --user start|publish/)
      assert.equal(existsSync(join(f.state, 'deployed')), false)
      assert.equal(existsSync(join(f.state, 'failed')), false)
      assert.equal(f.run().status, 0)
      assert.match(f.calls(), /systemctl --user start pulls-review.service/)
      assert.match(f.calls(), /publish/)
    }
  })

  it('defers an older running server without private-token update support', () => {
    const f = fixture()
    assert.equal(f.run({ PULLS_REVIEW_UPDATE_TOKEN_FILE: '' }).status, 0)
    assert.doesNotMatch(f.calls(), /idle-shutdown|systemctl --user start|publish/)
    assert.equal(existsSync(join(f.state, 'deployed')), false)
  })

  it('never restarts a supervisor-started replacement and reconciles a lost shutdown response', () => {
    for (const mode of ['restarted', 'lost-response', 'delayed-lost-response']) {
      const f = fixture()
      const result = f.run({ SHUTDOWN_MODE: mode })
      assert.equal(result.status, 0, result.stderr)
      assert.match(f.calls(), /systemctl --user start pulls-review.service/)
      assert.doesNotMatch(f.calls(), /systemctl --user restart/)
      assert.match(f.calls(), /publish/)
    }
  })

  it('does not record deployment or publish when starting the replacement fails', () => {
    const f = fixture()
    assert.equal(f.run({ START_FAIL: '1' }).status, 23)
    assert.equal(existsSync(join(f.state, 'deployed')), false)
    assert.doesNotMatch(f.calls(), /publish/)
    // The old server has already stopped. Retrying starts the replacement safely.
    assert.equal(f.run().status, 0)
    assert.match(f.calls(), /publish/)
  })
})
