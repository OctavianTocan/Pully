#!/bin/bash
set -euo pipefail

if [[ "${OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED:-NO}" == "YES" ]]; then
    exit 0
fi

cd "${SRCROOT:?Xcode must supply SRCROOT}/../mobile"
exec ./gradlew :shared-domain:embedAndSignAppleFrameworkForXcode --no-daemon
