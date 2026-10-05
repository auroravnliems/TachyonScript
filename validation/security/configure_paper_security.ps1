param(
    [Parameter(Mandatory = $true)][string]$ServerDirectory,
    [string]$PluginJar = (Join-Path $PSScriptRoot '..\..\tachyon-plugin\build\libs\TachyonScript-0.5.1-SNAPSHOT.jar'),
    [string]$Model = 'qwen/qwen3-coder'
)

$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') { throw 'This installer requires Windows DPAPI.' }
if ([string]::IsNullOrWhiteSpace($env:OPENROUTER_API_KEY) -or
        [string]::IsNullOrWhiteSpace($env:TACHYON_SECURITY_DISCORD_WEBHOOK)) {
    throw 'Supply OPENROUTER_API_KEY and TACHYON_SECURITY_DISCORD_WEBHOOK in the process environment. Values withheld.'
}
if ($Model -notmatch '^[A-Za-z0-9._:/-]+$') { throw 'Invalid model identifier.' }
$tachyonWebhookUri = [Uri]$env:TACHYON_SECURITY_DISCORD_WEBHOOK
if ($tachyonWebhookUri.Scheme -ne 'https' -or $tachyonWebhookUri.Host -ne 'discord.com' -or
        $tachyonWebhookUri.AbsolutePath -notmatch '^/api/webhooks/[0-9]+/[A-Za-z0-9_-]+$' -or
        $tachyonWebhookUri.UserInfo -ne '' -or $tachyonWebhookUri.Query -ne '' -or $tachyonWebhookUri.Fragment -ne '') {
    throw 'Expected a Discord HTTPS webhook. Value withheld.'
}
$tachyonServerRoot = (Get-Item -LiteralPath $ServerDirectory).FullName
if (-not (Test-Path -LiteralPath (Join-Path $tachyonServerRoot 'server.args') -PathType Leaf)) {
    throw 'The target is not the prepared Paper installation.'
}
$tachyonManifest = Get-Content -LiteralPath (Join-Path $tachyonServerRoot 'manifest.json') -Raw | ConvertFrom-Json
$tachyonPortProbe = New-Object Net.Sockets.TcpClient
try {
    $tachyonConnection = $tachyonPortProbe.ConnectAsync('127.0.0.1', [int]$tachyonManifest.port)
    try { $tachyonConnection.Wait(500) | Out-Null } catch { }
    if ($tachyonPortProbe.Connected) { throw 'Stop the original Paper server before configuring security.' }
} finally { $tachyonPortProbe.Dispose() }
$tachyonSourceJar = (Get-Item -LiteralPath $PluginJar).FullName
$tachyonInstalledJar = Join-Path $tachyonServerRoot 'plugins\TachyonScript-0.5.1-SNAPSHOT.jar'
$tachyonConfigPath = Join-Path $tachyonServerRoot 'plugins\TachyonScript\config.yml'
$tachyonLauncherPath = Join-Path $tachyonServerRoot 'start.ps1'
$tachyonOriginalConfig = [IO.File]::ReadAllText($tachyonConfigPath)
$tachyonProfile = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'openrouter-security.yml'))
$tachyonProfile = $tachyonProfile.Replace('model: "qwen/qwen3-coder"', ('model: "' + $Model + '"'))
$tachyonProfile = $tachyonProfile.Substring($tachyonProfile.IndexOf('security:'))
$tachyonSecurityMatches = [regex]::Matches($tachyonOriginalConfig, '(?m)^security:[ \t]*(?:#[^\r\n]*)?\r?$')
if ($tachyonSecurityMatches.Count -gt 1) { throw 'Duplicate security sections must be resolved before installation.' }
if ($tachyonSecurityMatches.Count -eq 1) {
    $tachyonSecurityStart = $tachyonSecurityMatches[0].Index
    $tachyonFollowingRoot = [regex]::Match($tachyonOriginalConfig.Substring($tachyonSecurityStart + $tachyonSecurityMatches[0].Length),
            '(?m)^[^\s#][^\r\n]*')
    $tachyonSecurityEnd = $tachyonOriginalConfig.Length
    if ($tachyonFollowingRoot.Success) {
        $tachyonSecurityEnd = $tachyonSecurityStart + $tachyonSecurityMatches[0].Length + $tachyonFollowingRoot.Index
    }
    $tachyonNewConfig = $tachyonOriginalConfig.Substring(0, $tachyonSecurityStart) + $tachyonProfile.TrimEnd() + "`r`n" +
            $tachyonOriginalConfig.Substring($tachyonSecurityEnd)
} else {
    $tachyonNewConfig = $tachyonOriginalConfig.TrimEnd() + "`r`n`r`n" + $tachyonProfile.TrimEnd() + "`r`n"
}
if (-not $tachyonNewConfig.Contains('enabled: true') -or $tachyonNewConfig.Contains($env:OPENROUTER_API_KEY) -or
        $tachyonNewConfig.Contains($env:TACHYON_SECURITY_DISCORD_WEBHOOK)) {
    throw 'Security configuration cannot contain literal credentials.'
}
$tachyonLoaderPath = Join-Path $tachyonServerRoot 'security-env.ps1'
$tachyonOriginalLauncher = [IO.File]::ReadAllText($tachyonLauncherPath)
$tachyonNewLauncher = $tachyonOriginalLauncher
if (-not $tachyonOriginalLauncher.Contains('security-env.ps1')) {
    $tachyonNewLauncher = '. (Join-Path $PSScriptRoot "security-env.ps1")' + "`r`n" + $tachyonOriginalLauncher
}
$tachyonBackupRoot = Join-Path $tachyonServerRoot ('plugins\TachyonScript\security-setup-backups\' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffffffZ'))
New-Item -ItemType Directory -Path $tachyonBackupRoot -Force | Out-Null
Copy-Item -LiteralPath $tachyonConfigPath -Destination (Join-Path $tachyonBackupRoot 'config.yml')
Copy-Item -LiteralPath $tachyonLauncherPath -Destination (Join-Path $tachyonBackupRoot 'start.ps1')
if (Test-Path -LiteralPath $tachyonInstalledJar) {
    Copy-Item -LiteralPath $tachyonInstalledJar -Destination (Join-Path $tachyonBackupRoot 'TachyonScript.jar')
}
if (Test-Path -LiteralPath $tachyonLoaderPath) {
    Copy-Item -LiteralPath $tachyonLoaderPath -Destination (Join-Path $tachyonBackupRoot 'security-env.ps1')
}
$tachyonCredentialRoot = Join-Path $env:LOCALAPPDATA 'TachyonScript\security'
New-Item -ItemType Directory -Path $tachyonCredentialRoot -Force | Out-Null
$tachyonCurrentIdentity = [Security.Principal.WindowsIdentity]::GetCurrent().User
$tachyonCredentialAcl = New-Object Security.AccessControl.DirectorySecurity
$tachyonCredentialAcl.SetOwner($tachyonCurrentIdentity)
$tachyonCredentialAcl.SetAccessRuleProtection($true, $false)
$tachyonCredentialAcl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($tachyonCurrentIdentity,
        'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')))
