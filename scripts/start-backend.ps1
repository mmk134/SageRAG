param(
    [switch]$Rebuild
)

$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

if ($Rebuild -or -not (Test-Path "bootstrap\target\bootstrap-0.0.1-SNAPSHOT.jar")) {
    Write-Host "构建后端..." -ForegroundColor Cyan
    mvn -o -DskipTests package
}

Write-Host "启动后端: http://localhost:9090/api/sagerag" -ForegroundColor Green
java -jar bootstrap\target\bootstrap-0.0.1-SNAPSHOT.jar
