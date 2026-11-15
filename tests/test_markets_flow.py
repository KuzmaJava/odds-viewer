"""Все рынки: разбор, движение по линии, открытие при появлении матча, линия закрытия."""
from datetime import timedelta

from app.markets import flat_prices
from app.movement import movements
from app.poller import Poller, iso, utc_now
from app.store import Store


def _bm(h2h=(1.75, 3.81, 4.98), dc=None, total=(2.5, 1.95, 1.93), spread=(-0.5, 1.98, 1.93)):
    markets = [{"key": "h2h", "outcomes": [{"name": "Kosovo", "price": h2h[0]}, {"name": "Draw", "price": h2h[1]},
                                           {"name": "Israel", "price": h2h[2]}]}]
    if dc:
        markets.append({"key": "double_chance", "outcomes": [
            {"name": "Kosovo or Draw", "price": dc[0]}, {"name": "Israel or Kosovo", "price": dc[1]},
            {"name": "Israel or Draw", "price": dc[2]}]})
    if total:
        markets.append({"key": "totals", "outcomes": [{"name": "Over", "price": total[1], "point": total[0]},
                                                      {"name": "Under", "price": total[2], "point": total[0]}]})
    if spread:
        markets.append({"key": "spreads", "outcomes": [
            {"name": "Kosovo", "price": spread[1], "point": spread[0]},
            {"name": "Israel", "price": spread[2], "point": -spread[0]}]})
    return {"key": "pinnacle", "title": "Pinnacle", "markets": markets}


def _raw(eid, commence, bm=None):
    ev = {"id": eid, "sport_key": "soccer_epl", "sport_title": "EPL", "commence_time": iso(commence),
          "home_team": "Kosovo", "away_team": "Israel"}
    if bm is not None:
        ev["bookmakers"] = [bm] if bm else []
    return ev


def test_flat_prices_all_markets():
    p = flat_prices(_bm(dc=(1.2, 1.3, 2.15)), "Kosovo", "Israel")
    assert p["home"] == (1.75, None) and p["X2"] == (2.15, None) and p["12"] == (1.3, None)
    assert p["over@2.5"] == (1.95, 2.5) and p["under@2.5"] == (1.93, 2.5)
    assert p["ah_home@-0.5"] == (1.98, -0.5) and p["ah_away@0.5"] == (1.93, 0.5)


def test_all_lines_without_quarter_lines():
    bm = {"markets": [
        {"key": "alternate_spreads", "outcomes": [
            {"name": "Kosovo", "point": 0.0, "price": 1.33}, {"name": "Israel", "point": -0.0, "price": 3.5},
            {"name": "Kosovo", "point": -0.75, "price": 2.03}, {"name": "Israel", "point": 0.75, "price": 1.88},
            {"name": "Kosovo", "point": -1.0, "price": 2.44}, {"name": "Israel", "point": 1.0, "price": 1.6}]},
        {"key": "alternate_totals", "outcomes": [
            {"name": "Over", "point": 2.25, "price": 1.67}, {"name": "Under", "point": 2.25, "price": 2.28},
            {"name": "Over", "point": 3.0, "price": 2.57}, {"name": "Under", "point": 3.0, "price": 1.54}]},
    ]}
    p = flat_prices(bm, "Kosovo", "Israel")
    assert set(p) == {"ah_home@0", "ah_away@0", "ah_home@-1", "ah_away@1", "over@3", "under@3"}


def test_each_line_tracked_separately_and_dc_real_vs_derived():
    s = Store(":memory:")
    ev = {"id": "a", "sport_key": "soccer_epl", "sport_title": "EPL", "home": "K", "away": "I",
          "commence_time": iso(utc_now() + timedelta(days=1))}
    s.save_prices(ev, "2026-11-14T08:00:00Z", {"home": (2.0, None), "draw": (3.4, None), "away": (3.8, None),
                                               "1X": (1.3, None), "12": (1.33, None), "X2": (1.8, None),
                                               "over@2.5": (1.90, 2.5), "under@2.5": (1.98, 2.5),
                                               "over@3": (2.6, 3.0), "under@3": (1.5, 3.0)})
    s.save_prices(ev, "2026-11-14T11:00:00Z", {"home": (1.9, None), "draw": (3.5, None), "away": (4.0, None),
                                               "over@2.5": (1.80, 2.5), "under@2.5": (2.05, 2.5),
                                               "over@3": (2.5, 3.0), "under@3": (1.55, 3.0)})
    mv = movements(s)[0]
    by = {o["code"]: o for o in mv["outcomes"]}
    assert by["over@2.5"]["label"] == "ТБ 2.5" and by["over@2.5"]["change_pct"] == -5.26
    assert by["over@3"]["opening"] == 2.6 and by["over@3"]["current"] == 2.5
    assert by["over@2.5"]["main"] and not by["over@3"]["main"]  # 1.80/2.05 ближе, чем 2.5/1.55
    # ДШ: открытие реальное, текущее — расчёт из последнего 1X2
    assert by["1X"]["opening"] == 1.3 and by["1X"]["derived"] is True
    assert by["home"]["change_pct"] == -5.0
    assert [o["code"] for o in mv["outcomes"]][:3] == ["home", "draw", "away"]


