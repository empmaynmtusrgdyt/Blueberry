#!/bin/bash
# Полный откат Blueberry. Запускать в обычном окне Terminal (нужен пароль для sudo-части).
#   uninstall-all.sh             откатить всё, сохранив ~/.config/blueberry (токен, конфиг) и логи
#   uninstall-all.sh --purge     дополнительно удалить ~/.config/blueberry и ~/Library/Logs/Blueberry
#   uninstall-all.sh --dry-run   только показать, что будет сделано
# Что откатывается: режим закрытой крышки (настройки питания), caffeinate, правила pf, запись sudoers и helper,
# LaunchAgents com.blueberry.*, процессы прокси / dns-sd, модель и сервер LM Studio.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CFG="$HOME/.config/blueberry"
PURGE=0; DRY=0
for a in "$@"; do case "$a" in --purge) PURGE=1 ;; --dry-run) DRY=1 ;; *) echo "неизвестный параметр: $a"; exit 1 ;; esac; done

run() { echo "+ $*"; [ "$DRY" -eq 1 ] || "$@" 2>&1 | sed 's/^/    /' || true; }
step() { echo; echo "== $* =="; }

step "1. Остановить watchdog (чтобы он не вмешивался)"
run launchctl bootout "gui/$(id -u)/com.blueberry.watchdog"

step "2. Вернуть настройки питания и остановить caffeinate"
if [ -x /usr/local/libexec/blueberry-pmset ]; then
  run sudo /usr/local/libexec/blueberry-pmset off
else
  echo "helper не установлен, возвращаю только disablesleep"
  run sudo pmset -a disablesleep 0
fi
[ -f "$CFG/state/caffeinate.pid" ] && run kill "$(cat "$CFG/state/caffeinate.pid")"
run rm -f "$CFG/state/caffeinate.pid"

step "3. Правила pf (если применялись)"
if [ -f "$CFG/state/pf_token" ]; then run "$ROOT/scripts/blueberry-firewall" remove; else echo "pf не применялся"; fi

step "4. Остановить прокси, модель и сервер LM Studio, удалить LaunchAgents"
run launchctl bootout "gui/$(id -u)/com.blueberry.proxy"
run launchctl bootout "gui/$(id -u)/com.blueberry.lmstudio"
if [ -x "$HOME/.lmstudio/bin/lms" ]; then
  run "$HOME/.lmstudio/bin/lms" unload --all
  run "$HOME/.lmstudio/bin/lms" server stop
fi
for l in watchdog proxy lmstudio; do run rm -f "$HOME/Library/LaunchAgents/com.blueberry.$l.plist"; done
run pkill -f "dns-sd -R Blueberry"

step "5. Убрать запись sudoers и helper"
run sudo rm -f /etc/sudoers.d/blueberry /usr/local/libexec/blueberry-pmset
run sudo rm -rf /var/db/blueberry

if [ "$PURGE" -eq 1 ]; then
  step "6. Удалить конфигурацию, токен и логи"
  run rm -rf "$CFG" "$HOME/Library/Logs/Blueberry"
else
  step "6. Конфигурация и токен сохранены (удалить: --purge)"
fi

echo; echo "Откат завершён."
[ "$DRY" -eq 1 ] && echo "(это был --dry-run: ничего не изменено)"
exit 0
