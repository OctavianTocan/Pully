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
| `worker`           | `home`: the `AgentHome` Durable Object that owns each login's container, for a host built as a Cloudflare Worker (wasm only)                                                                                                                              |
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
cargo check --target wasm32-unknown-unknown --features worker,embed
```

## Kotlin module

`kotlin/` is a Kotlin Multiplatform library (JVM and iOS, no Android plugin and no Compose) with
the app half of the same engine, in `com.octaviantocan.aikit`:

- `AiSignIns.kt`: the two CLIs, the server's sign-in answers, Claude Code's pasted-code check, the
  `AiSignInServices` an app implements, which step a sign-in page is at, and the wait for Codex.
- `CliCatalog.kt`: a CLI's model list, the effort a model switch keeps, and effort labels.
- `ModelSelection.kt`: which models a picker shows first, Pi's provider sections, and model names.

Each app draws the pages in its own look on these. A Gradle build includes it as a project:

```kotlin
include(":ai-kit")
project(":ai-kit").projectDir = file("../ai-kit/kotlin")
```

The including build declares the Kotlin Multiplatform plugin (Pully's `mobile/build.gradle.kts`).
An Android app consumes the JVM variant, as Pully's `android/core` does.

```sh
mobile/gradlew -p mobile :ai-kit:jvmTest
```
