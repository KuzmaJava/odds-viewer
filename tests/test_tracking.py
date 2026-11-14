from datetime import timedelta

from app.movement import change_pct, drops, event_movement, movements
from app.poller import Poller, iso, utc_now
from app.store import Store


def _raw_event(eid, commence, home_price, draw_price, away_price):
    return {
        "id": eid, "sport_key": "soccer_epl", "sport_title": "EPL", "commence_time": commence,
        "home_team": "Arsenal", "away_team": "Chelsea",
        "bookmakers": [{"key": "pinnacle", "title": "Pinnacle", "markets": [{"key": "h2h", "outcomes": [
            {"name": "Arsenal", "price": home_price}, {"name": "Chelsea", "price": away_price},
            {"name": "Draw", "price": draw_price},
        ]}]}],
    }


class FakeClient:
    def __init__(self, responses):
        self.responses = list(responses)
        self.quota = {"remaining": "400"}
        self.calls = []

    def odds(self, sport, markets, bookmakers, use_cache):
        self.calls.append((sport, markets, bookmakers, use_cache))
        return self.responses.pop(0)

    def events(self, sport):
        return []


def _poller(client, store):
    return Poller(client, store, default_sports=["soccer_epl"], min_quota=10,
                  base_hours=3, near_hours=1, near_window_hours=8)


def test_change_pct():
    assert change_pct(2.0, 1.8) == -10.0
    assert change_pct(2.0, 2.2) == 10.0
    assert change_pct(None, 2.0) is None


def test_poll_saves_only_upcoming_pinnacle_h2h():
    future = iso(utc_now() + timedelta(days=1))
    past = iso(utc_now() - timedelta(hours=1))
    client = FakeClient([[_raw_event("a", future, 2.0, 3.4, 3.8), _raw_event("live", past, 1.5, 4.0, 6.0)]])
    store = Store(":memory:")

    result = _poller(client, store).poll_once()

    assert result["snapshots_saved"] == 1
    assert client.calls == [("soccer_epl", "h2h,totals,spreads", "pinnacle", False)]
    assert store.history("a")[0]["p"]["home"] == [2.0, None]
    assert store.history("live") == []
    assert store.get_setting("last_poll_at") == result["at"]


def test_movement_from_opening_to_current_and_drops_list():
    store = Store(":memory:")
    future = iso(utc_now() + timedelta(days=1))
    ev = {"id": "a", "sport_key": "soccer_epl", "sport_title": "EPL",
          "home": "Arsenal", "away": "Chelsea", "commence_time": future}
    store.save_snapshot(ev, "2026-11-13T10:00:00Z", 2.0, 3.5, 4.0)
    store.save_snapshot(ev, "2026-11-13T10:30:00Z", 1.9, 3.5, 4.4)
    store.save_snapshot(ev, "2026-11-13T11:00:00Z", 1.8, 3.6, 4.6)

    rows = store.events(iso(utc_now()))
    mv = event_movement(rows[0], store.snapshots(rows[0]["id"]))
    by = {o["code"]: o for o in mv["outcomes"]}
    assert mv["snapshots"] == 3
    assert by["home"]["opening"] == 2.0 and by["home"]["current"] == 1.8
    assert by["home"]["change_pct"] == -10.0
    assert by["away"]["change_pct"] == 15.0
    assert by["1X"]["derived"] is True

    found = drops([mv], min_drop_pct=3)
    assert [d["code"] for d in found][0] == "home"  # самая сильная просадка первой
    assert all(d["change_pct"] <= -3 for d in found)
    assert "away" not in [d["code"] for d in found]


def test_started_events_filtered_out():
    store = Store(":memory:")
    past = iso(utc_now() - timedelta(hours=2))
    ev = {"id": "p", "sport_key": "s", "sport_title": "S", "home": "A", "away": "B", "commence_time": past}
    store.save_snapshot(ev, "2026-11-13T10:00:00Z", 2.0, 3.0, 4.0)
    assert store.events(iso(utc_now())) == []
    assert len(store.events(None)) == 1


