#!/usr/bin/env bash
set -euo pipefail

DIFF_FILE="${1:-diff.txt}"
MODEL="${PR_REVIEW_MODEL:-gpt-4o-mini}"
BASE_BRANCH="${PR_REVIEW_BASE:-origin/main}"
OUTPUT_FILE="${2:-build/review.md}"
MAX_CHARS_DIFF=20000
MAX_CHARS_DOCS=20000

if [[ -z "${OPENAI_API_KEY:-}" ]]; then
  echo "OPENAI_API_KEY is required" >&2
  exit 1
fi

mkdir -p "$(dirname "$OUTPUT_FILE")"

echo "Preparing context..."
diff_content="$(head -c $MAX_CHARS_DIFF "$DIFF_FILE" || true)"

collect_docs() {
  local target="$1"
  local limit="$2"
  local acc=""
  while IFS= read -r -d '' file; do
    snippet="--- FILE: ${file}
$(head -c 4000 "$file")
"
    acc="${acc}${snippet}"$'\n'
    if [[ ${#acc} -ge $limit ]]; then
      break
    fi
  done < <(find README.MD docs -type f -print0 2>/dev/null)
  echo "$acc"
}

docs_content="$(collect_docs "README.MD docs" "$MAX_CHARS_DOCS")"

prompt=$(cat <<EOF
Ты опытный инженер и делаешь code review для PR.
Дано:
- Diff (truncated).
- Контекст из документации/кода проекта (README + docs).

Что нужно:
1) Найти проблемы/баги/риски регрессий.
2) Проверить необходимость тестов.
3) Дать советы по улучшению (кратко).
Отвечай списком, с ссылками на файлы/фрагменты из diff или контекста, если возможно.
EOF
)

user_msg=$(cat <<EOF
## DIFF (truncated to ${MAX_CHARS_DIFF} chars)
${diff_content}

## DOCS (truncated to ${MAX_CHARS_DOCS} chars)
${docs_content}
EOF
)

echo "Calling OpenAI model $MODEL..."
response="$(curl -sS https://api.openai.com/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $OPENAI_API_KEY" \
  -d "$(jq -n \
    --arg model "$MODEL" \
    --arg sys "$prompt" \
    --arg user "$user_msg" \
    '{model:$model, messages:[{role:"system",content:$sys},{role:"user",content:$user}], temperature:0.2, max_completion_tokens:800}')")"

content="$(echo "$response" | jq -r '.choices[0].message.content // empty')"
if [[ -z "$content" ]]; then
  finish_reason="$(echo "$response" | jq -r '.choices[0].finish_reason // "unknown"')"
  content="(Авто-подсказка) Модель не вернула текст ответа (finish_reason=${finish_reason}). Уменьшите размер diff или попробуйте другую модель. Сырой ответ (усечён до 4000 символов):\n\n$(echo "$response" | head -c 4000)"
fi

cat > "$OUTPUT_FILE" <<EOF
# 🤖 AI Code Review

$content
EOF

echo "Review saved to $OUTPUT_FILE"
