package oddsviewer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ответы API для тестов: матч Kosovo — Israel с рынками Pinnacle. */
final class Fixtures {
    private Fixtures() {}

    static ObjectNode outcome(String name, double price, Double point) {
        ObjectNode o = Json.obj().put("name", name).put("price", price);
        if (point != null) o.put("point", point);
        return o;
    }

    /** h2h = {П1, X, П2}; dc = {1X, 12, X2} или null; total = {линия, Б, М} или null; spread = {фора хозяев, Ф1, Ф2} или null. */
    static ObjectNode bm(double[] h2h, double[] dc, double[] total, double[] spread) {
        ObjectNode bm = Json.obj().put("key", "pinnacle").put("title", "Pinnacle");
        ArrayNode markets = bm.putArray("markets");
        if (h2h != null) {
            ArrayNode o = markets.addObject().put("key", "h2h").putArray("outcomes");
            if (h2h[0] > 0) o.add(outcome("Kosovo", h2h[0], null));
            if (h2h[1] > 0) o.add(outcome("Draw", h2h[1], null));
            if (h2h[2] > 0) o.add(outcome("Israel", h2h[2], null));
        }
        if (dc != null) {
            ArrayNode o = markets.addObject().put("key", "double_chance").putArray("outcomes");
            o.add(outcome("Kosovo or Draw", dc[0], null)).add(outcome("Israel or Kosovo", dc[1], null)).add(outcome("Israel or Draw", dc[2], null));
        }
        if (total != null) {
            ArrayNode o = markets.addObject().put("key", "totals").putArray("outcomes");
            o.add(outcome("Over", total[1], total[0])).add(outcome("Under", total[2], total[0]));
        }
        if (spread != null) {
            ArrayNode o = markets.addObject().put("key", "spreads").putArray("outcomes");
            o.add(outcome("Kosovo", spread[1], spread[0])).add(outcome("Israel", spread[2], -spread[0]));
        }
        return bm;
    }

    static ObjectNode bmDefault() {
        return bm(new double[]{1.75, 3.81, 4.98}, null, new double[]{2.5, 1.95, 1.93}, new double[]{-0.5, 1.98, 1.93});
    }

    static ObjectNode raw(String id, Instant commence, ObjectNode bm) {
        ObjectNode ev = Json.obj().put("id", id).put("sport_key", "soccer_epl").put("sport_title", "EPL")
                .put("commence_time", Times.iso(commence)).put("home_team", "Kosovo").put("away_team", "Israel");
        if (bm != null) ev.putArray("bookmakers").add(bm);
        return ev;
    }

    /** Подмена API: запоминает вызовы, отдаёт заданные ответы. */
    static final class FakeApi implements OddsApi {
        final Map<String, String> quota = new LinkedHashMap<>(Map.of("remaining", "400"));
        final List<String> calls = new ArrayList<>();
        List<ObjectNode> listed = new ArrayList<>();
        List<ObjectNode> league = new ArrayList<>();
        final Map<String, ObjectNode> perEvent = new HashMap<>();
        RuntimeException eventError;

        @Override public Map<String, String> quota() { return quota; }

        @Override public JsonNode sports() {
            calls.add("sports");
            ArrayNode a = Json.arr();
            a.addObject().put("key", "soccer_epl").put("title", "EPL").put("group", "Soccer").put("active", true).put("has_outrights", false);
            return a;
        }

        @Override public JsonNode events(String sport) {
            calls.add("events:" + sport);
            ArrayNode a = Json.arr();
            listed.forEach(e -> { ObjectNode c = e.deepCopy(); c.remove("bookmakers"); a.add(c); });
            return a;
        }

        @Override public JsonNode odds(String sport, String markets, String bookmakers) {
            calls.add("odds:" + sport + ":" + markets);
            ArrayNode a = Json.arr();
            league.forEach(a::add);
            return a;
        }

        @Override public JsonNode eventOdds(String sport, String eventId, String markets, String bookmakers) {
            calls.add("event:" + eventId + ":" + markets);
            if (eventError != null) throw eventError;
            ObjectNode r = perEvent.get(eventId);
            return r == null ? Json.obj() : r;
        }

        List<String> paid() {
            return calls.stream().filter(c -> c.startsWith("odds:") || c.startsWith("event:")).toList();
        }
    }
}