Set-Acl -LiteralPath $tachyonCredentialRoot -AclObject $tachyonCredentialAcl
$tachyonSecrets = @{
    OPENROUTER_API_KEY = ConvertTo-SecureString $env:OPENROUTER_API_KEY -AsPlainText -Force
    TACHYON_SECURITY_DISCORD_WEBHOOK = ConvertTo-SecureString $env:TACHYON_SECURITY_DISCORD_WEBHOOK -AsPlainText -Force
}
$tachyonSecretsPath = Join-Path $tachyonCredentialRoot 'secrets.clixml'
if (Test-Path -LiteralPath $tachyonSecretsPath) {
    Copy-Item -LiteralPath $tachyonSecretsPath -Destination (Join-Path $tachyonCredentialRoot ('secrets-backup-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffffffZ') + '.clixml'))
}
$tachyonSecrets | Export-Clixml -LiteralPath $tachyonSecretsPath
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'import_windows_security.ps1') -Destination $tachyonLoaderPath -Force
[IO.File]::WriteAllText($tachyonConfigPath, $tachyonNewConfig, (New-Object Text.UTF8Encoding($false)))
[IO.File]::WriteAllText($tachyonLauncherPath, $tachyonNewLauncher, (New-Object Text.UTF8Encoding($false)))
Copy-Item -LiteralPath $tachyonSourceJar -Destination $tachyonInstalledJar -Force
. $tachyonLoaderPath
[PSCustomObject]@{
    server = $tachyonServerRoot
    model = $Model
    aiEnabled = $true
    discordEnabled = $true
    encryptedCredentials = $tachyonSecretsPath
    backup = $tachyonBackupRoot
    deployedSha256 = (Get-FileHash -LiteralPath $tachyonInstalledJar -Algorithm SHA256).Hash.ToLowerInvariant()
    serverStarted = $false
} | ConvertTo-Json -Compress
