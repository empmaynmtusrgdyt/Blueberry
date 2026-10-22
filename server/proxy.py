#!/usr/bin/env python3
"""Blueberry: reverse-proxy с авторизацией по токену перед LM Studio.

- пропускает только GET /v1/models, POST /v1/chat/completions и GET /health;
- требует Authorization: Bearer <токен> (кроме /health);
- стримит SSE без буферизации, при обрыве клиента закрывает upstream-запрос;
- если модель выгружена (idle-таймаут watchdog), загружает её заново;
- логирует только метаданные, содержимое сообщений не логируется.
"""
import asyncio
import hmac
import json
import logging
import os
import subprocess
import sys
import time
from pathlib import Path

import aiohttp
from aiohttp import web

CONFIG_DIR = Path(os.environ.get("BLUEBERRY_CONFIG_DIR", Path.home() / ".config" / "blueberry"))
STATE_DIR = CONFIG_DIR / "state"
LAST_REQUEST_FILE = STATE_DIR / "last_request"

log = logging.getLogger("blueberry.proxy")


def load_config() -> dict:
    """Читает config.env (KEY=value, комментарии #, допускает $HOME и кавычки)."""
    cfg = {}
    path = CONFIG_DIR / "config.env"
    if path.exists():
        for line in path.read_text().splitlines():
            line = line.split("#", 1)[0].strip()
            if "=" not in line:
                continue
            key, value = line.split("=", 1)
            cfg[key.strip()] = os.path.expandvars(value.strip().strip('"').strip("'"))
    return cfg


def load_token() -> str:
    path = CONFIG_DIR / "token"
    if not path.exists():
        sys.exit(f"Токен не найден: {path}. Создайте его: scripts/blueberry-token")
    if path.stat().st_mode & 0o077:
        sys.exit(f"У файла токена слишком широкие права, выполните: chmod 600 {path}")
    token = path.read_text().strip()
    if len(token) < 32:
        sys.exit("Токен слишком короткий (нужно не менее 32 символов)")
    return token


