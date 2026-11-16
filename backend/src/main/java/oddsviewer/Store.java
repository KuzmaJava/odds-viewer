package oddsviewer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Хранилище в памяти: матчи, снимки цен Pinnacle по всем рынкам, настройки.
 * Живёт между запусками в data/state.json (формат v2, совместим с Python-версией).
 */
public final class Store {

    /** Матч и его снимки: ts -> (код исхода -> цена). */
    public static final class Event {
        public final String id;
        public String sportKey, sportTitle, home, away, commenceTime;
        public String openedAt, closedAt, linesAt, h2hAt;
        final TreeMap<String, Map<String, Price>> snaps = new TreeMap<>();

        Event(String id) { this.id = id; }

        public int snapshotCount() { return snaps.size(); }
        public String firstTs() { return snaps.isEmpty() ? null : snaps.firstKey(); }
        public String lastTs() { return snaps.isEmpty() ? null : snaps.lastKey(); }
    }

    public record Meta(String id, String sportKey, String sportTitle, String home, String away, String commenceTime) {}

    private final Map<String, Event> events = new LinkedHashMap<>();
    private final Map<String, JsonNode> settings = new TreeMap<>();

    // --- settings ---
    public JsonNode getSetting(String key) { return settings.get(key); }

    public String getString(String key) {
        JsonNode n = settings.get(key);
        return n == null || n.isNull() ? null : n.asText();
    }

    public void setSetting(String key, JsonNode value) { settings.put(key, value == null ? Json.F.nullNode() : value); }

    public void setString(String key, String value) { setSetting(key, Json.F.textNode(value)); }

    // --- writes ---
    public void savePrices(Meta m, String ts, Map<String, Price> prices) {
        Event e = events.computeIfAbsent(m.id(), Event::new);
        if (e.sportKey == null) {
            e.sportKey = m.sportKey();
            e.sportTitle = m.sportTitle();
            e.home = m.home();
            e.away = m.away();
        }
        e.commenceTime = m.commenceTime(); // перенос матча — время начала обновляется
        e.snaps.computeIfAbsent(ts, k -> new LinkedHashMap<>()).putAll(prices);
    }

    public void mark(String eventId, String field, String ts) {
        Event e = events.get(eventId);
        if (e == null) return;
        switch (field) {
            case "opened_at" -> e.openedAt = ts;
            case "closed_at" -> e.closedAt = ts;
            case "lines_at" -> e.linesAt = ts;
            case "h2h_at" -> e.h2hAt = ts;
            default -> throw new IllegalArgumentException(field);
        }
    }

    public void updateCommence(String eventId, String commenceTime) {
        Event e = events.get(eventId);
        if (e != null) e.commenceTime = commenceTime;
    }

    // --- reads ---
    private static final Comparator<Event> BY_START = Comparator.comparing((Event e) -> e.commenceTime).thenComparing(e -> e.home);

    /** Матчи, по которым есть хоть один снимок; since — только начинающиеся позже. */
    public List<Event> events(String sinceCommence) {
        return events.values().stream()
                .filter(e -> !e.snaps.isEmpty())
                .filter(e -> sinceCommence == null || e.commenceTime.compareTo(sinceCommence) > 0)
                .sorted(BY_START).toList();
    }

    public Event event(String id) { return events.get(id); }

    public Map<String, Map<String, Price>> snapshots(String eventId) {
        Event e = events.get(eventId);
        return e == null ? Map.of() : e.snaps;
    }

    /** Для страницы: [{"ts": ..., "p": {code: [price, point]}}]. */
    public ArrayNode history(String eventId) {
        ArrayNode res = Json.arr();
        snapshots(eventId).forEach((ts, prices) -> {
            ObjectNode p = Json.obj();
            prices.forEach((code, pr) -> {
                ArrayNode v = p.putArray(code).add(pr.price());
                if (pr.point() == null) v.addNull(); else v.add(pr.point());
            });
            res.addObject().put("ts", ts).set("p", p);
        });
        return res;
    }

    public Set<String> eventIds(String sportKey) {
        Set<String> res = new LinkedHashSet<>();
        events.values().stream().filter(e -> sportKey == null || sportKey.equals(e.sportKey)).forEach(e -> res.add(e.id));
        return res;
    }

    /** Матчи, стартующие в (after, before], по которым ещё не снимали линию закрытия. */
    public List<Event> toClose(String after, String before) {
        return events.values().stream()
                .filter(e -> e.closedAt == null && e.commenceTime.compareTo(after) > 0 && e.commenceTime.compareTo(before) <= 0)
                .sorted(Comparator.comparing(e -> e.commenceTime)).toList();
    }

    public List<Event> upcoming(String after) {
        return events.values().stream().filter(e -> e.commenceTime.compareTo(after) > 0)
                .sorted(Comparator.comparing(e -> e.commenceTime)).toList();
    }

