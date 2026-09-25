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

# Only sockets where :8085 is the LOCAL end and the state is LISTENING belong to
# the service. The previous version scraped every netstat line containing the
# port, which also matched each client merely *connected* to it and killed that
# process instead — including the gateway, which holds an established connection
# to the ledger while proxying requests to it. It took the gateway down with it.
$listeners = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty OwningProcess -Unique |
    Where-Object { $_ -gt 0 }

if ($listeners) {
    foreach ($procId in $listeners) {
        $proc = Get-CimInstance Win32_Process -Filter "ProcessId = $procId" -ErrorAction SilentlyContinue
        if (-not $proc) { continue }
        if ($proc.Name -ne 'java.exe') {
            # A non-JVM holding 8085 is not this service's zombie. Report it and
            # leave it alone rather than guessing.
            Write-Host "PID $procId ($($proc.Name)) is listening on $port but is not a JVM - not killing it" -ForegroundColor Red
            Write-Host "  $($proc.CommandLine)" -ForegroundColor DarkGray
            continue
        }
        $cmd = $proc.CommandLine
        $shown = if ($cmd.Length -gt 140) { $cmd.Substring(0, 140) + '...' } else { $cmd }
        Write-Host "Killing PID $procId listening on $port" -ForegroundColor Yellow
        Write-Host "  $shown" -ForegroundColor DarkGray
        Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
    }
    Start-Sleep -Seconds 2
} else {
    Write-Host "Nothing is listening on $port" -ForegroundColor Green
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
