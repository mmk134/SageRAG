#!/usr/bin/env python3
"""SageRAG 离线评测脚本（仅依赖 Python 标准库）。

流程：登录取 token -> 逐条调用 SSE 问答 -> 解析增量 -> 统计关键词命中/首字延迟/总耗时。
用法：
    python eval/run_eval.py --base-url http://localhost:9090/api/sagerag \
        --questions eval/questions.jsonl
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


def post_json(url: str, payload: dict, token: str | None = None) -> dict:
    data = json.dumps(payload).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = token
    req = urllib.request.Request(url, data=data, headers=headers, method="POST")
    with urllib.request.urlopen(req, timeout=30) as resp:
        body = json.loads(resp.read().decode("utf-8"))
    if body.get("code") != "0":
        raise RuntimeError(f"登录失败: {body.get('message')}")
    return body["data"]


def stream_chat(base_url: str, token: str, question: str, timeout: int) -> tuple[str, float, float]:
    """返回 (回答文本, 首字延迟秒, 总耗时秒)。"""
    url = f"{base_url}/rag/v3/chat?question={urllib.parse.quote(question)}"
    req = urllib.request.Request(
        url,
        headers={"Authorization": token, "Accept": "text/event-stream"},
        method="GET",
    )
    started = time.perf_counter()
    first_token_at: float | None = None
    answer: list[str] = []
    event = "message"

    with urllib.request.urlopen(req, timeout=timeout) as resp:
        for raw in resp:
            line = raw.decode("utf-8", errors="replace").strip()
            if not line:
                event = "message"
                continue
            if line.startswith(":"):
                continue
            if line.startswith("event:"):
                event = line[6:].strip()
                continue
            if not line.startswith("data:"):
                continue
            payload = line[5:].strip()
            if event == "message":
                try:
                    delta = json.loads(payload)
                except json.JSONDecodeError:
                    continue
                if delta.get("type") == "think":
                    continue
                text = delta.get("delta") or ""
                if text:
                    if first_token_at is None:
                        first_token_at = time.perf_counter() - started
                    answer.append(text)
            elif event in ("finish", "done"):
                break
            elif event == "error":
                raise RuntimeError(payload)
    total = time.perf_counter() - started
    return "".join(answer), first_token_at or total, total


def load_questions(path: Path) -> list[dict]:
    items = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line:
            items.append(json.loads(line))
    return items


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:9090/api/sagerag")
    parser.add_argument("--questions", default=str(Path(__file__).with_name("questions.jsonl")))
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default="admin")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--output", default="")
    args = parser.parse_args()

    base_url = args.base_url.rstrip("/")
    login = post_json(f"{base_url}/auth/login", {"username": args.username, "password": args.password})
    token = login["token"]

    questions = load_questions(Path(args.questions))
    if args.limit > 0:
        questions = questions[: args.limit]

    rows, first_tokens, totals = [], [], []
    for index, item in enumerate(questions, 1):
        question = item["question"]
        keywords = item.get("keywords", [])
        print(f"[{index}/{len(questions)}] {question}", flush=True)
        try:
            answer, first, total = stream_chat(base_url, token, question, args.timeout)
            low = answer.lower()
            matched = [k for k in keywords if k.lower() in low]
            hit = bool(keywords) and len(matched) == len(keywords)
            rows.append({
                "id": item.get("id", str(index)),
                "question": question,
                "answer": answer,
                "hit": hit,
                "matched": matched,
                "first_token_s": round(first, 3),
                "total_s": round(total, 3),
            })
            first_tokens.append(first)
            totals.append(total)
            print(f"    hit={hit} first={first:.2f}s total={total:.2f}s", flush=True)
        except Exception as exc:  # noqa: BLE001
            rows.append({"id": item.get("id", str(index)), "question": question, "error": str(exc)})
            print(f"    ERROR: {exc}", flush=True)

    ok = [r for r in rows if "error" not in r]
    hit_count = sum(1 for r in ok if r["hit"])
    report = {
        "total": len(rows),
        "success": len(ok),
        "hit": hit_count,
        "hit_rate": round(hit_count / len(ok), 4) if ok else 0,
        "avg_first_token_s": round(statistics.mean(first_tokens), 3) if first_tokens else 0,
        "avg_total_s": round(statistics.mean(totals), 3) if totals else 0,
        "rows": rows,
    }

    print("\n===== Summary =====")
    print(json.dumps({k: v for k, v in report.items() if k != "rows"}, ensure_ascii=False, indent=2))

    if args.output:
        Path(args.output).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"saved -> {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
