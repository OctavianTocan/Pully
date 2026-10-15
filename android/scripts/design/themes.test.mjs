// The colour themes converted from OpenCode's (K18): the Kotlin on disk is what the generator writes from the
// vendored theme files, and every theme fills every Pulls role with a colour in both looks.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, it } from 'vitest'
import { contrast, kotlin, NAMES, ROLES, themes } from '../gen-opencode-themes.mjs'
import { ANDROID } from './kotlin.mjs'

const GENERATED = join(ANDROID, 'app/src/main/kotlin/com/octaviantocan/pulls/ui/design/OpenCodeThemes.kt')

/** Looks whose text is under 4.5:1 on their screen as OpenCode draws them; kept as they are rather than repainted. */
const LOW_CONTRAST = new Set(['solarized/light'])

describe('opencode themes', () => {
  const list = themes()

  it('are what the generator writes (run node android/scripts/gen-opencode-themes.mjs)', () => {
    assert.equal(readFileSync(GENERATED, 'utf8'), kotlin(list))
  })

  it('have a name each and come OpenCode first', () => {
    assert.equal(list[0].id, 'opencode')
    assert.deepEqual(list.map(t => t.id).sort(), Object.keys(NAMES).sort())
  })

  it('fill every role with a colour in both looks', () => {
    for (const theme of list) {
      for (const [look, palette] of [['light', theme.light], ['dark', theme.dark]]) {
        for (const role of ROLES)
          assert.ok(palette[role] && Number.isFinite(palette[role].r), `${theme.id} ${look} has no ${role}`)
        assert.equal(typeof palette.dark, 'boolean')
      }
    }
  })

  it('set text on the screen at 4.5:1 or more, but for the listed exceptions', () => {
    const low = list.flatMap(theme => [['light', theme.light], ['dark', theme.dark]]
      .filter(([, p]) => contrast(p.text, p.base) < 4.5)
      .map(([look]) => `${theme.id}/${look}`))
    assert.deepEqual(low, [...LOW_CONTRAST])
  })
})
