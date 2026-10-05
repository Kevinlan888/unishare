#!/bin/bash
# Reproducible framework project: xcrun compilers + Apple XCFramework packager.
set -euo pipefail
cd "$(dirname "$0")/.."
output=${OUTPUT_DIR:-build-ios-xcframework}
device_ssl=${OPENSSL_IOS_DEVICE:-/tmp/openssl-ios-device}
sim_ssl=${OPENSSL_IOS_SIM:-/tmp/openssl-ios-sim}
mkdir -p "$output"
for sdk in iphoneos iphonesimulator; do
 target=arm64-apple-ios15.0; triple=arm64-apple-ios; ssl=$device_ssl; platform=iPhoneOS
 if [[ $sdk == iphonesimulator ]]; then target=arm64-apple-ios15.0-simulator; triple=arm64-apple-ios-simulator; ssl=$sim_ssl; platform=iPhoneSimulator; fi
 root="$output/$sdk"; framework="$root/UniShareSDK.framework"
 mkdir -p "$root/obj" "$framework/Headers" "$framework/Modules/UniShareSDK.swiftmodule" "$root/bridge/ShareSdkBridge"
 cp ios/Sources/ShareSdkBridge/include/USBridge.h "$root/bridge/ShareSdkBridge/USBridge.h"
 cat > "$root/bridge/ShareSdkBridge/module.modulemap" <<'MODULE'
module ShareSdkBridge { header "USBridge.h" export * }
MODULE
 sysroot=$(xcrun --sdk "$sdk" --show-sdk-path)
 flags=(-target "$target" -isysroot "$sysroot" -std=c++17 -Iinclude -Isrc -Iios/Sources/ShareSdkBridge -I"$ssl/include" -O2 -fvisibility=hidden)
 objects=()
 for source in src/core/*.cpp src/protocol/*.cpp src/c_api*.cpp ios/Sources/ShareSdkBridge/*.mm; do
  object="$root/obj/$(basename "${source%.*}").o"
  if [[ $source == *.mm ]]; then
   xcrun --sdk "$sdk" clang++ "${flags[@]}" -fobjc-arc -fblocks -c "$source" -o "$object"
  else
   xcrun --sdk "$sdk" clang++ "${flags[@]}" -c "$source" -o "$object"
  fi
  objects+=("$object")
 done
 xcrun --sdk "$sdk" swiftc -swift-version 5 -target "$target" -sdk "$sysroot" -I "$root/bridge" \
  -module-name UniShareSDK -emit-library -emit-module -enable-library-evolution -O \
  -emit-module-path "$framework/Modules/UniShareSDK.swiftmodule/$triple.swiftmodule" \
  -emit-module-interface-path "$framework/Modules/UniShareSDK.swiftmodule/$triple.swiftinterface" \
  -emit-objc-header-path "$framework/Headers/UniShareSDK-Swift.h" \
  ios/Sources/ShareSdk/*.swift "${objects[@]}" "$ssl/lib/libssl.a" "$ssl/lib/libcrypto.a" \
  -Xlinker -install_name -Xlinker @rpath/UniShareSDK.framework/UniShareSDK \
  -Xlinker -dead_strip -lc++ -framework Foundation -framework UIKit -framework Security -framework Network -framework ImageIO \
  -o "$framework/UniShareSDK"
 cp ios/Sources/ShareSdkBridge/include/USBridge.h "$framework/Headers/USBridge.h"
 # Ship the narrow Objective-C header/module with the framework. Swift uses an
 # implementation-only import, so its public interface has no bridge dependency.
 cat > "$framework/Modules/module.modulemap" <<'MODULE'
framework module UniShareSDK {
 umbrella header "UniShareSDK-Swift.h"
 export *
 module * { export * }
}
module ShareSdkBridge { header "../Headers/USBridge.h" export * }
MODULE
 cat > "$framework/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleIdentifier</key><string>com.unishare.UniShareSDK</string>
<key>CFBundleName</key><string>UniShareSDK</string>
<key>CFBundleExecutable</key><string>UniShareSDK</string>
<key>CFBundlePackageType</key><string>FMWK</string>
<key>CFBundleVersion</key><string>1</string>
<key>CFBundleShortVersionString</key><string>1.0</string>
<key>MinimumOSVersion</key><string>15.0</string>
<key>CFBundleSupportedPlatforms</key><array><string>$platform</string></array>
</dict></plist>
PLIST
 codesign --force --sign - "$framework"
done
# xcodebuild requires a new destination. Avoid deleting a caller-supplied path.
archive="$output/UniShareSDK.xcframework"
if [[ -e $archive ]]; then echo "Output already exists: $archive; choose a fresh OUTPUT_DIR" >&2; exit 1; fi
xcodebuild -create-xcframework -framework "$output/iphoneos/UniShareSDK.framework" -framework "$output/iphonesimulator/UniShareSDK.framework" -output "$archive"
echo "XCFramework: $archive"
