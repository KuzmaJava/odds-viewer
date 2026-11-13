"""Статус трекера и данные для статического сайта (GitHub Pages)."""
from typing import Any, Dict, List, Optional

from app.movement import event_movement
from app.poller import Poller, iso, utc_now


def status_dict(poller: Poller, demo: bool) -> Dict[str, Any]:
    schedule = poller.schedule()
    store = poller.store
    quota = poller.client.quota if poller.client.quota.get("remaining") is not None else store.get_setting("quota", {})
    last_result = poller.last_result or store.get_setting("last_result")
    errors = (last_result or {}).get("errors") or []
    return {
        "demo": demo,
        "tracked_sports": poller.sports,
        "policy": {
            "base_hours": poller.base.total_seconds() / 3600,
            "near_hours": poller.near.total_seconds() / 3600,
            "near_window_hours": poller.near_window.total_seconds() / 3600,
        },
        "schedule": schedule,
        "last_poll_at": poller.last_poll_at,
        "next_poll_at": min((s["next_poll_at"] for s in schedule), default=None),
        "last_result": last_result,
        "last_error": poller.last_error or ("; ".join(errors) or None),
        "quota": quota,
    }


def site_data(poller: Poller, demo: bool, sports: List[dict], repo: Optional[str]) -> Dict[str, Any]:
    """Всё, что нужно странице в статическом режиме, одним файлом data.json."""
    rows = poller.store.events_with_range(None)
    return {
        "generated_at": iso(utc_now()),
        "repo": repo,
        "status": status_dict(poller, demo),
        "events": [event_movement(r) for r in rows],
        "history": {r["id"]: poller.store.history(r["id"]) for r in rows},
        "sports": sports,
    }
