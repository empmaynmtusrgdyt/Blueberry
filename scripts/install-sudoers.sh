#!/bin/bash
# Ставит привилегированный helper и узкую запись sudoers, чтобы `blueberry on/off` не просили пароль.
#   install-sudoers.sh show        показать, что будет сделано (ничего не меняет)
#   install-sudoers.sh install     установить (нужен sudo)
#   install-sudoers.sh uninstall   удалить
# Разрешается ровно две команды: helper с аргументом on и helper с аргументом off.
# Helper принадлежит root и не доступен пользователю на запись, поэтому подменить его нельзя.
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
USER_NAME="$(id -un)"
HELPER=/usr/local/libexec/blueberry-pmset
SUDOERS=/etc/sudoers.d/blueberry
RULE="$USER_NAME ALL=(root) NOPASSWD: $HELPER on, $HELPER off"

case "${1:-show}" in
  show)
    echo "Будет выполнено:"
    echo "  sudo install -d -o root -g wheel -m 755 /usr/local/libexec"
    echo "  sudo install -o root -g wheel -m 755 $ROOT/scripts/blueberry-pmset-helper $HELPER"
    echo "  запись в $SUDOERS (root:wheel, 440):"
    echo "      $RULE"
    echo "  проверка синтаксиса: visudo -cf (до установки)"
    ;;
  install)
    TMP="$(mktemp)"; echo "$RULE" > "$TMP"
    sudo visudo -cf "$TMP"
    sudo install -d -o root -g wheel -m 755 /usr/local/libexec
    sudo install -o root -g wheel -m 755 "$ROOT/scripts/blueberry-pmset-helper" "$HELPER"
    sudo install -o root -g wheel -m 440 "$TMP" "$SUDOERS"
    rm -f "$TMP"
    sudo visudo -c
    echo "Готово. Проверка: sudo -n $HELPER on  (и затем off)"
    ;;
  uninstall)
    sudo "$HELPER" off 2>/dev/null || true
    sudo rm -f "$SUDOERS" "$HELPER"
    echo "Запись sudoers и helper удалены."
    ;;
  *) echo "Использование: $0 show|install|uninstall"; exit 1 ;;
esac
