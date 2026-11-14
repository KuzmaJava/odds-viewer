"""Фоновый опрос Pinnacle с адаптивным расписанием по каждой лиге.

- Обычно лига опрашивается раз в `base_hours` (по умолчанию 3 ч).
- Если в лиге есть матч, который начнётся в ближайшие `near_window_hours` (8 ч),
  лига опрашивается раз в `near_hours` (1 ч) — до начала этого матча.

Плановый опрос: один запрос /odds на лигу (1X2 + тотал + фора, стоимость = число вернувшихся рынков).
Кроме того, каждый цикл (15 мин):
- бесплатно проверяется список матчей; новый матч сразу снимается полным набором рынков
  (1X2, реальный двойной шанс, тотал, фора, инд. тоталы) — это открытие линии;
- за час до старта снимается реальный двойной шанс — линия закрытия.
"""
import asyncio
import logging
import threading
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, List, Optional, Tuple

from app.markets import flat_prices
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
                 base_hours: float = 3, near_hours: float = 1, near_window_hours: float = 8,
                 league_markets: str = "h2h,totals,spreads",
                 open_markets: str = "h2h,double_chance,totals,spreads,team_totals",
                 close_markets: str = "h2h,double_chance,team_totals",
                 close_window_min: float = 60, cycle_seconds: int = 900):
        self.client = client
        self.store = store
        self.default_sports = default_sports
        self.min_quota = min_quota
        self.base = timedelta(hours=base_hours)
        self.near = timedelta(hours=near_hours)
        self.near_window = timedelta(hours=near_window_hours)
        self.league_markets = league_markets
        self.open_markets = open_markets
        self.close_markets = close_markets
        self.close_window = timedelta(minutes=close_window_min)
        self.cycle_seconds = cycle_seconds
        self.last_error: Optional[str] = None
        self.last_result: Optional[Dict[str, Any]] = None
        self._lock = threading.RLock()

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

    def due_sports(self, now: datetime, slack: timedelta = timedelta(0)) -> List[str]:
        """Лиги, которые пора опрашивать. slack — допуск для запуска по cron (раз в час),
        чтобы опрос не съезжал на следующий запуск из-за задержки в несколько минут."""
        return [s for s in self.sports if self.next_poll_for(s, now) <= now + slack]

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

    def _quota_error(self) -> str:
        return f"квота почти исчерпана (< {self.min_quota}), платные запросы остановлены"

    @staticmethod
    def _meta(raw: Dict[str, Any]) -> Dict[str, Any]:
        return {"id": raw["id"], "sport_key": raw["sport_key"], "sport_title": raw.get("sport_title"),
                "home": raw["home_team"], "away": raw["away_team"], "commence_time": raw["commence_time"]}

    @staticmethod
    def _pinnacle(raw: Dict[str, Any]) -> Dict[str, Any]:
        bm = next((b for b in raw.get("bookmakers") or [] if b.get("key") == BOOKMAKER), None)
        return flat_prices(bm, raw["home_team"], raw["away_team"]) if bm else {}

    def _event_snapshot(self, raw: Dict[str, Any], markets: str, ts: str, field: str) -> bool:
        """Запрос рынков одного матча; True, если Pinnacle уже дал линию (пустой ответ бесплатен)."""
        data = self.client.event_odds(raw["sport_key"], raw["id"], markets=markets, bookmakers=BOOKMAKER)
        prices = self._pinnacle({**raw, **(data or {})}) if data else {}
        if not prices:
            return False
        self.store.save_prices(self._meta({**raw, **data}), ts, prices)
        self.store.mark(raw["id"], field, ts)
        return True

    def _discover(self, sports: List[str], now: datetime, ts: str, errors: List[str]) -> int:
        """Новые матчи: бесплатный список /events каждые 15 мин; как только у Pinnacle появилась
        линия — снимаем полный набор рынков (это и есть открытие). Лиги, которые ещё ни разу не
        опрашивались, пропускаем: их первый опрос и так снимет всё, что уже висит."""
        opened = 0
        for sport in sports:
            if not self.sport_last_poll(sport):
                continue
            try:
                listed = self.client.events(sport)
            except Exception as e:  # noqa: BLE001
                errors.append(f"{sport} (список матчей): {e}")
                continue
            known = self.store.event_ids(sport)
            for raw in listed:
                if raw["id"] in known or parse_iso(raw["commence_time"]) <= now:
                    continue
                if self._quota_low():
                    errors.append(self._quota_error())
                    return opened
                try:
                    opened += self._event_snapshot(raw, self.open_markets, ts, "opened_at")
                except Exception as e:  # noqa: BLE001
                    errors.append(f"{sport} {raw['home_team']} — {raw['away_team']}: {e}")
        return opened

    def _poll_leagues(self, sports: List[str], now: datetime, ts: str, errors: List[str]) -> Tuple[int, List[str], int]:
        saved, polled, opened = 0, [], 0
        for sport in sports:
            if self._quota_low():
                errors.append(self._quota_error())
                break
            first_poll = not self.sport_last_poll(sport)
            known = self.store.event_ids(sport)
            try:
                events = self.client.odds(sport, markets=self.league_markets, bookmakers=BOOKMAKER, use_cache=False)
            except Exception as e:  # noqa: BLE001 — одна лига не должна ронять весь опрос
                log.exception("poll %s failed", sport)
                errors.append(f"{sport}: {e}")
                continue
            for raw in events:
                # Фиксируем только прематч: после начала матча движение — это уже лайв
                if parse_iso(raw["commence_time"]) <= now:
                    continue
                prices = self._pinnacle(raw)
                if not prices:
                    continue
                new = raw["id"] not in known
                if new and not first_poll and not self._quota_low():
                    # Линия появилась между проверками списка — снимаем открытие полным набором рынков
                    try:
                        if self._event_snapshot(raw, self.open_markets, ts, "opened_at"):
                            opened += 1
                            continue
                    except Exception as e:  # noqa: BLE001
                        errors.append(f"{sport} {raw['home_team']} — {raw['away_team']}: {e}")
                self.store.save_prices(self._meta(raw), ts, prices)
                if new:
                    self.store.mark(raw["id"], "opened_at", ts)
                saved += 1
            self.store.set_setting(f"last_poll_at:{sport}", ts)
            polled.append(sport)
        if polled:
            self.store.set_setting("last_poll_at", ts)
        return saved, polled, opened

    def _close_lines(self, now: datetime, ts: str, errors: List[str]) -> int:
        """Перед стартом — реальный двойной шанс (и 1X2 в тот же момент) для линии закрытия."""
        closed = 0
        for ev in self.store.to_close(iso(now), iso(now + self.close_window)):
            if ev["sport_key"] not in self.sports:
                continue
            if self._quota_low():
                errors.append(self._quota_error())
                break
            raw = {"id": ev["id"], "sport_key": ev["sport_key"], "sport_title": ev["sport_title"],
                   "home_team": ev["home"], "away_team": ev["away"], "commence_time": ev["commence_time"]}
            try:
                closed += self._event_snapshot(raw, self.close_markets, ts, "closed_at")
            except Exception as e:  # noqa: BLE001
                errors.append(f"{ev['home']} — {ev['away']} (закрытие): {e}")
        return closed

    def _finish(self, result: Dict[str, Any]) -> Dict[str, Any]:
        if self.client.quota.get("remaining") is not None:
            self.store.set_setting("quota", self.client.quota)
        self.last_error = "; ".join(result["errors"]) or None
        self.last_result = result
        self.store.set_setting("last_result", result)
        log.info("poll done: %s", result)
        return result

    def poll_once(self, sports: Optional[List[str]] = None) -> Dict[str, Any]:
        """Плановый опрос лиг (по умолчанию — всех отслеживаемых): 1X2, тотал и фора одним запросом на лигу."""
        with self._lock:
            now = utc_now()
            ts = iso(now)
            errors: List[str] = []
            saved, polled, opened = self._poll_leagues(self.sports if sports is None else sports, now, ts, errors)
            return self._finish({"at": ts, "sports": polled, "snapshots_saved": saved,
                                 "opened": opened, "closed": 0, "errors": errors})

    def run_cycle(self, force: bool = False, slack: timedelta = timedelta(0),
                  now: Optional[datetime] = None) -> Dict[str, Any]:
        """Один запуск (cron каждые 15 мин): новые матчи -> плановый опрос лиг -> линии закрытия.
        changed=False, если ничего не сохранено и нет новых ошибок — тогда коммитить нечего."""
        with self._lock:
            now = now or utc_now()
            ts = iso(now)
            errors: List[str] = []
            opened = self._discover(self.sports, now, ts, errors)
            due = list(self.sports) if force else self.due_sports(now, slack)
            saved, polled, opened2 = self._poll_leagues(due, now, ts, errors) if due else (0, [], 0)
            closed = self._close_lines(now, ts, errors)
            result = {"at": ts, "sports": polled, "snapshots_saved": saved,
                      "opened": opened + opened2, "closed": closed, "errors": errors}
            changed = bool(polled or result["opened"] or closed or
                           errors != (self.store.get_setting("last_result") or {}).get("errors"))
            if changed:
                self._finish(result)
            return {**result, "changed": changed}

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
                await asyncio.to_thread(self.run_cycle)
            except Exception as e:  # noqa: BLE001
                log.exception("poll failed")
                self.last_error = str(e)
            await asyncio.sleep(self.cycle_seconds)
