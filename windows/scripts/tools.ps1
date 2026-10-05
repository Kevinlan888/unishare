Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$WindowsRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$RepoRoot = [IO.Path]::GetFullPath((Join-Path $WindowsRoot '..'))
function Invoke-Checked([string]$Tool, [string[]]$Arguments) {
    & $Tool @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Tool failed with exit code $LASTEXITCODE" }
}
function Get-Sha256([string]$Path) {
    $stream = [IO.File]::OpenRead($Path)
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($algorithm.ComputeHash($stream)).Replace('-','') }
    finally { $algorithm.Dispose(); $stream.Dispose() }
}
function Get-CMake {
    $installed = Get-Command cmake.exe -ErrorAction SilentlyContinue
    if ($installed) { return $installed.Source }
    $bundled = 'C:\Program Files\Microsoft Visual Studio\18\Insiders\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe'
    if (Test-Path -LiteralPath $bundled) { return $bundled }
    throw 'CMake >= 3.21 is required; install the Visual Studio C++ CMake tools.'
}
function Get-SdkTool([string]$Name) {
    $kits = Join-Path ${env:ProgramFiles(x86)} 'Windows Kits\10\bin'
    $tool = Get-ChildItem -LiteralPath $kits -Directory | Where-Object { $_.Name -match '^10\.0\.\d+\.\d+$' } |
        Sort-Object { [version]$_.Name } -Descending | ForEach-Object { Join-Path $_.FullName "x64\$Name.exe" } |
        Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if (!$tool) { throw "Windows SDK x64 $Name.exe is required." }
    return $tool
}
function Assert-X64Pe([string]$Path) {
    $stream = [IO.File]::OpenRead($Path)
    $reader = New-Object IO.BinaryReader($stream)
    try {
        if ($reader.ReadUInt16() -ne 0x5A4D) { throw "Not a PE file: $Path" }
        $stream.Position = 0x3C
        $offset = $reader.ReadUInt32()
        $stream.Position = $offset
        if ($reader.ReadUInt32() -ne 0x4550 -or $reader.ReadUInt16() -ne 0x8664) { throw "Required x64 PE: $Path" }
    } finally { $reader.Dispose(); $stream.Dispose() }
}
