# scripts/kill-port.ps1
# Kills the process holding a specific port, only if it's a Java process.
# Safe to run even when the port is free.

param(
    [Parameter(Mandatory=$true)]
    [int]$Port
)

$ids = netstat -ano | Select-String ":$Port\s+.*LISTENING" | ForEach-Object {
    ($_ -split '\s+')[-1]
} | Sort-Object -Unique

$killed = 0
foreach ($id in $ids) {
    if ($id -and $id -ne "0") {
        $proc = Get-Process -Id $id -ErrorAction SilentlyContinue
        if ($proc -and $proc.ProcessName -eq "java") {
            Write-Host "Killing PID $id (java) on port $Port" -ForegroundColor Yellow
            Stop-Process -Id $id -Force
            $killed++
        } else {
            Write-Host "Port $Port held by PID $id - skipping (not java)" -ForegroundColor Red
        }
    }
}

if ($killed -eq 0) {
    Write-Host "Port $Port is free" -ForegroundColor Green
} else {
    Write-Host "Killed $killed java process on port $Port" -ForegroundColor Green
}
