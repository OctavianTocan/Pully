import type { FileChange } from '@pulls.review/core/types'
import type { Ref } from 'vue'
import type { ResolvedGroupWithChildren } from '../components/diff/group-utils'
import type { Command } from '../state/commands'
import type { DiffsStore } from '../stores/types'
import { nextTick, shallowRef, watch } from 'vue'
import { t } from '../i18n'
import { useCommand } from '../state/commands'
import { groupNav } from '../state/group-nav'
import { COMMAND } from '../state/keymap'
import { activeFile, fileViews } from '../state/navigation'
import { notify, openPalette } from '../state/palette'
import { settingsModalOpen } from '../state/settingsModal'

/** Where the diff view lives, for {@link usePrCommands}. */
export interface PrCommandsContext {
  store: () => DiffsStore | undefined
  /** Where the diff's elements are looked up: the document, or the embed's shadow root. */
  root: () => Document | ShadowRoot
  /** The element that scrolls; `undefined` when the window does. */
  scroller: () => HTMLElement | undefined
  /** Height of the sticky header covering the top of the scroller. */
  headerHeight: Ref<number>
  /** Keys of the collapsed top-level groups; navigation expands them as needed. */
  collapsedGroups: Ref<Set<string>>
}

const EDGE = 2
const FILE_HEADER_GAP = 8

async function sha256(text: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text))
  return [...new Uint8Array(digest)].map(byte => byte.toString(16).padStart(2, '0')).join('')
}

function encodePath(path: string): string {
  return path.split('/').map(encodeURIComponent).join('/')
}

function openExternal(url: string) {
  window.open(url, '_blank', 'noopener,noreferrer')
}

async function copy(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    notify(t('keys.copied', { text }))
  }
  catch {
    notify(t('keys.copyFailed'), 'error')
  }
}

/**
 * Registers the diff view's keyboard commands (file, change and group navigation, file
 * actions, view toggles) and its palette entries for every file and group, for the
 * lifetime of the calling component.
 *
 * @param context - The diff view's store and layout.
 */
