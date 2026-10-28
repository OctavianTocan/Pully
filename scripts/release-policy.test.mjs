import assert from 'node:assert/strict'
import { readdirSync, readFileSync } from 'node:fs'
// eslint-disable-next-line test/no-import-node-test
import { describe, it } from 'node:test'
import YAML from 'yaml'
import { releasePolicy } from './release-track.mjs'

const root = new URL('../', import.meta.url)
const read = path => readFileSync(new URL(path, root), 'utf8')
const workflow = name => YAML.parse(read(`.github/workflows/${name}.yml`))

describe('integration and release workflow boundaries', () => {
  it('keeps main integration free of compilers and per-feature release-number comparisons', () => {
    const ci = workflow('ci')
    assert.deepEqual(ci.on.pull_request.branches, ['main', 'release'])
    assert.equal(ci.on.workflow_dispatch, undefined, 'a manual job must never overwrite release-base evidence')
    const fast = ci.jobs['android-release']
    assert.ok(fast.steps.some(step => step.run?.includes('release-track.test.mjs')))
    assert.ok(fast.steps.some(step => step.run?.includes('legacy fast integration policy context')))
    assert.doesNotMatch(fast.steps.map(step => step.run || '').join('\n'), /check-release\.mjs|gradlew|cargo build|xcodebuild/)
    const strict = ci.jobs['release-prerequisites']
    assert.equal(strict.if, 'github.base_ref == \'release\' || github.ref == \'refs/heads/release\'')
    // eslint-disable-next-line no-template-curly-in-string -- This is a literal GitHub expression.
    assert.ok(strict.steps.some(step => step.env?.RELEASE_BASE === '${{ github.event.pull_request.base.sha || github.event.before }}'))
    assert.ok(strict.steps.some(step => step.run === 'node android/scripts/check-release.mjs --base "$RELEASE_BASE"'))
  })

  it('runs complete Android, native and backend suites on every release promotion without path holes', () => {
    for (const [file, name] of [['android-verify', 'android-release-verify'], ['ios-verify', 'ios-release-verify'], ['rust-backend-compile', 'backend-release-verify']]) {
      const w = workflow(file)
      assert.deepEqual(w.on.pull_request, { branches: ['release'] })
      assert.ok(w.on.push.branches.includes('release'))
      assert.equal(w.on.push.paths, undefined)
      assert.ok(Object.hasOwn(w.on, 'workflow_dispatch'))
      assert.equal(w.jobs[name].if, undefined)
      assert.equal(releasePolicy.requiredReleaseChecks.includes(name), name !== 'ios-release-verify')
    }
    const native = workflow('ios-verify').jobs['ios-release-verify']
    assert.equal(Object.keys(workflow('ios-verify').jobs).length, 1, 'do not duplicate a whole Mac build on main')
    for (const name of ['Test native account storage and configuration rules', 'Test actual Keychain in an ad-hoc signed simulator app host', 'Test actual Darwin RPC transport against loopback HTTP', 'Test shared domain on JVM and iOS; link Compose framework', 'Build and test the private iOS simulator host', 'Require every actual UIKit test and retain readable evidence', 'Verify original Pully fonts and canonical locales are bundled'])
      assert.ok(native.steps.some(step => step.name === name), name)
    const host = native.steps.find(step => step.name === 'Build and test the private iOS simulator host').run
    for (const proof of ['build CODE_SIGNING_ALLOWED=NO', 'build-for-testing', 'CODE_SIGN_IDENTITY=-', 'codesign --verify --strict', 'verify-keychain-test-host.py', 'pully-app', 'test-without-building'])
      assert.ok(host.includes(proof), `Real app-private Keychain host proof: ${proof}`)
    const android = workflow('android-verify').jobs['android-release-verify'].steps.map(s => s.run || '').join('\n')
    for (const task of [':shared-domain:jvmTest', ':shared-client:jvmTest', ':shared-ui:testAndroidHostTest', ':app:testDebugUnitTest', ':app:assembleDebug'])
      assert.ok(android.includes(task), task)
    for (const proof of ['aapt2', 'apksigner', 'keytool', 'actual.groups()', 'test "$actual" = "$expected"'])
      assert.ok(android.includes(proof), proof)
  })

  it('uses Pully Linux runners and keeps iOS optional on GitHub-hosted macOS', () => {
    const files = readdirSync(new URL('../.github/workflows/', import.meta.url)).filter(file => file.endsWith('.yml') && file !== 'ios-verify.yml')
    for (const file of files) {
      for (const job of Object.values(workflow(file.slice(0, -4)).jobs))
        assert.deepEqual(job['runs-on'], ['self-hosted', 'linux', 'x64', 'pully-linux'], file)
    }
    assert.equal(workflow('ios-verify').jobs['ios-release-verify']['runs-on'], 'macos-26')
    assert.ok(!releasePolicy.requiredReleaseChecks.includes('ios-release-verify'))
  })

  it('fences manual/tag and legacy deployment consumers before publication', () => {
    const tag = workflow('release')
    assert.equal(tag.jobs.release.needs, 'release-source')
    assert.ok(tag.jobs['release-source'].steps.some(s => s.run === 'node scripts/release-track.mjs'))
    const pkg = JSON.parse(read('package.json'))
    assert.ok(pkg.scripts['contracts:check'].startsWith('node scripts/release-track.mjs --deployment-only &&'))
    assert.equal(releasePolicy.stableBaseline, '3540fa0aea002689d2b09677d7f1b02b16833363')
    const update = read('scripts/pulls-review-update.sh')
    assert.ok(update.includes('git fetch -q origin release'))
    assert.ok(update.indexOf('node scripts/release-track.mjs') < update.indexOf('start_updated_server()'))
    const publish = read('android/scripts/publish-apk.sh')
    assert.ok(publish.indexOf('node ../scripts/release-track.mjs') < publish.indexOf('mkdir -p "$out"'))
    assert.ok(publish.includes('worktree add --detach'))
    assert.ok(publish.indexOf('node scripts/release-track.mjs') < publish.indexOf('cp "$apk"'))
  })
})
