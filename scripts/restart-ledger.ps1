# Restart ledger-service cleanly: kills any zombie process on 8085, rebuilds, restarts.
#
# This exists because the recurring failure is a live, orphaned JVM still holding
# port 8085 — after a suspend/resume, or a second instance started by hand — not a
# socket in TIME_WAIT. Spring's graceful shutdown cannot help with that: it drains
# in-flight requests when the process is asked to stop, and says nothing about a
# process nobody asked to stop. So the port is cleared explicitly here.
$port = 8085
$jar = "ledger-service\target\ledger-service-0.0.1-SNAPSHOT.jar"

Write-Host "Checking port $port..." -ForegroundColor Cyan
$existing = netstat -ano | Select-String ":$port\s" | ForEach-Object {
    ($_ -split '\s+')[-1]
} | Sort-Object -Unique | Where-Object { $_ -match '^\d+$' -and $_ -ne '0' }

if ($existing) {
    foreach ($procId in $existing) {
        Write-Host "Killing PID $procId holding port $port" -ForegroundColor Yellow
        Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
    }
    Start-Sleep -Seconds 2
}

Write-Host "Building ledger-service..." -ForegroundColor Cyan
mvn -f ledger-service/pom.xml clean package -DskipTests
if ($LASTEXITCODE -ne 0) {
    Write-Host "Build failed. Aborting." -ForegroundColor Red
    exit 1
}

Write-Host "Starting ledger-service..." -ForegroundColor Cyan
Start-Process java -ArgumentList "-jar",$jar -WindowStyle Minimized

Write-Host "Waiting for health check..." -ForegroundColor Cyan
for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Seconds 2
    try {
        $r = Invoke-WebRequest -Uri "http://localhost:8085/actuator/health" -UseBasicParsing -TimeoutSec 2
        if ($r.StatusCode -eq 200) {
            Write-Host "Ledger-service is UP on port $port" -ForegroundColor Green
            exit 0
        }
    } catch { }
}
Write-Host "Ledger-service failed to start in 60s. Check the console." -ForegroundColor Red
exit 1
