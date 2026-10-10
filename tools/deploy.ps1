# Copies the mod folder into the Workshop folder for in-game testing. Builds first unless -NoBuild is given.
# Usage: powershell -ExecutionPolicy Bypass -File tools\deploy.ps1 [-NoBuild]
param([switch]$NoBuild)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
if (-not $NoBuild) { & (Join-Path $PSScriptRoot 'build.ps1') }
$src = Join-Path $root 'mod'
$dst = Join-Path $env:USERPROFILE 'Zomboid\Workshop\TrueHordes'
if (Test-Path $dst) { Remove-Item -Recurse -Force $dst }
Copy-Item -Recurse $src $dst
Write-Host "Deployed to $dst"
