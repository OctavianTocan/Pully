/**
 * The contract between the `pulls.review` devframe server (`packages/cli`) and the
 * `PR_LOCAL` app build: the devframe id, where it mounts, and its RPC function names.
 * Browser-safe; both sides import it so the names can't drift.
 */
export const LOCAL_DEVFRAME_ID = 'pulls.review'

/** Where a devframe hub mounts it (`/__<id>/`); standalone, it serves at `/`. */
export const LOCAL_HUB_BASE_PATH = '/__pulls.review/'

/** Bare names; devframe namespaces them as `pulls.review:<name>`. */
export const LOCAL_RPC = {
  /** Branches, tags, recent commits and the default branch, for the ref picker. */
  repoInfo: 'repo-info',
  sourceKey: 'source-key',
  sourceFetch: 'source-fetch',
  sourceFingerprint: 'source-fingerprint',
  sourceLoadFile: 'source-load-file',
  /** An unstorage driver over the server's cache directory. */
  storageGetItem: 'storage-get-item',
  storageSetItem: 'storage-set-item',
  storageRemoveItem: 'storage-remove-item',
  storageGetKeys: 'storage-get-keys',
  githubToken: 'github-token',
  /** The signed-in user's pull requests, fetched fresh unless the server's copy is seconds old. */
  myPulls: 'my-pulls',
  /** One prompt through the server's Claude Code or Codex CLI. */
  llmRun: 'llm-run',
  /** The models (and their reasoning efforts) a CLI reports it can run. */
  aiModels: 'ai-models',
  /** Starts a prompt as a background job on the server; resolves to its id. */
  aiJobStart: 'ai-job-start',
  /** Long-polls a job for activity newer than a revision. */
  aiJobWait: 'ai-job-wait',
  aiJobCancel: 'ai-job-cancel',
  /** Jobs still running or recently finished, newest first. */
  aiJobList: 'ai-job-list',
  /** The skills a critique can be run through, as `ReviewLens`es. */
  lensList: 'lens-list',
  /** One skill's instructions by name, or `undefined` when there is none. */
  lensGet: 'lens-get',
  /** The server's last `my-pulls` answer without waiting; starts a refresh when it has aged. */
  myPullsCached: 'my-pulls-cached',
  /** Batched `storage-get-item` / `storage-set-item`: one round trip for many keys. */
  storageGetItems: 'storage-get-items',
  storageSetItems: 'storage-set-items',
  /** Starts an analysis, review, question or chat reply that runs and saves its result on the server. */
  aiTaskStart: 'ai-task-start',
  /** Long-polls a task for work-log entries newer than a revision. */
  aiTaskWait: 'ai-task-wait',
  aiTaskCancel: 'ai-task-cancel',
  /** One diff's tasks, running or recently finished, newest first. */
  aiTaskList: 'ai-task-list',
  /** Every diff's tasks, running or recently finished, newest first, without work logs. */
  aiTaskListAll: 'ai-task-list-all',
  /** One diff's saved question threads. */
  askThreads: 'ask-threads',
  /** Drops a question thread and stops its running answer. */
  askThreadClose: 'ask-thread-close',
  /** The conversation about the signed-in user's pull requests, asked from the phone's Home search. */
  inboxChat: 'inbox-chat',
  /** Starts that conversation afresh, stopping an answer still on its way. */
  inboxChatClear: 'inbox-chat-clear',
} as const

/** A skill offered as a point of view for an AI review. */
export interface ReviewLens {
  /** The skill's folder name, which a critique request names it by. */
  name: string
  /** A few words to show for it, such as "Security"; the name when the skill gives none. */
  title: string
  /** One sentence on what it looks for. */
  description?: string
}

/** What a lens name may look like; it also names a folder on the server. */
export const REVIEW_LENS_NAME = /^[\w-][\w.-]*$/

