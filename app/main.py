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

from app.movement import drops, event_movement
from app.odds_client import OddsApiError, OddsClient
from app.poller import Poller, iso, utc_now
from app.store import Store

load_dotenv()
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")

ROOT = Path(__file__).resolve().parent.parent
STATIC_DIR = ROOT / "static"

client = OddsClient(
    api_key=os.getenv("ODDS_API_KEY", "").strip(),
    regions=os.getenv("ODDS_REGIONS", "eu"),
    cache_ttl=int(os.getenv("CACHE_TTL_SECONDS", "120")),
)
store = Store(os.getenv("DB_PATH", str(ROOT / "data" / "odds.sqlite")))
poller = Poller(
    client, store,
    interval_minutes=int(os.getenv("POLL_INTERVAL_MINUTES", "30")),
    min_quota=int(os.getenv("MIN_QUOTA_REMAINING", "10")),
    default_sports=[s.strip() for s in os.getenv("TRACK_SPORTS", "soccer_epl").split(",") if s.strip()],
)


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
    per_poll = len(poller.sports)
    polls_per_month = int(30 * 24 * 60 / (poller.interval.total_seconds() / 60))
    return {
        "demo": client.demo,
        "tracked_sports": poller.sports,
        "interval_minutes": int(poller.interval.total_seconds() // 60),
        "last_poll_at": poller.last_poll_at,
        "next_poll_at": iso(poller.next_poll_at()),
        "last_result": poller.last_result,
        "last_error": poller.last_error,
        "quota": client.quota,
        "quota_estimate": {"per_poll": per_poll, "per_month": per_poll * polls_per_month},
    }


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
    """Внеплановый опрос (тратит квоту: 1 запрос на лигу)."""
    return await asyncio.to_thread(poller.poll_once)


def _movements(include_started: bool):
    since = None if include_started else iso(utc_now())
    return [event_movement(r) for r in store.events_with_range(since)]


@app.get("/api/events")
def events(include_started: bool = False):
    return _movements(include_started)


@app.get("/api/drops")
def get_drops(min_pct: float = 3.0, include_started: bool = False):
    return drops(_movements(include_started), min_pct)


@app.get("/api/events/{event_id}/history")
def history(event_id: str):
    ev = store.event(event_id)
    if not ev:
        raise HTTPException(status_code=404, detail="Матч не найден")
    return {"event": ev, "history": store.history(event_id)}
