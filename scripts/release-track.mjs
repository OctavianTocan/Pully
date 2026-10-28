import { spawnSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

export const releasePolicy = JSON.parse(readFileSync(new URL('../.github/release-policy.json', import.meta.url), 'utf8'))

function git(args, cwd) {
  const result = spawnSync('git', args, { cwd, encoding: 'utf8', maxBuffer: 1024 * 1024 })
  if (args[0] === 'symbolic-ref' && result.status === 1)
    return '' // A detached release/tag checkout still needs exact remote and CI proof.
  if (result.status !== 0)
    throw new Error('Release source could not be verified with Git; publication is refused.')
  return result.stdout.trim()
}

/** Latest genuine Actions results only; missing, skipped, stale or ambiguous evidence fails closed. */
export function requireReleaseChecks(checks, head, required = releasePolicy.requiredReleaseChecks) {
  const latest = new Map()
  for (const check of checks) {
    if (!required.includes(check.name) || check.app?.slug !== 'github-actions' || check.app?.id !== 15368 || check.head_sha !== head)
      continue
    if (!Number.isSafeInteger(check.id) || check.id <= 0)
      throw new Error('Release CI evidence has an invalid identity.')
    const previous = latest.get(check.name)
    if (!previous || check.id > previous.id)
      latest.set(check.name, check)
  }
  const missing = required.filter((name) => {
    const check = latest.get(name)
    return check?.status !== 'completed' || check.conclusion !== 'success'
  })
  if (missing.length)
    throw new Error(`Release is not verified: ${missing.join(', ')}. Keep the previous release running.`)
  return latest.get('release-prerequisites')
}

async function githubJson(path) {
  const response = await fetch(`https://api.github.com/repos/${path}`, {
    headers: { 'Accept': 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28' },
    redirect: 'error',
    signal: AbortSignal.timeout(15_000),
  })
  if (!response.ok || Number(response.headers.get('content-length') || 0) > 4 * 1024 * 1024)
    throw new Error('Release CI evidence is unavailable; publication is refused.')
  const text = await response.text()
  if (text.length > 4 * 1024 * 1024)
    throw new Error('Release CI evidence exceeds its bound.')
  return JSON.parse(text)
}

async function checkPage(repository, head, page) {
  const result = await githubJson(`${repository}/commits/${head}/check-runs?filter=all&per_page=100&page=${page}`)
  if (!Array.isArray(result.check_runs) || result.check_runs.length > 100)
    throw new Error('Release CI evidence is malformed.')
  return result.check_runs
}

async function requireReleasePush(check, head, policy, loadRun) {
  const prefix = `https://github.com/${policy.repository}/actions/runs/`
  const runId = check?.details_url?.startsWith(prefix) && /^(\d+)\//.exec(check.details_url.slice(prefix.length))?.[1]
  if (!runId)
    throw new Error('Release comparison evidence has no genuine Actions run.')
  const run = await loadRun(policy.repository, runId)
  if (run.event !== 'push' || run.head_branch !== policy.releaseBranch || run.head_sha !== head || run.status !== 'completed' || run.conclusion !== 'success' || run.path !== '.github/workflows/ci.yml' || run.repository?.full_name !== policy.repository)
    throw new Error('Release comparison must pass on this exact release push; manual checks cannot replace it.')
}

/**
 * @cc [owner:OctavianTocan,label:security;architecture] publication-needs-exact-verified-release
 * A server restart, APK or tag publication MUST use the exact current origin/release
 * commit with every required genuine Actions check successfully completed. Main,
 * stale, dirty, missing-ref, failed, skipped or unavailable evidence MUST fail closed.
 * Source: Tavi's 22 Oct 2026 fast-main and strongly verified release-branch decision.
 */
export async function verifyReleaseCheckout({ cwd = process.cwd(), readGit = git, loadPage = checkPage, loadRun = (repository, id) => githubJson(`${repository}/actions/runs/${id}`), policy = releasePolicy } = {}) {
  if (!/^[\w.-]+\/[\w.-]+$/.test(policy.repository) || policy.releaseBranch !== 'release' || !policy.requiredReleaseChecks?.length)
    throw new Error('Release policy is invalid.')
  const branch = readGit(['symbolic-ref', '--quiet', '--short', 'HEAD'], cwd)
  if (branch && branch !== policy.releaseBranch)
    throw new Error('Refusing publication or deployment from main or a feature branch; use the verified release track.')
  if (readGit(['status', '--porcelain', '--untracked-files=no'], cwd))
    throw new Error('Refusing publication of tracked local edits.')
  const remote = readGit(['remote', 'get-url', 'origin'], cwd)
  if (![`https://github.com/${policy.repository}.git`, `https://github.com/${policy.repository}`, `git@github.com:${policy.repository}.git`].includes(remote))
    throw new Error('Release origin must be the configured GitHub repository without URL credentials.')
  const head = readGit(['rev-parse', 'HEAD'], cwd)
  const ref = `refs/heads/${policy.releaseBranch}`
  const remoteHead = readGit(['ls-remote', '--exit-code', 'origin', ref], cwd)
  if (!/^[0-9a-f]{40}$/.test(head) || remoteHead !== `${head}\t${ref}`)
    throw new Error('The checkout is not the current remote release commit.')
  const checks = []
  for (let page = 1; page <= 5; page++) {
    const rows = await loadPage(policy.repository, head, page)
    if (!Array.isArray(rows) || rows.length > 100)
      throw new Error('Release CI evidence is malformed.')
    checks.push(...rows)
    if (rows.length < 100) {
      const prerequisites = requireReleaseChecks(checks, head, policy.requiredReleaseChecks)
      await requireReleasePush(prerequisites, head, policy, loadRun)
      return head
    }
  }
  throw new Error('Release CI evidence exceeds its page bound.')
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const deploymentOnly = process.argv.slice(2).includes('--deployment-only')
  if (!deploymentOnly || process.env.PULLS_REVIEW_PUBLIC_URL) {
    try {
      const head = await verifyReleaseCheckout()
      console.log(process.argv.includes('--print-sha') ? head : `Verified release source ${head}`)
    }
    catch {
      // Git and HTTP failures can contain routing or credential details. Never echo them.
      console.error('Release source or required CI is unverified. Keep the previous release running; use the verified release track.')
      process.exitCode = 1
    }
  }
}
