# maintainer guide

`.agents/` describes what the code does and why. It is a map, not a standard:
where the two disagree, the packages' `src/` and test suites win, and the docs get fixed.

## Rules that apply everywhere

- **MUST**, **MUST NOT**, **SHOULD** and **MAY** use RFC 2119 meanings. They mark
  real invariants - layer boundaries, wire contracts, output shapes - not house style.
- Vue SFCs **MUST NOT** have a `<style>` block, scoped or not. Style elements with
  UnoCSS utilities in the template. For markup the template can't put classes on
  (rendered Markdown, `v-html`), add a shortcut in `packages/app/uno.config.ts`
  built from arbitrary descendant variants (`[&_pre]:p-3`).

## Code contracts

Use the `code-contracts` skill (`.agents/skills/code-contracts/SKILL.md`) for every code
change and review: find the contracts that govern what you touch before you design the
change, keep them true, and add one for each new behaviour Tavi asks for or rejects.

- Contracts live in `CONTRACTS` files and apply to their directory and everything under
  it. The root [`CONTRACTS`](CONTRACTS) holds cross-package architecture and security;
  [`android/CONTRACTS`](android/CONTRACTS) the whole app; each
  `android/app/src/main/kotlin/com/octaviantocan/pulls/ui/<area>/CONTRACTS` one area
  (home, review, ai, design, settings, book, theme, update), with `sound/`, `update/`,
  `data/`, `android/core/` and `packages/<package>/` beside them. A rule for one TypeScript
  declaration MAY go in its doc comment instead.
- Kotlin and Vue are not parsed by `cc-check`, so their rules always go in a `CONTRACTS`
  file in the directory of the code.
- Grammar: `@cc [owner:OctavianTocan,label:<label>] <id>`, then the prose up to the next
  directive. Labels are `product`, `design`, `motion`, `architecture` and `security`,
  several joined by `;`. IDs are kebab-case, stable, and used once in their file and in
  every `CONTRACTS` file above it. Prose is plain words with RFC 2119 MUST / MUST NOT /
  SHOULD / MAY, and names its source: the recording and time, the F/H/J/K point, or the
  `android/DESIGN.md` rule. Don't copy DESIGN.md's numbers into a contract; cite the rule.
- Never weaken or delete a contract to make code pass. Change one only when the behaviour
  changes on purpose, in the same pull request, and say whose decision changed it.
- `pnpm run contracts:check` (`cc-check format`) rejects malformed `@cc` syntax and
  duplicate IDs, and runs in CI. The Android build bundles every `CONTRACTS` file into the
  APK, the UI Book's Contracts page (Settings, Advanced, UI Book) lists them by area, and
  `ContractsBundleTest` fails when one does not parse or lacks an owner or label.

## Find the contract

| Task                                                                                                                   | Read                                                                     |
| ---------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------ |
| Understand what this project is, why it exists, and its longer-term direction                                          | [00 Goal](./.agents/00-goal.md)                                          |
| Understand the overall design: provider/analyze-adapter boundaries, canonical data structures, caching, deferred scope | [01 Architecture](./.agents/01-architecture.md)                          |
| Understand a specific implementation phase's task breakdown                                                            | [plans/](./plans/)                                                       |
| Change how the Android app looks, or add to its UI Book                                                                | [android/DESIGN.md](./android/DESIGN.md), then `pnpm run android:design` |
