[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$androidRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
try {
    & (Join-Path $androidRoot 'gradlew.bat') -p $androidRoot :app:assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Debug APK build failed ($LASTEXITCODE)" }
    $apkPath = Join-Path $androidRoot 'app/build/outputs/apk/debug/app-debug.apk'
    if (!(Test-Path -LiteralPath $apkPath -PathType Leaf)) { throw "Missing debug APK: $apkPath" }
    $apk = Get-Item -LiteralPath $apkPath
    if ($apk.Length -eq 0) { throw 'Debug APK is empty' }
    $hash = (Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash
    Write-Output "APK: $($apk.FullName)"
    Write-Output "SHA-256: $hash"
    Write-Output "Size: $($apk.Length) bytes"
    exit 0
} catch {
    [Console]::Error.WriteLine("FAIL: $($_.Exception.Message)")
    exit 1
}
