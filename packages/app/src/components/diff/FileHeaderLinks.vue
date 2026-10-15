<script setup lang="ts">
import type { FileChange } from '@pulls.review/core/types'
import type { DiffsStore } from '../../stores/types'
import ActionIconButton from '@antfu/design/components/Action/ActionIconButton.vue'
import { useClipboard } from '@vueuse/core'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { githubBlobUrl, githubFileDiffUrl } from './file-links'

const props = defineProps<{
  store: DiffsStore
  file: FileChange
}>()

const { t } = useI18n()
const { copy, copied } = useClipboard({ legacy: true })
const lastCopied = ref<'path' | 'link'>()

const diff = computed(() => props.store.diff)
const github = computed(() => {
  const source = diff.value?.ref
  return source && (source.kind === 'github-pr' || source.kind === 'github-compare' || source.kind === 'github-commit') ? source : undefined
})
const blobUrl = computed(() => {
  const repo = github.value
  const sha = props.file.status === 'removed' ? diff.value?.base?.sha : diff.value?.head?.sha
  return repo && sha ? githubBlobUrl(repo.owner, repo.repo, sha, props.file.path) : undefined
})

async function copyPath() {
  lastCopied.value = 'path'
  await copy(props.file.path)
}

async function copyLink() {
  const url = diff.value?.url
  if (!url || !github.value)
    return
  lastCopied.value = 'link'
  await copy(await githubFileDiffUrl(url, github.value.kind === 'github-pr', props.file.path))
}

function tooltip(kind: 'path' | 'link') {
  if (copied.value && lastCopied.value === kind)
    return t('overview.file.copied')
  return t(kind === 'path' ? 'overview.file.copyPath' : 'overview.file.copyLink')
}
</script>

<template>
  <ActionIconButton
    compact
    :icon="copied && lastCopied === 'path' ? 'i-lu-check' : 'i-lu-copy'"
    :label="$t('overview.file.copyPath')"
    :tooltip="tooltip('path')"
    class="op-fade hover:op-100"
    @click="copyPath"
  />
  <template v-if="github && diff?.url">
    <ActionIconButton
      compact
      :icon="copied && lastCopied === 'link' ? 'i-lu-check' : 'i-ph:link'"
      :label="$t('overview.file.copyLink')"
      :tooltip="tooltip('link')"
      class="op-fade hover:op-100"
      @click="copyLink"
    />
    <ActionIconButton
      v-if="blobUrl"
      compact
      icon="i-carbon-logo-github"
      :href="blobUrl"
      target="_blank"
      rel="noopener"
      :label="$t('overview.file.openAtHead')"
      :tooltip="$t('overview.file.openAtHead')"
      class="op-fade hover:op-100"
    />
  </template>
</template>
