package oddsviewer;

import oddsviewer.Fixtures.FakeApi;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static oddsviewer.Fixtures.bm;
import static oddsviewer.Fixtures.bmDefault;
import static oddsviewer.Fixtures.raw;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Полный режим: снимок — запрос по матчу. */
class PollerTest {
    static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    static Poller poller(FakeApi api, Store s) {
        return new Poller(api, s, Settings.defaults(List.of("soccer_epl")));
    }

    static Duration h(long hours) { return Duration.ofHours(hours); }

    static void eventRow(Store s, String id, Instant commence, Instant opened) {
        s.savePrices(new Store.Meta(id, "soccer_epl", "EPL", "Kosovo", "Israel", Times.iso(commence)), Times.iso(opened),
                StoreMovementTest.prices("home", 2.0, null));
        s.mark(id, "opened_at", Times.iso(opened));
    }

    @Test
    void firstLeaguePollDoesNotSpendOnEventCalls() {
        FakeApi api = new FakeApi();
        Store s = new Store();
        api.league.add(raw("old", NOW.plus(h(48)), bmDefault()));
        var res = poller(api, s).runCycle(false, NOW);
        assertEquals(List.of("odds:soccer_epl:h2h"), api.paid().subList(0, 1));
        assertEquals(1, res.get("snapshots_saved").asInt());
        assertEquals(res.get("at").asText(), s.event("old").openedAt);
    }

    @Test
    void newMatchGetsFullOpeningSnapshotAsSoonAsPinnacleListsIt() {
        FakeApi api = new FakeApi();
        Store s = new Store();
        Poller p = poller(api, s);
        p.runCycle(false, NOW); // первый опрос лиги (матчей нет)

        api.listed.add(raw("new", NOW.plus(h(72)), null));
        api.calls.clear();
        var res = p.runCycle(false, NOW.plus(Duration.ofMinutes(15)));
        assertTrue(api.calls.contains("event:new:" + Settings.OPEN_MARKETS));
        assertFalse(res.get("changed").asBoolean());
        assertNull(s.event("new"));

        api.perEvent.put("new", raw("new", NOW.plus(h(72)), bm(new double[]{1.75, 3.81, 4.98}, new double[]{1.2, 1.3, 2.15},
                new double[]{2.5, 1.95, 1.93}, new double[]{-0.5, 1.98, 1.93})));
        res = p.runCycle(false, NOW.plus(Duration.ofMinutes(30)));
        assertEquals(1, res.get("opened").asInt());
        var snap = s.history("new").get(0).get("p");
        assertEquals(2.15, snap.get("X2").get(0).asDouble());
        assertEquals(1.95, snap.get("over@2.5").get(0).asDouble());
        assertNotNull(s.event("new").openedAt);
        assertNotNull(s.event("new").linesAt);

        api.calls.clear();
        p.runCycle(false, NOW.plus(Duration.ofMinutes(45)));
        assertEquals(List.of(), api.paid());
    }

    @Test
    void closingLineTakenOnceWithinWindowBeforeKickoff() {
        FakeApi api = new FakeApi();
        Store s = new Store();
        Poller p = poller(api, s);
        Instant kickoff = NOW.plus(Duration.ofMinutes(40));
        api.league.add(raw("m", kickoff, bmDefault()));
        p.runCycle(false, NOW);
        assertTrue(api.calls.contains("event:m:" + Settings.OPEN_MARKETS));
        assertNull(s.event("m").closedAt); // Pinnacle ничего не вернул — повторим

        api.perEvent.put("m", raw("m", kickoff, bm(new double[]{1.75, 3.81, 4.98}, new double[]{1.2, 1.3, 2.15}, null, null)));
        var res = p.runCycle(false, NOW.plus(Duration.ofMinutes(5)));
        assertEquals(1, res.get("closed").asInt());
        assertNotNull(s.event("m").closedAt);
        api.calls.clear();
        p.runCycle(false, NOW.plus(Duration.ofMinutes(10)));
        assertEquals(List.of(), api.paid());
    }

