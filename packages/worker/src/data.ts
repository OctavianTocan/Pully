/** Where the server's data folder is kept in the bucket. */
export const DATA_PREFIX = 'data/'

/** The hostname the container's data mirror calls; only the container can reach it. */
export const DATA_HOST = 'data.pully'

const PAGE = 1000

/** The R2 methods the data mirror uses, so tests can stand in for the bucket. */
export interface DataBucket {
  get: (key: string) => Promise<{ body: ReadableStream } | null>
  put: (key: string, value: ArrayBuffer) => Promise<unknown>
  delete: (key: string) => Promise<void>
  list: (options: { prefix: string, cursor?: string, limit: number }) => Promise<{ objects: { key: string, size: number }[], truncated: boolean, cursor?: string }>
}

/**
 * Answers the container's data mirror (`backend/src/data_mirror.rs`) from the bucket:
 * `GET /list?cursor=` pages through stored paths, and `GET`, `PUT` and `DELETE` on
 * `/file/<path>` read, replace and remove one file under {@link DATA_PREFIX}.
 *
 * @param request - The container's request to {@link DATA_HOST}.
 * @param bucket - The bucket that holds the data folder.
 * @returns The answer the mirror expects, or 400, 404 or 405.
 */
export async function answerDataMirror(request: Request, bucket: DataBucket): Promise<Response> {
  const url = new URL(request.url)
  if (url.pathname === '/list') {
    if (request.method !== 'GET')
      return new Response(null, { status: 405 })
    const listed = await bucket.list({ prefix: DATA_PREFIX, cursor: url.searchParams.get('cursor') || undefined, limit: PAGE })
    return Response.json({
      files: listed.objects.map(object => ({ path: object.key.slice(DATA_PREFIX.length), size: object.size })),
      cursor: listed.truncated ? listed.cursor : null,
    })
  }
  if (!url.pathname.startsWith('/file/'))
    return new Response(null, { status: 404 })
  const path = dataPath(url.pathname.slice('/file/'.length))
  if (!path)
    return new Response(null, { status: 400 })
  const key = DATA_PREFIX + path
  switch (request.method) {
    case 'GET': {
      const object = await bucket.get(key)
      return object ? new Response(object.body) : new Response(null, { status: 404 })
    }
    case 'PUT':
      await bucket.put(key, await request.arrayBuffer())
      return new Response(null, { status: 204 })
    case 'DELETE':
      await bucket.delete(key)
      return new Response(null, { status: 204 })
    default:
      return new Response(null, { status: 405 })
  }
}

/** The stored path for an encoded request path, or nothing when a segment is empty, `.`, `..` or holds a slash. */
export function dataPath(encoded: string): string | undefined {
  try {
    const segments = encoded.split('/').map(decodeURIComponent)
    if (segments.some(segment => !segment || segment === '.' || segment === '..' || /[/\\]/.test(segment)))
      return undefined
    return segments.join('/')
  }
  catch {
    return undefined
  }
}
