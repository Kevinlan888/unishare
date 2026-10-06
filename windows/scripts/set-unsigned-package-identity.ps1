[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$ManifestPath)

$marker = 'OID.2.25.311729368913984317654407730594956997722=1'
$fullPath = [IO.Path]::GetFullPath($ManifestPath)
if (!(Test-Path -LiteralPath $fullPath -PathType Leaf)) { throw 'ManifestPath must reference an existing file.' }

$manifest = New-Object System.Xml.XmlDocument
$manifest.PreserveWhitespace = $true
$manifest.XmlResolver = $null
$manifest.Load($fullPath)
$identities = @($manifest.SelectNodes("/*[local-name()='Package']/*[local-name()='Identity']"))
if ($identities.Count -ne 1) { throw 'Package manifest must contain exactly one Identity element.' }

$publisher = $identities[0].GetAttribute('Publisher').Trim()
if ([string]::IsNullOrWhiteSpace($publisher)) { throw 'Package manifest Identity must contain a Publisher.' }
$markers = [regex]::Matches($publisher, [regex]::Escape($marker))
if ($markers.Count -eq 0) {
    $identities[0].SetAttribute('Publisher', "$publisher, $marker")
} elseif ($markers.Count -ne 1 -or !$publisher.EndsWith($marker, [StringComparison]::Ordinal)) {
    throw 'Unsigned package marker must occur exactly once as the final Publisher field.'
}

$settings = New-Object System.Xml.XmlWriterSettings
$settings.Encoding = New-Object System.Text.UTF8Encoding($false)
$settings.Indent = $false
$writer = [Xml.XmlWriter]::Create($fullPath, $settings)
try { $manifest.Save($writer) } finally { $writer.Dispose() }

