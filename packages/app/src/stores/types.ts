import type { AgentMessage } from '@earendil-works/pi-agent-core'
import type { AskSelection } from '@pulls.review/core/llm'
import type { AiUsage, ReviewLens } from '@pulls.review/core/local-rpc'
import type { CommentThread, CritiqueFinding, CritiqueResult, CritiqueSeverity, DiffsPayload, FileChange, GroupedResult, GroupSource, PendingReview, ReviewDraftTarget, ReviewSummary, ReviewVerdict } from '@pulls.review/core/types'
import type { TrackedActivity } from '../components/ai/ai-activity'
import type { ResolvedGroupWithChildren } from '../components/diff/group-utils'

/** A step of the running analysis, already worded in the UI language. */
export interface LlmProgress { step: number, message: string }

/**
 * LLM-specific analysis operations, isolated behind `DiffsStore.llm` so components can
 * gate the whole "AI" affordance on its presence rather than a separate boolean prop.
 * Owns operations only, not settings CRUD - API keys live in the app-wide
 * `settings.value.llm` singleton (`app/state/settings.ts`), edited by `SettingsPanel.vue`
 * independently of any one diff view.
 *
 * Built with `reactive()` (see `createDiffsStore`/`createMockDiffsStore`), like a Pinia
 * store - fields read directly (`store.llm.isSetup`), no `.value`.
 */
export interface DiffsStoreLlm {
  /** Whether the selected provider can run: a CLI engine, or an API provider with its key. */
  readonly isSetup: boolean
  readonly isAnalyzing: boolean
  readonly progress: LlmProgress | undefined
  /** The current (or last failed) run's messages, streamed live; `[]` before the first run. */
  readonly transcript: AgentMessage[]
  readonly error: Error | undefined
  /** The current (or last) run's work log, oldest first; for API providers it is read off `transcript`. */
  readonly activities: TrackedActivity[]
  /** The engine of the current (or last) run, e.g. `claude-code`; unset before the first run. */
  readonly engine: string | undefined
  /** When the current (or last) run started, ms since the epoch. */
  readonly startedAt: number | undefined
  /** When the last run settled, ms since the epoch; unset while it runs. */
  readonly endedAt: number | undefined
  /** What the last run consumed, as its engine reported it. */
  readonly usage: AiUsage | undefined
  /** Whether the last run was stopped through `abort`. */
  readonly cancelled: boolean
  /** Whether the current run was already going on the server when this page opened. */
  readonly resumed: boolean
  reanalyze: () => Promise<void>
  /** Stops the in-flight run (and any chat) - leaves `error` unset. A server task is cancelled. */
  abort: () => void
  /** Stops following the run without stopping it: a server task still finishes and saves its result. */
  detach: () => void
  readonly chat: {
    readonly available: boolean
    readonly messages: AgentMessage[]
    readonly isStreaming: boolean
    readonly error: Error | undefined
    /** The pending (or last) reply's work log from a CLI engine; `[]` for API providers. */
    readonly activities: TrackedActivity[]
    /** When the pending (or last) reply was asked for, ms since the epoch. */
    readonly startedAt: number | undefined
    /** When the last reply settled, ms since the epoch; unset while it streams. */
    readonly endedAt: number | undefined
    send: (text: string) => Promise<void>
    retry: () => Promise<void>
    stop: () => void
    clear: () => Promise<void>
  }
}

/**
 * GitHub PR review threads and review submission, isolated behind `DiffsStore.reviews`
 * the same way LLM analysis sits behind `DiffsStore.llm`: components gate the whole
 * review affordance on its presence (`undefined` = the source has no review lifecycle -
 * a source without `DiffSource.reviews`). Mutations post directly from the browser with
 * the user's own credentials; every mutation refetches so the view always reflects the source.
 */
export interface DiffsStoreReviews {
  readonly threads: CommentThread[]
  readonly summaries: ReviewSummary[]
  /** The viewer's unsubmitted review; new comments attach to it while it exists. */
  readonly pendingReview: PendingReview | undefined
  readonly pendingCommentCount: number
  readonly isLoading: boolean
  /** Login of the token's user - gates edit/delete to own comments. */
  readonly viewerLogin: string | undefined
  /**
   * Whether write UI shows at all. Classic PATs are gated on their scopes
   * (`repo`/`public_repo`); fine-grained PATs expose no scopes, so they start
   * optimistic and flip off on the first 403 (see `writeBlockedReason`).
   */
  readonly canWrite: boolean
  /** Set when a write got a 403 - explains why write UI disappeared mid-session. */
  readonly writeBlockedReason: string | undefined
  /** Global persisted toggle: render existing (submitted) threads inline. Pending drafts always render. */
  readonly showThreads: boolean
  setShowThreads: (value: boolean) => void
  load: () => Promise<void>
  /** `single` posts immediately; `review` starts (or adds to) the pending review. */
  addComment: (target: ReviewDraftTarget, body: string, mode: 'single' | 'review') => Promise<void>
  reply: (rootCommentId: number, body: string) => Promise<void>
  editComment: (commentId: number, body: string) => Promise<void>
  deleteComment: (commentId: number) => Promise<void>
  resolveThread: (threadId: string) => Promise<void>
  submitReview: (verdict: ReviewVerdict, body: string) => Promise<void>
  /** Posts one COMMENT review with inline comments; a pending review is submitted with them, drafts included. */
  postReview: (body: string, comments: { target: ReviewDraftTarget, body: string }[]) => Promise<void>
  discardPendingReview: () => Promise<void>
}

