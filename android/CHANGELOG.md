# What's new in Pulls

<!--
One section per version, newest first, headed with the versionName from app/build.gradle.kts.
The first paragraph is the summary: one short sentence (70 characters at most, the notification
shows two short lines) that the update notification and the published latest.json use. The
bullets under it are what the What's new sheet lists.

Write for the person holding the phone: what they will see or can now do, in plain words. No
rule numbers, file names or component names. A pull request that bumps the version adds its
section here; `pnpm run android:design` fails when the version has none. The app bundles this
file, and android/scripts/publish-apk.sh puts it in latest.json.
-->

## 0.32.0

Pull requests show their kind and risk, and files their rating.

- A pull request Jev has read says what kind of change it is and how risky, under its name in the Inbox and under the branch on its page.
- Every rated file says "Needs a look", "Worth a look" or "Skim".
- Jev reads a pull request when it is analysed, and the AI uses its ratings to decide where to look first.
- The Inbox asks Jev about every pull request only while it is sorted by risk or grouped by kind.

## 0.31.0

Sheets open into pages, risky actions ask first, Commands anywhere.

### New

- Pull down on any page for Commands. Commands opens ready to type.
- In a pull request's ⋯ sheet, everything you open slides in as a page with Back, and the phone's Back goes back a page.
- Approve, request changes, merge, close and draft sit one page in, under Approve, merge or close, and each asks before it acts.
- Summary, Commits and Conversation sit side by side: swipe anywhere on the page, even the title, and the tab marker moves with you.
- Swiping between pages keeps your place, so the Inbox is still where you left it after a look at Tours.
- Changes: files sit side by side as you move between them, the counts line stays at the top, and colours show sooner.
- Conversation: chips show only what people wrote, or only the bots.
- Sound is now a page in Settings, and What's new reads like a proper changelog.

### Fixed

- Sheets bounce a little less, rise smoothly when the keyboard opens, and you feel them arrive.
- Moving between Inbox and Tours no longer snaps at the end.
- The ringing tap sound is gone; buttons click.
- Light and Dark only sweep when the look actually changes.
- The branch names and your profile photo show at once.
- Folding sections looks and moves the same everywhere, and the Files and Checks sheets use cards.
- Holding, folding and swiping feel the same on every page.
- Starting a tour shows its progress right away, it never flashes the start page at the end, and Back from a linked file returns to the same place.
- Links to files in hidden folders like .agents open the file instead of a missing page.
- Rows that scroll sideways fade at their edges.

## 0.30.0

Tours start from the tour icon, and sort sits next to search.

- A pull request opens on its description, with no Analyze card above it.
- Tap the tour icon on a pull request without a tour: pick the model and generate one. The tour page shows the work as it is written.
- Sort is the round button next to the search at the bottom of the Inbox, always in reach.

## 0.29.0

Screens slide side by side, sheets bounce, and taps are felt.

- Inbox and Tours sit side by side: tap or swipe between them. Summary, Commits and Conversation, and the files in Changes, slide whole too, with no fade.
- Pages inside a sheet move far enough to see which way they went, and Back goes to the right.
- Sheets rise from the bottom with a little bounce, Commands too, and scrolling a sheet's content back up no longer closes it.
- Taps, swipes and results vibrate. Settings has a Vibration switch under Sounds.
- Switching Light and Dark sweeps the new look in from a corner.
- Settings shows the model's name, keeps Server under Advanced at the bottom, drops three explanations, and lets you pick Split on any phone.
- Profile: tap your photo or name for your GitHub profile. Commands and the server address are gone, and the list sits closer.
- Inbox: names stay on one line, a stacked pull request shows its place, like 2/10, under its number, and holding a row opens its actions.
- The search dims the list as soon as you tap it, and the dim lifts a little slower.
- Repository headings no longer slide over the filter chips.
- A repository's page opens with its list straight away, because the server keeps it ready.
- Tours: a repository heading opens that repository's tours, and a finished tour shows a tick without "Read".
- A pull request shows its size at once. Its branch stays on one line and scrolls sideways, and only the part under the tabs swipes between them.
- Tapping a title puts the caret where you tapped, and the rest of the page fades while you edit.

## 0.28.0

Updates are required now, and tours you have opened load at once.

- When a new version is out, Pulls opens on it: what it brings, and Update. The app waits until you update.
- Commands has Update Pulls, which checks for a new version and installs it, and What's new.
- A tour you have opened is kept on the phone, so it shows straight away, before the rest of the pull request loads.
- Tours on the Tours list are saved to the phone in the background, including ones written while the app was closed.

