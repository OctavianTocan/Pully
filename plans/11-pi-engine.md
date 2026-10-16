# Plan 11: Pi, our own agent harness for any model

> Historical implementation plan. The superseded TypeScript server and Node-only local provider were removed in the Rust cutover. See [the current architecture](../.agents/01-architecture.md) and [migration notes](../docs/RUST_BACKEND_MIGRATION.md); source paths below describe the earlier design.

Status: **step 1 built** (the `pi` engine, opt-in). Claude Code stays the default.

## Why

Every AI feature on the server ran through the Claude Code or Codex CLI. That ties
Pulls to two vendors' tools, their release cadence and their quirks (a Codex
sandbox that needs namespaces, Claude's stream format, sign-ins that only a
terminal can do). Tavi runs CLIProxyAPI, which serves Claude, GPT, Gemini and
others through one OpenAI-compatible API on his own subscriptions. With an agent
loop of our own in front of it, Pulls can use any of those models and decide for
itself what the model may do.

## What the CLIs actually do for us

Less than it looks. Every server task already reduces to one call,
`runAiJob(request)` in `packages/cli/src/ai/jobs.ts`:

| Task                                             | How it reaches a model                                                   |
| ------------------------------------------------ | ------------------------------------------------------------------------ |
| Analyze, Chat                                    | core's pi-agent-core loop; each turn is one `runAiJob` (`cli-stream.ts`) |
| Review (critique, with a lens), Tour, Brief, Ask | one `runAiJob` with a system prompt, a prompt and often a JSON schema    |
| Ask about the inbox, follow-up questions         | the same                                                                 |

Inside a job the CLI is used as nothing more than a model with tools:

- Claude Code runs with `--tools ''` and only our MCP server allowed. Codex runs
  without its shell in Docker. Neither may touch files or the network.
- The tools are ours: `pr-tools.ts` (read a file's diff, hunks, source at the
  head commit, find files, search the diff or the code, read the discussion) and
  `inbox-tools.ts` (read a listed pull request and its diff). `pr-server.ts`
  only wraps them in MCP.
- Review lenses (the six skills) are text the server puts into the prompt.
- A schema answer comes back through `--json-schema` or `--output-schema`.

So a harness needs: a tool-calling loop, our tools, a structured answer, the
work log the phone shows, usage, limits and cancelling. No shell, no files, no
skills runtime, no MCP.

## Pi covers the loop

Pulls already depends on Pi's libraries: `@earendil-works/pi-ai` (one API over
OpenAI-compatible, Anthropic and other endpoints) and
`@earendil-works/pi-agent-core` (the agent loop with tool calls, argument
validation, steering and follow-ups). The analysis and the chat run on them today.
The `pi-coding-agent` CLI is not needed: it brings a shell, file editing and
sessions, which are the parts we want to leave out.

## How CLIProxyAPI plugs in

CLIProxyAPI listens on port 8317 and serves `/v1/models` and
`/v1/chat/completions`, authenticated by one of the `api-keys` in its config. The
server reads three variables, never anything from a client:

| Variable                   | Example                    | Required                           |
| -------------------------- | -------------------------- | ---------------------------------- |
| `PULLS_REVIEW_PI_BASE_URL` | `http://127.0.0.1:8317/v1` | yes, Pi is off without it          |
| `PULLS_REVIEW_PI_API_KEY`  | one of CLIProxyAPI's keys  | when the endpoint wants one        |
| `PULLS_REVIEW_PI_MODEL`    | `claude-opus-5-5`          | no; the first listed model is used |

Any other OpenAI-compatible endpoint works the same way (OpenRouter, a local
llama.cpp or vLLM).

## Step 1: the `pi` engine (built)

- `LLM_ENGINES` gains `pi`, so every task, the RPC validation, the phone and the
  web page accept it. Nothing changes for `claude-code` or `codex`.
- `packages/cli/src/ai/pi.ts` runs a job in the server process with
  `runAgentLoop`: the system prompt, the prompt, and `piTools()` built from the
  same tool specs and handlers the MCP server serves. A schema answer comes
  through a `submit_answer` tool whose parameters are the schema, so pi validates
  it and the model retries on a bad answer. The loop's events become the same
  work-log entries the CLI parsers produce (Thinking, Writing, Read …), and its
  usage the same `AiUsage`.
- Limits mirror the CLIs': the job queue (three at a time), the idle and total
  watchdogs, a nudge to answer after 40 turns or 400k characters read, a hard stop
  at 50 turns. Cancelling aborts the loop.
- `ai-models` for `pi` lists the endpoint's `/v1/models`, named the way vendors
  write them ("Claude Opus 5.5", "GPT-5.5 Codex") and grouped by family. Every
  model offers low, medium and high effort, sent as `reasoning_effort`; with none
  picked nothing is sent.
- The phone's model picker has a third tab, Pi, and folds older models per
  family. The web page's engine toggle has Pi too.
- Pi runs no process and no container, so `PULLS_REVIEW_AGENT_RUNTIME` does not
  apply to it. Its tools read the server's checkout through git, as the MCP server
  does.

## Later steps (not built)

1. **Run analysis and chat turns directly.** Today each turn of the analysis loop
   is a nested `runAiJob`, as for the CLIs. With `pi` the outer loop could call
   the model itself and give it the `pr` tools next to `read_diffs`, which saves
   a model call per turn.
2. **Anthropic Messages for Claude models.** CLIProxyAPI also serves
   `/v1/messages`. Claude through it keeps its thinking blocks and cache control
   exactly; pi-ai already speaks that API, so this is a per-model switch.
3. **A small model for follow-up questions.** Claude Code uses Haiku for them; Pi
   uses the default model. A `PULLS_REVIEW_PI_SMALL_MODEL` would fix that.
4. **Make Pi the default** once it has run on the phone for a while. Then the
   Docker image and the CLI sign-ins become optional.

## Risks

- **Context.** The CLIs compact a long transcript; this loop does not. The read
  budget and turn limit keep a run inside 200k tokens, and the tools already cap
  each answer.
- **Model differences.** Tool calling through a proxy depends on the proxy's
  translation for each vendor. The work log shows each step, and a model that
  never calls `submit_answer` fails with that message rather than a bad result.
- **Effort.** The endpoint does not say which models reason, so every model
  offers efforts; a model that cannot reason ignores or rejects
  `reasoning_effort`, and the error says so.
- **The key.** It stays in the server's environment (a systemd drop-in). The
  phone and the page never see it, and it is never stored in the repository.