export type AiRunOutcome = 'done' | 'failed' | 'stopped'

/** A request to scroll a finding into view; `nonce` changes on every request. */
export interface CritiqueFocus {
  id: string
  path: string
  nonce: number
}

/**
 * An AI review of the diff for defects, run through Claude Code or Codex on the local
 * server, behind `DiffsStore.critique` (local build only).
 */
export interface DiffsStoreCritique {
  /** Whether the configured AI provider can review: Claude Code or Codex. */
  readonly available: boolean
  /** Whether skills can be picked as review lenses. */
  readonly hasLenses: boolean
  /** The critique of the current head, from this run or an earlier one. */
  readonly result: CritiqueResult | undefined
  readonly isRunning: boolean
  readonly error: Error | undefined
  /** The work log of the review running now, or of the last one run on this page. */
  readonly activities: readonly TrackedActivity[]
  /** When that review started, ms since the epoch. */
  readonly startedAt: number | undefined
  /** How that review ended; `undefined` while it runs or before any has. */
  readonly outcome: AiRunOutcome | undefined
  /** Whole seconds since the running review started. */
  readonly elapsed: number
  /** The lens of the running review, else of `result`; `undefined` is the general review. */
  readonly lens: string | undefined
  /** Severities the findings list shows. */
  readonly severities: CritiqueSeverity[]
  /** `result`'s findings of the shown severities. */
  readonly visibleFindings: CritiqueFinding[]
  /** Ids of the findings picked for posting. */
  readonly selected: Set<string>
  /** Ids of the findings already posted from `result`. */
  readonly posted: Set<string>
  /** The visible, picked findings not yet posted. */
  readonly toPost: CritiqueFinding[]
  /** Whether `toPost` can be posted as a review on this source. */
  readonly canPost: boolean
  readonly isPosting: boolean
  readonly postError: Error | undefined
  /** The review body `post` is prefilled with. */
  readonly defaultBody: string
  readonly focused: CritiqueFocus | undefined
  /** The skills on offer as lenses; empty when none are. */
  listLenses: () => Promise<ReviewLens[]>
  /** Shows the saved critique for `lens` at this head, or runs one; `force` always runs. */
  run: (options?: { lens?: string, force?: boolean }) => Promise<void>
  /** Stops the running review, on the server too; leaves `error` unset. */
  abort: () => void
  /** Stops following the running review; a server task still finishes and saves it. */
  detach: () => void
  setSeverities: (severities: CritiqueSeverity[]) => void
  setSelected: (id: string, selected: boolean) => void
  /** Picks or unpicks every visible finding. */
  selectAll: (selected: boolean) => void
  focus: (finding: CritiqueFinding) => void
  /** Posts `toPost` as one COMMENT review with `body` above its inline comments. */
  post: (body: string) => Promise<void>
}

/** One question of an ask thread and its answer. */
export interface AskThreadTurn {
  question: string
  /** Markdown; unset while the question is being answered or when it failed. */
  answer?: string
  error?: string
  usage?: AiUsage
  /** What the engine did to answer, oldest first. */
  activities?: TrackedActivity[]
  /** When the question was sent, ms since the epoch. */
  startedAt?: number
  /** Unset while the answer is coming. */
  outcome?: AiRunOutcome
}

/** Questions about one selection of lines, answered one after another. */
export interface AskThread {
  id: string
  selection: AskSelection
  /** The selection as `path:L10-L20`. */
  label: string
  turns: AskThreadTurn[]
  isAsking: boolean
}

/**
 * Questions about selected lines, answered through Claude Code or Codex on the local
 * server, behind `DiffsStore.ask` (local build only). Threads last for the page session.
 */
export interface DiffsStoreAsk {
  /** Whether the configured ask provider is Claude Code or Codex. */
  readonly available: boolean
  /** This diff's threads, oldest first. */
  readonly threads: AskThread[]
  /** Opens a thread on `selection`, or returns the open one on the same lines. */
  open: (selection: AskSelection) => string
  /** Asks `question` in thread `id`, after the turns already answered there. */
  ask: (id: string, question: string) => Promise<void>
  /** Stops the answer thread `id` is waiting for. */
  stop: (id: string) => void
  close: (id: string) => void
}

/** A shared analysis found in the PR's Conversation comments, offered for loading. */
export interface SharedAnalysisCandidate {
  login: string
  /** `html_url` of the comment. */
  url: string
  result: GroupedResult
  /** The viewer's own comment (e.g. from another browser). */
  own: boolean
  /** Analyzed at a head the loaded diff has since moved past. */
  stale: boolean
}

