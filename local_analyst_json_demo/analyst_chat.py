"""
Console chat that feels like talking to DeepSeek, но с доступом к вашим данным.
Вопросы → SQL → выполнение → краткий ответ.

Запуск:
  cd local_analyst_json_demo
  python3 analyst_chat.py

По умолчанию использует Ollama deepseek-r1:8b (или что задано через LLM_* env).
"""

from __future__ import annotations

import sys

import demo
from service import summarize_rows  # deterministic fallback


def main() -> None:
    conn = demo.load_events()
    print("Аналитик запущен. Пиши вопрос, пустая строка или Ctrl+D — выход.")
    try:
        for line in sys.stdin:
            question = line.strip()
            if not question:
                break
            try:
                sql = demo.generate_sql(question)
                rows = demo.run_sql(conn, sql)
                answer = demo.call_llm(
                    demo.build_answer_prompt(question, sql, rows),
                    max_tokens=200,
                    temperature=0.1,
                )
                if not answer:
                    answer = summarize_rows(question, sql, rows)
                print(f"\nDeepSeek> {answer}\n")
            except Exception as exc:  # pragma: no cover - interactive convenience
                print(f"Ошибка: {exc}\n")
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()

