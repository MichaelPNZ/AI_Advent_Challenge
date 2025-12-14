"""Minimal local analytics demo over JSON + SQLite using a local LLM server.

Default: uses Ollama `deepseek-r1:8b` at http://127.0.0.1:11434 (fits your laptop).
Alternate: any OpenAI-compatible endpoint (e.g. your VPS llama server) via env vars.

Usage (Ollama local):
  python demo.py "какая ошибка чаще всего?"

Usage (remote OpenAI-style):
  LLM_PROVIDER=openai LLM_BASE_URL=http://<vps>:8080 LLM_MODEL=llama3.2-1b-instruct-q4_k_m \
  python demo.py "какая ошибка чаще всего?"

Requires only stdlib.
"""

from __future__ import annotations
import json
import os
import sqlite3
import sys
import urllib.error
import urllib.request
from typing import List, Dict, Any

DATA_PATH = os.path.join(os.path.dirname(__file__), "data", "events.json")
BASE_URL = os.environ.get("LLM_BASE_URL", "http://127.0.0.1:11434")
MODEL = os.environ.get("LLM_MODEL", "deepseek-r1:8b")
PROVIDER = os.environ.get("LLM_PROVIDER", "ollama").lower()  # "ollama" | "openai"


def load_events(db_path: str = DATA_PATH) -> sqlite3.Connection:
    """Load bundled JSON into in-memory SQLite table `events`."""

    with open(db_path, "r", encoding="utf-8") as f:
        payload = json.load(f)
    conn = sqlite3.connect(":memory:")
    conn.execute(
        """
        CREATE TABLE events (
            ts TEXT,
            user_id TEXT,
            step TEXT,
            event TEXT,
            status TEXT,
            message TEXT,
            duration_ms INTEGER
        );
        """
    )
    rows = [
        (
            item.get("ts"),
            item.get("user_id"),
            item.get("step"),
            item.get("event"),
            item.get("status"),
            item.get("message"),
            int(item.get("duration_ms", 0)),
        )
        for item in payload
    ]
    conn.executemany(
        "INSERT INTO events (ts, user_id, step, event, status, message, duration_ms) VALUES (?,?,?,?,?,?,?)",
        rows,
    )
    conn.commit()
    return conn


def _ollama_call(messages: List[Dict[str, Any]], max_tokens: int, temperature: float) -> str:
    body = json.dumps(
        {
            "model": MODEL,
            "messages": messages,
            "stream": False,
            "format": "json",
            "options": {
                "temperature": temperature,
                "num_predict": max_tokens,
                "top_p": 0.9,
                "keep_alive": "5m",
                "stop": ["<think>", "</think>"],
            },
        }
    ).encode("utf-8")
    req = urllib.request.Request(
        f"{BASE_URL}/api/chat",
        data=body,
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req) as resp:
        parsed = json.load(resp)
    content = parsed.get("message", {}).get("content", "")
    if not content and os.environ.get("DEBUG_SQL"):
        print("DEBUG raw ollama response:", parsed)
    return content


def _openai_call(messages: List[Dict[str, Any]], max_tokens: int, temperature: float) -> str:
    body = json.dumps(
        {
            "model": MODEL,
            "messages": messages,
            "max_tokens": max_tokens,
            "temperature": temperature,
        }
    ).encode("utf-8")
    req = urllib.request.Request(
        f"{BASE_URL}/v1/chat/completions",
        data=body,
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req) as resp:
        parsed = json.load(resp)
    return parsed["choices"][0]["message"]["content"]


def call_llm(messages: List[Dict[str, Any]], max_tokens: int = 256, temperature: float = 0.1) -> str:
    try:
        if PROVIDER == "ollama":
            return _ollama_call(messages, max_tokens, temperature)
        if PROVIDER == "openai":
            return _openai_call(messages, max_tokens, temperature)
        raise ValueError(f"Unsupported LLM_PROVIDER: {PROVIDER}")
    except urllib.error.HTTPError as e:
        detail = e.read().decode()
        raise RuntimeError(f"LLM HTTP error {e.code}: {detail}") from e
    except urllib.error.URLError as e:
        raise RuntimeError(f"LLM connection error: {e}") from e


def build_sql_prompt(question: str) -> List[Dict[str, str]]:
    schema = "columns: ts (ISO text), user_id, step, event, status, message, duration_ms (int)."
    few_shot = (
        "Вопрос: какая ошибка чаще всего?\n"
        "SQL: SELECT message, COUNT(*) AS cnt FROM events WHERE event='error' GROUP BY 1 ORDER BY cnt DESC LIMIT 5;\n\n"
        "Вопрос: на каком шаге больше всего отвалов?\n"
        "SQL: SELECT step, COUNT(*) AS errors FROM events WHERE event='error' GROUP BY step ORDER BY errors DESC LIMIT 5;\n\n"
        "Вопрос: средняя длительность по шагам\n"
        "SQL: SELECT step, AVG(duration_ms) AS avg_ms FROM events GROUP BY step ORDER BY avg_ms DESC;\n\n"
    )
    prompt = (
        "Ты генератор SQL для SQLite. Есть таблица events (" + schema + ")\n"
        "Отвечай строго JSON вида {\"sql\": \"...\"}. Без текста вокруг. Никаких рассуждений, тегов <think>, комментариев.\n\n"
        + few_shot
        + f"Вопрос: {question}\nSQL:"
    )
    return [
        {
            "role": "system",
            "content": (
                "Ты возвращаешь только JSON с полем sql. Не добавляй <think> и другие теги. "
                "Не добавляй комментарии. Я буду парсить ответ как JSON."
            ),
        },
        {"role": "user", "content": prompt},
    ]


