---
version: alpha
name: Pully for Android
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
  grabber: "#D9D9D6"
  # Existing fixed status-mark inks become tokens for the shared iOS extraction; no visual change.
  orb-head: "#9AD3D6"
  on-success: "#FFFFFF"
  checks-pending: "#F59E0B"
  dark-base: "#161616"
  dark-text: "#E3E3E3"
  dark-muted: "#9D9D9D"
  dark-faint: "#707070"
  dark-border: "#2B2B2B"
  dark-border-mute: "#242424"
  dark-hover: "#FFFFFF14"
  dark-active: "#FFFFFF1F"
  dark-raised: "#1D1D1D"
  dark-sunken: "#212121"
  dark-code: "#1B1B1B"
  dark-secondary: "#1B1B1B"
  dark-primary: "#1B7580"
  dark-primary-600: "#165E66"
  dark-active-text: "#6CC3CB"
  dark-success: "#3CC98F"
  dark-error: "#E8777C"
  dark-warning: "#F2A14B"
  dark-blue: "#60A5FA"
  dark-purple: "#C084FC"
  dark-gray: "#707070"
  dark-orange: "#FB923C"
  dark-amber: "#FBBF24"
  dark-accent-teal: "#4FB7C1"
  dark-accent-orange: "#F57A4F"
  dark-accent-magenta: "#C65FBF"
  dark-paper: "#1B1B1B"
  dark-paper-2: "#212121"
  dark-cta: "#E3E3E3"
  dark-on-cta: "#161616"
  dark-chip-on: "#2A2A2A"
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
  dark-grabber: "#3A3A3A"
  dark-orb-head: "#9AD3D6"
  dark-on-success: "#FFFFFF"
  dark-checks-pending: "#F59E0B"
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
  press: tint while held, nothing moves
  breathe: 1200ms reverse loop
  orb-turn: 1600ms linear loop
  orb-breath: 900ms reverse loop
  shimmer: 1200ms linear loop
  thinking-shimmer: 1200ms linear loop
elevation:
  button: 10px
  note: 12px
  bar: 14px
  search: 16px
  menu: 20px
  thumb: 3px
  modal: 40px
shadow:
  button: { y: 2px, alpha: 0.08 }
  note: { y: 2px, alpha: 0.10 }
  bar: { y: 2px, alpha: 0.12 }
  search: { y: 2px, alpha: 0.12 }
  menu: { y: 4px, alpha: 0.14 }
  thumb: { y: 1px, alpha: 0.20 }
  modal: { y: 8px, alpha: 0.22 }
shadow-layer:
  edge-y: 1px
  edge-radius: 1px
  edge-share: 0.5
  dark-strength: 2.7
  dark-max: 0.9
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
  foot-pill:
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
    backgroundColor: "{colors.chip-on}"
    rounded: "{rounded.md}"
    height: 12px
  profile-dot:
    rounded: "{rounded.full}"
    size: 40px
---

## Overview

Pully for Android is a phone app for reading pull requests: an inbox of what is waiting on you, a PR overview with a summary and a description, the diff, and a tour that walks a change in sections. It should feel calm and fast to scan. The diff is the product, so chrome stays flat and quiet, and nothing is on a screen unless it tells the reader something (rule 1).

The look comes from the Android prototype: warm paper, deep teal ink, one teal accent, white floating surfaces with a soft shadow, pill capsules, paper bottom sheets and a dark toast. Tavi's design canvas holds the reference rows (Row W is the latest). The canvas predates the code, so this file records what the Kotlin renders, and the rules below record how Tavi judges it.

The source speaks two visual dialects as of version 0.6.1. The prototype dialect (`ui/design/Kit.kt` and the `Lu` icons) is on Inbox, Tours, Overview, Diff, Tour and their sheets. The older web-port dialect (`ui/design/Components.kt`, Phosphor icons, 1dp borders, 4 to 8dp radii, 13sp meta) is on the repository list, Settings, Checkout, the command palette, the conversation and the review dialogs. Rule 49 says only the first may grow. The web-port kit is frozen: new code does not use it, and the bans check counts what is left so that number can only go down.

Dimensions in this file are written in `px` because the format needs a unit. Read every dimension as dp, and every font size as sp.

This file is the spec. The Kotlin tokens are checked against the front matter (`pnpm run android:design`), the in-app UI Book (Settings, then UI Book) renders the real components next to the values declared here, and the lints below fail when new code goes around it.

## Colors

One palette, two themes. `base` is the screen. `paper` and `secondary` are sheets and grouped backgrounds. `raised` is white cards and floating buttons, and `sunken` holds segment tracks. Ink is `text`; `muted` and `faint` are the two quieter steps, and text never fades further (rule 14).

The accent is one teal. `primary` fills checked boxes and switches, `accent-teal` is the brighter teal for progress, live dots and selection bars, and `active-text` colours links and active text. `cta` is the dark fill of the one main action on a screen. The Overview's Review and Analyze buttons and the Diff's round › are the exception: they take `primary`, the darker teal, which Tavi kept over the canvas's brighter #24929C (D2, 2026-10-15). `success`, `error` and `warning` carry status only, and the `diff-*` colours belong to the diff.

Dark mode is derived from the light set (`dark-*`). Every colour has a dark pair, and the tokens check fails on a colour without one. A screen is not done until it is right in both themes.

The Kotlin names are the properties of `PullsColors` in `Tokens.kt`. A name here is that property in kebab-case: `border-mute` is `borderMute`, `primary-600` is `primary600`. Alpha is the last pair in this file and the first in Kotlin.

Colour themes (K18). This palette is the theme named Pully, and the default. Settings, Theme (and Change theme in Commands) offers the others: OpenCode's built-in themes (https://github.com/anomalyco/opencode, MIT), converted by `android/scripts/gen-opencode-themes.mjs` from the theme files vendored beside it into `ui/design/OpenCodeThemes.kt`, one `PullsColors` for each look. Light, Dark and System still pick the look; a theme with one look (Aura, Ayu) keeps it under both. The mapping: `base`, `secondary` (and `paper`, `code`) and `sunken` are OpenCode's background, panel and element; `text` and `muted` its text roles, `faint` the muted one a third of the way into the background; borders its subtle border halfway into the background; `raised` a step above the sheet; `accent-teal` and `active-text` its primary; `primary` its primary darkened until white ink reads on it at 3:1; states and the diff washes from its own. The tokens check and the UI Book's Colors page are about this palette; the Themes page shows every theme. Code colours come from the server's highlighter and do not follow the theme yet.

## Typography

Geist carries interface text, Mona Sans carries numbers and file names, and Geist Mono carries code and branch names. Sizes come from `PullsType`. `xs` and `sm` step down one notch on the web from 768px up, so each has a `-wide` twin in the front matter; the phone app keeps its sizes on every window, because no column of it is wider than the readable width (rule 207). `xxl` is a page title (26, rule 12), `xl` a section heading, `lg` a sheet title, `row` and `body` a row title and body copy, `read` the reading size of summaries and tour text, `base` inputs, `sm` lists and meta.

Rule 14 sets the floor at 15 and bans uppercase and tracked labels. Phone meta today is partly below that: `xs` is 13, and `compact`, `mini` and `micro` are 12, 11 and 10. The raw-values check counts every use of those roles so the number can only fall. Letter-spacing exists only in `xxl`. No uppercase labels, no eyebrows.

## Layout

Phone first. The render size for a check is 390 by 844 (rule 25), and nothing sits inside the 16dp margin except full-bleed bands such as the diff and sheet backgrounds. The kit's touch target is 44dp (`TOUCH`): round buttons and capsules are 44, chips are 36, CTA buttons 52, sheet rows 52 and quick actions 72.

Spacing steps are 4, 8, 12, 16, 24 and 32. The code also uses 6, 10, 14 and 18 in places, which the scale does not list, and the checks do not count them. A sub-page keeps its context: the PR title and number stay in its header (rule 23).

The window has one of three shapes (`WindowShape` in `ui/design/WindowShape.kt`), and every page is the same page in each; only where it sits changes. A window narrower than 600dp is a **phone**: the layout above. A window 600dp wide or more but under 600dp tall is a phone held **sideways**: one page at a time in a column no wider than 600dp (`ReadableWidth`), centred, while the diff's code takes the whole width; headers sit 8dp under the status bar instead of 48dp from the top, and sheets rest at their tall height (rules 207 and 208). A window at least 600dp both ways is **unfolded**, a foldable opened or a tablet: the list (Inbox, a repository, Explore) on the left, a little under half the width and between 340 and 420dp, a hairline, and the pull request it opened beside it, each pane with its own header and foot bar, the open row marked and a sheet rising over the pane that opened it (rules 206 and 208). Nothing in any shape sits under a camera cutout or a navigation bar at the side (rule 209). Render checks for the wide shapes are 841 by 701 and 707 by 841 (unfolded) and 891 by 411 (sideways).

## Elevation & Depth

Floating things carry the canvas shadow from Row W, drawn by `floatShadow`: a soft ink drop two pixels down (`0 2px 10px` at 8% for a header circle or capsule), never an even glow all round. Compose's own elevation cannot offset a shadow, which is why it looked faint and flat on the phone, so `floatShadow` draws the drop itself from API 28 and falls back to a plain elevation below that. It comes in seven levels, named for what carries them: `button` (10px blur, 8%), `note` (12px, 10%, cards and quick actions), `bar` (14px, 12%, the bottom and comment bars), `search` (16px, 12%, the search pill and the Ask bar), `menu` (20px, 14%, four pixels down, for popovers), `thumb` (3px, 20%, the switch thumb) and `modal` (40px, 22%, a centered dialog). The `elevation` tokens are those blurs in dp. Under each drop sits a tight layer, a 1dp drop with a 1px blur at half the strength, which is the dark edge right at the border that Perplexity's shadows have. In dark mode the strength is multiplied by 2.7 so the drop still reads on the dark paper. A missing or different shadow on a floating element is a defect (rule 45).

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
- `foot-pill`: the one bar at the foot of the app, the search and Ask bars as one 52dp raised pill drawn once over the pages; a page says what it holds, and an Ask bar rests short, reading Ask (rules 196 to 198).
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

The `motion` tokens record what the kit uses today. It animates with `tween`. Durations in use: 150ms for a check filling, 200ms for chips, switches and tab ink, 250ms with the smooth-out curve for the capsule's sliding pill, 350ms in and 250ms out for the toast and 400ms for the progress ring. Live marks loop: the breathing dot at 1200ms, the orb's ring at 1600ms linear and its bot at 900ms, the skeleton's light band at 1200ms (rule 85).

Pressing tints the pressed thing flat (`active`, through `PullsIndication`, in 80ms in and 150ms out) and nothing moves, scales or ripples (rule 82). It answers with no delay. AI work in progress is a live, animated mark and never a blank (rules 18 and 43). A swiped row blurs slightly as it is displaced (rules 17, 32 and 46): the canvas row moves about 132 to 136dp with a 1.3px blur, which is what the app uses now and a side shadow, `-8px 0 18px` at 14% one way and `8px 0 18px` at 16% the other. A segmented pill's highlight slides to the tapped segment and the content fades in (rule 67). Anything that exists in both states of a screen keeps its position (rule 69). Move things with transform and opacity.

Rule 73 gives the numbers motion is moving to, taken from transitions.dev: 150ms for closes and text swaps, 250ms for opens and tabs, 400ms for panels, one smooth-out curve `cubic-bezier(0.22, 1, 0.36, 1)`, 2px of blur on small things. Menus (`MenuReveal`), tab and page switches (`BlurSwitch`), the capsule's sliding pill, the toast and the thinking line (`ThinkingSwap`) use them, and so now do icon swaps (`IconSwap`: 250ms, the old glyph shrinks to a quarter and blurs 2px away as the new one grows in), the dialog (`ModalReveal`: 250ms in from 0.96, 150ms out), the checkbox (the box fills in 150ms, then the tick is drawn in 350ms) and the pages of the app (`BlurSwitch` over the screen stack: a page that opens slides in 8dp through a 3px blur, the one under it slides away, and back slides the other way), the rows under the Summary, Commits and Conversation tabs (`PageSlide`: the rows leave in 150ms and the next ones arrive in 250ms, because a lazy list cannot sit inside an `AnimatedContent`), and the bottom sheet (`PullsSheet`, our own window since 0.7.3: it slides up in 400ms and down in 350ms while it fades through a 2px blur, follows a drag with its scrim, and a pull or flick down past a third of the way, or a fast throw, closes it; a sheet that opens half way lifts to full height when dragged or flicked up, and scrolling its content raises it first). Looping effects, the menus, tab and page switches, the thinking line, icon swaps, the dialog and the sheet hold still when the system setting "Remove animations" is on (`rememberMotionEnabled`); the older tweens (chips, switches, tab ink, the capsule's pill, the toast, the checkbox and the progress ring) do not check it themselves.

