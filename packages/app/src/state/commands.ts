import { computed, getCurrentScope, onScopeDispose, shallowReactive } from 'vue'

/** Something the user can run from the command palette or a keyboard shortcut. */
export interface Command {
  /** Stable identifier; `KEYMAP` binds keys to it. Later registrations of an id win. */
  id: string
  title: string
  /** Palette section heading, already translated. */
  group?: string
  /** Extra words the palette search matches on, beyond the title and group. */
  keywords?: string
  /** A binding like `mod+shift+m` or `g p`, for commands without a `KEYMAP` entry. */
  shortcut?: string
  /** Icon class, e.g. `i-lu-cog`. */
  icon?: string
  /** Kept out of the palette; still reachable through its shortcut. */
  hidden?: boolean
  /** Whether the command can run right now; it's neither listed nor bound while false. */
  when?: () => boolean
  run: () => unknown
}

/** A command, or a getter whose reactive result (one command or a list) stays registered. */
export type CommandSource = Command | (() => Command | readonly Command[])

const sources = shallowReactive(new Set<() => Command | readonly Command[]>())

/** Every registered command in registration order, including ones whose `when` is false. */
export const commands = computed(() => [...sources].flatMap(source => source()))

/**
 * Adds commands to the palette and makes their shortcuts live.
 *
 * @param source - A command, or a getter re-read whenever its reactive dependencies change.
 * @returns A function that removes exactly what this call added.
 */
export function registerCommand(source: CommandSource): () => void {
  const getter = typeof source === 'function' ? source : () => source
  sources.add(getter)
  return () => {
    sources.delete(getter)
  }
}

/**
 * {@link registerCommand} for the lifetime of the current component or effect scope.
 *
 * @param source - A command, or a getter re-read whenever its reactive dependencies change.
 */
export function useCommand(source: CommandSource): void {
  const unregister = registerCommand(source)
  if (getCurrentScope())
    onScopeDispose(unregister)
}

/**
 * Whether a command can run now.
 *
 * @param command - The command to check.
 * @returns `false` when its `when` says so.
 */
export function isAvailable(command: Command): boolean {
  return command.when?.() ?? true
}

/**
 * The command that runs for `id` right now: the latest registered one that's available.
 *
 * @param id - The command id.
 * @returns The command, or `undefined` when none is available.
 */
export function findCommand(id: string): Command | undefined {
  const list = commands.value
  for (let i = list.length - 1; i >= 0; i--) {
    const command = list[i]!
    if (command.id === id && isAvailable(command))
      return command
  }
}

/**
 * Runs the available command registered for `id`.
 *
 * @param id - The command id.
 * @returns Whether a command ran.
 */
export function runCommand(id: string): boolean {
  const command = findCommand(id)
  if (!command)
    return false
  void command.run()
  return true
}
