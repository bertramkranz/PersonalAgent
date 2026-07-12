[CmdletBinding()]
param(
    [switch]$IncludeDocker
)

$ErrorActionPreference = 'SilentlyContinue'

# Target only this repo's BertBot MCP server processes.
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$repoMarker = [regex]::Escape($repoRoot)
$patterns = @(
    'runMcpServer',
    'McpServerMainKt',
    'mcp-stdio-launcher-bertbot'
)

$targets = Get-CimInstance Win32_Process | Where-Object {
    $cmd = $_.CommandLine
    if (-not $cmd) { return $false }

    $matchesRepo = $cmd -match $repoMarker
    $matchesMcp = $false
    foreach ($pattern in $patterns) {
        if ($cmd -match $pattern) {
            $matchesMcp = $true
            break
        }
    }

    return $matchesRepo -and $matchesMcp
}

if (-not $targets -or $targets.Count -eq 0) {
    Write-Host 'No BertBot MCP processes found.'
} else {
    foreach ($proc in $targets) {
        try {
            Stop-Process -Id $proc.ProcessId -Force -ErrorAction Stop
            Write-Host ("Stopped PID {0}: {1}" -f $proc.ProcessId, $proc.Name)
        } catch {
            Write-Warning ("Could not stop PID {0}: {1}" -f $proc.ProcessId, $_.Exception.Message)
        }
    }
}

if ($IncludeDocker) {
    $dockerPs = docker ps --format "{{.ID}}`t{{.Names}}`t{{.Image}}"
    if ($LASTEXITCODE -eq 0 -and $dockerPs) {
        $containers = $dockerPs | Where-Object { $_ -match '(?i)bertbot' }
        foreach ($line in $containers) {
            $id = ($line -split "`t")[0]
            if ($id) {
                docker stop $id | Out-Null
                Write-Host ("Stopped Docker container {0}" -f $id)
            }
        }
        if (-not $containers) {
            Write-Host 'No BertBot Docker containers found.'
        }
    } else {
        Write-Host 'Docker not available or no running containers.'
    }
}

$remaining = Get-CimInstance Win32_Process | Where-Object {
    $cmd = $_.CommandLine
    if (-not $cmd) { return $false }
    ($cmd -match $repoMarker) -and ($cmd -match 'runMcpServer|McpServerMainKt|mcp-stdio-launcher-bertbot')
}

if ($remaining) {
    Write-Warning 'Some BertBot MCP processes are still running:'
    $remaining | Select-Object ProcessId, Name, CommandLine | Format-Table -AutoSize
    exit 1
}

Write-Host 'BertBot MCP stop complete: no matching processes running.'
