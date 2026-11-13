"""Сборка клиента и поллера из переменных окружения — общая для сервера и CLI."""
import os
from typing import List, Optional

from app.odds_client import OddsClient
from app.poller import Poller
from app.store import Store


def env_sports() -> Optional[List[str]]:
    raw = os.getenv("TRACK_SPORTS", "")
    sports = [s.strip() for s in raw.split(",") if s.strip()]
    return sports or None


def build_client() -> OddsClient:
    return OddsClient(
        api_key=os.getenv("ODDS_API_KEY", "").strip(),
        regions=os.getenv("ODDS_REGIONS", "eu"),
        cache_ttl=int(os.getenv("CACHE_TTL_SECONDS", "120")),
    )


def build_poller(client: OddsClient, store: Store) -> Poller:
    return Poller(
        client, store,
        default_sports=env_sports() or ["soccer_epl"],
        min_quota=int(os.getenv("MIN_QUOTA_REMAINING", "10")),
        base_hours=float(os.getenv("POLL_BASE_HOURS", "3")),
        near_hours=float(os.getenv("POLL_NEAR_HOURS", "1")),
        near_window_hours=float(os.getenv("NEAR_KICKOFF_HOURS", "8")),
    )
