# Copies the mod folder into the Workshop folder for in-game testing. Builds first unless -NoBuild is given.
# Usage: powershell -ExecutionPolicy Bypass -File tools\deploy.ps1 [-NoBuild]
param([switch]$NoBuild)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
if (-not $NoBuild) { & (Join-Path $PSScriptRoot 'build.ps1') }
$src = Join-Path $root 'mod'
$dst = Join-Path $env:USERPROFILE 'Zomboid\Workshop\TrueHordes'
# Keep the Workshop item id the game writes into workshop.txt after the first upload (else the next upload makes a new item).
$idLine = $null
$wt = Join-Path $dst 'workshop.txt'
if (Test-Path $wt) { $idLine = Get-Content $wt | Where-Object { $_ -match '^id=' } | Select-Object -First 1 }
if (Test-Path $dst) { Remove-Item -Recurse -Force $dst }
Copy-Item -Recurse $src $dst
if ($idLine) {
    $lines = Get-Content $wt | Where-Object { $_ -notmatch '^id=' }
    Set-Content -Path $wt -Value (@($lines[0], $idLine) + $lines[1..($lines.Count - 1)]) -Encoding ascii
}
Write-Host "Deployed to $dst"
