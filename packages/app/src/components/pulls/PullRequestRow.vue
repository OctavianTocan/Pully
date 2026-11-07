<script setup lang="ts">
import type { MyPull } from '@pulls.review/core/local-rpc'
import type { PullRequestListItem } from '@pulls.review/core/types'
import type { ViewedPullRequest } from '../../stores/pull-request-list-store'
import { computed } from 'vue'
import HomePullRow from '../home/HomePullRow.vue'

const props = defineProps<{ owner: string, repo: string, pr: PullRequestListItem, viewed?: ViewedPullRequest }>()
const pull = computed<MyPull>(() => ({
  owner: props.owner, repo: props.repo, number: props.pr.number,
  title: props.pr.title, author: props.pr.author?.login ?? '',
  isDraft: props.pr.state === 'draft', state: 'open', role: 'owned',
  updatedAt: props.pr.updatedAt, commentsCount: props.pr.comments,
  labels: props.pr.labels.map(label => label.name), checks: props.pr.checks,
  additions: props.viewed?.additions ?? props.pr.additions,
  deletions: props.viewed?.deletions ?? props.pr.deletions,
  changedFiles: props.pr.changedFiles,
}))
</script>

<template>
  <HomePullRow :pull="pull" />
</template>
