[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$OpenSslRoot,
      [ValidateSet('Release','RelWithDebInfo','MinSizeRel')][string]$Configuration = 'Release')
. (Join-Path $PSScriptRoot 'tools.ps1')
& (Join-Path $PSScriptRoot 'build-native.ps1') -OpenSslRoot $OpenSslRoot -Configuration $Configuration
if (!$?) { throw 'Native build gate failed.' }
$results = Join-Path $WindowsRoot ('artifacts\test-results\' + [guid]::NewGuid().ToString('N'))
Push-Location $WindowsRoot
try {
    Invoke-Checked 'dotnet' @('test',(Join-Path $WindowsRoot 'UniShare.slnx'),'-c',$Configuration,'-p:Platform=x64',
        '--logger','trx','--results-directory',$results)
} finally { Pop-Location }
$reports = @(Get-ChildItem -LiteralPath $results -Filter '*.trx')
$projects = @(Get-ChildItem -LiteralPath (Join-Path $WindowsRoot 'tests') -Directory)
if ($reports.Count -ne $projects.Count) { throw 'Missing required .NET test report.' }
foreach ($project in $projects) {
    $matching = @($reports | Where-Object { (Get-Content -LiteralPath $_.FullName -Raw) -match [regex]::Escape($project.Name + '.dll') })
    if ($matching.Count -ne 1) { throw "Missing or duplicated test gate: $($project.Name)" }
}
foreach ($report in $reports) {
    [xml]$trx = Get-Content -LiteralPath $report.FullName
    $counters = $trx.TestRun.ResultSummary.Counters
    if ([int]$counters.total -eq 0 -or [int]$counters.passed -ne [int]$counters.total -or [int]$counters.executed -ne [int]$counters.total) {
        throw "Failed or skipped required automated test: $($report.Name)"
    }
}
Write-Host "PASS all $($reports.Count) .NET gates including RealDll; reports: $results"
