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
  slide: 240ms cubic-bezier(0.2, 0.8, 0.2, 1)
  toast: 250ms
  progress: 400ms
  press: scale 0.97
  breathe: 1200ms reverse loop
  orb-turn: 1600ms linear loop
  orb-breath: 900ms reverse loop
  shimmer: 1500ms linear loop
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
- `sheet`, `sheet-card`, `sheet-row`, `quick-action`: the bottom sheet and what goes in it.
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

The `motion` tokens record what the kit uses today. It animates with `tween`. Durations in use: 150ms for a check filling, 200ms for chips, switches and tab ink, 240ms with `cubic-bezier(0.2, 0.8, 0.2, 1)` for the capsule's sliding pill, 250ms for the toast and 400ms for the progress ring. Live marks loop: the breathing dot at 1200ms, the orb's ring at 1600ms linear and its bot at 900ms, the skeleton shimmer at 1500ms.

Every pressable scales to 0.97 on pointer-down and answers with no delay. AI work in progress is a live, animated mark and never a blank (rules 18 and 43). A swiped row blurs slightly as it is displaced (rules 17, 32 and 46). Move things with transform and opacity.

## Rules

Rule numbers are one running list, so "rule 47" is unambiguous. Rules 1 to 44 come from the design canvas (Row W is the latest row) and are copied word for word from its rule notes, which Tavi wrote from his earlier recordings. Rules 45 to 65 were written from Tavi's phone walkthrough of the built app on 2026-10-14, one rule for each thing said, so each applies to screens that were not mentioned.

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

An icon from a published set (Lucide sparkles), never a hand-drawn glyph. One per screen: it marks the AI actor, not labels that are already words.

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

Check: put each floating element beside Row W. Same shadow, or fix it.

Source: walkthrough. Checked by: raw-values, book, review.

#### 46. Blur is part of how things move, in three places.

- Scroll edges: row titles blur as they scroll under the top header and under the bottom bar.
- The swiped row blurs as it is displaced, as in the design. This is the same thing as rules 17 and 32.
- Tab switches: the list rearranging between Inbox tabs is liked and keeps its motion. It needs a blur transition. The exact blur is not specified yet.

Source: walkthrough. Checked by: review.

#### 47. Build to the canvas measurably. "Different" is a defect.

"Close" is not accepted. What was flagged:

- Spacing inside Inbox rows, horizontal and vertical.
- A divider under the top buttons that the canvas has and the Inbox lacks. Tours has it, so the header treatment must be the same on every tab.
- The bottom search pill sits too low. Raise it. On the Tour the Ask bar is not high enough either.
- On the Overview the top part is bigger in the design and is not stuck to the top of the phone.

Check: render the screen and the canvas row at the same size, side by side, before showing it. Measure gaps. Do not eyeball them.

Source: walkthrough. Checked by: tokens, raw-values, book, review.

#### 48. Icons: keep the coloured Claude Code and Codex marks, cut the rest.

- Only the Claude Code and Codex marks stay, in colour.
- The model is shown by its own icon. The Model row on the Tour should show the icon of the model that was selected, not a generic one.
- No generic AI glyph, no decorative icon.
- Never the same icon twice on one card. Ask of every icon what fact it carries. This is rule 1 applied to icons.

Open: rule 37 keeps one Lucide sparkles icon per screen as the AI mark. The walkthrough does not say whether that mark stays. Keep rule 37 unless Tavi says otherwise. Which icons on the Tours rows, the Overview, the old menu, the Tour card and the buttons were meant is not named, so those need the frames or Tavi.

Source: walkthrough. Checked by: bans, review.

#### 49. One visual dialect: every screen is in the mobile style.

A screen that looks like the old app is a defect. Seen on:

- Settings: font, spacing and pills differ from the rest, and it has the old icons. Its model selector must be the mobile one already made.
- The Commands menu.
- The menu behind the project name.
- A menu in the Diff with the wrong font, whose rows should be coloured as in the diff.
- Another old-looking menu with an old icon.

Reuse the components already built for mobile (model selector, sheets) instead of rebuilding a web version. The profile sheet is the reference for right.

Check: for every screen reachable from the main flow, ask "does this look like the old app?" before shipping.

