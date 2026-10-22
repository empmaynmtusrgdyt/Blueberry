#!/bin/bash
# Запускает сервер LM Studio (только localhost), загружает Gemma и следит, что сервер жив.
# Выполняется под launchd (com.blueberry.lmstudio). Если скрипт завершится, launchd перезапустит его.
set -u

CONFIG="$HOME/.config/blueberry/config.env"
[ -f "$CONFIG" ] && . "$CONFIG"
LMS_BIN="${LMS_BIN:-$HOME/.lmstudio/bin/lms}"
LMS_PORT="${LMS_PORT:-1234}"
MODEL_KEY="${MODEL_KEY:-google/gemma-4-e4b}"
MODEL_IDENTIFIER="${MODEL_IDENTIFIER:-gemma}"
MODEL_CONTEXT="${MODEL_CONTEXT:-8192}"

log() { echo "$(date '+%F %T') $*"; }

server_up() { curl -sf -m 3 "http://127.0.0.1:${LMS_PORT}/v1/models" >/dev/null 2>&1; }
model_loaded() { "$LMS_BIN" ps 2>/dev/null | grep -q "^${MODEL_IDENTIFIER}[[:space:]]"; }

start_server() {
  log "запуск сервера LM Studio на 127.0.0.1:${LMS_PORT}"
  "$LMS_BIN" server start --port "$LMS_PORT" --bind 127.0.0.1 2>&1 | tr '\r' '\n' | tail -3
}

load_model() {
  log "загрузка модели ${MODEL_KEY} (контекст ${MODEL_CONTEXT})"
  "$LMS_BIN" load "$MODEL_KEY" -c "$MODEL_CONTEXT" -y --identifier "$MODEL_IDENTIFIER" 2>&1 \
    | tr '\r' '\n' | grep -v "Loading" | tail -3
}

# Первоначальный запуск
server_up || start_server
for _ in $(seq 1 30); do server_up && break; sleep 1; done
server_up || { log "сервер не поднялся, выхожу (launchd перезапустит)"; exit 1; }
model_loaded || load_model
log "готово: сервер и модель запущены"

# Мониторинг: только сервер. Выгрузкой модели по простою и её возвратом управляют watchdog и прокси.
while true; do
  sleep 20
  if ! server_up; then
    log "сервер недоступен, перезапуск"
    start_server
    sleep 3
    server_up && { model_loaded || load_model; }
  fi
done
