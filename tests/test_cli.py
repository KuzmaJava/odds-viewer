import json
from datetime import timedelta

from app import cli
from app.poller import iso, utc_now
from app.store import Store


def _ev(eid, commence):
    return {"id": eid, "sport_key": "soccer_epl", "sport_title": "EPL",
            "home": "A", "away": "B", "commence_time": commence}


def test_dump_load_roundtrip_and_prunes_old_events():
    s = Store(":memory:")
    future = iso(utc_now() + timedelta(days=2))
    old = iso(utc_now() - timedelta(days=30))
    s.save_snapshot(_ev("f", future), "2026-11-13T10:00:00Z", 2.0, 3.0, 4.0)
    s.save_snapshot(_ev("f", future), "2026-11-13T13:00:00Z", 1.9, 3.1, 4.2)
    s.save_snapshot(_ev("o", old), "2026-10-13T10:00:00Z", 2.0, 3.0, 4.0)
    s.set_setting("tracked_sports", ["soccer_epl"])

    data = s.dump(drop_commenced_before=iso(utc_now() - timedelta(days=14)))
    assert [e["id"] for e in data["events"]] == ["f"]

    s2 = Store(":memory:")
    s2.load(json.loads(json.dumps(data)))
    assert [h["home"] for h in s2.history("f")] == [2.0, 1.9]
    assert s2.get_setting("tracked_sports") == ["soccer_epl"]


class FakeClient:
    demo = False

    def __init__(self):
        self.quota = {"remaining": "300", "used": "200", "last_cost": "1"}
        self.calls = []

    def odds(self, sport, markets, bookmakers, use_cache):
        self.calls.append(sport)
        return [{
            "id": "e1", "sport_key": sport, "sport_title": "EPL",
            "commence_time": iso(utc_now() + timedelta(days=1)),
            "home_team": "A", "away_team": "B",
            "bookmakers": [{"key": "pinnacle", "title": "Pinnacle", "markets": [{"key": "h2h", "outcomes": [
                {"name": "A", "price": 2.0}, {"name": "B", "price": 3.5}, {"name": "Draw", "price": 3.3}]}]}],
        }]

    def sports(self):
        return [{"key": "soccer_epl", "title": "EPL", "group": "Soccer", "active": True, "has_outrights": False}]


def test_cli_run_polls_due_then_skips_until_next_slot(tmp_path, monkeypatch):
    fake = FakeClient()
    monkeypatch.setattr(cli, "build_client", lambda: fake)
    monkeypatch.setenv("TRACK_SPORTS", "soccer_epl")
    out_file = tmp_path / "gh_out"
    monkeypatch.setenv("GITHUB_OUTPUT", str(out_file))
    state, site = tmp_path / "state.json", tmp_path / "site"

    assert cli.main(["run", "--state", str(state), "--out", str(site)]) == 0
    assert fake.calls == ["soccer_epl"]
    assert "changed=true" in out_file.read_text()
    data = json.loads((site / "data.json").read_text())
    assert data["events"][0]["home"] == "A"
    assert data["status"]["quota"]["remaining"] == "300"
    assert data["sports"][0]["key"] == "soccer_epl"
    assert (site / "index.html").exists()
    assert json.loads(state.read_text())["events"][0]["snapshots"][0][1] == 2.0

    # Сразу второй запуск: по расписанию ещё рано — без запросов и без изменений
    out_file.write_text("")
    assert cli.main(["run", "--state", str(state), "--out", str(site)]) == 0
    assert fake.calls == ["soccer_epl"]
    assert "changed=false" in out_file.read_text()

    # --force опрашивает независимо от расписания
    assert cli.main(["run", "--force", "--state", str(state), "--out", str(site)]) == 0
    assert fake.calls == ["soccer_epl", "soccer_epl"]


def test_cli_slack_lets_hourly_cron_catch_slightly_late_slot():
    from app.poller import Poller
    s = Store(":memory:")
    p = Poller(FakeClient(), s, default_sports=["soccer_epl"])
    now = utc_now()
    s.set_setting("last_poll_at:soccer_epl", iso(now - timedelta(hours=2, minutes=50)))  # слот через 10 мин
    assert p.due_sports(now) == []
    assert p.due_sports(now, timedelta(minutes=cli.CRON_SLACK_MIN)) == ["soccer_epl"]


def test_sports_from_site_override_env_and_persist(tmp_path, monkeypatch):
    fake = FakeClient()
    monkeypatch.setattr(cli, "build_client", lambda: fake)
    monkeypatch.setenv("TRACK_SPORTS", "soccer_epl")
    monkeypatch.delenv("GITHUB_OUTPUT", raising=False)
    state, site = tmp_path / "state.json", tmp_path / "site"
    args = ["--state", str(state), "--out", str(site)]

    # Переменная репозитория работает, пока список не сохраняли с сайта
    cli.main(["run", *args])
    assert fake.calls == ["soccer_epl"]

    # С сайта добавили лигу — опрашивается только новая (EPL ещё рано)
    cli.main(["run", "--sports", "soccer_uefa_nations_league, soccer_epl", *args])
    assert fake.calls == ["soccer_epl", "soccer_uefa_nations_league"]
    saved = json.loads(state.read_text())["settings"]
    assert saved["tracked_sports"] == ["soccer_epl", "soccer_uefa_nations_league"]

    # Дальше переменная TRACK_SPORTS уже не перебивает список с сайта
    data = json.loads((site / "data.json").read_text())
    assert data["status"]["tracked_sports"] == ["soccer_epl", "soccer_uefa_nations_league"]


def test_removing_league_from_site_is_saved_without_polling(tmp_path, monkeypatch):
    fake = FakeClient()
    monkeypatch.setattr(cli, "build_client", lambda: fake)
    monkeypatch.setenv("TRACK_SPORTS", "soccer_epl,soccer_spain_la_liga")
    out_file = tmp_path / "gh_out"
    monkeypatch.setenv("GITHUB_OUTPUT", str(out_file))
    state, site = tmp_path / "state.json", tmp_path / "site"
    args = ["--state", str(state), "--out", str(site)]
    cli.main(["run", *args])
    calls = len(fake.calls)

    out_file.write_text("")
    cli.main(["run", "--sports", "soccer_epl", *args])
    assert len(fake.calls) == calls  # ничего не опрашивали
    assert "changed=true" in out_file.read_text()  # но state сохранён и сайт пересобран
    assert json.loads(state.read_text())["settings"]["tracked_sports"] == ["soccer_epl"]


def test_bad_league_key_rejected():
    import pytest
    with pytest.raises(SystemExit):
        cli.parse_sports("soccer_epl; rm -rf /")