Every change moves the way the person went (rule 105). The kit decides the way, never a screen: `pageWay` in `core/PageWay.kt` turns two places into forward (the new page from the right, the old one leaving left) or backward, and `swipeStep` turns a drag and its release speed into the next or the previous page. `BlurSwitch`, `LabelSwitch` and `rememberPageSlide` each take an `order` that ranks their pages left to right and work the way out from it; a `PageMotion` tells a `BlurSwitch` when Back or a back swipe made the change, and `PageBack` moves the page with a back swipe (up to 96dp, fading to 40%) and carries it on that way when the swipe completes. Rows dragged between tabs (`PageSlide`) follow the finger and, on release, carry on the way it went at the speed it was thrown (a throw of 500dp/s counts however short), or settle back where they were; they never spring back toward the old tab first. The tab underline is one ink that slides between labels in 250ms and leans toward the next tab as the finger drags, the review's capsule pill slides the same way, the file name between ‹ and › and the Tour's stop title change with `LabelSwitch` (sideways for files, up for reading on), and Commands, pulled down from the Inbox, rises from the bottom like every sheet (H6). Pages that sit side by side (the Inbox and Tours, the Overview's tabs, the Diff's files) slide the whole width with nothing fading, both in view while they move, and settle on `PullsMotion.PageSpring`, critically damped with a threshold fine enough that nothing snaps at the end (`SidePages` with a hoisted `SidePagesState` so a header and tabs can follow and drag the pages, and the Diff's `FilePages`); a sheet's own pages travel a third of its width, and sheets settle on a spring that overshoots a little (`PullsMotion.SheetSpring`, damping 0.74, rule 108), the same spring that lifts one to its tall height when a field takes the keyboard.

## Rules

Rule numbers are one running list, so "rule 47" is unambiguous. Rules 1 to 44 come from the design canvas (Row W is the latest row) and are copied word for word from its rule notes, which Tavi wrote from his earlier recordings. Rules 45 to 65 were written from Tavi's phone walkthrough of the built app on 2026-10-14, one rule for each thing said, so each applies to screens that were not mentioned. Rules 66 to 70 come from Tavi's review of the Perplexity mobile app on 2026-10-13, kept as a reference: 66 to 70 are rules (70 was confirmed on 2026-10-14). Rules 71 to 73 come from Tavi's answers of 2026-10-14: bars beside a live indicator, the thinking text, and the motion numbers. Rules 74 to 99 come from his second phone walkthrough of the same day (build 0.7.3, 28 minutes): fading edges, two-height sheets, a neutral dark mode, pressing that never moves anything, a fixed-height Analyze card, the diff's short top, and what the AI writes.

Each rule ends with where it is checked. `review` means a person looks, against the checklist below. The other names are scripts in `android/scripts/design` and are explained under Enforcement. The `rules` check fails when a rule has no such line, names a check that does not exist, or a check names a rule that does not list it.

### Rules 1 to 12, from the L, M and N recordings

#### 1. Information first, decoration never.

An element earns pixels by telling the reader something. Initial avatars, doc thumbnails, row glyphs, FAB, Share: cut. A real author photo is information; an initial is not. The reader-enabled browser helper is a narrow exception outside Pully: its floating button names the GitHub repository read from the browser address bar and opens that repository (Tavi, 18 Oct 2026).

Source: canvas. Checked by: bans, review.

#### 2. Complete identifiers, never truncated.

A PR title in a list row takes at most two lines and ends in an ellipsis past them (rule 201); on the pull request's own page it wraps whole. Repo + number always scannable. Branch means both ends: head → base. Diffstat always coloured.

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

Sections, not stops. Buttons name their effect. Cites say where they lead and are tappable. The summary and the description are told apart by the link that switches them (rule 138), never by a word over the text.

Changed on 2026-10-16 to match later decisions (M6, rule 138). The canvas said: "Every paragraph is labelled (Summary / Description)."

Source: canvas. Checked by: review.

#### 7. Irreversible actions are never primary.

Merge lives behind the ⋯ menu. The bottom primary is the reviewing action (Review 4 files, Next).

Source: canvas. Checked by: review.

#### 8. No cards for numbers.

Stat tiles / KPI cards are ugly. No boxed mini-widgets; typographic hierarchy on a flat page.

Source: canvas. Checked by: bans, review.

#### 9. The diff is the product.

Full-width lines, hunk expanders, word highlights, line numbers. Add/remove backgrounds clearly visible. Inline thread cards on their line. Files via a drawer or menu plus ‹ ›, and a swipe on the code moves to the next or previous file (rule 93).

Changed on 2026-10-16 to match later decisions (rule 93). The canvas said: "Files via a drawer or menu plus ‹ ›, never snapped tabs or horizontal swipe."

Source: canvas. Checked by: review.

#### 10. Composer: two modes.

Comment and Ask Pully only. Split visual layout, not three text chips. No counters, no voice.

Source: canvas. Checked by: bans, review.

#### 11. Not wanted, ever.

Voice/dictation. Suggestion mode. Share on PR/tour. “Up next” inside a tour. FAB with unclear scope. The opt-in cross-app browser helper is allowed only while a browser exposes a verified GitHub repository URL; an unknown URL hides its repository-specific button (Tavi, 18 Oct 2026). “Later” as a row action. Numbered step pips. Tiny provenance labels. Step counters in progress states.

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

Swipe right opens the review, as a full Mail-style gesture; a swipe left moves the page, not the row (rule 143). No reveal-then-tap. Blur the displaced row content slightly. Keep clearance from anything revealed.

Changed on 2026-10-16 to match later decisions (M4, rule 143). The canvas said: "Swipe left archives, swipe right opens the review, as full Mail-style gestures."

Source: canvas. Checked by: review.

#### 18. AI work looks alive.

In-progress analysis is a live, animated thinking indicator that feels smart. Never show the internal step. Make it tappable.

Source: canvas. Checked by: review.

#### 19. Analyze and review name their outputs.

Analyze changes produces the summary and per-file explanations; its preset and custom actions identify any additional tour, brief or AI review passes. Find problems produces review findings (bugs, risks and nits). Both open setup with the selected model and effort before work starts (Tavi, 20 Oct 2026 clarification and recording 00:37 to 01:35, replacing the unconditional three-output wording). An unanalysed PR is a real state with a visible way in. Per-file explanations show on the file rows. (Since rule 113 the way in is the tour icon, not a card on the Overview.)

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

#### 28. The Overview's tabs are underline tabs.

Summary and Conversation are underline tabs; the summary and the description switch with a link (rule 138). Pills are spent on chips, branches and cites.

Changed on 2026-10-16 to match later decisions (K23, K29, M6, rule 138). The canvas said: "Summary / Description are underline tabs."

Source: canvas. Checked by: bans, review.

#### 29. The ⋯ sheet is on every PR screen.

Checks, GitHub, Analyze options, Review with AI and Leave a comment; Approve, Request changes, the merge, Merge when checks pass, Convert to draft and Close sit a page in, under "Approve, merge or close" (rule 116). Reachable from the PR, diff and tour headers.

Changed on 2026-10-16 to match later decisions (J29, rule 116). The canvas said: "Checks, GitHub, Analyze again; Approve, Request changes, Squash and merge, Merge when checks pass, Convert to draft, Close."

Source: canvas. Checked by: review.

#### 30. Ask is a quiet toolbar.

“Ask | ⋯” in a floating capsule beside the primary button. Never a second Ask inside a comment card. AI actions are named by a word; no icon marks them (rule 48).

Changed on 2026-10-16 to match later decisions (rule 37 withdrawn, rule 48). The canvas said: "“✦ Ask | ⋯” … A sparkle alone is not a label: AI actions get a word or a real icon."

Source: canvas. Checked by: review.

#### 31. Tour sections scroll.

One scrolling page, no Next button. Progress is the ring in the header. Room at the bottom for the Ask toolbar.

Source: canvas. Checked by: review.

#### 32. One swipe on a row.

Right = Review (accent): it commits and blurs the displaced row. Left moves the Inbox on to Tours; Archive is only in a row's hold actions (rule 143).

Changed on 2026-10-16 to match later decisions (M4, rule 143). The canvas said: "Both swipes. Right = Review (accent). Left = Archive (dark). Both commit, both blur the displaced row."

Source: canvas. Checked by: review.

#### 33. No avatars on list rows.

Identity on a row is the author name in its own column. The reader’s avatar lives in the inbox header; the author’s photo on the PR page.

Source: canvas. Checked by: review.

#### 34. Notes about a file look like comments.

An AI note on a diff is a comment card by “Pully”: white, rounded, soft shadow. Not a loose line above the code.

Source: canvas. Checked by: review.

#### 35. Search is a bottom bar on every inbox.

Profile at the top, “Search or ask Pully” floating at the bottom, no search icon in the header.

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

Ring · title · chevron is a sticky strip under the header; it updates as the next section reaches the top. No count is shown, there or above a section title (rule 97).

Changed on 2026-10-16 to match later decisions (F61, rule 97). The canvas said: "N of M · title · ring is a sticky strip … The count exists only there, never inline above a section title."

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

Source: walkthrough. Checked by: tokens, raw-values, book, review.

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

The `bans` check counts uses of the robot head, the sparkle, the compass, the chat bubble and the speech bubbles (`ai-glyph`), so new code cannot add one. The count may only fall.

Source: walkthrough. Checked by: bans, review.

#### 49. One visual dialect: every screen is in the mobile style.

A screen that looks like the old app is a defect. Seen on:

- Settings: font, spacing and pills differ from the rest, and it has the old icons. The sheet is a greyer cream than the Inbox, its close button is a bare ✕, and its segmented controls are the reverse of the Inbox capsule (rule 70). Its model selector must be the mobile one already made. Today it is a cramped dropdown that reads "Default (reco…" with "Default" shown twice.
- The Commands menu: flat rows straight on the cream with no cards, small grey icons, and rows cut by an ellipsis.
- The screen behind the project name: a breadcrumb, bare icons, a bordered filter box and select, and a loading spinner, with none of the round shadowed buttons.
- The file switcher in the Diff: plain grey counts that should be coloured as in the diff, a font that differs from the file name above it, and different files cut to the identical text.
- The toolbar that floats over a selected diff line, with a cut label and the sparkle (rule 48).

Reuse the components already built for mobile (model selector, sheets) instead of rebuilding a web version. The profile sheet is the reference for right. The same kind of thing is the same component everywhere: Ask's progress is the Tour's, an empty chat looks like a full one, and every searchable list on a sheet is the one `SearchList` (L31, L35, L36; J18).

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

Pull down must not show a refresh indicator. The app refreshes by itself and does not show it. There is no Refresh button or command anywhere ("we should be refreshing ourselves so there should be no need for the user to refresh"): a pull request takes new commits by itself, and a page reloads quietly when the app comes back to the front. Today the pull-to-refresh spinner is drawn on top of the first row's title, with no space reserved for it.

Source: walkthrough. Checked by: bans, review.

#### 53. If it looks tappable, it does something.

The project heading on Tours looks tappable and does nothing: tapping it lights a grey press highlight across the heading and then nothing opens. It should open the project. On the Overview, the project name at the end of the meta line opens a screen in the old style, so the project destination must be in the new style (rule 49). The author name on the Overview is plain text and opens nothing, since a tap on it opening the project surprised him (L21); the branch chip copies the branch name. A link or a picture inside text opens its link, even in text you can edit, and a picture is as sharp as on GitHub (L14, L15). A name or photo that stands for something opens it, so there is no separate "Open …" row (H23). This is rule 4's "tappable rows look tappable", run the other way.

Source: walkthrough. Checked by: review.

#### 54. Tabs are easy to hit.

The tabs on the Overview are hard to hit. The hit area was not measured. Rule 12 asks for generous element size, and the kit's touch target is 44dp (`TOUCH`). In the frames the tabs are plain text labels with a teal underline under the active one, with no fill or padding, and "Conversation 23" runs close to the right edge.

Source: walkthrough. Checked by: review.

#### 55. The bottom pill is the one way to find a PR.

- It reads "Search or ask Pully" as in the design (rule 35). The app shows "Search pull requests" on the Inbox and "Search tours" on Tours. With the keyboard up the pill rides on top of it.
- It takes pasted links and PR numbers as well as text.
- So the "Open pull request" field in the profile sheet goes.

Source: walkthrough. Checked by: review.

#### 56. Row gestures are the same on Inbox and Tours.

Tours rows mirror the Inbox's: a swipe left opens the tour, a swipe right moves back to the Inbox, a tap opens and a hold opens the row's actions (Open, Stop or Remove) (M4, rule 143). This follows rules 15, 17 and 32: one row anatomy, blur the displaced row. Today a Tours row is a robot icon in a ring, a one-line title cut with an ellipsis, and "Writing · about a minute" under it.

Source: walkthrough. Checked by: review.

#### 57. Swiping moves between the Overview tabs.

The Overview has two tabs, Summary and Conversation, with no counts (K29, rule 91); the summary and the description switch with a link (rule 138). Swiping left and right changes between them, with easy, clean swipes. On the Diff a swipe moves between files (rule 93).

Source: walkthrough. Checked by: review.

#### 58. Sheets with long content pull all the way up.

Two bottom sheets cannot be pulled fully up. The ⋯ sheet is cut off at "Close pull request", with Review with AI, Review through a lens, Commands and Refresh below the fold (all since replaced: one Review with AI row, no Commands row, no Refresh; rules 52, 61 and 117), and the Select model sheet is cut off after Sonnet 5.5. The Ask sheet is nearly full height, so sheets are not all one height. Rule 5 prefers sheets over pages, so a sheet has to behave like a full surface when its content needs it. Every sheet, the profile sheet included, can be lifted to the tall height (rule 77 replaced this rule's exemption for the profile sheet).

Source: walkthrough. Checked by: review.

#### 59. Menus and sheets open and close with a transition.

A menu in the Diff needs an open and a close transition. The menu at that moment is the Aa menu (Smaller, Default, Larger, Wrap long lines, Show review comments, Copy path, Open on GitHub, Load the whole file), not the file switcher. What is wrong with the motion is still Tavi's to say, and a reference showing how those transitions should look is pending. Rule 44 applies once it arrives. Rule 68 applies now: answer at once.

Source: walkthrough. Checked by: review.

#### 60. A control appears only where it works.

The Smaller / Default / Larger menu on the Tour does nothing there, so drop it. The Aa button on the Tour header opens it together with Wrap long lines and Show review comments, which are settings for code, and the Tour has no code on it. The same button on the Diff also holds Copy path, Open on GitHub and Load the whole file, which are fine. Same principle as rule 1: if it does not tell or do anything, cut it.

Source: walkthrough. Checked by: review.

#### 61. Commands is a designed menu in the app's style, with fewer things in it.

- The current one is in the wrong style. Make a new design in the same style as everything else, and make it nice.
- It held too many things. Settled (0.21.0): Review through a lens is gone and ⋯ has one Review with AI row.
- Pulling any page down opens it (rule 117).
- Settled (J30, H22): neither the ⋯ sheet nor the profile sheet has a Commands row, since a pull down opens Commands on every page.

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
Replaced by rule 94, which settled the form: the bar.

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

#### 71. A live indicator stands alone.

Rule 43 says no shimmer bars standing in for content. On 14 Oct Tavi allowed grey bars under a live indicator, as in Perplexity's "Thinking…"; on 15 Oct he saw them under Ask's steps and called them awful (L33: "the loader belongs in the button"). So no grey bars sit under a live indicator anywhere, and the answer simply arrives where it goes. Rule 51's skeletons for pages and lists whose shape is known are unchanged. The canvas text of rule 43 is unchanged.

Check: while AI work runs there is a live indicator on screen and no grey bars under it.

Source: answers. Checked by: review.

#### 72. Thinking text is a shimmering plain sentence, each step with its own icon.

Tavi asked for the AI's thinking text to look more like Perplexity's. While work runs there is one line, a present-tense verb and the elapsed seconds ("Working 12s"; "Thinking" before the first step), the verb in the muted colour and the seconds in the faint one, in tabular digits, shimmering together. The shimmer leaves the text at full strength and dims a tilted band to a third of its opacity as it sweeps left to right, 1200ms a pass; with system animations off nothing sweeps. Each step is one plain sentence about the task, never the tool, in regular weight and never italic, after a 16dp icon of what that step does, in the muted colour, in a 28dp column: a page for reading a file, a magnifier for a search, a terminal for a command, a pen for an edit, a light bulb for thinking, a pen on a line for writing, a cog for a status line and a wrench for any other tool. A failed step shows a red ✕ in a circle and a warning an amber refresh, in the same colour as their sentence. This replaces the earlier dot: Tavi asked for "its own specific icon to the left, not a bullet point". The live orb sits under the newest line on the icons' column at 16dp; in Ask it is not under the steps but turns in the field's button where Send was, and a tap on it stops the answer (L31, L33). When the line is replaced by another, the old one leaves upward through a 2px blur while the new one rises in from below, 150ms ease-in-out, 8dp of travel, a 50ms gap (transitions.dev, Thinking states). When work ends the shimmer stops and the line becomes "Worked for 2m 3s", a button with a chevron, right when folded and down when open, whose steps open under it with no line beside them: the icons make the column. The sizes taken from screenshots are estimates, not measured on a device.

Check: start an analysis and watch it. One header line with the time, a dim band passing through it. Each step line has the icon of what it does, and none is italic. When the work ends, the shimmer stops and the line goes to the past tense with a chevron. With animations off nothing sweeps.

Source: answers. Checked by: review.

#### 73. Motion uses one set of numbers.

Tavi sent transitions.dev as the reference. Durations: 150ms for closes and text swaps, 250ms for opens, tabs and page slides, 350ms for a panel closing, 400ms for a panel opening. One easing, smooth out, `cubic-bezier(0.22, 1, 0.36, 1)`, for opens, closes and slides; ease-in-out for swaps; linear for shimmer. Blur is 2px for small things and 3px for page-sized changes. Scale is 0.97 for menus and 0.96 for dialogs. Travel is 4 to 8dp for text and pages. The mapping from the library's transitions to this app's surfaces was proposed by the rules file and Tavi can retune any row: menus use the dropdown recipe, dialogs the modal one, sheets panel reveal, the capsule tabs sliding, page changes page side-by-side, the thinking line shimmer text and thinking states. Looping effects follow the system setting "Remove animations".

Check: open and close one menu, one sheet, one dialog and one toast. Each takes the time above and eases out.

Source: answers. Checked by: bans, review.

### Rules 74 to 99, from the second phone walkthrough of 2026-10-14

The 73 points of Tavi's second walk through build 0.7.3 became rules 74 to 99. Features and one-off fixes that are not rules (the repository switches, Check out a branch going, the follow-up questions) live in the plan, not here. Rule 9's "never switched by a horizontal swipe" is replaced by rule 93, rule 64 by rule 94, and the profile sheet's exemption in rule 58 by rule 77.

#### 74. Fixed edges fade. They never cut or smear.

Anything pinned at the edge of something that scrolls has an edge fade on the content that passes under it: the chips and header on Home, a sheet's header, the Tour strip, the bottom search pill. The content goes to the page colour over about 32dp at the top (longer at a tall pill, about 130dp at the bottom of Home) with a blur that grows toward the pinned edge. The blur is made from content that continues past the edge, so a row at the edge is softened and faded, never stretched or sliced. The fade stops at the pinned thing. It never blurs a chip, a button or a header. Nothing white sits behind a floating pill: the rows fade into the page and the pill carries its own shadow. Anything that scrolls sideways (the branch row, the chips, a code block) fades the same way at an edge it can still scroll toward, and stays sharp at an edge with nothing past it (`sideScrollFade`, J14, J23).
This is rule 46's first bullet, made exact.

Check: scroll a list slowly and fast under each pinned header and pill. No row ends in a hard line, no text is stretched into blocks, the chips and buttons stay sharp, and nothing white sits under a pill. On the Diff, the code fades and blurs under the pinned counts line and under the bottom bar. Scroll the branch row and the Inbox chips sideways: what runs past an edge dissolves there.

Source: overhaul. Checked by: review.

#### 75. A group title has one line under it. A heading has none.

A group title in a list (the repository titles in the Inbox) has the single hairline under it that the canvas has (rule 47). It has none above it, so the first title has nothing between it and the chips, and a title never has a line above and below at once. A section heading on a page (Files, What needs a human, Brief) has no line under it in any state of the page.
It replaces the "try a separator under Files" of B3 and B4: nothing is added, and the line under What needs a human goes.

Check: look at every heading on Home, the Overview and the Analyze result. Group titles have one line under them. Headings have none.

Source: overhaul. Checked by: review.

#### 76. Dark mode is a neutral grey.

In dark mode every surface is a grey with no colour cast: the red, green and blue values differ by at most 2. The accent stays teal. Values read off Perplexity's dark mode in his recording (frames 05:42 and 06:06): page #161616, raised surfaces and the composer #1D1D1D, chips and the segment track #212121, sheet paper #1B1B1B, border #2B2B2B, text #E3E3E3, muted text #9D9D9D. The light theme is unchanged.

Check: dark mode beside Perplexity's, with the same content. The greys match and nothing looks blue or green.

Source: overhaul. Checked by: tokens, review.

#### 77. Sheets rest at two heights.

Every sheet rests at its normal height (its content, at most half the screen) or at the tall height near the top, and slides between them. A sheet whose content is short, the profile sheet included, can still be lifted to the tall height: the room under its content is the sheet's paper. A quick throw goes to the next stop, a slow release to the nearest one, a long drag down closes. The grab bar (the lip) at the top is the handle. This holds for every sheet, the profile sheet included. The header is fixed and the content scrolls under it through the edge fade of rule 74, so the ✕ never overlaps text. The chat opens at its normal height too, with no keyboard ("it goes into this text mode immediately" was jarring). Commands is the exception: it opens at its tall height and its search takes the keyboard as the sheet rises, not after it rests, so a command can be typed at once (J11, K5). Tapping a field, or lifting the sheet, brings the keyboard up and moves the sheet to the tall height on the same spring as a sheet let go, never popping there (J10); lowering it puts the keyboard away.
It replaces the profile sheet's exemption in rule 58, which Tavi gave when he did not yet know he could not pull it up.

Check: open every sheet. It rests at the stop it should and can go to the other one. The header stays put and nothing is sliced under it.

Source: overhaul. Checked by: review.

#### 78. A sheet opened from a sheet is a page inside it.

Language from Settings, Select model from the "No tour yet" sheet, Effort from its row under Model (rule 142): the new content slides sideways inside the same sheet. The sheet and its lip stay still, the header stays fixed, the ✕ becomes ←, and back returns to where you were with the scroll position kept. The parent never closes and reopens. This is how Claude's app does it. Switching an engine inside a picker changes its list in place and does not close the sheet.
It makes rule 66 exact for sheets.

Check: open a page inside a sheet and go back. Only the content moved, the sheet did not blink, and the list is where you left it.

Source: overhaul. Checked by: review.

#### 79. A sheet's body is its list.

Files, Commits and Checks sit in the raised cards every other sheet uses, one card under each plain heading (J24, which replaced "no panel inside the panel"; K29; rules 139 and 141). Rows are bigger (at least 60dp) with whole titles that wrap, as rows are everywhere else. No instruction names a key a phone does not have ("shift-click"). Checks lists failed first, then running, then passed, and a row taps through to the check.

Check: open each of the three sheets. Whole titles and rows as big as in the Inbox, in cards under their headings.

Source: overhaul. Checked by: review.

#### 80. A narrowed view always shows the way back.

When a screen shows part of something, such as one commit of six, it says so where the reader already looks, never in a box over the page (K25: "I don't like that box"): the Commits number on the Overview reads just "3" (or "12–15" for a range) in the link colour, so it fits its cell, and the file pill on the Diff reads "Only commit 3" (or "Only commits 4–6") where the folder was, never "Commit 3 of 6", which reads like a page count (L11). Both lead back: the Commits number opens the Commits sheet, whose first row is "All commits", a range held on the sheet is named at its top, and tapping the chosen commit again goes back to all commits (L11).

Check: pick one commit. The diff says which commit it shows, and the Commits number opens the way back.

Source: overhaul. Checked by: review.

#### 81. Settings show what you are choosing.

Where a setting changes how something looks, the control is the thing itself, as a small sample. Diff layout is two small diffs you tap. Code size is three sizes with a line of code under them that resizes. Code font is a list where each font is set in its own face on a sample line. A word or an icon is only for a setting that has no look.

Check: open Settings. Every look-changing setting shows its result without leaving the sheet.

Source: overhaul. Checked by: review.

#### 82. Pressing tints. It never moves anything.

A row, a tile, a card or a sheet row tints while it is held and nothing else: no scale, no shift, no growth. Small controls (round buttons, pills, chips, the send circle) may keep their 0.97 press. A tap on a heading that opens nothing does not change colour either.
Which "these" he meant at 27:56 is my reading: he says it right after the Checks and ⋯ sheets, so I took it to be the sheet's rows and tiles.

Check: press and hold a row in each sheet, a card on the Overview and a tile in the ⋯ sheet. Only the tint changes.

Source: overhaul. Checked by: review.

#### 83. Short text is edited where it is. Confirm with a toast.

The title of your own pull request is edited where it is: a tap turns it into a field and leaving it saves. The description, which runs long, opens on a tall sheet with a proper text field and Save, and the page never scrolls under it (L13). There is no "Edit" button. A control that copies or toggles keeps its face: copying the branch shows a small toast ("Branch name copied") and the pill goes on showing the branch.

Check: tap the title of your own PR: it edits in place. Tap its description: its sheet opens. Tap the branch pill: it stays the branch and a toast says it was copied.

Source: overhaul. Checked by: review.

#### 84. Send is off until there is something to send.

A send button never sends an empty field. With nothing typed it is grey, or in Ask it is the button that opens the pull request's earlier chats (L36).

Check: open every composer empty. Send is grey, or in Ask it opens the chat history.

Source: overhaul. Checked by: review.

#### 85. Skeletons shimmer.

Every skeleton bar carries a shimmer: a soft band, about 40% of the bar's width, sweeps left to right every 1200ms on a linear curve, in light and dark. With the system setting "Remove animations" the bar sits still. A skeleton that does not move is a defect. No bars sit under a live indicator (rule 71).

Check: put each skeleton on screen for three seconds. The light visibly passes through it.

Source: overhaul. Checked by: review.

#### 86. A card that works keeps its height.

Nothing on a page moves while AI works. (The Overview's Analyze card is gone since rule 113; the rest stands for every card that works.) The Analyze card is one fixed height (72dp) in every state: idle, running and finished. Its status is one line that ends in an ellipsis, because this is not important content and tapping it shows more. The step chips are not in the card while it runs, and the log opens in a sheet. A new line or a wrapped chip never moves what is below.

Check: run an analysis and watch the page. Nothing above or below the card moves.

Source: overhaul. Checked by: review.

#### 87. One job, one control.

Stop is the orb: tapping where the orb is stops the run, and there is no second Stop pill or second icon. A repeat action is the try-again arrow with an accessibility label, not the word Redo. A control that explains itself in a line of its own ("The Analyze button runs the last choice") goes, which is rule 1.
Rule 63 holds: a second quick tap never cancels. The control that started the work shows it: while an answer is on its way the orb sits in the field's button where Send was, and nothing else on the screen says that work is waiting (L33).

Check: start a tour and ask a question. Each job has one control, and the orb is it.

Source: overhaul. Checked by: review.

#### 88. Pages slide. They never pop.

Opening a PR from a row and returning slide the new page in from the side it belongs to, with the numbers of rule 73 (250ms, 8dp, a fade, 3px of blur). The Overview's tabs are pages side by side: they follow the finger and slide whole, with no fade (rule 107). Opening from a swiped row uses the same slide as opening by a tap.
It extends rule 57.

Check: open a PR from a row by tap and by swipe, then swipe between tabs. A slide every time and the section follows the finger.

Source: overhaul. Checked by: review.

#### 89. A picker shows the short list first.

A list of models or engines shows the few most people want, newest first, and puts the rest under a small "Older models" heading at the end of the list, in a card of their own that is always there to scroll to, never folded (Claude's picker lists them the same way, under "Other models"; he called a fold inside the list "not how it looked in the video"). A row is a name only, with no ID line and no description unless the name alone is not enough. The recommended choice carries a "Recommended" pill (26dp tall, 13sp medium, a neutral grey with dark text, as Claude's is; the teal wash was "the wrong color") and the selected one a plain, larger ✓, never the word "Default" beside a check. The footnote under a list is tiny, as in Claude's (13sp, the one place that goes below rule 14's floor, because he asked for it). There is no custom model ID field. "Newest first" is read from the version in the name: the newest version of each family (Opus, Sonnet, Fable, GPT) from the newest generation, the default and any alias such as "Opus (1M context)"; every other version goes under "Older models". Effort is not in the list: it is its own row under Model (M2, rule 142). The model in use is never hidden: it is in one list or the other. A Codex name loses the hyphens between its words ("GPT-5.1 Codex Max"). Claude Code lists its models by family alone ("Opus"), with the version only in the description, so the row takes the version from there ("Opus 5.5") and sorts by it. Claude Code's recommended effort is the CLI's own default for that model, and the Effort row names the level that will run. Pi is the exception to "Older models": it serves many providers' models through one endpoint, every one of them a current choice, so its list has no "Older models" and is instead a card per provider under the same small heading, named the way the provider writes itself ("AGY", "Claude", "Codex", "xAI"), the default's provider first and each newest first (`modelSections`). The server takes the route word off each name ("Claude Opus 5", never "Claude Claude Opus 5"), lists a model once per provider and leaves out models that make images, speech or embeddings (K1 to K4).
F34 stays: tapping Quick, Standard or Deep on the Analyze options page starts the analysis. (The first digest read this as choosing a model. The recording at 13:17 to 13:41 shows him tapping a preset, finding it started at once, and calling it awesome. Choosing a model only saves it.)

Check: open the model sheet for each engine beside Claude's. The same shape.

Source: overhaul. Checked by: review.

#### 90. A path is cut from the left.

A path shows its end, because the end is what tells files apart: "…/domains/practice/components". Folders are dropped from the front whole, never cut in the middle of a name. Tapping the path line shows the whole path. The file name comes first. Titles wrap whole (rule 2); people's names keep to one line and give way only at their end (rule 111).

Check: put a long path on a file row and in the diff header. The end of the path is readable, the whole path is one tap away and no name is cut.

Source: overhaul. Checked by: review.

#### 91. Long threads fold.

Each comment in Conversation folds. A folded comment is the name, the age and a chevron on one line, with its first line dimmed under it. The first comment starts open. Bot comments, such as CodeRabbit's, start folded. "commented" is never written, because it can only be a comment. The age and the chevron sit at the right edge of every header, so they line up from one comment to the next whatever the length of the name ("align these always"); a long name keeps to one line and gives way at its end rather than push them (rule 111).
Pushed commits are not in the Conversation, and since K29 they have no tab either: the Commits number opens a sheet built like Files ("just show a bottom sheet like the files"), with "Find a commit", "All commits" first, then the commits oldest first and numbered under a plain heading for each day (rule 141). A tap opens the diff of that commit alone (K25), a long-press stretches the range from the last one tapped and leaves the sheet open. Commits GitHub did not send are named in one row that opens them on GitHub. The tabs are Summary and Conversation, carry no counts, and sit from the start of the line a word's room apart, not spread over the width (K7).

Check: open a Conversation with ten comments. Every one folds and unfolds, none says "commented", the ages are in one column, and no commit is in it. Tap Commits: a sheet with a search, every commit numbered.

Source: overhaul. Checked by: review.

#### 92. The top of a code screen is short. The code gets the screen.

The header of the Diff is the back button and the capsule, nothing else. The file's name sits at the bottom between the ‹ › buttons ("put it between the arrows"), centred in a round pill, in the face it has on the file rows (the code font would break a long name), whole, on up to three lines; under a short name the end of its folder (rule 90) and "3 of 12". A tap on it opens the Files sheet, the one place a file is picked (rule 101). The note about the file scrolls away with the code. The line with its counts, threads and Reviewed box then stays pinned at the top of the file while that file is shown, and the code passes under it through the edge fade of rule 74. There is one Reviewed box per file, in that line; there is none at the end of the file (J22). Pinned chrome is no more than a fifth of the screen. The file ends where the code ends: nothing scrolls into empty space, only room to clear the bar.

Check: open a file with a long path and a long note. Pinned chrome is no more than a fifth of the screen. The name is whole at the bottom between the arrows. Scroll to the end: the last line is the last thing.

Source: overhaul. Checked by: review.

#### 93. Swiping the diff goes to the next file. The header never shifts.

A swipe left or right on the code goes to the next or previous file, like the ‹ › buttons, and the page follows the finger. This reverses the "never switched by horizontal swipe" half of rule 9, as he asked. On code wider than the screen a swipe first scrolls the code, and changes file only once the code is at its edge; on wrapped code, and anywhere off the code (the note, the counts line, a thread card), it changes file at once. Two fingers pinch, a held finger selects, and nothing changes file while lines are selected. From the first file a swipe right goes back to the Overview; past the last file there is nothing, so the page stays where it is and never opens the Tour (K26). The capsule keeps its shape on the Diff, the Overview and the Tour: three view icons and one corner slot, which is ⋯ on the Overview and the Tour and Aa on the Diff. Swiping between the three never moves the header: the back button and the capsule are drawn once over all three pages, so only what is under them changes, and the selected segment of the capsule slides to its new place. From the Tour a swipe right goes back to the Diff. The Overview's last tab is an end too: a swipe left there goes nowhere (K8). A pinch makes the code's text bigger or smaller as the fingers move: the code is laid out again at each size and still fills the page, never a picture of it scaled into a corner (M9). The line under the fingers stays there while they move and after they lift, and the page does not pan with them (L17).
Aa opens "Text and tools" as a bottom sheet, and every choice in it shows what it does instead of describing it ("why am I not seeing what these do"): the layout first, as the two small diffs of rule 81 (Split can be picked on any phone; a file with only one side falls back to unified, H10), the code size capsule over a sample that resizes and shows wrapping, the code font as a page inside the sheet, wrap long lines and review comments as switches, then the file's own actions (copy the path, open it on GitHub, load the whole file) and one row to the pull request's ⋯ actions.

Check: swipe between files and between views. The header is the same pixels throughout. Swipe on code wider than the screen: it scrolls first. Open Aa: the diff layout is the first thing, and every choice changes a sample in front of you.

Source: overhaul. Checked by: review.

#### 94. One way to start a comment, one bar to write it in.

The only way in is selecting lines: a tap on a line number, extended by dragging down the gutter. There is no toolbar floating over the selected lines. Selecting lines turns the bar at the bottom into the comment bar: a ✕ to let the lines go, the field (its placeholder is the one place the range is written, "Comment on lines 356–358") and the send button, nothing else (K11: no "In review" toggle, no ⋯); a comment goes into the pending review. Letting the lines go turns it back into ‹ name ›. Holding a line selects it, or keeps the selection it is in, and opens a small sheet: Ask about these lines (the Ask sheet, with the lines attached as a card), Copy file and lines, Copy GitHub link. Someone who cannot comment gets the ✕ and the range. A comment thread under the line stays a card in the code (rule 9); an Ask about lines shows under them as the chat shows it, the question in its bubble and the answer as plain text with no box (rule 99). Nothing repeats what is already on screen. Tapping a file note opens Ask with that note attached as a card above the field, which a ✕ leaves out.
This replaces rule 64's "a bar you type into" and the toolbar of rule 49. He chose option B of the target designs, the bar ("I'll live with this option for now"), over the sheet of option A ("I hate this so much").

Check: comment on three lines. One bar, one field, and nothing on the screen says the same thing twice. Let the lines go: the file name is back.

Source: overhaul. Checked by: review.

#### 95. Code, flows and tables in AI text work like the diff.

A code block in a tour or an answer is the diff's code view: syntax colours, sideways scroll, pinch zoom. A flow is drawn by the app from structured data, never printed as text. A table keeps a readable width for each column (the description never below about 230dp) and scrolls sideways with a fade at its edge. Inline code shows as code and never with its backticks. Pseudocode reads as plain steps that wrap under their indent, never as coloured code cut at both edges, and a code block carries no "Code" or "text" label (L27, L30). A caption under code says what the code does and why, not a label for it (L29). A call tree and a file tree are drawn as trees with guide lines, and a file in a tree that the pull request changes opens it. A flow line that does not parse stays as words in its place while the rest of the flow is drawn. In a tour's "In the code" list the place sits on its own line and its reason under it at the full width, so a long file name never squeezes the reason into a narrow column (24:30 in the second walkthrough).

Check: open a tour with a code block, pseudocode, a flow and a table. The code scrolls and zooms, the pseudocode wraps as steps, the flow is drawn, no column is squeezed to four characters.

Source: overhaul. Checked by: review.

#### 96. What the AI mentions is a link.

Every mention of a file, line, symbol or dimension, in a note, the summary, a finding, a tour or an answer, is a link that scrolls to that place. Cites are teal text with no underline, all in the one face of the text around them, whether the AI wrote them in backticks or not (K16: "some are monospace font and some are not"). They look like what they are and sit on the text line (rule 39). A file name or `path:line` in backticks is a link when it names a changed file, and a mention of a file the pull request does not change opens it on GitHub. What is posted to GitHub links each cite to the file at the head commit. In a tour, a file or line opens that file's diff on a sheet over the tour, at the line, and the tour stays where it was (L28).
He called F43 "super, super, super important", so the analysis, tour, Ask and review instructions all say it and a check makes sure.

Check: tap every cite in an analysis, a file note, a finding and an answer on lines. Each scrolls to its place; in a tour each opens its file's diff on a sheet.

Source: overhaul. Checked by: review.

#### 97. The Tour strip is calm.

The strip is one line under the header: a ring, the title and a chevron, each in its own column so the text never runs under the ring. The title is one line with an ellipsis or two whole lines, never cut in half. The ring fills continuously with the tour's scroll position, not in jumps. "2 of 4" is not shown (rule 11). The chevron opens the sections as a bottom sheet with the read ones ticked. When the tour ends there is no "Back to the pull request" button: the header or a swipe leaves it. The strip takes the edge fade of rule 74.

Check: scroll a tour slowly. The ring fills as you go, the title is whole, and the last screen has no button.

Source: overhaul. Checked by: review.

#### 98. No machinery on content screens.

A tour or a note does not show the model name, token counts or cache numbers. They live in Settings and the model sheet. Rewrite is an icon at the card's corner with no word.

Check: search each AI screen for a model name or a token count. None, except in the pickers.

Source: overhaul. Checked by: review.

#### 99. Ask replies are plain text.

The AI's reply has no box, fill or shadow. Only your own message is a bubble, in the accent colour. There is never a bubble with dots in it. "Clear" is not a word on the screen: a new-chat button sits at the top right. Under each answer, sources are chips that scroll to the place, and two or three follow-up questions that tap to send, written each turn by a small fast model. The thinking streams as it happens (rule 72): while a step thinks, its newest two or three lines show under it in the muted colour and fold away when it ends, and the answer appears in its place as it is written, with no jump when the saved reply takes over. The source chips cover the files it read as well as the places it names. The ⋯ menu of Perplexity is not copied.

Check: ask a question. A plain reply, source chips, follow-ups, no dots bubble and no "Clear".

Source: overhaul. Checked by: review.

### Rules 100 to 103, from Tavi's comments on the target designs and his notes of 2026-10-14

His sixteen comments on the target designs artifact, and the notes he wrote in the project at 16:55, added these. The rest of those notes (pull request stacks, sorting, sticky repository rows, faster loading of huge pull requests, a classifier, and replacing "Review with") are features waiting in the backlog, not rules.

#### 100. No link-out icons.

The app never shows an arrow leaving a box, or any other "opens elsewhere" mark ("never this kind of icon. We're not a web app. We don't open links, not like this"). A row that goes to GitHub carries GitHub's own mark, and any other row says in words what it does.

Check: open every sheet and list. No arrow-out icon anywhere.

Source: overhaul. Checked by: bans, review.

#### 101. A long list can be searched, and its sections fold.

A list that can run long (the files, the repositories, the themes, a model list of more than 8, earlier chats; L4, L23, L36) has a search field at the top of its sheet (`SheetSearch`: 48dp, the card radius, the float shadow, ✕ to clear), and a row matches when every word typed is in it. The files are one sheet, opened from the Files cell on the Overview and from the file name in the Diff, with the file on screen marked. Sections of files fold under their headings on the Overview, and a fold is kept while the pull request is open; in the sheet they sit under plain headings and nothing folds (M7, rule 141). A pull request with more than 80 files opens on the Overview with every section folded, so 80,000 changed lines start as a short list of headings, and the sheet shows the first 300 rows with a row to show all of them.

Check: open a pull request of a few hundred files. Headings first on the Overview, each opens and folds, the Files sheet's search finds a file by two words of its path, and nothing stutters.

Source: overhaul. Checked by: review.

#### 102. A file row swipes like a pull request row.

A file row on the Overview swipes both ways, as an Inbox row no longer does (rule 143): a swipe right opens it, as it opens a pull request row, a swipe left marks it reviewed (or not reviewed), a tap opens it and a long-press opens its actions (open, mark reviewed, copy the path, ask about the file). The checkbox sits on the first line of text. The file name comes first, its folder under it cut from the front (rule 90), the counts at the right, and the AI's note in two lines at most.

Check: swipe a file both ways, tap it and hold it. The swipe blurs the row like the Inbox's, and the box lines up with the name.

Source: overhaul. Checked by: review.

#### 103. Searching dims what is behind.

While the search on Home waits for its first letter with the keyboard up, the Inbox behind it dims a little (half the sheet's scrim), and a tap on it puts the keyboard away. Once something is typed, the matches are what the search is for, so they are shown at full strength and take taps.

Check: tap the search pill. The list dims. Type a letter: the matches are bright and open with one tap.

Source: overhaul. Checked by: review.

### Rules 104 and 105, from Tavi's message of 2026-10-15

#### 104. A new version must be installed, and its changelog is the screen that asks.

When the server has a newer build, Pully shows only the update screen, from the first frame of a start: "Pully 0.28.0 is ready", "Update to keep using Pully.", then every version it brings over the installed one, each with its summary and bullets, and "Update to 0.28.0" as the dark main action at the foot. Nothing closes it but the update; Back leaves the app. Update downloads and installs in the app and says how far it got (a ring while it downloads, the orb while the system installs), and a failed download or install offers Try again rather than letting the reader through. The phone remembers the last offer, so the screen is there before the server answers again, and it goes when the server has nothing newer. Commands has Update Pully, which asks the server at once and starts the update, or says this is the newest version, and What's new. The phone is also told in a notification when the app is closed. What's new opens by itself once after an install the update screen did not show. The words come from `android/CHANGELOG.md`, written for the reader, not from commit messages.

Check: publish a build with a higher version and open Pully. The update screen lists what changed and nothing gets past it; Update installs it. Run Update Pully from Commands with no newer build: it says this is the newest.

Source: answers. Checked by: review.

#### 105. Motion goes the way you went.

Tavi: "Most transition animations don't seem to naturally follow the movement you're doing. For instance, when I swipe in the PR home to move between the tabs, the direction of the transition does not go into the tab we're going to." Everything moves the way the person moved it. A thing dragged by a finger follows the finger, and when the finger lets go it either settles back where it was or carries on the way the finger went; it never springs back toward the finger's start before going on. The next tab, file, view or page comes in from the right and the one left behind leaves to the left; the previous one comes from the left. Pages sit where the controls show them: tabs in their order, the review's views in the capsule's order (Overview, Changes, Tour), the stack of pages by depth. Back always goes backward, whichever view or page it lands on. A back swipe moves the page with the finger and then carries it on that way, so a swipe from the right edge sends it left. The tab underline and the capsule's pill slide to the new place, and the underline leans toward the next tab while the finger drags. A label that names where you are (the file between ‹ and ›, the Tour's stop) changes the same way: the next file from the right, the next stop rising from below. Commands, pulled down from the Inbox, is a sheet like any other and rises from the bottom (H6); a sheet thrown down carries on down. Nothing that belongs to a page pops while the page slides: it slides with it. A quick throw of 500dp/s counts as a swipe once the finger has taken the page part of the way, so a nudge never turns a page by itself (L6); a third of the way over is enough without a throw (L18), and a throw back against the drag cancels it.

Check: on the Overview drag between tabs slowly and let go short, then far, then throw: the rows and the underline go toward the tab you went to, or back where they were, never the other way first. Swipe between files and past the last one, use ‹ ›, the capsule and Back, back-swipe from each edge, pull Commands down and close it.

Source: answers. Checked by: motion, review.

### Rule 106, from Tavi's message of 2026-10-15

#### 106. The web looks like the phone.

Tavi: "Let's also have an agent that updates the web page, and makes it like the mobile app looks-wise. The more code we can deduplicate, especially styling-wise, the better." The web app is the same product as the phone app and wears the same look: warm paper, deep teal ink, one teal accent, Geist, the prototype's icons, and the same pills, cards, chips, tabs, sheets and shadows. Neither side has a look of its own. The web takes its tokens from this file's front matter, its icons from `Lu` and its fonts from `PullsFonts` (written into `packages/app/src/design` as generated files), and the phone takes its strings from the web's locale files. A colour, size, radius, shadow, icon or string is added once, at its source, and `pnpm run android:design:update` carries it to the other side. The web may lay a screen out wider, with a side list next to the content from 768px up, but every piece it is made of is the phone's piece.

Check: open the same pull request on the phone and on the web at 390 by 844, in light and dark. Same colours, type, icons, corners and shadows.

Source: answers. Checked by: web, review.

### Rules 107 to 112, from the walkthrough of 0.24.0 on 2026-10-15

#### 107. Pages that sit side by side slide whole.

Tavi, on Inbox and Tours: "It should move like screens side by side", as Grok's Ask, Imagine and Build do, and on Summary and Commits: "pages next to each other just slide, no fade." Screens that sit beside each other (the Inbox and Tours, a repository's pull requests and its tours, the Overview's tabs, the Diff's files) move the whole width of the screen with a gap between them, and nothing fades or blurs. The Diff's files have no gap: they touch, edge to edge, so a swipe between them is one smooth sheet of code (M10). The Overview's tabs are drawn before the first drag, so the first swipe from Summary to Conversation moves as smoothly as the way back (M8). The Inbox and Tours, a repository's two lists and the Overview's two tabs follow a drag anywhere nothing else takes it, and settle on the nearer screen or go on the way a throw went (`SidePages`); both screens are in view during the move, and the tab marker moves with them, frame for frame (J15). On the Overview that includes the title, the meta, the branch row and the numbers, which sit over the two tabs as one head (J21, which replaced H32's "a sideways drag on the title does nothing"); only the branch row's own sideways scroll and a file row's swipe keep their drags. The Diff's files sit side by side the same way: during a swipe, an arrow or a pick from the Files sheet, the file on screen and the one it goes to are both in view, moving together, and a file picked from far away still arrives from beside the current one, never through a blank page (J25). The glide settles on a critically damped spring with no snap at the end (J7). A sheet's own pages (Settings to What's new, Code font, Language) travel a third of the sheet's width in 350ms and blur, so the way they go is plain (H1, H4).

Check: tap Tours and drag back to the Inbox from the chips; swipe Summary to Conversation and back, once on the rows and once on the title; swipe between files; open Settings, go to What's new and back. The screens move whole side by side, nothing fades, the tab marker keeps up, and Back goes right.

Source: walkthrough. Checked by: review.

#### 108. Sheets rise from the bottom, bounce a little, and close only when pulled.

Every sheet, Commands included, comes up from the bottom and settles on a spring that goes a little past its place and comes back (H3, H6), a fifth less than it first did (J1). Scrolling a sheet's content down and back up never moves the sheet: only a drag that starts with the content at its top, or on the grabber and header, pulls it down (H2).

Check: open Settings, scroll the content down and back up fast: the sheet stays. Pull it down from the header: it follows and closes. Pull the Inbox down for Commands: it rises from the bottom.

Source: walkthrough. Checked by: review.

#### 109. Taps, swipes and outcomes are felt.

Tavi: "use Android vibrations much more." Every control that plays a cue also vibrates, with the phone's own short effects: a tick for a quiet button or a choice, a click for a main button or a switch, two for a success and a heavy one for a failure. A swipe ticks when letting go would act and again if the finger takes it back, as do Pull for commands and a page that changes under the finger; holding a row is a heavy click. Vibration follows the phone's touch feedback setting and has its own switch under Settings, Sounds.

Check: with Vibration on, tap a tab, swipe a row past its point and back, pull for commands, hold a row, switch the theme. Each is felt; with the switch off none is.

Source: walkthrough. Checked by: review.

#### 110. Editing looks like editing.

Tapping the title puts the caret where the finger landed, not before the first letter (H34). While the title is edited it is underlined in the accent and the rest of the page fades to a quarter, so it is plain that the page is now about the field (H35). The description is edited on its own tall sheet (rule 83), so the page behind it dims as under any sheet. Leaving the field brings the page back.

Check: tap in the middle of a pull request's title: the caret is there, the page behind fades, and tapping the empty page saves and brings it back.

Source: walkthrough. Checked by: review.

#### 111. Names stay on one line.

An author's name, a branch and a tour's author never wrap and never stack a letter to a line (H15, H28). In a row the name takes one line and gives way only at its end. The branch row is always one line: a long branch runs past the edge and the row scrolls sideways to read it all, and "…" is the last resort (H31). A pull request in a stack shows where it sits, "2/10" with the layers mark, under its number, not beside the name (H16). A finished tour's ring is a filled tick and nothing says "Read" (H27).

The dedicated summary in a PR's hold sheet is an exception: its full repository and author each get their own wrapping width, so none of the information is cut away. Compact list rows and the Tours hold card keep their existing layout (Tavi, 20 Oct 2026, screenshot 79142: "we should show the PRs info nicely laid out ... Here it needs to all be visible").

Check: open the Inbox with a stacked pull request by a long login, and an Overview with a long branch: names are one line, the branch scrolls, and the stack place is under the number.

Source: walkthrough. Checked by: review.

#### 112. Light and dark wipe in from a corner.

Switching Light and Dark (Settings, or Commands) spreads the new look in a circle from the top corner on the side of the chosen option over the old one, in 650ms, across the page and any sheet over it (`ThemeWipe`). Without system animations the theme simply changes. The wipe plays only when the look on screen changes: choosing System while the phone is already in the look on screen, or going back from it, changes the setting without a wipe (J2). Picking a colour theme wipes the same way, from the top right for a theme further down the list and from the top left for one higher up (K18).

Check: in Settings tap Dark, then Light: the new look grows from the top right, then from the top left.

Source: walkthrough. Checked by: review.

### Rules 113 and 114, from Tavi's picks of 2026-10-15

#### 113. A tour starts from the tour icon.

Tavi: "What if we only generate the Tour when you tap on the Tour icon. We pop a bottom sheet with a 'There's no tour currently. Do you wanna generate one?' and a model selector every time you tap on the tour and there's no tour. Then we can completely take it out of the PR page." The Overview has no Analyze card and nothing that asks for an analysis: the page is the pull request. Tapping the tour icon in the header when there is no tour, and none is being written, opens a sheet titled "No tour yet" with one line on what a tour is, the Model row (its page slides in inside the same sheet, rule 78), the Effort row under it (L25), and Generate tour. Generate keeps the sheet open and shows the work log in it, with the Tour page behind: "Reading the change" while the analysis that comes first runs, then "Building the tour" (rule 144). With a tour, or one on its way, the icon opens the Tour page as before, and its dot breathes while one is written. The other analysis passes stay in ⋯, under Analyze options. This replaces rule 19's "visible way in" on the Overview and rule 86's Analyze card.

Check: open a pull request with no tour. The Summary starts with the description. Tap the tour icon: the sheet says there is no tour yet and names the model; Generate tour keeps the sheet open on its work log. Once the tour is there, tap the icon again: the Tour page opens, not the sheet.

Source: answers. Checked by: review.

#### 114. Sort sits beside search.

Tavi picked option A of the second round: the Inbox's sort is a round button as tall as the search pill, beside it at the bottom, under the thumb, so it never hides past the edge of the chips. The chips are the three filters alone. The button's label names the order the Inbox is in, and a tap opens the Sort sheet. Tours has no order to choose, so on Tours the button leaves and the pill takes the row.

Check: open the Inbox: sort is the round button right of the search pill and the chips hold only Waiting on you, Authored and All. Swipe to Tours: the button goes and the pill widens.

Source: answers. Checked by: review.

### Rules 115 to 129, from the walkthrough of 0.29.0 on 2026-10-15

#### 115. A sheet opened from a sheet is a page of it.

Tavi: "A sheet opened from a sheet should slide in as a page with Back to the first, not close it." Nothing in a sheet closes it to open another: Text and tools goes on to Pull request actions, and the actions go on to the model, Analyze options, Checks, Review with AI, Review progress and the decisions (rule 116), each a page of the same sheet with ← back (`PrSheet`, rule 78). The phone's Back goes to the page before, and closes the sheet only from its first page (J27, J32, J33).

Check: on the Diff tap Aa, then Pull request actions, then Analyze options: each slides in, and ← and the phone's Back walk back one page at a time.

Source: walkthrough. Checked by: review.

#### 116. Decisions sit a page in and ask first.

Tavi: "Be careful with these dangerous actions... they should all be in a sub menu... and even then you should double check that I actually meant to use that." Approve, Request changes, the merge, Merge when checks pass, Update branch, Convert to draft, Ready for review, Close and Reopen sit on one page, "Approve, merge or close", a row in the ⋯ sheet. Each one opens a page that says what will happen, with the one button that does it: the review with its verdict and Submit, the merge with its method and Merge, or a line and a button for the rest (Close's button in the error colour). Leave a comment stays in the ⋯ sheet. Turning off Merge when checks pass only stops something from happening, so it does not ask (J29).

Check: open ⋯. No decision is one tap away. Open Approve, merge or close, tap Close: a page says it closes without merging, and only its button closes it.

Source: walkthrough. Checked by: review.

#### 117. Pull down for Commands on every page.

Tavi: "I should be able to pull for commands on any page. Again, consistency." Pulling any page's list down past its top opens Commands the way the Inbox does: the Inbox, Tours, a repository's lists, and the Overview, Diff and Tour of a pull request (J30).

Check: on the Tour, the Diff and a repository, pull down from the top: "Pull for commands", then "Release for commands", and Commands opens.

Source: walkthrough. Checked by: review.

#### 118. Side by side pages keep their place.

Tavi, on swiping from the Inbox to Tours and back: "It brings me back to the top. It should keep me where I left off." Pages that sit side by side keep their scroll and what is open while the other is on screen: the Inbox and Tours, a repository's lists, the Overview's tabs, and each file of the Diff (J9). The Overview's head lifts with whichever tab is in view and the other tabs follow it, so a tab never opens with the head half gone.

Check: scroll the Inbox down, swipe to Tours and back: the Inbox is where it was. Do the same on Summary and Conversation.

Source: walkthrough. Checked by: review.

#### 119. One fold heading, everywhere.

Tavi: "Collapsible sections differ... one component, used everywhere." A heading that folds rows away is the Inbox's (`GroupHeading`) wherever it is: the name, the count, the chevron that turns as it folds, a line under it while folded, and the rows leaving and coming back with motion. The Inbox, Tours and the Overview's file sections use it. Sheets do not fold: they use the plain heading of rule 141 (M7). The chevrons of a list's headings share one x whatever the name's length, because each heading takes the full width (L3, L12).

Check: fold a repository on the Inbox and a section on the Overview: the same heading, the same turn and the same motion, and the chevrons in one column.

Source: walkthrough. Checked by: review.

#### 120. The same thing sounds and feels the same everywhere.

Tavi: "You cannot use a different vibration and sound for the same element on different pages." The kit gives each kind of control its cue and its vibration, and no screen chooses its own: a tap plays its role's cue, every fold toggles, every hold is the app's one hold (`cueCombinedClickable`, never the system's buzz), and every page that changes under the finger ticks, the Diff's files included. A sheet is felt as it arrives, as it reaches another height, and as a pull down crosses the point where letting go closes it (J12, J20).

Check: hold an Inbox row, a file row, a commit and a line of code: one feel. Open a sheet: it is felt as it lands. Fold a comment and a file section: the same sound.

Source: walkthrough. Checked by: review.

#### 121. Work shows from the tap.

Tavi: "It did not give me any feedback for quite a while." Anything that starts AI work shows its running work log, the first line and its clock, in the same frame as the tap, before the server answers. The sheet or page that shows the work (Generate tour's own sheet, rule 144) never shows its "nothing yet" state first (J28, J31).

Check: tap Generate tour: the sheet shows "Reading the change" with its clock running in the same frame.

Source: walkthrough. Checked by: review.

#### 122. One state to the next with nothing between.

When AI work ends, the page goes straight from the work log to what the work made. When one pass leads to another (the first analysis to the tour), it goes straight to the next pass's log. Neither the empty page nor the start page shows in between (J34).

Check: generate a tour and watch the end: the sheet closes on the finished tour, with no "Take a tour" page between.

Source: walkthrough. Checked by: review.

#### 123. Back returns to the exact place.

Tavi: "Back always returns to the exact place." After following a link out of a page (a tour's source, a mention, a file), Back returns to that page scrolled exactly where it was, with what was open still open. For a mention in an Ask answer, that place is the Ask sheet itself, at the same scroll (J35). This extends rules 66 and 78 beyond sheets.

Check: scroll a tour halfway, tap a file it names, then Back: the tour is where it was.

Source: walkthrough. Checked by: review.

#### 124. Links in AI text are read from the repository root.

`./` and `../` are resolved without going above the root, and a folder whose name starts with a dot (`.agents`, `.github`) keeps its dot. A link to a file the pull request changes opens in the app; any other file opens on GitHub at the head commit (J36).

Check: a tour that links `.agents/skills/…/SKILL.md` opens that file, never a 404.

Source: walkthrough. Checked by: review.

#### 125. No control rings.

Tavi, on the Code font row and the gear: "This sound, I hate it... never use that sound for anything." No control plays a ringing or pitched glass tone. Default's tap, select and toggle are a click and a short knock, gone in a few hundredths of a second at every emphasis; ringing tones are kept for outcomes such as success and ready (J3, J16).

Check: tap the gear and the Code font row with sounds on: a dry click, nothing that rings.

Source: walkthrough. Checked by: review.

#### 126. A choice of more than three is a page.

A choice of more than three options is a row that opens a page inside the sheet, like Code font and Model, with a tick on the chosen one: the sound (Default, Mech, Bubble, Press) is one. Choosing a sound plays one cue in it, then goes back. Nothing explains the sounds under them, on or off (J4, J5).

Check: in Settings, Sound is a row with the chosen one on the right; it opens a page, and picking one plays it and comes back.

Source: walkthrough. Checked by: review.

#### 127. What's new reads like a changelog.

Tavi: "Think of how good professional changelogs look." Each version is its own block: the version as a small header, a New tag on the newest, the summary as a bold headline, then one bullet per line with an accent dot and room between them, and a hairline between versions. A version may sort its bullets under `### New` and `### Fixed` (J6).

Check: open What's new: each version stands apart, the newest tagged New.

Source: walkthrough. Checked by: review.

#### 128. What the phone already has shows at once.

What one screen has already loaded is never fetched again before another shows it: the profile photo in the profile sheet is the one the header drew (J8), and a pull request opened from the Inbox shows its branches from the Inbox row, with the title and the size, before GitHub answers (J13). The model sheet shows the names it last had at once, even after a restart, and refreshes them quietly (L22).

Check: open the profile sheet: the photo is there with it. Open a pull request: the branch row is there in the first frame.

Source: walkthrough. Checked by: review.

#### 129. A long conversation can be narrowed.

Tavi: "They're hard to navigate because sometimes there's a lot of them." When both people and bots spoke, chips over the Conversation narrow it to All, People or Bots, with how many each holds; a narrowed one shows all of its comments, not the last 30 (J19).

Check: open a pull request with bot reviews and tap People: only what people wrote is left.

Source: walkthrough. Checked by: review.

### Rules 130 to 139, from the walkthrough of 0.31.0 on 2026-10-15

#### 130. Nothing scrolls over the buttons.

What scrolls on a page goes under the header, never over it: the back button and the capsule stay sharp and whole however far the page scrolls ("these buttons become semi transparent… hideous", K6). The Overview's head goes up until only its tabs are left, and they stay pinned under the header on the page's colour.

Check: scroll a long Summary. The buttons stay whole, the tabs stay pinned under them, and nothing reaches the status bar.

Source: walkthrough. Checked by: review.

#### 131. What does not change between pages stays still.

A bar that names the page, like the Diff's counts line (+3 −0, threads, Reviewed) and its bottom bar, stays in place while the pages slide under it; only its words change, coming in the way the pages went (K9: "that top part I don't think needs to be transitioned"). Pulling a page down for Commands moves the page, not the bars pinned to the bottom: Ask and Review stay where they are (L9). A swipe always ends on a page: a gesture cut short still lets go, so no two pages stay side by side (K10).

Check: swipe between files. The counts bar does not move, its numbers change. Swipe and lift a second finger halfway: the files settle.

Source: walkthrough. Checked by: review.

#### 132. Every field pill is Home's search pill.

A pill to type into at the bottom of a page has the Home search pill's size, face and placeholder style, and while it waits for its first letter the page behind it dims, as Home's does (K12: "a convention we use in multiple places").

Check: tap the Tour's Ask bar. It looks like Home's search, and the tour dims behind it until you type.

Source: walkthrough. Checked by: review.

#### 133. Work in progress is calm.

A running log shows its newest three steps, every line in the same grey with its icon centred on the words. A step that failed while the work carries on is a plain line, not red; amber is only for a retry or a rate limit. No line carries "Active 1s ago · 2s elapsed": only a stalled step says how long. Progress the page already shows, like "Wrote 3 of 6 stops", is not repeated in the log (K13: "too many things happening at once").

Check: start a tour. The log shows three quiet lines, nothing red or amber unless the engine is retrying.

Source: walkthrough. Checked by: review.

#### 134. New content arrives, it does not pop.

A tour seen for the first time brings its stops in one after another, each fading in and rising into place; one seen before, or a card scrolled back into view, is simply there (K14: "an animation of the things actually appearing on the page"). Content that arrives never moves the reader: a streaming answer follows the end only while the reader is at the end and stops the moment they scroll up (L32), and the stops of a tour being written show under its progress without moving the page (L26).

Check: open a freshly written tour. The stops come in one by one. Leave and come back: they are already there.

Source: walkthrough. Checked by: review.

#### 135. A pinned heading is never cut.

Every heading, open or folded, sticks whole at the top of the list when it gets there, and the next slides up under it and takes its place, so none is ever half under the chips (K19, L2).

Check: on Home, scroll past a project, open and folded: its heading sticks whole until the next one takes its place.

Source: walkthrough. Checked by: review.

#### 136. Only the author's actions on the author's pull request.

Archive and editing the title or description are for pull requests the viewer opened: on someone else's there is no Archive in its hold actions (K20) and the title and description are plain text (K21). Restoring an archived one is always there.

Check: open a pull request someone else opened. Its title does not edit. Hold its Inbox row: no Archive.

Source: walkthrough. Checked by: review.

#### 137. A page opens in its final shape.

While a pull request opens, the page is drawn with its own header, numbers line, tabs and bottom bar in their places, with bars where a number is not known yet; the button counts the files the Inbox knew of. When the page arrives only words fill in: nothing moves, no "0" turns into 50 (K22). What has shown stays until something newer replaces it, and the main action is never greyed while the rest arrives (L5).

Check: open a pull request from the Inbox. Nothing shifts as it loads.

Source: walkthrough. Checked by: review.

#### 138. Two versions of a text switch with a link.

Where one text has two versions (the AI summary and the description), a quiet link offers the other ("Show the description", "Show the AI summary") with no word before it naming the one shown (M6); the texts fade into each other. No pill switch (K23).

Check: open a summarised pull request. One link switches the text, and nothing names it.

Source: walkthrough. Checked by: review.

#### 139. Lists on sheets are one pattern.

Files, Commits and Checks are the same sheet: a search ("Find a file", "Find a commit", "Find a check") that also keeps the first heading clear of the ✕, the sheets' plain headings (rule 141), rows in cards whose title takes at most two lines and whose detail one, and added and removed lines side by side as on the Inbox (K24, K27, K28: "we gotta… actually standardize it").

Check: open Files, Commits and Checks. Same search, same headings, no name wraps past two lines.

Source: walkthrough. Checked by: review.

### Rules 140 to 145, from the walkthrough of 0.37.0 on 2026-10-16

#### 140. Jev stays out of the rows until it is asked for.

An Inbox row shows nothing from Jev unless the list is sorted Riskiest first or grouped by kind, and then only one short risk word under the number, in its column, so the row is no taller than without it; a stacked row, whose column holds "2/10", shows it only as the stack mark turning amber when the risk is high. There is no kind and risk line under the title (M1: "do not run it by default", rows "way too tall"). This replaces the third line of the Jev entry in the decision log.

Check: open the Inbox on Recently updated: no row says Feature, Fix or a risk. Sort Riskiest first: a risk word sits under each number and the rows keep their height.

Source: walkthrough. Checked by: review.

#### 141. Sheet headings are plain.

Every bottom sheet heads its sections the way the Settings sheet does: a small muted heading with no line under it, no count and no chevron, and nothing on a sheet folds. Checks, Commits, Files, the model list and every other sheet use the one heading. A sheet's first field (a search) sits clearly further under the ✕ than before (M7: "it has to be the same logic everywhere"). This replaces the fold headings of rule 139 and L12 on sheets; pages keep their fold headings.

Check: open Checks, Commits, Files and the model sheet. Their headings look like Settings' headings, with no lines, numbers or chevrons, and the search has air under the ✕.

Source: walkthrough. Checked by: review.

#### 142. Effort is a row of its own under Model.

Wherever a model is chosen (Settings, the Analyze sheet, the "No tour yet" sheet) the effort is its own row right under Model, and the model list ends with the models, so nobody scrolls past every model to set it (M2).

Check: open Settings. Effort sits under Model; open Model and scroll to the end: there is no effort there.

Source: walkthrough. Checked by: review.

#### 143. A row swipes one way; the other way moves the page.

No row archives on a swipe; Archive is only in a row's hold actions (M4: "I don't think we need archive at all"). On the Inbox a swipe right opens the review and a swipe left moves on to Tours. On Tours it is the mirror: a swipe left opens the tour, from the right edge, and a swipe right moves back to the Inbox; holding a tour row opens its actions. Once a swipe has taken a drag, letting go is not a tap: a short sideways drag springs the row back and opens nothing (L16).

Check: swipe an Inbox row left: Tours comes in. Swipe a Tours row right: the Inbox comes back. Swipe a Tours row left: the tour opens.

Source: walkthrough. Checked by: review.

#### 144. The sheet that started the work shows the work.

Generate tour keeps its sheet open and shows the work log there, with the Tour page behind it; the sheet can be closed, and the tour icon or the one quiet line on the Tour opens it again. The page shows no work log of its own, so nothing on it moves while the tour is written, and it has no Ask bar until there is a tour. The sheet closes when the tour is there. While the tour is written its plan and each stop already written show under the progress, in the places the stops will take (L26). A tap that comes before the pull request has loaded waits for it, so a button never looks dead (M11, M12).

Check: tap Generate tour: the sheet stays and the log starts in it. Close it and tap the tour icon: it comes back on the log. The Tour behind has one "Writing the tour" line and no Ask bar.

Source: walkthrough. Checked by: review.

#### 145. The profile shows your year on GitHub.

The profile sheet is a profile, not only switches: under a bigger photo and name it shows the last year on GitHub, the contributions as a big number over a grid of the last half year's days, then commits, pull requests, reviews and the day streak, and the year as monthly bars. The numbers show at once from the last answer, never behind a loading screen (M5).

Check: open Profile: the numbers and both graphs are there; open it again: they are there at once.

Source: walkthrough. Checked by: review.

### Rules 146 to 173, from the walkthrough of 0.38.0 on 2026-10-16

The recording of 16 Oct at 12:10 (N1 to N39, in `/mnt/project-files/design/feedback-2026-10-16-0.38.0.md`). He named shadcn's AI components (shadcn.io/ai) as the model for Ask: their behaviour, written in Kotlin.

#### 146. A fold heading reads "Name (count) ⌄".

The count sits in parentheses right after the name, and the chevron right after the count, all on the name's line, as Claude's own lists write "Done (44) ⌃". A long name is cut at its end so the count and the chevron stay whole. Anything else the heading carries, such as a section's + and −, sits at the right edge (N1, N35: "we swap it everywhere").

Check: on the Inbox and the Overview's Files groups, every chevron sits right after its "(n)".

Source: walkthrough. Checked by: review.

#### 147. Numbers in a list line up in one column.

Every count, diffstat and age in a list sits right-aligned in a column of fixed width, in tabular figures, so one more digit never moves a column. A heading's + and − use the rows' columns (N35: "those two are rules").

Check: draw a vertical line through the +, the − and the age on the Inbox and on the Files sheet; each is straight.

Source: walkthrough. Checked by: review.

#### 148. A pull request row is a state tile, a title and one meta line.

On the left is a square tinted with the state's colour, carrying the state's mark: open, draft, merged or closed. A draft differs from an open pull request by that tile, never by "[draft]" in its title. Beside it is the title, wrapped whole as rule 2 says, and under the title one line: the number, the stack place or Jev's risk word, the author, then checks, diffstat and age in their columns. A tour row carries its ring in the same tile. This is Claude's own list of pull requests (N2, N4). This replaces "under its number" in rule 111: the stack place now follows the number on the meta line.

Check: a draft and an open row differ by the tile alone, and no row has a second line under its title except labels on a repository page.

Source: walkthrough. Checked by: review.

#### 149. Every field is the kit's one field.

Search pills, Ask bars, sheet searches, Commands and the fields in sheet cards are all `FieldText`, framed by `FieldFrame`. They share one height, one inset, one text style, the muted placeholder in that style, the teal cursor and the same distance from a button at the end. On a page the frame is the pill; on a sheet it has the cards' corner and sits under the ✕ as rule 141 says. This carries rule 132 from the pills to every field (N6: "go through the entire project and standardize this stuff").

Check: a raw text field anywhere but the kit fails the bans check; compare Home's search, the Tour's Ask bar, the Ask sheet's field and Commands' search side by side.

Source: walkthrough. Checked by: bans, review.

#### 150. No Ask button: the ✕ becomes Send.

Home's search has only its ✕. While the text ends in "?", the ✕ fades into the primary Send circle, which asks the question, and it turns back when the "?" goes (N8: no "Ask" button "anywhere").

Check: type "what changed", then "?": the ✕ becomes Send. Delete the "?": the ✕ comes back.

Source: walkthrough. Checked by: review.

#### 151. Text fades where a field hides it.

Words that have scrolled past an edge of a field fade into the field at that edge: start and end for one line, top and bottom for several. An edge with nothing hidden has no fade (N9).

Check: type past the end of Home's search: the start fades and the end does not; move the cursor to the start and they swap.

Source: walkthrough. Checked by: review.

#### 152. The app's words have a font setting too.

Settings has a Fonts section with App font and Code font, each a page of faces shown in themselves. The app font sets every type role but the numbers' face and the code face, and Geist stays the default (N10).

Check: choose System serif: every screen's words change at once, and numbers and code keep their faces.

Source: walkthrough. Checked by: review.

#### 153. Older models are older within their tier.

A model goes under "Older models" only when a newer version of its own tier is listed or it is from an older generation. Its tier is its name less the maker and the version (Opus, Sol, Astra, Luna), so a lineup that shares a version number keeps each tier's newest at the top (N7: Sol, Astra and Luna are the new lineup). This replaces "of each family" in rule 89.

Check: Codex lists GPT-6.1 Sol, GPT-6 Astra and GPT-6 Luna first, and GPT-6 Sol under Older models.

Source: walkthrough. Checked by: review.

#### 154. Pseudocode is code.

Pseudocode sits in the code well in the code face, its keywords a little heavier, with a guide line for each indent level, lines that wrap under their own indent and the copy button every AI code block has (N13: "I still don't like the pseudo code", N18).

Check: open a tour stop with pseudocode: it reads as code, nothing is cut off, and Copy shows the toast.

Source: walkthrough. Checked by: review.

#### 155. A flow is steps on a rail.

Each part of a flow is a numbered card on a rail, and each hop is the rail with an arrowhead and what moves along it beside it (N19).

Check: a flow shows numbered cards joined by arrows down one rail.

Source: walkthrough. Checked by: review.

#### 156. A call tree reads at a glance.

A call tree sits in a well, with guide lines that end in a dot at each row. A chain such as `A → B → C` puts each step on its own line with its arrow in a column under the name (N16: "really easy and nice to look at").

Check: a chain of three calls stands as three aligned lines.

Source: walkthrough. Checked by: review.

#### 157. The app finds the names the AI did not link.

Inline code or a code-shaped word that names a changed file, such as `PrTourScreen`, is a link that opens that file, whether the AI linked it or not (N17).

Check: a file's name in a tour is teal and opens the file.

Source: walkthrough. Checked by: review.

#### 158. Code links never leave the app.

A code link opens in Pully, never on GitHub: a file in the diff opens at its line, near the top and scrolled to its left edge, and a file outside the diff opens on a sheet, read at the head commit and scrolled to the line (N20, N21).

Check: tap an "In the code" row for a file outside the diff: a sheet opens at the line and no browser opens.

Source: walkthrough. Checked by: review.

#### 159. The Tour's ⋯ is the tour's.

On the Tour, ⋯ opens at once on the tour's own things: Model and Effort, Analyze options, Regenerate tour, Ask about this tour and Switch to the diff, with the pull request's actions one row away (N22, N23).

Check: on the Tour, tap ⋯: the sheet is up at once and starts with the model.

Source: walkthrough. Checked by: review.

#### 160. Commands follow the page.

With no search, Commands opens with "Suggested", the page's own actions (Ask and Switch to the diff on a tour; Review files, Ask, Leave a comment and Approve on the Overview; the next file and Mark reviewed on the diff), and General comes last (N14). With a query, sections follow their best matching command, so Update Pully comes before unrelated Files actions when typing update (Tavi, 20 Oct 2026 controls feedback).

Check: pull for Commands on the Tour, the Overview and the diff: each starts with its own Suggested. Type update: Update Pully precedes unrelated Files actions.

Source: walkthrough. Checked by: review.

#### 161. Tour stops fold.

Tapping a stop's title folds it to the title, so a long tour scrolls fast, and the fold stays for the session. Going to a stop opens it (N15).

Check: fold a stop, leave the Tour and come back: it is still folded.

Source: walkthrough. Checked by: review.

#### 162. One Ask bar.

The Tour and the Overview ask through one bar. Empty, it shows the history button when the pull request has earlier chats (rule 193); once something is typed, Send. Sending carries on the last conversation and opens the sheet, and a tap on the empty bar opens the conversation there is. The dim behind the focused bar covers the header too (N24, N28, N29).

Check: on the Tour, focus the bar: the header dims, and typing turns the history button into Send.

Source: walkthrough. Checked by: review.

#### 163. Ask reads like a chat app.

The Ask sheet opens tall at once and may be dragged to half. A streaming answer is followed only while the reader is at its end, and a drag up stops the following; a sent question goes to the top of the sheet; a round button returns to the end. The field floats over the conversation, which shows through under it, with no band (N25, N26, N27; shadcn's Conversation).

Check: scroll up while an answer streams: the sheet stays where you are.

Source: walkthrough. Checked by: review.

#### 164. A caption is one sentence beside its kind.

A sketch's or a diagram's kind and its caption are one paragraph above it, and the caption is one short sentence (N33).

Check: a diagram's caption wraps under the word Diagram, not beside it.

Source: walkthrough. Checked by: review.

#### 165. Conversation starts with what people said.

The description is in the Summary tab, so Conversation does not open with it again. With nothing said, it reads "Nobody has commented yet." (N30).

Check: the first item in Conversation is never the description.

Source: walkthrough. Checked by: review.

#### 166. A bot is whatever GitHub says is a bot.

People and Bots split on GitHub's own data: the actor's Bot type or a login ending in "[bot]", never a list of names (N31).

Check: no list of bot logins exists in the app or the server.

Source: walkthrough. Checked by: review.

#### 167. The Overview's bottom row is the Ask bar alone.

The bottom row is the one Ask bar, "Ask about this pull request", the Tour's own, with nothing beside it. ⋯ is only in the header (N32: "like the Tour"). The round arrow to the review that sat beside it read as a second, highlighted Ask button, so it went (P2, rule 191): the diff is the header's, and Approve is in ⋯ and Commands.

Check: there is no text button, no round button and no ⋯ at the bottom of the Overview or its opening page.

Source: walkthrough. Checked by: review.

#### 168. Only the branch lines scroll away.

As the Overview scrolls, the repository, author, branch, stack and Jev lines go up under the title. The title folds to one line and stays with the Changes, Commits, Files and Checks row and the tabs (N38).

Check: scrolled to the end, the title, the numbers row and the tabs are still on screen.

Source: walkthrough. Checked by: review.

#### 169. A long list shows a few rows, then "Show N more".

A long list on the Overview (file groups, a group's files, what needs a person) shows its first five rows, then a hairline and "Show N more ⌄", which opens the rest in place. It is not a fold, and a single hidden row is shown rather than offered (N5, Claude's lists).

Check: no Overview list shows more than six rows before "Show N more".

Source: walkthrough. Checked by: review.

#### 170. Say a shared group name once.

File groups in a row that start with the same "Prefix › " sit under one line saying the Prefix, and each says only the rest, on the Overview and on the Files sheet. The AI is asked for short names that never repeat their parent's (N34).

Check: no two group headings in a row start with the same words.

Source: walkthrough. Checked by: review.

#### 171. The AI review sheet is a plain sheet.

"AI review (n)" is the sheet's heading; one capsule switches All, Bug, Risk and Nit; each finding is one row with the Files sheet's check. There is no Select all and no card inside a card (N36).

Check: the findings sheet has one level of cards and no Select all.

Source: walkthrough. Checked by: review.

#### 172. Two texts never stand in columns.

When two texts sit side by side and one could wrap, they become one paragraph that runs on under the first: a path after "Worth a look ·", a brief's label and its value, a finding's kind and title (N37: "almost a rule I personally have").

Check: no row holds a label beside text that wraps into a narrow column.

Source: walkthrough. Checked by: review.

#### 173. The pull request's ⋯ is short.

⋯ on a pull request offers Leave a comment, the decisions (approve, merge, close) and Find problems, with GitHub, Copy link and Analyze changes. Analyze changes and Find problems open model-visible setup; launch rows are actions without selection ticks (Tavi, 20 Oct 2026 clarification and recording 00:37 to 01:35). It has no Model or Analyze options rows, no Checks tile and no Review progress, since the checks are pinned at the top (N39).

Check: the ⋯ sheet fits on one screen with none of those rows.

Source: walkthrough. Checked by: review.

### Rule 174, from Tavi's message of 2026-10-16

#### 174. The changelog shows what changed.

Tavi: "From now on let's make sure we have images in the changelog too, showing the stuff, or little gifs if its animated." Every changelog section from 0.40.0 on has at least one picture of what changed, a short GIF where it moves, on its own line under the bullet it shows. The update screen, What's new and the version page in Settings show each picture under its bullet, full width with the cards' corner. Pictures live in `android/changelog/<version>/`.

Check: open What's new on 0.40.0: the bullets have their pictures, and the GIFs move.

Source: answers. Checked by: review.

### Rules 175 to 189, from the walkthrough of 0.39.0 on 2026-10-16

The recording of 16 Oct at 13:06 (O1 to O22, in `/mnt/project-files/design/feedback-2026-10-16-0.39.0.md`), read from the transcript and the frames.

#### 175. A link that arrives closes what was open.

A pull request link shared to Pully, or tapped in another app, first sends any open sheet down the way a sheet closes, then opens the pull request on top of the Inbox. Settings is closed too, so coming back from the pull request finds the Inbox, not a sheet left open (O1: "instead of going down nicely", O4: "I don't think that's good").

Check: open Profile, share a pull request link to Pully: Profile slides down, then the pull request opens. Open Settings, tap a link in WhatsApp, go back: the Inbox shows with no sheet.

Source: walkthrough. Checked by: review.

#### 176. Back on the Inbox asks before Pully closes.

Back on the Inbox, the page Pully closes from, opens a small sheet, "Close Pully?", with Close Pully and Stay; it never closes the app on the first press (O5).

Check: press Back on the Inbox: the sheet asks; Stay keeps Pully open, Close Pully closes it.

Source: walkthrough. Checked by: review.

#### 177. A setting Android owns says where it stands and how to change it.

Where a choice belongs to Android and Pully cannot make it (which app opens github.com links), the row says On or Off from Android's own answer and opens a page that lists each part of that answer, says in one sentence what to turn on, has one button to Android's page, and explains what Android does not allow, such as a link tapped inside a browser staying there. Pully never shows such a setting as a switch it cannot keep (O2, O3).

Check: in Settings, GitHub links says Off while any address is off; its page lists Open supported links and each address, and coming back from Android's page shows the new answer.

Source: walkthrough. Checked by: review.

#### 178. Numbers are there at once.

A page of numbers (Profile's year on GitHub, Stats) shows the last answer the moment it opens, kept on the phone between launches, and refreshes underneath; Pully asks for both a moment after it starts, so even a first open has today's numbers. A first open on a new phone holds the cards' places until they come (O6, O10; rule 145).

Check: open Profile and Stats after a restart: both show numbers at once.

Source: walkthrough. Checked by: review.

#### 179. A graph answers a finger.

Touching a graph reads it out: a tap or a drag along Profile's days or months shows that day's or month's count in the big number's place, with a tick as the finger crosses each one, and a tap on the same one again puts the total back (O7: "I would like to interact with it somehow").

Check: drag along the day grid: the number follows the finger, day by day.

Source: walkthrough. Checked by: review.

#### 180. A sheet's first swipe scrolls it.

A swipe up on a sheet at its normal height lifts it and carries on into its content in the same movement, so the first swipe on a long sheet scrolls it instead of stopping at the tall height (O8: "a little hard to scroll down ... just at the start").

Check: open Settings and throw it upward once: it lifts and its rows scroll.

Source: walkthrough. Checked by: review.

#### 181. Stats shows what the AI used and cost.

Stats has an AI usage section: tokens in, out and from cache, and the spend, then a table by feature (tours, summaries, analysis, AI review, Ask) and a card per model with its runs, a bar for its share and its tokens and cost. A cost the engine never reported shows "–", never $0.00, with one line saying some runs were not priced (O9).

Check: after a Codex run, Stats shows its tokens and "–" for its cost, and a Claude Code run shows a price.

Source: walkthrough. Checked by: review.

#### 182. No line explains what a button already says.

A sheet holds only what does something: no sentence under the title saying what the sheet is for when its title and its button say it ("No tour yet" and Generate tour). The first card sits at the sheets' gap under the ✕ (O11, O12; rule 141).

Check: open "No tour yet": the model card is the first thing under the ✕, with the same air as Settings' first card.

Source: walkthrough. Checked by: review.

#### 183. Work in progress rests at the foot of the page.

While a tour is written, its work log's sheet can be pulled down but not away: it rests at the foot of the Tour as a lip, the sheet's top edge with its handle, "Writing the tour" and the step it is on, and a pull up or a tap opens the sheet again. The page itself has no "Writing the tour" card, so it never looks finished before it is. The lip goes when the tour is there (O13). This replaces the one quiet line of rule 144.

Check: tap Generate tour, pull the sheet down: the lip stays at the bottom; pull it up: the log is back.

Source: walkthrough. Checked by: review.

#### 184. A work log grows downward, and every step opens.

The tour's work log lists every step, the newest at the bottom, with no "+4 more" squeezing older ones away. A tap on any finished step opens what is known about it: how long it took, what it read or found, and the rest of a title cut at two lines (O14, O15).

Check: watch a tour being written: the log only grows; tap "Wrote the answer": it opens.

Source: walkthrough. Checked by: review.

#### 185. What is still being written shows a loader, and the picker fills in.

A tour section not written yet shows its title and a shimmering loader where its words will go, never grey text. The section picker at the top is there from the plan on: its ring fills as sections are written, and the Sections sheet marks each one still to come with the live dot until it is written (O16, O17; rule 85).

Check: while a tour is written, the strip is at the top and its ring grows with each section; unwritten cards shimmer.

Source: walkthrough. Checked by: review.

#### 186. A hold menu names what it holds and groups its actions.

Holding a pull request opens a sheet headed by its number, with a card naming it (state, title, repository and author, diffstat) the sheets' gap under the ✕, then its actions in two headed groups: what Pully does with it (Start a tour, Open repo, Archive) and GitHub's (Open on GitHub, Copy link). The PR summary gives its whole title, repository and author their own width, with state and known file/change counts wrapping separately; it does not reuse the compact list row. The tour's hold menu keeps its existing card (O19: "too bland"; Tavi, 20 Oct 2026, screenshot 79142: "Here it needs to all be visible").

Check: hold a PR with a long title, repository and author at a narrow width and larger text: the whole identifiers and counts remain readable, with scrolling when needed. Its two action groups behave as before; hold a Tours row: its existing card remains.

Source: walkthrough. Checked by: review.

#### 187. A pull request is on its way before it is tapped.

The Inbox asks for its top pull requests once it shows, and for any row the moment a finger lands on it, so a tap opens the pull request and its files at once instead of the skeleton (O20).

Check: open the Inbox, wait a moment, tap the first row: its files are there with no skeleton.

Source: walkthrough. Checked by: review.

#### 188. Pinch zoom eases.

The code's size follows two fingers smoothly: each frame it moves part of the way to where the fingers have taken it, laid out again at most once a frame, so it never jumps in steps (O21; rule 93).

Check: pinch the Diff slowly and quickly: the text grows smoothly and the line under the fingers stays there.

Source: walkthrough. Checked by: review.

#### 189. A file swipe never stops halfway.

Letting go of a swipe between files carries the row on to the next file at once, at the speed it was thrown, before the new file is laid out; it never waits between the two (O22; rule 105).

Check: swipe between files several times: no swipe pauses mid-way.

Source: walkthrough. Checked by: review.

### Rules 190 to 194, from the walkthrough of 0.46.0 on 2026-10-17

The recording of 17 Oct at 13:26 (P1 to P6, in `/mnt/project-files/design/feedback-2026-10-17-0.46.0.md`), read from the transcript and a frame a second.

#### 190. The Inbox opens on the chip last picked.

Waiting on you, Authored or All: the one picked last is where the Inbox opens next time, and it travels with the account's other settings (P1: "whichever tab I clicked on last, when I open the app again, it uses that one").

Check: pick Authored, close Pully from the recent apps and open it: the Inbox shows Authored.

Source: walkthrough. Checked by: review.

#### 191. The Overview's Ask bar is the Tour's.

The Overview's bar at the foot behaves exactly as the Tour's: empty, its button opens the earlier chats; typed, it sends. No highlighted button beside it, and nothing at the foot goes to the diff (P2: "this is another ask button ... same functionality"; rule 167).

Since rule 197 the bar rests short, reading Ask, and shows its button once opened.

Check: on the Overview, the opened bar's button is grey or the history button until something is typed, and no button at the foot opens the diff.

Source: walkthrough. Checked by: review.

#### 192. Every bar at the foot rests at one height.

The Inbox's search, the Overview's and the Tour's Ask bars, the diff's file bar, a repository's search and a sheet's own field all rest 24dp over the navigation bar, 16dp in from the sides, through the one kit modifier (`floatingBar`), and since rule 196 the search and Ask bars are one pill drawn once over the pages. Opening a pull request never moves the bar down (P3: "it is on the homepage, but when I go to one of the PRs, it pushes it down").

Check: put the Inbox, the Overview, the Tour, the Diff and the Ask sheet side by side: every bar's bottom edge is at the same height.

Source: walkthrough. Checked by: review.

#### 193. No earlier-chats button without earlier chats.

The history button in an Ask bar and in the Ask sheet shows only when the pull request has chats before the open one; without them the empty bar shows the grey Send (rule 84) (P4: "you don't need to let me tap the button if there's no earlier chats").

Check: on a pull request never asked about, the Tour's bar shows no history button.

Source: walkthrough. Checked by: review.

#### 194. Earlier chats open alone.

The history button in an Ask bar opens the Chats sheet by itself, not the Ask sheet under it; picking a chat there opens Ask on that chat (P5: "it's just the earlier chats that need to open").

Check: tap the history button on the Tour: only Chats is on screen; pick a chat: Ask opens on it.

Source: walkthrough. Checked by: review.

### Rules 195 to 200, from the walkthrough of 0.47.0 on 2026-10-17

The recording of 17 Oct at 14:45 (Q1 to Q7, in `/mnt/project-files/design/feedback-2026-10-17-0.47.0.md`), read from the transcript and a frame a second, more over the diff swipe.

#### 195. A row is two lines, its tile nearly as tall.

An Inbox, Tours or repository row is the title over the meta line, and a repository row does not show its GitHub labels (Tavi's pick on 17 Oct). The state tile is nearly as tall as a one-line title and the meta line, with a little air above and below, and its mark grows with it (Q1: "rows should never have three lines ... the icon is actually the entire size pretty much of the row ... leave a little bit of margin up top and at the bottom"; Claude's own PR list). Rule 201 changed the title: it was one line here, and now takes up to two.

Check: a row with a short title is two lines and the tile's top and bottom sit just inside them.

Source: walkthrough. Checked by: review.

#### 196. One bar over every page.

The search and Ask bars are one pill, drawn once over the pages at the foot (`FootBarLayer`): a page says what the pill holds and the pill stays put while the pages slide under it. Going from one page to the next changes only what is inside it, the words leaving with a blur fade and the next ones arriving, and its width eases to the new page's. A button beside it (the Inbox's sort) leaves or arrives beside it. A bar of its own shape, the diff's file bar, takes its place the same way (Q2: "one component that's kind of over the screen, and then we can swap the text inside it with that nice blur fade ... we want that kind of seamlessness when we go into a PR as well").

Check: open a pull request from the Inbox: the pill never slides off with the Inbox; its words change in place and the sort button fades beside it.

Source: walkthrough. Checked by: review.

#### 197. Ask rests short.

A page's Ask bar (the Overview, its opening page and the Tour) rests as a short pill, centred, that reads Ask, with no button. A tap opens it to the full width with the cursor in it and its button at the end; left empty, it goes short again (Q5: "let's not have this send button and let's just have like a shorter bar that says Ask. And when you tap on it ... it kind of expands ... and then obviously compacts back whenever you get out of that"). He called it a bit experimental.

Check: on the Overview the bar reads Ask; a tap opens it with the keyboard; a tap on the page with nothing typed makes it short again.

Source: walkthrough. Checked by: review.

#### 198. A tap on the bar is the bar's.

A tap anywhere on the pill, its edge and padding included, opens it or puts the cursor in its field. It never reaches what is under it (Q6: "I keep trying to tap on the ask about this pull request, and it keeps getting me to the description").

Check: on the Overview with the description under the bar, tap the bar's edge: the bar opens and the Description sheet does not.

Source: walkthrough. Checked by: review.

#### 199. The editor opens where you tapped.

A tap on the description opens its sheet with the cursor at the place in the text the tap was over, and the sheet shows that place, not the end (Q3: "it scrolls me all the way down instead of stopping where I was gonna edit").

Check: tap the first paragraph of a long description: the sheet opens at its top with the cursor in that paragraph.

Source: walkthrough. Checked by: review.

#### 200. The description is written rendered.

The description sheet's field reads its Markdown rendered while it is typed, as Obsidian's live preview does: headings and strong words heavier, emphasis slanted, code in the code face, links in the link ink, a list's dash as a bullet. The marks (#, **, `, a link's address) fold away on every line but the one the cursor is on, where they show quietly; HTML comments and tags are quiet. Every character stays, so what is saved is the Markdown (Q4: "It is fine to let me do markdown, but just render it immediately, basically. Something like Obsidian").

Check: open a description with a heading, a list and code: no #, * or backtick shows away from the cursor's line.

Source: walkthrough. Checked by: review.

### Rule 201, from Tavi's message of 2026-10-17

Tavi's message of 17 Oct at 17:14, after a canvas of five other row directions he turned down ("my issue with our current thing is not any of those").

#### 201. A row's title takes two lines.

A pull request or tour row's title wraps to a second line before it ends in an ellipsis, and never takes a third, so a row is at most the two title lines over the meta line. A short title stays one line. The tile keeps its size and sits centred on the row (Tavi: "I meant the title should also be in two rows. I think everything else is fine"). This replaces rule 195's one-line title.

Check: in the Inbox, a title longer than the screen wraps to a second line and ends there in an ellipsis; a short one is one line; the tile is the same size in both rows.

Source: answers. Checked by: review.

### Rule 202, from Tavi's message of 2026-10-17

Tavi's message of 17 Oct at 17:40: "since PR stacks are so common nowadays, I would actually group them into one row, and make the stack the row, and when you tap it can show you which PRs are in there and such."

#### 202. A stack is one row in the Inbox.

When two or more layers of one stack are in the same Inbox section, they are one row where the first of them stood: the layers mark in a tile drawn as an outline in the bottom layer's state colour rather than filled, so it does not blend with the pull requests around it (Tavi, 17:54: "it blends too much with the PRs themselves"); the bottom layer's title and number, the one that merges first; after the number, the stack mark a lone layer carries with how many layers there are beside it, and no words (Tavi, 17:51: "there's no need for it to say '3 layers'. Just use the icon we were already using for stack and type a 3 next to it"); the worst of their checks, the sum of their + and −, and the newest age. A tap, a swipe or a hold opens the Stack sheet the pull request page uses, top layer first down to the base branch, and a tap on a layer opens it. A stack with one layer in the section keeps that pull request's own row with its place in the stack. Tours and a repository's list are unchanged.

Check: in the Inbox, three open layers of one stack are one row whose meta line shows the stack mark and a 3; tap it: the sheet lists the three, and tapping one opens that pull request.

Source: answers. Checked by: review.

### Rule 203, from Tavi's message of 2026-10-17

Tavi's message of 17 Oct at 20:13, on the Stack sheet a stack row opens: "in there I cannot see the user's name, so the layout looks a bit odd. It's like its missing the PR number (not the in-stack one; that one is there), and the username".

#### 203. A Stack sheet layer reads like an Inbox row.

Each layer in the Stack sheet has its place in the stack in the leading column by the line ("2/3", counted from the bottom like the rows' stack mark), then its title over the Inbox rows' meta line: its number, its author, Merged or Closed when it is, its checks and its + and −. The sheet reads the same from a stack row and from a pull request's page.

Check: tap a stack row in the Inbox: every layer shows its place on the left and "#number author" under its title, the checks and + and − on the right.

Source: answers. Checked by: review.

### Rule 204, from Tavi's notification request of 2026-10-21

#### 204. Choose alerts for work you follow on this device.

A pull request's actions offer Notify when checks fail, then Stop check notifications
while followed. Enabling asks for Android permission in that context. Settings has a
Notifications page for Check failures and AI results, with each switch reflecting both
the local choice and Android's channel permission. Its consent introduction says checks
require following a PR, AI results concern work started here, and background checks can
take 15 minutes or longer. Existing update notifications remain separate. Notification
taps open the indicated checks, tour or findings; ordinary completion does not start a
new AI run. These are device subscriptions, not a shared GitHub viewer's personal inbox.

Check: permission denial leaves the app usable; a followed PR can be unfollowed, Android
channel changes are reflected on return, and a cold or warm alert tap reaches its source
and result. No additional startup permission prompt appears.

Source: answers. Checked by: review.

### Rule 205, from Tavi's message of 2026-10-26

#### 205. Every version says when it came out.

Tavi: "Let's add a date and time to each changelog entry from now on. Ensure that's how they work." Every section of `android/CHANGELOG.md` has the date and time its version was released, in UTC, on a line of its own right under the heading (`#### 2026-10-26 14:30 UTC`). The update screen, What's new and Settings' What's new show it at the far end of the version's header, across from the number and the New tag, in the phone's own time zone and the way its language writes a date and a time ("26 Oct 2026, 14:30"), muted and small so the summary stays the headline. Versions run newest first by date as well as by number. The sections written before 26 October carry the time their version landed on main.

Check: open What's new: each version shows its date and time at the right of its header, in your own time.

Source: answers. Checked by: review.

### Rules 206 to 209, from Tavi's message of 2026-10-27

Tavi's message of 27 Oct at 12:55: "Now let's figure out how to make it so that the app looks great both on foldables unfolded, and also in horizontal mode on normal phone." The phone held upright stays the shape everything is designed at (rule 25); these rules say where the same pages go when the window is wider.

#### 206. Unfolded, the list and the pull request sit side by side.

On a window at least 600dp wide and 600dp tall (a foldable opened, a tablet), the list page (the Inbox, a repository, Explore) stays on the left, a little under half the width and between 340 and 420dp, and the pull request it opens shows beside it, at about a phone's width, behind a hairline. Each pane is a whole phone page with its own header and its own foot bar. Before anything is open the right pane says, in one muted line, that a pull request you open shows there. Opening another pull request from the list replaces the one beside it rather than stacking on it, so Back from the right pane goes to the list, never through every pull request tapped before; a pull request opened from inside another (a stack layer, a link) still stacks. The row whose pull request is open sits on the hover tint. The diff's columns fit the pane they are in. It is one route stack read as two panes, so folding the phone shut shows the same place as one page.

Check: at 841 by 701, open a pull request from the Inbox, then another: the second replaces the first, the Inbox does not move, its row is marked, and Back leaves the Inbox with the right pane saying what goes there.

Source: answers. Checked by: review.

#### 207. Sideways, one page in a readable column; the code takes the width.

On a window 600dp or more wide but under 600dp tall (a phone held sideways), one page shows at a time, as on a phone, but its rows, words and headers sit in a column no wider than 600dp, centred, so nothing stretches across the screen: the Inbox's tabs, chips, rows and search pill, a pull request's header, overview and tour, and the foot bar. The diff's code is the exception and takes the whole width, because reading a long line whole is why the phone was turned. The header sits 8dp under the status bar rather than 48dp from the top, since height is what a sideways phone lacks. Type keeps its phone sizes.

Check: at 891 by 411, the Inbox and the overview are a centred column with the page colour either side, the search pill is as wide as the column, the diff's code runs edge to edge under a header that stays in the column, and scrolling the overview leaves room to read.

Source: answers. Checked by: review.

#### 208. A sheet rises over the page that opened it, no wider than a column.

A sheet is never wider than 600dp: on a wide window it is centred at the foot, and in an unfolded window it rises over the pane whose page opened it (the pull request's ⋯ over the right pane, the Inbox's Sort over the list) while the scrim still dims everything (rule 5). Sideways, half the window is too little to read, so a sheet rests at its tall height instead of half way.

Check: open the pull request's ⋯ unfolded and sideways: it sits over the pull request, no wider than the column, and sideways it opens tall.

Source: answers. Checked by: review.

#### 209. Nothing sits under a camera cutout or a side navigation bar.

Held sideways, the camera cutout and the three-button navigation bar move to the sides of the screen. Pages keep clear of both on either side, the diff's full-width code included, while the page colour still runs under them.

Check: sideways with three-button navigation, no row, code line or button is under the bar or the camera.

Source: answers. Checked by: review.

### Rule 210, from Tavi's message of 2026-10-27

His message of 27 Oct at 14:36, on 0.65.0 unfolded: "the "Back" arrow icon on the top left of the right half, doesn't make a lot of sense when both pages are visible."

#### 210. Beside the list, a pull request has no Back.

In an unfolded window (rule 206) the pull request opened from the list leaves the Back button out of its header: the list it would go back to is already in sight, and a tap on another row replaces it. The capsule keeps its place on the right, and the header keeps its height. The phone's back gesture still empties the pane. A page opened from inside that one, a stack layer or a link, stacks in the right pane and has its Back, since there is somewhere in the pane to go back to. On a phone and sideways nothing changes.

Check: at 841 by 701, open a pull request from the Inbox: its header has the capsule and no Back; open a stack layer from its Stack sheet: that page has Back, and it goes to the first.

Source: answers. Checked by: review.

### Keep as is

These were liked in the walkthrough and must not regress:

- The profile sheet, which uses the right style.
- Bottom sheets at their normal height.
- The list rearranging when the scope chips switch (Waiting on you, Authored, All).
- The Files list on the Overview.
- The description on the Overview.
- The Diff screen.
- Pull down for Commands, and its search, now on every page (rule 117).
- The Settings sheet.
- Tapping Quick, Standard or Deep on the Analyze options page starts the analysis.
- The "Look for problems" row.
- Mark reviewed, then the next file.
- The Tour's sections and the Glossary.
- The commits, now a sheet opened from the Commits number (rules 91 and 139; K29).
- The pull request tours ("starting to look really nice", P6).
- Going between the Inbox and Tours, where the pill stays and only its words change ("really seamless", Q2).
- The Inbox's sort button beside the search ("which I think is great", Q2).

## Checklist

Run before showing any new screen.

1. Each element: which fact does it convey? None means cut it.
2. Each row: the title on at most two lines with an ellipsis, repo, number, author, head and base, coloured diffstat, checks, age. Present, aligned, the title never three lines.
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
18. Does any screen look like the old app? Settings, the Commands palette, the project screen and the file switcher are all in the mobile style.
19. Open a PR: title and number appear at once, there is no loading screen, and skeletons only stand in for waiting content.
20. Double-tap Generate tour, then stop it from the orb: the first tap responds, the second does not cancel, and a restart is offered.
21. Ask works on an unanalysed PR.
22. Every tappable-looking thing does something. Tabs and the orb that stops AI work are easy to hit.
23. Open each screen that is reachable from two or more places from each. Same presentation, sheet or page, every time.
24. Tap a segmented pill: does the highlight slide to the segment, and does the content fade in?
25. Tap and type: how soon does anything visibly change?
26. Flip between two states of a screen: does anything present in both jump?
27. Find one kind of control (close button, segmented control, chip, round button) on a page and in a sheet. Same shape, fill and shadow?
28. While AI work runs, a live indicator is on screen, with no grey bars under it (rule 71, L33).
29. The header line is a verb with elapsed seconds and the step lines are sentences after the icon of what each does, with no italics, and a dim band shimmers through the text (rule 72).
30. Search every screen for an AI icon (a sparkle, a robot, a brain). None. The brand marks of Claude Code and Codex (rule 48) and the live indicator are not AI icons in this sense.
31. Open and close a menu, a sheet, a dialog and a toast; the times and easing match rule 73.
32. Scroll a list slowly and fast under each pinned header and pill. No row ends in a hard line, no text is stretched, chips and buttons stay sharp, and nothing white sits under a pill (rule 74).
33. Every heading: group titles have one line under them and none above, headings have none (rule 75).
34. Open every sheet: two stops, a fixed header, nothing sliced under it (rule 77).
35. Open a page inside a sheet and go back: only the content moved, and the scroll position is kept (rule 78).
36. Press a row, a tile and a card: only the tint changes (rule 82).
37. Run an analysis: nothing on the page moves (rule 86).
38. Dark mode has no colour cast in any surface (rule 76).
39. Scroll to the end of a diff file: the last line is the end, and pinned chrome is no more than a fifth of the screen (rule 92).
40. Swipe between files and between the three views: the header does not move (rule 93).
41. Comment on a line: one way in, one bar (rule 94).
42. Tap every cite in an analysis, a tour and an Ask answer: each scrolls to its place, and in a tour a file opens its diff on a sheet (rule 96, L28).
43. Every skeleton visibly shimmers for three seconds in light and in dark (rule 85).
44. Open every sheet and list: no arrow-out icon (rule 100).
45. Open a pull request of a few hundred files: headings first, they fold, and the Files sheet finds a file by name (rule 101).
46. Swipe, tap and hold a file row on the Overview (rule 102).
47. Tap the search on Home: the list dims until the first letter (rule 103).
48. Type a question in the Home search: Ask fills in the pill, Send asks it, and every pull request the answer names opens with a tap (rule 55).
49. Scroll the Inbox past a repository's first rows: its heading sticks whole at the top until the next one slides up under it and takes its place (L2); a tap on the heading folds the rows, a tap on its name opens the repository, and the round sort button beside the search says the order and changes it (rules 70, 74, 75, 81, 114).
50. Offer an update: Pully opens on the update screen with the changelog, nothing gets past it, Update shows its progress in place, and Commands has Update Pully (rule 104).
51. Open a pull request that sits on another: its Inbox row shows "2/10" with the layers mark under its number (H16, rule 111), the page says "Stacked on #241" under the title, and a tap there opens the Stack sheet, top layer first down to the base branch, where a tap on another layer opens it (rule 14).
52. Drag, let go and throw between tabs and files, use the capsule and Back, and back-swipe from both edges: everything goes the way you went and nothing springs back the other way first (rule 105).
53. With Jev on, open the Sort sheet: Riskiest first and a Group by card (Repository, Kind) are there; picking either asks Jev about every open row and the kinds fill in, while with neither picked nothing is asked; grouped by kind, the headings run Fixes, Features, Refactors, Performance, Tests, Docs, Dependencies, Infra, then Not sorted yet, they fold and pin like repositories, a tap on their name only folds, and every row's meta line starts with its repository. A row Jev has read shows one short risk word under its number (a stacked row turns its stack mark amber when the risk is high), and no row has a kind line (M1, rule 140). Open a pull request and run an analysis: its log starts with "Finding the files that need a careful look", then the line under the branch says the kind, the risk and how many files need a look, and every rated file's second line starts with "Needs a look" in amber, "Worth a look" or "Skim". Opening a pull request alone asks Jev nothing. With Jev off none of it shows (rules 1, 14, 53, 70 and 75).
54. Open a pull request with thousands of changed lines: the title, description and conversation show at once, the sizes and Files rows are bars until the diff arrives, and a file whose patch was left out shows bars where its code goes, then its code, never a screen-wide wait (rules 51, 52 and 53).
55. Open Profile: it rests at its normal height and a pull lifts it to tall. Pull down for Commands: it rises tall with the keyboard coming up as it rises (rules 77 and 117; K5, H22).
56. Search every screen for Refresh: there is none, and new commits on an open pull request arrive without a tap (rule 52).
57. Select lines in the Diff: nothing floats over them, the range is written once, the bar is ✕, the field and Send, and holding a line offers Ask, Copy and the GitHub link (rule 94, K11).
58. Pinch the Diff: the text grows and shrinks with your fingers and still fills the page, and the line under them stays under them (rule 93).
59. Open a tour with pseudocode, a call tree, a file tree and a long file name under "In the code": pseudocode reads as plain wrapped steps, trees and flows are drawn, and no reason is squeezed (rule 95, L27).
60. Ask a question and watch it work: its newest lines of thinking and the answer appear as they are written, and the chips include the files it read (rule 99).
61. Open the Claude Code model sheet: names carry their version and older ones sit under "Older models"; in Settings the Effort row under Model names its level with Recommended on the CLI's default (rules 89 and 142).
62. Open a sheet, share a pull request link to Pully, then go back: the sheet slid down, the pull request opened, and Back lands on the Inbox with no sheet; Back again asks before Pully closes (rules 175 and 176).
63. Generate a tour and pull its sheet down: a lip stays at the foot of the Tour, the section strip fills in as sections are written, unwritten cards shimmer, and every finished step in the log opens (rules 183 to 185).
64. Restart Pully and open Profile, Stats and the first Inbox row: each shows at once, with no skeleton (rules 178 and 187).
65. Render every new screen at 841 by 701 and 707 by 841 as well as 390 by 844: the list and the pull request sit side by side, each a whole page, and the open row is marked (rule 206).
66. Render it at 891 by 411: one readable column, the code edge to edge, the header close under the status bar and sheets resting tall (rules 207 and 208).
67. Unfolded, the pull request opened from the list has no Back in its header; one opened from inside it does (rule 210).

The checklist is the canvas checklist (1 to 10, with the three Row W checks as 11 to 13) followed by the walkthrough checks (14 to 22) the Perplexity checks (23 to 27) the checks from Tavi's answers (28 to 31), the checks from the second walkthrough (32 to 43) the checks from his comments and notes of the same day (44 to 47) the check from his answer on the search pill (48), the checks from his ideas for Home (49 and 51), the check from his message on updates (50) the check from his message on motion (52), the check for Jev (53), the check for huge pull requests (54) the checks from the audit of the second walkthrough (55 to 61) the checks from the walkthrough of 0.39.0 (62 to 64) the checks for wide windows (65 and 66) and the check for Back beside the list (67). The canvas notes number Row W's checks 10 to 12, which collides with its own check 10, so they are renumbered here.

## Enforcement

Each layer catches what the one before cannot. All of them run in one command and in CI (`android-design` job), need only Node, and are never skipped: a failing check is fixed, not waived.

```
pnpm run android:design           run every check below
pnpm run android:design:update    rewrite the generated files and the baselines, then review the diff
pnpm run android:design:test      vitest suite of the checks themselves
```

1. **tokens.** `design.md lint` (from `@google/design.md`) on this file: it fails on a broken reference. Then the Kotlin is compared with the front matter: every colour and its dark pair, every type role (family, size, weight, line height, tracking), `PullsRadius` and `PullsSpace`. A colour without a dark pair fails. `app/src/main/assets/book/spec.json` is written from this file and fails when stale. Rules 12, 47 and 76.
2. **raw-values.** A ratchet. It counts raw hex colours, raw font sizes and raw radii in the Kotlin per file, text set below 15 (`xs`, `compact`, `mini`, `micro`), uppercase and tracked text, and raw shadows (`.shadow(`) outside the shadow helper, and compares them with `raw-values.baseline.json`. The count may only go down: a new raw value fails, and a file that drops below its baseline fails until `android:design:update` lowers it, which locks the gain in. A line can opt out with `// raw-value-ok: <reason>`. Rules 14, 45 and 47.
3. **bans.** Importing a Material widget the kit replaces (buttons, switches, checkboxes, text fields, dialogs, tabs, cards, dividers, progress indicators, FABs), Material icons or Android speech input, and an outgoing share, fail. Material's `ModalBottomSheet` is banned everywhere, because its slide and close timing cannot follow rule 73, and `DropdownMenu` is only for the kit's wrappers in `ui/design`. The web-port kit (`Components.kt`), its Phosphor icons, the waiting components (`Loading`, spinners) and pull-to-refresh are banned for new code. A call or a Material name spelled out with its full package counts the same as an imported one. The generic AI glyphs of rule 48, the robot head, the sparkle, the compass, the chat bubble and the speech bubbles (`ai-glyph`), are counted the same way: a new one fails, and the count may only fall. The uses that exist today are counted per file in `bans.baseline.json` and may only fall. Bans lift in the config at the top of `bans.mjs` or with `// ban-ok: <reason>` on the line, never silently. Rules 1, 5, 8, 10, 11, 22, 28, 38, 48, 49, 51, 52 and 73.
4. **book.** `book.json` is the one typed list of UI Book pages and components. Every page has a route in `UiBook.kt`, and every route is listed. Every component has exactly one default variant, and every named composable exists. Every public composable in `ui/design` has a page or an exemption with a reason in `book.exempt.json`. Every `components` token here is rendered in a `DesignSpecimen`, or exempt with a reason. `sources.json`, the code and props the Book shows, is read from the Kotlin and fails when stale. Rules 44, 45 and 47.
5. **motion.** Rule 105 lives in the kit, so the check keeps it there. Outside `ui/design` nothing may call Compose's own slides or pagers (`slideInHorizontally`, `slideIntoContainer`, `HorizontalPager` and the rest), because they pick a direction where they are called; screens change pages through `BlurSwitch`, `LabelSwitch` and `rememberPageSlide`, every call of which must name its `order =`, and the kit's declarations must keep `order` required. Inside the kit, every slide takes its offset from the way. `BackHandler` outside the kit fails too: Back goes through `PageBack`, so a back swipe moves the page with the finger. A line can opt out with `// motion-ok: <reason>` (the thinking line, which only reads on, and the Back that puts an edited field's text back). Rule 105.
6. **rules.** A check on this file's own list. The rules are one contiguous list from 1, each has a source and a `Checked by` line naming a check that exists, and each check enforces exactly the rules that name it. The checklist is contiguous too. `rules` is not a `Checked by` name: it checks the rules, not the code.
7. **Measure on the phone.** The UI Book's Component specs page measures each `DesignSpecimen` and prints `name ok` or `name.size: expected 44px, rendered 52px`. It replaces the browser render check the web UI Book runs, because Compose cannot be measured from Node. It runs when someone opens the page.
8. **Review against the checklist.** A person or an agent walks the screen against the checklist above and the rules, and reports breaks with `file:line` and the rule number.
9. **web.** The web app is drawn from the same sources (rule 106). `packages/app/src/design/tokens.generated.json` is written from the front matter, `icons.generated.json` from `Lu` in `Icons.kt`, and `fonts.generated.css` from `PullsFonts` in `Tokens.kt`, pointing at the same TTF files the APK ships; each fails when stale. `app/src/main/assets/i18n` must equal `packages/app/src/i18n/locales` byte for byte: strings are written on the web side and copied by `android:design:update`. The web's `uno.config.ts` builds its theme, CSS variables, the `@antfu/design` overrides and the kit's classes from those files, so it has no colour, size or shadow of its own.
10. **changelog.** Not a design rule, but it runs with the others: `android/CHANGELOG.md` has a section for the `versionName` in `app/build.gradle.kts`, with a release line (`#### 2026-10-26 14:30 UTC`, a real time, right under the heading, once), a summary sentence and at least one bullet, newest first by number and by date (rule 205). A pull request that bumps the version writes what changed for the reader there; the app shows it after the update and the publish step puts it in `latest.json`.

## UI Book

Settings has a UI Book row at the bottom. The book is a set of screens inside the app, built from the same kit, so what it shows is exactly what ships. It follows the web UI Book: one typed page list drives the index, the pager, search and the coverage check; foundations read the real tokens; every component has its own page with live previews, every state on its own copy, light and dark, variants as tabs, an Inspect toggle that reads the measured size, the props and the source read from the Kotlin at build time, usage notes and a Copy prompt that briefs an agent.

Files: `app/src/main/assets/book/book.json` (hand-written pages and components), `spec.json` and `sources.json` (generated, do not edit), and the Kotlin in `ui/book`.

To add a component: write it in `ui/design`, add its entry and variants to `book.json`, add a preview in `ui/book/BookPreviews.kt`, run `pnpm run android:design:update` and `pnpm run android:design`, then look at the page in light and dark on a phone.

## Decision log

Every difference between this file and the rendered app, and how it was settled.

- **2026-10-14, this file written from the source at version 0.6.1 (build 16).** The tokens are what `Tokens.kt` renders today, not the other way round, so no visible change. Kotlin is compared with this file instead of being generated from it: `Tokens.kt` carries computed properties and the app is being edited in parallel, and a generator would overwrite those edits. The checks still fail closed.
- **2026-10-14, departures are frozen, not fixed.** The raw-values and bans baselines record how far the code is from these rules today: 288 raw values (40 hex colours, 131 raw radii, 2 raw font sizes, 3 raw shadows, 106 text runs below 15, 6 uppercase or tracked) and 187 banned uses (93 Phosphor icon calls, 75 web-port kit calls, 6 Material components, 3 Material sheets or menus, 1 pull-to-refresh, 9 loading indicators). No screen was restyled to get there. Each fix lowers a number and the check asks for the baseline to be lowered with it.
- **2026-10-14, raw colours in the kit that are not tokens yet. Open.** `Kit.kt` holds literals for the unchecked box edge (`C9CFCE`), the pending ring (`B9C4C5`), the orb and toast accent (`9AD3D6`) and the profile gradient (`D9B38C`, `8C5A3C`). They need tokens, with dark values. They sit in the raw-values baseline until then. (The sheet grabber was one of them; it became the `grabber` token, with a dark value, in 0.20.0.)
- **2026-10-14, the PR row has no page. Open.** Rule 15 says one row anatomy everywhere, but the row is private to `HomeScreen.kt` and the Tours list, so the UI Book cannot render it. Pulling it into `ui/design` is a change to the screens and was left to the work that owns them.
- **2026-10-14, forced states.** The web book forces hover, focus and active with CSS. Compose cannot, so the book forces the states a component takes as parameters (selected, disabled, loading, running, done) and shows the pressed scale on the Motion page with a live pressable. A sheet is a window and cannot be framed inside a page, so its page shows the sheet's body inline and opens the real sheet from a button.
- **2026-10-14, rule 48 and rule 37. Settled the same day:** Tavi called the robot, the compass and the sparkle hideous, so none is left. The Model row shows the chosen engine's own mark in colour, and the `ai-glyph` ban baseline is zero. Earlier text, kept for the record: One Lucide sparkles icon per screen as the AI mark is still in force. The walkthrough does not say whether it stays, and the sparkle called hideous on "Ask AI" is the kind of mark rule 37 asks for. The `ai-glyph` ban counts the sparkle with the robot head, the compass and the chat bubble, so none can be added. Counting is not removing: the existing ones stay until Tavi decides.
- **2026-10-14, rule 61 and rule 29. Settled the same day:** Commands is its own sheet, opened by pulling the Inbox down or from the ⋯ sheet, with a search pill and four rows to a group until searched. Earlier text: how the Commands menu relates to the ⋯ sheet is not decided.
- **2026-10-14, rule 45 and `floatShadow`. Settled the same day:** `floatShadow` now draws one soft layer pushed down, with a level for each kind of element (`PullsShadow`). Earlier text: The canvas shadow is a soft layer pushed down (`0 2px 10px` at 8% ink on the round buttons), and on the phone the app's shadows read faint and flat. `floatShadow` in `Kit.kt` calls Compose `shadow()` with an elevation and no offset. The fix is one drawn shadow with a downward offset, in that one function, judged side by side on a device. It was not changed here, because it restyles every floating element and belongs to the screen work. An earlier reading, that the app's shadows were stronger than the canvas's, was wrong.
- **2026-10-14, rules 66 to 70 added from the Perplexity review.** Rules 66 to 69 are rules. Rule 70 was a proposal from the frames, not from anything Tavi said. He confirmed it the same day, see the entry on rules 71 to 73.
- **2026-10-14, rule 51 and rule 43. Settled the same day:** Tavi said bars are fine next to a live indicator, which is rule 71. Earlier text: The Analyzing state puts shimmer bars under each file row, against the wording of rule 43, which keeps the live orb and its outputs and no bars. A reading for Tavi to confirm: bars are fine as a hint for the one text being written, next to a live indicator, and rule 43 forbids bars in place of the live indicator and its outputs. The canvas text of rule 43 is unchanged.
- **2026-10-14, the phone walkthrough applied.** Every point in Tavi's feedback that is about how the app looks or moves now follows the mobile kit: the drawn shadow, blur at scroll edges and between tabs and pages (`edgeFade`, `BlurSwitch`), menus that grow out of their trigger (`MenuReveal`, from the `dropdown` recipe on transitions.dev), the header capsule on Overview, Diff and Tour, the pill input with its send circle inside (`PillInput`, `SendButton`), fields inside sheets (`SheetField`), and Settings, Commands, the repository list, Check out, the review dialogs, the share sheet, the AI review panel, thread cards and the banners rebuilt from kit parts. Labels in a sheet row wrap instead of being cut. The loading screens are skeleton rows. The robot, sparkle and compass are gone, along with `AppModal`, the web header and its footer. The baselines fell to 14 Phosphor icon calls, 3 web-port kit calls and 5 Material components, and 32 raw colours, 73 raw radii and 72 text runs below 15. What is left sits in the Overview timeline, the activity log, `Markdown.kt` and the CI and review status glyphs, which carry facts and need Lucide drawings of their own.
- **2026-10-14, rules 71 to 73 added, rule 37 withdrawn, rule 70 confirmed.** Tavi answered yes to bars beside a live indicator (71), no to keeping the sparkle (37 is withdrawn, so is Row W check 11), yes to rule 70, asked for the thinking text to look more like Perplexity's (72) and sent transitions.dev (73). Rule 70's direction for segmented controls is still open: the app follows the canvas capsule, so Settings, which already uses it, stays as it is, and a sheet's ✕ and back are now the round white button with its shadow.
- **2026-10-14, the thinking text applied (rule 72). Open edges.** `AiActivityLogFull` shows the shimmering verb and seconds, dot steps and the orb at 16dp, and its settled form has a chevron and a fading line. Since then the orb finishes its turn and settles into its still tick for about two seconds when work stops, and the Ask thread and the chat draw the three shimmer bars of rule 71 under it. The orb does not stay once settled. In the chat the orb and the bars are drawn by the message itself (`AiLiveMark`), not by the live log, so the orb stays where it is while the log hands over to the finished one and settles there; the activity log still has no favicons or grouped steps. Measured sizes need a phone: the band, the dot column and the 16dp orb come from screenshots of Perplexity.
- **2026-10-14, the sheet is the kit's own (0.7.3). Open edges.** Material's `ModalBottomSheet` cannot take a duration, so `PullsSheet` is now a dialog window with its own panel and scrim (`Sheet.kt`): 400ms up, 350ms down, smooth-out curve, a 2px blur fading with it, and the scrim fades with the panel. A drag on the panel, a flick, a scrolled-to-top pull and the system back all go through one position, so closing by any of them takes the same 350ms from wherever the sheet is. Frame stepping under Robolectric showed the open and close curves, the blur and the drag hand-offs; what it cannot show is the feel of a finger on a real phone, the keyboard moving a sheet with a field in it, or the dialog window drawn by the system, so those need a phone. The old sheet's fixed behaviours were kept on purpose: height up to 62% of the screen, `expandable` opening half way and lifting to full, `full` filling to 44dp under the top, the header with the round close or back button.
- **2026-10-14, the second walkthrough, first batch (rules 74 to 99, 0.8.0).** Tavi's 29-minute recording of the app on his phone is digested into 73 points, and rules 74 to 99 hold what they decided. This first batch fixes what is on every screen. The top blur smeared over the filter chips because `edgeBlur` recorded its blurred band at its own size and the blur bled out of it. `edgeFade` replaces it with a page-colour fade over three progressively blurred layers, each clipped to the band, and it also fades the bottom under the search pill, so the 140dp gradient box and the hairline under the chips are gone. The dark surfaces are neutral greys now (red, green and blue within 2 of each other), and the tokens check fails if one drifts back to blue. Press feedback is a flat tint (`PullsIndication`) instead of the Material ripple, the skeleton sweeps a visible sheen across the whole bar every 1200ms, floating shadows have a tight edge layer under the wide drop, and the PR meta line wraps whole names instead of cutting them. Open edges: how the press feels, whether the shimmer reads as alive and whether the edge fade is strong enough all need a phone, because Robolectric stills show geometry and not feel.
- **2026-10-14, the second walkthrough, second batch: sheets (rules 77 to 81, 0.9.0).** `PullsSheet` follows rule 77: a sheet with more content than fits rests at half the screen (or at its content height when that is less) and lifts to full, and its header is fixed while the body scrolls under it through `edgeFade`. A sheet that opens a keyboard is `full`, and Commands takes the keyboard only once it has stopped sliding, through `LocalSheetSettled`, so the keyboard never fights the slide; that is my reading of "half height" for those sheets, since a half-height sheet would sit behind the keyboard. Rule 78 is `PullsPagedSheet`: Settings, and the profile sheet with its Repositories list, are one sheet each with pages inside it, the header, ✕ to ← swap and lip fixed, the body sliding like a page change (`BlurSwitch`, now with a size change on the same 250ms curve), and a scroll place kept for each page. Rule 81 is on the Settings page: two small diffs for the layout, a size capsule over a sample line of code that resizes, and the code font as its own page. Check out a branch is gone (the screen, its route and its tests), and the profile sheet has a Repositories page with a switch for each repository, so the Inbox leaves out what is switched off (`AppSettings.hiddenRepos`, kept on the phone). Open edges: the drag, the settle between half and full, the keyboard after the slide and the press tint were checked in Robolectric stills and with the test clock, which show the geometry and not how a finger feels, so they need a phone. Commands and the chat are the sheets that open a keyboard; both are `full`, and only Commands waits for the slide so far.
- **2026-10-14, the second walkthrough, third batch: the pull request page (rules 79 to 84, 0.10.0).** Opening a pull request, a repository or back slides the page like every other page change (`BlurSwitch` in `PullsApp`, ranked by how deep the page is). The opening page draws the same meta line as the page, so the names that were cut with ellipses while it loaded are whole. The title and the description of a pull request you can change are edited where they are (`EditableText`): a tap turns the text into a field, the keyboard's Done or a tap on the empty page leaves it and saves, back puts the old text back, and the page shows the new text at once; the Edit link and the description sheet are gone, and so is the "Copied" the branch chip used to turn into (a small toast says "Branch name copied" and the chip goes on showing the branch). Commits, Checks and a new Files sheet are plain lists on the sheet's own paper (`ListRow`, 64dp or more, whole titles, a hairline between rows, no instruction about shift-click); Checks list failing first, then running, passing and skipped, and a row opens the check on GitHub; the Files cell on the page opens the Files sheet, and a row opens that file's diff or ticks it reviewed. The "Viewing commit 3 of 6" bar with its "Show all" pill is pinned over the Overview and the Diff, so a narrowed view always shows the way back. Under the tabs, the rows follow the finger toward the next tab (up to 96dp, fading as they go) and slide as a page when it is let go (`PageSlide`). Comments in the Conversation fold up and open with a tap on their header, a folded one shows its first line dimmed under the name and the age, a bot's comment (CodeRabbit and the like, by a `[bot]` login or a short list of known names) starts folded, the word "commented" is gone (a comment can only be a comment; a review still says what it decided), and the description card folds too. The text in the new lists is body size (15), so the below-15 and Phosphor baselines fell. My reading of rule 91: every comment starts open except a bot's or one GitHub hid, and the description card, the first comment, always starts open. Open edges: how well the rows keep up with the finger on the swipe between the three tabs, the keyboard moving the field into view while the page scrolls, the press tint and the way the rows blur while sliding were checked in Robolectric stills and with the test clock, so they need a phone. What I left for later workstreams: the file rows on the Summary tab (the checkbox that sits higher than the text, the long path) and the swipe on a file belong to the Files and Diff batch.
- **2026-10-14, the second walkthrough, fourth batch: Analyze (rules 86, 87, 89, 96, 0.11.0).** The Analyze card is one height, 72dp, before a run, while it runs and after (`AnalyzeStatusCard`), so nothing on the page moves while the AI works. Before a run it reads "Not analyzed yet" with the Analyze button and no tick (the orb has a new rest state, a wash with a small dot, and the tick now only means something finished: a stopped or failed run rests with the dot). While it runs it is a title and one shimmering line of what the AI is doing that ends in an ellipsis when it is too long, and the step chips are gone. The orb is the Stop button (`Orb(stop = true)`, a small square where the dot was, in a 44dp target), the Stop pill is gone, and so is the one in "Looking for problems". A run that did not finish shows a try-again icon in place of the words "Try again" and "Analyze again". Tapping the card opens the steps and the work log in a sheet (`AnalysisLogSheet`) and changes no colour while held; the card before a run opens nothing. The Analyze sheet is one `PullsPagedSheet` with pages: the presets and the model row, Custom, Select model and Effort, so choosing a model or switching engine never closes it, and the line "The Analyze button runs the last choice" is gone. The model list (`ModelPage`, `groupModels`) shows the newest version of each family from the newest generation plus the default and any alias, and lists the rest below the Effort row under a small "Older models" heading (first built as a closed fold inside the list; his comment on the target designs, "this is not how it looked in the video", sent me back to the frames, where Claude's picker has the recent models, then the Effort row, then "Other models" and a plain list, so that is the shape now), a name per row with no id and no description (only the Default keeps one line, which says which model it is), a neutral grey "Recommended" pill (Claude's, read from the frame at 13:06; the teal wash he called "the wrong color") and a larger plain ✓; there is no custom model id field and no "Used for the analysis" text; the Effort page has the same pill and tick and a tiny footnote. A Codex name loses its hyphens between words. "What needs a human" has no line under it, nor does the Files heading (the first row of a list has no line above it), and cites are teal with no underline. My readings: "newest first" is read from the version in the name, so Haiku 4.5 sits under Older models while Fable 5.1 stays up; the card keeps its title line above the status line because he called that card "much nicer"; the orb ignores a tap for 700ms after a run starts, so the tap that started it cannot also stop it (rule 63). A correction: the earlier digest said choosing a model starts the analysis, but at 13:17 to 13:41 he is tapping a preset (Quick, Standard, Deep), which does start it, and that is what stays; a model choice only saves. His comment on the target designs, "why is this a different color?", sits on the Analyze button, which was the dark call-to-action fill beside a teal Review button; both main actions on the page are now the accent fill with white ink (my reading: he does not say which colour he means). Open edges: the shimmer and the orb's square read as intended only on a phone, and the model list was drawn from a list I wrote from his frames (Default, Opus 5.5, Fable 5.1, Sonnet 5.5, Haiku 4.5 and an older Opus), not from a live server.
- **2026-10-14, the second walkthrough, fifth batch: Files and the Diff (rules 90 to 94, 100 to 103, 0.12.0).** The Diff's header is the back button and the capsule; the file's name moved to the bottom between ‹ and › in a round pill, whole, with the end of its folder and "3 of 12" under a short name, and a tap on it opens the Files sheet. Selecting lines turns that bar into the comment bar (option B, which he chose), and letting them go turns it back. The note and the counts line with the Reviewed box scroll with the code. A sideways swipe changes file and the page follows the finger: on code wider than the screen it scrolls the code first, off the code it changes file at once, and past the last file it goes to the Tour (before the first, to the Overview). The capsule's corner on the Diff is Aa, which opens "Text and tools": the layout as two small diffs, the code size over a sample that shows wrapping, the code font, wrap and review comments, the file's actions and the pull request's. Split needs a screen 640dp wide, so on a phone it is shown dim with "wider screens" rather than chosen and ignored. Tapping a file note opens Ask with the note attached as a card the question carries. The Files sheet is the one place a file is picked, with a search field (`SheetSearch`, also on the Repositories page, which is now a list of names with the owner under each), folding sections, the file on screen marked, and the first 300 rows of a long list. On the Overview, sections fold too (all of them above 80 files, shared with the sheet), and a file row is the target design's: the box on the first line, the name first, the folder cut from the front and whole on a tap, the note in two lines, a swipe right to mark it, left to open it and a long-press for its actions (`SwipeRow` takes a tap, a long-press and a start icon of its own). Commits have their own tab, oldest first and numbered, and the pushed-commit groups left the Conversation; the tabs lost their counts, and `UnderlineTabs(spread = true)` lays them first to last with even gaps, steps the labels down to 15sp when four do not fit at 16sp (they do not on a 390dp phone), and scrolls only when even that does not fit. Comment headers keep the age at the right edge. The Ask and ⋯ pill has more room right of the dots. Home's list dims while the search waits for its first letter. No arrow-out icon is left, and a ban (`external-link-icon`, rule 100) keeps it so. From the review bots: a tab changed back before it finished leaving now comes back (the rows stayed half faded), a folded review hides its inline comments, the commit banner's headline is two lines at most, the Stop orb's 700ms guard counts from the start of the task and not from the card coming back on screen, and when the tour and the brief both fail one try-again runs both. My readings: the swipe directions on a file row follow the target design he saw (right marks, left opens), which on the Summary tab means a sideways swipe on a file row is the row's and not the tab's; the dim is for the empty search only, because the matches are what you are looking for once you type. Open edges: the feel of the file swipe and its hand-off from code scrolling, the swipe on file rows next to the tab swipe, the keyboard and the dim were checked in Robolectric stills and with unit tests, not with a finger, so they need a phone.
- **2026-10-14, the second walkthrough, sixth batch: the Tour and Ask (rules 72, 95, 97 to 99, 0.13.0).** The Tour strip under the header is the ring, the stop you are on and a chevron, each in its own column: the ring fills with the scroll (`tourProgress`, the stops above a reading line plus the part of the one it crosses, the line sliding to the bottom as the list runs out, so it is full exactly when the list stops), "2 of 4" and the hairline are gone, and the title is one line with an ellipsis or two whole lines. A tap on it opens the sections as a sheet of the kit's sheet rows (ticked when read, an accent ring on the one you are on), which fixes the padding he said "feels off". The tour ends with its last card: no closing line and no button. The cards at the top are sharp while the tour is at rest and only fade once it moves (rule 74; the strength was already 0 at rest, and the shots show it). The Rewrite pill and the model and token line left the tour's first card; rewriting is a quiet icon at its corner. The Ask pill's send button is dim and does nothing while the field is empty. In Ask, your message is an accent bubble and the reply is plain text with no box; the "Clear" button became a new-chat icon at the top right of the sheet (`PullsSheet(trailing = …)`); the bubble of dots is gone, and from the moment a question is sent the log shows "Thinking" and the time, then each step. Every step in a work log has the icon of what it does instead of the dot (rule 72, amended by his comment "Each action should have its own specific icon to the left, not a bullet point"), and the open log lost its line, because the icons make the column. No model, engine or token line is left on a tour, an answer or a review (rule 98), so `AiUsageLine`, `critiqueEngineLine`, `formatTokens` and `formatCost` are gone. A Markdown table keeps a readable width for each column (as wide as its longest line, up to 240dp, so a description wraps there), spreads to the full width when it is narrow, scrolls sideways under a fade at the edge it can still go toward, has its header on the code paper and hairlines between rows, and is set at 15. Glossary terms lose the backticks the model writes around them, and their meanings render as Markdown. My readings: the new-chat icon is Lucide's square with a pen, and it hides while an answer is on its way; the reply keeps the reading size (16/24) of the tour's text. Left for the next batch (rules 95, 96 and 99): code blocks as the diff's code view, flows drawn by the app, mentions as links, sources chips and follow-up questions under each answer. Open edges: the feel of the ring as it fills and the sideways table scroll were checked with unit tests and Robolectric stills, not with a finger.
- **2026-10-14, the second walkthrough, seventh batch: what the AI writes (rules 95, 96 and 99, 0.14.0).** A code block in a tour, the brief or an answer is the diff's code view: coloured by the diff's highlighter when its fence names a language it knows, at the diff's code size, scrolling sideways and zoomed with two fingers like the diff (`AiCodeView`). A flow is drawn: the tour prompt asks for one hop per line (`From -> To: what moves`), and the app draws each part as a box and each hop as an arrow down with what moves beside it (`parseFlow`, `FlowView`); a sketch with a line that is not a hop stays the code view, so nothing it says is lost. Mentions are links: the tour, Ask and brief prompts carry one sentence (`MENTION_RULE`) asking for every file, function, type, field, setting or line to be a Markdown link to its path, and on the phone such a link, or a bare `file.ext:12` that names a changed file, is accent text with no underline that opens the diff at that line (`LocalMentions`, `codePlaceOf`). The analysis prompt does not carry it, because the GitHub Action posts its summary as a comment, where a path is not a link; its bare cites still become links on the phone, in the summary as in the tour. (Changed in 0.25.0: the analysis carries it too, and what is posted to GitHub links its cites.) Under each Ask answer: the places it mentions as outlined 36dp chips, the same chip as the kit's filters (rule 70); a copy icon (plain text, links keep their words) and a redo icon on the last answer, which asks again in place; and up to three follow-up questions, each a row with a turn-down arrow (`Lu.followUp`) that sends it when tapped. The questions come from a small, quick model after every answer (Haiku for Claude Code, Codex at low effort; `suggestQuestions`), are saved with the chat, and never repeat the question just asked; while they are written the log shows a step saying so, and when that model fails or takes more than 20 seconds, the answer simply has none. On the web, a link to a repository path in a chat answer does nothing instead of leaving the page. My readings: the code well has a hairline outline, because an Ask sheet is the same paper as the well; the arrow of a flow is the muted ink and 1.5dp, like the icons; an Ask thread on lines of the diff keeps plain text, since it already sits at the lines it is about. Open edges: the highlighter's colours come from the server and were not in the Robolectric stills, and the pinch zoom and the follow-up taps were checked in code and stills, not with a finger.
- **2026-10-14, the departures the design map found at 0.14.0 (0.15.0).** Fixed where a rule already decides. Rule 48: no speech bubble is left (the Ask pill is the word, the Ask field and "Ask about this file" have no glyph, the bubble glyphs are gone from the kit and `ai-glyph` counts them), the review card in ⋯ is words, and "Update branch" has the branch glyph instead of a second merge glyph. Rules 14, 22 and 42: the Files heading is "Files" alone, ticking Reviewed in the Diff plays the success sound on the last file and shows no "2 of 4" toast, and the progress sheet has no counter; Review progress opens from a row in ⋯. Rule 26: the meta line is "dayflow #241 · author · 6h ago". Rule 40: 24dp of air between the counts row and the diff. Rule 4: no hint line under the Inbox. Rule 36: a Tours row is a PR row (ring or orb, number, title, then state or author, checks, size and age). Rule 90: the Inbox author wraps whole. Rule 1: no "P" disc on the file note. Rule 11: Share left ⋯. Rule 29: "Merge when checks pass" is a switch row under the merge row, on GitHub's auto-merge, shown while checks or reviews are what it waits on (`blocked`, `checking`), and off again from the same row. Rules 6 and 19: a tour has sections, not stops, and the empty Tour page offers Analyze (the last choice with the tour in it, or the tour alone after an analysis), with no word on how the tour is made. Rule 87: Stop is the orb at the end of the work log's line (`AiActivityLogFull(onStop)`, guarded by `guardedStop`) in the Tour, the review and an Ask on lines, and every repeat is the try-again arrow with a label (the brief, a failed review, a failed tour, the lens list, the model list, Ask, a page that did not load, the Inbox). Rule 49: the Conversation's cards, a finding under its line, the Ask thread on lines and the error lines are the kit's (16dp white cards with the canvas shadow, hairlines from `Modifier.hairlineTop`, no border, no Phosphor icon, no Material divider, no tag); a review's decision is its verb in green or red, an event is a sentence with no icon, a finding's severity is a coloured word before its title, a suggestion is the diff's code view, and a Markdown task is the kit's bare check. Rule 14: the scan's runs of text below 15 fell from 48 to 5, all in the frozen web-port kit (`Components.kt`), so the work log, findings, threads, Conversation and Tour labels read at 15. Every ban is now at zero, so `bans.baseline.json` is empty. Kept as is: the Tour's Ask stays a field (D14; the walkthrough named its height and icon, both fixed in 0.13.0). Waiting on Tavi: the two teals on the main action (D2) and whether the Home search takes a question (D7). Open edges: the auto-merge row, the orb as Stop and the new cards were checked by compiling, unit tests and stills, not on a phone.
- **2026-10-15, Ask from the Home search (D7) and the darker teal (D2), 0.16.0. Settled by Tavi.** D7: he chose to build it, so the pill does what "Search or ask Pulls" says (rules 35 and 55). Typing still filters the list at once. On the Inbox any text other than a link or a number can also be asked: "Ask" sits in the pill as a quiet word in `active-text`, and once the text reads as a question (it ends in a question mark, or it is three words or more and starts like one, `looksLikeQuestion`) it fills like Open and the keyboard's action becomes Send. Asking opens "Ask about your pull requests", built from the same parts as Ask on a pull request (rule 99): the question in the accent bubble, the answer as plain text, the work log and orb while it is written, and under the answer the pull requests it names as chips, copy, redo and the follow-up questions. A pull request the answer names is also a link in the text (rule 96). Claude Code or Codex on the server answers from the open pull requests and the ones closed in the last 30 days, without the repositories switched off in Profile, and reads one of them further (description, checks, reviews, diff) through `gh` when the question needs it; only listed pull requests can be read. The conversation is kept on the server, so it is still there after a pull request opened from it, and New chat starts it afresh. With any other engine the pill only searches, as before. D2: he kept the darker teal (`primary`, #1B7580) on the Overview's Review and Analyze buttons and the Diff's round ›, and the Colors section now says so. Open edges: the Ask was checked by unit tests, compiling and stills, not on a phone, and how quickly an answer comes depends on the model and on `gh`.
- **2026-10-15, one Summary tab (rules 20, 28 and 38, 0.17.0). Asked by Tavi on 2026-10-14.** The Overview's Summary and Description tabs are one tab, Summary, so the tabs are Summary, Commits and Conversation, all three there from the start (rule 53): before the conversation arrives, Commits and Conversation hold skeleton rows. The Summary tab shows the author's description, still edited where it is (rule 83), under the Analyze card and above the brief, What needs a human and Files. Once an analysis has written a summary, the summary takes the description's place and a two-way capsule over the text, "Summary" and "Description", switches between them (rule 20's "one easy switch"): the pill slides and the other text fades in beside it (`BlurSwitch`, rule 67), and the capsule stays where it is. The choice holds while the pull request is open, and the next summary an analysis writes takes the place again (`showsDescription`). My reading of rules 28 and 38: they made the two texts underline tabs while they were page tabs; now that they share one tab, a second row of underline tabs under the first would read as tabs inside tabs, so the switch is the kit's capsule (`CapsuleTabs`, the same control as Inbox and Tours and the sheets, rule 70) at 240dp beside the text, and the page's own tabs stay underlined. Open edges: checked by compiling, unit tests and Robolectric stills in light and dark, not on a phone.
- **2026-10-15, sorting, folding and pinned headings on Home (rules 70, 74, 75, 81 and 101, 0.18.0). Asked by Tavi on 2026-10-14.** The Inbox can be sorted by recently updated (the default), least recently updated, PR number or largest change. The order is a chip after Waiting on you, Authored and All, the same 36dp outlined pill with the arrows in front so it reads as an order and not one more filter, and it names the current order (rule 81); a tap opens a Sort sheet with the four, the current one ticked, and a pick sorts at once and closes it. The repositories follow the order of their first row, so a sort orders them too, and a new sort starts the list from its top. A repository's heading now carries its count and a chevron: a tap on the heading folds its rows away or opens them, a tap on the name opens the repository, as before. The fold looks like the Files headings that fold (rule 70), is kept on the phone across restarts and is shared by Inbox and Tours, and a search opens every fold (rule 101). While its rows scroll, a repository's heading stays pinned at the top over them, the rows fading out just below it (rule 74) and one hairline under it (rule 75), and the next heading pushes it away as it arrives. Sort and folds are stored with `InboxLayout`; the pinned heading is `PinnedHeadings` over the list, with `pinnedGroup` the pure part the tests cover. Open edges: checked by compiling, unit tests and Robolectric stills (top, pinned, folded, the chip), not on a phone, so the feel of the push while flinging needs a finger.
- **2026-10-15, updates and the changelog, 0.19.0 (rule 104).** Tavi asked for the download banner to float on top of the Home search, a notification on the phone for new versions, and the changelog as a bottom sheet when the app opens on a new version. The banner was the first row of the Inbox list and its only words were "Update 0.16.0 is ready." with empty notes, because nothing wrote any. Now `android/CHANGELOG.md` is the one source: the app bundles it, the What's new sheet reads it, and `publish-apk.sh` puts it in `latest.json` with this version's summary as `notes`, so the card and the notification can say what is coming before it is installed. Earlier sections (0.9.0 to 0.16.0) were written from the merged pull requests. The background check is WorkManager every 15 minutes with a network, against the same `latest.json`, with no push service; it notifies once per version, and not for a version the open app already showed. My readings: "on top of the search bar" is above it, not over it; the card steps aside while the keyboard is up, because then the search is what you are doing; the notification's Update opens the app and starts the download, because Android does not let an app start an install from the background. Open edges: the card, its states and the sheet's content were checked in Robolectric stills, and the notification, the install and the permission prompts need a phone.
- **2026-10-15, pull request stacks (rules 5, 13, 14 and 70, 0.20.0). Asked by Tavi on 2026-10-14.** A pull request that sits in a stack says so. The server reads GitHub's own stacks where the host has them and otherwise infers them from branches, the way Graphite and spr stack: in one repository, A sits on B when A's base branch is B's head branch; a fork's branch never counts and the default branch is always the bottom. On Home a stacked row carries the layers glyph after the author and "on #241" for the layer under it, the bottom layer the glyph alone; no "2 of 3" (rule 14). On the pull request the same glyph sits in a soft accent chip under the title, "Stacked on #241" or "Bottom of a stack", and a tap on it opens the Stack sheet (rule 5): every layer top first, each a Home row (number column, the whole title, its state, checks and size in the Home columns, rule 13), joined by one thin line down to the base branch, like GitHub's stack map. This pull request is the filled accent dot and opens nothing; a tap on another layer opens it. My reading: the chip is the branch chip's size and tint so it reads as a fact about the pull request, not a button among the actions. Open edges: the stack queries were not run against GitHub from the sandbox, and the rows, chip and sheet were checked by compiling, unit tests and Robolectric stills, not on a phone.
- **2026-10-15, one way to start an AI review (rule 61's open point, 0.21.0). Asked by Tavi on 2026-10-14.** Tavi called "Review with" half-baked: "Review through a lens" opened a searchable list of every skill on the server computer. The ⋯ sheet now has one row, "Review with AI ›", which opens the Review sheet (`ReviewSheet` in `Critique.kt`): General first, then the review skills curated in the repository (`docker/agents/skills`), each its title and one line of what it looks for, in the Analyze sheet's choice rows (rule 70), with the tick on the one this pull request was last reviewed with. A tap starts the review with it and closes the sheet; the toasts and "Already reviewing" stay. There is no search field, because the list is six long (rule 89), no line explaining the sheet (rule 87), and the list is kept for the run and refreshed quietly each time the sheet opens (rules 50 and 52), with skeleton rows only the first time (rule 51). The review card names its lens by title. The phone's locale files no longer carry the `lens` strings, which only the old sheet read.
- **2026-10-15, motion goes the way you went (0.22.0, rule 105). Asked by Tavi the same day.** Swiping between the Overview's tabs let go of the rows by springing them back toward the tab being left (about 88dp the wrong way) before the 8dp slide to the new one, so the change looked like it went backwards. Released rows now carry on the way the finger went, at the speed it was thrown, and a throw of 500dp/s counts however short; a drag that does not reach the next tab settles back. The tab underline is one ink that slides between labels and leans toward the next tab under the finger. The review's header (back and the capsule) is drawn once above the three views instead of inside each, so it no longer slides with them (rule 93), and the capsule's pill slides. Back always goes backward, where the capsule's order could send a pop from the Diff to the Tour forward. A back swipe (Android 13 and later, with predictive back now on in the manifest) moves the page, the view or the UI Book page with the finger and carries it on that way. The file name between ‹ and › and the Tour's stop title change in the direction of travel (`LabelSwitch`), the filter chips and activity strip move with the Inbox instead of popping, Commands drops from the top when pulled down, and the UI Book's pages slide in the book's order. Kept as is: a row swiped right on the Inbox still opens its pull request with the forward slide, because rule 88 asks for the same slide as a tap. The finger feel (how far the rows lean, the 500dp/s throw, the ink's lean) was stepped frame by frame under Robolectric, not on a phone, and is for Tavi to judge there. The `motion` check keeps it from regressing.
- **2026-10-15, Jev sorts the Inbox and marks the files that need a look (rules 1, 14, 70, 74, 75 and 90, 0.23.0). Asked by Tavi on 2026-10-14.** With a TypeSafe key the server asks Jev, TypeSafe's classifier, what kind of change each open pull request is (fix, feature, refactor, performance, tests, docs, dependencies or infra) and how risky it is, and which files of an opened diff need a human; the list never waits for it, and without a key nothing here shows. The server's `my-pulls-cached` answer says `classifier: "jev"` while it is on, and the phone keeps that with its copy of the list, so a saved Kind grouping does not jump to repositories on launch. The Sort sheet then lists Riskiest first (highest risk first, rows Jev has not sorted yet at the end) and, under a small "Group by" heading, a second card with Repository and Kind, ticked like the sorts; a pick applies at once and closes the sheet. Grouped by kind, the headings are the kinds in a fixed order, Fixes first, then "Not sorted yet"; a kind with no rows is left out and each keeps the current sort inside it. A kind's heading folds and pins exactly like a repository's (its fold is kept in the same set, as `kind:fix`), but its name opens nothing, so the whole heading only folds. Rows from several repositories mix under a kind, so the row's meta line starts with the repository's name as its heading would give it, muted like the author after it. While Jev is off a saved Risk or Kind falls back to Recently updated and Repository and comes back with Jev. On a pull request the phone asks `jev-files` once the diff is open and again every three seconds while Jev is still sorting, for two minutes at most, and says nothing when Jev is off or the call fails. A file Jev marks p0 (or answers about with confidence under one half) starts its second line, in the Overview and the Files sheet, with "Needs a look" in the amber the app gives warnings, at the meta size (rule 14); p1 and p2 change nothing, and no glyph is added (rule 1). My readings: with Jev off the Group by card would hold Repository alone, a choice of one, so the card is left out rather than shown with one tick (rule 1); and "Needs a look" shares the folder's line, parted from it by a faint dot, so a flagged row is no taller than its neighbours and the folder is still cut from the front (rule 90). Open edges: no request reached TypeSafe from the sandbox, and the headings, rows, Sort sheet and file rows were checked by compiling, unit tests and Robolectric stills, not on a phone.
- **2026-10-15, huge pull requests open at once (rules 51, 52 and 53, 0.24.0). Asked by Tavi on 2026-10-14.** Dayflow #270 sat on "loading" because the PR page waited for the whole diff, every patch of every file, before drawing anything. The server now keeps its own copy of the open pull requests, refreshed every few minutes, and answers from it when GitHub's head has not moved. The phone asks for a lite diff: past a patch budget the server leaves files' patches out, and the phone asks for them six at a time as each file comes on screen. The PR page draws as soon as the overview or the diff is known, and while it has neither, the overview the server stored shows first and GitHub's replaces it. Until the diff arrives, the sizes and the Files rows are bars and the review button reads "Review changes", dimmed; no screen-wide loading state is left (rule 51). A left-out patch that no longer fits the file, because the pull request moved on, says "This file changed after the pull request opened." with "Load the new version"; one that failed to arrive says why, with "Load again". A stored overview never marks the diff stale; Refresh asks GitHub again. Open edges: checked by unit tests, the server's route tests and Robolectric stills, not on a phone or against Dayflow #270 itself.
- **2026-10-15, the audit of the second walkthrough (rules 52, 77, 78, 89, 91, 93 to 96, 99 and 102, 0.25.0). Asked by Tavi.** He still saw points from the recording missing. Each of its 73 points was checked against the app at 0.16.0, with the transcript and the frames: 47 were in, 2 had changed on his later word, and 24 were missing or half done, because each batch had been checked against the plan and this file rather than his words. All 24 are in this version. Sheets: every sheet has two rests, normal (its content, up to half the screen) and tall; Profile lifts; Commands opens at normal height and the keyboard waits for a tap on the field; Profile's Commands row opens Commands as a page inside Profile; the grabber is a token with a dark value. Refresh is gone everywhere: the app reloads quietly when it comes back to the front, and new commits on a pull request load without a tap. The repository page lost its band behind the search, and the window behind the pages and the launch screen are the neutral dark. The diff layout choices in Settings show small real code. Names wrap instead of ending in "…" in the commit bar, Conversation's headers and the meta line. In the Diff the toolbar over selected lines is gone: the comment bar holds ✕, the field, In review or Post now, ⋯ and Send, and ⋯ opens Ask about these lines, Copy file and lines and Copy GitHub link; an Ask on lines is the bubble and a plain answer. Pinch zoom scales the drawn code during the gesture and lays it out once at the end, keeping the line under the fingers in place (`CodeZoom`). The header (back, capsule and commit banner) is drawn once above the pages, so it stays still while they slide; the Tour swipes to its neighbours and the Overview's last tab swipes on to the Diff. A file row opens with the same swipe as a pull request row. In the Tour, pseudocode is coloured, call trees and file trees are drawn with guide lines, a flow line that does not parse stays as words, and "In the code" puts the reason under its place at full width. While the AI works its newest lines of thinking show and the answer streams in; the source chips include the files it read; the first step reads "Started". The analysis prompt asks for links too; findings, hunk notes, file notes and answers on lines render them; a backticked path is a link; and comments posted to GitHub link each cite to the file at the head commit. Claude Code's model names take their version from the CLI's description, and its effort shows the CLI's default for each model as Recommended. The Tours list follows the repositories you chose to hide. My reading: an answer on lines without a card still sits right under its lines, so it reads as theirs. Open edges: all of it was checked by compiling, unit tests and Robolectric stills, not on a phone, so the swipes, the pinch, link taps inside rows and the sheet heights need a hand on the phone; Claude Code's default effort was read from the CLI's own table, and an account or organisation setting could override it.
- **2026-10-15, fixes from the review of 0.20.0 to 0.24.0 (0.26.0).** Nothing changes how a screen looks; these are behaviour fixes that review bots found. Home re-reads the server's stored list every few seconds, for two minutes at most, while Jev is still sorting rows, so kinds and risk arrive without a reload and no screen-wide loading shows (rule 51). The stack under a pull request's title loads again on every load, and a failed load keeps the one shown. The Review sheet's list belongs to the server it came from, so a switched server never offers the old one's skills. "Needs a look" marks and left-out patches belong to the head they were asked for: a push clears the marks, and a late patch for the old head is dropped.
- **2026-10-15, updates are required and tours are kept (0.28.0, rule 104). Asked by Tavi.** He wanted Update in the command palette, the changelog shown and the update made mandatory when he opens an old version, and the tours he has gone through stored so they load quickly. The floating update card and its ✕ are gone: while the server offers a newer build, a full-screen update screen covers the app with the changelog and Update, and only installing it ends it. The last offer is kept on the phone, so the screen shows from the first frame; the background check keeps it too. Commands has Update Pulls (asks the server now, however recently it was asked) and What's new. Tours: the server already kept every tour on disk, and the phone kept only the Tours list and your place in each; the tour itself was fetched after the whole diff on every open. Now the phone keeps each tour it has shown (the newest 200), opens it before the diff or the overview arrives, and replaces it with the server's answer; Home fetches the tours on the Tours list the phone lacks. My readings: Back on the update screen leaves the app rather than doing nothing; a tour's sources and mentions open once the diff has arrived. Open edges: checked by compiling and unit tests, not on a phone.
- **2026-10-15, the walkthrough of 0.24.0 (rules 107 to 112, 0.29.0). Asked by Tavi.** His recording of 0.24.0 named 36 points, written down with their times in `/mnt/project-files/design/feedback-2026-10-15-0.24.0.md` and checked against the frames. Pages that sit side by side now slide whole with no fade (`SidePages` for the Inbox and Tours and a repository's two pages, `rememberPageSlide(full = true)` for the Overview's tabs and the Diff's files); a sheet's pages travel a third of its width. Sheets settle on a spring, Commands rises from the bottom like every sheet (the old "drops from the top" of rule 105 is gone), and a sheet only closes when its content is at the top and the drag starts there. Every cue also vibrates, with a Vibration switch. Light and Dark wipe in from a corner. The server keeps each repository's first page of pull requests and fetches the Inbox's busiest twelve ahead, and the phone keeps the last page it saw, so the repository page opens with rows. The Overview takes a pull request's size from the list it was opened from until its diff counts it. Two points wait on Tavi's pick from design options: the Analyze card and where sort sits at the top of Home. Kept as he asked: auto refresh, and Closed pull requests in Profile for now. Already fixed before this walkthrough: the squeezed "Viewing commit 2 of 7" bar (0.25.0). The feel of the swipes, the spring and the vibrations was not tried on a phone.
- **2026-10-15, the tour from its icon and sort beside search (rules 113 and 114, 0.30.0). Picked by Tavi.** He turned down the first round of options for the Analyze card and the top of Home ("I don't like any of the options"), which only restyled the same card and chip. From the second round he took sort beside the search (A) and, for Analyze, asked for his own variant of the top-bar icon (D): no Analyze card on the Overview at all, and the tour icon opening a "No tour yet" sheet with the model and Generate tour whenever there is no tour (`AnalyzePage.Tour` in `AnalyzeSheet`, `generateTour`). The Analyze card's code (`AnalysisBlock`, `AnalyzeStatusCard`, `AnalyzeSplitButton`, the log sheet and core's `analyzeCard`) is gone; the Tour page shows the analysis's own work log until the tour's starts (`tourComing`). My readings: the sheet's title is "No tour yet" and its button "Generate tour", from his words; the empty Tour page that a Tours row or a link can still open offers the same Generate tour. Open edges: checked by compiling, unit tests and the design checks, not on a phone.
- **2026-10-15, the walkthrough of 0.29.0 (rules 115 to 129, and 61, 74, 77, 79, 92, 107, 108 and 112, 0.31.0). Asked by Tavi.** His two recordings of 0.29.0 named 36 points, written down with their times in `/mnt/project-files/design/feedback-2026-10-15-0.29.0.md` and checked against the frames. The Overview's three tabs are one `SidePages` under one head that both drags them and lifts with the tab in view, which reverses H32; every side by side pair keeps its scroll; the page glide is critically damped with a fine threshold, so it no longer snaps; the sheet spring overshoots a fifth less. Sheets opened from sheets are pages of one `PrSheet`, the decisions sit a page in with a page that asks first, and the Commands row is gone from ⋯ since pulling down works on every page. Commands opens tall with the keyboard; a field lifts its sheet on the sheet spring; sheets are felt when they land. One fold heading, Files and Checks in cards, People and Bots chips on a long conversation, branches from the Inbox row, sideways rows that fade at their edges, one hold feel (`cueCombinedClickable`). The diff pins its counts line and slides files side by side and fetches colours ahead; tours show work from the tap, end straight on the tour, keep the place on Back and keep dot-folders in links; Default's tap is a dry click, Sound is a page, What's new reads like a changelog and the profile photo shares the header's image. My readings: "Approve, merge or close" names the decisions page; turning Merge when checks pass off does not ask, since it only stops a merge; the Conversation chips show only when both people and bots spoke. Open edges: checked by compiling, unit tests and the design checks, not on a phone, so the feel of the swipes, the spring, the keyboard lift and the vibrations needs a hand on the phone; the first file opened from the Overview still waits one round trip for its colours.
- **2026-10-15, Jev's read shows, and Jev runs only when it is needed (rules 1, 14, 50, 53 and 72, 0.32.0). Asked by Tavi.** He wanted what Jev computes shown rather than kept on the server ("We can actually show all this stuff"), and Jev not run for every pull request and every file as they arrive, but when there is an analysis, as something the analysing model can use. The server no longer asks Jev about each listed pull request or each opened diff. It asks when a review of a whole diff starts (an analysis, Look for problems, a tour or a brief, and the server's own pre-analysis): it rates the files and sorts the pull request, waiting at most 20 seconds, with one line in the work log, "Finding the files that need a careful look", that shows only when there is something to rate (rule 72). The model then gets a `triage_files` tool, listed first and named in its instructions, that reads Jev's kind and risk and the files by priority with how sure Jev is, as a hint it must still check. The Inbox asks Jev about every open row only while it is sorted by risk or grouped by kind, which need all of them (`my-pulls-cached` with `classify`). On screen: a row Jev has read gets a third line with its kind, how sure in words rather than a number, and its risk in words, "High risk" in the amber the app gives warnings; the meta line has no room left for them (H15), and under a kind's heading the kind is not repeated. On a pull request a plain line under the branch says the same and how many files need a look, with nothing to tap (rule 53). Every rated file now says its rating: "Needs a look" in amber, "Worth a look" and "Skim" in the muted ink, where only the first showed before. My readings: words over percentages for sureness, since Tavi reads plain words best; and the third line on a row rather than a mark under the number, which has room for "2/10" but not for a risk. Open edges: no request reached TypeSafe from the sandbox, and the rows and lines were checked by compiling and unit tests, not on a phone. The web app shows none of Jev yet.
- **2026-10-16, the walkthrough of 0.37.0 (rules 93, 107, 138, 139 and 140 to 145, 0.39.0). Asked by Tavi.** His recording of 16 Oct 09:50 has twelve points, M1 to M12, all built. Reversed on his word: the Jev line on Inbox rows (now one risk word under the number, only when sorted or grouped by Jev), the left swipe to archive, the right swipe on Tours rows that opened the review, the fold headings and counts on sheets (L12), the scaled picture during a pinch (back to laying the code out at each size, as before 0.25.0), the gap between the Diff's files, the word before the summary link, and closing the tour sheet on Generate (J28). My readings: the risk word is the one thing kept from Jev on a row, since sorting by risk without it would be blind; the sheets keep the + and − numbers on Files headings (L20), which are not a count; the profile's grid shows half a year so each day is big enough to see. Open edges: checked by compiling, unit tests and the design checks, not on a phone, so the pinch's smoothness on a huge file and the feel of the first tab swipe need a hand on the phone.
- **2026-10-16, every rule checked against what came after it (no version). Asked by Tavi.** He asked whether every rule from his recordings had made it into the rules file, the contracts, this file and the UI Book. Each of rules 1 to 145, the Keep as is list and the checklist was read against the later rounds (F, H, J, K, L and M points), the contracts and the code, and the text that no longer held was rewritten: 40 rules (among them 6, 17, 28, 29, 30, 32, 42, 53, 56, 57, 61, 71, 77, 79, 80, 83, 84, 89, 91, 95, 96, 101, 105, 113, 119, 121, 135 and 136) and 13 checks. A canvas rule keeps its old words in a "The canvas said" note. His 0.33.0 recording (L1 to L36) had become contracts but no rules; its lessons are now in the rules they belong to (49, 53, 87, 95, 101, 119, 128, 131, 134, 137, 143 and 144), not new numbers, because the 0.38.0 round takes 146 on. Rule 71 is reversed on his word (L33: no grey bars under a live indicator), so Ask's last hint bars are gone from the code. The rules 115 to 129 got their own heading.
- **2026-10-17, the walkthrough of 0.47.0 (rules 2, 191, 192 and 195 to 200, 0.48.0). Asked by Tavi.** His recording of 17 Oct 14:45 has seven points, Q1 to Q7, all built. Reversed on his word: PR titles in list rows wrap (rule 2), now one line with an ellipsis; the Overview's bar that opened the conversation on a tap (N28), now opening itself. My readings: the short Ask pill is centred and as wide as the word needs with room around it; the Inbox and a repository's search are different bars, so the words fade between them; the tapped place maps to the editor by its share of the text's height and lands at the start of that word. Q7, the diff's swipe back to the file the diff opened on, was a bug (the gesture kept the first file), not a rule. Open edges: checked by compiling, unit tests and Robolectric films on fake data, not on a phone, so the feel of the pill's width easing and of the live Markdown under a real keyboard needs a hand on the phone.
- **2026-10-26, every version says when it came out (rule 205, no version). Asked by Tavi.** He asked for a date and time on each changelog entry from now on. A section now carries `#### <date> <time> UTC` under its heading; the app shows it in the phone's zone and language at the far end of the version's header on the update screen and in What's new, and the `changelog` check, the release check and core's `ChangelogTest` fail on a section without one, with a time that does not exist, out of order, or placed below the summary. My readings: the time is written in UTC so a writer in any zone puts down the same thing; the 56 sections before it take the time their `versionName` first landed on main, from git; a `####` line, not a comment or the heading line, because builds from before this one skip it without losing the section or its summary, and `publish-apk.sh` now skips it too when it takes the notification's summary. A new time alone does not count as new notes for a release. Open edges: checked by compiling and unit tests, not on a phone.
- **2026-10-27, foldables and sideways phones (rules 206 to 209, 0.65.0). Asked by Tavi the same day.** Rendered at 891 by 411 the app stretched every row, tab and pill across the screen, the overview's pinned head left no room for its words, and a sheet spread the whole width resting at a sliver; unfolded at 841 by 701 the Inbox and a pull request were each a stretched phone page. `AppPages` now reads the window's shape: unfolded it lays the one route stack out as two panes (the last list page, and the top page beside it), each with its own `FootBars`, the page width the diff sizes its columns from (`LocalPageWidth`) and its span, so a sheet rises over its pane; a page opened from the list replaces the one beside it. Sideways and on anything wider than 600dp a list page, a pull request's header, overview and tour sit in a 600dp column (`readableWidth`), the diff's code keeps the whole width, the header comes up under the status bar and a sheet rests tall. Every page keeps clear of a side cutout or navigation bar. `PullsType.wide` is no longer set on the phone, since no column reaches 768dp. Rendered with Robolectric at 411 by 891, 891 by 411, 841 by 701 and 707 by 841; the phone renders are pixel for pixel what they were.
- **2026-10-27, no Back beside the list (rule 210 in Android 0.66.0). Asked by Tavi the same day.** With both panes in sight the right pane's Back read as a way to somewhere you could already see. `TwoPanes` marks the page right above the list with `LocalFirstBeside`, and the pull request header, its opening page and its load-failed page leave Back out there. We weighed a close X that empties the pane, and kept none: a tap on another row already replaces the page, the back gesture still empties it, and an X would be one more control for a rare need. A page stacked in the pane keeps Back.
