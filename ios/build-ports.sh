#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
sdk=${1:-iphonesimulator}
prefix=${2:-/tmp/openssl-ios-sim}
out="build-ios-$sdk"
mkdir -p "$out/obj"
sysroot=$(xcrun --sdk "$sdk" --show-sdk-path)
target=arm64-apple-ios15.0-simulator
if [[ "$sdk" == iphoneos ]]; then target=arm64-apple-ios15.0; fi
flags=(-target "$target" -isysroot "$sysroot" -std=c++17 -Iinclude -Isrc -Iios/Sources/ShareSdkBridge -I"$prefix/include" -g -O0)
objects=()
for source in src/core/*.cpp src/protocol/*.cpp src/c_api*.cpp ios/Sources/ShareSdkBridge/*.mm; do
 object="$out/obj/$(basename "${source%.*}").o"
 if [[ "$source" == *.mm ]]; then
  xcrun --sdk "$sdk" clang++ "${flags[@]}" -fobjc-arc -fblocks -c "$source" -o "$object"
 else
  xcrun --sdk "$sdk" clang++ "${flags[@]}" -c "$source" -o "$object"
 fi
 objects+=("$object")
done
xcrun libtool -static -o "$out/libunishare_ios.a" "${objects[@]}"
mkdir -p "$out/PortsTest.app"
if [[ "$sdk" == iphonesimulator ]]; then
 # Simulator Security reads the simulator entitlement section, as in Xcode's
 # simulator link. Do not add device-only restricted entitlements to the host
 # macOS ad-hoc code signature.
 xcrun --sdk "$sdk" clang++ "${flags[@]}" -fobjc-arc -fblocks ios/Tests/*.mm "${objects[@]}" "$prefix/lib/libssl.a" "$prefix/lib/libcrypto.a" -framework Foundation -framework UIKit -framework Security -framework Network -framework ImageIO -Wl,-sectcreate,__TEXT,__entitlements,ios/Tests/Simulator.entitlements -o "$out/PortsTest.app/PortsTest"
else
 xcrun --sdk "$sdk" clang++ "${flags[@]}" -fobjc-arc -fblocks ios/Tests/*.mm "${objects[@]}" "$prefix/lib/libssl.a" "$prefix/lib/libcrypto.a" -framework Foundation -framework UIKit -framework Security -framework Network -framework ImageIO -o "$out/PortsTest.app/PortsTest"
fi
cp ios/Tests/Info.plist "$out/PortsTest.app/Info.plist"
if [[ "$sdk" == iphonesimulator ]]; then
 codesign --force --sign - "$out/PortsTest.app"
fi
