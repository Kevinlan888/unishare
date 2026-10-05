#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build-ios-swift-tests
xcrun swiftc ios/Sources/ShareSdk/*.swift ios/Tests/ShareSdkTests.swift ios/Tests/main.swift -o build-ios-swift-tests/contracts
build-ios-swift-tests/contracts