    @Test
    void quotaGuardStopsPaidEventCalls() {
        FakeApi api = new FakeApi();
        Store s = new Store();
        Poller p = poller(api, s);
        p.runCycle(false, NOW);
        api.quota.put("remaining", "3");
        api.listed.add(raw("new", NOW.plus(h(72)), null));
        var res = p.runCycle(false, NOW.plus(Duration.ofMinutes(15)));
        assertTrue(api.paid().stream().noneMatch(c -> c.startsWith("event:")));
        assertTrue(res.get("errors").get(0).asText().contains("квота"));
    }

    @Test
    void linesSchedule12hThenEvery3hFrom13hBeforeKickoff() {
        Store s = new Store();
        Poller p = poller(new FakeApi(), s);
        Instant kickoff = NOW.plus(h(72));
        eventRow(s, "m", kickoff, NOW);
        assertFalse(p.linesDue(s.event("m"), NOW.plus(h(11))));
        assertTrue(p.linesDue(s.event("m"), NOW.plus(h(12))));
        s.mark("m", "lines_at", Times.iso(kickoff.minus(h(15))));
        assertFalse(p.linesDue(s.event("m"), kickoff.minus(h(13)).minus(Duration.ofMinutes(30))));
        assertTrue(p.linesDue(s.event("m"), kickoff.minus(h(13))));
        s.mark("m", "lines_at", Times.iso(kickoff.minus(h(13))));
        assertFalse(p.linesDue(s.event("m"), kickoff.minus(h(11))));
        assertTrue(p.linesDue(s.event("m"), kickoff.minus(h(10))));
        assertFalse(p.linesDue(s.event("m"), kickoff.minus(Duration.ofMinutes(50)))); // за час — уже закрытие
    }

    @Test
    void runCycleTakesSnapshotsForDueMatchesOnlyAllOutcomesTogether() {
        FakeApi api = new FakeApi();
        Store s = new Store();
        Poller p = poller(api, s);
        eventRow(s, "due", NOW.plus(h(48)), NOW.minus(h(13)));
        eventRow(s, "fresh", NOW.plus(h(48)), NOW.minus(h(1)));
        api.perEvent.put("due", raw("due", NOW.plus(h(48)), bm(new double[]{1.9, 3.5, 4.1}, null, new double[]{2.5, 1.95, 1.93}, null)));
        s.setString("last_poll_at:soccer_epl", Times.iso(NOW));
        var res = p.runCycle(false, NOW);
        assertEquals(List.of("event:due:" + Settings.LINES_MARKETS), api.paid());
        assertEquals(1, res.get("lines").asInt());
        var snap = s.history("due").get(s.history("due").size() - 1).get("p");
        assertEquals(1.9, snap.get("home").get(0).asDouble()); // 1X2 и линии — в одном снимке
        assertTrue(snap.has("over@2.5"));
        assertEquals(Times.iso(NOW), s.event("due").linesAt);
    }

    @Test
    void eventRemovedFromApiIsNotRequestedAgain() {
        FakeApi api = new FakeApi();
        Store s = new Store();
        Poller p = poller(api, s);
        eventRow(s, "gone", NOW.plus(h(48)), NOW.minus(h(13)));
        s.setString("last_poll_at:soccer_epl", Times.iso(NOW));
        api.eventError = new OddsApi.ApiException(404, "Event not found. The event may have expired or the event id is invalid.");
        var res = p.runCycle(false, NOW);
        assertEquals(0, res.get("errors").size());
        assertNotNull(s.event("gone").closedAt);
        api.calls.clear();
        p.runCycle(false, NOW.plus(h(13)));
        assertEquals(List.of(), api.paid());
    }

    @Test
    void nextSnapshotIsEarliestDueTime() {
        Store s = new Store();
        Poller p = poller(new FakeApi(), s);
        eventRow(s, "a", NOW.plus(h(20)), NOW.minus(h(1)));  // войдёт в окно 13 ч через 7 ч
        eventRow(s, "b", NOW.plus(h(60)), NOW.minus(h(6)));  // 12 ч от последнего снимка — через 6 ч
        assertEquals(Times.iso(NOW.plus(h(6))), p.nextSnapshot(NOW));
    }
}
