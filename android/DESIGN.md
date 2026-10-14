---
version: alpha
name: Pulls Review for Android
description: >-
  The Android app's design system. Warm paper with deep teal ink and one teal accent, Geist for
  text, Mona Sans for numbers, Geist Mono for code. White floating surfaces with a soft ink
  shadow, pill capsules, paper bottom sheets, a dark toast. Dimensions are written in px because
  the format needs a unit: read each as dp, and font sizes as sp. This front matter is the source
  of truth. android/scripts/design checks the Kotlin tokens against it and writes the UI Book's
  spec.json from it.
colors:
  base: "#FDFCF9"
  text: "#0E333A"
  muted: "#6E8285"
  faint: "#93A2A4"
  border: "#E3E5E3"
  border-mute: "#ECEBE7"
  hover: "#0E333A12"
  active: "#0E333A1A"
  raised: "#FFFFFF"
  sunken: "#F1F0EC"
  code: "#F4F2EF"
  secondary: "#F4F2EF"
  primary: "#1B7580"
  primary-600: "#165E66"
  active-text: "#1E6F78"
  success: "#1C8A5A"
  error: "#C94F55"
  warning: "#D9822B"
  blue: "#3B82F6"
  purple: "#A855F7"
  gray: "#93A2A4"
  orange: "#EA580C"
  amber: "#B45309"
  accent-teal: "#24929C"
  accent-orange: "#D9552A"
  accent-magenta: "#9C3A95"
  paper: "#F4F2EF"
  paper-2: "#F1F0EC"
  cta: "#13343B"
  on-cta: "#FFFFFF"
  chip-on: "#E9E8E3"
  accent-wash: "#E6F1F2"
  accent-wash-ink: "#165E66"
  diff-add: "#CFE6E3"
  diff-del: "#F6D7D0"
  diff-add-ink: "#1E7E8A"
  diff-del-ink: "#B4533F"
  diff-add-mark: "#A8D6D0"
  diff-del-mark: "#EDB5AA"
  shadow: "#0E333A"
  scrim: "#0E333A61"
  dark-base: "#0C1B1F"
  dark-text: "#E4EEEF"
  dark-muted: "#8FA6A9"
  dark-faint: "#6B8286"
  dark-border: "#263A3E"
  dark-border-mute: "#1B2D31"
  dark-hover: "#FFFFFF14"
  dark-active: "#FFFFFF1F"
  dark-raised: "#13282C"
  dark-sunken: "#173035"
  dark-code: "#112429"
  dark-secondary: "#112429"
  dark-primary: "#1B7580"
  dark-primary-600: "#165E66"
  dark-active-text: "#6CC3CB"
  dark-success: "#3CC98F"
  dark-error: "#E8777C"
  dark-warning: "#F2A14B"
  dark-blue: "#60A5FA"
  dark-purple: "#C084FC"
  dark-gray: "#6B8286"
  dark-orange: "#FB923C"
  dark-amber: "#FBBF24"
  dark-accent-teal: "#4FB7C1"
  dark-accent-orange: "#F57A4F"
  dark-accent-magenta: "#C65FBF"
  dark-paper: "#112429"
  dark-paper-2: "#173035"
  dark-cta: "#E4EEEF"
  dark-on-cta: "#0C1B1F"
  dark-chip-on: "#1F3A3F"
  dark-accent-wash: "#14363B"
  dark-accent-wash-ink: "#9AD3D6"
  dark-diff-add: "#123B3A"
  dark-diff-del: "#3F2420"
  dark-diff-add-ink: "#6CC3CB"
  dark-diff-del-ink: "#E08A76"
  dark-diff-add-mark: "#1C5A58"
  dark-diff-del-mark: "#6A3A32"
  dark-shadow: "#000000"
  dark-scrim: "#00000099"
typography:
  xs:
    fontFamily: Geist
    fontSize: 13px
    fontWeight: 400
    lineHeight: 18px
  xs-wide:
    fontFamily: Geist
    fontSize: 12px
    fontWeight: 400
    lineHeight: 16px
  sm:
    fontFamily: Geist
    fontSize: 15px
    fontWeight: 400
    lineHeight: 21px
  sm-wide:
    fontFamily: Geist
    fontSize: 14px
    fontWeight: 400
    lineHeight: 20px
  base:
    fontFamily: Geist
    fontSize: 16px
    fontWeight: 400
    lineHeight: 22px
  lg:
    fontFamily: Geist
    fontSize: 18px
    fontWeight: 400
    lineHeight: 26px
  xl:
    fontFamily: Geist
    fontSize: 20px
    fontWeight: 500
    lineHeight: 26px
  xxl:
    fontFamily: Geist
    fontSize: 26px
    fontWeight: 500
    lineHeight: 32px
    letterSpacing: -0.26px
  body:
    fontFamily: Geist
    fontSize: 17px
    fontWeight: 400
    lineHeight: 25px
  read:
    fontFamily: Geist
    fontSize: 16px
    fontWeight: 400
    lineHeight: 24px
  row:
    fontFamily: Geist
    fontSize: 17px
    fontWeight: 400
    lineHeight: 22px
  num:
    fontFamily: Mona Sans
    fontSize: 15px
    fontWeight: 600
    lineHeight: 22px
  micro:
    fontFamily: Geist
    fontSize: 10px
    fontWeight: 400
    lineHeight: 14px
  mini:
    fontFamily: Geist
    fontSize: 11px
    fontWeight: 400
    lineHeight: 16px
  compact:
    fontFamily: Geist
    fontSize: 12px
    fontWeight: 400
    lineHeight: 18px
rounded:
  xs: 2px
  sm: 4px
  md: 6px
  lg: 8px
  xl: 12px
  card: 16px
  sheet: 24px
  full: 9999px
spacing:
  1: 4px
  2: 8px
  3: 12px
  4: 16px
  6: 24px
  8: 32px
motion:
  tick: 150ms
  state: 200ms
  slide: 250ms cubic-bezier(0.22, 1, 0.36, 1)
  toast: 350ms in, 250ms out
  swap: 150ms ease-in-out
  progress: 400ms
  press: scale 0.97
  breathe: 1200ms reverse loop
  orb-turn: 1600ms linear loop
  orb-breath: 900ms reverse loop
  shimmer: 1500ms linear loop
  thinking-shimmer: 1200ms linear loop
elevation:
  button: 10px
  note: 12px
  bar: 14px
  search: 16px
  menu: 20px
  thumb: 3px
  modal: 40px
