import type { ReviewLens } from '@pulls.review/core/local-rpc'
import type { LocalRpc } from './connection'
import { LOCAL_RPC } from '@pulls.review/core/local-rpc'
import * as v from 'valibot'

/** Where the skills a critique can be run through come from. */
export interface LensSource {
  /** Every lens on offer, sorted by name. */
  list: () => Promise<ReviewLens[]>
  /** One lens's instructions, or `undefined` when no skill has that name. */
  get: (name: string) => Promise<string | undefined>
}

const ReviewLensesSchema = v.array(v.object({ name: v.string(), title: v.optional(v.nullable(v.string())), description: v.optional(v.nullable(v.string())) }))

let current: LensSource | undefined

/**
 * Lenses read by the `pulls.review` server, from the skills curated in the repository.
 * @param rpc - The local server connection.
 * @returns The lens source.
 */
export function createRpcLenses(rpc: LocalRpc): LensSource {
  return {
    list: async () => v.parse(ReviewLensesSchema, await rpc.call(LOCAL_RPC.lensList))
      .map(lens => ({ name: lens.name, title: lens.title || lens.name, description: lens.description ?? undefined })),
    get: async name => v.parse(v.optional(v.string()), await rpc.call(LOCAL_RPC.lensGet, { name }) ?? undefined),
  }
}

/**
 * Makes `source` the one every diff view offers lenses from.
 * @param source - The lens source, or `undefined` to offer none.
 */
export function setLensSource(source: LensSource | undefined): void {
  current = source
}

/**
 * The lens source set for this build.
 * @returns The source, or `undefined` when this build offers no lenses.
 */
export function lensSource(): LensSource | undefined {
  return current
}
