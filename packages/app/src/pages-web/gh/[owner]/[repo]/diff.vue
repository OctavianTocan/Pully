<script setup lang="ts">
import type { CommitSelection } from '../../../../components/overview/commit-selection'
import type { RoutableRef } from '../../../../source-routes'
import type { DiffsStore } from '../../../../stores/types'
import ActionButton from '@antfu/design/components/Action/ActionButton.vue'
import FeedbackEmptyState from '@antfu/design/components/Feedback/FeedbackEmptyState.vue'
import FeedbackLoading from '@antfu/design/components/Feedback/FeedbackLoading.vue'
import { createGithubSource } from '@pulls.review/core/github'
import { computed, getCurrentScope, onMounted, provide, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAppContext } from '../../../../app-context'
import DiffsPage from '../../../../components/diff/DiffsPage.vue'
import { commitSelectionRef, formatCommitSelection, parseCommitSelection } from '../../../../components/overview/commit-selection'
import { createPrOverviewContext, prOverviewKey } from '../../../../components/overview/context'
import { useDocumentTitle } from '../../../../composables/useDocumentTitle'
import { resolveStoredTokenMeta } from '../../../../composables/useGithubTokenMeta'
import { prSnapshots } from '../../../../state/snapshots'
import { createDiffsStore } from '../../../../stores/diffs-store'
import { createPrOverviewStore } from '../../../../stores/pr-overview-store'

const props = defineProps<{ sourceRef: RoutableRef }>()

const route = useRoute()
const router = useRouter()
const { cache, credentials } = useAppContext()
const scope = getCurrentScope()

// Read once: `App.vue` keys the routed page by path, so another diff mounts a fresh page and store.
const source = props.sourceRef
const pr = source.kind === 'github-pr' ? source : undefined

// `?from=<login>` deep-links a shared analysis (see plans/07); read once, never rewritten.
const from = typeof route.query.from === 'string' ? route.query.from : undefined

const store = createDiffsStore(createGithubSource(source, credentials, { tokenMeta: resolveStoredTokenMeta }), {
  cache,
  from,
  snapshots: import.meta.env.PR_LOCAL ? prSnapshots() : undefined,
})

const overview = pr && createPrOverviewStore(pr, {
  credentials,
  onChanged: (action) => {
    if (action === 'update-branch')
      void store.refresh()
  },
})

const selection = computed(() => pr ? parseCommitSelection(route.query.commits) : undefined)
const selectionKey = computed(() => selection.value ? formatCommitSelection(selection.value) : '')

// One store per visited selection, so stepping back and forth through commits keeps each loaded diff.
const commitStores = new Map<string, DiffsStore>()
function storeFor(value: CommitSelection | undefined): DiffsStore {
  if (!pr || !value)
    return store
  const key = formatCommitSelection(value)
  let hit = commitStores.get(key)
  if (!hit) {
    const create = () => createDiffsStore(createGithubSource(commitSelectionRef(pr.owner, pr.repo, value), credentials), { cache })
    // Created after setup, so bind its watchers to this page's scope explicitly.
    hit = scope?.run(create) ?? create()
    commitStores.set(key, hit)
  }
  return hit
}
const active = computed(() => storeFor(selection.value))

const started = new WeakSet<DiffsStore>()
function start(target: DiffsStore) {
  if (started.has(target))
    return
  started.add(target)
  void target.load()
}

function select(value: CommitSelection | undefined) {
  void router.replace({ query: { ...route.query, commits: value ? formatCommitSelection(value) : undefined } })
}

if (overview)
  provide(prOverviewKey, createPrOverviewContext(overview, () => selection.value, select))

// Matches the header's title and label; before the diff loads, the repo still
// identifies what is opening.
useDocumentTitle(() => {
  const diff = store.diff
  if (diff)
    return diff.label ? `${diff.title} (${diff.label})` : diff.title
  if (overview?.overview)
    return `${overview.overview.title} (#${overview.number})`
  return `${source.owner}/${source.repo}`
})

watch(active, start)
onMounted(() => {
  void overview?.load()
  start(active.value)
})
</script>

<template>
  <main>
    <DiffsPage
      :key="selectionKey"
      :store="active"
    >
      <template #loading>
        <FeedbackLoading :text="$t('pr.loading')" />
      </template>
      <template #error="{ error: err, retry }">
        <FeedbackEmptyState
          icon="i-ph:warning"
          :title="$t('pr.loadFailed')"
        >
          <template #hint>
            {{ err.message }}
          </template>
          <template #actions>
            <ActionButton variant="primary" @click="retry">
              {{ $t('common.retry') }}
            </ActionButton>
          </template>
        </FeedbackEmptyState>
      </template>
    </DiffsPage>
  </main>
</template>