    /** Время последнего снимка с линиями тоталов/фор (для матчей до появления lines_at). */
    public String lastLinesTs(String eventId) {
        String last = null;
        for (var en : snapshots(eventId).entrySet()) {
            if (en.getValue().keySet().stream().anyMatch(c -> c.contains("@"))) last = en.getKey();
        }
        return last;
    }

    public String nextCommence(String sportKey, String after) {
        return events.values().stream().filter(e -> sportKey.equals(e.sportKey) && e.commenceTime.compareTo(after) > 0)
                .map(e -> e.commenceTime).min(String::compareTo).orElse(null);
    }

    // --- JSON-состояние ---
    /** Всё содержимое. Матчи, начавшиеся раньше dropCommencedBefore, отбрасываются. */
    public ObjectNode dump(String dropCommencedBefore) {
        ObjectNode root = Json.obj();
        root.put("version", 2);
        root.set("settings", Json.copyOf(settings));
        ArrayNode out = root.putArray("events");
        events.values().stream()
                .filter(e -> dropCommencedBefore == null || e.commenceTime.compareTo(dropCommencedBefore) >= 0)
                .sorted(Comparator.comparing((Event e) -> e.commenceTime).thenComparing(e -> e.id))
                .forEach(e -> {
                    ObjectNode o = out.addObject();
                    o.put("id", e.id).put("sport_key", e.sportKey);
                    if (e.sportTitle != null) o.put("sport_title", e.sportTitle);
                    o.put("home", e.home).put("away", e.away).put("commence_time", e.commenceTime);
                    if (e.openedAt != null) o.put("opened_at", e.openedAt);
                    if (e.closedAt != null) o.put("closed_at", e.closedAt);
                    if (e.linesAt != null) o.put("lines_at", e.linesAt);
                    if (e.h2hAt != null) o.put("h2h_at", e.h2hAt);
                    ArrayNode snaps = o.putArray("snapshots");
                    e.snaps.forEach((ts, prices) -> {
                        ObjectNode p = Json.obj();
                        new TreeMap<>(prices).forEach((code, pr) -> {
                            ArrayNode v = p.putArray(code).add(pr.price());
                            if (pr.point() != null) v.add(pr.point());
                        });
                        snaps.addArray().add(ts).add(p);
                    });
                });
        return root;
    }

    public void load(JsonNode data) {
        data.path("settings").fields().forEachRemaining(en -> setSetting(en.getKey(), en.getValue()));
        boolean v1 = data.path("version").asInt(1) < 2;
        for (JsonNode ev : data.path("events")) {
            Meta m = new Meta(ev.path("id").asText(), ev.path("sport_key").asText(), textOrNull(ev, "sport_title"),
                    ev.path("home").asText(), ev.path("away").asText(), ev.path("commence_time").asText());
            JsonNode snaps = ev.path("snapshots");
            for (JsonNode snap : snaps) {
                String ts = snap.get(0).asText();
                if (v1) { // [ts, home, draw, away]
                    Map<String, Price> p = new LinkedHashMap<>();
                    String[] codes = {"home", "draw", "away"};
                    for (int i = 0; i < 3; i++) {
                        JsonNode v = snap.get(i + 1);
                        if (v != null && !v.isNull() && v.asDouble() != 0) p.put(codes[i], new Price(v.asDouble(), null));
                    }
                    savePrices(m, ts, p);
                } else {
                    savePrices(m, ts, upgradeCodes(snap.get(1)));
                }
            }
            if (snaps.isEmpty()) continue;
            for (String field : List.of("opened_at", "closed_at", "lines_at", "h2h_at")) {
                String value = textOrNull(ev, field);
                if (value == null && v1 && field.equals("opened_at")) value = snaps.get(0).get(0).asText();
                if (value != null) mark(m.id(), field, value);
            }
        }
    }

    /** Ранние снимки v2 хранили основную линию без кода линии ("over", point=2.5) — переводим в "over@2.5". */
    private static Map<String, Price> upgradeCodes(JsonNode prices) {
        Map<String, Price> res = new LinkedHashMap<>();
        prices.fields().forEachRemaining(en -> {
            String code = en.getKey();
            JsonNode v = en.getValue();
            double price = v.get(0).asDouble();
            Double point = v.size() > 1 && !v.get(1).isNull() ? v.get(1).asDouble() : null;
            if (Markets.LINE_BASES.contains(code) && point != null) {
                if (Markets.isQuarter(point)) return;
                code = Markets.lineCode(code, point);
            }
            res.put(code, new Price(price, point));
        });
        return res;
    }

    private static String textOrNull(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    List<Event> allEvents() { return new ArrayList<>(events.values()); }
}