/**
 * Sharing an AI result as a PR comment and loading results others shared,
 * behind `DiffsStore.shared` (github only, like `reviews`). See plans/07.
 */
export interface DiffsStoreShared {
  /** Offered for loading, newest first; `[]` once loaded or dismissed. */
  readonly candidates: SharedAnalysisCandidate[]
  /** e.g. the `?from=` user has no shared analysis here. */
  readonly notice: string | undefined
  /** Login of the token's user - the comment is posted as them. */
  readonly viewerLogin: string | undefined
  /** Same gating as `reviews.canWrite`. */
  readonly canShare: boolean
  readonly isSharing: boolean
  readonly error: Error | undefined
  /** The viewer's own comment on this PR once known - the next share updates it. */
  readonly ownComment: { id: number, url: string } | undefined
  dismiss: () => void
  /** Loads that user's analysis - an offered candidate, or else by scanning the comments again (like `?from=`). */
  load: (login: string) => Promise<void>
  /** Posts (or updates) the viewer's comment with the current locally generated AI result. */
  share: () => Promise<void>
}

/**
 * Presentation-level settings every diff view component needs, grouped so they can be
 * read off the same `store` prop instead of threaded down as their own separate props.
 * `layout` is a shared app-wide preference (backed by the same persisted singleton
 * across every `DiffsStore` instance, like `state/dark.ts`'s `isDark`) - writing it
 * through one store's `ui.layout` updates it everywhere.
 */
export interface DiffsStoreUi {
  readonly layout: 'split' | 'unified'
  setLayout: (layout: 'split' | 'unified') => void
}

/**
 * One reactive store per loaded diff - created explicitly by `createDiffsStore` (real
 * data) or `createMockDiffsStore` (Storybook/tests), never a global singleton/registry.
 * Passed down as a single prop through the whole view tree; components call its methods
 * directly instead of emitting events that bubble back up to whoever created it.
 *
 * Built with `reactive()`, like a Pinia store - state fields read directly
 * (`store.diff`, `store.reviewed`), never as raw `Ref`s needing `.value`. That also
 * keeps it safe to pass through contexts that wrap values in their own `reactive()`
 * (e.g. Storybook args) without double-unwrapping.
 */
/** Full file content at the diff's base/head, for expanding a file past its hunks. */
export interface DiffsStoreFileContent {
  /** Each side is `undefined` when the file doesn't exist there (an added file's old side, a removed file's new side). */
  load: (file: FileChange) => Promise<{ old?: string, new?: string }>
}

export interface DiffsStore {
  readonly diff: DiffsPayload | undefined
  readonly grouped: GroupedResult | undefined
  readonly isLoading: boolean
  readonly error: Error | undefined
  /** Only meaningful once `diff`/`grouped` are loaded - a source with no live origin (paste) just never sets this. */
  readonly isStale: boolean
  /** The server or GitHub could not be reached, so the page shows a saved copy that may be out of date. */
  readonly isOffline?: boolean
  readonly reviewed: Set<string>
  /**
   * Paths reviewed at an earlier `sha` that later commits replaced. Only meaningful for
   * a path whose current `sha` is not in `reviewed` (see `reviewStatus`).
   */
  readonly changedSinceReviewed: Set<string>
  /** Each group's `filePaths` resolved into real `FileChange`s, `[]` until `diff`/`grouped` are both loaded. */
  readonly groups: ResolvedGroupWithChildren[]
  /** The AI (`llm`/`web-llm`) result for the current diff, locally generated or loaded from a shared comment (`sharedBy` set). */
  readonly aiResult: GroupedResult | undefined
  readonly analyzeMode: GroupSource
  /** Switches the active grouping. `none`/`rule-based` analyze immediately (free, instant); `llm` only switches the view - `llm.reanalyze` actually runs it. */
  setAnalyzeMode: (mode: GroupSource) => Promise<void>
  readonly ui: DiffsStoreUi
  /** `undefined` = LLM analysis isn't available in this environment (embed, or disabled). */
  readonly llm?: DiffsStoreLlm
  /** `undefined` = this source has no review threads (paste/local, or capability off). */
  readonly reviews?: DiffsStoreReviews
  /** `undefined` = this source has no PR comments to share into / load from. */
  readonly shared?: DiffsStoreShared
  /** The source is live (its head can move), so refetching it can bring new changes. */
  readonly canRefresh: boolean
  /** The credential a failed load can be retried with; `undefined` when none would help (a paste). */
  readonly auth?: 'github-token'
  /** `undefined` = this source can't fetch a file's full content (a paste has no live origin). */
  readonly fileContent?: DiffsStoreFileContent
  /** `undefined` = no AI review in this build (only the local build has one). */
  readonly critique?: DiffsStoreCritique
  /** `undefined` = no asking about selected lines in this build (only the local build has it). */
  readonly ask?: DiffsStoreAsk
  load: () => Promise<void>
  refresh: () => Promise<void>
  /** Marks (or unmarks) files by `sha`; either way clears their `changedSinceReviewed` flag. */
  setReviewed: (shas: string[], reviewed: boolean) => Promise<void>
}
