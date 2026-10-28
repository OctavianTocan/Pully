# ai-kit

The AI layer Pully and ThyMind share: Claude Code and Codex on each person's own subscription
sign-in, and Gemini embeddings. Review features (tours, briefs, grouping) stay in Pully and search
stays in ThyMind; this package is only the engine under them. Its rules are in
[`CONTRACTS`](CONTRACTS).

## Rust crate

| Feature            | What it adds                                                                                                                                                                                                                                             |
| ------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `agents` (default) | `login` (the CLIs' own sign-in, status, sign-out), `process` (runs, model lists, efforts; Docker on a native server, plain processes in the hosted agent container), `remote` (a Worker's way to that container), `schema`, `redact`, and the `rt` shim |
| `runner`           | The server inside the hosted agent container (native only)                                                                                                                                                                                               |
| `embed`            | Gemini `gemini-embedding-2`: query and document text, answers to unit vectors, stored vectors, cosine similarity. No I/O                                                                                                                                 |

A host names everything that is its own once, before any AI work:

```rust
static HOST: ai_kit::Config = ai_kit::Config {
    volume: "my-agents-home", // where sign-ins live; never rename it
    container_prefix: "my",
    lock_file: ".my-auth.lock",
    env: "MY_AGENT", // MY_AGENT_RUNTIME, MY_AGENT_IMAGE
    image: "my-agents",
    image_setup: "docker/agents/setup.sh build",
    container_marker: "/.my-agent-container",
    sign_in_program: None,
    tools: None, // or Some(Tools { .. }) for an MCP server the jobs may call
    missing: |_, name, _| format!("Sign in to {name} first."),
    secrets: &["MY_OWN_API_KEY"],
    preparations: &[],
    prepare: |_, schema, raw| ai_kit::config::strict(schema, raw),
};
ai_kit::configure(&HOST)?;
```

Pully's names are in [`backend/src/agents.rs`](../backend/src/agents.rs). ThyMind pins this
repository as a git submodule and depends on the crate by path.

```sh
cargo test --features runner,embed
cargo check --no-default-features --features embed
cargo check --target wasm32-unknown-unknown --features embed
```
