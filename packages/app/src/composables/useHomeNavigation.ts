import type { Command } from '../state/commands'
import { onScopeDispose } from 'vue'
import { useRouter } from 'vue-router'
import { t } from '../i18n'
import { repoRoute, routeForRef } from '../source-routes'
import { useCommand } from '../state/commands'
import { COMMAND } from '../state/keymap'
import { activeRow } from '../state/navigation'

/** A pull request as a list page shows it. */
export interface NavPull {
  owner: string
  repo: string
  number: number | string
  title: string
}

/** Binds a pull request list to the keyboard, see {@link useHomeNavigation}. */
export interface HomeNavigation {
  /**
   * The value for the row's `data-nav-row` attribute, which is how the row is found to scroll to.
   *
   * @param pull - The row's pull request.
   * @returns Its key.
   */
  rowKey: (pull: NavPull) => string
  /**
   * Whether keyboard navigation is on this row.
   *
   * @param pull - The row's pull request.
   * @returns `true` for the selected row.
   */
  isActive: (pull: NavPull) => boolean
}

function key(pull: NavPull) {
  return `${pull.owner}/${pull.repo}#${pull.number}`
}

function routeOf(pull: NavPull) {
  return routeForRef({ kind: 'github-pr', owner: pull.owner, repo: pull.repo, number: String(pull.number) })
}

/**
 * Registers a pull request list's keyboard commands (move, open, refresh, filter) and a
 * palette entry for every pull request and repository, for the lifetime of the calling
 * component. Rows carry `data-nav-row` and the filter field `data-nav-search`.
 *
 * @param options - The list and how to reload it.
 * @param options.rows - The rows as shown, top to bottom.
 * @param options.pulls - Every loaded pull request, offered in the palette.
 * @param options.refresh - Reloads the list.
 * @returns Helpers for marking up the rows.
 */
export function useHomeNavigation(options: {
  rows: () => readonly NavPull[]
  pulls: () => readonly NavPull[] | undefined
  refresh?: () => unknown
}): HomeNavigation {
  const router = useRouter()

  function rowElement(rowKey: string) {
    return document.querySelector<HTMLElement>(`[data-nav-row="${CSS.escape(rowKey)}"]`) ?? undefined
  }

  /** Rows actually on screen, skipping those inside a collapsed section. */
  function visibleRows() {
    return options.rows().filter(pull => !!rowElement(key(pull))?.getClientRects().length)
  }

  function step(direction: 1 | -1) {
    const rows = visibleRows()
    if (!rows.length)
      return
    const index = rows.findIndex(pull => key(pull) === activeRow.value)
    const next = index < 0
      ? (direction > 0 ? 0 : rows.length - 1)
      : Math.min(Math.max(index + direction, 0), rows.length - 1)
    activeRow.value = key(rows[next]!)
    rowElement(activeRow.value)?.scrollIntoView({ block: 'nearest' })
  }

  function selected() {
    return visibleRows().find(pull => key(pull) === activeRow.value)
  }

  onScopeDispose(() => {
    activeRow.value = undefined
  })

  useCommand(() => {
    const group = t('palette.group.pulls')
    const list: Command[] = [
      { id: COMMAND.nextRow, title: t('keys.nextRow'), group, icon: 'i-ph:arrow-down', run: () => step(1) },
      { id: COMMAND.prevRow, title: t('keys.prevRow'), group, icon: 'i-ph:arrow-up', run: () => step(-1) },
      {
        id: COMMAND.openRow,
        title: t('keys.openRow'),
        group,
        icon: 'i-ph:arrow-right',
        when: () => !!selected(),
        run: () => router.push(routeOf(selected()!)),
      },
      {
        id: COMMAND.search,
        title: t('keys.search'),
        group,
        icon: 'i-lu-search',
        run: () => document.querySelector<HTMLElement>('[data-nav-search] input, input[data-nav-search]')?.focus(),
      },
    ]
    if (options.refresh) {
      const refresh = options.refresh
      list.push({ id: COMMAND.refresh, title: t('keys.refresh'), group, icon: 'i-lu-refresh', run: refresh })
    }
    return list
  })

  useCommand(() => {
    const pulls = options.pulls() ?? []
    const pullsGroup = t('palette.group.pulls')
    const reposGroup = t('palette.group.repos')
    const repos = new Map<string, NavPull>()
    const entries: Command[] = pulls.map((pull) => {
      repos.set(`${pull.owner}/${pull.repo}`, pull)
      return {
        id: `jump.pull:${key(pull)}`,
        title: t('palette.goToPull', { ref: key(pull), title: pull.title }),
        group: pullsGroup,
        icon: 'i-ph:git-pull-request',
        run: () => router.push(routeOf(pull)),
      }
    })
    for (const [name, pull] of repos) {
      entries.push({
        id: `jump.repo:${name}`,
        title: t('palette.goToRepo', { repo: name }),
        group: reposGroup,
        icon: 'i-lu-branch',
        run: () => router.push(repoRoute(pull.owner, pull.repo)),
      })
    }
    return entries
  })

  return {
    rowKey: key,
    isActive: pull => activeRow.value === key(pull),
  }
}
