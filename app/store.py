"""SQLite-хранилище: матчи, снимки коэффициентов Pinnacle, настройки."""
import json
import sqlite3
import threading
from pathlib import Path
from typing import Any, Dict, List, Optional

SCHEMA = """
CREATE TABLE IF NOT EXISTS events (
    id            TEXT PRIMARY KEY,
    sport_key     TEXT NOT NULL,
    sport_title   TEXT,
    home          TEXT NOT NULL,
    away          TEXT NOT NULL,
    commence_time TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS snapshots (
    event_id TEXT NOT NULL REFERENCES events(id),
    ts       TEXT NOT NULL,
    home     REAL,
    draw     REAL,
    away     REAL,
    PRIMARY KEY (event_id, ts)
);
CREATE TABLE IF NOT EXISTS settings (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
"""


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
    def save_snapshot(self, event: Dict[str, Any], ts: str, home: Optional[float],
                      draw: Optional[float], away: Optional[float]) -> None:
        with self._lock, self._conn:
            self._conn.execute(
                "INSERT INTO events(id, sport_key, sport_title, home, away, commence_time) "
                "VALUES (:id, :sport_key, :sport_title, :home, :away, :commence_time) "
                "ON CONFLICT(id) DO UPDATE SET commence_time = excluded.commence_time",
                event,
            )
            self._conn.execute(
                "INSERT OR REPLACE INTO snapshots(event_id, ts, home, draw, away) VALUES (?, ?, ?, ?, ?)",
                (event["id"], ts, home, draw, away),
            )

    # --- reads ---
    def events_with_range(self, since_commence: Optional[str] = None) -> List[Dict[str, Any]]:
        """Матчи с первым и последним снимком (для расчёта движения)."""
        sql = """
        SELECT e.*, agg.n, agg.first_ts, agg.last_ts,
               f.home AS open_home, f.draw AS open_draw, f.away AS open_away,
               l.home AS cur_home,  l.draw AS cur_draw,  l.away AS cur_away
        FROM events e
        JOIN (SELECT event_id, COUNT(*) n, MIN(ts) first_ts, MAX(ts) last_ts
              FROM snapshots GROUP BY event_id) agg ON agg.event_id = e.id
        JOIN snapshots f ON f.event_id = e.id AND f.ts = agg.first_ts
        JOIN snapshots l ON l.event_id = e.id AND l.ts = agg.last_ts
        """
        params: tuple = ()
        if since_commence:
            sql += " WHERE e.commence_time > ?"
            params = (since_commence,)
        sql += " ORDER BY e.commence_time, e.home"
        with self._lock:
            return [dict(r) for r in self._conn.execute(sql, params).fetchall()]

    def history(self, event_id: str) -> List[Dict[str, Any]]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT ts, home, draw, away FROM snapshots WHERE event_id = ? ORDER BY ts", (event_id,)
            ).fetchall()
        return [dict(r) for r in rows]

    def event(self, event_id: str) -> Optional[Dict[str, Any]]:
        with self._lock:
            row = self._conn.execute("SELECT * FROM events WHERE id = ?", (event_id,)).fetchone()
        return dict(row) if row else None
