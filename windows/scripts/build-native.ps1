[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$OpenSslRoot,
      [ValidateSet('Release','RelWithDebInfo','MinSizeRel')][string]$Configuration = 'Release')
. (Join-Path $PSScriptRoot 'tools.ps1')
if (($OpenSslRoot -notmatch '^[A-Za-z]:[\\/]' -and $OpenSslRoot -notmatch '^\\\\[^\\/]+\\[^\\/]+(?:\\|$)') -or
    !(Test-Path -LiteralPath $OpenSslRoot -PathType Container)) {
    throw 'OpenSslRoot must be an existing absolute x64 OpenSSL 3 development prefix.'
}
$OpenSslRoot = (Resolve-Path -LiteralPath $OpenSslRoot).Path
foreach ($relative in @('include\openssl\ssl.h','include\openssl\opensslv.h','lib\libssl.lib','lib\libcrypto.lib')) {
    if (!(Test-Path -LiteralPath (Join-Path $OpenSslRoot $relative) -PathType Leaf)) { throw "Missing OpenSSL prerequisite: $relative" }
}
if ((Get-Content -LiteralPath (Join-Path $OpenSslRoot 'include\openssl\opensslv.h') -Raw) -notmatch 'OPENSSL_VERSION_MAJOR\s+3\b') {
    throw 'The development prefix must contain OpenSSL major version 3.'
}
$runtime = @()
foreach ($pattern in @('libcrypto-3*.dll','libssl-3*.dll')) {
    $matches = @(Get-ChildItem -LiteralPath (Join-Path $OpenSslRoot 'bin') -Filter $pattern -File)
    if ($matches.Count -ne 1) { throw "Expected exactly one matching OpenSSL runtime for $pattern in the supplied prefix." }
    Assert-X64Pe $matches[0].FullName
    $runtime += $matches[0]
}
$notice = Join-Path $OpenSslRoot 'share\openssl\copyright'
if (!(Test-Path -LiteralPath $notice)) { $notice = Join-Path $OpenSslRoot 'LICENSE.txt' }
if (!(Test-Path -LiteralPath $notice)) { throw 'OpenSSL license/notices must be present (share/openssl/copyright or LICENSE.txt).' }
$cmake = Get-CMake
$ctest = Join-Path (Split-Path $cmake) 'ctest.exe'
$build = Join-Path $WindowsRoot 'artifacts\native-build'
$stage = Join-Path $WindowsRoot 'artifacts\native'
$cmakeOpenSslRoot = $OpenSslRoot.Replace('\','/')
Invoke-Checked $cmake @('--fresh','-S',$RepoRoot,'-B',$build,'-G','Visual Studio 18 2026','-A','x64',
    '-DBUILD_TESTING=ON',"-DOPENSSL_ROOT_DIR=$cmakeOpenSslRoot", "-DOPENSSL_INCLUDE_DIR=$cmakeOpenSslRoot/include",
    "-DOPENSSL_SSL_LIBRARY=$cmakeOpenSslRoot/lib/libssl.lib", "-DOPENSSL_CRYPTO_LIBRARY=$cmakeOpenSslRoot/lib/libcrypto.lib",
    "-DCMAKE_INSTALL_PREFIX=$stage")
Invoke-Checked $cmake @('--build',$build,'--config',$Configuration,'--parallel')
$inventoryJson = & $ctest '--test-dir' $build '-C' $Configuration '--show-only=json-v1'
if ($LASTEXITCODE -ne 0) { throw 'Native test inventory failed.' }
$nativeInventory = Join-Path $build 'native-inventory.json'
$inventoryJson | Set-Content -LiteralPath $nativeInventory -Encoding UTF8
& (Join-Path $PSScriptRoot 'validate-native-tests.ps1') -Inventory $nativeInventory
if (!$?) { throw 'Native inventory validation failed.' }
$nativeReport = Join-Path $build 'native-results.xml'
Invoke-Checked $ctest @('--test-dir',$build,'-C',$Configuration,'--output-on-failure','--no-tests=error','--timeout','120','--output-junit',$nativeReport)
& (Join-Path $PSScriptRoot 'validate-native-tests.ps1') -Inventory $nativeInventory -Report $nativeReport
if (!$?) { throw 'Native report validation failed.' }
Invoke-Checked $cmake @('--install',$build,'--config',$Configuration)
foreach ($relative in @('bin\unishare.dll','lib\unishare.lib','include\unishare\c_api.h','include\unishare\windows.h')) {
    if (!(Test-Path -LiteralPath (Join-Path $stage $relative))) { throw "Missing native install artifact: $relative" }
}
Assert-X64Pe (Join-Path $stage 'bin\unishare.dll')
foreach ($dll in $runtime) {
    $installed = Join-Path $stage "bin\$($dll.Name)"
    if (!(Test-Path -LiteralPath $installed) -or (Get-Sha256 $installed) -ne (Get-Sha256 $dll.FullName)) {
        throw "Missing or mismatched installed OpenSSL runtime: $($dll.Name)"
    }
}
$noticeDirectory = Join-Path $stage 'licenses'
New-Item -ItemType Directory -Path $noticeDirectory -Force | Out-Null
Copy-Item -LiteralPath $notice -Destination (Join-Path $noticeDirectory 'OpenSSL-NOTICES.txt') -Force
Get-ChildItem -LiteralPath (Join-Path $stage 'bin') -Filter '*.dll' | ForEach-Object { Write-Host "SHA256 $(Get-Sha256 $_.FullName) $($_.Name)" }
Write-Host 'PASS native CTest and x64 runtime staging.'
