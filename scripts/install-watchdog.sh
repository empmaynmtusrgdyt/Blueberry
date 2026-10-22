#!/bin/bash
# Устанавливает LaunchAgent com.blueberry.watchdog (без sudo).
# Копирует watchdog.py и переключатель blueberry в ~/.config/blueberry/bin.
# Удаление: scripts/install-watchdog.sh uninstall
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CFG="$HOME/.config/blueberry"
LABEL=com.blueberry.watchdog
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"

if [ "${1:-}" = "uninstall" ]; then
  launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
  rm -f "$PLIST" "$CFG/bin/watchdog.py"
  echo "Агент $LABEL удалён (конфиг и логи сохранены)."
  exit 0
fi

mkdir -p "$CFG/bin" "$HOME/Library/Logs/Blueberry" "$HOME/Library/LaunchAgents"
[ -x "$CFG/venv/bin/python" ] || { echo "Сначала scripts/install-proxy.sh (создаёт venv)"; exit 1; }
if ! grep -q '^WD_INTERVAL=' "$CFG/config.env" 2>/dev/null; then
  sed -n '/^# --- Watchdog/,$p' "$ROOT/config.example.env" >> "$CFG/config.env"
fi
cp "$ROOT/server/watchdog.py" "$CFG/bin/watchdog.py"
cp "$ROOT/scripts/blueberry" "$CFG/bin/blueberry"; chmod +x "$CFG/bin/blueberry"
sed "s|__HOME__|$HOME|g" "$ROOT/launchd/com.blueberry.watchdog.plist.template" > "$PLIST"
plutil -lint "$PLIST"

launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
sleep 2
launchctl bootstrap "gui/$(id -u)" "$PLIST"
echo "Агент $LABEL установлен. Логи: ~/Library/Logs/Blueberry/watchdog.log"