components:
  float-button:
    backgroundColor: "{colors.raised}"
    textColor: "{colors.text}"
    rounded: "{rounded.full}"
    size: 44px
  capsule-tabs:
    backgroundColor: "{colors.raised}"
    textColor: "{colors.text}"
    typography: "{typography.sm}"
    rounded: "{rounded.full}"
    height: 44px
    padding: 2px
  soft-chip:
    backgroundColor: "{colors.chip-on}"
    textColor: "{colors.text}"
    typography: "{typography.sm}"
    rounded: "{rounded.full}"
    height: 36px
    padding: 0px 14px
  underline-tabs:
    textColor: "{colors.text}"
    typography: "{typography.base}"
    height: 42px
  check:
    backgroundColor: "{colors.primary}"
    rounded: 7px
    size: 22px
  switch:
    backgroundColor: "{colors.primary}"
    rounded: "{rounded.full}"
    width: 52px
    height: 32px
  cta-button:
    backgroundColor: "{colors.cta}"
    textColor: "{colors.on-cta}"
    typography: "{typography.row}"
    rounded: "{rounded.full}"
    height: 52px
    padding: 0px 24px
  pill-button:
    backgroundColor: "{colors.paper-2}"
    textColor: "{colors.text}"
    typography: "{typography.row}"
    rounded: "{rounded.full}"
    height: 44px
    padding: 0px 16px
  pill-input:
    backgroundColor: "{colors.raised}"
    textColor: "{colors.text}"
    typography: "{typography.base}"
    rounded: "{rounded.full}"
    height: 52px
  sheet-field:
    textColor: "{colors.text}"
    typography: "{typography.row}"
    padding: 10px 16px
  sheet:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.text}"
    rounded: "{rounded.sheet}"
    padding: 0px 16px
  sheet-card:
    backgroundColor: "{colors.raised}"
    rounded: "{rounded.card}"
  sheet-row:
    textColor: "{colors.text}"
    typography: "{typography.row}"
    height: 52px
    padding: 0px 16px
  quick-action:
    backgroundColor: "{colors.raised}"
    textColor: "{colors.text}"
    typography: "{typography.sm}"
    rounded: "{rounded.card}"
    height: 72px
  toast:
    backgroundColor: "{colors.cta}"
    textColor: "{colors.on-cta}"
    typography: "{typography.sm}"
    rounded: "{rounded.full}"
    height: 44px
    padding: 0px 18px
  orb:
    backgroundColor: "{colors.accent-wash}"
    rounded: "{rounded.full}"
    size: 30px
  progress-ring:
    rounded: "{rounded.full}"
    size: 30px
  breathing-dot:
    backgroundColor: "{colors.accent-teal}"
    rounded: "{rounded.full}"
    size: 10px
  pending-dot:
    rounded: "{rounded.full}"
    size: 10px
  ok-dot:
    backgroundColor: "{colors.success}"
    rounded: "{rounded.full}"
    size: 14px
  skeleton:
    backgroundColor: "{colors.paper-2}"
    rounded: "{rounded.md}"
    height: 12px
  profile-dot:
    rounded: "{rounded.full}"
    size: 40px
---

## Overview

Pulls Review for Android is a phone app for reading pull requests: an inbox of what is waiting on you, a PR overview with a summary and a description, the diff, and a tour that walks a change in sections. It should feel calm and fast to scan. The diff is the product, so chrome stays flat and quiet, and nothing is on a screen unless it tells the reader something (rule 1).

The look comes from the Android prototype: warm paper, deep teal ink, one teal accent, white floating surfaces with a soft shadow, pill capsules, paper bottom sheets and a dark toast. Tavi's design canvas holds the reference rows (Row W is the latest). The canvas predates the code, so this file records what the Kotlin renders, and the rules below record how Tavi judges it.

The source speaks two visual dialects as of version 0.6.1. The prototype dialect (`ui/design/Kit.kt` and the `Lu` icons) is on Inbox, Tours, Overview, Diff, Tour and their sheets. The older web-port dialect (`ui/design/Components.kt`, Phosphor icons, 1dp borders, 4 to 8dp radii, 13sp meta) is on the repository list, Settings, Checkout, the command palette, the conversation and the review dialogs. Rule 49 says only the first may grow. The web-port kit is frozen: new code does not use it, and the bans check counts what is left so that number can only go down.

Dimensions in this file are written in `px` because the format needs a unit. Read every dimension as dp, and every font size as sp.

This file is the spec. The Kotlin tokens are checked against the front matter (`pnpm run android:design`), the in-app UI Book (Settings, then UI Book) renders the real components next to the values declared here, and the lints below fail when new code goes around it.

## Colors

One palette, two themes. `base` is the screen. `paper` and `secondary` are sheets and grouped backgrounds. `raised` is white cards and floating buttons, and `sunken` holds segment tracks. Ink is `text`; `muted` and `faint` are the two quieter steps, and text never fades further (rule 14).

The accent is one teal. `primary` fills checked boxes and switches, `accent-teal` is the brighter teal for progress, live dots and selection bars, and `active-text` colours links and active text. `cta` is the dark fill of the one main action on a screen. `success`, `error` and `warning` carry status only, and the `diff-*` colours belong to the diff.

Dark mode is derived from the light set (`dark-*`). Every colour has a dark pair, and the tokens check fails on a colour without one. A screen is not done until it is right in both themes.

The Kotlin names are the properties of `PullsColors` in `Tokens.kt`. A name here is that property in kebab-case: `border-mute` is `borderMute`, `primary-600` is `primary600`. Alpha is the last pair in this file and the first in Kotlin.

## Typography

Geist carries interface text, Mona Sans carries numbers and file names, and Geist Mono carries code and branch names. Sizes come from `PullsType`. `xs` and `sm` step down one notch from 768dp up, so each has a `-wide` twin in the front matter. `xxl` is a page title (26, rule 12), `xl` a section heading, `lg` a sheet title, `row` and `body` a row title and body copy, `read` the reading size of summaries and tour text, `base` inputs, `sm` lists and meta.

Rule 14 sets the floor at 15 and bans uppercase and tracked labels. Phone meta today is partly below that: `xs` is 13, and `compact`, `mini` and `micro` are 12, 11 and 10. The raw-values check counts every use of those roles so the number can only fall. Letter-spacing exists only in `xxl`. No uppercase labels, no eyebrows.

## Layout

Phone first. The render size for a check is 390 by 844 (rule 25), and nothing sits inside the 16dp margin except full-bleed bands such as the diff and sheet backgrounds. The kit's touch target is 44dp (`TOUCH`): round buttons and capsules are 44, chips are 36, CTA buttons 52, sheet rows 52 and quick actions 72.

Spacing steps are 4, 8, 12, 16, 24 and 32. The code also uses 6, 10, 14 and 18 in places, which the scale does not list, and the checks do not count them. From 768dp up (a folded phone opened), `xs` and `sm` step down one notch and modals become centered dialogs. A sub-page keeps its context: the PR title and number stay in its header (rule 23).

## Elevation & Depth

Floating things carry the canvas shadow from Row W, drawn by `floatShadow`: a soft ink drop two pixels down (`0 2px 10px` at 8% for a header circle or capsule), never an even glow all round. Compose's own elevation cannot offset a shadow, which is why it looked faint and flat on the phone, so `floatShadow` draws the drop itself from API 28 and falls back to a plain elevation below that. It comes in seven levels, named for what carries them: `button` (10px blur, 8%), `note` (12px, 10%, cards and quick actions), `bar` (14px, 12%, the bottom and comment bars), `search` (16px, 12%, the search pill and the Ask bar), `menu` (20px, 14%, four pixels down, for popovers), `thumb` (3px, 20%, the switch thumb) and `modal` (40px, 22%, a centered dialog). The `elevation` tokens are those blurs in dp. In dark mode the strength is multiplied by 2.7 so the drop still reads on the dark paper. A missing or different shadow on a floating element is a defect (rule 45).

