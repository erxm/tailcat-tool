#!/usr/bin/env bash
set -euo pipefail

TASK_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$TASK_ROOT"
bash "$TASK_ROOT/scripts/build-icon.sh"
swift build -c release --product TailcatManager
swift build -c release --product TailcatCredentialHelper
TASK_BIN="$(swift build -c release --show-bin-path)"
TASK_VERSION="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' resources/Info.plist)"
TASK_APP="$TASK_ROOT/dist/Tailcat Manager.app"
if [[ -f "$TASK_APP/Contents/MacOS/TailcatManager" ]] && /usr/sbin/lsof -t "$TASK_APP/Contents/MacOS/TailcatManager" >/dev/null 2>&1; then
    TASK_APP="$TASK_ROOT/dist/$TASK_VERSION/Tailcat Manager.app"
    printf '%s\n' 'Existing app is running; packaging separately to preserve its connections'
fi
if [[ -f "$TASK_APP/Contents/MacOS/TailcatManager" ]] && /usr/sbin/lsof -t "$TASK_APP/Contents/MacOS/TailcatManager" >/dev/null 2>&1; then
    printf '%s\n' 'The output app is running; close that version before replacing it' >&2
    exit 1
fi
mkdir -p "$TASK_APP/Contents/MacOS" "$TASK_APP/Contents/Resources"
cp "$TASK_BIN/TailcatManager" "$TASK_APP/Contents/MacOS/TailcatManager"
cp "$TASK_BIN/TailcatCredentialHelper" "$TASK_APP/Contents/MacOS/TailcatCredentialHelper"
cp "$TASK_ROOT/resources/Info.plist" "$TASK_APP/Contents/Info.plist"
cp "$TASK_ROOT/resources/AppIcon.icns" "$TASK_APP/Contents/Resources/AppIcon.icns"
cp "$TASK_ROOT/LICENSE" "$TASK_APP/Contents/Resources/LICENSE"
cp "$TASK_ROOT/THIRD_PARTY_NOTICES.md" "$TASK_APP/Contents/Resources/THIRD_PARTY_NOTICES.md"
bash "$TASK_ROOT/scripts/sign-app.sh" "$TASK_APP/Contents/MacOS/TailcatCredentialHelper" cc.coauto.tailcat-manager.credentials
bash "$TASK_ROOT/scripts/sign-app.sh" "$TASK_APP"
TASK_ZIP="$TASK_ROOT/dist/Tailcat-Manager-${TASK_VERSION}.zip"
ditto -c -k --sequesterRsrc --keepParent "$TASK_APP" "$TASK_ZIP"
printf '%s\n' "$TASK_APP"
printf '%s\n' "$TASK_ZIP"
