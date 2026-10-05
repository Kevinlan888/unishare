#requires -Version 7.4
param([Parameter(Mandatory=$true)][string]$Document, [Parameter(Mandatory=$true)][string]$Schema)
$ErrorActionPreference = 'Stop'
if (!(Test-Json -Json (Get-Content -LiteralPath $Document -Raw) -SchemaFile $Schema -ErrorAction Stop)) {
    throw 'Invalid acceptance evidence: JSON schema validation failed.'
}
