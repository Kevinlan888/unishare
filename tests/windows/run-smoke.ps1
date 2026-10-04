param([Parameter(Mandatory=$true)][string]$BuildDirectory)
$build = (Resolve-Path -LiteralPath $BuildDirectory).Path
$run = Join-Path $build ('smoke-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run | Out-Null
$exe = Join-Path $build 'windows_smoke.exe'
$receiver = Start-Process -FilePath $exe -ArgumentList @('receiver',('"' + $run + '"'),'--test-consent') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $run 'receiver.log') -RedirectStandardError (Join-Path $run 'receiver.err')
$sender = Start-Process -FilePath $exe -ArgumentList @('sender',('"' + $run + '"'),'--test-consent') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $run 'sender.log') -RedirectStandardError (Join-Path $run 'sender.err')
$receiver.WaitForExit()
$sender.WaitForExit()
Get-Content -LiteralPath (Join-Path $run 'receiver.log')
Get-Content -LiteralPath (Join-Path $run 'sender.log')
Write-Output "Logs: $run; receiver=$($receiver.ExitCode), sender=$($sender.ExitCode)"
if ($receiver.ExitCode -ne 0 -or $sender.ExitCode -ne 0) { exit 1 }