/**
 * What the server can run a model prompt through: the Claude Code and Codex CLIs, or `pi`, the server's own agent
 * loop (pi-agent-core) calling any model behind an OpenAI-compatible endpoint, such as CLIProxyAPI.
 */
export const LLM_ENGINES = ['claude-code', 'codex', 'pi'] as const
export type LlmEngine = typeof LLM_ENGINES[number]

/** A model as its CLI (or, for `pi`, the endpoint's model list) describes it, never a hard-coded list. */
export interface CliModel {
  /** What `--model` takes. */
  id: string
  name: string
  description?: string
  /** Reasoning efforts the model accepts, lowest first; empty when it takes none. */
  efforts: string[]
  /** The effort the CLI uses when none is passed. */
  defaultEffort?: string
  /** The model the CLI runs when none is passed. */
  isDefault?: boolean
}

export interface CliModelCatalog {
  engine: LlmEngine
  models: CliModel[]
  /** The signed-in plan, e.g. `Claude Max`, when the CLI reports it. */
  account?: string
  /** The CLI's own version string. */
  version?: string
}

/** Token counts and cost exactly as the CLI reported them; fields it omits stay unset. */
export interface AiUsage {
  inputTokens?: number
  outputTokens?: number
  cacheReadTokens?: number
  cacheWriteTokens?: number
  reasoningTokens?: number
  /** USD; only Claude Code reports it. */
  costUsd?: number
  durationMs?: number
  /** The model that actually ran. */
  model?: string
}

export const AI_ACTIVITY_KINDS = ['status', 'thinking', 'text', 'tool'] as const
export type AiActivityKind = typeof AI_ACTIVITY_KINDS[number]
export type AiActivityStatus = 'running' | 'done' | 'failed'

/**
 * One entry of a job's work log. Entries update in place (a thinking block grows,
 * a tool call finishes), so readers merge them by `id`.
 */
export interface AiActivity {
  id: string
  kind: AiActivityKind
  /** One line saying what happened, e.g. `Read packages/core/src/index.ts`. */
  title: string
  /** The reasoning, answer or tool output so far. */
  text?: string
  /** The tool's own name, for `tool` entries. */
  tool?: string
  status: AiActivityStatus
  startedAt: number
  endedAt?: number
  /** The job revision this entry last changed at. */
  rev: number
}

/** A pull request (or compare/commit) the model may inspect through tools while it works. */
export interface AiPrContext {
  owner: string
  repo: string
  number?: number
  title?: string
  headSha: string
  baseSha?: string
  files: { path: string, previousPath?: string, status?: string, patch?: string }[]
}

export interface AiJobRequest {
  engine: LlmEngine
  model?: string
  effort?: string
  system: string
  prompt: string
  /** JSON Schema the final answer must match; the answer is then JSON text. */
  schema?: unknown
  /** What the job is for, shown in job lists, e.g. `Analyze antfu/pulls.review#62`. */
  label?: string
  /** Starting a job whose key matches one still running returns that job instead. */
  key?: string
  /** Lets the model read the PR's diffs and source through tools. */
  context?: AiPrContext
  /** Lets the model read the pull requests of an inbox through tools; set by the server's own tasks only. */
  inbox?: AiInboxContext
}

/** The pull requests an inbox question may read through tools: only these, nothing else on GitHub. */
export interface AiInboxContext {
  pulls: { owner: string, repo: string, number: number }[]
}

export const AI_JOB_STATUSES = ['running', 'done', 'failed', 'cancelled'] as const
export type AiJobStatus = typeof AI_JOB_STATUSES[number]

/** A job as of revision `rev`; `activities` holds only entries changed since the caller's `after`. */
export interface AiJobSnapshot {
  id: string
  label?: string
  key?: string
  engine: LlmEngine
  model?: string
  effort?: string
  status: AiJobStatus
  rev: number
  activities: AiActivity[]
  /** The final answer, once `done`. */
  result?: string
  error?: string
  usage?: AiUsage
  startedAt: number
  endedAt?: number
}

