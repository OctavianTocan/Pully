---
name: tests
title: Tests
description: Changed behaviour that no test would catch, and tests that cannot fail.
---

# Tests review

Find the behaviour this change adds or changes that no test would catch if it broke tomorrow, and the
tests that pass without proving anything. Each finding names the behaviour and the test that should
exist: the file it belongs in, the input and the expected result.

## How to work

1. List what the change does that someone relies on: a function's return value for each kind of input,
   an error it throws, an output shape (JSON, a prompt, a command line), a state transition.
2. Find the tests for it through the tools. In this kind of repository they sit next to the code:
   `foo.ts` and `foo.test.ts` (vitest), and JVM tests under `src/test` for Kotlin.
3. For each behaviour, decide whether an existing test would fail if the new code were wrong. A test
   that only runs the code, or that asserts on a mock it set up itself, does not count.

## What to look for

- New branches with no test: the error path, the empty input, the fallback, the second page.
- Pure functions that build something exact (arguments for a CLI, a prompt, a URL, a query, a file
  path) with no test that pins the output. These are cheap to test and costly to break.
- Changed behaviour whose old test was updated to match the new output without checking that the new
  output is right.
- Tests that cannot fail: assertions inside a callback that never runs, `expect` on a promise that is
  not awaited, `toBeTruthy` on an object, snapshots nobody reads.
- Tests coupled to timing (`setTimeout`, real clocks, sleeps) where fake timers would make them exact,
  and tests that depend on the order they run in or on a shared temp folder.
- Mocks that replace the very thing under test, or mock the network so loosely that a wrong request
  still passes.
- Removed tests with no reason given, and `.skip` or `.only` left behind.
- Kotlin: logic placed inside a composable where it cannot be unit-tested, when a plain function or a
  `ViewModel` would let a JVM test cover it.

## Severity

- **bug**: a test that claims to cover something and cannot fail, or a removed test that guarded
  behaviour that is still there.
- **risk**: important new behaviour (data written, money, security, a wire format) with no test.
- **nit**: a cheap test worth adding for a smaller branch.

Anchor each finding on the changed lines that lack the test, not on the test file, unless the test
itself is wrong. Do not ask for tests of trivial getters, generated code or pure layout.
