#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
prefix=${1:-/tmp/openssl-ios-sim}
simulator=${2:-booted}
out=build-ios-iphonesimulator
mkdir -p "$out/core-tests"
sysroot=$(xcrun --sdk iphonesimulator --show-sdk-path)
flags=(-target arm64-apple-ios15.0-simulator -isysroot "$sysroot" -Iinclude -Isrc -I"$prefix/include" -g -O0)
for source in tests/core/*_test.cpp tests/core/c_api_header_test.c; do
 name=$(basename "${source%.*}")
 if [[ "$source" == *.c ]]; then
  xcrun --sdk iphonesimulator clang "${flags[@]}" -std=c11 -c "$source" -o "$out/core-tests/$name.o"
 else
  xcrun --sdk iphonesimulator clang++ "${flags[@]}" -std=c++17 -c "$source" -o "$out/core-tests/$name.o"
 fi
 xcrun --sdk iphonesimulator clang++ "${flags[@]}" "$out/core-tests/$name.o" "$out/libunishare_ios.a" "$prefix/lib/libssl.a" "$prefix/lib/libcrypto.a" -framework Foundation -framework UIKit -framework Security -framework Network -framework ImageIO -o "$out/core-tests/$name"
 xcrun simctl spawn "$simulator" "$PWD/$out/core-tests/$name"
 echo "PASS $name"
done
