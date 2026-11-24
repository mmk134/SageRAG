# 离线评测

用于回归验证「改完检索/提示词后，问答质量有没有变差」。

## 准备

1. 启动后端、知识库并完成演示文档分块；
2. Python 3.9+（只用标准库，无需安装依赖）；
3. `questions.jsonl` 里的 `keywords` 按预期答案维护，命中规则为**全部关键词出现**。

## 运行

```bash
python eval/run_eval.py \
  --base-url http://localhost:9090/api/sagerag \
  --questions eval/questions.jsonl \
  --output eval/report.json
```

参数：

| 参数 | 说明 |
|---|---|
| `--limit` | 只跑前 N 条，联调时用 |
| `--timeout` | 单条问答超时秒数，默认 180 |
| `--username/--password` | 登录账号，默认 admin/admin |

## 输出

终端打印每条问题的命中、首字延迟、总耗时；`--output` 会额外保存完整 JSON，包含回答原文，便于人工复核。

指标口径：

- **hit_rate**：全部关键词命中的问题占比；
- **avg_first_token_s**：从发起请求到第一个非 think 增量的平均耗时；
- **avg_total_s**：单条问答总耗时平均值。

## 说明

这是轻量回归脚本，不是 RAGAS 那种 LLM-as-judge 评测。关键词命中只能发现明显退化；要评估忠实度、相关性，建议在报告基础上接人工抽检或 RAGAS。
