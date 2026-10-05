param(
    [Parameter(Mandatory)][ValidateSet('sender','receiver')][string]$Role,
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9-]+$')][string]$Run,
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9-]+$')][string]$Peer,
    [string]$Executable = "$PSScriptRoot/../../build-interop-windows/interop.exe"
)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path "$PSScriptRoot/../..").Path
$out = "$taskRoot/build-interop-$Run"
$fixtures = "$PSScriptRoot/build-fixtures"
& python "$PSScriptRoot/fixtures.py" $fixtures
if ($LASTEXITCODE) { throw 'Fixture generation failed' }
if (Test-Path -LiteralPath "$out/windows") { throw 'Use a fresh run name' }
New-Item -ItemType Directory -Force $out | Out-Null
$process = Start-Process -FilePath $Executable -ArgumentList @($Role,$Run,$Peer,"`"$fixtures`"","`"$out/windows`"",'--test-consent') -WindowStyle Hidden -PassThru -RedirectStandardOutput "$out/windows.jsonl" -RedirectStandardError "$out/windows-error.log"
if (!$process.WaitForExit(240000)) { $process.Kill(); throw 'Windows host exceeded 240 seconds' }
Get-Content "$out/windows.jsonl"
if ($process.ExitCode -ne 0) { throw 'Windows interop host failed' }
