# Blueberry

Локальная модель Gemma (LM Studio) на MacBook, доступная с Android-телефона по локальной сети без интернета —
даже когда крышка ноутбука закрыта и он лежит в рюкзаке. Телефон раздаёт hotspot, мак подключается к нему сам,
в приложении Blueberry вы общаетесь с моделью.

## Архитектура

```
┌────────────── Android ───────────────┐          ┌──────────────────── MacBook ─────────────────────┐
│ Приложение Blueberry                 │          │                                                    │
│  • чат, история (Room)               │  Wi-Fi   │  com.blueberry.proxy   :8787  (0.0.0.0)           │
│  • токен в Keystore                  │ ───────▶ │   • Bearer-токен, 401 без него                    │
│  • QR / Bonjour / ручной ввод        │  HTTP    │   • SSE-стрим, отмена при обрыве клиента          │
└──────────────────────────────────────┘ (LAN)    │   • загрузка модели после idle (холодный старт)   │
                                                  │            │ 127.0.0.1                             │
                                                  │            ▼                                       │
                                                  │  com.blueberry.lmstudio  :1234  (только localhost) │
                                                  │   LM Studio server + google/gemma-4-e4b            │
                                                  │                                                    │
                                                  │  com.blueberry.watchdog  (заряд, перегрев, idle)   │
                                                  │  blueberry on|off|status|qr  (сон и caffeinate)    │
                                                  └────────────────────────────────────────────────────┘
```

* Наружу смотрит только прокси (порт 8787). LM Studio слушает 127.0.0.1; при желании порт 1234 дополнительно закрывается правилом pf.
* Разрешены три пути: `GET /health` (без токена), `GET /v1/models`, `POST /v1/chat/completions` (с токеном).
* Прокси анонсирует Bonjour-сервис `_blueberry._tcp`, когда слушает не на localhost.

## Структура репозитория

| Путь | Что там |
|---|---|
| `server/proxy.py` | прокси с авторизацией, стримингом и Bonjour |
| `server/watchdog.py` | защита от перегрева, разрядки и простоя |
| `scripts/blueberry` | переключатель `on` / `off` / `status` / `qr` |
| `scripts/install-*.sh`, `install-all.sh` | установка LaunchAgents и системных частей |
| `scripts/uninstall-all.sh` | полный откат |
| `scripts/blueberry-pmset-helper`, `install-sudoers.sh` | привилегированный helper и узкая запись sudoers |
| `scripts/blueberry-firewall`, `firewall/` | необязательное правило pf для порта 1234 |
| `launchd/` | шаблоны LaunchAgent |
| `android/` | приложение (`com.blueberry.app`) |
| `docs/` | сеть и обнаружение, памятка для рюкзака |
| `config.example.env` | шаблон конфигурации (копируется в `~/.config/blueberry/config.env`) |

Системные пути: конфигурация `~/.config/blueberry/` (`config.env`, `token`, `state/`, `bin/`, `venv/`), логи `~/Library/Logs/Blueberry/`,
LaunchAgents в `~/Library/LaunchAgents/`.

## Установка

Требуется: macOS на Apple Silicon, LM Studio с загруженной моделью `google/gemma-4-e4b` (команда `lms` в `~/.lmstudio/bin`), Python 3.

```bash
cd Blueberry
scripts/install-all.sh                  # токен, venv, агенты lmstudio / proxy / watchdog (без sudo)
```

Затем в **обычном окне Terminal** (нужен пароль) установите helper и узкую запись sudoers, чтобы `blueberry on/off` и watchdog
работали без пароля. Посмотреть план: `scripts/install-sudoers.sh show`.

```bash
scripts/install-sudoers.sh install
```

Добавьте команду в PATH (`~/.zshrc`): `export PATH="$HOME/.config/blueberry/bin:$PATH"`.

Необязательно, второй слой защиты: `scripts/blueberry-firewall show`, потом `apply` (закрывает порт 1234 для всего, кроме loopback).

### Конфигурация

`~/.config/blueberry/config.env` (шаблон: `config.example.env`). После правки перезапустите нужный агент
(`launchctl kickstart -k gui/$(id -u)/com.blueberry.proxy`, то же для `watchdog`).

| Параметр | По умолчанию | Смысл |
|---|---|---|
| `PROXY_HOST` / `PROXY_PORT` | `0.0.0.0` / `8787` | адрес прокси |
| `MODEL_KEY`, `MODEL_IDENTIFIER`, `MODEL_CONTEXT` | `google/gemma-4-e4b`, `gemma`, `8192` | модель и контекст |
| `PROXY_MAX_BODY_BYTES`, `PROXY_MAX_CONCURRENT` | 262144, 2 | лимиты |
| `WD_BATTERY_MIN_PERCENT` | 15 | ниже и без зарядки: аварийная остановка |
| `WD_THERMAL_LEVEL`, `WD_CPU_SPEED_LIMIT_MIN`, `WD_THERMAL_SUSTAIN_SECONDS` | 2, 70, 180 | перегрев дольше порога: аварийная остановка |
| `WD_IDLE_MINUTES` | 20 | без запросов: выгрузить модель |
| `BONJOUR_NAME` | `Blueberry` | имя сервиса |

Размер контекста в приложении (экран подключения) должен совпадать с `MODEL_CONTEXT`: по нему обрезается история.

## Как пользоваться

```bash
blueberry on        # не спать при закрытой крышке (disablesleep, sleep 0, powernap 0, caffeinate -imsu)
blueberry status    # режим, питание, батарея, термика, IP, порты, сервисы, watchdog, последние срабатывания
blueberry off       # вернуть исходные настройки питания
blueberry qr        # QR для телефона (--png сохранить и открыть изображение; удалите после использования)
```

