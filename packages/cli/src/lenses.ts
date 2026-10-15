import type { ReviewLens } from '@pulls.review/core/local-rpc'
import { existsSync } from 'node:fs'
import { readdir, readFile } from 'node:fs/promises'
import { join } from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'
import { REVIEW_LENS_NAME } from '@pulls.review/core/local-rpc'

const MAX_INSTRUCTIONS = 30_000

/**
 * The skills curated in the repository, the same ones the agents image is built with. Run from a checkout's
 * `src/` or `dist/`, they are read where they live, so an edit shows at once and matches the image. An
 * installed CLI has no checkout around it and reads the copy its build put in `dist/skills/`.
 * @param moduleUrl - Where this module runs from.
 * @returns The checkout's folder when there is one, else the bundled copy.
 */
export function curatedSkillsDir(moduleUrl: string | URL = import.meta.url): string {
  const checkout = fileURLToPath(new URL('../../../docker/agents/skills/', moduleUrl))
  return existsSync(checkout) ? checkout : fileURLToPath(new URL('./skills/', moduleUrl))
}

let curated: string | undefined

export interface LensSourceOptions {
  /** A folder of skill folders, each holding a `SKILL.md`; else `PULLS_REVIEW_SKILLS_DIR`, else the curated set. */
  skillsDir?: string
}

function skillsDirOf(options: LensSourceOptions): string {
  return options.skillsDir ?? (process.env.PULLS_REVIEW_SKILLS_DIR || (curated ??= curatedSkillsDir()))
}

/**
 * Splits a SKILL.md into its frontmatter fields and its body.
 * @param text - The file's content.
 * @returns The top-level frontmatter fields as plain strings, and the trimmed body.
 */
export function parseSkill(text: string): { fields: Record<string, string>, body: string } {
  const match = /^---\r?\n([\s\S]*?)\r?\n---\r?\n?/.exec(text)
  if (!match)
    return { fields: {}, body: text.trim() }
  const fields: Record<string, string> = {}
  const lines = match[1]!.split(/\r?\n/)
  for (let index = 0; index < lines.length; index++) {
    const field = /^([\w-]+):(.*)$/.exec(lines[index]!)
    if (!field)
      continue
    let value = field[2]!.trim()
    if (/^[|>][+-]?$/.test(value) || value === '') {
      const continued: string[] = []
      while (index + 1 < lines.length && /^\s+\S/.test(lines[index + 1]!))
        continued.push(lines[++index]!.trim())
      value = continued.join(' ')
    }
    fields[field[1]!] = value.replace(/^(["'])([\s\S]*)\1$/, '$2')
  }
  return { fields, body: text.slice(match[0].length).trim() }
}

/**
 * The skills a review can be run through: the curated folder only, never the skills of the account the
 * server runs under.
 * @param options - Which folder to read; the curated set by default.
 * @returns The lenses sorted by title; empty when the folder is missing or holds none.
 */
export async function listLenses(options: LensSourceOptions = {}): Promise<ReviewLens[]> {
  const dir = skillsDirOf(options)
  const entries = await readdir(dir).catch(() => [] as string[])
  const lenses = await Promise.all(entries.filter(entry => REVIEW_LENS_NAME.test(entry)).map(async (entry): Promise<ReviewLens | undefined> => {
    const text = await readFile(join(dir, entry, 'SKILL.md'), 'utf8').catch(() => undefined)
    if (text === undefined)
      return undefined
    const { fields } = parseSkill(text)
    return { name: entry, title: fields.title || entry, description: fields.description || undefined }
  }))
  return lenses.filter(lens => lens !== undefined).sort((a, b) => a.title.localeCompare(b.title))
}

/**
 * One skill's instructions, without its frontmatter.
 * @param name - The skill's name, as `listLenses` returned it.
 * @param options - Which folder to read; the curated set by default.
 * @returns The instructions, or `undefined` when no skill has that name.
 */
export async function getLens(name: string, options: LensSourceOptions = {}): Promise<string | undefined> {
  if (!REVIEW_LENS_NAME.test(name))
    return undefined
  const text = await readFile(join(skillsDirOf(options), name, 'SKILL.md'), 'utf8').catch(() => undefined)
  if (text === undefined)
    return undefined
  const body = parseSkill(text).body
  if (!body)
    return undefined
  return body.length > MAX_INSTRUCTIONS ? `${body.slice(0, MAX_INSTRUCTIONS)}\n[truncated]` : body
}
