package oddsviewer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreMovementTest {
    static Store.Meta meta(String id, Instant commence) {
        return new Store.Meta(id, "soccer_epl", "EPL", "K", "I", Times.iso(commence));
    }

    static Map<String, Price> prices(Object... kv) {
        Map<String, Price> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 3) m.put((String) kv[i], new Price((Double) kv[i + 1], (Double) kv[i + 2]));
        return m;
    }

    static Map<String, JsonNode> byCode(ObjectNode mv) {
        Map<String, JsonNode> m = new HashMap<>();
        mv.get("outcomes").forEach(o -> m.put(o.get("code").asText(), o));
        return m;
    }

    @Test
    void dumpLoadRoundtripAndPrunesOldEvents() {
        Store s = new Store();
        Instant now = Instant.now();
        s.savePrices(meta("f", now.plus(Duration.ofDays(2))), "2026-11-13T10:00:00Z", prices("home", 2.0, null));
        s.savePrices(meta("f", now.plus(Duration.ofDays(2))), "2026-11-13T13:00:00Z", prices("home", 1.9, null, "over@2.5", 1.8, 2.5));
        s.savePrices(meta("o", now.minus(Duration.ofDays(30))), "2026-10-13T10:00:00Z", prices("home", 2.0, null));
        s.mark("f", "opened_at", "2026-11-13T10:00:00Z");
        s.setSetting("tracked_sports", Json.arr().add("soccer_epl"));

        ObjectNode data = s.dump(Times.iso(now.minus(Duration.ofDays(14))));
        assertEquals(1, data.get("events").size());
        Store s2 = new Store();
        s2.load(Json.parse(Json.py(data, false)));
        assertEquals(2, s2.history("f").size());
        assertEquals(1.9, s2.history("f").get(1).get("p").get("home").get(0).asDouble());
        assertEquals("2026-11-13T10:00:00Z", s2.event("f").openedAt);
        assertEquals("soccer_epl", s2.getSetting("tracked_sports").get(0).asText());
    }

    @Test
    void loadsV1AndEarlyV2States() {
        Store s = new Store();
        s.load(Json.parse("""
                {"version": 1, "events": [{"id": "a", "sport_key": "soccer_epl", "sport_title": "EPL", "home": "K", "away": "I",
                 "commence_time": "2099-01-01T00:00:00Z", "snapshots": [["2026-11-13T10:00:00Z", 2.0, 3.0, 4.0]]}]}"""));
        assertEquals("2026-11-13T10:00:00Z", s.event("a").openedAt);
        assertEquals(3, s.snapshots("a").get("2026-11-13T10:00:00Z").size());

        Store s2 = new Store();
        s2.load(Json.parse("""
                {"version": 2, "events": [{"id": "a", "sport_key": "soccer_epl", "sport_title": "EPL", "home": "K", "away": "I",
                 "commence_time": "2099-01-01T00:00:00Z",
                 "snapshots": [["2026-11-14T08:00:00Z", {"home": [2.0], "over": [1.9, 2.5], "ah_home": [1.98, -0.75]}]]}]}"""));
        assertEquals(java.util.Set.of("home", "over@2.5"), s2.snapshots("a").get("2026-11-14T08:00:00Z").keySet());
    }

    @Test
    void eachLineTrackedSeparatelyAndDcRealVsDerived() {
        Store s = new Store();
        Store.Meta m = meta("a", Instant.now().plus(Duration.ofDays(1)));
        s.savePrices(m, "2026-11-14T08:00:00Z", prices("home", 2.0, null, "draw", 3.4, null, "away", 3.8, null,
                "1X", 1.3, null, "12", 1.33, null, "X2", 1.8, null, "over@2.5", 1.90, 2.5, "under@2.5", 1.98, 2.5,
                "over@3", 2.6, 3.0, "under@3", 1.5, 3.0));
        s.savePrices(m, "2026-11-14T11:00:00Z", prices("home", 1.9, null, "draw", 3.5, null, "away", 4.0, null,
                "over@2.5", 1.80, 2.5, "under@2.5", 2.05, 2.5, "over@3", 2.5, 3.0, "under@3", 1.55, 3.0));
        ObjectNode mv = Movement.eventMovement(s.event("a"));
        var by = byCode(mv);
        assertEquals("ТБ 2.5", by.get("over@2.5").get("label").asText());
        assertEquals(-5.26, by.get("over@2.5").get("change_pct").asDouble());
        assertEquals(2.5, by.get("over@3").get("current").asDouble());
        assertTrue(by.get("over@2.5").get("main").asBoolean());
        assertFalse(by.get("over@3").get("main").asBoolean());
        assertEquals(1.3, by.get("1X").get("opening").asDouble());
        assertTrue(by.get("1X").get("derived").asBoolean());
        assertEquals(-5.0, by.get("home").get("change_pct").asDouble());
        assertEquals("home", mv.get("outcomes").get(0).get("code").asText());
        assertEquals("away", mv.get("outcomes").get(2).get("code").asText());
    }

    @Test
    void mainLineShiftIsReported() {
        Store s = new Store();
        Store.Meta m = meta("a", Instant.now().plus(Duration.ofDays(1)));
        s.savePrices(m, "2026-11-14T08:00:00Z", prices("home", 2.0, null, "over@2.25", 1.9, 2.25, "under@2.25", 1.95, 2.25));
        s.savePrices(m, "2026-11-14T11:00:00Z", prices("home", 2.0, null, "over@2.5", 2.05, 2.5, "under@2.5", 1.8, 2.5));
        var by = byCode(Movement.eventMovement(s.event("a")));
        assertTrue(by.get("over@2.5").get("main").asBoolean());
        assertEquals(2.25, by.get("over@2.5").get("open_main_point").asDouble());
        assertFalse(by.get("over@2.25").get("main").asBoolean());
        assertTrue(by.get("over@2.25").get("open_main_point").isNull());
        assertNull(s.lastLinesTs("missing"));
    }
}
