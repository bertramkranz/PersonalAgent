param(
    [Parameter(Position = 0)]
    [string]$GradleTask = "runMcpServer"
)

$ErrorActionPreference = "Stop"

$workspaceRoot = (Get-Location).Path
$workspaceRootNormalized = [System.IO.Path]::GetFullPath($workspaceRoot)
$escapedTaskName = [Regex]::Escape($GradleTask)

# Kill stale Gradle wrapper and cmd processes tied to this workspace/task so MCP always starts fresh code.
Get-CimInstance Win32_Process |
    Where-Object {
        ($_.Name -in @("cmd.exe", "java.exe")) -and
            $_.CommandLine -and
            ($_.CommandLine -match "(?i)\\b$escapedTaskName\\b") -and
            $_.CommandLine -like "*$workspaceRootNormalized*" -and
            ($_.CommandLine -match "(?i)(gradlew|gradle-wrapper|GradleDaemon|gradle\.jar|gradle-launcher)")
    } |
    ForEach-Object {
        try {
            Stop-Process -Id $_.ProcessId -Force -ErrorAction Stop
        } catch {
            # Ignore race conditions where process exits between enumerate and kill.
        }
    }

$gradleWrapper = Join-Path $workspaceRoot "gradlew.bat"
if (-not (Test-Path $gradleWrapper)) {
    throw "Missing gradlew.bat at $gradleWrapper"
}

# Keep Gradle lifecycle noise off stdout so MCP JSON-RPC framing is not polluted.
& $gradleWrapper $GradleTask "--no-daemon" "--quiet" "--console=plain" "--warning-mode=none"
exit $LASTEXITCODE
