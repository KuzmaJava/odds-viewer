package oddsviewer;

import oddsviewer.Fixtures.FakeApi;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static oddsviewer.Fixtures.bm;
import static oddsviewer.Fixtures.raw;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Настройки по лигам: полный режим по точкам и режим лиги вместе. */
class LeagueSettingsTest {
    static final Instant NOW = PollerTest.NOW;
    static final String CFG = "default: full, points 13h; soccer_uefa_nations_league: league\nsoccer_uefa_champs_league: league, far 24h";

    static Settings settings(String... sports) {
        return Settings.defaults(List.of(sports)).withLeagueMode(true, 8).withLeagues(LeagueCfg.parse(CFG));
    }

    @Test
    void parsesSettings() {
        var m = LeagueCfg.parse(CFG);
        assertEquals(new LeagueCfg(false, List.of(13.0), null), m.get("default"));
        assertEquals(new LeagueCfg(true, List.of(), null), m.get("soccer_uefa_nations_league"));
        assertEquals(new LeagueCfg(true, List.of(), 24.0), m.get("soccer_uefa_champs_league"));
        assertEquals(List.of(13.0, 4.0), LeagueCfg.parseOne("full, points 4h 13h").points());
        assertThrows(IllegalArgumentException.class, () -> LeagueCfg.parse("soccer_epl: fast"));
        Settings s = settings("soccer_epl");
        assertTrue(s.forSport("soccer_epl").byPoints());               // по «default»
        assertTrue(s.forSport("soccer_uefa_nations_league").leagueMode());
        assertEquals("все линии: открытие, за 13 ч, закрытие", s.forSport("soccer_epl").describe(s));
    }

    @Test
    void pointsScheduleOpeningThenAt13hThenClosing() {
        Store s = new Store();
        Poller p = new Poller(new FakeApi(), s, settings("soccer_epl"));
        Instant kickoff = NOW.plus(Duration.ofDays(4));
        PollerTest.eventRow(s, "m", kickoff, NOW); // открытие сейчас
        assertFalse(p.linesDue(s.event("m"), NOW.plus(Duration.ofDays(2))));            // никаких «раз в N ч»
        assertFalse(p.linesDue(s.event("m"), kickoff.minus(Duration.ofHours(14))));
        assertTrue(p.linesDue(s.event("m"), kickoff.minus(Duration.ofHours(13))));
        assertEquals(kickoff.minus(Duration.ofHours(13)), p.nextSnapshotAt(s.event("m"), NOW));
        s.mark("m", "lines_at", Times.iso(kickoff.minus(Duration.ofHours(13))));
        assertFalse(p.linesDue(s.event("m"), kickoff.minus(Duration.ofHours(5))));     // следующее — закрытие
        assertEquals(kickoff.minus(Duration.ofHours(1)), p.nextSnapshotAt(s.event("m"), kickoff.minus(Duration.ofHours(5))));
    }

    @Test
    void midPointSkippedWhenQuotaOnlyCoversClosings() {
        FakeApi api = new FakeApi();
        Store s = new Store();
        Poller p = new Poller(api, s, settings("soccer_epl"));
        Instant kickoff = NOW.plus(Duration.ofHours(12));
        for (int i = 0; i < 5; i++) PollerTest.eventRow(s, "m" + i, kickoff, NOW.minus(Duration.ofDays(3)));
        api.quota.put("remaining", "20"); // 5 матчей × 3 на закрытие = 15, остаётся 5 < запаса 10
        assertFalse(p.linesDue(s.event("m0"), NOW));
        api.quota.put("remaining", "200");
        assertTrue(p.linesDue(s.event("m0"), NOW));
    }

    @Test
    void mixedCycleFullLeaguesPerMatchAndNationsLeagueOneRequest() {
        FakeApi api = new FakeApi();
        Store s = new Store();
        Poller p = new Poller(api, s, settings("soccer_epl", "soccer_uefa_nations_league"));
        // EPL: матч за 12 ч — точка «за 13 ч» прошла, снимок по матчу
        PollerTest.eventRow(s, "epl1", NOW.plus(Duration.ofHours(12)), NOW.minus(Duration.ofDays(3)));
        s.setString("last_poll_at:soccer_epl", Times.iso(NOW.minus(Duration.ofHours(1))));
        api.perEvent.put("epl1", raw("epl1", NOW.plus(Duration.ofHours(12)),
                bm(new double[]{1.9, 3.5, 4.1}, null, new double[]{2.5, 1.95, 1.93}, new double[]{-0.5, 1.98, 1.93})));
        // Лига наций — новая лига: один запрос на всю лигу
        var nl = raw("nl1", NOW.plus(Duration.ofDays(2)), bm(new double[]{2.0, 3.3, 3.9}, null, new double[]{2.25, 1.9, 1.95}, null));
        nl.put("sport_key", "soccer_uefa_nations_league");
        api.league.add(nl);

        var res = p.runCycle(false, NOW);
        assertEquals(List.of("event:epl1:" + Settings.LINES_MARKETS, "odds:soccer_uefa_nations_league:h2h,totals"), api.paid());
        assertEquals(1, res.get("lines").asInt());
        var epl = s.history("epl1").get(s.history("epl1").size() - 1).get("p");
        assertTrue(epl.has("ah_home@-0.5") && epl.has("over@2.5") && !epl.has("1X")); // все линии, ДШ — расчётом
        assertTrue(s.history("nl1").get(0).get("p").has("over@2.25"));               // режим лиги: четвертная основная
        assertTrue(p.scheduleSummary().contains("все линии: открытие, за 13 ч, закрытие"));
        assertTrue(p.scheduleSummary().contains("режим лиги"));
    }

    @Test
    void closingInPointsModeHasNoRealDoubleChance() {
        FakeApi api = new FakeApi();
        Store s = new Store();
        Poller p = new Poller(api, s, settings("soccer_epl"));
        PollerTest.eventRow(s, "m", NOW.plus(Duration.ofMinutes(40)), NOW.minus(Duration.ofDays(1)));
        s.setString("last_poll_at:soccer_epl", Times.iso(NOW));
        p.runCycle(false, NOW);
        assertEquals(List.of("event:m:" + Settings.LINES_MARKETS), api.paid());
    }
}
