param([switch]$PrepareOnly, [switch]$VerifyNegativeOnly)
$ErrorActionPreference = 'Stop'
$androidRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$fixtureRoot = Join-Path $androidRoot 'app/build/abi-contract-fixture'
$fixtureScript = Join-Path $fixtureRoot 'commented-app.gradle.kts'
$initScript = Join-Path $fixtureRoot 'select-commented-app.init.gradle.kts'
if (!$VerifyNegativeOnly) {
New-Item -ItemType Directory -Path $fixtureRoot -Force | Out-Null
$source = [IO.File]::ReadAllText((Join-Path $androidRoot 'app/build.gradle.kts'))
# This is a deliberate test mutation, not the validator: Gradle evaluates this
# copied build script with both app ABI declarations commented out.
$declaration = '(?m)^(\s*)ndk \{ abiFilters \+= listOf\([^\r\n]+\) \}\s*$'
if ([regex]::Matches($source, $declaration).Count -ne 2) { throw 'Expected exactly two app ABI declarations to mutate' }
$commented = [regex]::Replace($source, $declaration, '$1// ndk ABI declaration intentionally disabled: $0')
[IO.File]::WriteAllText($fixtureScript, $commented, [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText($initScript, @'
settingsEvaluated {
    project(":app").buildFileName = "build/abi-contract-fixture/commented-app.gradle.kts"
}
'@, [Text.UTF8Encoding]::new($false))
Write-Output "Prepared commented ABI build fixture: $fixtureScript"
Write-Output "Init script: $initScript"
if ($PrepareOnly) { return }
}

$gradle = Join-Path $androidRoot 'gradlew.bat'
$report = Join-Path $androidRoot 'app/build/test-results/testDebugUnitTest/TEST-com.unishare.app.BuildContractTest.xml'
$started = (Get-Item -LiteralPath $initScript).LastWriteTimeUtc
if (!$VerifyNegativeOnly) {
& $gradle -p $androidRoot :app:testDebugUnitTest --rerun --tests '*BuildContractTest*' -I $initScript --console=plain
if ($LASTEXITCODE -eq 0) { throw 'FALSE GREEN: commented app ABI declarations were accepted' }
}
if (!(Test-Path -LiteralPath $report) -or (Get-Item -LiteralPath $report).LastWriteTimeUtc -lt $started) { throw 'Negative build produced no fresh JUnit evidence' }
[xml]$negative = Get-Content -LiteralPath $report -Raw
$failed = @($negative.SelectNodes('//testcase[failure]'))
if ($negative.SelectNodes('//testcase').Count -eq 4 -and $failed.Count -eq 0) { throw 'FALSE GREEN: commented app ABI declarations were accepted' }
if ($negative.SelectNodes('//testcase').Count -ne 4 -or $failed.Count -ne 1 -or
    $failed[0].name -ne 'app_variants_filter_dependency_abis_to_the_native_sdk_abis' -or
    $negative.SelectNodes('//error|//skipped').Count -ne 0) { throw 'Negative fixture failed for a different reason' }
if ($failed[0].failure.message -notlike '*App debug must not package dependency-only ABIs*') { throw 'Negative fixture did not reach the evaluated ABI assertion' }
$abiReport = Join-Path $androidRoot 'app/build/reports/abi-contract.properties'
$evaluated = @(Get-Content -LiteralPath $abiReport)
if ('app.debug=' -notin $evaluated -or 'app.release=' -notin $evaluated) { throw 'Negative fixture did not actually disable both evaluated app ABI filters' }
Write-Output 'PASS negative fixture: evaluated commented filters reject the ABI contract (4 tests, exactly one intended failure)'
if ($VerifyNegativeOnly) { return }

& $gradle -p $androidRoot :app:testDebugUnitTest --rerun --tests '*BuildContractTest*' --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Normal app ABI configuration failed to restore GREEN' }
[xml]$positive = Get-Content -LiteralPath $report -Raw
if ($positive.SelectNodes('//testcase').Count -ne 4 -or $positive.SelectNodes('//failure|//error|//skipped').Count -ne 0) { throw 'Normal ABI configuration has invalid JUnit evidence' }
Write-Output 'PASS normal evaluated ABI contract: 4 tests, zero failures/errors/skipped'
