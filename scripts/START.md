# SageRAG 本地启动

## 1. 启动基础设施

```powershell
docker compose up -d postgres redis rocketmq-namesrv rocketmq-broker
```

首次启动会自动执行 `resources/database/schema_pg.sql` 和 `init_data_pg.sql`。
如果修改了 SQL 需要重建空库：

```powershell
docker compose down -v
docker compose up -d postgres redis rocketmq-namesrv rocketmq-broker
```

## 2. 配置模型 Key

```powershell
Copy-Item .env.example .env
notepad .env
```

至少填写 `BAILIAN_API_KEY`（chat/embedding/rerank 一次配齐），或使用本地 Ollama。

## 3. 启动后端（端口 9090）

```powershell
mvn -o install -DskipTests
.\scripts\start-backend.ps1
```

## 4. 启动 MCP Server（端口 9099，可选但建议）

```powershell
.\scripts\start-mcp.ps1
```

## 5. 启动前端（端口 5173）

```powershell
.\scripts\start-frontend.ps1
```

访问 http://localhost:5173 ，默认账号 `admin / admin`。

## 6. 导入演示文档

登录后台 → 知识库管理 → 进入「员工手册与制度」或「业务系统手册」→ 上传
`resources/demo-docs/` 下的 Markdown → 点击「分块」。

然后回到聊天页，用示例问题试问。