The canvas shadows are one soft layer pushed down, in the ink colour `rgba(14,51,58)` (the sheet's is black): the round header buttons and the mode capsule `0 2px 10px` at 8%, the search pill `0 2px 16px` at 12%, the bottom toolbar `0 2px 14px` at 12%, a note card `0 2px 12px` at 10%, and the bottom sheet `0 -4px 30px rgba(0,0,0,.18)`. `floatShadow` draws it: one soft layer pushed down, at a level for each of those elements (rule 45 and the Decision log).

Everything beneath a sheet dims uniformly through `scrim`, the header and the trigger included (rule 5). Depth comes from the shadow and the scrim. A border or a fill alone never lifts a surface.

## Shapes

Radii are 2 (the sheet grabber), 4, 6, 8, 12, 16 (cards), 24 (sheet corners) and full for pills. Buttons, capsules, chips and switches are pills. The checkbox corner is 32% of its size, which is 7 at 22dp. Nested corners are concentric: inner is max(0, outer minus inset minus border).

Code reads radii and spacing from `PullsRadius` and `PullsSpace` in `ui/design/Scale.kt`, and the tokens check compares them with this file. A raw `RoundedCornerShape(12.dp)` in new code fails the raw-values check.

## Components

The kit lives in `ui/design`. Use it before writing anything new. A new shared component lands with its UI Book page in `book.json` in the same change. Each entry below is a `components` token that the UI Book renders in a `DesignSpecimen`. `size`, `width` and `height` are exact and the book measures them on the phone. `padding` and `rounded` are declared and read from the source.

- `float-button`: the 44dp circle that floats on the page with its own shadow. The header buttons are this.
- `capsule-tabs`: a white pill of equal segments with a sliding paper pill under the selected one. Inbox and Tours.
- `soft-chip`: a 36dp outlined pill that fills when selected, with a muted count.
- `underline-tabs`: text tabs on a hairline with the selected one underlined in the accent. Tabs inside a page (rules 28 and 38).
- `check`: a 22dp box, accent when on.
- `switch`: a 52 by 32 switch.
- `cta-button`: the 52dp dark pill that carries the screen's one main action (rule 7: never an irreversible one).
- `pill-button`: the quiet 44dp paper pill for the actions beside it (Retry, Stop, Rewrite).
- `pill-input`: the 52dp raised pill that holds a one-line question or comment, its send circle inside at the right.
- `sheet`, `sheet-card`, `sheet-row`, `sheet-field`, `quick-action`: the bottom sheet and what goes in it. A field in a sheet is a `sheet-field` in the white card, never a bordered input.
- `toast`: one dark pill above the bottom bars, with an optional action such as Undo.
- `orb`, `progress-ring`, `breathing-dot`, `pending-dot`, `ok-dot`: AI and progress marks (rules 18 and 43).
- `skeleton`: a shimmering bar for content that is still arriving (rule 51).
- `profile-dot`: the avatar placeholder.

The web-port kit (`ActionButton`, `IconButton`, `ToggleGroup`, `TextInput`, `Badge`, `Card`, `ReviewCheckbox`, `Loading`, `EmptyState` and the Phosphor `PhIcon`) is not in the token list on purpose. It is frozen by rule 49, and the UI Book shows it under Legacy kit with the files that still use it.

## Do's and Don'ts

- Do give every element a fact to carry, and every button a word for its effect (rules 1 and 6).
- Do keep the reviewing action at the bottom and irreversible ones behind the ⋯ sheet (rules 7 and 29).
- Do use a sheet over the page for grouped actions, chat and files (rule 5).
- Do show what the app already knows at once, and use skeletons for the rest (rules 50 and 51).
- Do check a screen in light, dark and at 390 by 844 before showing it (rules 25 and 44).
- Don't use stat tiles, KPI cards or a run-on sentence of counts (rules 8 and 22).
- Don't add avatars on list rows, initials, a FAB, voice input, Share on a PR or tour, "Later" as a row action, numbered pips or step counters (rules 1, 11 and 33).
- Don't use a Material widget where the kit has a component, and don't draw an icon by hand (rules 37 and 48).
- Don't write a raw hex colour, font size or radius in a composable.
- Don't show a loading screen or a refresh indicator (rules 51 and 52).
- Don't use uppercase or tracked labels, and don't set text under 15 (rule 14).

## Motion

The `motion` tokens record what the kit uses today. It animates with `tween`. Durations in use: 150ms for a check filling, 200ms for chips, switches and tab ink, 250ms with the smooth-out curve for the capsule's sliding pill, 350ms in and 250ms out for the toast and 400ms for the progress ring. Live marks loop: the breathing dot at 1200ms, the orb's ring at 1600ms linear and its bot at 900ms, the skeleton shimmer at 1500ms.

Every pressable scales to 0.97 on pointer-down and answers with no delay. AI work in progress is a live, animated mark and never a blank (rules 18 and 43). A swiped row blurs slightly as it is displaced (rules 17, 32 and 46): the canvas row moves about 132 to 136dp with a 1.3px blur and a side shadow, `-8px 0 18px` at 14% one way and `8px 0 18px` at 16% the other. A segmented pill's highlight slides to the tapped segment and the content fades in (rule 67). Anything that exists in both states of a screen keeps its position (rule 69). Move things with transform and opacity.

Rule 73 gives the numbers motion is moving to, taken from transitions.dev: 150ms for closes and text swaps, 250ms for opens and tabs, 400ms for panels, one smooth-out curve `cubic-bezier(0.22, 1, 0.36, 1)`, 2px of blur on small things. Menus (`MenuReveal`), tab and page switches (`BlurSwitch`), the capsule's sliding pill, the toast and the thinking line (`ThinkingSwap`) use them, and so now do icon swaps (`IconSwap`: 250ms, the old glyph shrinks to a quarter and blurs 2px away as the new one grows in), the dialog (`ModalReveal`: 250ms in from 0.96, 150ms out), the checkbox (the box fills in 150ms, then the tick is drawn in 350ms) and the content of a bottom sheet (`panelReveal`: 400ms fade through a 2px blur). The sheet's own slide and its closing stay Material's `ModalBottomSheet`, whose timing the kit cannot set; replacing it would need a sheet of our own. Looping effects, the menus, tab and page switches, the thinking line, icon swaps, the dialog and the sheet content hold still when the system setting "Remove animations" is on (`rememberMotionEnabled`); the older tweens (chips, switches, tab ink, the capsule's pill, the toast, the checkbox and the progress ring) do not check it themselves.

## Rules

Rule numbers are one running list, so "rule 47" is unambiguous. Rules 1 to 44 come from the design canvas (Row W is the latest row) and are copied word for word from its rule notes, which Tavi wrote from his earlier recordings. Rules 45 to 65 were written from Tavi's phone walkthrough of the built app on 2026-10-14, one rule for each thing said, so each applies to screens that were not mentioned. Rules 66 to 70 come from Tavi's review of the Perplexity mobile app on 2026-10-13, kept as a reference: 66 to 70 are rules (70 was confirmed on 2026-10-14). Rules 71 to 73 come from Tavi's answers of 2026-10-14: bars beside a live indicator, the thinking text, and the motion numbers.

Each rule ends with where it is checked. `review` means a person looks, against the checklist below. The other names are scripts in `android/scripts/design` and are explained under Enforcement. The `rules` check fails when a rule has no such line, names a check that does not exist, or a check names a rule that does not list it.

### Rules 1 to 12, from the L, M and N recordings

#### 1. Information first, decoration never.

An element earns pixels by telling the reader something. Initial avatars, doc thumbnails, row glyphs, FAB, Share: cut. A real author photo is information; an initial is not.

Source: canvas. Checked by: bans, review.

#### 2. Complete identifiers, never truncated.

PR titles wrap, never ellipsise. Repo + number always scannable. Branch means both ends: head → base. Diffstat always coloured.

Source: canvas. Checked by: review.

#### 3. Dense but calm meta.

All the facts (number, author, diffstat, checks, age) but never one cluttered line. Group, align, give variable fields fixed width.

Source: canvas. Checked by: review.

#### 4. Lists are the backbone.

Rows grouped by repo, chips for scope. Tappable rows look tappable (chevron, pressed state). Swipe actions are the fast path. No explanatory copy or empty-state prose under a populated list.

Source: canvas. Checked by: review.

#### 5. Sheets over the page, not new pages.

Bottom sheets for grouped actions, chat, composer, files. Dim everything beneath, uniformly, including the header and the trigger.

Source: canvas. Checked by: bans, review.

#### 6. Words mean what they do.

Sections, not stops. Buttons name their effect. Cites say where they lead and are tappable. Every paragraph is labelled (Summary / Description).

Source: canvas. Checked by: review.

#### 7. Irreversible actions are never primary.

Merge lives behind the ⋯ menu. The bottom primary is the reviewing action (Review 4 files, Next).

Source: canvas. Checked by: review.

#### 8. No cards for numbers.

Stat tiles / KPI cards are ugly. No boxed mini-widgets; typographic hierarchy on a flat page.

Source: canvas. Checked by: bans, review.

#### 9. The diff is the product.

Full-width lines, hunk expanders, word highlights, line numbers. Add/remove backgrounds clearly visible. Inline thread cards on their line. Files via a drawer or menu plus ‹ ›, never snapped tabs or horizontal swipe.

