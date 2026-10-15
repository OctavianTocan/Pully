import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'
import { REVIEW_LENS_NAME } from '@pulls.review/core/local-rpc'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { curatedSkillsDir, getLens, listLenses, parseSkill } from './lenses'

let skillsDir: string

function skill(name: string, text: string) {
  mkdirSync(join(skillsDir, name), { recursive: true })
  writeFileSync(join(skillsDir, name, 'SKILL.md'), text)
}

beforeEach(() => {
  skillsDir = mkdtempSync(join(tmpdir(), 'pulls-review-skills-'))
})

afterEach(() => {
  vi.unstubAllEnvs()
  rmSync(skillsDir, { recursive: true, force: true })
})

describe('parseSkill', () => {
  it('reads plain, quoted and folded fields and strips the frontmatter', () => {
    const { fields, body } = parseSkill('---\nname: sec\ndescription: >\n  Looks for\n  injection.\ntitle: "Security"\n---\n\n# Body\n')
    expect(fields).toEqual({ name: 'sec', description: 'Looks for injection.', title: 'Security' })
    expect(body).toBe('# Body')
    expect(parseSkill('No frontmatter.').body).toBe('No frontmatter.')
  })
})

describe('lenses from a skills folder', () => {
  beforeEach(() => {
    skill('security', '---\nname: security\ntitle: Security\ndescription: Finds holes.\n---\nCheck auth.')
    skill('perf', '---\nname: perf\ntitle: Performance\n---\nCount the loops.')
    skill('empty', 'no frontmatter')
    mkdirSync(join(skillsDir, 'not-a-skill'))
  })

  it('lists skill folders that hold a SKILL.md, by title, falling back to the name', async () => {
    expect(await listLenses({ skillsDir })).toEqual([
      { name: 'empty', title: 'empty', description: undefined },
      { name: 'perf', title: 'Performance', description: undefined },
      { name: 'security', title: 'Security', description: 'Finds holes.' },
    ])
  })

  it('reads one skill by name and refuses names outside the folder', async () => {
    expect(await getLens('security', { skillsDir })).toBe('Check auth.')
    expect(await getLens('missing', { skillsDir })).toBeUndefined()
    expect(await getLens('../security', { skillsDir })).toBeUndefined()
  })

  it('reads PULLS_REVIEW_SKILLS_DIR when no folder is passed', async () => {
    vi.stubEnv('PULLS_REVIEW_SKILLS_DIR', skillsDir)
    expect((await listLenses()).map(lens => lens.name)).toEqual(['empty', 'perf', 'security'])
    expect(await getLens('perf')).toBe('Count the loops.')
  })

  it('offers nothing when the folder is missing', async () => {
    expect(await listLenses({ skillsDir: join(skillsDir, 'nowhere') })).toEqual([])
  })
})

describe('the curated skills', () => {
  beforeEach(() => {
    vi.stubEnv('PULLS_REVIEW_SKILLS_DIR', '')
  })

  it('are read from the checkout, and from the copy beside the bundle in an installed CLI', () => {
    // `skillsDir` stands in for a checkout, then for the folder an installed package sits in.
    const bundle = pathToFileURL(join(skillsDir, 'packages/cli/dist/index.mjs'))
    skill('packages/cli/dist/skills/security', 'Check auth.')
    skill('docker/agents/skills/security', 'Check auth.')
    expect(curatedSkillsDir(bundle)).toBe(join(skillsDir, 'docker/agents/skills/'))
    expect(curatedSkillsDir(pathToFileURL(join(skillsDir, 'packages/cli/src/lenses.ts')))).toBe(join(skillsDir, 'docker/agents/skills/'))

    rmSync(join(skillsDir, 'docker'), { recursive: true })
    expect(curatedSkillsDir(bundle)).toBe(join(skillsDir, 'packages/cli/dist/skills/'))
  })

  it('are the default, each with a title, a one-sentence description and instructions', async () => {
    const lenses = await listLenses()
    expect(lenses.map(lens => lens.name).sort()).toEqual(['android-compose', 'api-and-contracts', 'correctness', 'performance', 'security', 'tests'])
    for (const lens of lenses) {
      expect(lens.name).toMatch(REVIEW_LENS_NAME)
      expect(lens.title).not.toBe(lens.name)
      expect(lens.description).toMatch(/^[A-Z][^\n]*\.$/)
      expect(await getLens(lens.name)).toMatch(/^# /)
    }
  })
})
