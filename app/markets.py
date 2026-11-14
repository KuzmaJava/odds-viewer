"""Приведение ответа The Odds API к простой структуре для таблицы.

Рынки:
  h2h          -> П1 / X / П2
  double_chance-> 1X / 12 / X2 (если конторы его не дают — считаем из 1X2)
  totals       -> общий тотал (основная линия + все линии)
  team_totals  -> индивидуальные тоталы (только через event-эндпоинт)
"""
from typing import Any, Dict, List, Optional, Tuple


def _round(x: Optional[float]) -> Optional[float]:
    return round(x, 3) if x else None


def derive_double_chance(h2h: Dict[str, Optional[float]]) -> Dict[str, Optional[float]]:
    """1X = 1 / (1/П1 + 1/X) и т.д. Маржа конторы при этом сохраняется (оценка)."""
    p1, px, p2 = h2h.get("home"), h2h.get("draw"), h2h.get("away")

    def combine(a: Optional[float], b: Optional[float]) -> Optional[float]:
        if not a or not b:
            return None
        return _round(1 / (1 / a + 1 / b))

    return {"1X": combine(p1, px), "12": combine(p1, p2), "X2": combine(px, p2)}


def margin(prices: List[Optional[float]]) -> Optional[float]:
    """Маржа конторы в процентах: (сумма 1/k − 1) × 100."""
    if not prices or any(not p for p in prices):
        return None
    return round((sum(1 / p for p in prices) - 1) * 100, 2)


def _parse_h2h(outcomes: List[dict], home: str, away: str) -> Dict[str, Optional[float]]:
    res: Dict[str, Optional[float]] = {"home": None, "draw": None, "away": None}
    for o in outcomes:
        name = o.get("name")
        if name == home:
            res["home"] = o.get("price")
        elif name == away:
            res["away"] = o.get("price")
        elif name == "Draw":
            res["draw"] = o.get("price")
    return res


def _parse_double_chance(outcomes: List[dict], home: str, away: str) -> Dict[str, Optional[float]]:
    res: Dict[str, Optional[float]] = {"1X": None, "12": None, "X2": None}
    for o in outcomes:
        name = o.get("name", "")
        has_home, has_away, has_draw = home in name, away in name, "Draw" in name
        if has_home and has_draw:
            res["1X"] = o.get("price")
        elif has_home and has_away:
            res["12"] = o.get("price")
        elif has_away and has_draw:
            res["X2"] = o.get("price")
    return res


def _parse_over_under(outcomes: List[dict]) -> List[Dict[str, Any]]:
    """Группирует Over/Under по линии -> [{point, over, under}], отсортировано по линии."""
    lines: Dict[float, Dict[str, Any]] = {}
    for o in outcomes:
        point = o.get("point")
        if point is None:
            continue
        line = lines.setdefault(point, {"point": point, "over": None, "under": None})
        if o.get("name") == "Over":
            line["over"] = o.get("price")
        elif o.get("name") == "Under":
            line["under"] = o.get("price")
    return [lines[p] for p in sorted(lines)]


