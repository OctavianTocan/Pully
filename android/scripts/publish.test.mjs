import assert from 'node:assert/strict'
import { execFileSync, spawn, spawnSync } from 'node:child_process'
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
// The release CI preflight runs before installing workspace dependencies.
// eslint-disable-next-line test/no-import-node-test
import { afterEach, describe, it } from 'node:test'
import { setTimeout } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'
import { releaseFixtureGate } from '../../scripts/release-track.test-support.mjs'

const publisher = fileURLToPath(new URL('./publish-apk.sh', import.meta.url))
const pastBuilder = fileURLToPath(new URL('./build-past-ui.py', import.meta.url))
const temporary = []
afterEach(async () => {
  for (const path of temporary.splice(0)) {
    await waitForHistory(path)
    rmSync(path, { recursive: true, force: true })
  }
})

function fixture({ have = 65, code = 66, output, build = ':' } = {}) {
  const home = mkdtempSync(join(tmpdir(), 'pully-publish-test-'))
  temporary.push(home)
  const repo = join(home, 'repo')
  const android = join(repo, 'android')
  const out = output || join(home, 'published')
  const sdk = join(home, 'sdk')
  for (const path of [join(android, 'scripts'), join(android, 'app/build/outputs/apk/debug'), out, join(sdk, 'build-tools/37.0.0')])
    mkdirSync(path, { recursive: true })
  copyFileSync(publisher, join(android, 'scripts/publish-apk.sh'))
  mkdirSync(join(repo, 'scripts'), { recursive: true })
  writeFileSync(join(repo, 'scripts/release-track.mjs'), '// The fixture PATH invokes the actual production admission with synthetic CI responses.\n')
  copyFileSync(pastBuilder, join(android, 'scripts/build-past-ui.py'))
  writeFileSync(join(android, 'gradlew'), `#!/bin/sh\nset -e\n${build}\nmkdir -p app/build/outputs/apk/debug\ncat build-input.txt > app/build/outputs/apk/debug/app-debug.apk\n`, { mode: 0o755 })
  writeFileSync(join(android, 'CHANGELOG.md'), '## 0.52.0\n\nLinks work.\n\n- A change.\n')
  writeFileSync(join(android, 'app/build.gradle.kts'), `versionCode = ${code}\nversionName = "0.52.0"\n`)
  const bytes = `new APK bytes ${code}`
  writeFileSync(join(android, 'build-input.txt'), bytes)
  writeFileSync(join(android, 'app/build/outputs/apk/debug/app-debug.apk'), bytes)
  writeFileSync(join(android, '.gitignore'), 'app/build/\n')
  writeFileSync(join(sdk, 'build-tools/37.0.0/aapt2'), `#!/bin/sh\necho "package: name='com.octaviantocan.pulls' versionCode='${code}' versionName='0.52.0'"\n`, { mode: 0o755 })
  const git = (...args) => execFileSync('git', args, { cwd: repo, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim()
  git('init', '-b', 'release')
  git('config', 'user.email', 'test@example.com')
  git('config', 'user.name', 'Publisher test')
  git('add', '.')
  git('commit', '-m', 'fixture')
  git('clone', '--bare', repo, join(home, 'origin.git'))
  git('remote', 'add', 'origin', join(home, 'origin.git'))
  const bin = releaseFixtureGate(home)
  writeFileSync(join(out, 'latest.json'), JSON.stringify({ versionCode: have, versionName: '0.51.0', sha256: 'previous' }))
  writeFileSync(join(out, 'pulls.apk'), 'previous APK bytes')
  const options = {
    encoding: 'utf8',
    env: { ...process.env, HOME: home, PATH: `${bin}:${process.env.PATH}`, JAVA_HOME: home, ANDROID_HOME: sdk, PULLS_REVIEW_ANDROID_DIR: out },
  }
  const args = [join(android, 'scripts/publish-apk.sh'), 'https://example.com']
  const run = (extra = {}) => spawnSync('/bin/bash', args, { ...options, env: { ...options.env, ...extra } })
  const start = () => {
    const child = spawn('/bin/bash', args, options)
    let stderr = ''
    child.stderr.on('data', data => stderr += data)
    const done = new Promise((resolve, reject) => {
      child.on('error', reject)
      child.on('close', status => resolve({ status, stderr }))
    })
    return { child, done }
  }
  return { android, home, out, git, run, start, bytes }
}

async function waitFor(path) {
  const deadline = Date.now() + 5000
  while (!existsSync(path)) {
    assert.ok(Date.now() < deadline, `Timed out waiting for ${path}`)
    await setTimeout(10)
  }
}

async function waitForHistory(home) {
  const repo = join(home, 'repo')
  if (!existsSync(join(repo, '.git')))
    return
  const deadline = Date.now() + 5000
  for (;;) {
    const listed = spawnSync('git', ['worktree', 'list', '--porcelain'], { cwd: repo, encoding: 'utf8' })
    assert.equal(listed.status, 0, listed.stderr)
    if ((listed.stdout.match(/^worktree /gm) || []).length === 1)
      return
    assert.ok(Date.now() < deadline, 'Owned history checkout must be cleaned after the background process finishes')
    await setTimeout(10)
  }
}

describe('APK publication safeguards', () => {
  it('refuses manual main publication or incomplete release checks before starting Gradle', () => {
    for (const main of [false, true]) {
      const f = fixture({ build: 'touch "$HOME/entered"' })
      if (main)
        f.git('checkout', '-b', 'main')
      const before = readFileSync(join(f.out, 'latest.json'), 'utf8')
      const result = f.run(main ? {} : { RELEASE_FIXTURE_CI: 'skipped' })
      assert.notEqual(result.status, 0)
      assert.equal(existsSync(join(f.home, 'entered')), false)
      assert.equal(readFileSync(join(f.out, 'latest.json'), 'utf8'), before)
      assert.equal(readFileSync(join(f.out, 'pulls.apk'), 'utf8'), 'previous APK bytes')
    }
  })

  it('builds immutable approved inputs even if the caller switches to unreviewed main during Gradle', async () => {
    const f = fixture({ build: 'touch "$HOME/entered"\nwhile [ ! -e "$HOME/release" ]; do sleep 0.01; done' })
    const approved = f.git('rev-parse', 'HEAD')
    const task = f.start()
    try {
      await waitFor(join(f.home, 'entered'))
      f.git('checkout', '-b', 'main')
      writeFileSync(join(f.android, 'build-input.txt'), 'unreviewed inputs')
      f.git('add', '.')
      f.git('commit', '-m', 'unreviewed inputs')
      writeFileSync(join(f.home, 'release'), '')
      const result = await task.done
      assert.equal(result.status, 0, result.stderr)
      assert.equal(readFileSync(join(f.out, 'pulls.apk'), 'utf8'), f.bytes)
      assert.equal(JSON.parse(readFileSync(join(f.out, 'latest.json'))).sourceCommit, approved)
      assert.equal(JSON.parse(readFileSync(join(f.out, 'latest.json'))).apkUrl, 'https://example.com/download/pully.apk')
      assert.equal(f.git('branch', '--show-current'), 'main')
      assert.deepEqual(JSON.parse(readFileSync(join(f.out, 'past/index.json'))).versions, [])
      await waitForHistory(f.home)
      const log = readFileSync(join(f.out, 'past/build.log'), 'utf8')
      assert.match(log, /index.json lists 0 past versions/)
      assert.doesNotMatch(log, /Traceback|unverified|refused/)
    }
    finally {
      writeFileSync(join(f.home, 'release'), '')
      await task.done
    }
  })

  it('refuses a release ref changed during the immutable build before replacing served files', async () => {
    const f = fixture({ build: 'touch "$HOME/entered"\nwhile [ ! -e "$HOME/release" ]; do sleep 0.01; done' })
    const before = readFileSync(join(f.out, 'latest.json'), 'utf8')
    const task = f.start()
    try {
      await waitFor(join(f.home, 'entered'))
      writeFileSync(join(f.android, 'build-input.txt'), 'next release inputs')
      f.git('add', '.')
      f.git('commit', '-m', 'next release')
      f.git('push', 'origin', 'release')
      writeFileSync(join(f.home, 'release'), '')
      assert.notEqual((await task.done).status, 0)
      assert.equal(readFileSync(join(f.out, 'latest.json'), 'utf8'), before)
      assert.equal(readFileSync(join(f.out, 'pulls.apk'), 'utf8'), 'previous APK bytes')
    }
    finally {
      writeFileSync(join(f.home, 'release'), '')
      await task.done
    }
  })

  it('publishes a newer build with its clean source commit and permits identical retries', () => {
    const f = fixture()
    const result = f.run()
    assert.equal(result.status, 0, result.stderr)
    const manifest = JSON.parse(readFileSync(join(f.out, 'latest.json')))
    assert.equal(manifest.versionCode, 66)
    assert.equal(manifest.sourceCommit, f.git('rev-parse', 'HEAD'))
    assert.equal(readFileSync(join(f.out, 'pulls.apk'), 'utf8'), f.bytes)
    assert.equal(f.run().status, 0)
  })

  it('refuses older or same-code different-byte builds before replacing either served file', () => {
    for (const have of [66, 67]) {
      const f = fixture({ have })
      const before = readFileSync(join(f.out, 'latest.json'), 'utf8')
      assert.notEqual(f.run().status, 0)
      assert.equal(readFileSync(join(f.out, 'latest.json'), 'utf8'), before)
      assert.equal(readFileSync(join(f.out, 'pulls.apk'), 'utf8'), 'previous APK bytes')
    }
  })

  it('rejects a local release bump without deleting it or replacing the APK', () => {
    const f = fixture()
    writeFileSync(join(f.android, 'app/build.gradle.kts'), 'versionCode = 67\n')
    const result = f.run()
    assert.notEqual(result.status, 0)
    assert.match(result.stderr, /Refusing to publish tracked local edits/)
    assert.equal(readFileSync(join(f.android, 'app/build.gradle.kts'), 'utf8'), 'versionCode = 67\n')
    assert.equal(readFileSync(join(f.out, 'pulls.apk'), 'utf8'), 'previous APK bytes')
  })

  it('serializes clean checkouts and rejects an older publisher queued behind a newer one', async () => {
    const newer = fixture({ code: 67, build: 'touch "$HOME/entered"\nwhile [ ! -e "$HOME/release" ]; do sleep 0.01; done' })
    const older = fixture({ output: newer.out, build: 'touch "$HOME/entered"' })
    const first = newer.start()
    let second
    try {
      await waitFor(join(newer.home, 'entered'))
      second = older.start()
      await setTimeout(150)
      assert.equal(existsSync(join(older.home, 'entered')), false, 'queued publisher must not start building with the shared lock held')
      writeFileSync(join(newer.home, 'release'), '')
      const published = await first.done
      assert.equal(published.status, 0, published.stderr)
      const rejected = await second.done
      assert.notEqual(rejected.status, 0)
      assert.match(rejected.stderr, /refusing to replace published build 67 with 66/)
      const manifest = JSON.parse(readFileSync(join(newer.out, 'latest.json')))
      assert.equal(manifest.versionCode, 67)
      assert.equal(manifest.sourceCommit, newer.git('rev-parse', 'HEAD'))
      assert.equal(readFileSync(join(newer.out, 'pulls.apk'), 'utf8'), newer.bytes)
    }
    finally {
      first.child.kill()
      second?.child.kill()
      writeFileSync(join(newer.home, 'release'), '')
      await first.done
      if (second)
        await second.done
    }
  })

  it('releases the publication lock after a failed build without replacing served files', () => {
    const failed = fixture({ build: 'exit 17' })
    assert.equal(failed.run().status, 17)
    assert.equal(readFileSync(join(failed.out, 'pulls.apk'), 'utf8'), 'previous APK bytes')
    const retry = fixture({ output: failed.out })
    assert.equal(retry.run().status, 0)
    assert.equal(readFileSync(join(retry.out, 'pulls.apk'), 'utf8'), retry.bytes)
    assert.equal(existsSync(join(retry.out, 'latest.json.tmp')), false)
    assert.equal(existsSync(join(retry.out, 'pulls.apk.tmp')), false)
  })

  it('keeps the historical index valid while independent writers publish it concurrently', () => {
    const home = mkdtempSync(join(tmpdir(), 'pully-past-index-test-'))
    temporary.push(home)
    const result = spawnSync('python3', ['-c', `
import importlib.util, json, pathlib, subprocess, sys, time
script, directory = sys.argv[1:]
dest = pathlib.Path(directory)
meta = dict(versionName='0.1.0', versionCode=1, packageName='fixture', commit='fixture', date='fixture', subject='A' * 100000, sha256='fixture', size=1)
(dest / '0.1.0.json').write_text(json.dumps(meta))
(dest / '0.1.0.apk').write_bytes(b'x')
worker = '''import importlib.util, pathlib, sys
spec = importlib.util.spec_from_file_location('past', sys.argv[1])
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
for _ in range(30): module.write_index(pathlib.Path(sys.argv[2]))
'''
writers = [subprocess.Popen([sys.executable, '-c', worker, script, directory], stdout=subprocess.DEVNULL) for _ in range(2)]
reads = 0
try:
    while any(writer.poll() is None for writer in writers):
        index = dest / 'index.json'
        if index.exists():
            assert json.loads(index.read_text())['versions'][0]['versionCode'] == 1
            reads += 1
        time.sleep(0.001)
    assert all(writer.wait() == 0 for writer in writers)
    assert reads > 0
    assert not list(dest.glob('*.tmp'))
finally:
    for writer in writers:
        if writer.poll() is None: writer.kill()
        writer.wait()
`, pastBuilder, home], { encoding: 'utf8', timeout: 15000 })
    assert.equal(result.status, 0, result.stderr)
  })

  it('excludes unshipped main versions and orphaned cached APKs from the release history index', () => {
    const f = fixture()
    const approved = f.git('rev-parse', 'HEAD')
    f.git('checkout', '-b', 'main')
    writeFileSync(join(f.android, 'app/build.gradle.kts'), 'versionCode = 67\nversionName = "0.53.0"\n')
    f.git('add', '.')
    f.git('commit', '-m', 'unshipped version')
    const result = spawnSync('python3', ['-c', `
import importlib.util, json, pathlib, sys
script, repo, approved, directory = sys.argv[1:]
spec = importlib.util.spec_from_file_location('past', script)
past = importlib.util.module_from_spec(spec)
spec.loader.exec_module(past)
past.REPO = pathlib.Path(repo)
past.ANDROID = past.REPO / 'android'
known = past.versions(approved)
assert [item['versionName'] for item in known] == ['0.52.0']
dest = pathlib.Path(directory)
dest.mkdir()
for name, commit in [('0.52.0', approved), ('0.53.0', 'unshipped')]:
    meta = dict(versionName=name, versionCode=66, packageName='fixture', commit=commit, date='fixture', subject='Fixture', sha256='fixture', size=1)
    (dest / (name + '.json')).write_text(json.dumps(meta))
    (dest / (name + '.apk')).write_bytes(b'x')
past.write_index(dest, {(item['versionName'], item['commit']) for item in known})
index = json.loads((dest / 'index.json').read_text())
assert [item['versionName'] for item in index['versions']] == ['0.52.0']
assert (dest / '0.53.0.apk').exists(), 'Do not delete operator cache artifacts'
`, pastBuilder, join(f.home, 'repo'), approved, join(f.home, 'history')], { encoding: 'utf8' })
    assert.equal(result.status, 0, result.stderr)
  })

  it('refuses shallow release history without hiding legitimate cached versions', () => {
    const f = fixture()
    const previous = f.git('rev-parse', 'HEAD')
    writeFileSync(join(f.android, 'app/build.gradle.kts'), 'versionCode = 67\nversionName = "0.53.0"\n')
    f.git('add', '.')
    f.git('commit', '-m', 'next release')
    f.git('push', 'origin', 'release')
    const shallow = join(f.home, 'shallow')
    f.git('clone', '--depth', '1', '--branch', 'release', `file://${join(f.home, 'origin.git')}`, shallow)
    const history = join(f.out, 'past')
    mkdirSync(history)
    const index = JSON.stringify({ versions: [{ versionName: '0.52.0', commit: previous }] })
    writeFileSync(join(history, 'index.json'), index)
    writeFileSync(join(history, '0.52.0.apk'), 'legitimate previous APK')
    const result = spawnSync('python3', [join(shallow, 'android/scripts/build-past-ui.py'), '--index'], {
      encoding: 'utf8',
      env: { ...process.env, PATH: `${join(f.home, 'bin')}:${process.env.PATH}`, PULLS_REVIEW_ANDROID_DIR: f.out },
    })
    assert.notEqual(result.status, 0)
    assert.match(result.stderr, /Complete release history is required/)
    assert.equal(readFileSync(join(history, 'index.json'), 'utf8'), index)
    assert.equal(readFileSync(join(history, '0.52.0.apk'), 'utf8'), 'legitimate previous APK')
  })
})
