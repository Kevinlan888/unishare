[CmdletBinding()]
param(
    [string]$ThumbprintPath = (Join-Path $PSScriptRoot '..\signing\package-signing-thumbprint.txt')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (!(Test-Path -LiteralPath $ThumbprintPath -PathType Leaf)) {
    throw "Signing certificate thumbprint file was not found: $ThumbprintPath"
}

$thumbprint = (Get-Content -LiteralPath $ThumbprintPath -Raw).Trim().Replace(' ', '').ToUpperInvariant()
if ($thumbprint -notmatch '^[0-9A-F]{40}$') {
    throw "Signing certificate thumbprint must contain exactly 40 hexadecimal characters: $ThumbprintPath"
}

$store = [Security.Cryptography.X509Certificates.X509Store]::new(
    [Security.Cryptography.X509Certificates.StoreName]::My,
    [Security.Cryptography.X509Certificates.StoreLocation]::CurrentUser)
$certificate = $null
try {
    $store.Open([Security.Cryptography.X509Certificates.OpenFlags]::ReadOnly)
    $matches = @($store.Certificates.Find(
        [Security.Cryptography.X509Certificates.X509FindType]::FindByThumbprint,
        $thumbprint,
        $false))
    if ($matches.Count -ne 1) {
        throw "Expected exactly one CurrentUser/My signing certificate for thumbprint $thumbprint; found $($matches.Count)."
    }

    $certificate = $matches[0]
    if ($certificate.Subject -ne 'CN=UniShare') {
        throw "Signing certificate subject must be CN=UniShare; found $($certificate.Subject)."
    }
    if (!$certificate.HasPrivateKey) {
        throw "Signing certificate $thumbprint does not have an accessible private key in CurrentUser/My."
    }
    $now = [DateTime]::Now
    if ($now -lt $certificate.NotBefore -or $now -gt $certificate.NotAfter) {
        throw "Signing certificate $thumbprint is not currently valid."
    }

    $codeSigningOid = '1.3.6.1.5.5.7.3.3'
    $enhancedKeyUsage = @($certificate.Extensions | Where-Object { $_.Oid.Value -eq '2.5.29.37' })
    $supportsCodeSigning = $enhancedKeyUsage.Count -eq 0 -or
        ($enhancedKeyUsage.Count -eq 1 -and
            @($enhancedKeyUsage[0].EnhancedKeyUsages | Where-Object { $_.Value -eq $codeSigningOid }).Count -eq 1)
    if (!$supportsCodeSigning) {
        throw "Signing certificate $thumbprint is not valid for code signing."
    }

    Write-Output $certificate.Thumbprint.ToUpperInvariant()
}
finally {
    if ($certificate) { $certificate.Dispose() }
    $store.Dispose()
}
