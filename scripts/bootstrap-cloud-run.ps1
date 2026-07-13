[CmdletBinding()]
param(
    [string]$ProjectId = (gcloud config get-value project),
    [string]$Region = (gcloud config get-value compute/region),
    [string]$ServiceName = "bertbot-webhook",
    [string]$ArtifactRegistryRepo = "bertbot",
    [string]$ImageTag = "latest",
    [string]$CloudSqlInstance = "bertbot-postgres",
    [string]$DatabaseName = "bertbot",
    [string]$DatabaseUser = "bertbot",
    [string]$AiApiKeySecret = "bertbot-ai-api-key",
    [string]$DatabasePasswordSecret = "bertbot-db-password",
    [string]$TelegramSecretTokenSecret,
    [string]$SlackSigningSecret,
    [string]$WhatsAppAppSecret,
    [string]$WhatsAppVerifyTokenSecret,
    [switch]$CreateSecrets,
    [switch]$BuildAndPushImage,
    [switch]$Deploy,
    [switch]$AllowUnauthenticated
)

$ErrorActionPreference = "Stop"

function Invoke-NativeCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,

        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,

        [switch]$IgnoreExitCode
    )

    & $FilePath @Arguments
    if (-not $IgnoreExitCode -and $LASTEXITCODE -ne 0) {
        throw "Command failed: $FilePath $($Arguments -join ' ')"
    }
}

function Test-GcloudResourceExists {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$DescribeArgs
    )

    Invoke-NativeCommand -FilePath "gcloud" -Arguments $DescribeArgs -IgnoreExitCode
    return $LASTEXITCODE -eq 0
}

function Read-RequiredSecret {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Prompt
    )

    while ($true) {
        $secure = Read-Host -Prompt $Prompt -AsSecureString
        $ptr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
        try {
            $plain = [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
        } finally {
            [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
        }

        if (-not [string]::IsNullOrWhiteSpace($plain)) {
            return $plain
        }

        Write-Host "Value cannot be empty." -ForegroundColor Yellow
    }
}

function Upsert-SecretValue {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,

        [Parameter(Mandatory = $true)]
        [string]$Value
    )

    $tempFile = [System.IO.Path]::GetTempFileName()
    try {
        Set-Content -Path $tempFile -Value $Value -NoNewline
        if (Test-GcloudResourceExists -DescribeArgs @("secrets", "describe", $Name)) {
            Invoke-NativeCommand -FilePath "gcloud" -Arguments @("secrets", "versions", "add", $Name, "--data-file=$tempFile")
            Write-Host "Added new version for secret '$Name'."
        } else {
            Invoke-NativeCommand -FilePath "gcloud" -Arguments @("secrets", "create", $Name, "--data-file=$tempFile")
            Write-Host "Created secret '$Name'."
        }
    } finally {
        Remove-Item -Path $tempFile -Force -ErrorAction SilentlyContinue
    }
}

if ([string]::IsNullOrWhiteSpace($ProjectId)) {
    throw "ProjectId is required. Set gcloud project or pass -ProjectId explicitly."
}

if ([string]::IsNullOrWhiteSpace($Region)) {
    throw "Region is required. Set gcloud compute/region or pass -Region explicitly."
}

$workspaceRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $workspaceRoot

if (-not $Deploy -and -not $BuildAndPushImage) {
    # Default behavior: fully provision and deploy unless explicitly disabled.
    $Deploy = $true
    $BuildAndPushImage = $true
}

Write-Host "Project: $ProjectId"
Write-Host "Region: $Region"

Invoke-NativeCommand -FilePath "gcloud" -Arguments @("config", "set", "project", $ProjectId)

if (-not (Test-GcloudResourceExists -DescribeArgs @("artifacts", "repositories", "describe", $ArtifactRegistryRepo, "--location", $Region))) {
    Invoke-NativeCommand -FilePath "gcloud" -Arguments @(
        "artifacts", "repositories", "create", $ArtifactRegistryRepo,
        "--location", $Region,
        "--repository-format", "docker",
        "--description", "BertBot container images"
    )
} else {
    Write-Host "Artifact Registry repository '$ArtifactRegistryRepo' already exists."
}

if (-not (Test-GcloudResourceExists -DescribeArgs @("sql", "instances", "describe", $CloudSqlInstance))) {
    Invoke-NativeCommand -FilePath "gcloud" -Arguments @(
        "sql", "instances", "create", $CloudSqlInstance,
        "--database-version", "POSTGRES_16",
        "--edition", "ENTERPRISE",
        "--region", $Region,
        "--cpu", "1",
        "--memory", "3840MiB",
        "--storage-size", "20GB",
        "--storage-type", "SSD"
    )
} else {
    Write-Host "Cloud SQL instance '$CloudSqlInstance' already exists."
}

