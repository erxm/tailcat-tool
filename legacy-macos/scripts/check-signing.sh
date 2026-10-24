#!/usr/bin/env bash
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TASK_TMP="$(mktemp -d "${TMPDIR:-/tmp/}tailcat-signing-check.XXXXXX")"
TASK_SERVICE="$(uuidgen)"
cleanup() {
    if [[ -x "$TASK_TMP/first.app/Contents/MacOS/Probe" ]]; then
        "$TASK_TMP/first.app/Contents/MacOS/Probe" delete "$TASK_SERVICE" "$TASK_TMP/bundled-helper" "$TASK_TMP/installed/v1" >/dev/null 2>&1 || true
    fi
    rm -rf "$TASK_TMP"
}
trap cleanup EXIT
cat > "$TASK_TMP/Probe.swift" <<'SWIFT'
import Foundation
import Security
import TailcatCore

let mode = CommandLine.arguments[1], id = UUID(uuidString: CommandLine.arguments[2])!
let store = try HelperCredentialStore(bundledHelper: URL(fileURLWithPath: CommandLine.arguments[3]),
    directory: URL(fileURLWithPath: CommandLine.arguments[4]))
if mode == "create" {
    try store.write("tc" + String(repeating: "T", count: 30), id: id)
} else if mode == "delete" {
    try store.delete(id: id)
} else if mode == "read" {
    guard try store.readForObservation(id: id) == "tc" + String(repeating: "T", count: 30) else { exit(1) }
} else {
    do { _ = try store.readForObservation(id: id); exit(1) }
    catch { print("PASS caller without GUI identity is denied") }
}
#if SECOND_BUILD
print("PASS second build")
#else
print("PASS first build / unrelated signature denied")
#endif
SWIFT
for TASK_VARIANT in first second unrelated wrong-identifier; do
    mkdir -p "$TASK_TMP/$TASK_VARIANT.app/Contents/MacOS"
    cp "$TASK_ROOT/resources/Info.plist" "$TASK_TMP/$TASK_VARIANT.app/Contents/Info.plist"
    /usr/libexec/PlistBuddy -c 'Set :CFBundleExecutable Probe' "$TASK_TMP/$TASK_VARIANT.app/Contents/Info.plist"
done
cd "$TASK_ROOT"
swift build --target TailcatCore > "$TASK_TMP/build.log" 2>&1 || { cat "$TASK_TMP/build.log"; exit 1; }
swift build --product TailcatCredentialHelper > "$TASK_TMP/build.log" 2>&1 || { cat "$TASK_TMP/build.log"; exit 1; }
TASK_BIN="$(swift build --show-bin-path)"
cp "$TASK_BIN/TailcatCredentialHelper" "$TASK_TMP/bundled-helper"
bash "$TASK_ROOT/scripts/sign-app.sh" "$TASK_TMP/bundled-helper" cc.coauto.tailcat-manager.credentials
TASK_OBJECTS=("$TASK_BIN"/TailcatCore.build/*.o)
swiftc -I "$TASK_BIN/Modules" "$TASK_TMP/Probe.swift" "${TASK_OBJECTS[@]}" -o "$TASK_TMP/first.app/Contents/MacOS/Probe" 2> "$TASK_TMP/compiler.log" || { cat "$TASK_TMP/compiler.log"; exit 1; }
swiftc -DSECOND_BUILD -I "$TASK_BIN/Modules" "$TASK_TMP/Probe.swift" "${TASK_OBJECTS[@]}" -o "$TASK_TMP/second.app/Contents/MacOS/Probe" 2> "$TASK_TMP/compiler.log" || { cat "$TASK_TMP/compiler.log"; exit 1; }
cp "$TASK_TMP/first.app/Contents/MacOS/Probe" "$TASK_TMP/unrelated.app/Contents/MacOS/Probe"
cp "$TASK_TMP/first.app/Contents/MacOS/Probe" "$TASK_TMP/wrong-identifier.app/Contents/MacOS/Probe"
bash "$TASK_ROOT/scripts/sign-app.sh" "$TASK_TMP/first.app"
bash "$TASK_ROOT/scripts/sign-app.sh" "$TASK_TMP/second.app"
codesign --force --sign - "$TASK_TMP/unrelated.app"
bash "$TASK_ROOT/scripts/sign-app.sh" "$TASK_TMP/wrong-identifier.app" cc.coauto.unrelated-app
"$TASK_TMP/first.app/Contents/MacOS/Probe" create "$TASK_SERVICE" "$TASK_TMP/bundled-helper" "$TASK_TMP/installed/v1"
ditto "$TASK_TMP/second.app" "$TASK_TMP/first.app"
"$TASK_TMP/first.app/Contents/MacOS/Probe" read "$TASK_SERVICE" "$TASK_TMP/bundled-helper" "$TASK_TMP/installed/v1"
"$TASK_TMP/unrelated.app/Contents/MacOS/Probe" denied "$TASK_SERVICE" "$TASK_TMP/bundled-helper" "$TASK_TMP/installed/v1"
"$TASK_TMP/wrong-identifier.app/Contents/MacOS/Probe" denied "$TASK_SERVICE" "$TASK_TMP/bundled-helper" "$TASK_TMP/installed/v1"
printf '%s\n' 'PASS rebuilt app retains Keychain access; unrelated ad-hoc caller cannot read it; no authorization UI'