/** Why a pull request shows up for the signed-in user (`owned`: it sits in a repo they or their orgs own). */
export const MY_PULL_ROLES = ['review-requested', 'authored', 'involved', 'owned'] as const
export type MyPullRole = typeof MY_PULL_ROLES[number]

export interface MyPull {
  owner: string
  repo: string
  number: number
  title: string
  author: string
  isDraft: boolean
  updatedAt: string
  commentsCount: number
  labels: string[]
  state: 'open' | 'closed'
  role: MyPullRole
  url?: string
  /** Closed by merging rather than abandoned. */
  merged?: boolean
  /** A closed one: when it was merged or closed, which a later comment does not move as it does `updatedAt`. */
  closedAt?: string
  headSha?: string
  /** The signed-in user is asked to review it, directly or through a team. */
  requestedFromMe?: boolean
  /** Absent when the repository requires no review. */
  reviewDecision?: MyPullReviewDecision
  /** The combined status of the head commit's checks. */
  checks?: MyPullChecks
  mergeable?: MyPullMergeable
  additions?: number
  deletions?: number
  changedFiles?: number
  /** Logins and `org/team` slugs with a pending review request. */
  reviewRequests?: string[]
  /** The branch it merges into. */
  baseRef?: string
  /** The branch it merges from. */
  headRef?: string
  /** Open pull requests only: the stack it is a layer of, when it is in one. */
  stack?: MyPullStack
  /** Jev's classification of this head commit and title; absent until it is done, or when Jev is off. */
  jev?: MyPullJev
}

/**
 * Where a pull request sits in a stack: GitHub's own (`native`), or inferred from the listed open
 * pull requests, one stacked on another when its base branch is the other's head branch.
 */
export interface MyPullStack {
  /** The same for every layer of one stack: `native:<owner>/<repo>:<stack number>` or `inferred:<owner>/<repo>:<bottom head branch>`. */
  id: string
  /** 1 sits on the base branch; each layer above counts up. Siblings of a fork share a position. */
  position: number
  /** How many pull requests the stack holds. */
  size: number
  native: boolean
  /** The number of the pull request this one sits on, when it is listed or known. */
  below?: number
}

/** What a pull request is for, as Jev sorts them. */
export const MY_PULL_KINDS = ['feature', 'fix', 'refactor', 'performance', 'docs', 'tests', 'dependencies', 'infra'] as const

export interface MyPullJev {
  /** One of `MY_PULL_KINDS`; a client treats any other value as unknown. */
  kind: string
  /** How sure Jev is of `kind`, 0 to 1. */
  kindConfidence: number
  /** 0 trivial, 1 low, 2 medium, 3 high; probability-weighted, so it can sit between two levels. */
  risk: number
}

export type MyPullReviewDecision = 'approved' | 'changes-requested' | 'review-required'
/** `none`: the head commit reports no checks at all. */
export type MyPullChecks = 'success' | 'failure' | 'pending' | 'none'
/** `unknown` while GitHub is still computing it. */
export type MyPullMergeable = 'mergeable' | 'conflicting' | 'unknown'

export const MY_PULL_STATES = ['open', 'closed'] as const
export type MyPullState = typeof MY_PULL_STATES[number]

/** What the server last fetched for a `my-pulls` state. */
export interface MyPullsSnapshot {
  pulls: MyPull[]
  /** Epoch milliseconds the list was fetched at. */
  fetchedAt: number
  /** A newer list is being fetched right now. */
  refreshing: boolean
  /** Present when the server classifies pull requests (`MyPull.jev`), so a client can offer grouping by kind and risk. */
  classifier?: 'jev'
}

/** What a server task does; each saves its result where the page reads it back. */
export const AI_TASK_KINDS = ['analyze', 'critique', 'ask', 'chat', 'tour', 'brief', 'inbox'] as const
export type AiTaskKind = typeof AI_TASK_KINDS[number]

/** The task key of a question about the inbox, which belongs to no diff. */
export const INBOX_KEY = 'inbox'