Source: canvas. Checked by: review.

#### 10. Composer: two modes.

Comment and Ask Pulls only. Split visual layout, not three text chips. No counters, no voice.

Source: canvas. Checked by: bans, review.

#### 11. Not wanted, ever.

Voice/dictation. Suggestion mode. Share on PR/tour. “Up next” inside a tour. FAB with unclear scope. “Later” as a row action. Numbered step pips. Tiny provenance labels. Step counters in progress states.

Source: canvas. Checked by: bans, review.

#### 12. Scale and tone.

26px+ plain PR title (no chat bubble). Perplexity palette: warm off-white, teal accent, deep ink. Generous element size.

Source: canvas. Checked by: tokens, review.

### Rules 13 to 24, from the row O recording

#### 13. Alignment first.

Number, author, age, diffstat sit in fixed columns so every row lines up. Test: add a digit, swap a longer author name. Nothing else may move. Misalignment is the first thing he sees.

Source: canvas. Checked by: review.

#### 14. No tiny grey text.

Small uppercase labels, eyebrows, provenance lines, counters (“1 of 4 reviewed”, “Section 3 of 7”) are awful. Body-size ink or nothing. Never below 15px, never uppercase-tracked. A section label is one word: Summary.

Source: canvas. Checked by: raw-values, review.

#### 15. One row anatomy everywhere.

A PR row is identical in Inbox, Tours, search: title, number, author, coloured diffstat, checks, age. Context-free counts (“7 sections”) never replace them.

Source: canvas. Checked by: review.

#### 16. Status words only where they vary.

A group titled “Ready to read” does not repeat Ready on each row. Failing / passing get a real icon, not a dot.

Source: canvas. Checked by: review.

#### 17. Swipe commits, tap opens.

Swipe left archives, swipe right opens the review, as full Mail-style gestures. No reveal-then-tap. Blur the displaced row content slightly. Keep clearance from anything revealed.

Source: canvas. Checked by: review.

#### 18. AI work looks alive.

In-progress analysis is a live, animated thinking indicator that feels smart. Never show the internal step. Make it tappable.

Source: canvas. Checked by: review.

#### 19. Analyze is one action, three outputs.

Analysing a PR = summary + per-file explanations + tour. Call it Analyze, not Write tour. An unanalysed PR is a real state with a visible way in. Per-file explanations show on the file rows.

Source: canvas. Checked by: review.

#### 20. Summary and Description, both first-class.

Description = what the author wrote. Summary = what the AI produced. One easy switch on the PR page.

Source: canvas. Checked by: review.

#### 21. Identifiers look like GitHub's.

Branches are squarish pills, head → base, tap to copy. Cites keep the concept but drop mono and the current pill style; try Mona Sans.

Source: canvas. Checked by: review.

#### 22. Counts: neither cards nor a run-on sentence.

No stat tiles, and no “+180 −32 across 4 files · 2 commits · 2 checks failing · 0 of 4 reviewed” either. Aligned label/value columns where nothing moves when digits change.

Source: canvas. Checked by: bans, review.

#### 23. Sub-pages keep their context.

A tour section page carries the PR title and number in its header, with progress as a small element there. No pips, no eyebrow, no bottom “X of Y”.

Source: canvas. Checked by: review.

#### 24. Buttons are one line.

“Next unreviewed” becomes “Next”. A wrapped label is a defect.

Source: canvas. Checked by: review.

### Rules 25 to 36, from the two recordings of 2026-10-13 (rows P to U)

#### 25. Nothing overflows the frame.

No horizontal scroll, no scrollbar, nothing inside the 16px margin. Sliding rows clip inside the frame. Render at 390×844 before publishing.

Source: canvas. Checked by: review.

#### 26. Meta lines are one line.

Author · repo #number · age on one line; head → base on one line. Each segment ellipsises, nothing wraps.

Source: canvas. Checked by: review.

#### 27. Icons, not words, for segment navigation.

Overview / Changes / Tour is three icons in a capsule. Words are for tabs inside a page.

Source: canvas. Checked by: review.

#### 28. Summary / Description are underline tabs.

Pills are spent on chips, branches and cites.

Source: canvas. Checked by: bans, review.

#### 29. The ⋯ sheet is on every PR screen.

Checks, GitHub, Analyze again; Approve, Request changes, Squash and merge, Merge when checks pass, Convert to draft, Close. Reachable from the PR, diff and tour headers.

Source: canvas. Checked by: review.

#### 30. Ask is a quiet toolbar.

“✦ Ask | ⋯” in a floating capsule beside the primary button. Never a second Ask inside a comment card. A sparkle alone is not a label: AI actions get a word or a real icon.

Source: canvas. Checked by: review.

#### 31. Tour sections scroll.

One scrolling page, no Next button. Progress is the ring in the header. Room at the bottom for the Ask toolbar.

Source: canvas. Checked by: review.

#### 32. Both swipes.

Right = Review (accent). Left = Archive (dark). Both commit, both blur the displaced row.

Source: canvas. Checked by: review.

#### 33. No avatars on list rows.

Identity on a row is the author name in its own column. The reader’s avatar lives in the inbox header; the author’s photo on the PR page.

Source: canvas. Checked by: review.

#### 34. Notes about a file look like comments.

An AI note on a diff is a comment card by “Pulls”: white, rounded, soft shadow. Not a loose line above the code.

Source: canvas. Checked by: review.

#### 35. Search is a bottom bar on every inbox.

Profile at the top, “Search or ask Pulls” floating at the bottom, no search icon in the header.

Source: canvas. Checked by: review.

#### 36. Two accepted row anatomies.

Canonical: P’s number column, then the title; author, checks, diffstat, age in fixed columns. Alternative: U’s status circle + number leading the row. Nothing else.

Source: canvas. Checked by: review.

### Rules 37 to 44, from the row V recording (2026-10-13)

#### 37. One real AI icon.

Withdrawn on 2026-10-14: Tavi said the sparkle does not stay, so no icon marks AI. AI actions are marked by their words ("Ask AI", "Analyze") and by the live progress indicator of rule 72. The text it had: an icon from a published set (Lucide sparkles), never a hand-drawn glyph, one per screen.

Source: canvas. Checked by: review.

#### 38. Tabs are an underline, not a box.

A hairline under Summary / Description and the accent underline on the active tab. No hairline above.

Source: canvas. Checked by: bans, review.

#### 39. Cites sit on the text line.

Same cap height as the body, no vertical padding; tint and a thin border do the “different” work. Never taller than the line, never alone on a line.

Source: canvas. Checked by: review.

#### 40. Air between blocks.

A comment card or stats row never touches the diff. At least 24px above the diff’s top edge.

Source: canvas. Checked by: review.

#### 41. Checkboxes are bare.

Reviewed is a checkbox and a word. No pill, chip or border around it.

Source: canvas. Checked by: review.

#### 42. Tour position lives in a sticky strip.

N of M · title · ring is a sticky strip under the header; it updates as the next section reaches the top. The count exists only there, never inline above a section title.

Source: canvas. Checked by: review.

#### 43. Progress states show the work, not blanks.

No shimmer bars standing in for content. The live orb plus the outputs with their state: done, writing, pending.

Source: canvas. Checked by: review.

#### 44. Show states interactively.

Once a row is accepted, the next deliverable is a clickable prototype with real transitions: tabs, sheets, swipes, comment reply, the tour scroll.

Source: canvas. Checked by: book, review.

### Rules 45 to 65, from the phone walkthrough of 2026-10-14

#### 45. Shadows match the canvas on every floating element.

The header round buttons, the bottom search pill, the comment bar, the Tour card and the Ask bar all carry the canvas shadow (`floatShadow`). A missing or different shadow is a defect, not a nit. It was the most repeated complaint in the walkthrough.

