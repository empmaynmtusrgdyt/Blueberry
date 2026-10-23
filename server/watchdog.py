#!/usr/bin/env python3
"""Blueberry watchdog: защита от перегрева, разрядки и простоя.

Каждые WD_INTERVAL секунд проверяет:
  * заряд батареи (без зарядки ниже порога -> аварийная остановка);
  * тепловое давление системы и ограничение скорости CPU, если оно держится дольше порога;
  * время с последнего запроса к модели (простой -> модель выгружается из памяти).

Аварийная остановка: выгрузить модель, остановить сервер LM Studio и прокси, вернуть обычный сон
(`blueberry off`). Повторно сервисы поднимает `blueberry on`.

  watchdog.py          рабочий режим
  watchdog.py --check  один раз показать показания и решения, ничего не выполняя
"""
import json
import logging
import os
import re
import socket
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

CONFIG_DIR = Path(os.environ.get("BLUEBERRY_CONFIG_DIR", Path.home() / ".config" / "blueberry"))
STATE_DIR = CONFIG_DIR / "state"
LAST_REQUEST_FILE = STATE_DIR / "last_request"
TRIPPED_FILE = STATE_DIR / "tripped"      # JSON: причина и время аварийной остановки
ALERTS_FILE = STATE_DIR / "alerts.log"    # история срабатываний (показывает `blueberry status`)

log = logging.getLogger("blueberry.watchdog")


def load_config() -> dict:
    cfg = {}
    path = CONFIG_DIR / "config.env"
    if path.exists():
        for line in path.read_text().splitlines():
            line = line.split("#", 1)[0].strip()
            if "=" in line:
                k, v = line.split("=", 1)
                cfg[k.strip()] = os.path.expandvars(v.strip().strip('"').strip("'"))
    return cfg


def run(*cmd, timeout=60) -> str:
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return r.stdout
    except (OSError, subprocess.TimeoutExpired):
        return ""