Source: walkthrough. Checked by: bans, review.

#### 50. Never make the reader wait for what the app already knows.

Opening a PR shows the title, the number and the rest of the header at once, because they are already loaded. Moving between the Overview tabs has no load: content loads as the reader scrolls, the first items appear immediately, and the data is already fetched in the background. Which tab was meant is not named.

Source: walkthrough. Checked by: review.

#### 51. No loading screens. If something must wait, use skeletons.

This is for page and list content whose shape is known. It does not override rule 43: AI work in progress (Analyzing) still shows the live orb and its outputs, with no shimmer bars.

Source: walkthrough. Checked by: bans, review.

#### 52. Refresh is silent.

Pull down must not show a refresh indicator. The app refreshes by itself and does not show it.

Source: walkthrough. Checked by: bans, review.

#### 53. If it looks tappable, it does something.

The project heading on Tours looks tappable and does nothing. It should open the project. On the Overview, tapping the project name goes to a menu in the old style, so the project destination must be in the new style (rule 49). The author name on the Overview does respond to a tap, which is right. This is rule 4's "tappable rows look tappable", run the other way.

Source: walkthrough. Checked by: review.

#### 54. Tabs are easy to hit.

The tabs on the Overview are hard to hit. The hit area was not measured. Rule 12 asks for generous element size, and the kit's touch target is 44dp (`TOUCH`).

Source: walkthrough. Checked by: review.

#### 55. The bottom pill is the one way to find a PR.

- It reads "Search or ask Pulls" as in the design (rule 35). The app shows "Search".
- It takes pasted links and PR numbers as well as text.
- So the "Open pull request" field in the profile sheet goes.

Source: walkthrough. Checked by: review.

#### 56. Row gestures are the same on Inbox and Tours.

Tours rows do nothing when swiped. They get a cancel on one side and open on the other, and tap still opens. This follows rules 15, 17 and 32: one row anatomy, both swipes, blur the displaced row.

Source: walkthrough. Checked by: review.

#### 57. Swiping moves between the Overview tabs.

Swiping left and right changes between the Overview tabs (the canvas calls them Summary and Description; the walkthrough says Description and Conversation) with easy, clean swipes. Rule 9 still holds: files in the diff are never switched by horizontal swipe.

Source: walkthrough. Checked by: review.

#### 58. Sheets with long content pull all the way up.

Two bottom sheets on the Tour cannot be pulled fully up. Rule 5 prefers sheets over pages, so a sheet has to behave like a full surface when its content needs it. Sheets at their normal height are fine, the profile sheet included.

Source: walkthrough. Checked by: review.

#### 59. Menus and sheets open and close with a transition.

A menu in the Diff, probably the file menu, needs an open and a close transition. A reference showing how those transitions should look is pending from Tavi. Rule 44 applies once it arrives.

Source: walkthrough. Checked by: review.

#### 60. A control appears only where it works.

The Smaller / Default / Larger menu on the Tour does nothing there, so drop it. Same principle as rule 1: if it does not tell or do anything, cut it.

Source: walkthrough. Checked by: review.

#### 61. Commands is a designed menu in the app's style, with fewer things in it.

- The current one is in the wrong style. Make a new design in the same style as everything else, and make it nice.
- It may hold too many things. "Review with AI" and "Review through lens" need a decision.
- Tentative, not a rule yet: open it on pull down.
- Open: how Commands relates to rule 29's ⋯ sheet.

Source: walkthrough. Checked by: review.

#### 62. Ask never needs Analyze first.

Tapping Ask opens the bottom sheet with the chat, and the reader chats straight away. The current screen is not acceptable. Rule 19 makes an unanalysed PR a real state, so Ask has to work in that state.

Source: walkthrough. Checked by: review.

#### 63. AI actions answer the tap at once and never strand the reader.

Pressing Analyze gave slow feedback. A double tap cancelled the run, and after the cancel there was no way to restart.

Checks:

- The first tap shows a response immediately.
- A second tap cannot cancel. Cancelling is a deliberate action.
- A cancelled or failed run always offers a way to start again.
- No state is a dead end.

Source: walkthrough. Checked by: review.

#### 64. The comment composer is a bar you type into.

