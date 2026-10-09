[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$scriptPath = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../scripts/test-app.ps1'))
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('unishare verifier fixtures ' + [guid]::NewGuid().ToString('N'))
$suite = '<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase name="works" /></testsuite>'
$manifest = @'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.unishare.app">
<uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<application><service android:name="com.unishare.app.service.ShareForegroundService" android:exported="false" android:foregroundServiceType="connectedDevice" /></application>
</manifest>
'@
function Put-Fixture([string]$Relative, [string]$Value) {
    $path = Join-Path $fixtureRoot $Relative
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($path)) | Out-Null
    [IO.File]::WriteAllText($path, $Value)
}
function Case([string]$Name, [string]$Junit, [string]$ManifestXml, [int]$ExitCode) {
    @{ Name = $Name; Junit = $Junit; Manifest = $ManifestXml; ExitCode = $ExitCode }
}
$cases = @(
    (Case 'single suite success' $suite $manifest 0),
    (Case 'multiple suites success' ('<testsuites tests="2" failures="0" errors="0" skipped="0">' + $suite + $suite + '</testsuites>') $manifest 0),
    (Case 'namespace success' ($suite.Replace('<testsuite ', '<testsuite xmlns="urn:junit" ')) $manifest 0),
    (Case 'fabricated positive count' '<testsuite tests="1" failures="0" errors="0" skipped="0" />' $manifest 1),
    (Case 'suite testcase mismatch' ($suite.Replace('tests="1"', 'tests="2"')) $manifest 1),
    (Case 'root aggregate mismatch' ('<testsuites tests="2" failures="0" errors="0" skipped="0">' + $suite + '</testsuites>') $manifest 1),
    (Case 'failure node with false zero' ($suite.Replace('<testcase name="works" />', '<testcase name="works"><failure /></testcase>')) $manifest 1),
    (Case 'error node with false zero' ($suite.Replace('<testcase name="works" />', '<testcase name="works"><error /></testcase>')) $manifest 1),
    (Case 'skipped node with false zero' ($suite.Replace('<testcase name="works" />', '<testcase name="works"><skipped /></testcase>')) $manifest 1),
    (Case 'missing suite count' ($suite.Replace(' failures="0"', '')) $manifest 1),
    (Case 'missing root count' ('<testsuites tests="1" errors="0" skipped="0">' + $suite + '</testsuites>') $manifest 1),
    (Case 'invalid numeric count' ($suite.Replace('tests="1"', 'tests="bogus"')) $manifest 1),
    (Case 'zero test report' '<testsuite tests="0" failures="0" errors="0" skipped="0" />' $manifest 1),
    (Case 'malformed XML' '<testsuite' $manifest 1),
    (Case 'empty XML' '' $manifest 1),
    (Case 'extra testcase outside suite' ('<testsuites tests="2" failures="0" errors="0" skipped="0">' + $suite + '<testcase /></testsuites>') $manifest 1),
    (Case 'relative component success' $suite ($manifest.Replace('com.unishare.app.service.ShareForegroundService', '.service.ShareForegroundService')) 0),
    (Case 'combined dataSync FGS prohibited' $suite ($manifest.Replace('foregroundServiceType="connectedDevice"', 'foregroundServiceType="dataSync|connectedDevice"')) 1),
    (Case 'merged broad storage' $suite ($manifest.Replace('<application>', '<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" /><application>')) 1),
    (Case 'merged read storage' $suite ($manifest.Replace('<application>', '<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" /><application>')) 1),
    (Case 'merged sdk23 write storage' $suite ($manifest.Replace('<application>', '<uses-permission-sdk-23 android:name="android.permission.WRITE_EXTERNAL_STORAGE" /><application>')) 1),
    (Case 'merged local network target mismatch' $suite ($manifest.Replace('<application>', '<uses-permission android:name="android.permission.ACCESS_LOCAL_NETWORK" /><application>')) 1),
    (Case 'wrong service component' $suite ($manifest.Replace('com.unishare.app.service.ShareForegroundService', 'com.unishare.app.service.OtherService')) 1),
    (Case 'missing service' $suite ($manifest -replace '<service[^>]*/>', '') 1),
    (Case 'exported service' $suite ($manifest.Replace('exported="false"', 'exported="true"')) 1),
    (Case 'missing exported attribute' $suite ($manifest.Replace(' android:exported="false"', '')) 1),
    (Case 'wrong FGS type' $suite ($manifest.Replace('foregroundServiceType="connectedDevice"', 'foregroundServiceType="dataSync"')) 1)
)
$failed = 0
try {
    # Synthetic presence fixtures isolate the validator. No real outputs are changed.
    Put-Fixture 'app/build/outputs/apk/debug/app-debug.apk' 'fixture APK presence'
    Put-Fixture 'share-sdk/build/outputs/aar/share-sdk-debug.aar' 'fixture AAR presence'
    Put-Fixture 'app/build/reports/tests/testDebugUnitTest/index.html' '<html>fixture</html>'
    Put-Fixture 'app/build/reports/lint-results-debug.xml' '<issues />'
    Put-Fixture 'app/build/reports/lint-results-debug.html' '<html>fixture</html>'
    foreach ($case in $cases) {
        Put-Fixture 'app/build/test-results/testDebugUnitTest/TEST-fixture.xml' $case.Junit
        Put-Fixture 'app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml' $case.Manifest
        $start = New-Object Diagnostics.ProcessStartInfo
        $start.FileName = 'powershell.exe'
        $start.Arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$scriptPath`" -VerifyOnly -OutputRoot `"$fixtureRoot`""
        $start.UseShellExecute = $false
        $start.CreateNoWindow = $true
        $start.RedirectStandardOutput = $true
        $start.RedirectStandardError = $true
        $process = [Diagnostics.Process]::Start($start)
        try {
            $output = $process.StandardOutput.ReadToEnd() + $process.StandardError.ReadToEnd()
            $process.WaitForExit()
            if ($process.ExitCode -ne $case.ExitCode) {
                $failed++
                Write-Output "FAIL $($case.Name): expected exit $($case.ExitCode), got $($process.ExitCode)"
                Write-Output $output
            } else { Write-Output "PASS $($case.Name): exit $($process.ExitCode)" }
        } finally { $process.Dispose() }
    }
    Write-Output "Fixture checks: $($cases.Count) cases, $failed failures; root=$fixtureRoot"
    if ($failed -ne 0) { exit 1 }
    exit 0
} finally {
    $resolved = [IO.Path]::GetFullPath($fixtureRoot)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if (!$resolved.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -or (Split-Path $resolved -Leaf) -notlike 'unishare verifier fixtures *') { throw 'Unsafe fixture cleanup path' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