$dbExists = (& gcloud sql databases list --instance $CloudSqlInstance --format="value(name)" | Where-Object { $_ -eq $DatabaseName })
if (-not $dbExists) {
    Invoke-NativeCommand -FilePath "gcloud" -Arguments @("sql", "databases", "create", $DatabaseName, "--instance", $CloudSqlInstance)
} else {
    Write-Host "Database '$DatabaseName' already exists."
}

$userExists = (& gcloud sql users list --instance $CloudSqlInstance --format="value(name)" | Where-Object { $_ -eq $DatabaseUser })
if (-not $userExists) {
    $dbPassword = Read-RequiredSecret -Prompt "Enter password for Cloud SQL user '$DatabaseUser'"
    Invoke-NativeCommand -FilePath "gcloud" -Arguments @("sql", "users", "create", $DatabaseUser, "--instance", $CloudSqlInstance, "--password", $dbPassword)
    if ($CreateSecrets) {
        Upsert-SecretValue -Name $DatabasePasswordSecret -Value $dbPassword
    }
} else {
    Write-Host "Cloud SQL user '$DatabaseUser' already exists."
    if ($CreateSecrets -and -not (Test-GcloudResourceExists -DescribeArgs @("secrets", "describe", $DatabasePasswordSecret))) {
        $dbPassword = Read-RequiredSecret -Prompt "Enter existing password for Cloud SQL user '$DatabaseUser' (to store in secret '$DatabasePasswordSecret')"
        Upsert-SecretValue -Name $DatabasePasswordSecret -Value $dbPassword
    }
}

if ($CreateSecrets) {
    $aiKey = Read-RequiredSecret -Prompt "Enter BERTBOT AI API key"
    Upsert-SecretValue -Name $AiApiKeySecret -Value $aiKey

    if ($TelegramSecretTokenSecret) {
        $value = Read-RequiredSecret -Prompt "Enter Telegram secret token"
        Upsert-SecretValue -Name $TelegramSecretTokenSecret -Value $value
    }

    if ($SlackSigningSecret) {
        $value = Read-RequiredSecret -Prompt "Enter Slack signing secret"
        Upsert-SecretValue -Name $SlackSigningSecret -Value $value
    }

    if ($WhatsAppAppSecret) {
        $value = Read-RequiredSecret -Prompt "Enter WhatsApp app secret"
        Upsert-SecretValue -Name $WhatsAppAppSecret -Value $value
    }

    if ($WhatsAppVerifyTokenSecret) {
        $value = Read-RequiredSecret -Prompt "Enter WhatsApp verify token"
        Upsert-SecretValue -Name $WhatsAppVerifyTokenSecret -Value $value
    }
}

$imageUri = "${Region}-docker.pkg.dev/${ProjectId}/${ArtifactRegistryRepo}/${ServiceName}:${ImageTag}"

if ($BuildAndPushImage) {
    Invoke-NativeCommand -FilePath "gcloud" -Arguments @("auth", "configure-docker", "${Region}-docker.pkg.dev", "--quiet")
    Invoke-NativeCommand -FilePath "gcloud" -Arguments @("builds", "submit", "--tag", $imageUri, ".")
}

if ($Deploy) {
    $deployScript = Join-Path $PSScriptRoot "deploy-cloud-run.ps1"
    $deployParams = @{
        ProjectId = $ProjectId
        Region = $Region
        ArtifactRegistryRepo = $ArtifactRegistryRepo
        ImageTag = $ImageTag
        CloudSqlInstance = $CloudSqlInstance
        ServiceName = $ServiceName
        DatabaseName = $DatabaseName
        DatabaseUser = $DatabaseUser
        AiApiKeySecret = $AiApiKeySecret
        DatabasePasswordSecret = $DatabasePasswordSecret
    }

    if ($AllowUnauthenticated) {
        $deployParams.AllowUnauthenticated = $true
    }

    if ($TelegramSecretTokenSecret) {
        $deployParams.TelegramSecretTokenSecret = $TelegramSecretTokenSecret
    }

    if ($SlackSigningSecret) {
        $deployParams.SlackSigningSecret = $SlackSigningSecret
    }

    if ($WhatsAppAppSecret) {
        $deployParams.WhatsAppAppSecret = $WhatsAppAppSecret
    }

    if ($WhatsAppVerifyTokenSecret) {
        $deployParams.WhatsAppVerifyTokenSecret = $WhatsAppVerifyTokenSecret
    }

    & $deployScript @deployParams
    if ($LASTEXITCODE -ne 0) {
        throw "Deploy script failed."
    }
}

Write-Host "Bootstrap completed successfully."
