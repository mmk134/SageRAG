# CLAUDE.md

本文件给后续参与开发的 AI / 工程师提供项目约定。

## 项目概览

SageRAG 是一个本地可部署的通用知识库问答平台：文档入库 → 多路检索 → 意图路由 → 流式生成 → 会话记忆 / Trace。仓库注释、文档、Prompt 均为中文。

## 常用命令

```bash
# 基础设施（PostgreSQL+pgvector / Redis / RocketMQ）
docker compose up -d postgres redis rocketmq-namesrv rocketmq-broker

# 后端：首次 install，再运行 jar（9090，context-path=/api/sagerag）
mvn -o install -DskipTests
java -jar bootstrap/target/bootstrap-0.0.1-SNAPSHOT.jar

# MCP Server：9099
mvn -o -pl mcp-server -am -DskipTests package
java -jar mcp-server/target/mcp-server-0.0.1-SNAPSHOT.jar

# 编译 / 打包
mvn -o -DskipTests compile
mvn -o -DskipTests package

# 前端：5173
cd frontend
npm install
npm run dev
npm run build
npx tsc -b
```

- `bootstrap` 的测试是 `@SpringBootTest`，需要 PostgreSQL / Redis / RocketMQ 先启动。
- 前端没有单测；`npx tsc -b` 是类型检查，`npm run build` 只跑 Vite。
- spotless 的 `apply` 绑定在 compile 阶段，会改写 Java 文件、补版权头，属正常现象。

## 模块

```text
bootstrap   主应用（业务都在这）
framework   横切：异常、Result、SSE、UserContext、幂等、Trace、MQ
infra-ai    模型供应商：Chat / Embedding / Rerank 路由与熔断
mcp-server  独立 MCP Server
frontend    React 聊天页 + 后台
```

`framework` / `infra-ai` 没有自动装配文件，靠 `com.sagerag` 包扫描生效。新增 Bean 必须在 `com.sagerag` 下。

## 核心链路

`RAGChatController` → `ChatQueueLimiter` → `StreamChatPipeline`（8 阶段）→ `LLMService.streamChat`。

检索：`RetrievalEngine` → `MultiChannelRetrievalEngine`（通道并行）→ 去重 → Rerank。
意图：`t_intent_node` 三级树，叶子分 KB / MCP / SYSTEM；首次启动由 `IntentTreeInitializer` 灌默认树。
入库：上传 → RocketMQ → `KnowledgeDocumentChunkConsumer` → Tika 解析 → 参数化分块 → embedding → `t_knowledge_chunk` + `t_knowledge_vector`。

## 约定

- 新增检索通道：实现 `SearchChannel` + `@Component`；新增后处理器：实现 `SearchResultPostProcessor` + `@Component`。
- 新增分块策略：实现 `ChunkingStrategy`，在 `ChunkingMode` 注册。
- 新增模型供应商：补 `ModelProvider` + `ChatClient` + `application.yaml` 配置。
- 线程池集中在 `ThreadPoolExecutorConfig`，用 TTL 包装，不要新建裸 `ExecutorService`。
- 入库只支持 `chunk` 模式（`chunkStrategy` + `chunkConfig`），没有 Pipeline 编排。
- 向量库只有 pgvector，没有 Milvus 分支。

## 配置

单一 `bootstrap/src/main/resources/application.yaml`；本地敏感配置放项目根 `.env`（已 gitignore），通过 `spring.config.import` 读取。模型 Key：`BAILIAN_API_KEY` / `SILICONFLOW_API_KEY`。

## 数据

建表 `resources/database/schema_pg.sql`，初始化 `init_data_pg.sql`，MCP/系统意图 `extra_intents.sql`，演示文档 `resources/demo-docs/`（批量入库脚本 `scripts/import-knowledge.ps1`）。Docker 首次启动会自动执行前三个 SQL。
