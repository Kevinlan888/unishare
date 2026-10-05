[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$Report, [Parameter(Mandatory=$true)][int]$ExpectedCount)
. (Join-Path $PSScriptRoot 'tools.ps1')
$document = New-Object System.Xml.XmlDocument
$document.Load($Report)
$tests = $document.SelectNodes('//testcase')
if ($ExpectedCount -lt 1 -or $tests.Count -ne $ExpectedCount) { throw 'Missing required native test report or case.' }
if ($document.SelectNodes('//testcase/skipped|//testcase/failure|//testcase/error').Count -gt 0 -or
    @($tests | Where-Object { $_.GetAttribute('status') -ne 'run' }).Count -gt 0) {
    throw 'Failed or skipped required native test.'
}
Write-Host "PASS all $ExpectedCount required native tests executed."
