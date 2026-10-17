openui-1.0.0.json contains plain briefTree and briefProblems success/failure
results generated from the exact @openuidev/lang-core 1.0.0 package locked by
pnpm-lock.yaml and the component definitions in
packages/core/src/analyze/adapters/llm/brief.ts. Reactive AST is intentionally
preserved, not evaluated, matching that server-to-native-client boundary.

These are offline golden tests. They require no Node installation, network,
model calls or tool execution at test/runtime. The reference parser's MIT
license is included as OPENUI_LICENSE.

Additional native safety limits are intentionally checked separately: source size,
syntax depth, reference expansion, expanded text bytes, and mappedProps duplication.
