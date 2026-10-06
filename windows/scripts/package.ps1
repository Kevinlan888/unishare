[CmdletBinding()]
param([ValidateSet('Release')][string]$Configuration='Release', [ValidateSet('x64')][string]$Platform='x64',
      [switch]$Unsigned, [string]$CertificatePath, [string]$CertificatePassword)
. (Join-Path $PSScriptRoot 'tools.ps1')
if (!$Unsigned -and (!$CertificatePath -or !(Test-Path -LiteralPath $CertificatePath))) { throw 'Distribution requires an external trusted signing certificate; use -Unsigned for CI.' }
if ($Unsigned -and $CertificatePath) { throw 'Unsigned CI mode must not use a signing certificate.' }
$native = Join-Path $WindowsRoot 'artifacts\native\bin'
foreach ($pattern in @('unishare.dll','libcrypto-3*.dll','libssl-3*.dll')) {
    $dlls = @(Get-ChildItem -LiteralPath $native -Filter $pattern -File)
    if ($dlls.Count -ne 1) { throw "Missing or ambiguous staged runtime: $pattern. Run build-native.ps1 first." }
    Assert-X64Pe $dlls[0].FullName
}
$makeappx = Get-SdkTool 'makeappx'
$makepri = Get-SdkTool 'makepri'
$packageDirectory = Join-Path $WindowsRoot ('artifacts\packages\' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $packageDirectory -Force | Out-Null
$arguments = @('build',(Join-Path $WindowsRoot 'src\UniShare.App\UniShare.App.csproj'),'-c',$Configuration,'-p:Platform=x64',
    '-p:PublishProfile=win10-x64','-p:GenerateAppxPackageOnBuild=true','-p:WindowsPackageType=MSIX',
    '-p:DebugType=none','-p:DebugSymbols=false','-p:AppxSymbolPackageEnabled=false',
    '-p:AppxBundle=Never','-p:AppxPackageSigningEnabled=false',"-p:AppxPackageDir=$packageDirectory\")
Push-Location $WindowsRoot
try { Invoke-Checked 'dotnet' $arguments } finally { Pop-Location }
$packages = @(Get-ChildItem -LiteralPath $packageDirectory -Filter '*.msix' -File -Recurse)
if ($packages.Count -ne 1) { throw "Expected exactly one x64 MSIX, found $($packages.Count)." }
$package = $packages[0].FullName
$unpacked = Join-Path $packageDirectory 'inspection'
Invoke-Checked $makeappx @('unpack','/p',$package,'/d',$unpacked,'/o')
if ($Unsigned) {
    Invoke-Checked 'powershell.exe' @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'set-unsigned-package-identity.ps1'),'-ManifestPath',(Join-Path $unpacked 'AppxManifest.xml'))
}
# Repack without /nv exercises MakeAppx manifest/schema and semantic validation.
$validated = Join-Path $packageDirectory 'validated.msix'
Invoke-Checked $makeappx @('pack','/d',$unpacked,'/p',$validated,'/o')
if ($Unsigned) {
    Move-Item -LiteralPath $validated -Destination $package -Force
} else {
    Remove-Item -LiteralPath $validated
}
$dump = Join-Path $packageDirectory 'resources.xml'
Invoke-Checked $makepri @('dump','/if',(Join-Path $unpacked 'resources.pri'),'/of',$dump,'/dt','detailed','/o')
$inspectionArguments = @{ PackageRoot=$unpacked; NativeRoot=$native; ResourceDump=$dump; Unsigned=[bool]$Unsigned }
$contentList = & (Join-Path $PSScriptRoot 'inspect-package.ps1') @inspectionArguments
$contentList | Set-Content -LiteralPath (Join-Path $packageDirectory 'contents.txt')
if (!$?) { throw 'Package content inspection failed.' }
if (!$Unsigned) {
    $signtool = Get-SdkTool 'signtool'
    $signArguments = @('sign','/fd','SHA256','/f',$CertificatePath)
    if ($CertificatePassword) { $signArguments += @('/p',$CertificatePassword) }
    Invoke-Checked $signtool ($signArguments + @($package))
    Invoke-Checked $signtool @('verify','/pa',$package)
}
Write-Host "SHA256 $(Get-Sha256 $package)"
Write-Host "PASS Release x64 MSIX: $package"
