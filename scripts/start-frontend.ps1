$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..\frontend")

if (-not (Test-Path "node_modules")) {
    Write-Host "安装前端依赖..." -ForegroundColor Cyan
    npm install
}

Write-Host "启动前端: http://localhost:5173" -ForegroundColor Green
npm run dev
