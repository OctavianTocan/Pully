# Browser helper

The optional accessibility helper shows a repository-named **Open in Pully** button
only when a supported foreground browser exposes a complete GitHub repository URL
in its native address field. Tapping it opens the repository in Pully. A PR, issue,
file or other repository subpage is normalized to the repository root.

Enable it in **Settings → Browser helper**. Read the separate disclosure, continue,
then enable **Pully browser helper** in Android accessibility settings yourself.
Android's permission alone is insufficient; Pully's consent defaults off. **Disable
helper** withdraws consent immediately and disables the service. Sideloaded apps may
also require Android's *Allow restricted settings* step. Nothing enables this on a
phone automatically.

## URL verification and limits

The helper checks exact native address-field IDs, browser package, visibility,
focus, foreground window and URL syntax. It does not scan a page for GitHub links,
read the clipboard, infer a URL from a title, or keep browsing history. A field
inside web content is rejected. The URL is normalized locally; only `owner/repo`
is held in memory, and only an explicit tap hands it to Pully's normal repository
flow. No analytics or network calls are made by the helper.

Unknown browsers, hidden/elided address bars, editing an address, conflicting
fields, lock screen, other apps and unavailable accessibility content hide the
button. The URL is checked again on tap so a previous tab's repository cannot
be opened from a stale button. Browser UI updates can change accessibility IDs.
A native accessibility observation is not a guaranteed browser navigation API;
a URL the browser has not exposed cannot be recovered safely. The helper does
not claim universal browser coverage or verify that a repository exists online.

## Browser adapter matrix

No Android device or emulator has been used for this change. The following are
implemented address-field adapters, **not device-verified browser support**:

| Browser family | Packages | Native field candidate |
| --- | --- | --- |
| Chrome / Chromium | Stable, Beta, Dev, Canary, Chromium | `url_bar` |
| Brave | Stable, Beta, Nightly | `url_bar` |
| Edge | Stable, Beta, Dev, Canary | `url_bar` |
| Vivaldi | Stable, Snapshot | `url_bar` |
| Firefox | Stable, Beta, Nightly | `mozac_browser_toolbar_url_view` |
| Samsung Internet | Stable, Beta | `location_bar_edit_text` |
| Opera | Stable, Beta | `url_field` |

Other browsers deliberately produce no button. Add an adapter only after checking
its actual native address field, including focused/hidden state and navigation.
Never add a generic text or content-description fallback to claim wider coverage.

## Verification

Run from `android/`:

    ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug

The `Android verify` PR workflow runs the same checks. `BrowserRepoTest` covers URL
normalization, GitHub reserved routes, spoofed authorities, credentials, ports,
encoded/elided text, address editing, hidden fields, web-content lookalikes,
unknown packages and conflicting candidates.

On each target browser/version, manually check in light and dark themes:

- Consent decline/Back, returning from Android settings without enabling, enabling,
  disabling, system revoke, service reconnect and process restart.
- Repository root and subpages, PR/commit/compare → button opens repository root.
- GitHub profile, search, org settings, unrelated sites and a page merely containing
  a GitHub link → no button.
- Typed but unsubmitted GitHub URL, toolbar collapse, tab switches, redirects,
  back/forward, scrolling, notification shade, keyboard, split screen and lock.
- Switch to another app or Pully itself; no stale button. Tap during navigation;
  either the verified current repository opens or nothing opens.
- Long owner/repository names, large text and screen readers; labels stay readable.

Compilation/JVM checks do not establish any of these on-device behaviors.

## Distribution

Accessibility is a sensitive capability. Google Play requires the relevant
accessibility declaration/review and prominent disclosure/consent for apps that
are not accessibility tools. This helper does not set `isAccessibilityTool=true`.
Play approval is not implied by implementation. No draw-over-other-apps,
screenshot, gesture, notification, browser-history or package-inventory permission
is added for this feature.

References:
- https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
- https://support.google.com/googleplay/android-developer/answer/10964491
- https://support.google.com/android/answer/12623953
