import type { HostFile, ServerHost } from './host'
import { createReadStream } from 'node:fs'
import { stat } from 'node:fs/promises'
import { homedir } from 'node:os'
import { join } from 'node:path'
import process from 'node:process'
import { Readable } from 'node:stream'
import { createRepoCacheDriver } from './storage'

/** Where `android/scripts/publish-apk.sh` puts the updater's files; read on every request, so a change applies at once. */
export function androidDir(): string {
  return process.env.PULLS_REVIEW_ANDROID_DIR || join(homedir(), '.local/share/pulls-review/android')
}

async function androidFile(name: string): Promise<HostFile | undefined> {
  const path = join(androidDir(), name)
  const info = await stat(path).catch(() => undefined)
  if (!info?.isFile())
    return undefined
  return { body: Readable.toWeb(createReadStream(path)) as ReadableStream<Uint8Array>, size: info.size }
}

/** A host over files the server can read, with the store in the checkout's git folder. */
export async function checkoutHost(cwd: string): Promise<ServerHost> {
  return { storage: await createRepoCacheDriver(cwd), cwd, androidFile }
}

let host: Promise<ServerHost> | undefined

/**
 * The CLI's one host per process: the browser socket, the phone API and the PR store share it,
 * and so share one session (see `serverSession`).
 * @param cwd - The repository the server was started in.
 */
export function nodeHost(cwd: string): Promise<ServerHost> {
  return host ??= checkoutHost(cwd)
}