def _extract_select(reply: str) -> str:
    """Try multiple ways to extract a SELECT statement from model reply."""
    if not reply:
        return ""

    def clean_jsonish(text: str) -> str:
        start = text.find("{")
        end = text.rfind("}")
        if start != -1 and end != -1 and end > start:
            return text[start : end + 1]
        return text

    # JSON path
    try:
        parsed = json.loads(clean_jsonish(reply))
        if isinstance(parsed, dict) and "sql" in parsed:
            candidate = str(parsed.get("sql", "")).strip()
            if candidate.lower().startswith("select"):
                return candidate
    except json.JSONDecodeError:
        pass

    text = reply.strip()
    # code fence
    if "```" in text:
        parts = text.split("```")
        for part in parts:
            part_strip = part.strip()
            if part_strip.lower().startswith("select"):
                return part_strip
    # raw select line
    lowered = text.lower()
    if "select" in lowered:
        idx = lowered.find("select")
        candidate = text[idx:]
        # cut off after semicolon if present
        if ";" in candidate:
            candidate = candidate.split(";", 1)[0] + ";"
        return candidate.strip()
    return ""


def _heuristic_sql(question: str) -> str:
    q_lower = question.lower()
    if "ошиб" in q_lower or "error" in q_lower:
        if "реже" in q_lower or "редк" in q_lower or "миним" in q_lower:
            return "SELECT message, COUNT(*) AS cnt FROM events WHERE event='error' GROUP BY message ORDER BY cnt ASC LIMIT 5;"
        return "SELECT message, COUNT(*) AS cnt FROM events WHERE event='error' GROUP BY message ORDER BY cnt DESC LIMIT 5;"
    if "длитель" in q_lower or "duration" in q_lower or "avg" in q_lower:
        return "SELECT step, AVG(duration_ms) AS avg_ms FROM events GROUP BY step ORDER BY avg_ms DESC LIMIT 5;"
    if "шаг" in q_lower or "ворон" in q_lower or "этап" in q_lower or "теряет" in q_lower:
        return "SELECT step, COUNT(*) AS total, SUM(event='error') AS errors FROM events GROUP BY step ORDER BY errors DESC, total DESC LIMIT 10;"
    return "SELECT * FROM events LIMIT 20;"


def generate_sql(question: str) -> str:
    if os.environ.get("ANALYST_NO_LLM") == "1":
        sql = _heuristic_sql(question)
    else:
        reply = call_llm(build_sql_prompt(question), max_tokens=200, temperature=0.0)
        sql = _extract_select(reply)
        if not sql or not sql.lower().startswith("select"):
            sql = _heuristic_sql(question)

    if "limit" not in sql.lower():
        sql = sql.rstrip(";") + " LIMIT 20;"
    return sql


def run_sql(conn: sqlite3.Connection, sql: str) -> List[Dict[str, Any]]:
    cur = conn.execute(sql)
    cols = [desc[0] for desc in cur.description]
    rows = [dict(zip(cols, row)) for row in cur.fetchall()]
    return rows


def build_answer_prompt(question: str, sql: str, rows: List[Dict[str, Any]]) -> List[Dict[str, str]]:
    summary = json.dumps(rows, ensure_ascii=False)
    prompt = (
        "Ты аналитик. На вопрос пользователя дан SQL и результат.\n"
        f"Вопрос: {question}\nSQL: {sql}\nРезультат (JSON): {summary}\n"
        "Дай краткий ответ по-русски, до 60 слов. Если данных нет, скажи, что данных нет."
    )
    return [
        {
            "role": "system",
            "content": "Не используй <think>. Отвечай одним абзацем, без маркировок.",
        },
        {"role": "user", "content": prompt},
    ]


def answer(question: str, conn: sqlite3.Connection) -> None:
    print(f"\nQ: {question}")
    sql = generate_sql(question)
    print(f"SQL → {sql}")
    rows = run_sql(conn, sql)
    print(f"Rows ({len(rows)}): {rows}")
    final = call_llm(build_answer_prompt(question, sql, rows), max_tokens=200)
    print(f"\nAnswer:\n{final}\n")


def main(argv: List[str]) -> None:
    question = " ".join(argv) if argv else "какая ошибка чаще всего?"
    conn = load_events()
    answer(question, conn)


if __name__ == "__main__":
    main(sys.argv[1:])
