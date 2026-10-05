param(
    [Parameter(Mandatory)][ValidateSet('sender','receiver')][string]$Role,
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9-]+$')][string]$Run,
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9-]+$')][string]$Peer,
    [string]$Serial = '62041JEA316847',
    [switch]$Build
)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path "$PSScriptRoot/../..").Path
$adb = "$taskRoot/build-deps/android-sdk/platform-tools/adb.exe"
$out = "$taskRoot/build-interop-$Run"
New-Item -ItemType Directory -Force $out | Out-Null
if ($Build) {
    & python "$PSScriptRoot/fixtures.py" "$PSScriptRoot/build-fixtures"
    if ($LASTEXITCODE) { throw 'Fixture generation failed' }
    $env:ANDROID_HOME = "$taskRoot/build-deps/android-sdk"
    $env:GRADLE_USER_HOME = "$taskRoot/build-deps/gradle-cache"
    & "$taskRoot/build-deps/gradle/gradle-9.4.1/bin/gradle.bat" -p "$PSScriptRoot/android" assembleDebug "-PconsumedAar=$taskRoot/android/share-sdk/build/outputs/aar/share-sdk-release.aar" "-Pandroid.aapt2FromMavenOverride=$env:ANDROID_HOME/build-tools/36.0.0/aapt2.exe" --offline --console=plain
    if ($LASTEXITCODE) { throw 'Public AAR host build failed' }
    & $adb -s $Serial install -r "$PSScriptRoot/android/build/outputs/apk/debug/interop-debug.apk"
    if ($LASTEXITCODE) { throw 'Host install failed' }
}
# A fresh run isolates SDK identity and logs. This alters only the test app.
$interopArguments = @('-s',$Serial,'shell','am','instrument','-w','-r','-e','role',$Role,'-e','run',$Run,'-e','peer',$Peer,'-e','testConsent','true','com.unishare.interop/.InteropHost')
$process = Start-Process -FilePath $adb -ArgumentList $interopArguments -WindowStyle Hidden -PassThru -RedirectStandardOutput "$out/android-console.log" -RedirectStandardError "$out/android-error.log"
$hostFailure = $null
try {
    if (!$process.WaitForExit(240000)) {
        & $adb -s $Serial shell am force-stop com.unishare.interop
        $process.Kill()
        throw 'Android host exceeded 240 seconds'
    }
    $console = Get-Content -Raw "$out/android-console.log"
    $console | Write-Output
    if ($process.ExitCode -ne 0 -or !$console.Contains('INTEROP_PASS') -or !$console.Contains('INSTRUMENTATION_CODE: -1')) { throw 'Android host failed' }
} catch {
    $hostFailure = $_
}
try {
    $records = & $adb -s $Serial shell run-as com.unishare.interop cat "files/$Run-$Role.jsonl" 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Android evidence retrieval failed (adb exit $LASTEXITCODE): $records" }
    $records | Set-Content -Encoding utf8 "$out/android.jsonl"
} catch {
    if ($null -eq $hostFailure) { throw }
    Write-Warning "Evidence retrieval also failed: $_"
}
if ($null -ne $hostFailure) { throw $hostFailure }
