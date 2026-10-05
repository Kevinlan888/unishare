#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
archive=${1:-build-ios-xcframework-final/UniShareSDK.xcframework}
bundle=${2:-com.unishare.acceptance}
team=${3:-}
device=${4:-}
out=build-ios-acceptance-device
mkdir -p "$out/AcceptanceHost.xcodeproj"
cp ios/Tests/AcceptanceHost.xcodeproj/project.pbxproj "$out/AcceptanceHost.xcodeproj/"
cp ios/Tests/{AcceptanceApp,ClipboardAcceptance,PeerAcceptance}.swift "$out/"
ditto "$archive" "$out/UniShareSDK.xcframework"
cp ios/Tests/Info.plist "$out/Info.plist"
/usr/libexec/PlistBuddy -c 'Set :CFBundleIdentifier $(PRODUCT_BUNDLE_IDENTIFIER)' "$out/Info.plist"
/usr/libexec/PlistBuddy -c 'Set :CFBundleExecutable Acceptance' "$out/Info.plist"
/usr/libexec/PlistBuddy -c 'Add :UISupportedInterfaceOrientations array' "$out/Info.plist"
for orientation in UIInterfaceOrientationPortrait UIInterfaceOrientationPortraitUpsideDown UIInterfaceOrientationLandscapeLeft UIInterfaceOrientationLandscapeRight; do
 /usr/libexec/PlistBuddy -c "Add :UISupportedInterfaceOrientations: string $orientation" "$out/Info.plist"
done
# Xcode supplies real entitlements/profile. Never copy Simulator.entitlements.
if [[ -z $team ]]; then
 echo "Prepared $out/AcceptanceHost.xcodeproj. Open in Xcode, set bundle/team and select your connected iPhone."
 exit 0
fi
destination='generic/platform=iOS'
if [[ -n $device ]]; then destination="id=$device"; fi
xcodebuild -project "$out/AcceptanceHost.xcodeproj" -scheme Acceptance \
 -configuration Debug -destination "$destination" -derivedDataPath "$out/DerivedData" \
 -allowProvisioningUpdates -allowProvisioningDeviceRegistration \
 DEVELOPMENT_TEAM="$team" PRODUCT_BUNDLE_IDENTIFIER="$bundle" build
echo "App: $out/DerivedData/Build/Products/Debug-iphoneos/Acceptance.app"
