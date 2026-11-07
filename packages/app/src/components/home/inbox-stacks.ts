import type { MyPull } from '@pulls.review/core/local-rpc'

/** Fold a section's stack where its first member appeared, with its bottom layer first. */
export function inboxStacks(pulls: MyPull[]): {
  pull: MyPull
  layers?: MyPull[]
}[] {
  const key = (pull: MyPull) => pull.stack ? `${pull.owner}/${pull.repo}/${pull.stack.id}` : undefined
  const stacks = new Map<string, MyPull[]>()
  for (const pull of pulls) {
    const id = key(pull)
    if (id)
      stacks.set(id, [...(stacks.get(id) ?? []), pull])
  }
  const placed = new Set<string>()
  return pulls.flatMap((pull) => {
    const id = key(pull)
    const layers = id ? stacks.get(id) : undefined
    if (!id || !layers || layers.length < 2)
      return [{ pull }]
    if (placed.has(id))
      return []
    placed.add(id)
    layers.sort((a, b) => a.stack!.position - b.stack!.position || a.number - b.number)
    const bottom = layers[0]!
    const worst = layers.find(layer => layer.checks === 'failure' || layer.mergeable === 'conflicting') ?? layers.find(layer => layer.checks === 'pending') ?? layers.find(layer => layer.checks === 'success') ?? bottom
    const counted = layers.filter(layer => layer.additions !== undefined)
    return [{ layers, pull: { ...bottom, author: '', requestedFromMe: layers.some(layer => layer.requestedFromMe ?? layer.role === 'review-requested'), checks: worst.checks, mergeable: worst.mergeable, additions: counted.length ? counted.reduce((n, layer) => n + (layer.additions ?? 0), 0) : undefined, deletions: counted.length ? counted.reduce((n, layer) => n + (layer.deletions ?? 0), 0) : undefined, updatedAt: layers.reduce((latest, layer) => Date.parse(layer.updatedAt) > Date.parse(latest) ? layer.updatedAt : latest, bottom.updatedAt) } }]
  })
}
