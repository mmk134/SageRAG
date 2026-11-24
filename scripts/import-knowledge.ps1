param(
    [string]$BaseUrl = "http://127.0.0.1:9090/api/sagerag",
    [string]$Username = "admin",
    [string]$Password = "admin"
)

$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

$loginBody = @{ username = $Username; password = $Password } | ConvertTo-Json
$login = Invoke-RestMethod -Uri "$BaseUrl/auth/login" -Method Post -ContentType "application/json" -Body $loginBody
$token = $login.data.token
$headers = @{ Authorization = $token }

$kbResp = Invoke-RestMethod -Uri "$BaseUrl/knowledge-base" -Headers $headers
$kbs = @($kbResp.data.records)
$groupKb = ($kbs | Where-Object { $_.name -eq "员工手册与制度" } | Select-Object -First 1).id
$bizKb = ($kbs | Where-Object { $_.name -eq "业务系统手册" } | Select-Object -First 1).id
if (-not $groupKb -or -not $bizKb) {
    throw "未找到预置知识库，请确认 init_data_pg.sql 已执行"
}

$docRoot = Join-Path (Get-Location) "resources\demo-docs\knowledge"
if (-not (Test-Path $docRoot)) {
    throw "未找到演示文档目录: $docRoot"
}

$summary = @()
foreach ($file in Get-ChildItem $docRoot -Recurse -File -Filter *.md) {
    $relative = $file.FullName.Substring($docRoot.Length + 1)
    $top = ($relative -split '\\')[0]
    $kbId = if ($top -eq "group") { $groupKb } else { $bizKb }

    $page = Invoke-RestMethod -Uri "$BaseUrl/knowledge-base/$kbId/docs?current=1&size=200" -Headers $headers
    $exists = @($page.data.records | Where-Object { $_.docName -eq $file.Name }).Count -gt 0
    if ($exists) {
        Write-Host "[skip] $relative" -ForegroundColor DarkGray
        $summary += [pscustomobject]@{ file = $relative; status = "skipped" }
        continue
    }

    $raw = curl.exe -s -X POST "$BaseUrl/knowledge-base/$kbId/docs/upload" `
        -H "Authorization: $token" `
        --form-string "sourceType=file" `
        --form-string "chunkStrategy=structure_aware" `
        -F "file=@$($file.FullName)"
    $json = $raw | ConvertFrom-Json
    if ($json.code -ne "0") {
        Write-Host "[fail] $relative : $($json.message)" -ForegroundColor Red
        $summary += [pscustomobject]@{ file = $relative; status = "upload-failed" }
        continue
    }

    $docId = $json.data.id
    $null = Invoke-RestMethod -Uri "$BaseUrl/knowledge-base/docs/$docId/chunk" -Method Post -Headers $headers
    Write-Host "[ok] $relative -> $docId" -ForegroundColor Green
    $summary += [pscustomobject]@{ file = $relative; status = "uploaded"; docId = $docId }
}

$summary | Format-Table -AutoSize
Write-Host "完成。等待异步分块后可在后台查看文档状态。" -ForegroundColor Cyan
