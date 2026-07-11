$ErrorActionPreference = "Stop"

$launcher = Join-Path $PSScriptRoot "mcp-stdio-launcher.ps1"
if (-not (Test-Path $launcher)) {
    throw "Missing launcher script at $launcher"
}

& powershell -NoProfile -ExecutionPolicy Bypass -File $launcher runMcpServer
exit $LASTEXITCODE
