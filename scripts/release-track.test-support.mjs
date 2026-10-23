import { mkdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import process from 'node:process'

/** Actual production admission and real local Git; only GitHub's CI response/origin identity are fixture data. */
export function releaseFixtureGate(home) {
  const bin = join(home, 'bin')
  mkdirSync(bin, { recursive: true })
  const runner = join(home, 'release-gate-fixture.mjs')
  const module = new URL('./release-track.mjs', import.meta.url).href
  writeFileSync(runner, `
import { spawnSync } from 'node:child_process'
import { releasePolicy, verifyReleaseCheckout } from ${JSON.stringify(module)}
if (process.argv.includes('--deployment-only') && !process.env.PULLS_REVIEW_PUBLIC_URL) process.exit(0)
function readGit(args, cwd) {
  if (args.join(' ') === 'remote get-url origin') return 'https://github.com/' + releasePolicy.repository + '.git'
  const result = spawnSync('git', args, { cwd, encoding: 'utf8' })
  if (args[0] === 'symbolic-ref' && result.status === 1) return ''
  if (result.status !== 0) throw new Error('Fixture Git verification failed')
  return result.stdout.trim()
}
const head = readGit(['rev-parse', 'HEAD'], process.cwd())
try {
  const verified = await verifyReleaseCheckout({
    readGit,
    loadPage: async () => releasePolicy.requiredReleaseChecks.map((name, index) => ({ id: index + 1, name, head_sha: head, app: { id: 15368, slug: 'github-actions' }, status: 'completed', conclusion: process.env.RELEASE_FIXTURE_CI || 'success', details_url: 'https://github.com/' + releasePolicy.repository + '/actions/runs/12/job/' + (index + 1) })),
    loadRun: async () => ({ event: 'push', head_branch: 'release', head_sha: head, status: 'completed', conclusion: 'success', path: '.github/workflows/ci.yml', repository: { full_name: releasePolicy.repository } }),
  })
  console.log(process.argv.includes('--print-sha') ? verified : 'Verified release source ' + verified)
} catch {
  console.error('Release source is unverified; publication refused.')
  process.exitCode = 1
}
`)
  const quote = value => `'${value.replaceAll('\'', '\'\\\'\'')}'`
  writeFileSync(join(bin, 'node'), `#!/bin/sh
unset NODE_TEST_CONTEXT
case "$1" in
  *release-track.mjs) exec ${quote(process.execPath)} ${quote(runner)} "$@";;
  *) exec ${quote(process.execPath)} "$@";;
esac
`, { mode: 0o755 })
  return bin
}
