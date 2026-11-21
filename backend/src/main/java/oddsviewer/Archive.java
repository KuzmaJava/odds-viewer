package oddsviewer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Архив прошедших матчей: data/archive/ГГГГ-ММ.json (месяц начала матча, UTC), тот же формат, что у state.json.
 * Матч попадает в архив через {@link #AFTER} после начала и хранится там всегда, а из state.json
 * по-прежнему уходит через Cli.KEEP_DAYS дней.
 */
public final class Archive {
    private Archive() {}

    static final Duration AFTER = Duration.ofHours(3);
    private static final Pattern FILE = Pattern.compile("\\d{4}-\\d{2}\\.json");

    static String month(String commenceTime) { return commenceTime.substring(0, 7); }

    /** Все месяцы архива: "2026-11" -> матчи. */
    static Map<String, Store> load(Path dir) {
        Map<String, Store> res = new TreeMap<>();
        if (!Files.isDirectory(dir)) return res;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(f -> FILE.matcher(f.getFileName().toString()).matches()).sorted().toList()) {
                Store s = new Store();
                s.load(Json.parse(Files.readString(f, StandardCharsets.UTF_8)));
                res.put(f.getFileName().toString().replace(".json", ""), s);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return res;
    }

    /**
     * Переносит начавшиеся (не меньше AFTER назад) матчи из state в архив. Перенесённый матч (старт снова в будущем)
     * из архива убирается. true — файлы архива изменились.
     */
    static boolean sync(Store store, Path dir, Instant now) {
        String cutoff = Times.iso(now.minus(AFTER));
        Map<String, Store> months = load(dir);
        Map<String, String> before = new TreeMap<>();
        months.forEach((m, s) -> before.put(m, text(s)));
        for (Store.Event ev : store.allEvents()) {
            if (ev.snapshotCount() == 0) continue;
            boolean done = ev.commenceTime.compareTo(cutoff) <= 0;
            String target = done ? month(ev.commenceTime) : null;
            months.forEach((m, s) -> { if (!m.equals(target)) s.remove(ev.id); });
            if (target != null) months.computeIfAbsent(target, k -> new Store()).put(ev);
        }
        boolean changed = false;
        for (var e : months.entrySet()) {
            String t = text(e.getValue());
            if (t.equals(before.get(e.getKey()))) continue;
            Cli.write(dir.resolve(e.getKey() + ".json"), t);
            changed = true;
        }
        return changed;
    }

    /** Как state.json: по строке на матч, чтобы diff в git оставался читаемым. */
    static String text(Store s) {
        ObjectNode data = s.dump(null);
        List<String> lines = new ArrayList<>();
        data.get("events").forEach(e -> lines.add(Json.py(e, false)));
        return "{\"version\": 2,\n\"events\": [\n" + String.join(",\n", lines) + "\n]}\n";
    }

    /**
     * Для сайта: archive/index.json — список всех матчей архива (поиск по команде) с 1X2 открытие/закрытие;
     * archive/ГГГГ-ММ.json — движение и история снимков матчей месяца (как в data.json).
     */
    static void exportSite(Path dir, Path out, Instant now) {
        Map<String, Store> months = load(dir);
        ObjectNode index = Json.obj();
        index.put("generated_at", Times.iso(now));
        ArrayNode ms = index.putArray("months");
        ArrayNode list = index.putArray("events");
        for (var e : months.entrySet()) {
            ObjectNode file = Json.obj();
            ArrayNode events = file.putArray("events");
            ObjectNode history = file.putObject("history");
            List<Store.Event> evs = e.getValue().events(null);
            for (Store.Event ev : evs) {
                ObjectNode mv = Movement.eventMovement(ev);
                events.add(mv);
                history.set(ev.id, e.getValue().history(ev.id));
                ObjectNode row = list.addObject();
                row.put("id", ev.id).put("sport_key", ev.sportKey).put("sport_title", ev.sportTitle)
                        .put("home", ev.home).put("away", ev.away).put("commence_time", ev.commenceTime).put("month", e.getKey());
                ObjectNode x = Json.obj();
                for (JsonNode o : mv.path("outcomes")) {
                    if (!"1x2".equals(o.path("market").asText())) continue;
                    ArrayNode v = x.putArray(o.path("code").asText());
                    v.add(o.path("opening").asDouble()).add(o.path("current").asDouble());
                }
                row.set("x", x);
            }
            ms.addObject().put("month", e.getKey()).put("count", evs.size());
            Cli.write(out.resolve("archive").resolve(e.getKey() + ".json"), Json.compact(file));
        }
        Cli.write(out.resolve("archive").resolve("index.json"), Json.compact(index));
    }
}
