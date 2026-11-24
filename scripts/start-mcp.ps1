$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

if (-not (Test-Path "mcp-server\target\mcp-server-0.0.1-SNAPSHOT.jar")) {
    Write-Host "构建 MCP Server..." -ForegroundColor Cyan
    mvn -o -pl mcp-server -am -DskipTests package
}

Write-Host "启动 MCP Server: http://localhost:9099" -ForegroundColor Green
java -jar mcp-server\target\mcp-server-0.0.1-SNAPSHOT.jar
