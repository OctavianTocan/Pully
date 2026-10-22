import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
// Portable publication checks also run before any compiler or SDK setup.
// eslint-disable-next-line test/no-import-node-test
import { describe, it } from 'node:test'
import { fileURLToPath } from 'node:url'
import { releasePolicy, requireReleaseChecks, verifyReleaseCheckout } from './release-track.mjs'

const head = 'a'.repeat(40)
const prefix = `https://github.com/${releasePolicy.repository}/actions/runs/12/job/`
function checks() {
  return releasePolicy.requiredReleaseChecks.map((name, index) => ({
    id: index + 1,
    name,
    head_sha: head,
    app: { id: 15368, slug: 'github-actions' },
    status: 'completed',
    conclusion: 'success',
    details_url: prefix + (index + 1),
  }))
}
const goodRun = () => ({ event: 'push', head_branch: 'release', head_sha: head, status: 'completed', conclusion: 'success', path: '.github/workflows/ci.yml', repository: { full_name: releasePolicy.repository } })

function fixture({ branch = 'release', dirty = '', remoteHead = head, origin = `https://github.com/${releasePolicy.repository}.git`, rows = checks(), run = goodRun() } = {}) {
  const queries = []
  return {
    queries,
    options: {
      readGit(args) {
        queries.push(args)
        switch (args[0]) {
          case 'symbolic-ref': return branch
          case 'status': return dirty
          case 'remote': return origin
          case 'rev-parse': return head
          case 'ls-remote': return `${remoteHead}\trefs/heads/release`
          default: throw new Error('Unexpected Git request')
        }
      },
      loadPage: async () => rows,
      loadRun: async () => run,
    },
  }
}

describe('exact release admission', () => {
  it('admits a clean exact release or detached tag with all real checks and its release-push comparison', async () => {
    for (const branch of ['release', '']) {
      const f = fixture({ branch })
      assert.equal(await verifyReleaseCheckout(f.options), head)
      assert.ok(f.queries.some(args => args.join(' ') === 'ls-remote --exit-code origin refs/heads/release'))
    }
  })

  it('refuses main and feature deployments before any network read', async () => {
    for (const branch of ['main', 'feature/private-change']) {
      const f = fixture({ branch })
      await assert.rejects(verifyReleaseCheckout(f.options), /verified release track/)
      assert.equal(f.queries.length, 1)
    }
  })

  it('refuses dirty, stale, missing and credential-bearing origins', async () => {
    for (const change of [{ dirty: ' M backend/src/lib.rs' }, { remoteHead: 'b'.repeat(40) }, { origin: 'https://token@github.com/OctavianTocan/pulls.review.git' }, { origin: 'https://example.com/repo.git' }])
      await assert.rejects(verifyReleaseCheckout(fixture(change).options))
    const f = fixture()
    f.options.readGit = () => {
      throw new Error('Missing release ref')
    }
    await assert.rejects(verifyReleaseCheckout(f.options))
  })

  it('refuses each missing, incomplete, skipped, stale or forged required check', () => {
    for (const name of releasePolicy.requiredReleaseChecks) {
      assert.throws(() => requireReleaseChecks(checks().filter(c => c.name !== name), head))
      for (const change of [{ conclusion: 'failure' }, { conclusion: 'skipped' }, { conclusion: 'cancelled' }, { status: 'in_progress' }, { head_sha: 'b'.repeat(40) }, { app: { id: 42, slug: 'github-actions' } }, { app: { id: 15368, slug: 'another-app' } }]) {
        const rows = checks().map(c => c.name === name ? { ...c, ...change } : c)
        assert.throws(() => requireReleaseChecks(rows, head))
      }
    }
  })

  it('cannot fall back to an older success after a newer required failure', () => {
    const rows = checks()
    rows.push({ ...rows[0], id: 1000, conclusion: 'failure' })
    assert.throws(() => requireReleaseChecks(rows, head))
  })

  it('does not let manual or PR metadata checks replace the actual release push comparison', async () => {
    for (const change of [{ event: 'workflow_dispatch' }, { event: 'pull_request' }, { head_branch: 'main' }, { head_sha: 'b'.repeat(40) }, { conclusion: 'failure' }, { status: 'in_progress' }, { path: '.github/workflows/other.yml' }, { repository: { full_name: 'another/repo' } }])
      await assert.rejects(verifyReleaseCheckout(fixture({ run: { ...goodRun(), ...change } }).options), /exact release push/)
  })

  it('fails closed on unavailable or unbounded API evidence', async () => {
    const f = fixture()
    f.options.loadPage = async () => {
      throw new Error('429')
    }
    await assert.rejects(verifyReleaseCheckout(f.options))
    let pages = 0
    f.options.loadPage = async () => {
      pages++
      return Array.from({ length: 100 }, () => ({ name: 'unrelated' }))
    }
    await assert.rejects(verifyReleaseCheckout(f.options), /page bound/)
    assert.equal(pages, 5)
  })

  it('keeps the legacy installed-updater hook strict while ordinary integration checks stay local', () => {
    const script = fileURLToPath(new URL('./release-track.mjs', import.meta.url))
    const env = { ...process.env }
    delete env.PULLS_REVIEW_PUBLIC_URL
    delete env.NODE_TEST_CONTEXT
    const cwd = mkdtempSync(join(tmpdir(), 'pully-integration-gate-'))
    try {
      assert.equal(spawnSync('git', ['init', '-b', 'main'], { cwd }).status, 0)
      const local = spawnSync(process.execPath, [script, '--deployment-only'], { cwd, env, encoding: 'utf8' })
      assert.equal(local.status, 0, local.stderr)
      const deployment = spawnSync(process.execPath, [script, '--deployment-only'], { cwd, env: { ...env, PULLS_REVIEW_PUBLIC_URL: 'https://example.com' }, encoding: 'utf8' })
      assert.equal(deployment.status, 1)
      assert.doesNotMatch(deployment.stderr, /token@|Authorization|CF-Access/)
    }
    finally { rmSync(cwd, { recursive: true, force: true }) }
  })
})