Tapping at the bottom of the diff opens a comment and in-review control that is not liked. It used to be a bar you could just type in, which is better, and the buttons can be integrated into it. It is also missing its shadow (rule 45). A few variants are to be tried, so treat the form as open. This is the app-side counterpart of rule 10.

Source: walkthrough. Checked by: review.

#### 65. Reviewed moves you on.

Pressing Reviewed moves to the next file that is not reviewed, and marks the finished file, for example by greying it out a little. Rule 41 still holds: Reviewed itself stays a bare checkbox and a word.

Source: walkthrough. Checked by: review.

### Keep as is

These were liked in the walkthrough and must not regress:

- The profile sheet, which uses the right style.
- Bottom sheets at their normal height.
- The list rearranging when Tours tabs switch.
- The Files list on the Overview. A separator may be tried.
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
11. One AI icon per screen.
12. A cite never grows its line.
13. At least 24dp above the diff.
14. Every floating element has the canvas shadow. Compare it with Row W.
15. Blur at the top and bottom scroll edges, on the swiped row, and on tab switches.
16. Screen and canvas row side by side at the same size, with gaps measured, not eyeballed.
17. Icons: only the coloured Claude Code and Codex marks, the chosen model's own icon, and rule 37's single AI mark. For every other icon, which fact does it carry? None means cut it.
18. Does any screen look like the old app? Settings, Commands, the project menu, the Aa menu and the review dialogs are all in the mobile style.
19. Open a PR: title and number appear at once, there is no loading screen, and skeletons only stand in for waiting content.
20. Double-tap Analyze, then cancel it: the first tap responds, the second does not cancel, and a restart is offered.
21. Ask works on an unanalysed PR.
22. Every tappable-looking thing does something, and tabs are easy to hit.

The checklist is the canvas checklist (1 to 10, with the three Row W checks as 11 to 13) followed by the walkthrough checks (14 to 22). The canvas notes number Row W's checks 10 to 12, which collides with its own check 10, so they are renumbered here.

## Enforcement

Each layer catches what the one before cannot. All of them run in one command and in CI (`android-design` job), need only Node, and are never skipped: a failing check is fixed, not waived.

```
pnpm run android:design           run every check below
pnpm run android:design:update    rewrite the generated files and the baselines, then review the diff
pnpm run android:design:test      vitest suite of the checks themselves
```

1. **tokens.** `design.md lint` (from `@google/design.md`) on this file: it fails on a broken reference. Then the Kotlin is compared with the front matter: every colour and its dark pair, every type role (family, size, weight, line height, tracking), `PullsRadius` and `PullsSpace`. A colour without a dark pair fails. `app/src/main/assets/book/spec.json` is written from this file and fails when stale. Rules 12 and 47.
2. **raw-values.** A ratchet. It counts raw hex colours, raw font sizes and raw radii in the Kotlin per file, text set below 15 (`xs`, `compact`, `mini`, `micro`), uppercase and tracked text, and raw shadows (`.shadow(`) outside the shadow helper, and compares them with `raw-values.baseline.json`. The count may only go down: a new raw value fails, and a file that drops below its baseline fails until `android:design:update` lowers it, which locks the gain in. A line can opt out with `// raw-value-ok: <reason>`. Rules 14, 45 and 47.
3. **bans.** Importing a Material widget the kit replaces (buttons, switches, checkboxes, text fields, dialogs, tabs, cards, dividers, progress indicators, FABs), Material icons or Android speech input, and an outgoing share, fail. Material's `ModalBottomSheet` and `DropdownMenu` are only for the kit's wrappers in `ui/design`. The web-port kit (`Components.kt`), its Phosphor icons, the waiting components (`Loading`, spinners) and pull-to-refresh are banned for new code. The uses that exist today (three Material sheet or menu imports in `AiParts.kt` and `Chrome.kt` among them) are counted per file in `bans.baseline.json` and may only fall. Bans lift in the config at the top of `bans.mjs` or with `// ban-ok: <reason>` on the line, never silently. Rules 1, 5, 8, 10, 11, 22, 28, 38, 48, 49, 51 and 52.
4. **book.** `book.json` is the one typed list of UI Book pages and components. Every page has a route in `UiBook.kt`, and every route is listed. Every component has exactly one default variant, and every named composable exists. Every public composable in `ui/design` has a page or an exemption with a reason in `book.exempt.json`. Every `components` token here is rendered in a `DesignSpecimen`, or exempt with a reason. `sources.json`, the code and props the Book shows, is read from the Kotlin and fails when stale. Rules 44, 45 and 47.
5. **rules.** A check on this file's own list. Rules 1 to 65 are contiguous, each has a source and a `Checked by` line naming a check that exists, and each check enforces exactly the rules that name it. The checklist is contiguous too. `rules` is not a `Checked by` name: it checks the rules, not the code.
6. **Measure on the phone.** The UI Book's Component specs page measures each `DesignSpecimen` and prints `name ok` or `name.size: expected 44px, rendered 52px`. It replaces the browser render check the web UI Book runs, because Compose cannot be measured from Node. It runs when someone opens the page.
7. **Review against the checklist.** A person or an agent walks the screen against the checklist above and the rules, and reports breaks with `file:line` and the rule number.