def main_line(lines: List[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    """Основная линия — где Б и М ближе всего друг к другу."""
    full = [l for l in lines if l["over"] and l["under"]]
    if not full:
        return None
    return min(full, key=lambda l: abs(l["over"] - l["under"]))


def _parse_team_totals(outcomes: List[dict], home: str, away: str) -> Dict[str, List[Dict[str, Any]]]:
    by_team: Dict[str, List[dict]] = {"home": [], "away": []}
    for o in outcomes:
        team = o.get("description")
        if team == home:
            by_team["home"].append(o)
        elif team == away:
            by_team["away"].append(o)
    return {side: _parse_over_under(items) for side, items in by_team.items()}


def normalize_bookmaker(bm: dict, home: str, away: str) -> Dict[str, Any]:
    markets = {m["key"]: m.get("outcomes", []) for m in bm.get("markets", [])}

    h2h = _parse_h2h(markets.get("h2h", []), home, away)
    if "double_chance" in markets:
        dc = _parse_double_chance(markets["double_chance"], home, away)
        dc_derived = False
    else:
        dc = derive_double_chance(h2h)
        dc_derived = True

    totals = _parse_over_under(markets.get("totals", []))
    result: Dict[str, Any] = {
        "key": bm.get("key"),
        "title": bm.get("title"),
        "last_update": bm.get("last_update"),
        "h2h": h2h,
        "h2h_margin": margin([h2h["home"], h2h["draw"], h2h["away"]] if h2h["draw"] else [h2h["home"], h2h["away"]]),
        "double_chance": dc,
        "double_chance_derived": dc_derived,
        "totals": totals,
        "totals_main": main_line(totals),
    }
    if "team_totals" in markets:
        tt = _parse_team_totals(markets["team_totals"], home, away)
        result["team_totals"] = {
            side: {"lines": lines, "main": main_line(lines)} for side, lines in tt.items()
        }
    return result


def normalize_event(ev: dict) -> Dict[str, Any]:
    home, away = ev.get("home_team", ""), ev.get("away_team", "")
    return {
        "id": ev.get("id"),
        "sport_key": ev.get("sport_key"),
        "sport_title": ev.get("sport_title"),
        "commence_time": ev.get("commence_time"),
        "home": home,
        "away": away,
        "bookmakers": [normalize_bookmaker(bm, home, away) for bm in ev.get("bookmakers", [])],
    }


# --- плоские цены для трекера: код исхода -> (коэффициент, линия) ---

def _main_spread(outcomes: List[dict], home: str, away: str) -> Optional[Dict[str, Any]]:
    """Основная фора: пара (фора хозяев, фора гостей) с самыми близкими коэффициентами."""
    pairs: Dict[float, Dict[str, Any]] = {}
    for o in outcomes:
        point = o.get("point")
        if point is None:
            continue
        if o.get("name") == home:
            pairs.setdefault(point, {"point": point})["home"] = o.get("price")
        elif o.get("name") == away:
            pairs.setdefault(-point, {"point": -point})["away"] = o.get("price")
    full = [p for p in pairs.values() if p.get("home") and p.get("away")]
    return min(full, key=lambda p: abs(p["home"] - p["away"])) if full else None


def flat_prices(bm: dict, home: str, away: str) -> Dict[str, Tuple[float, Optional[float]]]:
    """Все цены одной конторы: {"home": (1.75, None), "over": (1.95, 2.5), "ah_home": (1.98, -0.75), ...}.
    Для тоталов и фор берётся основная линия."""
    markets = {m["key"]: m.get("outcomes", []) for m in bm.get("markets", [])}
    res: Dict[str, Tuple[float, Optional[float]]] = {}

    def put(code: str, price: Optional[float], point: Optional[float] = None) -> None:
        if price:
            res[code] = (price, point)

    if "h2h" in markets:
        for code, price in _parse_h2h(markets["h2h"], home, away).items():
            put(code, price)
    if "double_chance" in markets:
        for code, price in _parse_double_chance(markets["double_chance"], home, away).items():
            put(code, price)
    if "totals" in markets:
        line = main_line(_parse_over_under(markets["totals"]))
        if line:
            put("over", line["over"], line["point"])
            put("under", line["under"], line["point"])
    if "spreads" in markets:
        sp = _main_spread(markets["spreads"], home, away)
        if sp:
            put("ah_home", sp["home"], sp["point"])
            put("ah_away", sp["away"], -sp["point"])
    if "team_totals" in markets:
        for side, lines in _parse_team_totals(markets["team_totals"], home, away).items():
            line = main_line(lines)
            if line:
                prefix = "it1" if side == "home" else "it2"
                put(f"{prefix}_over", line["over"], line["point"])
                put(f"{prefix}_under", line["under"], line["point"])
    return res