On the phone today the shadows are there but faint and flat: a pale halo spread evenly round the edge, with no visible drop below it. The canvas shadow is faint too (8% ink on the round buttons), but it is pushed 2dp down (`0 2px 10px`), and that offset is what makes a button look lifted. The fix is a drawn shadow with a downward offset like the canvas, not a stronger glow. See Elevation & Depth.

Check: put each floating element beside Row W. Same shadow, with a visible drop below it, or fix it.

Source: walkthrough. Checked by: raw-values, book, review.

#### 46. Blur is part of how things move, in three places.

- Scroll edges: row titles blur as they scroll under the top header and under the bottom bar. Today every scroll edge is a hard cut: rows end in a sharp line under the chip row, text runs up to the bottom pill and stops, and content is sliced under a sheet's fixed header and under the diff's sub-header. The blur belongs at all of them.
- The swiped row blurs as it is displaced, as in the design. This is the same thing as rules 17 and 32. The canvas row moves sideways about 132 to 136dp with a 1.3px blur and a side shadow. Today it slides fully sharp, and the Review action is a flat teal rectangle with square corners and no shadow.
- The scope chips: switching between Waiting on you, Authored and All is liked and keeps its motion. It needs a blur transition, where the outgoing and incoming text overlap. The exact blur is not specified yet. Rule 67 is one possible reference.

Source: walkthrough. Checked by: review.

#### 47. Build to the canvas measurably. "Different" is a defect.

"Close" is not accepted. What was flagged:

- Spacing inside Inbox rows, horizontal and vertical. The columns are not fixed (rule 13): the gap between the added and removed numbers changes from row to row, and the status marks sit at slightly different positions.
- A divider that the canvas has and the Inbox lacks: a line directly under the project heading. Tours has a hairline under its heading, and the Inbox draws hairlines only above later groups, so the group heading must be treated the same on every tab.
- The bottom search pill sits too low, with its lower edge almost on the system gesture bar. Raise it. The Ask bar on the Tour is in the same low position.
- On the Overview the top part is bigger in the design and is not stuck to the top of the phone.

Check: render the screen and the canvas row at the same size, side by side, before showing it. Measure gaps. Do not eyeball them.

Source: walkthrough. Checked by: tokens, raw-values, book, review.

#### 48. Icons: keep the coloured Claude Code and Codex marks, cut the rest.

