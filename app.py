"""Отдаёт frontend и его безопасную публичную runtime-конфигурацию."""

import os
from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from dotenv import load_dotenv

BASE_DIR = Path(__file__).resolve().parent
STATIC_DIR = BASE_DIR / "static"

load_dotenv()

app = FastAPI()


@app.middleware("http")
async def disable_html_cache(request, call_next):
    """Не даёт браузеру сочетать актуальные страницы со старым frontend-кешем."""

    response = await call_next(request)
    content_type = response.headers.get("content-type", "")
    if content_type.startswith("text/html"):
        response.headers["cache-control"] = "no-store, max-age=0"
    return response


@app.get("/api/config")
def config():
    """Возвращает публичную конфигурацию frontend без серверных секретов."""

    avatar_provider = os.getenv("AVATAR_PROVIDER", "did").lower()
    scribe_language = os.getenv("SCRIBE_LANGUAGE_CODE")
    response = {
        "avatar_provider": avatar_provider,
        "chat_api_url": os.getenv("CHAT_API_URL", "http://localhost:8080"),
        "stt_enabled": bool(os.getenv("ELEVENLABS_API_KEY", "").strip()),
        "scribe_model": os.getenv("SCRIBE_MODEL", "scribe_v2_realtime").strip() or "scribe_v2_realtime",
        "scribe_language_code": "ru" if scribe_language is None else scribe_language.strip() or None,
    }

    if avatar_provider == "did":
        agent_id = os.getenv("DID_AGENT_ID", "").strip()
        client_key = os.getenv("DID_CLIENT_KEY", "").strip()
        if not agent_id or not client_key:
            raise HTTPException(
                status_code=503,
                detail="D-ID configuration is unavailable.",
            )
        response["agent_id"] = agent_id
        response["client_key"] = client_key

    return response


app.mount(
    "/",
    StaticFiles(
        directory=STATIC_DIR,
        html=True
    ),
    name="static"
)
