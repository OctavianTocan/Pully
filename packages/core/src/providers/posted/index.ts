import type { DiffsPayload } from '../../types/diff'
import type { DiffSource } from '../../types/source'
import { parsePatch } from '../../patch-parser'
import { serializeRef } from '../../types/source'

/** What an agent posted, as the server stores it: the patch text and the words it sent with it. */
export interface PostedDiffInput {
  id: string
  patch: string
  /** Bumped by every `PUT`, so a page open on an older one can say a newer one was posted. */
  version: number
  title: string
  description?: string
  /** Who posted it, as the agent named itself or by its key's name. */
  author?: string
  createdAt: string
  updatedAt: string
}

/** The payload of a posted diff: no base, head, commits or pull request, so clients draw none of them. */
export async function postedPayload(input: PostedDiffInput): Promise<DiffsPayload> {
  return {
    ref: { kind: 'posted', id: input.id },
    title: input.title,
    description: input.description,
    author: input.author ? { name: input.author } : undefined,
    createdAt: input.createdAt,
    updatedAt: input.updatedAt,
    files: await parsePatch(input.patch),
  }
}

/** A diff an agent posted (plan 13): parsed again on every fetch, never stored parsed, and versioned by its `PUT`s. */
export function createPostedSource(input: PostedDiffInput): DiffSource {
  return {
    key: async () => serializeRef({ kind: 'posted', id: input.id }),
    fetch: () => postedPayload(input),
    fingerprint: async () => `v${input.version}`,
  }
}
