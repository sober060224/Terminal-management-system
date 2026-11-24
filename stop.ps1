# Stop the backend (8089) and the workspace MySQL instance (3307).
# The system MySQL80 service on 3306 is left alone.
# ASCII-only output on purpose: Windows cmd uses the system ANSI code page.

$ErrorActionPreference = 'SilentlyContinue'

foreach ($port in 8089, 3307) {
    $pids = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty OwningProcess -Unique
    foreach ($procId in $pids) {
        if ($procId -and $procId -ne 0 -and $procId -ne $PID) {
            Write-Host "stopping port $port, PID $procId ..."
            Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
        }
    }
}

# Fallback: backend process may be alive without holding the port yet
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -like '*FerryApplication*' } |
    ForEach-Object {
        Write-Host "stopping backend process PID $($_.ProcessId) ..."
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
    }

Start-Sleep -Seconds 2

foreach ($port in 8089, 3307) {
    $still = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue
    if ($still) { Write-Host "[WARN] port $port is still listening" }
    else { Write-Host "port $port stopped" }
}
