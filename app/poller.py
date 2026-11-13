"""Фоновый опрос Pinnacle: раз в N минут сохраняем 1X2 по всем предстоящим матчам отслеживаемых лиг."""
import asyncio
import logging
import threading
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, List, Optional

from app.markets import normalize_event
from app.odds_client import OddsClient
from app.store import Store

log = logging.getLogger("poller")

BOOKMAKER = "pinnacle"


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def iso(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def parse_iso(s: str) -> datetime:
    return datetime.fromisoformat(s.replace("Z", "+00:00"))


class Poller:
    def __init__(self, client: OddsClient, store: Store, interval_minutes: int, min_quota: int,
                 default_sports: List[str]):
        self.client = client
        self.store = store
        self.interval = timedelta(minutes=interval_minutes)
        self.min_quota = min_quota
        self.default_sports = default_sports
        self.last_error: Optional[str] = None
        self.last_result: Optional[Dict[str, Any]] = None
        self._lock = threading.Lock()

    # --- настройки ---
    @property
    def sports(self) -> List[str]:
        return self.store.get_setting("tracked_sports", self.default_sports)

    @sports.setter
    def sports(self, value: List[str]) -> None:
        self.store.set_setting("tracked_sports", value)

    @property
    def last_poll_at(self) -> Optional[str]:
        return self.store.get_setting("last_poll_at")

    def next_poll_at(self) -> datetime:
        last = self.last_poll_at
        return parse_iso(last) + self.interval if last else utc_now()

    # --- опрос ---
    def _quota_low(self) -> bool:
        remaining = self.client.quota.get("remaining")
        try:
            return remaining is not None and float(remaining) < self.min_quota
        except ValueError:
            return False

    def poll_once(self) -> Dict[str, Any]:
        """Один проход по всем отслеживаемым лигам. Стоит 1 запрос квоты на лигу."""
        with self._lock:
            now = utc_now()
            ts = iso(now)
            saved, errors = 0, []
            for sport in self.sports:
                if self._quota_low():
                    errors.append(f"квота почти исчерпана (< {self.min_quota}), опрос остановлен")
                    break
                try:
                    events = self.client.odds(sport, markets="h2h", bookmakers=BOOKMAKER, use_cache=False)
                except Exception as e:  # noqa: BLE001 — одна лига не должна ронять весь опрос
                    log.exception("poll %s failed", sport)
                    errors.append(f"{sport}: {e}")
                    continue
                for raw in events:
                    # Фиксируем только прематч: после начала матча движение — это уже лайв
                    if parse_iso(raw["commence_time"]) <= now:
                        continue
                    ev = normalize_event(raw)
                    bm = next((b for b in ev["bookmakers"] if b["key"] == BOOKMAKER), None)
                    if not bm or not bm["h2h"]["home"]:
                        continue
                    self.store.save_snapshot(
                        {k: ev[k] for k in ("id", "sport_key", "sport_title", "home", "away", "commence_time")},
                        ts, bm["h2h"]["home"], bm["h2h"]["draw"], bm["h2h"]["away"],
                    )
                    saved += 1
            self.store.set_setting("last_poll_at", ts)
            self.last_error = "; ".join(errors) or None
            self.last_result = {"at": ts, "snapshots_saved": saved, "errors": errors}
            log.info("poll done: %s", self.last_result)
            return self.last_result

    async def run_forever(self) -> None:
        while True:
            wait = (self.next_poll_at() - utc_now()).total_seconds()
            if wait > 0:
                await asyncio.sleep(min(wait, 60))  # просыпаемся раз в минуту, чтобы учесть ручной опрос
                continue
            try:
                await asyncio.to_thread(self.poll_once)
            except Exception as e:  # noqa: BLE001
                log.exception("poll failed")
                self.last_error = str(e)
                await asyncio.sleep(60)