Модель после простоя выгружается, а при первом сообщении загружается заново: **холодный старт ~10–20 с**,
приложение показывает «Модель загружается…».

Если watchdog остановил сервисы (аварийный режим), `blueberry on` снимает его и поднимает агенты заново.

## Подключение телефона

1. Установите готовый APK из репозитория: `dist/Blueberry-debug.apk` (пересборка: `cd android && ./gradlew assembleDebug`, затем скопировать `app/build/outputs/apk/debug/app-debug.apk` в `dist/Blueberry-debug.apk`).
   Без кабеля APK можно переслать себе, например в Telegram, и открыть на телефоне.
2. Мак и телефон в одной сети (или мак подключён к hotspot телефона). Прокси слушает 0.0.0.0.
3. `blueberry qr`, в приложении «Сканировать QR». Альтернатива: ввести IP, порт и токен вручную (`~/.config/blueberry/token`).
4. «Проверить соединение», «Сохранить», создайте чат.

Приложение подключается в таком порядке: сохранённые данные → автообнаружение Bonjour (если адрес сменился) → ручной ввод.
Подробности про IP в сети hotspot: `docs/network.md`. Сканер QR использует Google Play Services; при первом запуске ему может понадобиться интернет.

### Hotspot

Мак должен знать сеть телефона: подключитесь к ней один раз вручную и включите «Автоматическое подключение»
(Системные настройки, Wi-Fi, Подробнее). Для стабильного адреса включите для этой сети «Private Wi-Fi address: Fixed».
Пароль сети в файлы проекта не записывается.

## Диагностика

```bash
blueberry status                                               # общая картина
tail -f ~/Library/Logs/Blueberry/proxy.log                     # запросы (метод, путь, статус, время; без содержимого)
tail -f ~/Library/Logs/Blueberry/watchdog.log                  # срабатывания watchdog
tail -f ~/Library/Logs/Blueberry/lmstudio.log                  # запуск сервера и модели
cat ~/.config/blueberry/state/alerts.log                       # история аварийных остановок
launchctl print gui/$(id -u)/com.blueberry.proxy | grep state  # состояние агента (то же для lmstudio, watchdog)
lsof -nP -iTCP:8787 -iTCP:1234 -sTCP:LISTEN                    # кто слушает порты (1234 только 127.0.0.1)
curl -s http://127.0.0.1:8787/health                           # {"status":"ok","model":"loaded|unloaded|loading"}
pmset -g | grep -E "SleepDisabled|sleep"                       # режим сна
pmset -g assertions                                            # что мешает сну
pmset -g therm                                                 # тепловое состояние
dns-sd -B _blueberry._tcp local.                               # виден ли Bonjour-сервис
~/.lmstudio/bin/lms ps                                         # загружена ли модель
```

Типичные проблемы: **401**, токен в приложении не совпадает с `~/.config/blueberry/token`, отсканируйте QR заново;
**«Сервер недоступен»**, проверьте `blueberry status` (порт 8787 на `*`), одну ли сеть используют телефон и мак;
**нет ответа после простоя**, подождите холодный старт (до ~30 с); **429**, заняты оба слота запросов.

## Известные ограничения

* **FileVault.** После перезагрузки или полной разрядки мак ждёт ввода пароля FileVault до загрузки системы; без него не стартует ни сеть, ни агенты. Автологин при включённом FileVault недоступен. Сервер рассчитан на мак, уже включённый и разблокированный перед тем, как его положили в рюкзак.
* **Нагрев в рюкзаке.** Закрытый наглухо рюкзак плохо охлаждает мак. Watchdog выключит сервисы при устойчивом перегреве, но лучше оставить доступ воздуху.
* **Без TLS.** Трафик между телефоном и маком идёт по HTTP внутри локальной сети, защищён только токеном. Приложение принимает только локальные адреса (10.x, 172.16–31.x, 192.168.x, 169.254.x, `*.local`).
* **Имя Wi-Fi сети** macOS скрывает от процессов без права на геолокацию, поэтому `blueberry status` показывает IP и шлюз, а не SSID.
* **Фон на телефоне.** Если Android завершит процесс приложения в фоне, идущая генерация прервётся (история сохраняется, незавершённый ответ нет).
* **mDNS** на hotspot Android работает не на всех устройствах (на TECNO SPARK Go 1 автообнаружение проверено и работает); поэтому есть QR и ручной ввод.
* **Один пользователь, один сервер.** Параллельно обслуживаются максимум 2 запроса.

## Откат

В обычном окне Terminal:

```bash
scripts/uninstall-all.sh --dry-run    # посмотреть план
scripts/uninstall-all.sh              # откатить всё, сохранив токен и конфиг
scripts/uninstall-all.sh --purge      # дополнительно удалить ~/.config/blueberry и логи
```

Откат возвращает настройки питания, останавливает `caffeinate`, снимает правило pf (если применялось), удаляет запись sudoers
и helper, останавливает и удаляет агенты `com.blueberry.*`, выгружает модель и останавливает сервер LM Studio.
Отдельные части можно откатывать по одной: `scripts/install-sudoers.sh uninstall`, `scripts/blueberry-firewall remove`,
`scripts/install-proxy.sh uninstall` (аналогично `install-lmstudio.sh`, `install-watchdog.sh`).

Перед тем как положить мак в рюкзак: [docs/backpack-checklist.md](docs/backpack-checklist.md).
