/**
 * @cc [owner:OctavianTocan,label:architecture] pully-linux-job-placement
 * Every Pully Linux workflow job MUST use the repository's pully-linux self-hosted runners.
 * Source: Octavian's runner decision, 28 Oct 2026.
 */
export const linuxRunnerLabels = ['self-hosted', 'linux', 'x64', 'pully-linux']

/**
 * @cc [owner:OctavianTocan,label:architecture] pully-ios-verification-optional
 * iOS verification MAY use GitHub-hosted macOS and MUST NOT be required for merging
 * or publishing a release. Source: Octavian's runner decision, 28 Oct 2026.
 */
export const iosVerificationRequired = false