class Watchdog:
    def __init__(self, cfg: dict):
        g = cfg.get
        self.interval = int(g("WD_INTERVAL", 30))
        self.battery_min = int(g("WD_BATTERY_MIN_PERCENT", 15))
        self.thermal_level = int(g("WD_THERMAL_LEVEL", 2))           # 0 норма, 1 умеренный, 2 сильный, 3 критический
        self.cpu_limit_min = int(g("WD_CPU_SPEED_LIMIT_MIN", 70))    # % скорости CPU; ниже — считаем троттлингом
        self.thermal_sustain = int(g("WD_THERMAL_SUSTAIN_SECONDS", 180))
        self.idle_seconds = int(g("WD_IDLE_MINUTES", 20)) * 60
        self.lms = g("LMS_BIN", str(Path.home() / ".lmstudio/bin/lms"))
        self.model_id = g("MODEL_IDENTIFIER", "gemma")
        self.lms_port = int(g("LMS_PORT", 1234))
        self.proxy_port = int(g("PROXY_PORT", 8787))
        self.started = time.time()
        self.hot_since: float | None = None

    # ---------- показания ----------
    def battery(self) -> tuple[int | None, bool]:
        """(процент, на батарее без зарядки)"""
        out = run("pmset", "-g", "batt")
        m = re.search(r"(\d+)%", out)
        percent = int(m.group(1)) if m else None
        on_battery = "Battery Power" in out and "discharging" in out
        return percent, on_battery

    def thermal(self) -> tuple[int, int]:
        """(уровень теплового давления, ограничение CPU в %)"""
        out = run("notifyutil", "-g", "com.apple.system.thermalpressurelevel")
        m = re.search(r"(\d+)\s*$", out.strip())
        level = int(m.group(1)) if m else 0
        out = run("pmset", "-g", "therm")
        m = re.search(r"CPU_Speed_Limit\s*=\s*(\d+)", out)
        return level, int(m.group(1)) if m else 100

    @staticmethod
    def _listening(port: int) -> bool:
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.5):
                return True
        except OSError:
            return False

    def active(self) -> bool:
        """Запущено ли что-то, что стоит защищать: сервер LM Studio, прокси или режим закрытой крышки.

        После `blueberry stop` сторож продолжает работать, но заряд и нагрев его уже не касаются:
        останавливать нечего, а ложные уведомления только путают."""
        if self._listening(self.lms_port) or self._listening(self.proxy_port):
            return True
        return re.search(r"SleepDisabled\s+1", run("pmset", "-g")) is not None

    def model_state(self) -> str | None:
        """None: сервер не работает или модель не загружена; иначе статус (IDLE / GENERATING / ...).

        Опрашиваем сервер по HTTP, а не командой `lms ps`: она сама поднимает фоновый сервис LM Studio,
        и после `blueberry stop` он оживал бы каждые 30 секунд."""
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{self.lms_port}/api/v0/models", timeout=3) as r:
                models = json.load(r).get("data", [])
        except (OSError, ValueError):
            return None
        for m in models:
            if m.get("id") == self.model_id and m.get("state") == "loaded":
                # статус «идёт генерация» виден только в lms ps; вызываем его, лишь когда сервер точно работает
                for line in run(self.lms, "ps").splitlines():
                    parts = line.split()
                    if parts and parts[0] == self.model_id and len(parts) > 2:
                        return parts[2]
                return "IDLE"
        return None

    def last_activity(self) -> float:
        try:
            return max(float(LAST_REQUEST_FILE.read_text().strip()), self.started)
        except (OSError, ValueError):
            return self.started

    # ---------- действия ----------
    @staticmethod
    def alert(message: str) -> None:
        log.warning(message)
        try:
            STATE_DIR.mkdir(parents=True, exist_ok=True)
            with ALERTS_FILE.open("a") as f:
                f.write(f"{time.strftime('%F %T')} {message}\n")
        except OSError:
            pass
        run("osascript", "-e",
            f'display notification "{message}" with title "Blueberry" sound name "Basso"', timeout=10)

    def emergency_stop(self, reason: str) -> None:
        self.alert(f"Аварийная остановка: {reason}")
        TRIPPED_FILE.write_text(json.dumps({"reason": reason, "time": time.strftime("%F %T")}, ensure_ascii=False))
        run(self.lms, "unload", "--all")
        run(self.lms, "server", "stop")
        uid = os.getuid()
        for label in ("com.blueberry.proxy", "com.blueberry.lmstudio"):
            run("launchctl", "bootout", f"gui/{uid}/{label}")
        blueberry = CONFIG_DIR / "bin" / "blueberry"
        run(str(blueberry), "off")
        log.info("сервисы остановлены, обычный сон возвращён")

    def idle_unload(self) -> None:
        self.alert(f"Нет запросов {self.idle_seconds // 60} мин: модель выгружена, режим ожидания")
        run(self.lms, "unload", "--all")

    # ---------- проверка ----------
    def evaluate(self, act: bool) -> list[str]:
        report = []
        if TRIPPED_FILE.exists():
            return ["аварийный режим активен (сброс: blueberry on)"]

        if not self.active():
            self.hot_since = None
            return ["сервер и режим закрытой крышки выключены: проверки заряда и нагрева пропущены"]

        percent, on_battery = self.battery()
        report.append(f"батарея: {percent}% {'без зарядки' if on_battery else 'питание/зарядка'}")
        if percent is not None and on_battery and percent < self.battery_min:
            report.append(f"-> ЗАРЯД < {self.battery_min}%")
            if act:
                self.emergency_stop(f"заряд {percent}% ниже порога {self.battery_min}% без зарядки")
            return report

        level, speed = self.thermal()
        hot = level >= self.thermal_level or speed < self.cpu_limit_min
        report.append(f"термика: уровень {level}, лимит CPU {speed}%{' (перегрев)' if hot else ''}")
        now = time.time()
        if hot:
            self.hot_since = self.hot_since or now
            held = now - self.hot_since
            report.append(f"-> перегрев держится {held:.0f} с из {self.thermal_sustain}")
            if held >= self.thermal_sustain:
                if act:
                    self.emergency_stop(f"перегрев (уровень {level}, лимит CPU {speed}%) дольше {self.thermal_sustain} с")
                return report
        else:
            self.hot_since = None

        state = self.model_state()
        idle = now - self.last_activity()
        report.append(f"модель: {state or 'не загружена'}, простой {idle / 60:.1f} мин из {self.idle_seconds // 60}")
        if state == "IDLE" and idle >= self.idle_seconds:
            report.append("-> простой: выгрузить модель")
            if act:
                self.idle_unload()
        return report

    def loop(self) -> None:
        log.info("watchdog запущен: заряд<%d%%, термо>=%d/лимит<%d%% %dс, простой %d мин, период %dс",
                 self.battery_min, self.thermal_level, self.cpu_limit_min, self.thermal_sustain,
                 self.idle_seconds // 60, self.interval)
        while True:
            try:
                self.evaluate(act=True)
            except Exception as e:  # noqa: BLE001  watchdog не должен падать
                log.error("ошибка проверки: %s", e)
            time.sleep(self.interval)


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s", stream=sys.stdout)
    wd = Watchdog(load_config())
    if "--check" in sys.argv:
        for line in wd.evaluate(act=False):
            print(line)
        return
    wd.loop()


if __name__ == "__main__":
    main()
