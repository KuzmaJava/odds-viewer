"""Опрос Pinnacle: у каждого матча своё расписание снимков, все исходы снимаются в один момент.

Снимок матча = один запрос по матчу: 1X2 + все линии тоталов и фор (+ инд. тоталы, если есть), ≈ 3 запроса квоты.
- открытие: как только матч появился (бесплатная проверка списка каждые 15 мин) — плюс реальный двойной шанс;
- раз в 12 ч, пока до старта больше 13 ч;
- за 13 ч до старта и дальше раз в 3 ч: 13, 10, 7, 4 ч;
- закрытие примерно за час до старта — плюс реальный двойной шанс.
Запрос по всей лиге (1X2) делается только при первом подключении лиги.
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
                 league_markets: str = "h2h",
                 open_markets: str = "h2h,double_chance,alternate_totals,alternate_spreads,team_totals,alternate_team_totals",
                 lines_markets: str = "h2h,alternate_totals,alternate_spreads,team_totals,alternate_team_totals",
                 close_markets: str = "h2h,double_chance,alternate_totals,alternate_spreads,team_totals,alternate_team_totals",
                 lines_far_hours: float = 12, lines_near_hours: float = 3, lines_near_window_hours: float = 13,
                 close_window_min: float = 60, cycle_seconds: int = 900,
                 league_mode: bool = False, league_snapshot_markets: str = "h2h,totals"):
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
        self.lines_markets = lines_markets
        self.lines_far = timedelta(hours=lines_far_hours)
        self.lines_near = timedelta(hours=lines_near_hours)
        self.lines_near_window = timedelta(hours=lines_near_window_hours)
        self.close_window = timedelta(minutes=close_window_min)
        self.cycle_seconds = cycle_seconds
        self.league_mode = league_mode
        self.league_snapshot_markets = league_snapshot_markets
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

    def _pinnacle(self, raw: Dict[str, Any]) -> Dict[str, Any]:
        bm = next((b for b in raw.get("bookmakers") or [] if b.get("key") == BOOKMAKER), None)
        # В режиме лиги API отдаёт одну основную линию — храним её, даже если она четвертная
        return flat_prices(bm, raw["home_team"], raw["away_team"], keep_quarter=self.league_mode) if bm else {}

    def _event_snapshot(self, raw: Dict[str, Any], markets: str, ts: str, field: str) -> bool:
        """Запрос рынков одного матча; True, если Pinnacle уже дал линию (пустой ответ бесплатен)."""
        data = self.client.event_odds(raw["sport_key"], raw["id"], markets=markets, bookmakers=BOOKMAKER)
        prices = self._pinnacle({**raw, **(data or {})}) if data else {}
        if not prices:
            return False
        self.store.save_prices(self._meta({**raw, **data}), ts, prices)
        self.store.mark(raw["id"], field, ts)
        if any("@" in code for code in prices):
            self.store.mark(raw["id"], "lines_at", ts)
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
                if raw["id"] in known:
                    self.store.update_commence(raw["id"], raw["commence_time"])  # перенос матча
                    continue
                if parse_iso(raw["commence_time"]) <= now:
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
                if self._event_gone(e, ev):
                    continue
                errors.append(f"{ev['home']} — {ev['away']} (закрытие): {e}")
        return closed

    def lines_due(self, ev: Dict[str, Any], now: datetime, slack: timedelta = timedelta(minutes=10)) -> bool:
        """Пора ли снять все линии матча: раз в 12 ч, а когда до старта <= 13 ч — раз в 3 ч
        (13, 10, 7, 4 ч до старта; за час — уже линия закрытия)."""
        to_start = parse_iso(ev["commence_time"]) - now
        if to_start <= self.close_window or ev.get("closed_at"):
            return False
        last = ev.get("lines_at") or self.store.last_lines_ts(ev["id"]) or ev.get("opened_at")
        if not last:
            return True
        last_dt = parse_iso(last)
        near = to_start <= self.lines_near_window
        if near and parse_iso(ev["commence_time"]) - last_dt > self.lines_near_window:
            return True  # только что вошли в последние 13 ч
        return now - last_dt >= (self.lines_near if near else self.lines_far) - slack

    def next_snapshot_at(self, ev: Dict[str, Any], now: datetime) -> Optional[datetime]:
        """Когда по расписанию следующий снимок матча (для статуса на сайте)."""
        start = parse_iso(ev["commence_time"])
        if start <= now or ev.get("closed_at"):
            return None
        close_at = start - self.close_window
        last = ev.get("lines_at") or self.store.last_lines_ts(ev["id"]) or ev.get("opened_at")
        if not last:
            return now
        last_dt, near_start = parse_iso(last), start - self.lines_near_window
        due = min(last_dt + self.lines_far, near_start) if last_dt < near_start else last_dt + self.lines_near
        return max(min(due, close_at), now)

    def next_snapshot(self, now: Optional[datetime] = None) -> Optional[str]:
        now = now or utc_now()
        times = [t for ev in self.store.upcoming(iso(now)) if ev["sport_key"] in self.sports
                 for t in [self.next_snapshot_at(ev, now)] if t]
        return iso(min(times)) if times else None

    def _snap_lines(self, now: datetime, ts: str, errors: List[str], force: bool = False) -> int:
        taken = 0
        for ev in self.store.upcoming(iso(now)):
            if ev["sport_key"] not in self.sports or ev.get("closed_at"):
                continue
            if not force and not self.lines_due(ev, now):
                continue
            if self._quota_low():
                errors.append(self._quota_error())
                break
            raw = {"id": ev["id"], "sport_key": ev["sport_key"], "sport_title": ev["sport_title"],
                   "home_team": ev["home"], "away_team": ev["away"], "commence_time": ev["commence_time"]}
            try:
                taken += self._event_snapshot(raw, self.lines_markets, ts, "lines_at")
            except Exception as e:  # noqa: BLE001
                if self._event_gone(e, ev):
                    continue
                errors.append(f"{ev['home']} — {ev['away']} (линии): {e}")
        return taken

    def _event_gone(self, e: Exception, ev: Dict[str, Any]) -> bool:
        """Матч сняли с линии (перенос, отмена): API отвечает «Event not found». Больше его не запрашиваем."""
        if "event not found" not in str(e).lower():
            return False
        self.store.mark(ev["id"], "closed_at", iso(utc_now()))
        log.info("event gone: %s — %s", ev["home"], ev["away"])
        return True

    def _finish(self, result: Dict[str, Any]) -> Dict[str, Any]:
        if self.client.quota.get("remaining") is not None:
            self.store.set_setting("quota", self.client.quota)
        self.last_error = "; ".join(result["errors"]) or None
        self.last_result = result
        self.store.set_setting("last_result", result)
        log.info("poll done: %s", result)
        return result

    def poll_once(self, sports: Optional[List[str]] = None) -> Dict[str, Any]:
        """Плановый опрос лиг (по умолчанию — всех отслеживаемых): 1X2 одним запросом на лигу."""
        with self._lock:
            now = utc_now()
            ts = iso(now)
            errors: List[str] = []
            saved, polled, opened = self._poll_leagues(self.sports if sports is None else sports, now, ts, errors)
            return self._finish({"at": ts, "sports": polled, "snapshots_saved": saved,
                                 "opened": opened, "closed": 0, "errors": errors})

    def run_cycle(self, force: bool = False, slack: timedelta = timedelta(0),
                  now: Optional[datetime] = None) -> Dict[str, Any]:
        """Один запуск (cron каждые 15 мин): новые матчи -> новые лиги -> закрытие -> снимки по расписанию.
        force — снять все предстоящие матчи сейчас, не глядя на расписание.
        changed=False, если ничего не сохранено и нет новых ошибок — тогда коммитить нечего."""
        if self.league_mode:
            return self._run_league_cycle(force, now)
        with self._lock:
            now = now or utc_now()
            ts = iso(now)
            errors: List[str] = []
            opened = self._discover(self.sports, now, ts, errors)
            # Запрос по лиге — только для только что подключённых лиг (снять всё, что уже висит)
            first = [sp for sp in self.sports if not self.sport_last_poll(sp)]
            saved, polled, opened2 = self._poll_leagues(first, now, ts, errors) if first else (0, [], 0)
            closed = self._close_lines(now, ts, errors)
            lines = self._snap_lines(now, ts, errors, force=force)
            if lines or closed or opened or opened2:
                self.store.set_setting("last_poll_at", ts)
            result = {"at": ts, "sports": polled, "snapshots_saved": saved,
                      "opened": opened + opened2, "closed": closed, "lines": lines, "errors": errors}
            changed = bool(polled or result["opened"] or closed or lines or
                           errors != (self.store.get_setting("last_result") or {}).get("errors"))
            if changed:
                self._finish(result)
            return {**result, "changed": changed}

    # --- режим «запрос по лиге»: один запрос снимает все матчи лиги (1X2 + основной тотал) ---
    def _league_due(self, sport: str, now: datetime, errors: List[str]) -> Optional[str]:
        """Почему пора снять лигу (или None): новая лига, новые матчи в бесплатном списке,
        расписание какого-то матча (12 ч / за 13 ч — 3 ч) или его закрытие."""
        if not self.sport_last_poll(sport):
            return "новая лига"
        try:
            listed = self.client.events(sport)
        except Exception as e:  # noqa: BLE001
            errors.append(f"{sport} (список матчей): {e}")
            listed = []
        known = self.store.event_ids(sport)
        for raw in listed:
            if raw["id"] in known:
                self.store.update_commence(raw["id"], raw["commence_time"])
            elif parse_iso(raw["commence_time"]) > now:
                return "новые матчи"
        for ev in self.store.upcoming(iso(now)):
            if ev["sport_key"] != sport or ev.get("closed_at"):
                continue
            if parse_iso(ev["commence_time"]) - now <= self.close_window:
                return "закрытие"
            if self.lines_due(ev, now):
                return "расписание"
        return None

    def _run_league_cycle(self, force: bool, now: Optional[datetime]) -> Dict[str, Any]:
        with self._lock:
            now = now or utc_now()
            ts = iso(now)
            errors: List[str] = []
            saved, polled, opened, closed = 0, [], 0, 0
            for sport in self.sports:
                reason = "вручную" if force else self._league_due(sport, now, errors)
                if not reason:
                    continue
                if self._quota_low():
                    errors.append(self._quota_error())
                    break
                known = self.store.event_ids(sport)
                try:
                    events = self.client.odds(sport, markets=self.league_snapshot_markets, bookmakers=BOOKMAKER,
                                              use_cache=False)
                except Exception as e:  # noqa: BLE001
                    errors.append(f"{sport}: {e}")
                    continue
                for raw in events:
                    start = parse_iso(raw["commence_time"])
                    if start <= now:
                        continue
                    prices = self._pinnacle(raw)
                    if not prices:
                        continue
                    self.store.save_prices(self._meta(raw), ts, prices)
                    if raw["id"] not in known:
                        self.store.mark(raw["id"], "opened_at", ts)
                        opened += 1
                    self.store.mark(raw["id"], "lines_at", ts)
                    if start - now <= self.close_window and not (self.store.event(raw["id"]) or {}).get("closed_at"):
                        self.store.mark(raw["id"], "closed_at", ts)
                        closed += 1
                    saved += 1
                self.store.set_setting(f"last_poll_at:{sport}", ts)
                polled.append(sport)
            if polled:
                self.store.set_setting("last_poll_at", ts)
            result = {"at": ts, "sports": polled, "snapshots_saved": saved, "opened": opened,
                      "closed": closed, "lines": 0, "errors": errors}
            changed = bool(polled or errors != (self.store.get_setting("last_result") or {}).get("errors"))
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
