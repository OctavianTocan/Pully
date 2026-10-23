# Native required-reason API evidence

The app-owned `Pully/PrivacyInfo.xcprivacy` declares the APIs actually used by the
native host and shared dependencies. This records required-reason API use, not a
complete data-collection or App Store privacy assessment. Preserve the Google
Sign-In SDK's own bundled manifest and its authentication/profile/diagnostic
declarations.

| Category      | Reason   | Actual use                                                                                                    |
| ------------- | -------- | ------------------------------------------------------------------------------------------------------------- |
| UserDefaults  | `CA92.1` | The host's nonsecret configuration and existing shared `pulls-ui` preferences, confined to this app.          |
| FileTimestamp | `C617.1` | Coil's shared avatar disk cache reads size/timestamps of files in the app's temporary container through Okio. |

The file-metadata declaration accompanies the common GithubAvatar dependency
migration (Coil 3.3.0 and its Okio 3.15.0 dependency). The inspected upstream
Coil tag resolves to `1731511f0c12ef556db7822a91b6ea03fa3f95a1`:

- [`disk/utils.kt`](https://github.com/coil-kt/coil/blob/3.3.0/coil-core/src/nonJsCommonMain/kotlin/coil3/disk/utils.kt)
  creates the default cache under `FileSystem.SYSTEM_TEMPORARY_DIRECTORY/coil3_disk_cache`.
- [`DiskLruCache.kt`](https://github.com/coil-kt/coil/blob/3.3.0/coil-core/src/commonMain/kotlin/coil3/disk/DiskLruCache.kt)
  calls `fileSystem.metadata(clean).size` when committing cache entries.
- Okio's published `okio-iosarm64-3.15.0-sources.jar`,
  `appleMain/okio/ApplePosixVariant.kt`, implements that metadata lookup with
  `lstat`, returning file size and creation/modification/access timestamps. The
  inspected source artifact contains no `PrivacyInfo.xcprivacy`.
- Coil's [`fileSystems.nonJvmCommon.kt`](https://github.com/coil-kt/coil/blob/3.3.0/coil-core/src/nonJvmCommonMain/kotlin/coil3/util/fileSystems.nonJvmCommon.kt)
  returns a fixed 4 GB estimate for remaining disk space; it does not query the
  device's free space on iOS. This path does not justify a DiskSpace declaration.

[Apple's current reasons](https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitypereasons)
allow `C617.1` for metadata of files inside the app container. The cache uses this
metadata for local accounting; it does not send filesystem metadata off-device.

The macOS built-app resource check compares the app-owned manifest with this
source and checks that Google Sign-In's own manifest is present. Source inspection
does not prove an archive's complete linked API inventory; inspect the actual
archive/privacy report before a distribution submission. No App Store submission
or signed-device validation is claimed here.
