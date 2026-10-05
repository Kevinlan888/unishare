#!/bin/bash
# Public XCFramework consumer, signed using the host's Personal Team.
set -euo pipefail
cd "$(dirname "$0")/../../.."
archive=$1; xcode_device=$2; core_device=$3; role=$4; run=$5; peer=$6
[[ $role == sender || $role == receiver ]] && [[ $run =~ ^[A-Za-z0-9-]+$ ]] || exit 2
: "${DEVELOPMENT_TEAM:?Set the locally configured signing team; never commit it}"
out="build-interop-ios-device-$run"; project="$out/project"
[[ ! -e $project ]] || { echo 'Use a fresh physical run name' >&2; exit 2; }
mkdir -p "$project/Interop.xcodeproj"
cp tests/interop/ios/Interop.swift "$project/Interop.swift"
cp -R "$archive" "$project/UniShareSDK.xcframework"
python3 tests/interop/fixtures.py "$project/fixtures"
cp ios/Tests/Info.plist "$project/Info.plist"
/usr/libexec/PlistBuddy -c 'Set :CFBundleIdentifier com.unishare.acceptance' "$project/Info.plist"
/usr/libexec/PlistBuddy -c 'Set :CFBundleExecutable Interop' "$project/Info.plist"
/usr/libexec/PlistBuddy -c 'Set :CFBundleName UniShare Interop' "$project/Info.plist"
python3 - "$project" <<'PY'
import pathlib,sys
p=pathlib.Path(sys.argv[1])
s=pathlib.Path('ios/Tests/AcceptanceHost.xcodeproj/project.pbxproj').read_text()
s=s.replace('path = AcceptanceApp.swift','path = Interop.swift')
s=s.replace('files = (A10000000000000000000001, A10000000000000000000002, A10000000000000000000003);','files = (A10000000000000000000001);')
s=s.replace('name = Acceptance; productName = Acceptance;','name = Interop; productName = Interop;')
s=s.replace('path = Acceptance.app','path = Interop.app')
s=s.replace('buildPhases = (','buildPhases = (A40000000000000000000004, ')
s=s.replace('objects = {','objects = {\n A20000000000000000000006 = {isa = PBXFileReference; lastKnownFileType = folder; path = fixtures; sourceTree = "<group>"; };\n A10000000000000000000006 = {isa = PBXBuildFile; fileRef = A20000000000000000000006; };\n A40000000000000000000004 = {isa = PBXResourcesBuildPhase; buildActionMask = 2147483647; files = (A10000000000000000000006); runOnlyForDeploymentPostprocessing = 0; };')
s=s.replace('children = (','children = (A20000000000000000000006, ')
(p/'Interop.xcodeproj/project.pbxproj').write_text(s)
PY
# Signing settings and generated project/output stay under ignored build paths.
xcodebuild -project "$project/Interop.xcodeproj" -scheme Interop -configuration Debug \
 -destination "id=$xcode_device" -derivedDataPath "$out/derived" -allowProvisioningUpdates \
 "DEVELOPMENT_TEAM=$DEVELOPMENT_TEAM" build >"$out/build.log" 2>&1
app="$out/derived/Build/Products/Debug-iphoneos/Interop.app"
xcrun devicectl device install app --device "$core_device" "$app" >"$out/install.log" 2>&1
[[ ${MODE:-run} == build ]] && exit 0
python3 - "$core_device" "$role" "$run" "$peer" "$out/$role.log" <<'PY'
import pathlib, subprocess, sys
device,role,run,peer,log=sys.argv[1:]
command=['xcrun','devicectl','device','process','launch','--device',device,'--terminate-existing','--console','com.unishare.acceptance',role,run,peer,'--test-consent']
try: result=subprocess.run(command,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=240)
except subprocess.TimeoutExpired as failure:
    pathlib.Path(log).write_bytes(failure.stdout or b'')
    sys.exit('physical interop timeout; inspect '+log)
pathlib.Path(log).write_bytes(result.stdout)
print(result.stdout.decode(errors='replace'),end='',flush=True)
if result.returncode or b'"event":"complete"' not in result.stdout: sys.exit('physical host failed; inspect '+log)
PY
