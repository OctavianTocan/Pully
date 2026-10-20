import assert from 'node:assert/strict'
import { execFileSync, spawnSync } from 'node:child_process'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
// The release CI preflight runs before installing workspace dependencies.
// eslint-disable-next-line test/no-import-node-test
import { afterEach, describe, it } from 'node:test'
import { fileURLToPath } from 'node:url'

const updater = fileURLToPath(new URL('./pulls-review-update.sh', import.meta.url))
const temporary = []
afterEach(() => {
  for (const path of temporary.splice(0))
    rmSync(path, { recursive: true, force: true })
})

function fixture({ have = 65 } = {}) {
  const home = mkdtempSync(join(tmpdir(), 'pully-update-test-'))
  temporary.push(home)
  const repo = join(home, 'repo')
  const state = join(home, 'state')
  const out = join(home, 'android')
  const bin = join(home, 'bin')
  const calls = join(home, 'calls')
  for (const path of [repo, state, out, bin, join(repo, 'android/app'), join(repo, 'android/scripts')])
    mkdirSync(path, { recursive: true })
  const git = (...args) => execFileSync('git', args, { cwd: repo, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim()
  git('init', '-b', 'main')
  git('config', 'user.email', 'test@example.com')
  git('config', 'user.name', 'Updater test')
  writeFileSync(join(repo, 'android/app/build.gradle.kts'), 'versionCode = 66\nversionName = "0.52.0"\n')
  writeFileSync(join(repo, 'android/scripts/publish-apk.sh'), `#!/bin/sh
set -eu
echo publish >> "$CALLS"
if [ "\${APK_PUBLISH_FAIL:-0}" = 1 ]; then exit 17; fi
printf '{"versionCode":66}' > "$PULLS_REVIEW_ANDROID_DIR/latest.json"
`, { mode: 0o755 })
  git('add', '.')
  git('commit', '-m', 'fixture')
  git('clone', '--bare', repo, join(home, 'origin.git'))
  git('remote', 'add', 'origin', join(home, 'origin.git'))
  writeFileSync(join(out, 'latest.json'), JSON.stringify({ versionCode: have }))
  writeFileSync(calls, '')
  for (const command of ['pnpm', 'cargo', 'systemctl']) {
    writeFileSync(join(bin, command), `#!/bin/sh
echo '${command}' "$@" >> "$CALLS"
if [ "\${BUILD_FAIL:-0}" = 1 ] && [ '${command}' = cargo ]; then exit 19; fi
`, { mode: 0o755 })
  }
  const run = extra => spawnSync('/bin/sh', [updater], {
    encoding: 'utf8',
    env: { ...process.env, HOME: home, PATH: `${bin}:/usr/bin:/bin`, CALLS: calls, PULLS_REVIEW_REPO: repo, PULLS_REVIEW_UPDATE_STATE: state, PULLS_REVIEW_ANDROID_DIR: out, PULLS_REVIEW_PUBLIC_URL: 'https://example.com', ...extra },
  })
  return { repo, state, out, run, git, calls: () => readFileSync(calls, 'utf8') }
}

describe('Pully deployment updater', () => {
  it('builds and publishes clean source, without running prepare or dirtying tracked files', () => {
    const f = fixture()
    const result = f.run()
    assert.equal(result.status, 0, result.stderr)
    assert.match(f.calls(), /install --frozen-lockfile --ignore-scripts/)
    assert.match(f.calls(), /systemctl --user restart pulls-review.service/)
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
})