export function usePrCommands(context: PrCommandsContext): void {
  const { store, root, scroller, headerHeight, collapsedGroups } = context

  // `o` opens a new tab, which must happen synchronously in the key handler; the
  // anchor GitHub uses is a SHA-256 of the path, so hash every path up front.
  const anchors = shallowRef(new Map<string, string>())
  watch(() => store()?.diff?.files, async (files) => {
    if (!files || !globalThis.crypto?.subtle)
      return
    const entries = await Promise.all(files.map(async file => [file.path, await sha256(file.path)] as const))
    anchors.value = new Map(entries)
  }, { immediate: true })

  function topLine() {
    return (scroller()?.getBoundingClientRect().top ?? 0) + headerHeight.value
  }

  function bottomLine() {
    return scroller()?.getBoundingClientRect().bottom ?? window.innerHeight
  }

  function scrollBy(delta: number) {
    (scroller() ?? window).scrollBy({ top: delta, behavior: 'instant' })
  }

  function scrollTo(top: number) {
    (scroller() ?? window).scrollTo({ top, behavior: 'instant' })
  }

  /** Scrolls so `top()` sits `offset` px below the sticky header, re-aligning while the virtualized diff re-measures. */
  function align(top: () => number | undefined, offset = 0) {
    let frames = 0
    const step = () => {
      const y = top()
      if (y === undefined)
        return
      const delta = y - (topLine() + offset)
      if (Math.abs(delta) > 1)
        scrollBy(delta)
      if (++frames < 3)
        requestAnimationFrame(step)
    }
    step()
  }

  function markers() {
    return [...root().querySelectorAll<HTMLElement>('[data-file-start]')]
  }

  function markerFor(sha: string) {
    return root().querySelector<HTMLElement>(`[data-file-start="${CSS.escape(sha)}"]`) ?? undefined
  }

  /** Viewport extent of a file: from its marker to the next file in the same column, or the column's end. */
  function span(list: HTMLElement[], index: number) {
    const marker = list[index]!
    const next = list[index + 1]
    const top = marker.getBoundingClientRect().top
    const bottom = next && next.parentElement === marker.parentElement
      ? next.getBoundingClientRect().top
      : marker.parentElement!.getBoundingClientRect().bottom
    return { top, bottom }
  }

  function selectedIndex(list: HTMLElement[]) {
    const index = list.findIndex(marker => marker.dataset.fileStart === activeFile.value)
    if (index < 0)
      return -1
    const { top, bottom } = span(list, index)
    return bottom > topLine() && top < bottomLine() ? index : -1
  }

  /** The selected file while it's on screen, else the one at the top of the viewport. */
  function currentIndex(list: HTMLElement[]) {
    const selected = selectedIndex(list)
    if (selected >= 0)
      return selected
    const line = topLine() + EDGE
    return list.findIndex((_, i) => span(list, i).bottom > line)
  }

  function currentFile(): FileChange | undefined {
    const list = markers()
    const sha = list[currentIndex(list)]?.dataset.fileStart
    if (!sha)
      return
    activeFile.value = sha
    return store()?.diff?.files.find(file => file.sha === sha)
  }

  function goToFile(sha: string) {
    activeFile.value = sha
    align(() => markerFor(sha)?.getBoundingClientRect().top)
  }

  function stepFile(direction: 1 | -1) {
    const list = markers()
    if (!list.length)
      return
    const selected = selectedIndex(list)
    let target: number
    if (selected >= 0)
      target = selected + direction
    else if (direction > 0)
      target = list.findIndex(marker => marker.getBoundingClientRect().top > topLine() + EDGE)
    else
      target = list.findLastIndex(marker => marker.getBoundingClientRect().top < topLine() - EDGE)
    const sha = list[target]?.dataset.fileStart
    if (sha)
      goToFile(sha)
  }

  function stepHunk(direction: 1 | -1) {
    const list = markers()
    let index = currentIndex(list)
    if (index < 0)
      return
    const headerOffset = (root().getElementById(`file-${list[index]!.dataset.fileStart}`)?.offsetHeight ?? 0) + FILE_HEADER_GAP
    const line = topLine() + headerOffset
    for (; index >= 0 && index < list.length; index += direction) {
      const sha = list[index]!.dataset.fileStart!
      const view = fileViews.get(sha)
      const tops = view?.hunkTops() ?? []
      const hunk = direction > 0
        ? tops.findIndex(top => top > line + EDGE)
        : tops.findLastIndex(top => top < line - EDGE)
      if (hunk >= 0) {
        activeFile.value = sha
        align(() => fileViews.get(sha)?.hunkTops()[hunk], headerOffset)
        return
      }
    }
  }

  function groupKeys(groups: ResolvedGroupWithChildren[]) {
    return groups.flatMap(group => [group.key, ...group.children.map(child => child.key)])
  }

  function stepGroup(direction: 1 | -1) {
    const elements = groupKeys(store()?.groups ?? [])
      .map(key => root().getElementById(`group-${key}`))
      .filter(el => !!el)
    const line = topLine()
    const target = direction > 0
      ? elements.find(el => el.getBoundingClientRect().top > line + EDGE)
      : elements.findLast(el => el.getBoundingClientRect().top < line - EDGE)
    if (target)
      align(() => target.isConnected ? target.getBoundingClientRect().top : undefined)
  }

  /** The top-level group holding `key` (a group key) or `sha` (a file), so a collapsed one can be opened first. */
  function parentGroup(match: (group: ResolvedGroupWithChildren['children'][number]) => boolean) {
    return store()?.groups.find(group => match(group) || group.children.some(match))
  }

  async function expandGroup(key: string | undefined) {
    if (!key || !collapsedGroups.value.has(key))
      return
    const next = new Set(collapsedGroups.value)
    next.delete(key)
    collapsedGroups.value = next
    await nextTick()
  }

  async function jumpToFile(sha: string) {
    await expandGroup(parentGroup(group => group.files.some(file => file.sha === sha))?.key)
    const view = fileViews.get(sha)
    if (view?.collapsed.value) {
      view.collapsed.value = false
      await nextTick()
    }
    goToFile(sha)
  }

  async function jumpToGroup(key: string) {
    await expandGroup(parentGroup(group => group.key === key)?.key)
    align(() => root().getElementById(`group-${key}`)?.getBoundingClientRect().top)
  }

  const hasDiff = () => !!store()?.diff
  const github = () => {
    const ref = store()?.diff?.ref
    return ref && ref.kind !== 'paste' && ref.kind !== 'local' && ref.kind !== 'posted' ? ref : undefined
  }

  useCommand(() => {
    const s = store()
    const pr = t('palette.group.pr')
    const view = t('palette.group.view')
    const list: Command[] = [
      { id: COMMAND.nextFile, title: t('keys.nextFile'), group: pr, icon: 'i-ph:arrow-down', when: hasDiff, run: () => stepFile(1) },
      { id: COMMAND.prevFile, title: t('keys.prevFile'), group: pr, icon: 'i-ph:arrow-up', when: hasDiff, run: () => stepFile(-1) },
      { id: COMMAND.nextHunk, title: t('keys.nextHunk'), group: pr, icon: 'i-ph:arrow-line-down', when: hasDiff, run: () => stepHunk(1) },
      { id: COMMAND.prevHunk, title: t('keys.prevHunk'), group: pr, icon: 'i-ph:arrow-line-up', when: hasDiff, run: () => stepHunk(-1) },
      { id: COMMAND.nextGroup, title: t('keys.nextGroup'), group: pr, icon: 'i-ph:caret-double-down', when: hasDiff, run: () => stepGroup(1) },
      { id: COMMAND.prevGroup, title: t('keys.prevGroup'), group: pr, icon: 'i-ph:caret-double-up', when: hasDiff, run: () => stepGroup(-1) },
      {
        id: COMMAND.toggleReviewed,
        title: t('keys.toggleReviewed'),
        group: pr,
        icon: 'i-ph:check-square',
        when: hasDiff,
        run: () => {
          const file = currentFile()
          if (file && s)
            return s.setReviewed([file.sha], !s.reviewed.has(file.sha))
        },
      },
      {
        id: COMMAND.toggleFile,
        title: t('keys.toggleFile'),
        group: pr,
        icon: 'i-ph:arrows-in-line-vertical',
        when: hasDiff,
        run: () => {
          const view = fileViews.get(currentFile()?.sha ?? '')
          if (view)
            view.collapsed.value = !view.collapsed.value
        },
      },
      {
        id: COMMAND.openFile,
        title: t('keys.openFile'),
        group: pr,
        icon: 'i-carbon-logo-github',
        when: () => !!s?.diff?.url,
        run: () => {
          const file = currentFile()
          const url = s?.diff?.url
          if (!file || !url)
            return
          const base = s.diff!.ref.kind === 'github-pr' ? `${url}/files` : url
          const anchor = anchors.value.get(file.path)
          openExternal(anchor ? `${base}#diff-${anchor}` : base)
        },
      },
      {
        id: COMMAND.openPr,
        title: t('keys.openPr'),
        group: pr,
        icon: 'i-ph:github-logo',
        when: () => !!s?.diff?.url,
        run: () => openExternal(s!.diff!.url!),
      },
      {
        id: COMMAND.copyPath,
        title: t('keys.copyPath'),
        group: pr,
        icon: 'i-lu-copy',
        when: hasDiff,
        run: () => {
          const file = currentFile()
          if (file)
            return copy(file.path)
        },
      },
      {
        id: COMMAND.copyPermalink,
        title: t('keys.copyPermalink'),
        group: pr,
        icon: 'i-ph:link',
        when: () => !!github() && !!s?.diff?.head,
        run: () => {
          const file = currentFile()
          const ref = github()
          const diff = s?.diff
          const sha = file?.status === 'removed' ? diff?.base?.sha : diff?.head?.sha
          if (file && ref && sha)
            return copy(`https://github.com/${ref.owner}/${ref.repo}/blob/${sha}/${encodePath(file.path)}`)
        },
      },
      {
        id: COMMAND.findFile,
        title: t('keys.findFile'),
        group: pr,
        icon: 'i-lu-search',
        when: hasDiff,
        run: () => openPalette({ prefix: 'jump.file:', label: t('palette.group.files') }),
      },
      {
        id: COMMAND.analyze,
        title: t('keys.analyze'),
        group: pr,
        icon: 'i-lu-think',
        when: () => !!s?.llm && hasDiff(),
        run: () => {
          const llm = s!.llm!
          if (!llm.isSetup)
            settingsModalOpen.value = true
          else if (s!.aiResult && s!.analyzeMode === 'rule-based')
            return s!.setAnalyzeMode(s!.aiResult.source ?? 'llm')
          else if (!llm.isAnalyzing)
            return llm.reanalyze()
        },
      },
      { id: COMMAND.refresh, title: t('keys.refresh'), group: pr, icon: 'i-lu-refresh', when: () => !!s?.canRefresh, run: () => s!.refresh() },
      { id: COMMAND.top, title: t('keys.top'), group: pr, hidden: true, run: () => scrollTo(0) },
      { id: COMMAND.bottom, title: t('keys.bottom'), group: pr, hidden: true, run: () => scrollTo((scroller() ?? document.documentElement).scrollHeight) },
      {
        id: COMMAND.layout,
        title: t('keys.layout'),
        group: view,
        icon: 'i-ph:columns',
        when: hasDiff,
        run: () => s!.ui.setLayout(s!.ui.layout === 'split' ? 'unified' : 'split'),
      },
      {
        id: COMMAND.groupNav,
        title: t('keys.groupNav'),
        group: view,
        icon: 'i-ph:sidebar',
        when: hasDiff,
        run: () => {
          groupNav.value = groupNav.value === 'sidebar' ? 'tabs' : 'sidebar'
        },
      },
      {
        id: COMMAND.threads,
        title: t('keys.threads'),
        group: view,
        icon: 'i-ph:chats',
        when: () => !!s?.reviews,
        run: () => s!.reviews!.setShowThreads(!s!.reviews!.showThreads),
      },
      {
        id: COMMAND.analyzeMode,
        title: t('keys.analyzeMode'),
        group: view,
        icon: 'i-ph:shuffle',
        when: () => !!s?.aiResult,
        run: () => s!.setAnalyzeMode(s!.analyzeMode === 'rule-based' ? s!.aiResult!.source ?? 'llm' : 'rule-based'),
      },
    ]
    return list
  })

  useCommand(() => {
    const group = t('palette.group.files')
    const seen = new Set<string>()
    return (store()?.groups ?? []).flatMap(parent => [parent, ...parent.children]).flatMap(child => child.files.flatMap((file): Command[] => {
      if (seen.has(file.sha))
        return []
      seen.add(file.sha)
      return [{
        id: `jump.file:${file.sha}`,
        title: t('palette.jumpToFile', { path: file.path }),
        group,
        keywords: [file.previousPath, child.label].filter(Boolean).join(' '),
        icon: 'i-lu-file',
        run: () => jumpToFile(file.sha),
      }]
    }))
  })

  useCommand(() => {
    const group = t('palette.group.groups')
    return (store()?.groups ?? []).flatMap(parent => [parent, ...parent.children].map((child): Command => ({
      id: `jump.group:${child.key}`,
      title: t('palette.jumpToGroup', { name: child === parent ? child.label : `${parent.label} › ${child.label}` }),
      group,
      icon: 'i-ph:folder-simple',
      run: () => jumpToGroup(child.key),
    })))
  })
}
