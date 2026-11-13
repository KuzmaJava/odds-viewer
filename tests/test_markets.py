from app.markets import derive_double_chance, main_line, margin, normalize_event

EVENT = {
    "id": "e1", "sport_key": "soccer_epl", "sport_title": "EPL",
    "commence_time": "2026-11-14T15:00:00Z",
    "home_team": "Arsenal", "away_team": "Chelsea",
    "bookmakers": [{
        "key": "pinnacle", "title": "Pinnacle", "last_update": "2026-11-13T10:00:00Z",
        "markets": [
            {"key": "h2h", "outcomes": [
                {"name": "Arsenal", "price": 2.0}, {"name": "Chelsea", "price": 4.0}, {"name": "Draw", "price": 3.5},
            ]},
            {"key": "totals", "outcomes": [
                {"name": "Over", "price": 1.6, "point": 2.0}, {"name": "Under", "price": 2.4, "point": 2.0},
                {"name": "Over", "price": 1.95, "point": 2.5}, {"name": "Under", "price": 1.9, "point": 2.5},
            ]},
            {"key": "team_totals", "outcomes": [
                {"name": "Over", "description": "Arsenal", "price": 1.8, "point": 1.5},
                {"name": "Under", "description": "Arsenal", "price": 2.0, "point": 1.5},
                {"name": "Over", "description": "Chelsea", "price": 2.1, "point": 0.5},
            ]},
        ],
    }],
}


def test_h2h_parsed_by_team_name():
    bm = normalize_event(EVENT)["bookmakers"][0]
    assert bm["h2h"] == {"home": 2.0, "draw": 3.5, "away": 4.0}


def test_double_chance_derived_from_1x2():
    dc = derive_double_chance({"home": 2.0, "draw": 4.0, "away": 4.0})
    assert dc == {"1X": 1.333, "12": 1.333, "X2": 2.0}


def test_double_chance_handles_missing_draw():
    assert derive_double_chance({"home": 1.5, "draw": None, "away": 2.5})["1X"] is None


def test_main_total_line_is_most_balanced():
    bm = normalize_event(EVENT)["bookmakers"][0]
    assert bm["totals_main"]["point"] == 2.5
    assert [l["point"] for l in bm["totals"]] == [2.0, 2.5]


def test_team_totals_split_by_team():
    tt = normalize_event(EVENT)["bookmakers"][0]["team_totals"]
    assert tt["home"]["main"] == {"point": 1.5, "over": 1.8, "under": 2.0}
    assert tt["away"]["lines"] == [{"point": 0.5, "over": 2.1, "under": None}]
    assert tt["away"]["main"] is None


def test_margin():
    assert margin([2.0, 2.0]) == 0.0
    assert margin([1.9, 1.9]) == 5.26
    assert margin([2.0, None]) is None


def test_main_line_empty():
    assert main_line([]) is None
