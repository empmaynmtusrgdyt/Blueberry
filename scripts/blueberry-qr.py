#!/usr/bin/env python3
"""Показывает QR-код для первой настройки приложения Blueberry.

В QR лежит JSON: {"v":1,"name":..., "host":<IP мака>, "port":..., "mdns":<имя>.local, "token":...}.
Токен секретный: QR не сохраняется в репозиторий. С --png файл пишется в ~/.config/blueberry/state/ (права 600).

  blueberry qr            показать QR в терминале
  blueberry qr --png      дополнительно сохранить PNG и открыть его
  blueberry qr --ip X     указать IP вручную (если определился не тот интерфейс)
"""
import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

import qrcode

CONFIG_DIR = Path(os.environ.get("BLUEBERRY_CONFIG_DIR", Path.home() / ".config" / "blueberry"))


def sh(*cmd) -> str:
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=5).stdout.strip()
    except (OSError, subprocess.TimeoutExpired):
        return ""


def config() -> dict:
    cfg = {}
    path = CONFIG_DIR / "config.env"
    if path.exists():
        for line in path.read_text().splitlines():
            line = line.split("#", 1)[0].strip()
            if "=" in line:
                k, v = line.split("=", 1)
                cfg[k.strip()] = os.path.expandvars(v.strip().strip('"').strip("'"))
    return cfg


def default_ip() -> str:
    iface = ""
    for line in sh("route", "-n", "get", "default").splitlines():
        if "interface:" in line:
            iface = line.split()[-1]
    return (sh("ipconfig", "getifaddr", iface) if iface else "") or sh("ipconfig", "getifaddr", "en0")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--png", action="store_true")
    ap.add_argument("--ip")
    args = ap.parse_args()

    token_file = CONFIG_DIR / "token"
    if not token_file.exists():
        sys.exit("Токена нет. Сначала: scripts/blueberry-token")
    cfg = config()
    ip = args.ip or default_ip()
    if not ip:
        sys.exit("Не удалось определить IP: мак не подключён к сети?")
    host = sh("scutil", "--get", "LocalHostName")
    payload = {
        "v": 1,
        "name": cfg.get("BONJOUR_NAME", "Blueberry"),
        "host": ip,
        "port": int(cfg.get("PROXY_PORT", 8787)),
        "mdns": f"{host}.local" if host else "",
        "token": token_file.read_text().strip(),
    }
    qr = qrcode.QRCode(border=2, error_correction=qrcode.constants.ERROR_CORRECT_M)
    qr.add_data(json.dumps(payload, separators=(",", ":")))
    qr.make(fit=True)

    print(f"Сервер: http://{ip}:{payload['port']}  ({payload['mdns'] or 'без mDNS-имени'})")
    if cfg.get("PROXY_HOST", "127.0.0.1") in ("127.0.0.1", "localhost"):
        print("ВНИМАНИЕ: прокси сейчас слушает только localhost (PROXY_HOST), телефон не подключится.")
    qr.print_ascii(invert=True)
    print("Отсканируйте в приложении Blueberry. Токен в QR: не показывайте экран посторонним.")
    if args.png:
        out = CONFIG_DIR / "state" / "blueberry-qr.png"
        out.parent.mkdir(parents=True, exist_ok=True)
        old = os.umask(0o077)
        try:
            qr.make_image(fill_color="black", back_color="white").save(out)
        finally:
            os.umask(old)
        print(f"PNG: {out} (удалите после использования)")
        subprocess.run(["open", str(out)])


if __name__ == "__main__":
    main()
