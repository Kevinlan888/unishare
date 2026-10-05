param([int]$TargetSdk = 36, [string]$Serial = '127.0.0.1:16384', [switch]$CompileOnly)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$env:ANDROID_HOME = Join-Path $taskRoot 'build-deps/android-sdk'
$env:GRADLE_USER_HOME = Join-Path $taskRoot 'build-deps/gradle-cache'
$gradle = Join-Path $taskRoot 'build-deps/gradle/gradle-9.4.1/bin/gradle.bat'
$aar = Join-Path $taskRoot 'android/share-sdk/build/outputs/aar/share-sdk-release.aar'
$consumer = Join-Path $PSScriptRoot 'consumer'
& $gradle -p $consumer compileDebugKotlin assembleRelease "-PconsumedAar=$aar" "-PconsumerTarget=$TargetSdk" "-Pandroid.aapt2FromMavenOverride=$env:ANDROID_HOME/build-tools/36.0.0/aapt2.exe" --offline --console=plain
if ($LASTEXITCODE) { throw 'AAR consumer compile/shrink failed' }
$manifest = Join-Path $consumer 'build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml'
$merged = [xml](Get-Content -Raw $manifest)
$ns = New-Object Xml.XmlNamespaceManager($merged.NameTable)
$ns.AddNamespace('android', 'http://schemas.android.com/apk/res/android')
$declared = $merged.SelectNodes('/manifest/uses-permission[@android:name="android.permission.ACCESS_LOCAL_NETWORK"]', $ns).Count
if (($TargetSdk -ge 37) -ne ($declared -eq 1)) { throw 'Local network permission merge does not match host target' }
if ($merged.manifest.'uses-sdk'.GetAttribute('targetSdkVersion', 'http://schemas.android.com/apk/res/android') -ne "$TargetSdk") { throw 'Wrong consumer target SDK' }
Write-Output "PASS target $TargetSdk manifest merge; ACCESS_LOCAL_NETWORK count $declared"
if (!$CompileOnly) {
    $adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
    & $adb -s $Serial install -r (Join-Path $consumer 'build/outputs/apk/release/unishare-aar-consumer-release.apk')
    if ($LASTEXITCODE) { throw 'Consumer APK installation failed' }
    $result = & $adb -s $Serial shell am instrument -w -r com.unishare.consumer/com.unishare.consumer.ConsumerSmoke
    $result | Write-Output
    if (!(($result -join "`n").Contains('PASS minified release AAR consumer JNI lifecycle'))) { throw 'Minified release AAR consumer failed' }
}