- Only the Claude Code and Codex marks stay, in colour. Both are monochrome today, a black asterisk and a grey knot.
- The model is shown by its own icon. The Model row in the ⋯ sheet shows a robot head, and should show the icon of the model that was selected.
- No generic AI glyph, no decorative icon. The ones named so far: the robot head (on each Tours row, on the Overview's "Not analyzed yet" card and on the Model row), the sparkle (on "AI Analysis" in Settings and on "Ask AI" in the toolbar over a selected line), the compass (beside "Take a tour of this pull request" and inside the "Give me a tour" button), the chat bubble (in the Ask bar) and the speech bubble on "Request changes". By the same kind: the plug on Server, the layout glyphs on Unified and Split, the zoom magnifiers on Code size, and the globe, key and eye in the Server fields.
- Never the same icon twice on one card. Ask of every icon what fact it carries. This is rule 1 applied to icons.

The robot head also works as a status indicator, because the ring around it turns while a tour is writing. Not complained about: the three icons in the header capsule on the Overview, the gear, and the icons in the profile sheet.

Settled on 2026-10-14: the sparkle does not stay either, so rule 37 is withdrawn and no screen has an AI icon.

The `bans` check counts uses of the robot head, the sparkle, the compass and the chat bubble (`ai-glyph`), so new code cannot add one. The count may only fall.

Source: walkthrough. Checked by: bans, review.

#### 49. One visual dialect: every screen is in the mobile style.

A screen that looks like the old app is a defect. Seen on:

- Settings: font, spacing and pills differ from the rest, and it has the old icons. The sheet is a greyer cream than the Inbox, its close button is a bare ✕, and its segmented controls are the reverse of the Inbox capsule (rule 70). Its model selector must be the mobile one already made. Today it is a cramped dropdown that reads "Default (reco…" with "Default" shown twice.
- The Commands menu: flat rows straight on the cream with no cards, small grey icons, and rows cut by an ellipsis.
- The screen behind the project name: a breadcrumb, bare icons, a bordered filter box and select, and a loading spinner, with none of the round shadowed buttons.
- The file switcher in the Diff: plain grey counts that should be coloured as in the diff, a font that differs from the file name above it, and different files cut to the identical text.
- The toolbar that floats over a selected diff line, with a cut label and the sparkle (rule 48).

Reuse the components already built for mobile (model selector, sheets) instead of rebuilding a web version. The profile sheet is the reference for right.

Check: for every screen reachable from the main flow, ask "does this look like the old app?" before shipping.

Source: walkthrough. Checked by: bans, review.

#### 50. Never make the reader wait for what the app already knows.

Opening a PR shows the title, the number and the rest of the header at once, because they are already loaded. Today a PR opens with "Checks None" and only two tabs, and the Conversation count and the real check state arrive a moment later, so the page fills in piece by piece. Moving between the Overview tabs has no load: content loads as the reader scrolls, the first items appear immediately, and the data is already fetched in the background. Which tab was meant is not named.

Source: walkthrough. Checked by: review.

#### 51. No loading screens. If something must wait, use skeletons.

This is for page and list content whose shape is known. It does not override rule 43: AI work in progress (Analyzing) still shows the live orb and its outputs, with no shimmer bars.

Today opening a PR puts up an empty page with a spinner and "Loading pull request…", and the project screen does the same, although the title, number and author are already known from the row that was tapped. The Analyzing state puts shimmer bars under each file row, against the wording of rule 43. See the Decision log.

Source: walkthrough. Checked by: bans, review.

#### 52. Refresh is silent.

Pull down must not show a refresh indicator. The app refreshes by itself and does not show it. Today the pull-to-refresh spinner is drawn on top of the first row's title, with no space reserved for it.

Source: walkthrough. Checked by: bans, review.

#### 53. If it looks tappable, it does something.

The project heading on Tours looks tappable and does nothing: tapping it lights a grey press highlight across the heading and then nothing opens. It should open the project. On the Overview, the project name at the end of the meta line opens a screen in the old style, so the project destination must be in the new style (rule 49). The author name on the Overview does respond to a tap, which is right, and the branch chip copies the branch name. This is rule 4's "tappable rows look tappable", run the other way.

Source: walkthrough. Checked by: review.

#### 54. Tabs are easy to hit.

The tabs on the Overview are hard to hit. The hit area was not measured. Rule 12 asks for generous element size, and the kit's touch target is 44dp (`TOUCH`). In the frames the tabs are plain text labels with a teal underline under the active one, with no fill or padding, and "Conversation 23" runs close to the right edge.

Source: walkthrough. Checked by: review.

#### 55. The bottom pill is the one way to find a PR.

- It reads "Search or ask Pulls" as in the design (rule 35). The app shows "Search pull requests" on the Inbox and "Search tours" on Tours. With the keyboard up the pill rides on top of it.
- It takes pasted links and PR numbers as well as text.
- So the "Open pull request" field in the profile sheet goes.

Source: walkthrough. Checked by: review.

#### 56. Row gestures are the same on Inbox and Tours.

Tours rows do nothing when swiped. They get a cancel on one side and open on the other, and tap still opens. This follows rules 15, 17 and 32: one row anatomy, both swipes, blur the displaced row. Today a Tours row is a robot icon in a ring, a one-line title cut with an ellipsis, and "Writing · about a minute" under it.

Source: walkthrough. Checked by: review.

#### 57. Swiping moves between the Overview tabs.

The Overview has three tabs: Summary, Description and Conversation (with a count). The canvas rules name only Summary and Description. Swiping left and right changes between them, with easy, clean swipes. Rule 9 still holds: files in the diff are never switched by horizontal swipe.

Source: walkthrough. Checked by: review.

#### 58. Sheets with long content pull all the way up.

Two bottom sheets cannot be pulled fully up. The ⋯ sheet is cut off at "Close pull request", with Review with AI, Review through a lens, Commands and Refresh below the fold, and the Select model sheet is cut off after Sonnet 5.5. The Ask sheet is nearly full height, so sheets are not all one height. Rule 5 prefers sheets over pages, so a sheet has to behave like a full surface when its content needs it. Sheets at their normal height are fine, the profile sheet included.

Source: walkthrough. Checked by: review.

#### 59. Menus and sheets open and close with a transition.

A menu in the Diff needs an open and a close transition. The menu at that moment is the Aa menu (Smaller, Default, Larger, Wrap long lines, Show review comments, Copy path, Open on GitHub, Load the whole file), not the file switcher. What is wrong with the motion is still Tavi's to say, and a reference showing how those transitions should look is pending. Rule 44 applies once it arrives. Rule 68 applies now: answer at once.

Source: walkthrough. Checked by: review.

#### 60. A control appears only where it works.

The Smaller / Default / Larger menu on the Tour does nothing there, so drop it. The Aa button on the Tour header opens it together with Wrap long lines and Show review comments, which are settings for code, and the Tour has no code on it. The same button on the Diff also holds Copy path, Open on GitHub and Load the whole file, which are fine. Same principle as rule 1: if it does not tell or do anything, cut it.

Source: walkthrough. Checked by: review.

#### 61. Commands is a designed menu in the app's style, with fewer things in it.

- The current one is in the wrong style. Make a new design in the same style as everything else, and make it nice.
- It may hold too many things. "Review with AI" and "Review through lens" need a decision.
- Tentative, not a rule yet: open it on pull down.
- Open: how Commands relates to rule 29's ⋯ sheet. Today "Commands ›" is a row in the ⋯ sheet and in the profile sheet, and tapping it closes the sheet and opens the palette with the keyboard up. Whether it stays a row in the ⋯ sheet is Tavi's call.

Source: walkthrough. Checked by: review.

#### 62. Ask never needs Analyze first.

Tapping Ask opens the bottom sheet with the chat, and the reader chats straight away. The current screen is not acceptable: it is a title, one grey line reading "Analyze this pull request first, then you can ask about it.", a centred Analyze button, and no composer. Rule 19 makes an unanalysed PR a real state, so Ask has to work in that state.

Source: walkthrough. Checked by: review.

#### 63. AI actions answer the tap at once and never strand the reader.

Pressing Analyze gave slow feedback. A double tap cancelled the run, and after the cancel there was no way to restart. The card then said "Analyzed" with the Tour step still an empty circle and an italic "Starting Claude…". Nothing said the run was stopped, and the only "Analyze again" was a tile inside the ⋯ sheet.

Checks:

- The first tap shows a response immediately.
- A second tap cannot cancel. Cancelling is a deliberate action.
- A cancelled or failed run always offers a way to start again.
- No state is a dead end.

Source: walkthrough. Checked by: review.

#### 64. The comment composer is a bar you type into.

Tapping a line of the diff opens a comment and in-review control at the bottom that is not liked. Two things appear together: a pill reading "Comment · In review ✕" between a round ‹ button and a round teal › button, and a small white toolbar floating over the selected line (rule 49). The words sit in a pill, so it reads as a label and not as a field you can type in. It used to be a bar you could just type in, which is better, and the buttons can be integrated into it. It is also missing its shadow (rule 45). A few variants are to be tried, so treat the form as open. This is the app-side counterpart of rule 10.

Source: walkthrough. Checked by: review.

#### 65. Reviewed moves you on.

Pressing Reviewed moves to the next file that is not reviewed, and marks the finished file, for example by greying it out a little. Today ticking Reviewed leaves the reader on the same file and shows a toast ("1 of 134 files reviewed") over the bottom of the diff. Back on the Overview the file is struck through and the button says "Review 133 files", so the count of what is left already updates. Rule 41 still holds: Reviewed itself stays a bare checkbox and a word.

Source: walkthrough. Checked by: review.

### Rules 66 to 70, from the Perplexity review of 2026-10-13

#### 66. One destination, one presentation.

The same screen always opens the same way: always as a sheet, or always as a page. In the reference app the sign-in screen is a bottom sheet from one place and a full page from another, which is confusing. This extends rule 5: once a destination is a sheet, every route to it uses the sheet. In this app, check destinations that can be reached from more than one place, such as the model selector (rule 49).

Check: for each screen reachable from two or more places, open it from each. Same presentation every time.

Source: perplexity. Checked by: review.

#### 67. A segmented pill moves its highlight, and the new content fades in.

When a pill that switches between modes is tapped, the active highlight slides across to the new segment instead of jumping, and the new content fades in. This is the motion that goes with rule 27. It is one possible reference for the blur transition on the scope chips left open in rule 46, and it is not said to be the one.

Check: tap a segment. The highlight travels to it and the content fades in.

Source: perplexity. Checked by: review.

#### 68. Reactive first, springy second.

A control or sheet answers the instant it is touched. A spring or bounce is a bonus, not the requirement. A small bounce that fires the moment a character is typed was liked. This applies to rule 59 (transitions) and rule 63 (AI actions answer at once): sheets, the Analyze tap and the comment bar.

Check: tap or type and note how soon anything visibly changes. A plain transition that starts at once beats a springy one that starts late.

Source: perplexity. Checked by: review.

#### 69. Elements that exist in both states keep their position.

When a screen changes state, whatever is present in both states stays exactly where it was, so the change reads as a layer arriving instead of a new screen. In this app the nearest cases are the Overview header across the Tour (rule 23) and the bottom bar across screens.

Check: flip between two states of a screen and watch. Anything that exists in both must not jump.

Source: perplexity. Checked by: review.

#### 70. The same control looks the same on a page and in a sheet.

Confirmed by Tavi on 2026-10-14. In the reference app the round ✕ in a sheet is the same white circle with a soft shadow as the round buttons on the page behind it. In this app the Settings sheet had a bare ✕ and segmented controls that were the reverse of the Inbox capsule (rule 49), so one kind of control looked different on a page and in a sheet. Now a sheet's close and back buttons are the round white button with its shadow, and the segmented controls in a sheet are the Inbox capsule. Which way a segmented control goes is still Tavi's call: the canvas capsule is a white track with a grey selected segment, the reference app uses a grey track with a white selected segment, and the app follows the canvas until he says otherwise.

Check: take one kind of control (close button, segmented control, chip, round button). Find it on a page and in a sheet. Same shape, fill and shadow.

Source: perplexity. Checked by: review.

### Rules 71 to 73, from Tavi's answers of 2026-10-14

#### 71. A live indicator may have shimmer bars under it.

Rule 43 says no shimmer bars standing in for content. Tavi confirmed that bars are fine next to a live indicator, as in Perplexity's "Thinking…", where three grey bars sit under the mark and the word, the second and third a little shorter than the first. So the live indicator stays, and grey bars may sit under it as a hint of where the output will arrive. Bars never replace the indicator and never stand alone. Rule 51's skeletons for pages and lists whose shape is known are unchanged. The canvas text of rule 43 is unchanged.

Check: while AI work runs there is a live indicator on screen. Any bars sit under it.

Source: answers. Checked by: review.

#### 72. Thinking text is a shimmering plain sentence with a dot.

Tavi asked for the AI's thinking text to look more like Perplexity's. While work runs there is one line, a present-tense verb and the elapsed seconds ("Working 12s"; "Thinking" before the first step), the verb in the muted colour and the seconds in the faint one, in tabular digits, shimmering together. The shimmer leaves the text at full strength and dims a tilted band to a third of its opacity as it sweeps left to right, 1200ms a pass; with system animations off nothing sweeps. Each step is one plain sentence about the task, never the tool, in regular weight and never italic, after a 6dp dot in the text colour (red when it failed, amber for a warning) whose centre is 10dp in from the margin; the dot replaces every per-row icon. The live orb sits under the newest line on the dot's column at 16dp. When the line is replaced by another, the old one leaves upward through a 2px blur while the new one rises in from below, 150ms ease-in-out, 8dp of travel, a 50ms gap (transitions.dev, Thinking states). When work ends the shimmer stops and the line becomes "Worked for 2m 3s", a button with a chevron, right when folded and down when open, whose steps hang from a 1dp line that fades out. The sizes taken from screenshots are estimates, not measured on a device.

Check: start an analysis and watch it. One header line with the time, a dim band passing through it. Step lines with a dot, no icon, never italic. When the work ends, the shimmer stops and the line goes to the past tense with a chevron. With animations off nothing sweeps.

Source: answers. Checked by: review.

#### 73. Motion uses one set of numbers.

Tavi sent transitions.dev as the reference. Durations: 150ms for closes and text swaps, 250ms for opens, tabs and page slides, 350ms for a panel closing, 400ms for a panel opening. One easing, smooth out, `cubic-bezier(0.22, 1, 0.36, 1)`, for opens, closes and slides; ease-in-out for swaps; linear for shimmer. Blur is 2px for small things and 3px for page-sized changes. Scale is 0.97 for menus and 0.96 for dialogs. Travel is 4 to 8dp for text and pages. The mapping from the library's transitions to this app's surfaces was proposed by the rules file and Tavi can retune any row: menus use the dropdown recipe, dialogs the modal one, sheets panel reveal, the capsule tabs sliding, page changes page side-by-side, the thinking line shimmer text and thinking states. Looping effects follow the system setting "Remove animations".

Check: open and close one menu, one sheet, one dialog and one toast. Each takes the time above and eases out.

Source: answers. Checked by: review.

### Keep as is

These were liked in the walkthrough and must not regress:

- The profile sheet, which uses the right style.
- Bottom sheets at their normal height.
- The list rearranging when the scope chips switch (Waiting on you, Authored, All).
- The Files list on the Overview. A separator under the "Files" heading may be tried: in the not-analyzed state nothing divides it from the Analyze card above.
- The description on the Overview.
- The Diff screen, and its pinch zoom.

## Checklist

Run before showing any new screen.

1. Each element: which fact does it convey? None means cut it.
2. Each row: full title, repo, number, author, head and base, coloured diffstat, checks, age. Present, aligned, never one crammed line.
3. Each bottom button: reversible? If not, it goes behind ⋯.
4. Each word: what a GitHub reviewer would say.
5. Change one number's digit count and one author's length. If anything moves, fix the columns.
6. Search the screen for text under 15 or uppercase-tracked. Each hit is a defect.
7. Every PR row, on every page, has the same anatomy.
8. Button labels are one line.
9. Render at 390 by 844. Any scroll, scrollbar or element inside the 16dp margin gets fixed first.
10. Each meta line: imagine the frame 60dp narrower. Anything that would wrap ellipsises instead.
11. (Withdrawn with rule 37: no screen has an AI icon.)
12. A cite never grows its line.
13. At least 24dp above the diff.
14. Every floating element has the canvas shadow, with a visible drop below it. Compare it with Row W.
15. Blur at every scroll edge (under the header, under the bottom pills, under a sheet's header), on the swiped row, and when the scope chips switch.
16. Screen and canvas row side by side at the same size, with gaps measured, not eyeballed.
17. Icons: only the Claude Code and Codex marks (in colour), the chosen model's own icon and the live indicator. For every other icon, which fact does it carry? None means cut it. Check the robot head, the sparkle and the compass first.
18. Does any screen look like the old app? Settings, the Commands palette, the project screen, the file switcher and the toolbar over a selected line are all in the mobile style.
19. Open a PR: title and number appear at once, there is no loading screen, and skeletons only stand in for waiting content.
20. Double-tap Analyze, then cancel it: the first tap responds, the second does not cancel, and a restart is offered.
21. Ask works on an unanalysed PR.
22. Every tappable-looking thing does something. Tabs and the "Stop ›" text on the Analyzing card are easy to hit.
23. Open each screen that is reachable from two or more places from each. Same presentation, sheet or page, every time.
24. Tap a segmented pill: does the highlight slide to the segment, and does the content fade in?
25. Tap and type: how soon does anything visibly change?
26. Flip between two states of a screen: does anything present in both jump?
27. Find one kind of control (close button, segmented control, chip, round button) on a page and in a sheet. Same shape, fill and shadow?
28. While AI work runs, a live indicator is on screen, and any bars sit under it (rule 71).
29. The header line is a verb with elapsed seconds and the step lines are sentences with a dot, no icon and no italics, and a dim band shimmers through the text (rule 72).
30. Search every screen for an AI icon (a sparkle, a robot, a brain). None. The brand marks of Claude Code and Codex (rule 48) and the live indicator are not AI icons in this sense.
31. Open and close a menu, a sheet, a dialog and a toast; the times and easing match rule 73.

The checklist is the canvas checklist (1 to 10, with the three Row W checks as 11 to 13) followed by the walkthrough checks (14 to 22) the Perplexity checks (23 to 27) and the checks from Tavi's answers (28 to 31). The canvas notes number Row W's checks 10 to 12, which collides with its own check 10, so they are renumbered here.

## Enforcement

Each layer catches what the one before cannot. All of them run in one command and in CI (`android-design` job), need only Node, and are never skipped: a failing check is fixed, not waived.

```
pnpm run android:design           run every check below
pnpm run android:design:update    rewrite the generated files and the baselines, then review the diff
pnpm run android:design:test      vitest suite of the checks themselves
```

1. **tokens.** `design.md lint` (from `@google/design.md`) on this file: it fails on a broken reference. Then the Kotlin is compared with the front matter: every colour and its dark pair, every type role (family, size, weight, line height, tracking), `PullsRadius` and `PullsSpace`. A colour without a dark pair fails. `app/src/main/assets/book/spec.json` is written from this file and fails when stale. Rules 12 and 47.
2. **raw-values.** A ratchet. It counts raw hex colours, raw font sizes and raw radii in the Kotlin per file, text set below 15 (`xs`, `compact`, `mini`, `micro`), uppercase and tracked text, and raw shadows (`.shadow(`) outside the shadow helper, and compares them with `raw-values.baseline.json`. The count may only go down: a new raw value fails, and a file that drops below its baseline fails until `android:design:update` lowers it, which locks the gain in. A line can opt out with `// raw-value-ok: <reason>`. Rules 14, 45 and 47.
3. **bans.** Importing a Material widget the kit replaces (buttons, switches, checkboxes, text fields, dialogs, tabs, cards, dividers, progress indicators, FABs), Material icons or Android speech input, and an outgoing share, fail. Material's `ModalBottomSheet` and `DropdownMenu` are only for the kit's wrappers in `ui/design`. The web-port kit (`Components.kt`), its Phosphor icons, the waiting components (`Loading`, spinners) and pull-to-refresh are banned for new code. A call or a Material name spelled out with its full package counts the same as an imported one. The generic AI glyphs of rule 48, the robot head, the sparkle, the compass and the chat bubble (`ai-glyph`), are counted the same way: a new one fails, and the count may only fall. The uses that exist today (three Material sheet or menu imports in `AiParts.kt` and `Chrome.kt` among them) are counted per file in `bans.baseline.json` and may only fall. Bans lift in the config at the top of `bans.mjs` or with `// ban-ok: <reason>` on the line, never silently. Rules 1, 5, 8, 10, 11, 22, 28, 38, 48, 49, 51 and 52.
4. **book.** `book.json` is the one typed list of UI Book pages and components. Every page has a route in `UiBook.kt`, and every route is listed. Every component has exactly one default variant, and every named composable exists. Every public composable in `ui/design` has a page or an exemption with a reason in `book.exempt.json`. Every `components` token here is rendered in a `DesignSpecimen`, or exempt with a reason. `sources.json`, the code and props the Book shows, is read from the Kotlin and fails when stale. Rules 44, 45 and 47.
5. **rules.** A check on this file's own list. Rules 1 to 73 are contiguous, each has a source and a `Checked by` line naming a check that exists, and each check enforces exactly the rules that name it. The checklist is contiguous too. `rules` is not a `Checked by` name: it checks the rules, not the code.
6. **Measure on the phone.** The UI Book's Component specs page measures each `DesignSpecimen` and prints `name ok` or `name.size: expected 44px, rendered 52px`. It replaces the browser render check the web UI Book runs, because Compose cannot be measured from Node. It runs when someone opens the page.
7. **Review against the checklist.** A person or an agent walks the screen against the checklist above and the rules, and reports breaks with `file:line` and the rule number.

## UI Book

Settings has a UI Book row at the bottom. The book is a set of screens inside the app, built from the same kit, so what it shows is exactly what ships. It follows the web UI Book: one typed page list drives the index, the pager, search and the coverage check; foundations read the real tokens; every component has its own page with live previews, every state on its own copy, light and dark, variants as tabs, an Inspect toggle that reads the measured size, the props and the source read from the Kotlin at build time, usage notes and a Copy prompt that briefs an agent.

Files: `app/src/main/assets/book/book.json` (hand-written pages and components), `spec.json` and `sources.json` (generated, do not edit), and the Kotlin in `ui/book`.

To add a component: write it in `ui/design`, add its entry and variants to `book.json`, add a preview in `ui/book/BookPreviews.kt`, run `pnpm run android:design:update` and `pnpm run android:design`, then look at the page in light and dark on a phone.

## Decision log

Every difference between this file and the rendered app, and how it was settled.

- **2026-10-14, this file written from the source at version 0.6.1 (build 16).** The tokens are what `Tokens.kt` renders today, not the other way round, so no visible change. Kotlin is compared with this file instead of being generated from it: `Tokens.kt` carries computed properties and the app is being edited in parallel, and a generator would overwrite those edits. The checks still fail closed.
- **2026-10-14, departures are frozen, not fixed.** The raw-values and bans baselines record how far the code is from these rules today: 288 raw values (40 hex colours, 131 raw radii, 2 raw font sizes, 3 raw shadows, 106 text runs below 15, 6 uppercase or tracked) and 187 banned uses (93 Phosphor icon calls, 75 web-port kit calls, 6 Material components, 3 Material sheets or menus, 1 pull-to-refresh, 9 loading indicators). No screen was restyled to get there. Each fix lowers a number and the check asks for the baseline to be lowered with it.
- **2026-10-14, raw colours in the kit that are not tokens yet. Open.** `Kit.kt` holds literals for the sheet grabber (`D9D9D6`), the unchecked box edge (`C9CFCE`), the pending ring (`B9C4C5`), the orb and toast accent (`9AD3D6`) and the profile gradient (`D9B38C`, `8C5A3C`). They need tokens, with dark values. They sit in the raw-values baseline until then.
- **2026-10-14, the PR row has no page. Open.** Rule 15 says one row anatomy everywhere, but the row is private to `HomeScreen.kt` and the Tours list, so the UI Book cannot render it. Pulling it into `ui/design` is a change to the screens and was left to the work that owns them.
- **2026-10-14, forced states.** The web book forces hover, focus and active with CSS. Compose cannot, so the book forces the states a component takes as parameters (selected, disabled, loading, running, done) and shows the pressed scale on the Motion page with a live pressable. A sheet is a window and cannot be framed inside a page, so its page shows the sheet's body inline and opens the real sheet from a button.
- **2026-10-14, rule 48 and rule 37. Settled the same day:** Tavi called the robot, the compass and the sparkle hideous, so none is left. The Model row shows the chosen engine's own mark in colour, and the `ai-glyph` ban baseline is zero. Earlier text, kept for the record: One Lucide sparkles icon per screen as the AI mark is still in force. The walkthrough does not say whether it stays, and the sparkle called hideous on "Ask AI" is the kind of mark rule 37 asks for. The `ai-glyph` ban counts the sparkle with the robot head, the compass and the chat bubble, so none can be added. Counting is not removing: the existing ones stay until Tavi decides.
- **2026-10-14, rule 61 and rule 29. Settled the same day:** Commands is its own sheet, opened by pulling the Inbox down or from the ⋯ sheet, with a search pill and four rows to a group until searched. Earlier text: how the Commands menu relates to the ⋯ sheet is not decided.
- **2026-10-14, rule 45 and `floatShadow`. Settled the same day:** `floatShadow` now draws one soft layer pushed down, with a level for each kind of element (`PullsShadow`). Earlier text: The canvas shadow is a soft layer pushed down (`0 2px 10px` at 8% ink on the round buttons), and on the phone the app's shadows read faint and flat. `floatShadow` in `Kit.kt` calls Compose `shadow()` with an elevation and no offset. The fix is one drawn shadow with a downward offset, in that one function, judged side by side on a device. It was not changed here, because it restyles every floating element and belongs to the screen work. An earlier reading, that the app's shadows were stronger than the canvas's, was wrong.
- **2026-10-14, rules 66 to 70 added from the Perplexity review.** Rules 66 to 69 are rules. Rule 70 was a proposal from the frames, not from anything Tavi said. He confirmed it the same day, see the entry on rules 71 to 73.
- **2026-10-14, rule 51 and rule 43. Settled the same day:** Tavi said bars are fine next to a live indicator, which is rule 71. Earlier text: The Analyzing state puts shimmer bars under each file row, against the wording of rule 43, which keeps the live orb and its outputs and no bars. A reading for Tavi to confirm: bars are fine as a hint for the one text being written, next to a live indicator, and rule 43 forbids bars in place of the live indicator and its outputs. The canvas text of rule 43 is unchanged.
- **2026-10-14, the phone walkthrough applied.** Every point in Tavi's feedback that is about how the app looks or moves now follows the mobile kit: the drawn shadow, blur at scroll edges and between tabs and pages (`edgeBlur`, `BlurSwitch`), menus that grow out of their trigger (`MenuReveal`, from the `dropdown` recipe on transitions.dev), the header capsule on Overview, Diff and Tour, the pill input with its send circle inside (`PillInput`, `SendButton`), fields inside sheets (`SheetField`), and Settings, Commands, the repository list, Check out, the review dialogs, the share sheet, the AI review panel, thread cards and the banners rebuilt from kit parts. Labels in a sheet row wrap instead of being cut. The loading screens are skeleton rows. The robot, sparkle and compass are gone, along with `AppModal`, the web header and its footer. The baselines fell to 14 Phosphor icon calls, 3 web-port kit calls and 5 Material components, and 32 raw colours, 73 raw radii and 72 text runs below 15. What is left sits in the Overview timeline, the activity log, `Markdown.kt` and the CI and review status glyphs, which carry facts and need Lucide drawings of their own.
- **2026-10-14, rules 71 to 73 added, rule 37 withdrawn, rule 70 confirmed.** Tavi answered yes to bars beside a live indicator (71), no to keeping the sparkle (37 is withdrawn, so is Row W check 11), yes to rule 70, asked for the thinking text to look more like Perplexity's (72) and sent transitions.dev (73). Rule 70's direction for segmented controls is still open: the app follows the canvas capsule, so Settings, which already uses it, stays as it is, and a sheet's ✕ and back are now the round white button with its shadow.
- **2026-10-14, the thinking text applied (rule 72). Open edges.** `AiActivityLogFull` shows the shimmering verb and seconds, dot steps and the orb at 16dp, and its settled form has a chevron and a fading line. Since then the orb finishes its turn and settles into its still tick for about two seconds when work stops, and the Ask thread and the chat draw the three shimmer bars of rule 71 under it. The orb does not stay once settled, and in the chat it leaves with the live log, because the finished log is a different place in the list; the activity log still has no favicons or grouped steps. Measured sizes need a phone: the band, the dot column and the 16dp orb come from screenshots of Perplexity.
