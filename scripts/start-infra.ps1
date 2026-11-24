$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

Write-Host "启动 PostgreSQL / Redis / RocketMQ..." -ForegroundColor Cyan
docker compose up -d postgres redis rocketmq-namesrv rocketmq-broker

Write-Host "等待健康检查..." -ForegroundColor Cyan
Start-Sleep -Seconds 5
docker compose ps
