[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$PackageRoot,
      [Parameter(Mandatory=$true)][string]$NativeRoot,
      [Parameter(Mandatory=$true)][string]$ResourceDump,
      [switch]$Unsigned)
. (Join-Path $PSScriptRoot 'tools.ps1')
$files = @(Get-ChildItem -LiteralPath $PackageRoot -File -Recurse)
foreach ($file in $files) {
    $relative = $file.FullName.Substring($PackageRoot.TrimEnd('\').Length + 1)
    if ($relative -match '(?i)(^|[\\/])(tests?|src|\.git|identity|identities|TestResults)([\\/]|$)|\.Tests\.|\.(pdb|cs|cpp|c|h|slnx|csproj|resw|xaml|md|ps1|pubxml|props|targets|appxmanifest|sqlite|sqlite3|db|pfx|pem|key|log)$|(^|[\\/])(settings|identity|trust)\.json$') {
        throw "Forbidden package payload: $relative"
    }
    if ($file.Extension -in @('.json','.xml','.txt') -and (Get-Content -LiteralPath $file.FullName -Raw) -match '(?i)(?<![a-z0-9])[a-z]:[\\/]|\\\\[^\\\s]+\\') {
        throw "Absolute filesystem path in package metadata: $relative"
    }
    Write-Output $relative
}
foreach ($required in @('UniShare.App.exe','unishare.dll','NLog.dll','resources.pri','Assets\StoreLogo.png','AppxManifest.xml','licenses\OpenSSL-NOTICES.txt','licenses\NLog-LICENSE.txt')) {
    if (!(Test-Path -LiteralPath (Join-Path $PackageRoot $required))) { throw "Missing package payload: $required" }
}
foreach ($pattern in @('unishare.dll','libcrypto-3*.dll','libssl-3*.dll')) {
    $staged = @(Get-ChildItem -LiteralPath $NativeRoot -Filter $pattern -File)
    if ($staged.Count -ne 1) { throw "Expected one staged $pattern" }
    $target = Join-Path $PackageRoot $staged[0].Name
    if (!(Test-Path -LiteralPath $target)) { throw "Missing package runtime: $($staged[0].Name)" }
    if ((Get-Sha256 $target) -ne (Get-Sha256 $staged[0].FullName)) { throw "Native hash mismatch: $($staged[0].Name)" }
}
[xml]$manifest = Get-Content -LiteralPath (Join-Path $PackageRoot 'AppxManifest.xml')
if ($manifest.Package.Identity.ProcessorArchitecture -ne 'x64') { throw 'Package must target x64.' }
$unsignedMarker = 'OID.2.25.311729368913984317654407730594956997722=1'
$identity = $manifest.SelectSingleNode("/*[local-name()='Package']/*[local-name()='Identity']")
$publisher = if ($identity) { $identity.GetAttribute('Publisher') } else { '' }
if ([string]::IsNullOrWhiteSpace($publisher)) { throw 'Package Publisher is required.' }
if ($Unsigned) {
    $markerCount = [regex]::Matches($publisher, [regex]::Escape($unsignedMarker)).Count
    if ($markerCount -ne 1 -or !$publisher.EndsWith($unsignedMarker, [StringComparison]::Ordinal)) {
        throw 'Unsigned package Publisher must end with exactly one unsigned namespace marker.'
    }
} elseif ($publisher.IndexOf($unsignedMarker, [StringComparison]::Ordinal) -ge 0) {
    throw 'Signed package Publisher must not contain the unsigned namespace marker.'
}
$applications = @($manifest.Package.Applications.Application)
if ($applications.Count -ne 1 -or $applications[0].Executable -ne 'UniShare.App.exe') { throw 'Package must have exactly one application executable.' }
foreach ($declaration in $manifest.SelectNodes('//*[@Executable]')) {
    if ($declaration.Executable -ne 'UniShare.App.exe') { throw 'Executable declaration must reference UniShare.App.exe.' }
}
if ($manifest.Package.Dependencies.TargetDeviceFamily.MinVersion -ne '10.0.17763.0') { throw 'Incorrect Windows minimum version.' }
if ($manifest.OuterXml -match 'windows\.service|windows\.backgroundTasks') { throw 'Services/background tasks are forbidden.' }
$startup = $manifest.SelectNodes("//*[local-name()='StartupTask']")
if ($startup.Count -ne 1 -or $startup[0].Enabled -ne 'false') { throw 'Startup must default to off.' }
if ($manifest.OuterXml -notmatch 'windows.toastNotificationActivation') { throw 'Missing notification declaration.' }
$resources = New-Object System.Xml.XmlDocument
$resources.Load($ResourceDump)
foreach ($language in @('en-US','zh-CN')) {
    if (@($manifest.Package.Resources.Resource | Where-Object Language -eq $language).Count -eq 0) { throw "Missing manifest language: $language" }
    $candidates = $resources.SelectNodes("//NamedResource[@uri='ms-resource://UniShare/Resources/SendClipboard']/Candidate")
    if (@($candidates | Where-Object { @($_.QualifierSet.Qualifier | Where-Object { $_.name -eq 'Language' -and $_.value -eq $language }).Count -gt 0 -and $_.Value }).Count -eq 0) {
        throw "Missing compiled application language: $language"
    }
}
if ($Unsigned -and (Test-Path -LiteralPath (Join-Path $PackageRoot 'AppxSignature.p7x'))) { throw 'Unsigned CI package unexpectedly contains a signature.' }
Write-Host 'PASS package content, native hashes, process policy, and bilingual resources.'