## 0.27.0

Pi joins Claude Code and Codex: any model from your own API.

- The model picker has a third tab, Pi. It lists every model your server's API offers, such as Claude, GPT and Gemini models through CLIProxyAPI, with the newest of each family first.
- Analysis, reviews, tours, briefs, questions and Ask about your inbox all run on the model you pick there, the same way they do on Claude Code and Codex.
- Until the server is set up for it, the Pi tab says what the server needs.

## 0.26.0

Fixes for stacks, Jev groupings, AI reviews and huge pull requests.

- Jev's kinds and risk show up on Home as soon as they are ready, without leaving and coming back.
- On a TypeSafe plan with lower limits, Jev slows down instead of leaving pull requests unsorted.
- A pull request's stack shows again after it reloads, and unusual branch setups no longer show a stack that isn't there.
- Review with AI lists the review skills of the server you are connected to.
- "Needs a look" marks are cleared when a pull request gets new commits.
- A huge pull request that gets new commits while you read it never mixes in code from before.

## 0.25.0

Everything still missing from your walkthrough video is now in.

- Every sheet rests at two heights. Profile and Commands open at the normal one and pull up to tall, and Commands waits for a tap before the keyboard comes up.
- Commands opened from Profile slides in inside Profile.
- There is no Refresh anywhere. Pulls reloads when you come back to it, and new commits on a pull request load by themselves.
- Dark mode is a neutral grey everywhere, the launch screen included, and Settings shows real code for the diff layouts.
- Long names wrap instead of being cut short.
- Selecting lines in the Diff turns the bottom bar into the comment bar, with nothing floating over the lines. Its ⋯ asks about the lines, copies them or copies their GitHub link.
- Pinch zoom keeps the line under your fingers in place.
- Swipe from the Tour back to the Diff, or past the last tab of a pull request on to the Diff.
- File rows open with the same swipe as pull request rows.
- In a tour, pseudocode is coloured, call trees and file trees are drawn, and "In the code" no longer squeezes its text.
- While the AI works you see its latest thinking and the answer as it is written, and its sources include the files it read.
- Every file or line the AI mentions is a link, in notes, findings and answers too, and in what it posts on GitHub.
- Claude's models show their version, and each one's usual effort is marked Recommended.
- The Tours tab hides the repositories you chose to hide.

## 0.24.0

Huge pull requests open at once.

- The title, description and conversation show right away, even on pull requests with thousands of changed lines.
- The server keeps a copy of your open pull requests, so they open from it instead of waiting on GitHub.
- On a big diff, each file's code loads as you reach it.

## 0.23.0

Group pull requests by kind and see the riskiest first.

- When the server has Jev, TypeSafe's classifier, the Sort sheet adds Riskiest first and a Group by choice: by repository or by kind, such as fixes, features and refactors.
- Grouped by kind, each row names its repository next to the author.
- In a pull request's files, the ones Jev thinks need a closer look say "Needs a look".

## 0.22.0

Swipes and pages now move the way your finger goes.

- Swipe between a pull request's tabs and the rows follow your finger, then carry on to the tab you went to instead of springing back first. A quick flick is enough.
- The line under the tabs leans toward the next tab as you drag and slides across when it changes.
- Next, previous and Back move the way they say: the next tab, file or view comes in from the right, and Back always slides backward.
- A back swipe moves the page with your finger, then carries it off the way you swiped.
- The back button and the Overview, Changes and Tour switch stay still when you change view, and the switch's highlight slides.
- The file name at the bottom of the diff and the stop on the Tour slide the way you went.
- The filters and the activity strip move with the Inbox when you switch to Tours.
- Commands, pulled down from the top, drops from the top and goes back up.

## 0.21.0

Review with AI now offers only the review skills chosen for it.

- The More sheet has one Review with AI row. It opens a short list: General, then each review skill by its name and what it looks for.
- The list shows only the skills chosen for reviews, not every skill on the server computer.
- A tap starts the review, and a tick marks the one this pull request was last reviewed with.

## 0.20.0

See which pull requests are stacked on others.

- A pull request that sits on another shows a stack mark on Home, with the number of the one under it.
- On the pull request, a chip under the title says what it is stacked on. Tap it to see the whole stack, top to bottom, and open any other layer.

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