def test_early_v2_state_main_line_codes_are_upgraded():
    s = Store(":memory:")
    s.load({"version": 2, "events": [{"id": "a", "sport_key": "soccer_epl", "sport_title": "EPL", "home": "K",
            "away": "I", "commence_time": "2099-01-01T00:00:00Z",
            "snapshots": [["2026-11-14T08:00:00Z", {"home": [2.0], "over": [1.9, 2.5], "ah_home": [1.98, -0.75]}]]}]})
    assert set(s.history("a")[0]["p"]) == {"home", "over@2.5"}


class FlowClient:
    demo = False

    def __init__(self):
        self.quota = {"remaining": "400"}
        self.listed, self.league, self.per_event = [], [], {}
        self.calls = []

    def events(self, sport):
        self.calls.append(("events", sport))
        return [{k: v for k, v in e.items() if k != "bookmakers"} for e in self.listed]

    def odds(self, sport, markets, bookmakers, use_cache):
        self.calls.append(("odds", sport, markets))
        return self.league

    def event_odds(self, sport, event_id, markets, bookmakers):
        self.calls.append(("event", event_id, markets))
        return self.per_event.get(event_id, {})


def _poller(client, store):
    return Poller(client, store, default_sports=["soccer_epl"])


def test_first_league_poll_does_not_spend_on_event_calls():
    c, s = FlowClient(), Store(":memory:")
    c.league = [_raw("old", utc_now() + timedelta(days=2), _bm())]
    c.listed = list(c.league)
    res = _poller(c, s).run_cycle()
    assert [x[0] for x in c.calls] == ["odds"]  # лига новая: ни списка, ни запросов по матчам
    assert res["snapshots_saved"] == 1 and s.event("old")["opened_at"] == res["at"]


def test_new_match_gets_full_opening_snapshot_as_soon_as_pinnacle_lists_it():
    c, s = FlowClient(), Store(":memory:")
    p = _poller(c, s)
    p.run_cycle()  # первый опрос лиги (матчей нет)
    later = utc_now() + timedelta(minutes=15)

    # Матч появился в списке, но Pinnacle линию ещё не дал — пустой ответ, ничего не сохраняем
    c.listed = [_raw("new", utc_now() + timedelta(days=3))]
    c.calls.clear()
    res = p.run_cycle(now=later)
    assert ("event", "new", p.open_markets) in c.calls and not res["changed"] and s.event("new") is None

    # Через 15 минут линия есть — снимаем открытие со всеми рынками, включая реальный ДШ
    c.per_event["new"] = {**_raw("new", utc_now() + timedelta(days=3)), "bookmakers": [_bm(dc=(1.2, 1.3, 2.15))]}
    res = p.run_cycle(now=later + timedelta(minutes=15))
    assert res["opened"] == 1 and res["changed"]
    snap = s.history("new")[0]["p"]
    assert snap["X2"] == [2.15, None] and snap["over@2.5"] == [1.95, 2.5] and s.event("new")["opened_at"]

    # Дальше матч уже известен — повторных запросов по нему нет
    c.calls.clear()
    p.run_cycle(now=later + timedelta(minutes=30))
    assert [x for x in c.calls if x[0] == "event"] == []


def test_closing_line_taken_once_within_window_before_kickoff():
    c, s = FlowClient(), Store(":memory:")
    p = _poller(c, s)
    kickoff = utc_now() + timedelta(minutes=40)
    c.league = [_raw("m", kickoff, _bm())]
    p.run_cycle()  # первый опрос: матч сохранён; до старта 40 мин -> сразу и закрытие
    # Запрос закрытия был, но Pinnacle ничего не вернул — повторим в следующем цикле
    assert ("event", "m", p.close_markets) in c.calls and s.event("m")["closed_at"] is None
    c.per_event["m"] = {**_raw("m", kickoff), "bookmakers": [_bm(dc=(1.2, 1.3, 2.15), total=None, spread=None)]}
    res = p.run_cycle(now=utc_now() + timedelta(minutes=5))
    assert res["closed"] == 1 and s.event("m")["closed_at"]
    c.calls.clear()
    p.run_cycle(now=utc_now() + timedelta(minutes=10))
    assert [x for x in c.calls if x[0] == "event"] == []


