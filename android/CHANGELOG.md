# What's new in Pulls

<!--
One section per version, newest first, headed with the versionName from app/build.gradle.kts.
The first paragraph is the summary: one short sentence (70 characters at most, the update card
shows two short lines) that the card, the notification and the published latest.json use. The
bullets under it are what the What's new sheet lists.

Write for the person holding the phone: what they will see or can now do, in plain words. No
rule numbers, file names or component names. A pull request that bumps the version adds its
section here; `pnpm run android:design` fails when the version has none. The app bundles this
file, and android/scripts/publish-apk.sh puts it in latest.json.
-->

## 0.19.0

New versions float above the search and notify your phone.

- When a new version is ready, a card floats just above the search on Home. Tap it to read what's new, or tap Update to install it.
- Pulls checks for new versions in the background and sends a notification when one is out. Its Update button opens the app and starts the download.
- After an update, a sheet like this one opens once to show what changed.
- Settings has a What's new page to read it again, and a switch for the update notifications.

## 0.18.0

Sort the Inbox and fold its repositories.

- A chip after the filters says how the Inbox is sorted. Tap it to sort by recently updated, least recently updated, PR number or largest change.
- Tap a repository's heading to fold its pull requests away, or tap its name to open the repository. Folds are kept, and a search opens them all.
- While you scroll, a repository's heading stays at the top until the next one takes its place.

## 0.17.0

Summary and Description are one tab.

- A pull request's tabs are Summary, Commits and Conversation, all there from the start.
- Summary shows the author's description until an analysis writes a summary. Then a switch over the text moves between the two.

## 0.16.0

Ask a question from the Home search.

- Type a question in the search on Home and tap Ask. Claude Code or Codex answers it from your open pull requests and the ones closed in the last month.
- Pull requests the answer names open with a tap, and the conversation is kept.

## 0.15.0

Small fixes where the app still looked unlike the design.

- No more speech bubbles or progress counters.
- Tours rows look like Inbox rows.
- More has a switch to merge when checks pass.
- Stop is the turning orb wherever something runs, and every retry is the same arrow.

## 0.14.0

AI answers show code like the diff, and what they mention opens.

- Code in a tour, the brief or an answer uses the diff's code view, and flows are drawn.
- Files and lines an answer mentions are links into the diff.
- Ask answers have source chips, copy and redo, and suggest follow-up questions.

## 0.13.0

The Tour and Ask, reworked from the walkthrough.

- The Tour strip is a ring that fills as you scroll, and its sections open as a sheet.
- Ask replies are plain text, with a new-chat button instead of Clear.
- Tables in AI text keep readable columns and scroll sideways.

## 0.12.0

The Diff's file name sits between the arrows.

- Tap the file name at the bottom of the Diff to open Files, with search and folding sections.
- Swipe sideways to move to the next file.
- Commits have their own tab.
- The Home list dims while the search waits for you to type.

## 0.11.0

The Analyze card keeps one height, and the model list is shorter.

- Tap the orb to stop an analysis.
- The model list shows the newest models first, with older ones below.

## 0.10.0

Pull requests slide in and edit in place.

- Edit a pull request's title and description in place.
- Commits, Checks and Files are plain lists with whole titles.

## 0.9.0

Sheets rest at half height and lift to full.

- Pages inside a sheet slide sideways and keep their place when you go back.
- Settings and Profile are rebuilt, and Profile can hide a repository from the Inbox.
