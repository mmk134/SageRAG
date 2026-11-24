# SageRAG 架构说明

## 1. 模块边界

```text
bootstrap   主应用：对话、检索、意图、知识库、后台、用户
framework   横切能力：异常、Result、SSE、用户上下文、幂等、Trace、MQ 抽象
infra-ai    模型接入：Chat / Embedding / Rerank 的供应商实现、路由与熔断
mcp-server  独立 MCP Server，通过 HTTP 暴露工具
frontend    React 聊天页 + 管理后台
```

`framework` 与 `infra-ai` 是纯类库，没有自动装配文件，依赖 `bootstrap` 的组件扫描生效；所有模块共用 `com.sagerag` 根包。

## 2. 一次问答

入口：`GET /api/sagerag/rag/v3/chat`（SSE），停止：`POST /rag/v3/stop`。

```text
ChatQueueLimiter            Redis 公平排队，拿不到令牌直接返回排队/拒绝事件
  -> StreamChatTraceRunner  创建 Trace
  -> StreamChatPipeline
       1. loadMemory         最近 N 轮 + 摘要
       2. rewriteQuery       查询改写、拆子问题
       3. resolveIntents     意图分类（并行）
       4. handleGuidance     歧义引导（可短路）
       5. handleSystemOnly   系统类意图（可短路）
       6. retrieve           KB 多路检索 + MCP 工具
       7. 空召回短路
       8. streamRagResponse  拼 Prompt，流式输出
```

Prompt 模板放在 `resources/prompt/*.st`，Mustache 占位符，按 `--- section: xxx ---` 分节。

## 3. 检索

```text
RetrievalEngine
  ├─ MultiChannelRetrievalEngine
  │    ├─ IntentDirectedSearchChannel  (priority 1)
  │    └─ VectorGlobalSearchChannel    (priority 10，置信度低时兜底)
  │    └─ DeduplicationPostProcessor -> RerankPostProcessor
  └─ MCP：参数抽取 -> 工具调用
```

- 通道通过 `List<SearchChannel>` 注入，`getPriority()` 排序、`isEnabled()` 决定是否参与；
- 后处理器通过 `List<SearchResultPostProcessor>` 注入，`getOrder()` 决定顺序；
- 新增通道/后处理器只需加 `@Component`，不改注册表；
- 集合级并行走 `AbstractParallelRetriever` 模板方法。

向量存储只有 `PgRetrieverService` / `PgVectorStoreService`，底层是 PostgreSQL + pgvector，表和索引在 `schema_pg.sql` 的 `t_knowledge_vector`。

## 4. 意图

三级树：DOMAIN → CATEGORY → TOPIC，只有叶子参与分类，每个叶子有 `kind`：

| kind | 行为 |
|---|---|
| KB | 走知识库检索，带 `collectionName`、`topK`、可选 `promptTemplate` |
| MCP | 走工具调用，带 `mcpToolId` |
| SYSTEM | 直接回复，不检索 |

数据源是 `t_intent_node`，Redis 缓存 `sagerag:intent:tree`。首次启动时 `IntentTreeInitializer` 在表为空时从 `IntentTreeFactory` 导入默认树；后台可维护。

分类流程：`DefaultIntentClassifier` 拉叶子 → 渲染 `intent-classifier.st` → LLM 打分 → 解析 JSON → `IntentResolver` 按阈值过滤、单问题 top-3、全局 cap 3，最后合并成 KB / MCP 两组。

## 5. 文档入库

```text
上传 (file / url)
  -> 落库 + 发 RocketMQ 事务消息
  -> KnowledgeDocumentChunkConsumer
  -> Tika 解析
  -> ChunkingStrategyFactory 按 chunkStrategy 创建策略
  -> ChunkEmbeddingService 批量 embedding
  -> 事务：写 t_knowledge_chunk + t_knowledge_vector，更新文档状态
```

分块策略：

| 策略 | 参数 |
|---|---|
| `fixed_size` | `chunkSize`、`overlapSize`（`-1` 表示不分块） |
| `structure_aware` | `targetChars`、`maxChars`、`minChars`、`overlapChars` |

分块日志 `t_knowledge_document_chunk_log` 记录提取、分块、向量化、持久化耗时，前端「分块详情」直接展示。

## 6. 模型接入与容错

业务只依赖 `LLMService` / `EmbeddingService` / `RerankService`，实现是 `Routing*Service`：

- `ModelSelector` 按 `ai.*.candidates.priority` 排序；
- `ModelRoutingExecutor.executeWithFallback()` 跳过不健康候选；
- `ModelHealthStore` 三态熔断：失败 N 次 OPEN，冷却后半开探测，成功恢复 CLOSED。

新增供应商：补 `ModelProvider` 枚举 → 实现 `ChatClient`（可继承 `AbstractOpenAIStyleChatClient`）→ 配置 `ai.providers.*` 和候选。

## 7. 会话记忆

- `history-keep-turns` 控制保留轮数；
- 超过 `summary-start-turns` 后异步生成摘要，写 `t_conversation_summary`；
- 请求时拼「摘要 + 最近消息」，控制 token；
- 摘要线程池单独隔离，避免影响主链路。

## 8. 稳定性与可观测性

- **排队限流**：`ChatQueueLimiter` + Redisson 公平锁（Lua 原子领取），排队状态通过 SSE 推送；
- **上传限流**：`UploadRateLimitFilter` + 分布式信号量；
- **Trace**：`@RagTraceNode` 切面写 `t_rag_trace_run` / `t_rag_trace_node`，后台可按 traceId 看每个阶段耗时；
- **幂等**：`@IdempotentSubmit`（Redisson 锁）、`@IdempotentConsume`（Redis Lua）。

## 9. 数据表

| 表 | 用途 |
|---|---|
| `t_user` | 用户 |
| `t_conversation` / `t_message` / `t_conversation_summary` | 会话、消息、摘要 |
| `t_message_feedback` | 回答反馈 |
| `t_knowledge_base` / `t_knowledge_document` / `t_knowledge_chunk` | 知识库、文档、分块 |
| `t_knowledge_vector` | pgvector 向量 |
| `t_knowledge_document_chunk_log` | 分块日志 |
| `t_knowledge_document_schedule*` | URL 文档定时刷新 |
| `t_intent_node` | 意图树 |
| `t_query_term_mapping` | 查询词归一化 |
| `t_rag_trace_run` / `t_rag_trace_node` | 链路追踪 |
| `t_sample_question` | 示例问题 |

## 10. 扩展点

| 想做什么 | 做什么 |
|---|---|
| 新增检索通道 | 实现 `SearchChannel` + `@Component` |
| 新增后处理器 | 实现 `SearchResultPostProcessor` + `@Component` |
| 新增 MCP 工具 | 在 `mcp-server` 加 `*McpExecutor` 并暴露 `SyncToolSpecification` |
| 新增分块策略 | 实现 `ChunkingStrategy` + 在 `ChunkingMode` 注册 |
| 新增模型供应商 | 补 `ModelProvider` + `ChatClient` + 配置 |
| 换向量库 | 实现 `RetrieverService` / `VectorStoreService` |