def test_poll_stops_when_quota_low():
    client = FakeClient([[]])
    client.quota = {"remaining": "5"}
    result = _poller(client, Store(":memory:")).poll_once()
    assert client.calls == []
    assert "квота" in result["errors"][0]


def test_tracked_sports_persisted():
    store = Store(":memory:")
    p = _poller(FakeClient([]), store)
    assert p.sports == ["soccer_epl"]
    p.sports = ["soccer_spain_la_liga"]
    assert _poller(FakeClient([]), store).sports == ["soccer_spain_la_liga"]


def _event_at(store, eid, commence):
    store.save_snapshot({"id": eid, "sport_key": "soccer_epl", "sport_title": "EPL",
                         "home": "A", "away": "B", "commence_time": iso(commence)},
                        "2026-11-13T10:00:00Z", 2.0, 3.0, 4.0)


def test_interval_is_3h_when_no_match_soon_and_1h_within_8h():
    store = Store(":memory:")
    p = _poller(FakeClient([]), store)
    now = utc_now()
    assert p.interval_for("soccer_epl", now) == timedelta(hours=3)  # матчей нет
    _event_at(store, "far", now + timedelta(hours=20))
    assert p.interval_for("soccer_epl", now) == timedelta(hours=3)
    _event_at(store, "near", now + timedelta(hours=7))
    assert p.interval_for("soccer_epl", now) == timedelta(hours=1)
    # после начала ближнего матча — снова раз в 3 ч
    assert p.interval_for("soccer_epl", now + timedelta(hours=7, minutes=1)) == timedelta(hours=3)


def test_due_sports_respects_per_league_interval():
    store = Store(":memory:")
    p = _poller(FakeClient([]), store)
    p.sports = ["soccer_epl", "soccer_spain_la_liga"]
    now = utc_now()
    assert p.due_sports(now) == ["soccer_epl", "soccer_spain_la_liga"]  # ни разу не опрашивались

    two_h_ago = iso(now - timedelta(hours=2))
    store.set_setting("last_poll_at:soccer_epl", two_h_ago)
    store.set_setting("last_poll_at:soccer_spain_la_liga", two_h_ago)
    assert p.due_sports(now) == []  # обе на 3-часовом интервале

    _event_at(store, "near", now + timedelta(hours=5))  # матч EPL через 5 ч -> EPL раз в час
    assert p.due_sports(now) == ["soccer_epl"]


def test_poll_once_only_given_sports_and_records_per_league_time():
    client = FakeClient([[]])
    store = Store(":memory:")
    p = _poller(client, store)
    p.sports = ["soccer_epl", "soccer_spain_la_liga"]
    res = p.poll_once(["soccer_spain_la_liga"])
    assert [c[0] for c in client.calls] == ["soccer_spain_la_liga"]
    assert res["sports"] == ["soccer_spain_la_liga"]
    assert p.sport_last_poll("soccer_spain_la_liga") == res["at"]
    assert p.sport_last_poll("soccer_epl") is None


def test_rises_list_sorted_strongest_first_and_excludes_drops():
    from app.movement import rises
    store = Store(":memory:")
    future = iso(utc_now() + timedelta(days=1))
    ev = {"id": "a", "sport_key": "soccer_epl", "sport_title": "EPL",
          "home": "Arsenal", "away": "Chelsea", "commence_time": future}
    store.save_snapshot(ev, "2026-11-13T10:00:00Z", 2.0, 3.5, 4.0)
    store.save_snapshot(ev, "2026-11-13T11:00:00Z", 1.8, 3.6, 4.6)  # П1 -10%, X +2.86%, П2 +15%

    mv = movements(store)[0]
    found = rises([mv], min_rise_pct=3)
    codes = [r["code"] for r in found]
    assert codes[0] == "away" and found[0]["change_pct"] == 15.0
    assert "home" not in codes          # просадка сюда не попадает
    assert "draw" not in codes          # +2.86% меньше порога
    assert all(r["change_pct"] >= 3 for r in found)
    assert [r["change_pct"] for r in found] == sorted((r["change_pct"] for r in found), reverse=True)

    # нулевой порог: всё, что выросло, но не «без изменений»
    assert "draw" in [r["code"] for r in rises([mv], 0)]
