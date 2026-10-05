#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../../.."
archive=$1; uuid=$2; role=$3; run=$4; peer=$5
[[ $role == sender || $role == receiver ]] && [[ $run =~ ^[A-Za-z0-9-]+$ ]] || exit 2
out="build-interop-ios-$run"; app="$out/Interop.app"; slice="$archive/ios-arm64-simulator"
mkdir -p "$app/Frameworks"
python3 tests/interop/fixtures.py "$app/fixtures"
cp -R "$slice/UniShareSDK.framework" "$app/Frameworks/"
xcrun --sdk iphonesimulator swiftc -swift-version 5 -parse-as-library \
 -target arm64-apple-ios15.0-simulator -sdk "$(xcrun --sdk iphonesimulator --show-sdk-path)" \
 -F "$slice" -framework UniShareSDK tests/interop/ios/Interop.swift \
 -Xlinker -rpath -Xlinker @executable_path/Frameworks \
 -Xlinker -sectcreate -Xlinker __TEXT -Xlinker __entitlements -Xlinker ios/Tests/Simulator.entitlements \
 -o "$app/Interop"
cp ios/Tests/Info.plist "$app/Info.plist"
/usr/libexec/PlistBuddy -c 'Set :CFBundleExecutable Interop' "$app/Info.plist"
codesign --force --sign - "$app/Frameworks/UniShareSDK.framework"
codesign --force --sign - "$app"
xcrun simctl install "$uuid" "$app"
python3 - "$uuid" "$role" "$run" "$peer" "$out/$role.log" <<'PY'
import pathlib, subprocess, sys
uuid, role, run, peer, log = sys.argv[1:]
try:
    result = subprocess.run(["xcrun", "simctl", "launch", "--console", uuid,
        "com.unishare.ports", role, run, peer, "--test-consent"],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=240)
except subprocess.TimeoutExpired as error:
    subprocess.run(["xcrun", "simctl", "terminate", uuid, "com.unishare.ports"])
    pathlib.Path(log).write_bytes(error.stdout or b"")
    sys.exit("interop process timeout")
pathlib.Path(log).write_bytes(result.stdout)
print(result.stdout.decode(errors="replace"), end="", flush=True)
if result.returncode or b'"event":"complete"' not in result.stdout:
    sys.exit("interop host failed; inspect " + log)
PY