## UI Book

Settings has a UI Book row at the bottom. The book is a set of screens inside the app, built from the same kit, so what it shows is exactly what ships. It follows the web UI Book: one typed page list drives the index, the pager, search and the coverage check; foundations read the real tokens; every component has its own page with live previews, every state on its own copy, light and dark, variants as tabs, an Inspect toggle that reads the measured size, the props and the source read from the Kotlin at build time, usage notes and a Copy prompt that briefs an agent.

Files: `app/src/main/assets/book/book.json` (hand-written pages and components), `spec.json` and `sources.json` (generated, do not edit), and the Kotlin in `ui/book`.

To add a component: write it in `ui/design`, add its entry and variants to `book.json`, add a preview in `ui/book/BookPreviews.kt`, run `pnpm run android:design:update` and `pnpm run android:design`, then look at the page in light and dark on a phone.

## Decision log

Every difference between this file and the rendered app, and how it was settled.

- **2026-10-14, this file written from the source at version 0.6.1 (build 16).** The tokens are what `Tokens.kt` renders today, not the other way round, so no visible change. Kotlin is compared with this file instead of being generated from it: `Tokens.kt` carries computed properties and the app is being edited in parallel, and a generator would overwrite those edits. The checks still fail closed.
- **2026-10-14, departures are frozen, not fixed.** The raw-values and bans baselines record how far the code is from these rules today: 288 raw values (40 hex colours, 131 raw radii, 2 raw font sizes, 3 raw shadows, 106 text runs below 15, 6 uppercase or tracked) and 172 banned uses (89 Phosphor icon calls, 68 web-port kit calls, 5 Material components, 3 Material sheets or menus, 1 pull-to-refresh, 6 loading indicators). No screen was restyled to get there. Each fix lowers a number and the check asks for the baseline to be lowered with it.
- **2026-10-14, raw colours in the kit that are not tokens yet. Open.** `Kit.kt` and `AppModal.kt` hold literals for the sheet grabber (`D9D9D6`), the unchecked box edge (`C9CFCE`), the pending ring (`B9C4C5`), the orb and toast accent (`9AD3D6`) and the profile gradient (`D9B38C`, `8C5A3C`). They need tokens, with dark values. They sit in the raw-values baseline until then.
- **2026-10-14, the PR row has no page. Open.** Rule 15 says one row anatomy everywhere, but the row is private to `HomeScreen.kt` and the Tours list, so the UI Book cannot render it. Pulling it into `ui/design` is a change to the screens and was left to the work that owns them.
- **2026-10-14, forced states.** The web book forces hover, focus and active with CSS. Compose cannot, so the book forces the states a component takes as parameters (selected, disabled, loading, running, done) and shows the pressed scale on the Motion page with a live pressable. A sheet is a window and cannot be framed inside a page, so its page shows the sheet's body inline and opens the real sheet from a button.
- **2026-10-14, rule 48 and rule 37. Open.** One Lucide sparkles icon per screen as the AI mark is still in force. The walkthrough does not say whether it stays.
- **2026-10-14, rule 61 and rule 29. Open.** How the Commands menu relates to the ⋯ sheet is not decided.
