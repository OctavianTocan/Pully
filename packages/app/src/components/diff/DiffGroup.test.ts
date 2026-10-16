import type { FileChange } from '@pulls.review/core/types'
import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '../../i18n'
import { createMockDiffsStore } from '../../stores/mock-diffs-store'
import DiffGroup from './DiffGroup.vue'
import { resolveGroups } from './group-utils'

const file: FileChange = { path: 'file.ts', sha: 'file-sha', status: 'added', additions: 1, deletions: 0, isBinary: false, hunks: [] }
const group = resolveGroups([{
  key: 'parent',
  label: 'Parent',
  filePaths: [],
  children: [{ key: 'child', label: 'Child', filePaths: [file.path] }],
}], [file])[0]!

let wrapper: ReturnType<typeof mount> | undefined
const elements: HTMLElement[] = []
afterEach(() => {
  wrapper?.unmount()
  for (const element of elements.splice(0))
    element.remove()
})

function element(parent: HTMLElement | ShadowRoot, id?: string) {
  const result = document.createElement('div')
  if (id)
    result.id = id
  parent.append(result)
  elements.push(result)
  return result
}

describe('diff group navigation', () => {
  it.each([false, true])('navigates files and parents inside the owning root (shadow: %s)', async (shadow) => {
    const host = element(document.body)
    const root = shadow ? host.attachShadow({ mode: 'open' }) : host
    const target = element(root)
    const expand = vi.fn()
    const outside = shadow ? ['group-parent', 'file-file-sha'].map(id => element(document.body, id)) : []
    const outsideScroll = vi.fn()
    for (const decoy of outside)
      decoy.scrollIntoView = outsideScroll

    wrapper = mount(DiffGroup, {
      attachTo: target,
      props: { store: createMockDiffsStore({}), group, collapsed: false, filesVisible: [] },
      global: {
        plugins: [i18n],
        stubs: {
          DiffGroupNav: true,
          FileTree: { props: ['files'], emits: ['navigate'], template: '<button data-file-tree @click="$emit(\'navigate\', files[0].sha)">File</button>' },
          FileDiff: { props: ['file'], template: '<div :id="\'file-\' + file.sha" />', methods: { expand } },
        },
      },
    })
    const parent = wrapper.get('#group-parent').element as HTMLElement
    const fileElement = wrapper.get('#file-file-sha').element as HTMLElement
    const parentScroll = vi.fn()
    const fileScroll = vi.fn()
    parent.scrollIntoView = parentScroll
    fileElement.scrollIntoView = fileScroll

    await wrapper.get('[data-file-tree]').trigger('click')
    expect(expand).toHaveBeenCalledOnce()
    expect(fileScroll).toHaveBeenCalledWith({ behavior: 'smooth', block: 'start' })

    await wrapper.findAll('button').find(button => button.text() === 'Parent ›')!.trigger('click')
    expect(parentScroll).toHaveBeenCalledWith({ behavior: 'smooth', block: 'start' })
    expect(outsideScroll).not.toHaveBeenCalled()
  })
})
