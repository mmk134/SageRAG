# 面试讲解提纲

> 用法：先按「一句话版本」讲，再按追问展开。所有结论都能在代码里找到对应位置。

## 0. 一句话版本

SageRAG 是一个可本地部署的通用知识库问答平台：文档上传后异步解析、分块、向量化；提问时先做查询改写和意图识别，再并行跑「意图定向检索 + 全局向量检索」，去重、Rerank 后拼 Prompt 流式输出，同时记录 Trace 和会话记忆。

## 1. 整体链路（必问）

入口 `RAGChatController` 的 `GET /rag/v3/chat`，返回 `SseEmitter`。`StreamChatPipeline` 是骨架，8 个阶段：

`loadMemory → rewriteQuery → resolveIntents → handleGuidance → handleSystemOnly → retrieve → 空召回短路 → streamRagResponse`

三个短路点：歧义引导、系统意图、空召回。这样避免无意义的模型调用。

## 2. 为什么用 pgvector 而不是 Milvus

- 数据量在百万 chunk 内，HNSW 足够；
- 向量和业务表在同一个 PostgreSQL，事务、备份、权限一套体系；
- 少维护一个中间件，本地一键起；
- 保留了 `RetrieverService` / `VectorStoreService` 抽象，换库只加实现。

可追问「pgvector 的局限」：超大规模、多副本、复杂过滤性能不如专用向量库；过滤条件多时要注意索引选择。

## 3. 多路检索怎么做的

`MultiChannelRetrievalEngine`：

1. 过滤 `isEnabled` 的通道，按 priority 排序；
2. `CompletableFuture` 并行执行，单通道异常只记日志、返回空结果；
3. 后处理链：去重（按 chunkId）→ Rerank；
4. 返回统一 `RetrievedChunk`。

两个通道：意图定向（按命中的 collection 检索）和全局兜底（意图置信度低于阈值时）。

## 4. 意图树为什么是三级

DOMAIN → CATEGORY → TOPIC。只有叶子参与分类，父节点用于组织和后台展示。分类时把叶子渲染进 Prompt，让模型打分，再按阈值 0.35 过滤、单问题 top-3、全局最多 3 个意图，最后拆成 KB / MCP 两组。

这样做的好处：意图规模变大时只增加叶子，Prompt 仍可控；坏处：层级设计依赖业务梳理。

## 5. SSE 流式怎么实现

后端 `SseEmitterSender` 封装 `SseEmitter`，CAS 保证只关闭一次；事件类型有 `meta` / `message` / `finish` / `done` / `cancel` / `error`。前端 `useStreamResponse` 用 `fetch + ReadableStream` 自己解析 SSE，支持 AbortController 中断和指数退避重试。

停止：前端拿 `taskId` 调 `/rag/v3/stop`，后端 `StreamTaskManager` 取消任务并发 `cancel` 事件。

## 6. 排队限流为什么不用 Guava RateLimiter

单机限流在多实例下不准。这里用 Redis + Lua 做公平排队：按到达顺序分配令牌，拿不到就轮询等待，超时返回排队失败。Lua 保证「判断 + 领取」原子性，避免并发超卖。

## 7. 熔断器状态机

`ModelHealthStore`：CLOSED 正常；连续失败达到阈值 → OPEN，期间直接跳过；冷却后进入 HALF_OPEN 放一个探测请求，成功回 CLOSED，失败继续 OPEN。每个模型独立计数，路由层按候选顺序降级。

## 8. 文档入库为什么用 MQ

上传接口只做落库 + 发消息，解析/分块/embedding 是重活，放同步会超时。用 RocketMQ 事务消息：先发半消息，本地事务更新文档状态，再提交；消费失败可重试。分块日志记录每个阶段耗时，方便定位慢在哪。

## 9. 分块策略

- `fixed_size`：固定字符数 + overlap，适合结构弱的文本；
- `structure_aware`：按标题/段落边界切，控制 target/max/min，适合 Markdown、制度文档。

参数存在 `chunk_config` JSONB 里，策略工厂 `ChunkingStrategyFactory` 按 `chunkStrategy` 创建。

## 10. 会话记忆

保留最近 N 轮原文；超过阈值后异步把更早的对话压缩成摘要存 `t_conversation_summary`。请求时拼「摘要 + 最近消息」，兼顾连贯性和 token 成本。摘要线程池独立，避免拖慢主链路。

## 11. MCP 怎么接的

`mcp-server` 是独立进程，用 MCP Java SDK 暴露工具；`bootstrap` 作为 Client 启动时 `listTools()`，把每个远程工具包装成 `McpClientToolExecutor`。调用前用 LLM 按工具 JSON Schema 抽参数，再执行。

## 12. 怎么证明改了没变差

`eval/run_eval.py` 跑固定问题集，统计关键词命中、首字延迟、总耗时。关键词命中只能发现明显退化，忠实度还需要人工抽检或接 RAGAS。改检索/提示词前后各跑一次，对比报告。

## 13. 遇到过什么坑（讲一个真实的）

- SSE 的 emitter 被多个线程写，关闭时容易重复 complete；用 CAS + 状态位保证只关一次。
- 意图为空时全局检索兜底，导致答非所问；加了置信度阈值和「空召回」短路。
- 分块参数和策略不匹配（比如 structure_aware 传了 chunkSize）会静默用默认值；现在上传前按策略的 defaultConfig 校验必填字段。

## 14. 如果 QPS 涨 10 倍

1. 模型侧：候选池扩容 + 熔断隔离 + 按意图分流到小模型；
2. 检索侧：通道超时降级、缓存热点 query 的 embedding/召回、pgvector 加只读副本；
3. 接入侧：排队限流阈值上调、SSE 连接数上限、网关层限流；
4. 入库侧：MQ 消费并发和分片，embedding 批大小调优。

## 15. 这个项目你做了什么（如实说）

在 Ragent AI（Apache-2.0）的基础上做二次开发：

- 重构包结构和品牌，接口前缀统一；
- 删掉数据库驱动的 Pipeline 入库，改成参数化分块，链路更短；
- 移除 Milvus，只保留 pgvector；
- 新增本地文件存储实现，S3 变可选；
- 新增意图树空库初始化、演示知识库和示例问题；
- 新增 Docker Compose、离线评测脚本、演示文档；
- 前端裁掉数据通道模块，修了会话接口的类型问题，`tsc` 全量通过。

如果面试官追问「哪些是上游的」，就按这个清单说，别硬拗从零自研。
