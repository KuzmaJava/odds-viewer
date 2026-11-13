import os
from pathlib import Path

from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from app.markets import normalize_event
from app.odds_client import OddsApiError, OddsClient

load_dotenv()

STATIC_DIR = Path(__file__).resolve().parent.parent / "static"

client = OddsClient(
    api_key=os.getenv("ODDS_API_KEY", "").strip(),
    regions=os.getenv("ODDS_REGIONS", "eu"),
    cache_ttl=int(os.getenv("CACHE_TTL_SECONDS", "120")),
)

app = FastAPI(title="Odds Viewer")
app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")


def _call(fn, *args, **kwargs):
    try:
        return fn(*args, **kwargs)
    except OddsApiError as e:
        raise HTTPException(status_code=e.status, detail=str(e))


@app.get("/")
def index():
    return FileResponse(STATIC_DIR / "index.html")


@app.get("/api/status")
def status():
    return {
        "demo": client.demo,
        "regions": client.regions,
        "default_bookmaker": os.getenv("DEFAULT_BOOKMAKER", "pinnacle"),
        "quota": client.quota,
    }


@app.get("/api/sports")
def sports(group: str = Query("Soccer", description="Группа спорта; пусто — все")):
    items = _call(client.sports)
    return [s for s in items if s.get("active") and not s.get("has_outrights") and (not group or s.get("group") == group)]


@app.get("/api/odds/{sport}")
def odds(sport: str):
    events = _call(client.odds, sport)
    return {"events": [normalize_event(e) for e in events], "quota": client.quota}


@app.get("/api/odds/{sport}/{event_id}/extra")
def event_extra(sport: str, event_id: str, markets: str = "team_totals"):
    ev = _call(client.event_odds, sport, event_id, markets)
    if not ev:
        raise HTTPException(status_code=404, detail="Событие не найдено")
    return {"event": normalize_event(ev), "quota": client.quota}
