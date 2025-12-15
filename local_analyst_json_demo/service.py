"""
Lightweight HTTP service that lets you ask analytics questions against local JSON logs
using a local LLM (Ollama DeepSeek-R1:8B by default, or any OpenAI-compatible server).

Start the server once, then hit:
  curl "http://127.0.0.1:8001/ask?q=какая%20ошибка%20чаще%20всего?"

Uses only the standard library; reuses helpers from demo.py for SQL generation, execution,
and summarization. Data are loaded into in-memory SQLite on startup.
"""

from __future__ import annotations

import json
import os
import sqlite3
import urllib.parse
from http.server import BaseHTTPRequestHandler, HTTPServer
from typing import Dict, Any

# Reuse logic from demo.py
import demo

HOST = os.environ.get("ANALYST_HOST", "127.0.0.1")
PORT = int(os.environ.get("ANALYST_PORT", "8001"))
DATA_PATH = os.environ.get(
    "ANALYST_DATA", os.path.join(os.path.dirname(__file__), "data", "events.json")
)


class AnalystHandler(BaseHTTPRequestHandler):
    conn: sqlite3.Connection = demo.load_events(DATA_PATH)

    def _json_response(self, code: int, payload: Dict[str, Any]) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):  # noqa: N802
        # http.server decodes path as latin-1; re-decode to utf-8 to keep Cyrillic
        utf8_path = self.path.encode("latin-1").decode("utf-8", errors="replace")
        parsed = urllib.parse.urlparse(utf8_path)
        if parsed.path != "/ask":
            self._json_response(404, {"error": "not found"})
            return

        qs = urllib.parse.parse_qs(parsed.query, encoding="utf-8", errors="replace")
        question = qs.get("q", [""])[0].strip()
        if not question:
            self._json_response(400, {"error": "missing query param q"})
            return

        try:
            sql = demo.generate_sql(question)
            rows = demo.run_sql(self.conn, sql)
            answer = ""
            if os.environ.get("ANALYST_NO_LLM") == "1":
                answer = summarize_rows(question, sql, rows)
            else:
                answer = demo.call_llm(
                    demo.build_answer_prompt(question, sql, rows),
                    max_tokens=200,
                    temperature=0.1,
                )
                if not answer:
                    answer = summarize_rows(question, sql, rows)
            self._json_response(
                200, {"question": question, "sql": sql, "rows": rows, "answer": answer}
            )
        except Exception as exc:  # broad, to surface in JSON
            self._json_response(500, {"error": str(exc)})


def summarize_rows(question: str, sql: str, rows: list[dict[str, Any]]) -> str:
    """Deterministic short summary when LLM did not answer."""
    if not rows:
        return "Данных нет по этому запросу."

    # message + cnt pattern
    if {"message", "cnt"}.issubset(rows[0].keys()):
        top = rows[0]
        # если выбрано ASC (редкая), будем говорить "самая редкая"
        return f"{'Самая редкая' if 'ASC' in sql.upper() else 'Чаще всего встречается'} ошибка «{top['message']}» — {top['cnt']} раз(а)."

    if {"step", "errors"}.issubset(rows[0].keys()):
        top = rows[0]
        return f"Больше всего ошибок на шаге «{top['step']}» — {top['errors']}."

    if {"step", "avg_ms"}.issubset(rows[0].keys()):
        top = rows[0]
        return f"Самое медленное: шаг «{top['step']}», среднее {int(top['avg_ms'])} мс."

    return f"Получено {len(rows)} строк по запросу. SQL: {sql}"


def main() -> None:
    server = HTTPServer((HOST, PORT), AnalystHandler)
    print(f"Local analyst listening on http://{HOST}:{PORT}/ask")
    print("Example: curl \"http://127.0.0.1:8001/ask?q=какая%20ошибка%20чаще%20всего?\"")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
