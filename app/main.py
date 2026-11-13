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
    default_sports=[s.strip() for s in os.getenv("TRACK_SPORTS", "soccer_epl").split(",") if s.strip()],
    min_quota=int(os.getenv("MIN_QUOTA_REMAINING", "10")),
    base_hours=float(os.getenv("POLL_BASE_HOURS", "3")),
    near_hours=float(os.getenv("POLL_NEAR_HOURS", "1")),
    near_window_hours=float(os.getenv("NEAR_KICKOFF_HOURS", "8")),
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
    schedule = poller.schedule()
    hours = lambda td: td.total_seconds() / 3600  # noqa: E731
    return {
        "demo": client.demo,
        "tracked_sports": poller.sports,
        "policy": {
            "base_hours": hours(poller.base),
            "near_hours": hours(poller.near),
            "near_window_hours": hours(poller.near_window),
        },
        "schedule": schedule,
        "last_poll_at": poller.last_poll_at,
        "next_poll_at": min((s["next_poll_at"] for s in schedule), default=None),
        "last_result": poller.last_result,
        "last_error": poller.last_error,
        "quota": client.quota,
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
