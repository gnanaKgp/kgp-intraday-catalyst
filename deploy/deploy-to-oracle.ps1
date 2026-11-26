# ==============================================================================
# NSE Catalyst Terminal - 1-Click Fast Deployment to Oracle Cloud VM
# Run this from Windows PowerShell whenever you make code changes:
#   .\deploy\deploy-to-oracle.ps1 -Build
# ==============================================================================
param(
    [switch]$Build = $false
)

$KEY_PATH = "C:\Users\gnana\Documents\NSE Catalyst\ssh-key-2026-10-24.key"
$VM_HOST = "ubuntu@130.210.51.233"
$JAR_NAME = "nse-catalyst-terminal-1.0.0.jar"
$JAR_PATH = "target\$JAR_NAME"
$REMOTE_DIR = "/opt/nse-catalyst-terminal"
$SERVICE_NAME = "nse-catalyst-terminal"

# Auto-configure JAVA_HOME for PowerShell if not set
if (-not $env:JAVA_HOME -and (Test-Path "C:\Program Files\Java\jdk-17.0.1")) {
    $env:JAVA_HOME = "C:\Program Files\Java\jdk-17.0.1"
}

Write-Host "========================================================" -ForegroundColor Cyan
Write-Host "[DEPLOY] Deploying NSE Catalyst Terminal to Oracle Cloud VM..." -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Cyan

# 1. Optionally compile fresh JAR if requested or if missing
if ($Build -or (-not (Test-Path $JAR_PATH))) {
    Write-Host "[BUILD] Building fresh JAR using Maven..." -ForegroundColor Yellow
    mvn clean package -DskipTests
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[ERROR] Maven build failed! Check errors above." -ForegroundColor Red
        exit 1
    }
}

# 2. Verify JAR exists
if (-not (Test-Path $JAR_PATH)) {
    Write-Host "[ERROR] $JAR_PATH not found!" -ForegroundColor Red
    Write-Host "[TIP] Run with -Build (.\deploy\deploy-to-oracle.ps1 -Build)" -ForegroundColor Yellow
    exit 1
}

$jarSize = (Get-Item $JAR_PATH).Length / 1MB
Write-Host "[OK] Found JAR: $JAR_PATH ($([math]::Round($jarSize, 2)) MB)" -ForegroundColor Green

# 3. Upload JAR to VM
Write-Host "[UPLOAD] Uploading JAR to VM..." -ForegroundColor Yellow
scp -i "$KEY_PATH" "$JAR_PATH" "${VM_HOST}:/home/ubuntu/$JAR_NAME"
if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] Upload failed! Check network or SSH key path." -ForegroundColor Red
    exit 1
}

# 4. Move to /opt and restart service
Write-Host "[SERVICE] Updating service on VM and restarting..." -ForegroundColor Yellow
ssh -i "$KEY_PATH" "$VM_HOST" "sudo mv /home/ubuntu/$JAR_NAME $REMOTE_DIR/$JAR_NAME && sudo chown ubuntu:ubuntu $REMOTE_DIR/$JAR_NAME && sudo systemctl restart $SERVICE_NAME && sleep 4 && sudo systemctl status $SERVICE_NAME --no-pager"

Write-Host "`n========================================================" -ForegroundColor Green
Write-Host "[SUCCESS] Deployment Complete! Terminal is running live." -ForegroundColor Green
Write-Host "[URL] Live Dashboard: http://130.210.51.233:8081/" -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Green

