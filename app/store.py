"""SQLite-хранилище: матчи, снимки цен Pinnacle по всем рынкам, настройки.

Снимок — набор цен, снятых в один момент (ts). Каждая цена — строка (код исхода, линия, коэффициент):
  home/draw/away, 1X/12/X2, over/under (линия тотала), ah_home/ah_away (фора), it1_over/... (инд. тоталы).
"""
import json
import sqlite3
import threading
from pathlib import Path
from typing import Any, Dict, List, Optional, Set, Tuple

SCHEMA = """
CREATE TABLE IF NOT EXISTS events (
    id            TEXT PRIMARY KEY,
    sport_key     TEXT NOT NULL,
    sport_title   TEXT,
    home          TEXT NOT NULL,
    away          TEXT NOT NULL,
    commence_time TEXT NOT NULL,
    opened_at     TEXT,   -- снят полный набор рынков при появлении матча
    closed_at     TEXT    -- снят реальный двойной шанс перед стартом
);
CREATE TABLE IF NOT EXISTS prices (
    event_id TEXT NOT NULL REFERENCES events(id),
    ts       TEXT NOT NULL,
    code     TEXT NOT NULL,
    point    REAL,
    price    REAL NOT NULL,
    PRIMARY KEY (event_id, ts, code)
);
CREATE TABLE IF NOT EXISTS settings (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
"""

META = ("id", "sport_key", "sport_title", "home", "away", "commence_time")
Prices = Dict[str, Tuple[float, Optional[float]]]  # code -> (price, point)


