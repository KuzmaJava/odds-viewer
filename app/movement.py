"""Движение коэффициентов: начальное значение -> текущее, по исходам и двойным шансам."""
from typing import Any, Dict, List, Optional

from app.markets import derive_double_chance

# (код исхода, подпись)
OUTCOMES = [("home", "П1"), ("draw", "X"), ("away", "П2"), ("1X", "1X"), ("12", "12"), ("X2", "X2")]


def prices(home: Optional[float], draw: Optional[float], away: Optional[float]) -> Dict[str, Optional[float]]:
    """Все 6 цен: 1X2 от Pinnacle + двойной шанс, рассчитанный из 1X2."""
    res: Dict[str, Optional[float]] = {"home": home, "draw": draw, "away": away}
    res.update(derive_double_chance(res))
    return res


def change_pct(opening: Optional[float], current: Optional[float]) -> Optional[float]:
    if not opening or not current:
        return None
    return round((current - opening) / opening * 100, 2)


def event_movement(row: Dict[str, Any]) -> Dict[str, Any]:
    """row — строка из Store.events_with_range()."""
    opening = prices(row["open_home"], row["open_draw"], row["open_away"])
    current = prices(row["cur_home"], row["cur_draw"], row["cur_away"])
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
        "outcomes": [
            {
                "code": code,
                "label": label,
                "opening": opening[code],
                "current": current[code],
                "change_pct": change_pct(opening[code], current[code]),
                "derived": code in ("1X", "12", "X2"),
            }
            for code, label in OUTCOMES
        ],
    }


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
