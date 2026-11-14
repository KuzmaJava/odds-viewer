"""Движение коэффициентов: открытие -> текущее значение по каждому исходу всех рынков.

- Для тоталов, фор и инд. тоталов сравниваем цены одной и той же линии: «открытие» — первый снимок
  с той же линией, что сейчас; если линия сдвигалась, это видно по open_point.
- Реальный двойной шанс Pinnacle снимается только при появлении матча и перед стартом (он дорогой).
  В остальных снимках он рассчитывается из 1X2 того же снимка — такие значения помечены derived.
"""
from typing import Any, Dict, List, Optional, Tuple

from app.markets import derive_double_chance

# (код, подпись, рынок)
OUTCOMES = [
    ("home", "П1", "1x2"), ("draw", "X", "1x2"), ("away", "П2", "1x2"),
    ("1X", "1X", "dc"), ("12", "12", "dc"), ("X2", "X2", "dc"),
    ("over", "ТБ", "total"), ("under", "ТМ", "total"),
    ("ah_home", "Ф1", "spread"), ("ah_away", "Ф2", "spread"),
    ("it1_over", "ИТ1Б", "team_total"), ("it1_under", "ИТ1М", "team_total"),
    ("it2_over", "ИТ2Б", "team_total"), ("it2_under", "ИТ2М", "team_total"),
]
MARKETS = {"1x2": "1X2", "dc": "Двойной шанс", "total": "Тотал", "spread": "Фора", "team_total": "Инд. тотал"}
DC_CODES = ("1X", "12", "X2")

Snap = Tuple[str, Dict[str, Tuple[float, Optional[float]]]]


def change_pct(opening: Optional[float], current: Optional[float]) -> Optional[float]:
    if not opening or not current:
        return None
    return round((current - opening) / opening * 100, 2)


def _with_dc(prices: Dict[str, Tuple[float, Optional[float]]]) -> Dict[str, Tuple[float, Optional[float], bool]]:
    """Цены снимка + двойной шанс: реальный, если снят, иначе рассчитанный из 1X2 (derived=True)."""
    res = {c: (p, pt, False) for c, (p, pt) in prices.items()}
    if not all(c in prices for c in DC_CODES) and all(c in prices for c in ("home", "draw", "away")):
        derived = derive_double_chance({c: prices[c][0] for c in ("home", "draw", "away")})
        for c in DC_CODES:
            if c not in res and derived[c]:
                res[c] = (derived[c], None, True)
    return res


def outcome_movement(snaps: List[Snap], code: str) -> Optional[Dict[str, Any]]:
    seen = [(ts, p[code]) for ts, p in ((ts, _with_dc(pr)) for ts, pr in snaps) if code in p]
    if not seen:
        return None
    cur_ts, (cur, point, cur_derived) = seen[-1]
    same_line = [s for s in seen if s[1][1] == point]
    open_ts, (opening, _, open_derived) = same_line[0]
    return {
        "opening": opening, "current": cur, "change_pct": change_pct(opening, cur),
        "point": point, "open_point": seen[0][1][1],
        "open_ts": open_ts, "cur_ts": cur_ts,
        "derived": open_derived or cur_derived,
    }


def event_movement(row: Dict[str, Any], snaps: List[Snap]) -> Dict[str, Any]:
    """row — строка из Store.events(), snaps — Store.snapshots(id)."""
    outcomes = []
    for code, label, market in OUTCOMES:
        mv = outcome_movement(snaps, code)
        if mv:
            outcomes.append({"code": code, "label": label, "market": market, **mv})
    return {
        "id": row["id"],
        "sport_key": row["sport_key"],
        "sport_title": row["sport_title"],
        "home": row["home"],
        "away": row["away"],
        "commence_time": row["commence_time"],
        "snapshots": row["n"],
        "first_ts": row["first_ts"],
        "last_ts": row["last_ts"],
        "opened_at": row.get("opened_at"),
        "closed_at": row.get("closed_at"),
        "outcomes": outcomes,
    }


def movements(store, since_commence: Optional[str] = None) -> List[Dict[str, Any]]:
    return [event_movement(r, store.snapshots(r["id"])) for r in store.events(since_commence)]


def _moves(events: List[Dict[str, Any]], min_pct: float, rising: bool) -> List[Dict[str, Any]]:
    """Плоский список исходов, у которых коэффициент сдвинулся от открытия минимум на min_pct %
    вниз (rising=False) или вверх (rising=True). Самые сильные движения сверху."""
    threshold = abs(min_pct)
    res = []
    for ev in events:
        for o in ev["outcomes"]:
            ch = o["change_pct"]
            if ch is None or ch == 0:
                continue
            if (rising and ch >= threshold) or (not rising and ch <= -threshold):
                res.append({
                    "event_id": ev["id"], "sport_title": ev["sport_title"],
                    "home": ev["home"], "away": ev["away"], "commence_time": ev["commence_time"],
                    "snapshots": ev["snapshots"], "first_ts": ev["first_ts"], "last_ts": ev["last_ts"],
                    **o,
                })
    return sorted(res, key=lambda d: d["change_pct"], reverse=rising)


def drops(events: List[Dict[str, Any]], min_drop_pct: float) -> List[Dict[str, Any]]:
    """Просадки: коэффициент упал минимум на min_drop_pct %."""
    return _moves(events, min_drop_pct, rising=False)


def rises(events: List[Dict[str, Any]], min_rise_pct: float) -> List[Dict[str, Any]]:
    """Повышения: коэффициент вырос минимум на min_rise_pct %."""
    return _moves(events, min_rise_pct, rising=True)
