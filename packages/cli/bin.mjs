#!/usr/bin/env node
import { spawn } from 'node:child_process'
import { accessSync, constants, existsSync, readFileSync, statSync } from 'node:fs'
import { constants as osConstants } from 'node:os'
import { delimiter, dirname, isAbsolute, join } from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

const packageDir = dirname(fileURLToPath(import.meta.url))
const binaryName = process.platform === 'win32' ? 'pulls-review-server.exe' : 'pulls-review-server'
const sourceRoot = join(packageDir, '../..')
const manifest = join(sourceRoot, 'backend/Cargo.toml')

function isCheckout() {
  // An installed package can share an ancestor with a source tree. Only this
  // checkout's packages/cli layout may select its own target directory.
  if (packageDir !== join(sourceRoot, 'packages/cli') || !existsSync(manifest))
    return false
  try {
    return JSON.parse(readFileSync(join(sourceRoot, 'package.json'), 'utf8')).name === '@pulls.review/monorepo'
  }
  catch {
    return false
  }
}

const checkout = isCheckout()

function executable(path) {
  try {
    accessSync(path, constants.X_OK)
    return statSync(path).isFile()
  }
  catch {
    return false
  }
}

function resolveBinary() {
  const configured = process.env.PULLS_REVIEW_SERVER_BIN
  if (configured !== undefined) {
    if (!configured.trim())
      throw new Error('PULLS_REVIEW_SERVER_BIN is empty. Set it to the native executable or unset it.')
    return configured
  }
  if (checkout) {
    for (const profile of ['debug', 'release']) {
      const candidate = join(sourceRoot, 'backend/target', profile, binaryName)
      if (executable(candidate))
        return candidate
    }
  }
  // Ignore relative/empty PATH entries: the repository being reviewed must not
  // select a server executable just by containing a file with this name.
  for (const directory of (process.env.PATH || '').split(delimiter)) {
    if (!isAbsolute(directory))
      continue
    const candidate = join(directory, binaryName)
    if (executable(candidate))
      return candidate
  }
  throw new Error('Native pulls-review-server executable not found.')
}

function fail(message) {
  console.error(`pulls.review: ${message}`)
  if (checkout)
    console.error(`Build it with: cargo build --locked --manifest-path ${JSON.stringify(manifest)}`)
  console.error('Or install from a source checkout: cargo install --locked --path /path/to/pulls.review/backend')
  console.error('Put Cargo\'s bin directory on PATH, or set PULLS_REVIEW_SERVER_BIN to the executable.')
  console.error('The npm package includes the web UI and curated skills; it does not download or build the native server.')
  process.exitCode = 1
}

function launch(binary) {
  const child = spawn(binary, process.argv.slice(2), {
    cwd: process.cwd(),
    env: {
      ...process.env,
      PULLS_REVIEW_WEB_DIR: process.env.PULLS_REVIEW_WEB_DIR ?? join(packageDir, 'dist/client'),
      PULLS_REVIEW_SKILLS_DIR: process.env.PULLS_REVIEW_SKILLS_DIR ?? join(packageDir, 'dist/skills'),
    },
    shell: false,
    stdio: 'inherit',
  })
  const handlers = new Map()
  for (const signal of ['SIGINT', 'SIGTERM', 'SIGHUP']) {
    const forward = () => child.kill(signal)
    handlers.set(signal, forward)
    process.on(signal, forward)
  }
  function cleanup() {
    for (const [signal, forward] of handlers)
      process.off(signal, forward)
  }
  child.once('error', (error) => {
    cleanup()
    fail(`Unable to start ${JSON.stringify(binary)} (${error.code || 'spawn error'}). Check PULLS_REVIEW_SERVER_BIN and executable permissions.`)
  })
  child.once('exit', (code, signal) => {
    cleanup()
    if (signal) {
      process.exitCode = 128 + (osConstants.signals[signal] || 1)
      process.kill(process.pid, signal)
    }
    else {
      process.exitCode = code ?? 1
    }
  })
}

try {
  launch(resolveBinary())
}
catch (error) {
  fail(error.message)
}
