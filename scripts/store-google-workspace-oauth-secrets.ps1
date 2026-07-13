param(
    [Parameter(Mandatory = $true)]
    [string]$ProjectId,
    [string]$GoogleWorkspaceOauthCredentialsJsonPath,
    [string]$GoogleWorkspaceTokenPath = "/opt/google-workspace-extension/gemini-cli-workspace-token.json",
    [string]$GoogleWorkspaceMasterKeyPath = "/opt/google-workspace-extension/.gemini-cli-workspace-master-key",
    [string]$GoogleWorkspaceOauthCredentialsJsonB64SecretName = "bertbot-google-workspace-oauth-credentials-json-b64",
    [string]$GoogleWorkspaceTokenB64SecretName = "bertbot-google-workspace-token-b64",
    [string]$GoogleWorkspaceMasterKeyB64SecretName = "bertbot-google-workspace-master-key-b64"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Ensure-SecretExists {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Project,
        [Parameter(Mandatory = $true)]
        [string]$SecretName
    )

    $exists = gcloud secrets list --project $Project --filter="name:$SecretName" --format="value(name)"
    if (-not $exists) {
        Write-Host "Creating secret: $SecretName"
        gcloud secrets create $SecretName --project $Project --replication-policy="automatic" | Out-Host
    }
}

function Add-SecretVersionFromString {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Project,
        [Parameter(Mandatory = $true)]
        [string]$SecretName,
        [Parameter(Mandatory = $true)]
        [string]$Value
    )

    $tmp = New-TemporaryFile
    try {
        [System.IO.File]::WriteAllText($tmp.FullName, $Value)
        gcloud secrets versions add $SecretName --project $Project --data-file=$tmp.FullName | Out-Host
    } finally {
        Remove-Item -Path $tmp.FullName -ErrorAction SilentlyContinue
    }
}

function To-Base64FileContent {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "File not found: $Path"
    }

    $bytes = [System.IO.File]::ReadAllBytes($Path)
    return [Convert]::ToBase64String($bytes)
}

if (-not $GoogleWorkspaceOauthCredentialsJsonPath -and (-not $GoogleWorkspaceTokenPath -or -not $GoogleWorkspaceMasterKeyPath)) {
    throw "Provide GoogleWorkspaceOauthCredentialsJsonPath or both token/master key paths."
}

if ($GoogleWorkspaceOauthCredentialsJsonPath) {
    $oauthJsonB64 = To-Base64FileContent -Path $GoogleWorkspaceOauthCredentialsJsonPath
    Ensure-SecretExists -Project $ProjectId -SecretName $GoogleWorkspaceOauthCredentialsJsonB64SecretName
    Add-SecretVersionFromString -Project $ProjectId -SecretName $GoogleWorkspaceOauthCredentialsJsonB64SecretName -Value $oauthJsonB64
    Write-Host "Stored OAuth credentials JSON secret version: $GoogleWorkspaceOauthCredentialsJsonB64SecretName"
}

if (Test-Path -LiteralPath $GoogleWorkspaceTokenPath -and Test-Path -LiteralPath $GoogleWorkspaceMasterKeyPath) {
    $tokenB64 = To-Base64FileContent -Path $GoogleWorkspaceTokenPath
    $masterKeyB64 = To-Base64FileContent -Path $GoogleWorkspaceMasterKeyPath

    Ensure-SecretExists -Project $ProjectId -SecretName $GoogleWorkspaceTokenB64SecretName
    Ensure-SecretExists -Project $ProjectId -SecretName $GoogleWorkspaceMasterKeyB64SecretName

    Add-SecretVersionFromString -Project $ProjectId -SecretName $GoogleWorkspaceTokenB64SecretName -Value $tokenB64
    Add-SecretVersionFromString -Project $ProjectId -SecretName $GoogleWorkspaceMasterKeyB64SecretName -Value $masterKeyB64

    Write-Host "Stored token/master-key secret versions: $GoogleWorkspaceTokenB64SecretName, $GoogleWorkspaceMasterKeyB64SecretName"
} elseif (-not $GoogleWorkspaceOauthCredentialsJsonPath) {
    throw "Token/master-key files were not found and OAuth JSON path was not provided."
}

Write-Host "Done."
