import assert from 'node:assert/strict'
import { spawn, spawnSync } from 'node:child_process'
import { once } from 'node:events'
import { chmodSync, copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { delimiter, dirname, join } from 'node:path'
import process from 'node:process'
// eslint-disable-next-line test/no-import-node-test -- The native launcher has no npm runtime or test dependencies.
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'

const cliDir = fileURLToPath(new URL('..', import.meta.url))
const unix = process.platform !== 'win32'
const inspect = `console.log(JSON.stringify({
  argv: process.argv.slice(2), cwd: process.cwd(),
  web: process.env.PULLS_REVIEW_WEB_DIR, skills: process.env.PULLS_REVIEW_SKILLS_DIR,
  android: process.env.PULLS_REVIEW_ANDROID_DIR, helper: process.env.PULLS_REVIEW_AGENT_MCP_BINARY,
  custom: process.env.LAUNCHER_TEST_CUSTOM,
})); process.exit(Number(process.env.LAUNCHER_TEST_EXIT || 0))`

function fixture(t, source = false) {
  const root = mkdtempSync(join(tmpdir(), 'pulls-launcher-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const packageDir = join(root, source ? 'packages/cli' : 'node_modules/pulls.review')
  const cwd = join(root, 'reviewed repository')
  mkdirSync(packageDir, { recursive: true })
  mkdirSync(cwd)
  const launcher = join(packageDir, 'bin.mjs')
  copyFileSync(join(cliDir, 'bin.mjs'), launcher)
  const script = join(root, 'inspect.mjs')
  writeFileSync(script, inspect)
  const env = { ...process.env, PATH: '' }
  for (const key of Object.keys(env)) {
    if (key.startsWith('PULLS_REVIEW_') || key.startsWith('LAUNCHER_TEST_'))
      delete env[key]
  }
  if (source) {
    mkdirSync(join(root, 'backend'))
    writeFileSync(join(root, 'backend/Cargo.toml'), '[package]\nname = "pulls-review-server"\n')
    writeFileSync(join(root, 'package.json'), JSON.stringify({ name: '@pulls.review/monorepo' }))
  }
  return {
    root,
    packageDir,
    cwd,
    launcher,
    script,
    env,
    run: (args = [], overrides = {}) => spawnSync(process.execPath, [launcher, ...args], {
      cwd,
      env: { ...env, ...overrides },
      encoding: 'utf8',
      timeout: 5000,
    }),
  }
}

function fakeExecutable(path, body = inspect) {
  mkdirSync(dirname(path), { recursive: true })
  writeFileSync(path, `#!${process.execPath}\n${body}\n`)
  chmodSync(path, 0o755)
}

function output(result) {
  assert.equal(result.error, undefined)
  assert.equal(result.status, 0, result.stderr)
  return JSON.parse(result.stdout)
}

test('forwards arguments literally, cwd, environment and absolute packaged asset defaults', (t) => {
  const f = fixture(t)
  const args = ['main...feature with spaces', '--cwd', '../another repo', '--data-dir', './cache', '--assets', './custom ui', '$(touch SHOULD_NOT_EXIST); echo bad']
  const result = f.run([f.script, ...args], {
    PULLS_REVIEW_SERVER_BIN: process.execPath,
    PULLS_REVIEW_ANDROID_DIR: '/android-builds',
    PULLS_REVIEW_AGENT_MCP_BINARY: '/linux/helper',
    LAUNCHER_TEST_CUSTOM: 'preserved',
  })
  assert.deepEqual(output(result), {
    argv: args,
    cwd: f.cwd,
    web: join(f.packageDir, 'dist/client'),
    skills: join(f.packageDir, 'dist/skills'),
    android: '/android-builds',
    helper: '/linux/helper',
    custom: 'preserved',
  })
  assert.equal(existsSync(join(f.cwd, 'SHOULD_NOT_EXIST')), false)
})

test('preserves explicit web and skills overrides, including empty values', (t) => {
  const f = fixture(t)
  for (const value of ['../custom path', '']) {
    const data = output(f.run([f.script], {
      PULLS_REVIEW_SERVER_BIN: process.execPath,
      PULLS_REVIEW_WEB_DIR: value,
      PULLS_REVIEW_SKILLS_DIR: value,
    }))
    assert.equal(data.web, value)
    assert.equal(data.skills, value)
  }
})

test('propagates a native nonzero exit status', (t) => {
  const f = fixture(t)
  const result = f.run([f.script], { PULLS_REVIEW_SERVER_BIN: process.execPath, LAUNCHER_TEST_EXIT: '23' })
  assert.equal(result.status, 23)
})

test('inherits stdin, stdout and stderr', (t) => {
  const f = fixture(t)
  writeFileSync(f.script, 'process.stdin.pipe(process.stdout); process.stderr.write("native stderr\\n")')
  const result = spawnSync(process.execPath, [f.launcher, f.script], {
    cwd: f.cwd,
    env: { ...f.env, PULLS_REVIEW_SERVER_BIN: process.execPath },
    input: 'native stdin\n',
    encoding: 'utf8',
    timeout: 5000,
  })
  assert.equal(result.status, 0)
  assert.equal(result.stdout, 'native stdin\n')
  assert.equal(result.stderr, 'native stderr\n')
})

test('explicit missing executable fails closed even with a source build available', { skip: !unix }, (t) => {
  const f = fixture(t, true)
  fakeExecutable(join(f.root, 'backend/target/debug/pulls-review-server'))
  const result = f.run([], { PULLS_REVIEW_SERVER_BIN: join(f.root, 'missing server') })
  assert.equal(result.status, 1)
  assert.equal(result.stdout, '')
  assert.match(result.stderr, /Unable to start.*ENOENT/)
  assert.match(result.stderr, /PULLS_REVIEW_SERVER_BIN/)
})

test('explicit empty executable fails closed', (t) => {
  const f = fixture(t)
  const result = f.run([], { PULLS_REVIEW_SERVER_BIN: '' })
  assert.equal(result.status, 1)
  assert.match(result.stderr, /PULLS_REVIEW_SERVER_BIN is empty/)
})

test('explicit nonexecutable file fails closed', { skip: !unix }, (t) => {
  const f = fixture(t, true)
  const denied = join(f.root, 'not-executable')
  writeFileSync(denied, 'not an executable')
  fakeExecutable(join(f.root, 'backend/target/debug/pulls-review-server'))
  const result = f.run([], { PULLS_REVIEW_SERVER_BIN: denied })
  assert.equal(result.status, 1)
  assert.match(result.stderr, /EACCES/)
  assert.equal(result.stdout, '')
})

test('explicit executable takes precedence over checkout builds', { skip: !unix }, (t) => {
  const f = fixture(t, true)
  fakeExecutable(join(f.root, 'backend/target/debug/pulls-review-server'), 'process.exit(99)')
  assert.equal(output(f.run([f.script], { PULLS_REVIEW_SERVER_BIN: process.execPath })).cwd, f.cwd)
})

test('source wrapper resolves its own debug then release builds, never reviewed cwd', { skip: !unix }, (t) => {
  const f = fixture(t, true)
  const debug = join(f.root, 'backend/target/debug/pulls-review-server')
  fakeExecutable(debug, 'console.log(JSON.stringify({ selected: "debug", cwd: process.cwd() }))')
  fakeExecutable(join(f.root, 'backend/target/release/pulls-review-server'), 'console.log(JSON.stringify({ selected: "release" }))')
  fakeExecutable(join(f.cwd, 'backend/target/debug/pulls-review-server'), 'process.exit(99)')
  assert.deepEqual(output(f.run()), { selected: 'debug', cwd: f.cwd })
  rmSync(debug)
  assert.deepEqual(output(f.run()), { selected: 'release' })
})

test('source detection requires both package layout and checkout markers', { skip: !unix }, (t) => {
  const f = fixture(t, true)
  fakeExecutable(join(f.root, 'backend/target/debug/pulls-review-server'), 'process.exit(99)')
  rmSync(join(f.root, 'backend/Cargo.toml'))
  const result = f.run()
  assert.equal(result.status, 1)
  assert.match(result.stderr, /executable not found/)
})

test('installed wrapper cannot discover an ancestor checkout build', { skip: !unix }, (t) => {
  const f = fixture(t)
  mkdirSync(join(f.root, 'backend'))
  writeFileSync(join(f.root, 'backend/Cargo.toml'), '[package]\nname = "pulls-review-server"\n')
  writeFileSync(join(f.root, 'package.json'), JSON.stringify({ name: '@pulls.review/monorepo' }))
  fakeExecutable(join(f.root, 'backend/target/debug/pulls-review-server'), 'process.exit(99)')
  assert.equal(f.run().status, 1)
})

test('resolves an installed native executable on an absolute PATH entry', { skip: !unix }, (t) => {
  const f = fixture(t)
  const binDir = join(f.root, 'native bin')
  fakeExecutable(join(binDir, 'pulls-review-server'))
  assert.equal(output(f.run(['--help'], { PATH: binDir })).argv[0], '--help')
})

test('ignores relative and empty PATH entries instead of running reviewed files', { skip: !unix }, (t) => {
  const f = fixture(t)
  fakeExecutable(join(f.cwd, 'pulls-review-server'), 'process.exit(99)')
  fakeExecutable(join(f.cwd, 'bin/pulls-review-server'), 'process.exit(99)')
  const result = f.run([], { PATH: ['', '.', 'bin'].join(delimiter) })
  assert.equal(result.status, 1)
  assert.match(result.stderr, /executable not found/)
})

test('missing native binary explains setup without creating assets or builds', (t) => {
  const f = fixture(t, true)
  const result = f.run()
  assert.equal(result.status, 1)
  assert.match(result.stderr, /cargo build --locked --manifest-path/)
  assert.match(result.stderr, /cargo install --locked --path/)
  assert.match(result.stderr, /does not download or build/)
  assert.equal(existsSync(join(f.packageDir, 'dist')), false)
  assert.equal(existsSync(join(f.root, 'backend/target')), false)
})

test('propagates native signal termination', { skip: !unix }, (t) => {
  const f = fixture(t)
  writeFileSync(f.script, 'process.kill(process.pid, "SIGTERM")')
  const result = f.run([f.script], { PULLS_REVIEW_SERVER_BIN: process.execPath })
  assert.equal(result.status, null)
  assert.equal(result.signal, 'SIGTERM')
})

for (const signal of ['SIGINT', 'SIGTERM', 'SIGHUP']) {
  test(`forwards ${signal} and waits for native cleanup`, { skip: !unix, timeout: 5000 }, async (t) => {
    const f = fixture(t)
    writeFileSync(f.script, `process.on('${signal}', () => { console.log('cleaned'); process.exit(37) }); console.log('ready'); setInterval(() => {}, 1000)`)
    const child = spawn(process.execPath, [f.launcher, f.script], {
      cwd: f.cwd,
      env: { ...f.env, PULLS_REVIEW_SERVER_BIN: process.execPath },
      stdio: ['ignore', 'pipe', 'pipe'],
    })
    t.after(() => child.kill('SIGKILL'))
    let stdout = ''
    const ready = new Promise((resolve, reject) => {
      child.once('error', reject)
      child.once('exit', () => reject(new Error('Launcher exited before native readiness')))
      child.stdout.on('data', (data) => {
        stdout += data
        if (stdout.includes('ready'))
          resolve()
      })
    })
    const closed = once(child, 'close')
    await ready
    child.kill(signal)
    const [code, exitSignal] = await closed
    assert.equal(code, 37)
    assert.equal(exitSignal, null)
    assert.match(stdout, /cleaned/)
  })
}

test('asset preparation removes all stale server bundles and copies only curated skills', (t) => {
  const f = fixture(t, true)
  mkdirSync(join(f.packageDir, 'scripts'))
  copyFileSync(join(cliDir, 'scripts/build.mjs'), join(f.packageDir, 'scripts/build.mjs'))
  mkdirSync(join(f.root, 'docker/agents/skills/security'), { recursive: true })
  writeFileSync(join(f.root, 'docker/agents/skills/security/SKILL.md'), 'curated security lens\n')
  mkdirSync(join(f.packageDir, 'dist/client'), { recursive: true })
  for (const old of ['index.mjs', 'devframe.mjs', 'pr-server.mjs', 'client/old.js'])
    writeFileSync(join(f.packageDir, 'dist', old), 'stale')
  const result = spawnSync(process.execPath, [join(f.packageDir, 'scripts/build.mjs')], { cwd: f.cwd, encoding: 'utf8' })
  assert.equal(result.status, 0, result.stderr)
  for (const old of ['index.mjs', 'devframe.mjs', 'pr-server.mjs', 'client'])
    assert.equal(existsSync(join(f.packageDir, 'dist', old)), false)
  assert.equal(readFileSync(join(f.packageDir, 'dist/skills/security/SKILL.md'), 'utf8'), 'curated security lens\n')
})

test('npm package retains both launch aliases and has no server dependency or exported TS entry', () => {
  const manifest = JSON.parse(readFileSync(join(cliDir, 'package.json'), 'utf8'))
  assert.equal(manifest.name, 'pulls.review')
  assert.deepEqual(manifest.bin, { 'pulls.review': './bin.mjs', 'pulls-review': './bin.mjs' })
  assert.deepEqual(manifest.files, ['bin.mjs', 'dist'])
  assert.equal(manifest.dependencies, undefined)
  assert.equal(manifest.devDependencies, undefined)
  assert.equal(manifest.exports, undefined)
  assert.match(manifest.scripts.build, /^node scripts\/build.mjs && pnpm -F @pulls.review\/app run build:local$/)
  for (const removed of ['src', 'tsconfig.json', 'tsdown.config.ts', 'vitest.config.ts'])
    assert.equal(existsSync(join(cliDir, removed)), false)
})
