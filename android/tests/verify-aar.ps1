param([string]$Aar, [string]$ReadElf)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (!$Aar) { $Aar = Join-Path $taskRoot 'android/share-sdk/build/outputs/aar/share-sdk-release.aar' }
if (!$ReadElf) { $ReadElf = Join-Path $taskRoot 'build-deps/android-sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$output = Join-Path $taskRoot 'build-aar-verification'
New-Item -ItemType Directory -Force $output | Out-Null
$archive = [IO.Compression.ZipFile]::OpenRead($Aar)
try {
    $native = @($archive.Entries | Where-Object { $_.FullName -like 'jni/*' -and $_.Name })
    $expected = @('jni/arm64-v8a/libunishare_android.so', 'jni/armeabi-v7a/libunishare_android.so', 'jni/x86_64/libunishare_android.so')
    if (@(Compare-Object ($native.FullName | Sort-Object) ($expected | Sort-Object)).Count) { throw 'Unexpected release native entries/probe' }
    $machines = @{ 'arm64-v8a'='AArch64'; 'armeabi-v7a'='ARM'; 'x86_64'='Advanced Micro Devices X86-64' }
    foreach ($entry in $native) {
        $abi = $entry.FullName.Split('/')[1]
        $elf = Join-Path $output "$abi.so"
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $elf, $true)
        $header = & $ReadElf --file-header $elf
        if ($LASTEXITCODE -or !(($header -join "`n") -match "Machine:\s+$([regex]::Escape($machines[$abi]))")) { throw "Wrong ELF architecture: $abi" }
        $dynamic = & $ReadElf --dynamic $elf
        $needed = @($dynamic | Select-String '\(NEEDED\).*\[(.*?)\]' | ForEach-Object { $_.Matches[0].Groups[1].Value })
        foreach ($dependency in $needed) { if ($dependency -notin @('libandroid.so','liblog.so','libm.so','libdl.so','libc.so')) { throw "Unexpected dependency: $abi $dependency" } }
        $symbols = (& $ReadElf --dyn-syms --wide $elf) -join "`n"
        foreach ($method in @('create','submit','snapshot','state','destroy')) {
            if (!$symbols.Contains("Java_com_unishare_sdk_internal_SdkNative_$method")) { throw "Missing JNI entrypoint $method" }
        }
        if (!$symbols.Contains('Java_com_unishare_sdk_internal_NativeBridge_abortStream')) { throw 'Missing stream cleanup entrypoint' }
        Write-Output "PASS $abi ELF $($machines[$abi]); NEEDED $($needed -join ', ')"
    }
    $classes = Join-Path $output 'classes.jar'
    [IO.Compression.ZipFileExtensions]::ExtractToFile($archive.GetEntry('classes.jar'), $classes, $true)
    $jar = [IO.Compression.ZipFile]::OpenRead($classes)
    try { foreach ($type in @('ShareSdk','DeviceDiscovery','ConnectionManager','TransferService','ClipboardService','Operation','DeviceInfo')) {
        if (!$jar.GetEntry("com/unishare/sdk/$type.class")) { throw "Missing public class $type" }
    } } finally { $jar.Dispose() }
    if (!$archive.GetEntry('proguard.txt')) { throw 'Missing consumer rules' }
    Write-Output 'PASS release AAR: three native ABIs, public Kotlin classes and consumer rules; no probe'
} finally { $archive.Dispose() }
