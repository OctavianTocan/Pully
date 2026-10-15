---
name: android-compose
title: Android and Compose
description: Compose state, recomposition, lifecycle and threading mistakes in Android code.
---

# Android and Jetpack Compose review

Review the Kotlin and Compose code in this change as it behaves on a real phone: rotated, sent to the
background, killed and restored, on a slow network, with the system font scaled up. If the change has
no Android code, say so in the summary and report nothing from this lens.

## How to work

1. For each composable the change touches, list the state it reads, where that state lives and what
   writes it. Then picture the screen recomposing, rotating and coming back from the background.
2. For each coroutine, find its scope and dispatcher, and what happens to it when the screen leaves.
3. For each value kept across recompositions or screens, check what it is keyed on and when it resets.

## What to look for

- State: `mutableStateOf` created without `remember`, so it resets on every recomposition; `remember`
  where `rememberSaveable` is needed to survive rotation or process death; `remember` without the keys
  that should reset it; state copied into a local `var` and then edited as if it were shared; mutable
  collections (`ArrayList`, `HashMap`) put in state, so changes are not seen.
- Recomposition: state read at the top of a large composable so all of it recomposes on each change;
  reading a fast-changing value (scroll offset, animation) in composition instead of in a lambda or
  `Modifier` that defers it; unstable parameters (plain `List`, lambdas capturing changing values) that
  stop skipping; missing `key` on `LazyColumn` or `LazyRow` items, which mixes up item state.
- Side effects: work done in the composable body instead of `LaunchedEffect`, `DisposableEffect` or an
  event callback; `LaunchedEffect(Unit)` that should restart when an input changes, or one keyed on a
  value that changes every frame; listeners and callbacks registered without `onDispose`; stale values
  captured in a long-running effect where `rememberUpdatedState` is needed.
- Lifecycle: coroutines in `GlobalScope` or a scope that outlives the screen; collecting flows without
  `collectAsStateWithLifecycle` or `repeatOnLifecycle`, so work continues in the background; a
  `ViewModel` holding an `Activity`, `Context` or view; work that is lost on process death.
- Threading: network, disk or JSON parsing on `Dispatchers.Main`; state written from a background
  thread outside a snapshot-safe path; `runBlocking` anywhere in UI code; shared mutable objects without
  synchronisation.
- Navigation and back: a back press that leaves a sheet or dialog in a broken state, a result delivered
  twice, a screen that reloads everything when you return to it.
- Text and layout: fixed heights that cut text when the font scale grows, text truncated where it must be
  read whole, touch targets under 48dp, content drawn under the system bars or the keyboard without
  insets.
- Accessibility: clickable things without a role or a label, icons without a content description when
  they carry meaning, colour as the only signal.
- Resources and APIs: calls to APIs above `minSdk` without a version check, permissions used without a
  runtime request, strings hard-coded where the app is translated.

## Severity

- **bug**: wrong behaviour you can reproduce: state lost on rotation, a crash, a leak, a frozen frame.
- **risk**: it goes wrong only on some devices, sizes, speeds or after process death.
- **nit**: a cheaper or clearer Compose pattern with a real benefit.

Ignore formatting and naming.
