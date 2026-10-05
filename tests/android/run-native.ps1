param([string]$Sdk,[Parameter(Position=0)][string]$Binary)
$ErrorActionPreference='Stop'
$adb=Join-Path $Sdk 'platform-tools/adb.exe'
$remote='/data/local/tmp/unishare-'+[IO.Path]::GetFileName($Binary)
& $adb push $Binary $remote | Write-Output
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
& $adb shell chmod 700 $remote
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
& $adb shell $remote
exit $LASTEXITCODE
