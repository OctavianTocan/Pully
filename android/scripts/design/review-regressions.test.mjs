import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { describe, it } from 'vitest'
import { composables } from './kotlin.mjs'

const review = new URL('../../app/src/main/kotlin/com/octaviantocan/pulls/ui/review/', import.meta.url)
const read = name => readFileSync(new URL(name, review), 'utf8')

describe('review layout regression guards', () => {
  it('keeps metadata in a single row with bounded one-line repository and author names', () => {
    const source = composables(read('PrMeta.kt'), 'PrMeta.kt').find(item => item.name === 'PrMetaLine').source
    assert.ok(!source.includes('FlowRow('))
    assert.ok(source.includes('Row('))
    assert.equal((source.match(/weight\(1f, fill = false\)/g) ?? []).length, 2)
    assert.equal((source.match(/maxLines = 1, softWrap = false/g) ?? []).length, 4)
    assert.equal((source.match(/overflow = TextOverflow.Ellipsis/g) ?? []).length, 2)
  })

  it('puts both the tour question field and its dimmer inside the saved-tour guard', () => {
    const source = read('PrTourScreen.kt')
    assert.match(source, /if \(tour != null\) SearchDim\(askFocused/)
    assert.match(source, /if \(tour != null\) AskBar\(/)

  })

  it('uses the tested highlight boundary and never caches a missing response row', () => {
    const source = read('DiffBody.kt')
    assert.ok(source.includes('missing.chunked(HIGHLIGHT_BATCH_SIZE)'))
    assert.ok(source.includes('HIGHLIGHT_BATCH_SIZE = 400'))
    assert.ok(source.includes('val line = lines.getOrNull(i) as? JsonArray ?: return@forEachIndexed'))
  })
})