/** What an analysis starts by itself once it succeeds, so the whole job finishes with the phone asleep. */
export const AI_FOLLOW_UPS = ['tour', 'brief'] as const
export type AiFollowUp = typeof AI_FOLLOW_UPS[number]

/** The CLI, model and effort a task runs with, resolved by the page from its settings. */
export interface AiTaskModel {
  engine: LlmEngine
  model?: string
  effort?: string
}

/** Lines of one file a question is about; mirrors core's `AskSelection`. */
export interface AiTaskSelection {
  path: string
  side: 'additions' | 'deletions'
  startLine: number
  endLine: number
}

interface AiTaskRequestBase {
  /** The diff's cache key; the server reads the diff from the cache under it. */
  key: string
  model: AiTaskModel
}

/**
 * A server task. It keeps running when the page reloads or closes, and writes its
 * result into the shared cache: the AI grouping and chat session of the diff entry,
 * the critique, tour and brief caches, or the diff's saved question threads.
 */
export type AiTaskRequest
  = | AiTaskRequestBase & { kind: 'analyze', locale: string, then?: AiFollowUp[] }
    | AiTaskRequestBase & { kind: 'critique', variant: string, lens?: string }
    | AiTaskRequestBase & { kind: 'ask', threadId: string, selection: AiTaskSelection, question: string }
    | AiTaskRequestBase & { kind: 'chat', locale: string, text?: string, retry?: boolean }
    | AiTaskRequestBase & { kind: 'tour', locale: string }
    | AiTaskRequestBase & { kind: 'brief', locale: string }
    /**
     * A question about the signed-in user's pull requests, keyed {@link INBOX_KEY}. `retry` answers the last question
     * again in place of its old answer; `skip` names repositories (`owner/repo`) the phone hides.
     */
    | AiTaskRequestBase & { kind: 'inbox', locale: string, question: string, retry?: boolean, skip?: string[] }

/** A task as of revision `rev`; `activities` holds only entries changed since the caller's `after`. */
export interface AiTaskSnapshot {
  id: string
  kind: AiTaskKind
  key: string
  /** The diff's title, so a list across diffs can name each one. */
  title?: string
  label: string
  engine: LlmEngine
  model?: string
  effort?: string
  status: AiJobStatus
  rev: number
  activities: AiActivity[]
  error?: string
  usage?: AiUsage
  /** Once `done`: `ask` the answer; `analyze` the `GroupedResult` and `critique` the `CritiqueResult`, as JSON. */
  result?: string
  startedAt: number
  endedAt?: number
  /** `critique`: the cache variant the review is saved under. */
  variant?: string
  lens?: string
  /** `ask`: the thread the question belongs to. */
  threadId?: string
  /** `ask` and `inbox`: the question; `chat`: the message sent. */
  text?: string
  /** `analyze`: what it starts after itself, so a client can show those steps from the first moment. */
  then?: AiFollowUp[]
}

/** One saved question of a thread and its answer. */
export interface AiAskTurn {
  question: string
  answer?: string
  error?: string
  usage?: AiUsage
  activities?: AiActivity[]
  startedAt?: number
  /** Unset while the answer is coming. */
  outcome?: 'done' | 'failed' | 'stopped'
  /** The server task answering it. */
  taskId?: string
  /** An inbox answer: the listed pull requests it names, in the order it names them. */
  pulls?: AiPullRef[]
}

/** A pull request by name. */
export interface AiPullRef {
  owner: string
  repo: string
  number: number
}

/** The conversation about the inbox: its turns, oldest first, and the questions offered under the last answer. */
export interface AiInboxChat {
  turns: AiAskTurn[]
  suggestions?: string[]
}

/** Questions about one selection of lines, saved on the server per diff. */
export interface AiAskThread {
  id: string
  selection: AiTaskSelection
  /** The selection as `path:L10-L20`. */
  label: string
  turns: AiAskTurn[]
}