def test_quota_guard_stops_paid_event_calls():
    c, s = FlowClient(), Store(":memory:")
    p = _poller(c, s)
    p.run_cycle()
    c.quota = {"remaining": "3"}
    c.listed = [_raw("new", utc_now() + timedelta(days=3))]
    res = p.run_cycle(now=utc_now() + timedelta(minutes=15))
    assert [x for x in c.calls if x[0] == "event"] == []
    assert any("квота" in e for e in res["errors"])


def _ev_row(store, eid, commence, opened):
    store.save_prices({"id": eid, "sport_key": "soccer_epl", "sport_title": "EPL", "home": "K", "away": "I",
                       "commence_time": iso(commence)}, iso(opened), {"home": (2.0, None)})
    store.mark(eid, "opened_at", iso(opened))
    return store.event(eid)


def test_lines_schedule_12h_then_every_3h_from_13h_before_kickoff():
    s = Store(":memory:")
    p = _poller(FlowClient(), s)
    now = utc_now()
    kickoff = now + timedelta(days=3)
    ev = _ev_row(s, "m", kickoff, now)
    assert not p.lines_due(ev, now + timedelta(hours=11))
    assert p.lines_due(ev, now + timedelta(hours=12))            # раз в 12 ч
    # снимок за 15 ч до старта; за 13 ч — вход в окно «каждые 3 ч», снимаем сразу
    s.mark("m", "lines_at", iso(kickoff - timedelta(hours=15)))
    ev = s.event("m")
    assert not p.lines_due(ev, kickoff - timedelta(hours=13, minutes=30))
    assert p.lines_due(ev, kickoff - timedelta(hours=13))
    s.mark("m", "lines_at", iso(kickoff - timedelta(hours=13)))
    ev = s.event("m")
    assert not p.lines_due(ev, kickoff - timedelta(hours=11))
    assert p.lines_due(ev, kickoff - timedelta(hours=10))         # 13 -> 10 -> 7 -> 4
    assert not p.lines_due(ev, kickoff - timedelta(minutes=50))   # за час — уже закрытие


def test_run_cycle_takes_lines_for_due_matches_only():
    c, s = FlowClient(), Store(":memory:")
    p = _poller(c, s)
    now = utc_now()
    _ev_row(s, "due", now + timedelta(days=2), now - timedelta(hours=13))
    _ev_row(s, "fresh", now + timedelta(days=2), now - timedelta(hours=1))
    c.per_event["due"] = {**_raw("due", now + timedelta(days=2)), "bookmakers": [_bm(h2h=(1.9, 3.5, 4.1))]}
    s.set_setting("last_poll_at:soccer_epl", iso(now))  # лиге опрос пока не нужен
    res = p.run_cycle(now=now)
    assert [x[1] for x in c.calls if x[0] == "event"] == ["due"] and res["lines"] == 1
    assert s.event("due")["lines_at"] == iso(now) and "over@2.5" in s.history("due")[-1]["p"]
    snap = s.history("due")[-1]["p"]
    assert snap["home"] == [1.9, None]          # 1X2 и линии — в одном снимке, в одно время
    assert not [x for x in c.calls if x[0] == "odds"]  # запросов по лиге больше нет


def test_event_removed_from_api_is_not_requested_again():
    from app.odds_client import OddsApiError

    class Gone(FlowClient):
        def event_odds(self, sport, event_id, markets, bookmakers):
            self.calls.append(("event", event_id, markets))
            raise OddsApiError(404, "Event not found. The event may have expired or the event id is invalid.")

    c, s = Gone(), Store(":memory:")
    p = _poller(c, s)
    now = utc_now()
    _ev_row(s, "gone", now + timedelta(days=2), now - timedelta(hours=13))
    s.set_setting("last_poll_at:soccer_epl", iso(now))
    res = p.run_cycle(now=now)
    assert res["errors"] == [] and s.event("gone")["closed_at"]
    c.calls.clear()
    p.run_cycle(now=now + timedelta(hours=13))
    assert [x for x in c.calls if x[0] == "event"] == []
