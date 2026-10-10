# Compiles the Java core with JDK 25, runs the offline tests, and packages TrueHordes.jar into mod\.
# Usage (from anywhere): powershell -ExecutionPolicy Bypass -File tools\build.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$jdk = Join-Path $env:USERPROFILE '.jdks\jdk-25.0.4.1+1\bin'
$game = 'C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid'
$gameJar = Join-Path $game 'projectzomboid.jar'
$cp = "$gameJar;$(Join-Path $game 'ZombieBuddy.jar')"
$out = Join-Path $root 'build'
$jarOut = Join-Path $root 'mod\Contents\mods\TrueHordes\42\media\java\TrueHordes.jar'

Push-Location $root
try {
    if (Test-Path $out) { Remove-Item -Recurse -Force $out }
    New-Item -ItemType Directory -Force (Join-Path $out 'classes'), (Join-Path $out 'test') | Out-Null

    $src = @(Get-ChildItem (Join-Path $root 'java\src') -Recurse -Filter *.java | ForEach-Object FullName)
    & (Join-Path $jdk 'javac.exe') --release 25 -encoding UTF-8 -Xlint:-options -cp $cp -d (Join-Path $out 'classes') @src
    if ($LASTEXITCODE -ne 0) { throw 'compile failed' }

    $tests = @(Get-ChildItem (Join-Path $root 'java\test') -Recurse -Filter *.java | ForEach-Object FullName)
    & (Join-Path $jdk 'javac.exe') --release 25 -encoding UTF-8 -Xlint:-options -cp "$(Join-Path $out 'classes');$cp" -d (Join-Path $out 'test') @tests
    if ($LASTEXITCODE -ne 0) { throw 'test compile failed' }

    & (Join-Path $jdk 'java.exe') -cp "$(Join-Path $out 'test');$(Join-Path $out 'classes')" truehordes.test.TestRunner $gameJar
    if ($LASTEXITCODE -ne 0) { throw 'tests failed' }

    New-Item -ItemType Directory -Force (Split-Path $jarOut) | Out-Null
    & (Join-Path $jdk 'jar.exe') --create --file $jarOut -C (Join-Path $out 'classes') .
    if ($LASTEXITCODE -ne 0) { throw 'jar failed' }
    Write-Host "Built $jarOut"
} finally {
    Pop-Location
}
