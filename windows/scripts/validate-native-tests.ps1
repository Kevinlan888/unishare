[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$Inventory, [string]$Report)
. (Join-Path $PSScriptRoot 'tools.ps1')
# Independent release contract: never derive the required names/count from CTest itself.
$required = @('sdk_services','sdk_lifecycle','transfer_engine','identity','protocol','contracts',
    'device_catalog','session','sdk_api','sdk_api_c11','windows_storage_stream_clipboard',
    'windows_network','windows_adapter','windows_package')
function Assert-RequiredNames($Names, [string]$Source) {
    $seen = @{}
    foreach ($name in $Names) {
        if ($required -cnotcontains $name -or $seen.ContainsKey($name)) { throw "Unexpected or duplicate required native test in ${Source}: $name" }
        $seen[$name] = $true
    }
    foreach ($name in $required) {
        if (!$seen.ContainsKey($name)) { throw "Missing required native test in ${Source}: $name" }
    }
}
$inventoryDocument = Get-Content -LiteralPath $Inventory -Raw | ConvertFrom-Json
Assert-RequiredNames @($inventoryDocument.tests | ForEach-Object { $_.name }) 'CTest inventory'
foreach ($test in $inventoryDocument.tests) {
    if ($test.PSObject.Properties.Name -contains 'properties') {
        foreach ($property in @($test.properties)) {
            if ($property.name -eq 'DISABLED' -and $property.value -eq $true) { throw 'Failed or skipped required native test in CTest inventory.' }
        }
    }
}
if ($Report) {
    $document = New-Object System.Xml.XmlDocument
    $document.Load($Report)
    $tests = $document.SelectNodes('//testcase')
    Assert-RequiredNames @($tests | ForEach-Object { $_.GetAttribute('name') }) 'JUnit report'
    if ($document.SelectNodes('//testcase/skipped|//testcase/failure|//testcase/error').Count -gt 0 -or
        @($tests | Where-Object { $_.GetAttribute('status') -ne 'run' }).Count -gt 0) {
        throw 'Failed or skipped required native test.'
    }
    Write-Host "PASS all $($required.Count) required native tests executed."
} else { Write-Host "PASS exact $($required.Count)-name required native inventory." }
