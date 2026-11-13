"""Фоновый опрос Pinnacle с адаптивным расписанием по каждой лиге.

- Обычно лига опрашивается раз в `base_hours` (по умолчанию 3 ч).
- Если в лиге есть матч, который начнётся в ближайшие `near_window_hours` (8 ч),
  лига опрашивается раз в `near_hours` (1 ч) — до начала этого матча.

Один запрос /odds возвращает все матчи лиги, поэтому стоимость = 1 запрос квоты на лигу за опрос.
"""
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
    def __init__(self, client: OddsClient, store: Store, default_sports: List[str], min_quota: int = 10,
                 base_hours: float = 3, near_hours: float = 1, near_window_hours: float = 8):
        self.client = client
        self.store = store
        self.default_sports = default_sports
        self.min_quota = min_quota
        self.base = timedelta(hours=base_hours)
        self.near = timedelta(hours=near_hours)
        self.near_window = timedelta(hours=near_window_hours)
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

    def sport_last_poll(self, sport: str) -> Optional[str]:
        return self.store.get_setting(f"last_poll_at:{sport}")

    # --- расписание ---
    def interval_for(self, sport: str, now: datetime) -> timedelta:
        nxt = self.store.next_commence(sport, iso(now))
        if nxt and parse_iso(nxt) - now <= self.near_window:
            return self.near
        return self.base

    def next_poll_for(self, sport: str, now: datetime) -> datetime:
        last = self.sport_last_poll(sport)
        if not last:
            return now
        return parse_iso(last) + self.interval_for(sport, now)

    def due_sports(self, now: datetime) -> List[str]:
        return [s for s in self.sports if self.next_poll_for(s, now) <= now]

    def schedule(self, now: Optional[datetime] = None) -> List[Dict[str, Any]]:
        now = now or utc_now()
        res = []
        for s in self.sports:
            nxt = self.store.next_commence(s, iso(now))
            res.append({
                "sport": s,
                "last_poll_at": self.sport_last_poll(s),
                "next_poll_at": iso(max(self.next_poll_for(s, now), now)),
                "interval_hours": self.interval_for(s, now).total_seconds() / 3600,
                "next_match_at": nxt,
            })
        return res

    # --- опрос ---
    def _quota_low(self) -> bool:
        remaining = self.client.quota.get("remaining")
        try:
            return remaining is not None and float(remaining) < self.min_quota
        except ValueError:
            return False

    def poll_once(self, sports: Optional[List[str]] = None) -> Dict[str, Any]:
        """Опрос указанных лиг (по умолчанию — всех отслеживаемых). 1 запрос квоты на лигу."""
        with self._lock:
            now = utc_now()
            ts = iso(now)
            saved, polled, errors = 0, [], []
            for sport in (self.sports if sports is None else sports):
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
                self.store.set_setting(f"last_poll_at:{sport}", ts)
                polled.append(sport)
            if polled:
                self.store.set_setting("last_poll_at", ts)
            self.last_error = "; ".join(errors) or None
            self.last_result = {"at": ts, "sports": polled, "snapshots_saved": saved, "errors": errors}
            log.info("poll done: %s", self.last_result)
            return self.last_result

    def _migrate_global_last_poll(self) -> None:
        """Старые версии хранили одно общее время опроса — переносим его на каждую лигу."""
        last = self.last_poll_at
        if not last:
            return
        for s in self.sports:
            if not self.sport_last_poll(s):
                self.store.set_setting(f"last_poll_at:{s}", last)

    async def run_forever(self) -> None:
        self._migrate_global_last_poll()
        while True:
            try:
                due = self.due_sports(utc_now())
                if due:
                    await asyncio.to_thread(self.poll_once, due)
            except Exception as e:  # noqa: BLE001
                log.exception("poll failed")
                self.last_error = str(e)
            await asyncio.sleep(60)
