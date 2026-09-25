# ==============================================================
#  Port occupancy checker
# ==============================================================
#  Usage:
#    .\scripts\check-port.ps1                      # check default ports
#    .\scripts\check-port.ps1 -Port 5173           # check one port
#    .\scripts\check-port.ps1 -Port 5173 -Kill     # check and kill owner
#
#  Why this script exists:
#
#  1. Vite listens on IPv6 only  ([::1]:5173), NOT on 0.0.0.0.
#     So PowerShell's Get-NetTCPConnection -LocalPort 5173 MISSES it,
#     making the port look free while it is actually taken.
#     Always use netstat for this check.
#
#  2. IDEA / Maven / Vite often leave orphan java / node processes behind,
#     which cause "Port xxx already in use" on the next start.
#
#  NOTE: This file is intentionally ASCII-only.
#        Windows PowerShell 5.1 reads .ps1 as ANSI(GBK) when there is no BOM,
#        which turns any non-ASCII comment into garbage and breaks parsing.
#        Chinese documentation lives in md文档/ instead.
# ==============================================================

[CmdletBinding()]
param(
    [int[]]$Port = @(5173, 5174, 8080, 3307, 8000),
    [switch]$Kill
)

$ErrorActionPreference = "Continue"

$PortPurpose = @{
    5173 = "frontend Vite dev server (default)"
    5174 = "frontend Vite (fallback when 5173 is taken)"
    8080 = "backend Spring Boot"
    3307 = "MySQL container mapped port"
    8000 = "Chroma vector store"
}

function Get-PortOwners {
    param([int]$PortNumber)

    # Must use netstat: Get-NetTCPConnection misses IPv6-only listeners.
    $lines = netstat -ano | Select-String ":$PortNumber\s+\S+\s+LISTENING"
    if (-not $lines) { return @() }

    return $lines | ForEach-Object {
        $parts = $_.Line.Trim() -split '\s+'
        [PSCustomObject]@{
            Address = $parts[1]
            Pid     = [int]$parts[-1]
        }
    } | Sort-Object Pid -Unique
}

$anyFound = $false

foreach ($p in $Port) {
    $owners = Get-PortOwners -PortNumber $p
    $purpose = if ($PortPurpose.ContainsKey($p)) { $PortPurpose[$p] } else { "" }

    if (-not $owners) {
        Write-Host ("  {0,-6} FREE" -f $p) -ForegroundColor DarkGray
        continue
    }

    $anyFound = $true
    Write-Host ("  {0,-6} IN USE  ({1})" -f $p, $purpose) -ForegroundColor Yellow

    foreach ($o in $owners) {
        $proc = Get-Process -Id $o.Pid -ErrorAction SilentlyContinue
        if (-not $proc) {
            Write-Host ("         {0}  PID={1}  (process already exited)" -f $o.Address, $o.Pid) -ForegroundColor DarkGray
            continue
        }

        Write-Host ("         {0}  PID={1}  {2}  started {3}" -f `
            $o.Address, $o.Pid, $proc.ProcessName, $proc.StartTime)

        if ($Kill) {
            try {
                Stop-Process -Id $o.Pid -Force -ErrorAction Stop
                Write-Host "         -> killed" -ForegroundColor Green
            } catch {
                Write-Host ("         -> kill failed: {0}" -f $_.Exception.Message) -ForegroundColor Red
            }
        }
    }
}

Write-Host ""

if (-not $anyFound) {
    Write-Host "All checked ports are free." -ForegroundColor Green
} elseif ($Kill) {
    Write-Host "Kill attempted. Re-run this script to confirm." -ForegroundColor Green
} else {
    Write-Host "To kill the owner:" -ForegroundColor Cyan
    Write-Host "    .\scripts\check-port.ps1 -Port 5173 -Kill"
    Write-Host ""
    Write-Host "Tip: Vite uses strictPort:false, so it auto-switches 5173 -> 5174." -ForegroundColor DarkGray
    Write-Host "     You may not need to kill anything - just read the printed URL." -ForegroundColor DarkGray
}
