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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Гибрид: частый 1X2 одним запросом на лигу + все тоталы и форы запросом по матчу в точках. */
class HybridModeTest {
    static final Instant NOW = PollerTest.NOW;
    static final double[] H2H = {1.9, 3.5, 4.1};

    static Poller poller(FakeApi api, Store s) {
        return new Poller(api, s, Settings.defaults(List.of("soccer_epl"))
                .withLeagues(LeagueCfg.parse("default: hybrid, points 24h 13h 4h, far 12h")));
    }

    static void known(Store s, Instant kickoff, Instant at) {
        PollerTest.eventRow(s, "m", kickoff, at);
        s.mark("m", "lines_at", Times.iso(at));
        s.mark("m", "h2h_at", Times.iso(at));
        s.setString("last_poll_at:soccer_epl", Times.iso(at));
    }

    @Test
    void parsesAndDescribes() {
        LeagueCfg c = LeagueCfg.parse("x: hybrid, points 13h 24h 4h, far 24h").get("x");
        assertTrue(c.hybrid() && c.byPoints() && !c.leagueMode());
        assertEquals(List.of(24.0, 13.0, 4.0), c.points());
        assertEquals("1X2 раз в 24 ч (за 13 ч до матча — раз в 3 ч); все тоталы и форы: открытие, за 24 ч, за 13 ч, за 4 ч, закрытие",
                c.describe(Settings.defaults(List.of())));
    }

    @Test
    void frequent1x2ByLeagueAndLinesOnlyAtPoints() {
        FakeApi api = new FakeApi();
        Store s = new Store();
        Poller p = poller(api, s);
        Instant kickoff = NOW.plus(Duration.ofDays(4));
        known(s, kickoff, NOW);
        api.league.add(raw("m", kickoff, bm(H2H, null, null, null)));
        api.perEvent.put("m", raw("m", kickoff, bm(null, null, new double[]{2.5, 1.95, 1.93}, new double[]{-0.5, 1.98, 1.93})));

        p.runCycle(false, NOW.plus(Duration.ofHours(6)));
        assertEquals(List.of(), api.paid());                                       // ни 1X2, ни линиям не пора
        p.runCycle(false, NOW.plus(Duration.ofHours(12)));
        assertEquals(List.of("odds:soccer_epl:h2h"), api.paid());                  // 1X2 раз в 12 ч — 1 запрос на лигу
        assertEquals(Times.iso(NOW.plus(Duration.ofHours(12))), s.event("m").h2hAt);

        api.calls.clear();
        p.runCycle(false, kickoff.minus(Duration.ofHours(24)));                    // точка «за 24 ч»: линии без 1X2
        assertTrue(api.paid().contains("event:m:" + Settings.HYBRID_LINES));
        assertNull(s.event("m").closedAt);

        api.calls.clear();
        p.runCycle(false, kickoff.minus(Duration.ofMinutes(50)));                  // закрытие — снимок по матчу с 1X2
        assertEquals(List.of("event:m:" + Settings.LINES_MARKETS), api.paid());
        assertNotNull(s.event("m").closedAt);

        api.calls.clear();
        p.runCycle(false, kickoff.minus(Duration.ofMinutes(35)));                  // после закрытия — ничего
        assertEquals(List.of(), api.paid());
    }

    @Test
    void every3hFor1x2Within13hAndTimersIndependent() {
        FakeApi api = new FakeApi();
        Store s = new Store();
        Poller p = poller(api, s);
        Instant kickoff = NOW.plus(Duration.ofHours(12));
        known(s, kickoff, NOW.minus(Duration.ofHours(4)));
        assertTrue(p.h2hDue(s.event("m"), NOW));          // вошли в последние 13 ч
        s.mark("m", "h2h_at", Times.iso(NOW));
        assertFalse(p.h2hDue(s.event("m"), NOW.plus(Duration.ofHours(2))));
        assertTrue(p.h2hDue(s.event("m"), NOW.plus(Duration.ofHours(3))));
        assertTrue(p.linesDue(s.event("m"), NOW));        // точка «за 13 ч» по линиям — свой таймер
        assertFalse(p.h2hDue(s.event("m"), kickoff.minus(Duration.ofMinutes(50)))); // закрытие — не по лиге
    }

    @Test
    void newLeagueOpenedBy1x2GetsLinesOnTheSameRun() {
        // Новая лига: первый снимок — запрос по лиге (только 1X2), сразу за ним — линии по каждому матчу
        FakeApi api = new FakeApi();
        Store s = new Store();
        Poller p = poller(api, s);
        Instant kickoff = NOW.plus(Duration.ofDays(10));
        api.league.add(raw("m", kickoff, bm(H2H, null, null, null)));
        api.perEvent.put("m", raw("m", kickoff, bm(null, null, new double[]{2.5, 1.95, 1.93}, new double[]{-0.5, 1.98, 1.93})));

        p.runCycle(false, NOW);
        assertTrue(api.paid().contains("event:m:" + Settings.HYBRID_LINES));
        assertNotNull(s.event("m").linesAt);
        assertTrue(s.history("m").toString().contains("over@2.5"));

        api.calls.clear();
        p.runCycle(false, NOW.plus(Duration.ofMinutes(15)));                       // повторно не снимаем
        assertFalse(api.paid().contains("event:m:" + Settings.HYBRID_LINES));
    }
}