class Proxy:
    def __init__(self, cfg: dict, token: str):
        self.token = token
        self.upstream = f"http://127.0.0.1:{cfg.get('LMS_PORT', '1234')}"
        self.lms_bin = cfg.get("LMS_BIN", str(Path.home() / ".lmstudio/bin/lms"))
        self.model_key = cfg.get("MODEL_KEY", "google/gemma-4-e4b")
        self.model_id = cfg.get("MODEL_IDENTIFIER", "gemma")
        self.model_ctx = cfg.get("MODEL_CONTEXT", "8192")
        self.max_body = int(cfg.get("PROXY_MAX_BODY_BYTES", 256 * 1024))
        self.max_concurrent = int(cfg.get("PROXY_MAX_CONCURRENT", 2))
        self.active = 0
        self.load_lock = asyncio.Lock()
        self.loading = False
        self.session: aiohttp.ClientSession | None = None

    # ---------- вспомогательное ----------
    def authorized(self, request: web.Request) -> bool:
        header = request.headers.get("Authorization", "")
        scheme, _, value = header.partition(" ")
        if scheme.lower() != "bearer" or not value:
            return False
        return hmac.compare_digest(value.strip().encode(), self.token.encode())

    @staticmethod
    def touch_activity() -> None:
        try:
            STATE_DIR.mkdir(parents=True, exist_ok=True)
            LAST_REQUEST_FILE.write_text(str(int(time.time())))
        except OSError:
            pass

    async def model_state(self) -> str:
        """loaded | loading | unloaded | server_down"""
        if self.loading:
            return "loading"
        try:
            async with self.session.get(f"{self.upstream}/api/v0/models",
                                        timeout=aiohttp.ClientTimeout(total=3)) as r:
                data = await r.json()
            for m in data.get("data", []):
                if m.get("id") == self.model_id and m.get("state") == "loaded":
                    return "loaded"
            return "unloaded"
        except Exception:
            return "server_down"

    async def ensure_model(self) -> bool:
        """Загружает модель, если выгружена. Одновременно только одна загрузка."""
        async with self.load_lock:
            if await self.model_state() == "loaded":
                return True
            self.loading = True
            started = time.monotonic()
            log.info("модель выгружена, загружаю заново")
            try:
                proc = await asyncio.create_subprocess_exec(
                    self.lms_bin, "load", self.model_key, "-c", self.model_ctx, "-y",
                    "--identifier", self.model_id,
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                await asyncio.wait_for(proc.wait(), timeout=300)
            except Exception as e:  # noqa: BLE001
                log.error("не удалось загрузить модель: %s", type(e).__name__)
                return False
            finally:
                self.loading = False
            log.info("модель загружена за %.1f с", time.monotonic() - started)
            return await self.model_state() == "loaded"

    # ---------- обработчики ----------
    async def health(self, request: web.Request) -> web.Response:
        return web.json_response({"status": "ok", "model": await self.model_state()})

    async def models(self, request: web.Request) -> web.Response:
        try:
            async with self.session.get(f"{self.upstream}/v1/models",
                                        timeout=aiohttp.ClientTimeout(total=10)) as r:
                body = await r.read()
                return web.Response(body=body, status=r.status, content_type="application/json")
        except aiohttp.ClientError:
            return web.json_response({"error": "upstream_unavailable"}, status=502)

    async def chat(self, request: web.Request) -> web.StreamResponse:
        if self.active >= self.max_concurrent:
            return web.json_response({"error": "busy"}, status=429, headers={"Retry-After": "5"})
        self.active += 1
        try:
            return await self._chat(request)
        finally:
            self.active -= 1

    async def _chat(self, request: web.Request) -> web.StreamResponse:
        raw = await request.read()  # client_max_size защищает от больших тел (413)
        try:
            payload = json.loads(raw)
            if not isinstance(payload, dict) or not isinstance(payload.get("messages"), list):
                raise ValueError
        except ValueError:
            return web.json_response({"error": "bad_request"}, status=400)
        payload["model"] = self.model_id  # клиент не выбирает произвольные модели
        self.touch_activity()

        stream = bool(payload.get("stream"))
        resp = web.StreamResponse(status=200, headers={
            "Content-Type": "text/event-stream" if stream else "application/json",
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
        })

        # Холодный старт: заголовки уходят сразу, а пока грузится модель,
        # шлём SSE-комментарии ": loading", чтобы клиент не ушёл по таймауту.
        if await self.model_state() != "loaded":
            if not stream:
                if not await self.ensure_model():
                    return web.json_response({"error": "model_load_failed"}, status=503)
            else:
                await resp.prepare(request)
                task = asyncio.ensure_future(self.ensure_model())
                try:
                    while not task.done():
                        await resp.write(b": loading\n\n")
                        await asyncio.wait({task}, timeout=3)
                except (ConnectionResetError, asyncio.CancelledError):
                    # загрузку не прерываем: модель всё равно понадобится
                    raise
                if not task.result():
                    await resp.write(b'data: {"error":"model_load_failed"}\n\n')
                    return resp

        try:
            timeout = aiohttp.ClientTimeout(total=None, connect=5, sock_read=600)
            async with self.session.post(f"{self.upstream}/v1/chat/completions",
                                         json=payload, timeout=timeout) as up:
                if not resp.prepared:
                    resp.set_status(up.status)
                    resp.content_type = up.content_type
                    await resp.prepare(request)
                async for chunk in up.content.iter_any():
                    await resp.write(chunk)
                await resp.write_eof()
                # выход из async with закрывает соединение с upstream
        except (ConnectionResetError, asyncio.CancelledError):
            # клиент оборвал соединение: выход из async with закрыл upstream-запрос,
            # LM Studio прекращает генерацию
            log.info("клиент отключился, upstream закрыт")
            raise
        except aiohttp.ClientError:
            if not resp.prepared:
                return web.json_response({"error": "upstream_unavailable"}, status=502)
            await resp.write(b'data: {"error":"upstream_unavailable"}\n\n')
        return resp


@web.middleware
async def guard(request: web.Request, handler):
    """Авторизация, список разрешённых путей и логирование метаданных."""
    start = time.monotonic()
    status = 500
    try:
        allowed = {("GET", "/health"), ("GET", "/v1/models"), ("POST", "/v1/chat/completions")}
        if (request.method, request.path) not in allowed:
            raise web.HTTPNotFound()
        if request.path != "/health" and not request.app["proxy"].authorized(request):
            raise web.HTTPUnauthorized(headers={"WWW-Authenticate": "Bearer"},
                                       text='{"error":"unauthorized"}',
                                       content_type="application/json")
        resp = await handler(request)
        status = resp.status
        return resp
    except web.HTTPException as e:
        status = e.status
        raise
    except (asyncio.CancelledError, ConnectionResetError):
        status = 499  # клиент оборвал соединение
        raise
    finally:
        if request.path != "/health":
            log.info("%s %s -> %s %.2fс", request.method, request.path, status,
                     time.monotonic() - start)


async def on_startup(app: web.Application) -> None:
    app["proxy"].session = aiohttp.ClientSession()


async def on_cleanup(app: web.Application) -> None:
    await app["proxy"].session.close()


def make_app(cfg: dict, token: str) -> web.Application:
    proxy = Proxy(cfg, token)
    app = web.Application(middlewares=[guard], client_max_size=proxy.max_body)
    app["proxy"] = proxy
    app.router.add_get("/health", proxy.health)
    app.router.add_get("/v1/models", proxy.models)
    app.router.add_post("/v1/chat/completions", proxy.chat)
    app.on_startup.append(on_startup)
    app.on_cleanup.append(on_cleanup)
    return app


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s",
                        stream=sys.stdout)
    logging.getLogger("aiohttp.access").setLevel(logging.WARNING)
    cfg = load_config()
    token = load_token()
    host = cfg.get("PROXY_HOST", "127.0.0.1")
    port = int(cfg.get("PROXY_PORT", 8787))
    log.info("прокси слушает на %s:%s", host, port)
    web.run_app(make_app(cfg, token), host=host, port=port, print=None,
                handler_cancellation=True)


if __name__ == "__main__":
    main()
