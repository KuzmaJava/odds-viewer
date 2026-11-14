import asyncio
import logging
import os
from contextlib import asynccontextmanager
from pathlib import Path
from typing import List

from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

from app.movement import MARKETS, drops, movements, rises
from app.config import build_client, build_poller
from app.export import status_dict
from app.odds_client import OddsApiError
from app.poller import iso, utc_now
from app.store import Store

load_dotenv()
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")

ROOT = Path(__file__).resolve().parent.parent
STATIC_DIR = ROOT / "static"

client = build_client()
store = Store(os.getenv("DB_PATH", str(ROOT / "data" / "odds.sqlite")))
poller = build_poller(client, store)


@asynccontextmanager
async def lifespan(_: FastAPI):
    task = asyncio.create_task(poller.run_forever())
    yield
    task.cancel()


app = FastAPI(title="Pinnacle Odds Tracker", lifespan=lifespan)
app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")


@app.get("/")
def index():
    return FileResponse(STATIC_DIR / "index.html", headers={"Cache-Control": "no-cache"})


@app.get("/api/status")
def status():
    return status_dict(poller, client.demo)


@app.get("/api/sports")
def sports(group: str = Query("Soccer", description="Группа спорта; пусто — все")):
    try:
        items = client.sports()
    except OddsApiError as e:
        raise HTTPException(status_code=e.status, detail=str(e))
    return [s for s in items if s.get("active") and not s.get("has_outrights") and (not group or s.get("group") == group)]


class TrackedSports(BaseModel):
    sports: List[str]


@app.put("/api/tracked")
def set_tracked(body: TrackedSports):
    poller.sports = sorted(set(body.sports))
    return {"tracked_sports": poller.sports}


@app.post("/api/poll")
async def poll_now():
    """Внеплановый опрос всех лиг (тратит квоту)."""
    return await asyncio.to_thread(poller.run_cycle, True)


def _movements(include_started: bool):
    since = None if include_started else iso(utc_now())
    return movements(store, since)


@app.get("/api/markets")
def markets():
    return MARKETS


@app.get("/api/events")
def events(include_started: bool = False):
    return _movements(include_started)


@app.get("/api/drops")
def get_drops(min_pct: float = 3.0, include_started: bool = False):
    return drops(_movements(include_started), min_pct)


@app.get("/api/rises")
def get_rises(min_pct: float = 3.0, include_started: bool = False):
    return rises(_movements(include_started), min_pct)


@app.get("/api/events/{event_id}/history")
def history(event_id: str):
    ev = store.event(event_id)
    if not ev:
        raise HTTPException(status_code=404, detail="Матч не найден")
    return {"event": ev, "history": store.history(event_id)}
