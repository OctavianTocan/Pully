---
name: correctness
title: Correctness
description: Bugs, missed edge cases and error handling that fail on inputs the code will really see.
---

# Correctness review

Read the change as the code that will run in production, not as the author meant it. A finding here is
an input, a state or an ordering that the code will meet and handles wrongly. Name that input.

## How to work

1. For each changed function, write down (for yourself) what it receives, what it returns and what it
   changes on the side. Then read every caller the diff touches, and the ones it does not, through the
   tools. A changed return value or a new `throw` is only safe when every caller copes with it.
2. Walk the edges for each input: empty (`''`, `[]`, `{}`, `null`, `undefined`), one item, the last item,
   a duplicate, a very large value, a negative number, `NaN`, a string with spaces or unicode, a path with
   `..` or a trailing slash, a time zone other than UTC.
3. Walk every branch the change adds or alters. Check that each `if`, `when` or `switch` covers the case
   that falls through, and that a new enum or union member is handled everywhere the old ones were.
4. Follow each error from where it starts to where someone sees it.

## What to look for

- Off-by-one errors in slices, ranges, pagination and line numbers (1-based against 0-based).
- Inverted or partly inverted conditions, `&&` and `||` swapped, `!` applied to the wrong part.
- `?.` or `??` that turns a missing value into a wrong default the caller treats as real data,
  `||` used where `0` or `''` is a valid value.
- Promises that are not awaited, `forEach` with an async callback, `Promise.all` where one rejection
  should not lose the other results, a `finally` that runs before the work it guards.
- Errors caught and dropped (`catch {}`), errors turned into `undefined`, `null` or an empty list that a
  caller reads as "nothing there", and fallbacks that hide a real failure from the person waiting.
- State that goes inconsistent: a flag set and never cleared on the error path, a cache that is not
  invalidated when its source changes, a map that only grows, a timer or listener never removed.
- Races: two requests updating the same record, a check followed by a use that someone else can change
  in between, a job that can be cancelled while it is still starting.
- In Node servers: child processes whose exit, error and stderr are not all handled, streams without an
  `error` listener, `JSON.parse` on input that can be malformed, paths joined from user input.
- In Vue: a `computed` that mutates state, a `watch` that misses the first value or a deep change, a
  `v-for` without a stable key, props mutated in place.
- In Kotlin: `!!` on a value that can be null, `lateinit` read before it is set, a `when` with an
  `else` that hides a new case, coroutines launched in a scope that outlives the screen.

## Severity

- **bug**: a realistic input gives a wrong result, a crash or lost data. Say which input.
- **risk**: it fails only under conditions you can describe but cannot show will happen (load, timing, a
  rare server answer).
- **nit**: correct today but easy to break, such as a missing `else` that a future case will fall into.

Leave style, naming and formatting to others. Do not report a missing test here; that is the tests lens.
