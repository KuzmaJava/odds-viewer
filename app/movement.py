"""Движение коэффициентов: открытие -> текущее значение по каждому исходу всех рынков.

- Тоталы и форы: каждая нечетвертная линия — отдельный исход ("over@2.5", "ah_home@-1"), открытие и
  текущее значение сравниваются в пределах одной линии. main=True — основная линия (Б и М ближе всего).
- Реальный двойной шанс Pinnacle снимается только при появлении матча и перед стартом (он дорогой).
  В остальных снимках он рассчитывается из 1X2 того же снимка — такие значения помечены derived.
"""
from typing import Any, Dict, List, Optional, Tuple

from app.markets import derive_double_chance, split_code

# (базовый код, подпись, рынок)
OUTCOMES = [
    ("home", "П1", "1x2"), ("draw", "X", "1x2"), ("away", "П2", "1x2"),
    ("1X", "1X", "dc"), ("12", "12", "dc"), ("X2", "X2", "dc"),
    ("over", "ТБ", "total"), ("under", "ТМ", "total"),
    ("ah_home", "Ф1", "spread"), ("ah_away", "Ф2", "spread"),
    ("it1_over", "ИТ1Б", "team_total"), ("it1_under", "ИТ1М", "team_total"),
    ("it2_over", "ИТ2Б", "team_total"), ("it2_under", "ИТ2М", "team_total"),
]
BASE = {code: (i, label, market) for i, (code, label, market) in enumerate(OUTCOMES)}
MARKETS = {"1x2": "1X2", "dc": "Двойной шанс", "total": "Тотал", "spread": "Фора", "team_total": "Инд. тотал"}
DC_CODES = ("1X", "12", "X2")
# пары исходов одной линии: (код A, код B, знак линии B относительно A)
PAIRS = [("over", "under", 1), ("ah_home", "ah_away", -1), ("it1_over", "it1_under", 1), ("it2_over", "it2_under", 1)]

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


def _fmt_point(base: str, point: float) -> str:
    s = f"{point:g}"
    return f"+{s}" if base.startswith("ah_") and point > 0 else s


def _main_lines(current: Dict[str, Tuple[float, Optional[float], bool]]) -> set:
    """Основная линия каждого рынка по текущим ценам: где коэффициенты пары ближе всего."""
    main = set()
    for a, b, sign in PAIRS:
        best = None
        for code, (price, point, _) in current.items():
            if split_code(code)[0] != a or point is None:
                continue
            other = current.get(f"{b}@{sign * point + 0.0:g}")
            if other and (best is None or abs(price - other[0]) < best[0]):
                best = (abs(price - other[0]), code, f"{b}@{sign * point + 0.0:g}")
        if best:
            main.update(best[1:])
    return main


def event_movement(row: Dict[str, Any], snaps: List[Snap]) -> Dict[str, Any]:
    """row — строка из Store.events(), snaps — Store.snapshots(id)."""
    full = [(ts, _with_dc(p)) for ts, p in snaps]
    first: Dict[str, Tuple[str, Tuple[float, Optional[float], bool]]] = {}
    last: Dict[str, Tuple[str, Tuple[float, Optional[float], bool]]] = {}
    count: Dict[str, int] = {}
    for ts, prices in full:
        for code, v in prices.items():
            first.setdefault(code, (ts, v))
            last[code] = (ts, v)
            count[code] = count.get(code, 0) + 1
    main = _main_lines({c: v for c, (_, v) in last.items()})

    def order(code: str):
        base, point = split_code(code)
        return (BASE.get(base, (99,))[0], point if point is not None else 0)

    outcomes = []
    for code in sorted(last, key=order):
        base, point = split_code(code)
        if base not in BASE:
            continue
        _, label, market = BASE[base]
        open_ts, (opening, _, open_derived) = first[code]
        cur_ts, (cur, _, cur_derived) = last[code]
        outcomes.append({
            "code": code, "base": base, "market": market,
            "label": label if point is None else f"{label} {_fmt_point(base, point)}",
            "point": point, "main": code in main or point is None,
            "opening": opening, "current": cur, "change_pct": change_pct(opening, cur),
            "open_ts": open_ts, "cur_ts": cur_ts, "n": count[code],
            "derived": open_derived or cur_derived,
        })
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
