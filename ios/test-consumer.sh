#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
archive=${1:-build-ios-xcframework/UniShareSDK.xcframework}
uuid=${2:-071982EE-2278-42D1-AEEF-B1DDE4E53FF4}
mode=${3:-normal}
# Consumer compiler sees only the delivered XCFramework slice, no source/module map.
slice="$archive/ios-arm64-simulator"
out="build-ios-consumer-$mode"; app="$out/SwiftConsumer.app"
mkdir -p "$app/Frameworks"
cp -R "$slice/UniShareSDK.framework" "$app/Frameworks/"
sysroot=$(xcrun --sdk iphonesimulator --show-sdk-path)
xcrun --sdk iphonesimulator swiftc -swift-version 5 -parse-as-library -target arm64-apple-ios15.0-simulator -sdk "$sysroot" \
 -F "$slice" -framework UniShareSDK ios/Tests/ConsumerApp.swift \
 -Xlinker -rpath -Xlinker @executable_path/Frameworks \
 -Xlinker -sectcreate -Xlinker __TEXT -Xlinker __entitlements -Xlinker ios/Tests/Simulator.entitlements \
 -o "$app/SwiftConsumer"
cp ios/Tests/Info.plist "$app/Info.plist"
/usr/libexec/PlistBuddy -c 'Set :CFBundleExecutable SwiftConsumer' "$app/Info.plist"
if [[ $mode == permission ]]; then /usr/libexec/PlistBuddy -c 'Delete :NSBonjourServices' "$app/Info.plist"; fi
codesign --force --sign - "$app/Frameworks/UniShareSDK.framework"
codesign --force --sign - "$app"
xcrun simctl install "$uuid" "$app"
if [[ $mode == permission ]]; then
 xcrun simctl launch --console "$uuid" com.unishare.ports --permission
else
 xcrun simctl launch --console "$uuid" com.unishare.ports
fi
