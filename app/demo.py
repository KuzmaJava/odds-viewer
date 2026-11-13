"""Демо-данные в формате The Odds API — чтобы UI работал без ключа."""
import random
from datetime import datetime, timedelta, timezone
from typing import Dict, List

_SPORTS = [
    {"key": "soccer_epl", "group": "Soccer", "title": "EPL", "active": True, "has_outrights": False},
    {"key": "soccer_spain_la_liga", "group": "Soccer", "title": "La Liga - Spain", "active": True, "has_outrights": False},
]

_MATCHES = {
    "soccer_epl": [
        ("Arsenal", "Chelsea"), ("Liverpool", "Manchester City"), ("Tottenham Hotspur", "Newcastle United"),
        ("Aston Villa", "Brighton and Hove Albion"), ("West Ham United", "Everton"),
    ],
    "soccer_spain_la_liga": [
        ("Real Madrid", "Barcelona"), ("Atlético Madrid", "Sevilla"), ("Real Sociedad", "Villarreal"),
    ],
}

_BOOKS = [("pinnacle", "Pinnacle", 0.025), ("onexbet", "1xBet", 0.05), ("williamhill", "William Hill", 0.065)]


def _price(prob: float, book_margin: float) -> float:
    return round(1 / (prob * (1 + book_margin)), 2)


def _event(sport: str, idx: int, home: str, away: str, with_team_totals: bool = False) -> dict:
    rnd = random.Random(f"{sport}-{idx}")
    ph = rnd.uniform(0.25, 0.6)
    pd = rnd.uniform(0.2, 0.3)
    pa = max(0.08, 1 - ph - pd)
    s = ph + pd + pa
    ph, pd, pa = ph / s, pd / s, pa / s
    p_over = rnd.uniform(0.4, 0.6)
    start = datetime.now(timezone.utc).replace(minute=0, second=0, microsecond=0) + timedelta(hours=6 + idx * 5)
    event_id = f"demo-{sport}-{idx}"

    books = []
    for key, title, m in _BOOKS:
        jitter = rnd.uniform(-0.015, 0.015)
        markets = [
            {"key": "h2h", "outcomes": [
                {"name": home, "price": _price(ph + jitter, m)},
                {"name": away, "price": _price(pa - jitter, m)},
                {"name": "Draw", "price": _price(pd, m)},
            ]},
            {"key": "totals", "outcomes": [
                {"name": "Over", "price": _price(p_over + jitter, m), "point": 2.5},
                {"name": "Under", "price": _price(1 - p_over - jitter, m), "point": 2.5},
            ]},
        ]
        if with_team_totals:
            pho, pao = rnd.uniform(0.35, 0.65), rnd.uniform(0.3, 0.6)
            markets.append({"key": "team_totals", "outcomes": [
                {"name": "Over", "description": home, "price": _price(pho, m), "point": 1.5},
                {"name": "Under", "description": home, "price": _price(1 - pho, m), "point": 1.5},
                {"name": "Over", "description": away, "price": _price(pao, m), "point": 0.5 + (idx % 2)},
                {"name": "Under", "description": away, "price": _price(1 - pao, m), "point": 0.5 + (idx % 2)},
            ]})
        books.append({
            "key": key, "title": title,
            "last_update": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "markets": markets,
        })

    return {
        "id": event_id, "sport_key": sport,
        "sport_title": next(x["title"] for x in _SPORTS if x["key"] == sport),
        "commence_time": start.isoformat(timespec="seconds").replace("+00:00", "Z"),
        "home_team": home, "away_team": away, "bookmakers": books,
    }


def sports() -> List[dict]:
    return _SPORTS


def odds(sport: str) -> List[dict]:
    return [_event(sport, i, h, a) for i, (h, a) in enumerate(_MATCHES.get(sport, []))]


def event_odds(sport: str, event_id: str) -> Dict:
    for i, (h, a) in enumerate(_MATCHES.get(sport, [])):
        if f"demo-{sport}-{i}" == event_id:
            return _event(sport, i, h, a, with_team_totals=True)
    return {}
