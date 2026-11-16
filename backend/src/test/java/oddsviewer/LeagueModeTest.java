package oddsviewer;

import oddsviewer.Fixtures.FakeApi;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static oddsviewer.Fixtures.bm;
import static oddsviewer.Fixtures.raw;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Режим лиги: один запрос снимает все матчи лиги (1X2 + основной тотал). */
class LeagueModeTest {
    static final Instant NOW = PollerTest.NOW;
    static final double[] H2H = {1.75, 3.81, 4.98};

    static Poller poller(FakeApi api, Store s) {
        return new Poller(api, s, Settings.defaults(List.of("soccer_epl")).withLeagueMode(true, 24));
    }

    @Test
    void oneRequestForAllMatchesKeepsQuarterMainTotal() {
        FakeApi api = new FakeApi();
        Store s = new Store();
        Poller p = poller(api, s);
        api.league.add(raw("a", NOW.plus(Duration.ofDays(2)), bm(H2H, null, new double[]{2.25, 1.9, 1.95}, null)));
        api.league.add(raw("b", NOW.plus(Duration.ofDays(3)), bm(H2H, null, new double[]{3.0, 2.0, 1.85}, null)));
        var res = p.runCycle(false, NOW);
        assertEquals(List.of("odds:soccer_epl:h2h,totals"), api.paid());
        assertEquals(2, res.get("snapshots_saved").asInt());
        assertEquals(2, res.get("opened").asInt());
        assertTrue(s.history("a").get(0).get("p").has("over@2.25"));

        api.calls.clear();
        p.runCycle(false, NOW.plus(Duration.ofHours(1)));
        assertEquals(List.of(), api.paid()); // никому не пора

        p.runCycle(false, NOW.plus(Duration.ofHours(24)));
        assertEquals(List.of("odds:soccer_epl:h2h,totals"), api.paid());
    }

    @Test
    void newMatchInFreeListTriggersSnapshotAndLineShiftIsShown() {
        FakeApi api = new FakeApi();
        Store s = new Store();
        Poller p = poller(api, s);
        api.league.add(raw("a", NOW.plus(Duration.ofDays(2)), bm(H2H, null, new double[]{2.25, 1.9, 1.95}, null)));
        p.runCycle(false, NOW);
        api.listed.add(raw("new", NOW.plus(Duration.ofDays(4)), null));
        api.league.clear();
        api.league.add(raw("a", NOW.plus(Duration.ofDays(2)), bm(H2H, null, new double[]{2.5, 2.05, 1.8}, null)));
        api.league.add(raw("new", NOW.plus(Duration.ofDays(4)), bm(H2H, null, new double[]{2.5, 1.95, 1.93}, null)));
        var res = p.runCycle(false, NOW.plus(Duration.ofMinutes(15)));
        assertEquals(1, res.get("opened").asInt());
        var by = StoreMovementTest.byCode(Movement.eventMovement(s.event("a")));
        assertTrue(by.get("over@2.5").get("main").asBoolean());
        assertEquals(2.25, by.get("over@2.5").get("open_main_point").asDouble());
    }

    @Test
    void closingMarksMatchesNearKickoff() {
        FakeApi api = new FakeApi();
        Store s = new Store();
        api.league.add(raw("m", NOW.plus(Duration.ofMinutes(50)), bm(H2H, null, null, null)));
        var res = poller(api, s).runCycle(false, NOW);
        assertEquals(1, res.get("closed").asInt());
        assertNotNull(s.event("m").closedAt);
    }
}
