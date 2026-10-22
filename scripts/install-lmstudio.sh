#!/bin/bash
# Устанавливает LaunchAgent com.blueberry.lmstudio (без sudo, только для текущего пользователя).
# Скрипты копируются в ~/.config/blueberry/bin: launchd не должен зависеть от ~/Documents (защита TCC).
# Удаление: scripts/install-lmstudio.sh uninstall
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CFG="$HOME/.config/blueberry"
LABEL=com.blueberry.lmstudio
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"

if [ "${1:-}" = "uninstall" ]; then
  launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
  rm -f "$PLIST" "$CFG/bin/lmstudio-run.sh"
  echo "Агент $LABEL удалён (конфиг и логи сохранены)."
  exit 0
fi

mkdir -p "$CFG/bin" "$HOME/Library/Logs/Blueberry" "$HOME/Library/LaunchAgents"
[ -f "$CFG/config.env" ] || cp "$ROOT/config.example.env" "$CFG/config.env"
cp "$ROOT/scripts/lmstudio-run.sh" "$CFG/bin/lmstudio-run.sh"
sed "s|__HOME__|$HOME|g" "$ROOT/launchd/com.blueberry.lmstudio.plist.template" > "$PLIST"
plutil -lint "$PLIST"

launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$PLIST"
echo "Агент $LABEL установлен и запущен. Логи: ~/Library/Logs/Blueberry/"
