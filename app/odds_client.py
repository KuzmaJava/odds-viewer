"""Клиент The Odds API v4 с простым кэшем в памяти (экономит квоту)."""
import time
from typing import Any, Dict, List, Optional, Tuple

import httpx

from app import demo

BASE_URL = "https://api.the-odds-api.com/v4"


class OddsApiError(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status


class OddsClient:
    def __init__(self, api_key: str, regions: str = "eu", cache_ttl: int = 120):
        self.api_key = api_key
        self.regions = regions
        self.cache_ttl = cache_ttl
        self._cache: Dict[Tuple, Tuple[float, Any]] = {}
        self.quota: Dict[str, Optional[str]] = {"remaining": None, "used": None, "last_cost": None}
        self._http = httpx.Client(base_url=BASE_URL, timeout=15)

    @property
    def demo(self) -> bool:
        return not self.api_key

    def _get(self, path: str, params: Dict[str, Any], ttl: Optional[int] = None) -> Any:
        key = (path, tuple(sorted(params.items())))
        cached = self._cache.get(key)
        ttl = self.cache_ttl if ttl is None else ttl
        if cached and time.time() - cached[0] < ttl:
            return cached[1]

        resp = self._http.get(path, params={**params, "apiKey": self.api_key})
        self.quota = {
            "remaining": resp.headers.get("x-requests-remaining"),
            "used": resp.headers.get("x-requests-used"),
            "last_cost": resp.headers.get("x-requests-last"),
        }
        if resp.status_code != 200:
            try:
                msg = resp.json().get("message", resp.text)
            except ValueError:
                msg = resp.text
            raise OddsApiError(resp.status_code, msg)

        data = resp.json()
        self._cache[key] = (time.time(), data)
        return data

    def sports(self) -> List[dict]:
        """Список видов спорта/лиг. Не тратит квоту."""
        if self.demo:
            return demo.sports()
        return self._get("/sports", {}, ttl=3600)

    def odds(self, sport: str, markets: str = "h2h,totals") -> List[dict]:
        """Коэффициенты по всем матчам лиги. Стоимость = кол-во рынков × кол-во регионов."""
        if self.demo:
            return demo.odds(sport)
        return self._get(
            f"/sports/{sport}/odds",
            {"regions": self.regions, "markets": markets, "oddsFormat": "decimal", "dateFormat": "iso"},
        )

    def event_odds(self, sport: str, event_id: str, markets: str) -> dict:
        """Доп. рынки (team_totals, double_chance...) для одного матча."""
        if self.demo:
            return demo.event_odds(sport, event_id)
        return self._get(
            f"/sports/{sport}/events/{event_id}/odds",
            {"regions": self.regions, "markets": markets, "oddsFormat": "decimal", "dateFormat": "iso"},
        )
