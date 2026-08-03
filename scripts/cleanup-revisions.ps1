# Google Cloud Project ID
$GoogleProjectId = "personal-agent-502221"

# GCP Region
$GcpRegion = "europe-west2"

# Array of Cloud Run services to clean up
$CloudRunServices = @(
    "bertbot-webhook"
)

foreach ($Service in $CloudRunServices) {
    Write-Host "Processing service: $Service" -ForegroundColor Cyan

    # Get list of inactive revisions (wrapping in @() forces the output to be an array)
    $Revisions = @(gcloud run revisions list `
        --service="$Service" `
        --project="$GoogleProjectId" `
        --region="$GcpRegion" `
        --format='value(metadata.name)' `
        --sort-by='metadata.creationTimestamp' `
        --filter="status.conditions.type:Active AND status.conditions.status:'False'") | Where-Object { $_ }

    if ($Revisions.Count -eq 0) {
        Write-Host "No inactive revisions found for $Service`n" -ForegroundColor Yellow
        continue
    }

    Write-Host "Found $($Revisions.Count) inactive revisions"

    # Confirm cleanup
    $Reply = Read-Host "Confirm cleanup for $Service? (y/n)"
    if ($Reply -match '^[Yy]$') {
        foreach ($Revision in $Revisions) {
            Write-Host "Deleting revision: $Revision" -ForegroundColor Red
            gcloud run revisions delete "$Revision" `
                --quiet `
                --project="$GoogleProjectId" `
                --region="$GcpRegion"
        }
        Write-Host "Completed cleanup for $Service`n" -ForegroundColor Green
    } else {
        Write-Host "Cleanup cancelled for $Service`n" -ForegroundColor Yellow
    }
}