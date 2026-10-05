param(
    [string]$SecretsFile = (Join-Path $env:LOCALAPPDATA 'TachyonScript\security\secrets.clixml')
)

$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') { throw 'This credential loader requires Windows DPAPI.' }
if (-not (Test-Path -LiteralPath $SecretsFile -PathType Leaf)) {
    throw 'TachyonScript security credentials are missing. Run the security configuration installer first.'
}
$tachyonEncryptedSecrets = Import-Clixml -LiteralPath $SecretsFile
foreach ($tachyonSecretName in @('OPENROUTER_API_KEY', 'TACHYON_SECURITY_DISCORD_WEBHOOK')) {
    $tachyonSecureValue = $tachyonEncryptedSecrets[$tachyonSecretName]
    if ($tachyonSecureValue -isnot [System.Security.SecureString]) {
        throw 'TachyonScript security credential store is invalid. Secret values withheld.'
    }
    $tachyonSecretPointer = [IntPtr]::Zero
    try {
        $tachyonSecretPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($tachyonSecureValue)
        $tachyonPlainValue = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($tachyonSecretPointer)
        if ([string]::IsNullOrWhiteSpace($tachyonPlainValue)) { throw 'A required security credential is empty.' }
        [Environment]::SetEnvironmentVariable($tachyonSecretName, $tachyonPlainValue, 'Process')
    } finally {
        if ($tachyonSecretPointer -ne [IntPtr]::Zero) {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($tachyonSecretPointer)
        }
        $tachyonPlainValue = $null
        $tachyonSecureValue = $null
    }
}
$tachyonEncryptedSecrets = $null