class Store:
    def __init__(self, path: str):
        if path != ":memory:":
            Path(path).parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._lock = threading.Lock()
        with self._lock:
            self._conn.executescript(SCHEMA)

    # --- settings ---
    def get_setting(self, key: str, default: Any = None) -> Any:
        with self._lock:
            row = self._conn.execute("SELECT value FROM settings WHERE key = ?", (key,)).fetchone()
        return json.loads(row["value"]) if row else default

    def set_setting(self, key: str, value: Any) -> None:
        with self._lock, self._conn:
            self._conn.execute(
                "INSERT INTO settings(key, value) VALUES (?, ?) "
                "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                (key, json.dumps(value)),
            )

    # --- writes ---
    def save_prices(self, event: Dict[str, Any], ts: str, prices: Prices) -> None:
        meta = {k: event[k] for k in META}
        with self._lock, self._conn:
            self._conn.execute(
                "INSERT INTO events(id, sport_key, sport_title, home, away, commence_time) "
                "VALUES (:id, :sport_key, :sport_title, :home, :away, :commence_time) "
                "ON CONFLICT(id) DO UPDATE SET commence_time = excluded.commence_time",
                meta,
            )
            self._conn.executemany(
                "INSERT OR REPLACE INTO prices(event_id, ts, code, point, price) VALUES (?, ?, ?, ?, ?)",
                [(meta["id"], ts, code, point, price) for code, (price, point) in prices.items()],
            )

    def save_snapshot(self, event: Dict[str, Any], ts: str, home: Optional[float],
                      draw: Optional[float], away: Optional[float]) -> None:
        """Только 1X2 (старый формат снимка)."""
        self.save_prices(event, ts, {c: (p, None) for c, p in (("home", home), ("draw", draw), ("away", away)) if p})

    def mark(self, event_id: str, field: str, ts: str) -> None:
        assert field in ("opened_at", "closed_at")
        with self._lock, self._conn:
            self._conn.execute(f"UPDATE events SET {field} = ? WHERE id = ?", (ts, event_id))

    # --- reads ---
    def events(self, since_commence: Optional[str] = None) -> List[Dict[str, Any]]:
        """Матчи, по которым есть хоть один снимок: метаданные + n/first_ts/last_ts."""
        sql = """
        SELECT e.*, agg.n, agg.first_ts, agg.last_ts
        FROM events e
        JOIN (SELECT event_id, COUNT(DISTINCT ts) n, MIN(ts) first_ts, MAX(ts) last_ts
              FROM prices GROUP BY event_id) agg ON agg.event_id = e.id
        """
        params: tuple = ()
        if since_commence:
            sql += " WHERE e.commence_time > ?"
            params = (since_commence,)
        sql += " ORDER BY e.commence_time, e.home"
        with self._lock:
            return [dict(r) for r in self._conn.execute(sql, params).fetchall()]

    def snapshots(self, event_id: str) -> List[Tuple[str, Prices]]:
        """[(ts, {code: (price, point)}), ...] по времени."""
        with self._lock:
            rows = self._conn.execute(
                "SELECT ts, code, point, price FROM prices WHERE event_id = ? ORDER BY ts", (event_id,)
            ).fetchall()
        res: List[Tuple[str, Prices]] = []
        for r in rows:
            if not res or res[-1][0] != r["ts"]:
                res.append((r["ts"], {}))
            res[-1][1][r["code"]] = (r["price"], r["point"])
        return res

    def history(self, event_id: str) -> List[Dict[str, Any]]:
        """Для страницы: [{"ts": ..., "p": {code: [price, point]}}]."""
        return [{"ts": ts, "p": {c: [pr, pt] for c, (pr, pt) in prices.items()}}
                for ts, prices in self.snapshots(event_id)]

    def event_ids(self, sport_key: Optional[str] = None) -> Set[str]:
        sql, params = "SELECT id FROM events", ()
        if sport_key:
            sql, params = sql + " WHERE sport_key = ?", (sport_key,)
        with self._lock:
            return {r["id"] for r in self._conn.execute(sql, params)}

    def to_close(self, after: str, before: str) -> List[Dict[str, Any]]:
        """Матчи, стартующие в (after, before], по которым ещё не снимали линию закрытия."""
        with self._lock:
            return [dict(r) for r in self._conn.execute(
                "SELECT * FROM events WHERE closed_at IS NULL AND commence_time > ? AND commence_time <= ? "
                "ORDER BY commence_time", (after, before))]

    def next_commence(self, sport_key: str, after: str) -> Optional[str]:
        """Время ближайшего ещё не начавшегося матча лиги (по данным последних опросов)."""
        with self._lock:
            row = self._conn.execute(
                "SELECT MIN(commence_time) t FROM events WHERE sport_key = ? AND commence_time > ?",
                (sport_key, after),
            ).fetchone()
        return row["t"] if row else None

    def event(self, event_id: str) -> Optional[Dict[str, Any]]:
        with self._lock:
            row = self._conn.execute("SELECT * FROM events WHERE id = ?", (event_id,)).fetchone()
        return dict(row) if row else None

    # --- JSON-состояние (для GitHub Actions: база живёт в репозитории как текстовый файл) ---
    def dump(self, drop_commenced_before: Optional[str] = None) -> Dict[str, Any]:
        """Всё содержимое базы. Матчи, начавшиеся раньше drop_commenced_before, отбрасываются.
        snapshots: [[ts, {code: [price, point]}], ...] (point опускается, если его нет)."""
        with self._lock:
            settings = {r["key"]: json.loads(r["value"]) for r in self._conn.execute("SELECT * FROM settings")}
            events = [dict(r) for r in self._conn.execute("SELECT * FROM events ORDER BY commence_time, id")]
        if drop_commenced_before:
            events = [e for e in events if e["commence_time"] >= drop_commenced_before]
        out = []
        for e in events:
            snaps = [[ts, {c: [pr] if pt is None else [pr, pt] for c, (pr, pt) in sorted(prices.items())}]
                     for ts, prices in self.snapshots(e["id"])]
            out.append({**{k: v for k, v in e.items() if v is not None}, "snapshots": snaps})
        return {"version": 2, "settings": settings, "events": out}

    def load(self, data: Dict[str, Any]) -> None:
        for key, value in data.get("settings", {}).items():
            self.set_setting(key, value)
        v1 = data.get("version", 1) < 2
        for ev in data.get("events", []):
            meta = {k: ev[k] for k in META}
            snaps = ev.get("snapshots", [])
            for snap in snaps:
                if v1:  # [ts, home, draw, away]
                    self.save_snapshot(meta, *snap)
                else:
                    ts, prices = snap
                    self.save_prices(meta, ts, {c: (v[0], v[1] if len(v) > 1 else None) for c, v in prices.items()})
            if not snaps:
                continue
            # Матчи из старого формата уже были в базе до появления «открытия» — считаем их открытыми
            for field in ("opened_at", "closed_at"):
                value = ev.get(field) or (snaps[0][0] if v1 and field == "opened_at" else None)
                if value:
                    self.mark(meta["id"], field, value)
