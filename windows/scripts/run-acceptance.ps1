[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$Output, [string]$Evidence,
      [string]$Package, [string]$Reason = 'Physical peers, an installed package, interactive operator, and Windows accessibility/display controls have not been verified in this environment.')
. (Join-Path $PSScriptRoot 'tools.ps1')
function Get-MsixHash([string]$Path) {
    if (!(Test-Path -LiteralPath $Path -PathType Leaf) -or [IO.Path]::GetExtension($Path) -ine '.msix') {
        throw 'Package must be an existing MSIX file.'
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead([IO.Path]::GetFullPath($Path))
    try {
        foreach ($footprint in @(
            @('AppxManifest.xml','Package','http://schemas.microsoft.com/appx/manifest/foundation/windows10'),
            @('AppxBlockMap.xml','BlockMap','http://schemas.microsoft.com/appx/2010/blockmap'),
            @('[Content_Types].xml','Types','http://schemas.openxmlformats.org/package/2006/content-types'))) {
            $entries = @($archive.Entries | Where-Object FullName -ceq $footprint[0])
            if ($entries.Count -ne 1) { throw "Missing or duplicate MSIX footprint: $($footprint[0])" }
            $stream = $entries[0].Open()
            try {
                $xml = New-Object System.Xml.XmlDocument
                $xml.XmlResolver = $null
                $xml.Load($stream)
                if ($xml.DocumentElement.LocalName -cne $footprint[1] -or $xml.DocumentElement.NamespaceURI -cne $footprint[2]) {
                    throw "Invalid MSIX footprint: $($footprint[0])"
                }
            } finally { $stream.Dispose() }
        }
    } finally { $archive.Dispose() }
    Get-Sha256 $Path
}
$outputPath = if ([IO.Path]::IsPathRooted($Output)) { [IO.Path]::GetFullPath($Output) } else { [IO.Path]::GetFullPath((Join-Path $RepoRoot $Output)) }
$scenarios = @('discovery-offline-rediscovery','pairing-match-mismatch-reject-timeout','trust-forget-restart','identity-change-block',
    'text-trusted-autoaccept','text-untrusted-decision','image-offer','file-integrity','clipboard-source-text','clipboard-source-image',
    'receiver-clipboard-unchanged','accept-reject','active-cancel','timeout','network-disconnect-reconnect',
    'collision-and-temp-cleanup','unwritable-receive-directory','hidden-window-notifications','notification-reject-view',
    'tray-close-pause-resume-exit-no-orphans','restart-summary-privacy','sqlite-log-privacy')
$matrix = @()
foreach ($peer in @('Windows','Android','iOS')) {
    foreach ($direction in @('outgoing','incoming')) {
        foreach ($scenario in $scenarios) {
            $matrix += [pscustomobject]@{ id="$peer/$direction/$scenario"; peer=$peer; scenario="$direction-$scenario" }
        }
    }
}
foreach ($locale in @('en-US','zh-CN')) {
    foreach ($theme in @('light','dark','high-contrast')) {
        foreach ($size in @('960x640','1440x900')) {
            foreach ($scale in @(100,125,150,175,200)) {
                $scenario = "ui-$locale-$theme-$size-$scale-keyboard-focus-screenreader-no-clipping"
                $matrix += [pscustomobject]@{ id="Local/$scenario"; peer='Local'; scenario=$scenario }
            }
        }
    }
}
$schema = Join-Path $PSScriptRoot 'acceptance.schema.json'
$validator = Join-Path $PSScriptRoot 'validate-acceptance.ps1'
$pwsh = (Get-Command pwsh.exe -ErrorAction Stop).Source
if ($Evidence) {
    try {
        Invoke-Checked $pwsh @('-NoProfile','-File',$validator,'-Document',$Evidence,'-Schema',$schema)
        $record = Get-Content -LiteralPath $Evidence -Raw | ConvertFrom-Json
        if ($record.results.Count -ne $matrix.Count) { throw 'The complete required matrix must be supplied.' }
        $observed = @($record.results | Where-Object { $_.status -in @('PASS','FAIL') })
        if ($observed.Count -gt 0) {
            if (!$Package) { throw 'Observed results require -Package to bind the exact MSIX.' }
            if ($record.provenance.packageSha256 -notmatch '^[A-Fa-f0-9]{64}$' -or $record.provenance.packageSha256 -match '^0{64}$') {
                throw 'Observed results require a valid nonzero SHA256 package hash.'
            }
        }
        if ($Package -and (Get-MsixHash $Package) -ine $record.provenance.packageSha256) { throw 'Package provenance hash mismatch.' }
        $deviceIds = @{}
        foreach ($device in $record.provenance.devices) {
            if ([string]::IsNullOrWhiteSpace($device.id) -or $deviceIds.ContainsKey($device.id)) { throw 'Missing or duplicate device provenance ID.' }
            $deviceIds[$device.id] = $true
        }
        $seen = @{}
        foreach ($row in $record.results) {
            if ($seen.ContainsKey($row.id)) { throw "Duplicate matrix row: $($row.id)" }
            $seen[$row.id] = $true
            $definition = @($matrix | Where-Object id -eq $row.id)
            if ($definition.Count -ne 1 -or $row.peer -ne $definition[0].peer -or $row.scenario -ne $definition[0].scenario) { throw 'Unknown matrix row.' }
            if ($row.status -in @('PASS','FAIL')) {
                if ([string]::IsNullOrWhiteSpace($record.provenance.operator) -or !$record.provenance.packageSha256 -or !$row.observedAtUtc -or !$row.evidence.Count -or !$row.deviceIds.Count) {
                    throw 'Observed results require operator, package hash, time, device IDs, and evidence.'
                }
                [datetimeoffset]::Parse($row.observedAtUtc) | Out-Null
                if (@($row.evidence | Where-Object { [string]::IsNullOrWhiteSpace($_) }).Count -gt 0) { throw 'Observed evidence references must not be empty or whitespace.' }
                $references = @{}
                foreach ($id in $row.deviceIds) {
                    if ([string]::IsNullOrWhiteSpace($id) -or $references.ContainsKey($id)) { throw 'Missing or duplicate referenced device ID.' }
                    $references[$id] = $true
                    if (@($record.provenance.devices | Where-Object id -eq $id).Count -ne 1) { throw "Missing device provenance: $id" }
                }
                $devices = @($record.provenance.devices | Where-Object { $_.id -in $row.deviceIds })
                $windowsDevices = @($devices | Where-Object platform -eq 'Windows')
                if ($row.peer -eq 'Windows') {
                    if ($windowsDevices.Count -lt 2) { throw 'Windows observation requires two distinct referenced physical Windows devices.' }
                } elseif ($row.peer -eq 'Local') {
                    if ($windowsDevices.Count -lt 1) { throw 'Local UI observation requires a referenced physical Windows device.' }
                } else {
                    if ($windowsDevices.Count -lt 1 -or @($devices | Where-Object platform -eq $row.peer).Count -lt 1) { throw 'Cross-platform observation requires referenced Windows and named physical peer devices.' }
                }
            } elseif ([string]::IsNullOrWhiteSpace($row.reason)) { throw 'Unobserved results require a reason.' }
        }
    } catch { throw "Invalid acceptance evidence: $_" }
} else {
    $packageHash = if ($Package) { Get-MsixHash $Package } else { '' }
    $record = [ordered]@{
        schemaVersion=1; recordedAtUtc=[datetimeoffset]::UtcNow.ToString('o'); releaseGate='BLOCKED'
        provenance=[ordered]@{ operator=''; os=[Environment]::OSVersion.VersionString; architecture=$env:PROCESSOR_ARCHITECTURE; packageSha256=$packageHash; devices=@(); limitations=@($Reason) }
        results=@($matrix | ForEach-Object { [ordered]@{ id=$_.id; peer=$_.peer; scenario=$_.scenario; status='NOT_RUN'; reason=$Reason; observedAtUtc=''; deviceIds=@(); evidence=@() } })
    }
}
$statuses = @($record.results | ForEach-Object { $_.status })
$record.releaseGate = if ($statuses -contains 'FAIL') { 'FAIL' } elseif (@($statuses | Where-Object { $_ -ne 'PASS' }).Count) { 'BLOCKED' } else { 'PASS' }
New-Item -ItemType Directory -Path (Split-Path $outputPath) -Force | Out-Null
$temporary = $outputPath + '.' + [guid]::NewGuid().ToString('N') + '.tmp'
try {
    $record | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $temporary -Encoding UTF8
    Invoke-Checked $pwsh @('-NoProfile','-File',$validator,'-Document',$temporary,'-Schema',$schema)
    Move-Item -LiteralPath $temporary -Destination $outputPath -Force
} finally { if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary } }
Write-Host "$($record.releaseGate): $($record.results.Count) physical/UI acceptance rows recorded at $outputPath"
if ($record.releaseGate -eq 'PASS') { exit 0 }
if ($record.releaseGate -eq 'FAIL') { exit 1 }
exit 2
