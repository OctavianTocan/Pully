import type { Storage } from 'unstorage'

const storageQueues = new WeakMap<Storage, Map<string, Promise<unknown>>>()

/** Serializes a complete read/modify/write across repositories sharing one storage. */
export function storageTransaction<T>(storage: Storage, key: string, task: () => Promise<T>): Promise<T> {
  let queues = storageQueues.get(storage)
  if (!queues) {
    queues = new Map()
    storageQueues.set(storage, queues)
  }
  const run = (queues.get(key) ?? Promise.resolve()).then(task, task)
  const settled = run.catch(() => {})
  queues.set(key, settled)
  void settled.then(() => {
    if (queues.get(key) === settled)
      queues.delete(key)
  })
  return run
}
