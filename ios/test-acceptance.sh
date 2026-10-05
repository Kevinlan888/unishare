#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
archive=${1:-build-ios-xcframework-final/UniShareSDK.xcframework}
uuid=${2:-071982EE-2278-42D1-AEEF-B1DDE4E53FF4}
mode=${3:-all}
case "$mode" in all|clipboard|network|permission) ;; *) echo "Unknown acceptance mode: $mode" >&2; exit 2 ;; esac
slice="$archive/ios-arm64-simulator"
out="build-ios-acceptance-$mode"; app="$out/Acceptance.app"
mkdir -p "$app/Frameworks"
cp -R "$slice/UniShareSDK.framework" "$app/Frameworks/"
xcrun --sdk iphonesimulator swiftc -swift-version 5 -parse-as-library \
 -target arm64-apple-ios15.0-simulator -sdk "$(xcrun --sdk iphonesimulator --show-sdk-path)" \
 -F "$slice" -framework UniShareSDK ios/Tests/{AcceptanceApp,ClipboardAcceptance,PeerAcceptance}.swift \
 -Xlinker -rpath -Xlinker @executable_path/Frameworks \
 -Xlinker -sectcreate -Xlinker __TEXT -Xlinker __entitlements -Xlinker ios/Tests/Simulator.entitlements \
 -o "$app/Acceptance"
cp ios/Tests/Info.plist "$app/Info.plist"
/usr/libexec/PlistBuddy -c 'Set :CFBundleExecutable Acceptance' "$app/Info.plist"
if [[ $mode == permission ]]; then /usr/libexec/PlistBuddy -c 'Delete :NSBonjourServices' "$app/Info.plist"; fi
codesign --force --sign - "$app/Frameworks/UniShareSDK.framework"
codesign --force --sign - "$app"
xcrun simctl install "$uuid" "$app"
run() {
 python3 - "$uuid" "$1" "$out/$1.log" <<'PY'
import pathlib, subprocess, sys
device, mode, log = sys.argv[1:]
try:
    result = subprocess.run(["xcrun", "simctl", "launch", "--console", device,
                             "com.unishare.ports", mode], stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=240)
except subprocess.TimeoutExpired as error:
    subprocess.run(["xcrun", "simctl", "terminate", device, "com.unishare.ports"])
    pathlib.Path(log).write_bytes(error.stdout or b"")
    sys.exit("FAIL simulator host timeout; log=" + log)
pathlib.Path(log).write_bytes(result.stdout)
print(result.stdout.decode(errors="replace"), end="", flush=True)
expected = ("IOS_ACCEPTANCE_RESULT mode=" + mode + " failures=0").encode()
if result.returncode or expected not in result.stdout:
    sys.exit("FAIL host exit/result marker; log=" + log)
PY
}
if [[ $mode == all || $mode == clipboard ]]; then run clipboard-write; run clipboard-read; fi
if [[ $mode == all || $mode == network ]]; then run network; run trust-read; fi
if [[ $mode == permission ]]; then run permission; fi
