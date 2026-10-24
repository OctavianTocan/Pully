import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { copyFileSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
// eslint-disable-next-line test/no-import-node-test
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'

const source = join(dirname(fileURLToPath(import.meta.url)), 'publish-staging-android.sh')
const fingerprint = createHash('sha256').update('pinned certificate').digest('hex')

function fixture({ tools = true, packageName = 'com.octaviantocan.pulls.staging', signer = fingerprint } = {}) {
  const root = mkdtempSync(join(tmpdir(), 'pully-staging-publish-'))
  const bin = join(root, 'bin')
  const sdk = join(root, 'Android SDK')
  for (const path of ['packages/worker/scripts', 'android/app', 'bin', 'Android SDK/build-tools/35.0.0', 'Android SDK/build-tools/37.0.0'])
    mkdirSync(join(root, path), { recursive: true })
  const executable = (path, text) => writeFileSync(path, `#!/usr/bin/env bash\nset -eu\n${text}\n`, { mode: 0o755 })
  copyFileSync(source, join(root, 'packages/worker/scripts/publish-staging-android.sh'))
  writeFileSync(join(root, 'android/CHANGELOG.md'), 'Staging fixture')
  executable(join(root, 'android/gradlew'), 'mkdir -p app/build/outputs/apk/debug; printf "staging apk" > app/build/outputs/apk/debug/app-debug.apk; echo build >> "$TEST_EVENTS"')
  executable(join(bin, 'keytool'), 'printf "pinned certificate"')
  executable(join(bin, 'npx'), 'printf "%s\\n" "$*" >> "$TEST_EVENTS"; if [[ "$*" == *latest.json* ]]; then while [ "$1" != --file ]; do shift; done; cp "$2" "$TEST_MANIFEST"; fi')
  for (const tool of ['aapt2', 'apksigner'])
    executable(join(sdk, 'build-tools/35.0.0', tool), 'echo wrong-sdk >> "$TEST_EVENTS"; exit 99')
  if (tools) {
    executable(join(sdk, 'build-tools/37.0.0/aapt2'), `[[ "$1 $2" == 'dump badging' ]] && [ "$#" = 3 ]; echo "package: name='${packageName}' versionCode='1' versionName='0.3.0-staging.1'"`)
    executable(join(sdk, 'build-tools/37.0.0/apksigner'), `[[ "$1 $2" == 'verify --print-certs' ]] && [ "$#" = 3 ]; echo 'Signer #1 certificate SHA-256 digest: ${signer}'`)
  }
  const git = (...args) => {
    const result = spawnSync('git', args, { cwd: root, encoding: 'utf8' })
    assert.equal(result.status, 0, result.stderr)
    return result.stdout.trim()
  }
  git('init', '-q', '-b', 'main')
  git('add', '.')
  git('-c', 'user.name=Staging fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'Staging fixture')
  const events = join(root, 'events')
  const manifest = join(root, 'manifest.json')
  writeFileSync(events, '')
  return {
    events,
    manifest,
    commit: git('rev-parse', 'HEAD'),
    run: () => spawnSync('bash', [join(root, 'packages/worker/scripts/publish-staging-android.sh'), 'https://staging.example.invalid'], {
      cwd: root,
      encoding: 'utf8',
      env: { ...process.env, PATH: `${bin}:${process.env.PATH}`, ANDROID_HOME: sdk, TEST_EVENTS: events, TEST_MANIFEST: manifest },
    }),
  }
}

test('staging publication selects the pinned SDK with several installed versions and a spaced SDK path', () => {
  const f = fixture()
  const result = f.run()
  assert.equal(result.status, 0, result.stderr)
  const events = readFileSync(f.events, 'utf8').trim().split('\n')
  assert.equal(events.length, 3)
  assert.equal(events[0], 'build')
  assert.match(events[1], /r2 object put pully-android\/staging\/pulls.apk/)
  assert.match(events[2], /r2 object put pully-android\/staging\/latest.json/)
  const manifest = JSON.parse(readFileSync(f.manifest, 'utf8'))
  assert.equal(manifest.versionCode, 1)
  assert.equal(manifest.sourceCommit, f.commit)
  assert.equal(manifest.apkUrl, 'https://staging.example.invalid/download/staging/pully.apk')
  assert.equal(manifest.sha256, createHash('sha256').update('staging apk').digest('hex'))
})

test('missing pinned SDK tools refuse building and publication', () => {
  const f = fixture({ tools: false })
  const result = f.run()
  assert.notEqual(result.status, 0)
  assert.match(result.stderr, /Missing Android build tool/)
  assert.equal(readFileSync(f.events, 'utf8'), '')
})

for (const [name, options] of [['stable package', { packageName: 'com.octaviantocan.pulls' }], ['different signing key', { signer: 'wrong-fingerprint' }]]) {
  test(`staging publication refuses a ${name} before any upload`, () => {
    const f = fixture(options)
    assert.notEqual(f.run().status, 0)
    assert.equal(readFileSync(f.events, 'utf8'), 'build\n')
  })
}
