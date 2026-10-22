#!/bin/bash
# Полная установка серверной части Blueberry (без sudo): токен, venv, LaunchAgents lmstudio / proxy / watchdog.
# Системные части со sudo ставятся отдельно и по подтверждению:
#   scripts/install-sudoers.sh install   (blueberry on/off без пароля)
#   scripts/blueberry-firewall apply     (необязательно: закрыть порт 1234 в pf)
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
"$ROOT/scripts/install-lmstudio.sh"
"$ROOT/scripts/install-proxy.sh"
"$ROOT/scripts/install-watchdog.sh"
cat <<MSG

Готово. Команда blueberry лежит в ~/.config/blueberry/bin. Чтобы вызывать её отовсюду, добавьте в ~/.zshrc:
    export PATH="\$HOME/.config/blueberry/bin:\$PATH"
Дальше: scripts/install-sudoers.sh install  (в обычном окне Terminal, нужен пароль)
MSG
