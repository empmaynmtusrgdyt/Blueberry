#!/bin/bash
# Устанавливает LaunchAgent com.blueberry.proxy (без sudo).
# Код и venv копируются в ~/.config/blueberry (launchd не должен читать ~/Documents).
# Удаление: scripts/install-proxy.sh uninstall
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CFG="$HOME/.config/blueberry"
LABEL=com.blueberry.proxy
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"

if [ "${1:-}" = "uninstall" ]; then
  launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
  rm -f "$PLIST" "$CFG/bin/proxy.py"
  echo "Агент $LABEL удалён (конфиг, токен, venv и логи сохранены)."
  exit 0
fi

mkdir -p "$CFG/bin" "$HOME/Library/Logs/Blueberry" "$HOME/Library/LaunchAgents"
"$ROOT/scripts/blueberry-token"
# добавить недостающие параметры прокси в существующий config.env
if ! grep -q '^PROXY_PORT=' "$CFG/config.env" 2>/dev/null; then
  sed -n '/^# --- Прокси/,$p' "$ROOT/config.example.env" >> "$CFG/config.env"
fi
if [ ! -x "$CFG/venv/bin/python" ]; then
  python3 -m venv "$CFG/venv"
  "$CFG/venv/bin/pip" install -q -r "$ROOT/server/requirements.txt"
fi
cp "$ROOT/server/proxy.py" "$CFG/bin/proxy.py"
sed "s|__HOME__|$HOME|g" "$ROOT/launchd/com.blueberry.proxy.plist.template" > "$PLIST"
plutil -lint "$PLIST"

launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
sleep 2
launchctl bootstrap "gui/$(id -u)" "$PLIST"
echo "Агент $LABEL установлен. Логи: ~/Library/Logs/Blueberry/proxy.log"
