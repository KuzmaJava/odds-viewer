package oddsviewer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Опрос Pinnacle. У каждого матча своё расписание снимков (все исходы — в один момент):
 * открытие (как только матч появился — бесплатная проверка списка каждые 15 мин), раз в N ч,
 * за 13 ч до старта и дальше раз в 3 ч, закрытие примерно за час до старта.
 *
 * Полный режим: снимок — запрос по матчу (1X2 + все нечетвертные линии тоталов и фор, ≈ 3 запроса квоты).
 * Режим лиги: снимок — один запрос на всю лигу (1X2 + основной тотал), когда хотя бы одному матчу пора.
 */
public final class Poller {
    public static final String BOOKMAKER = "pinnacle";
    private static final Duration SLACK = Duration.ofMinutes(10);

    public final OddsApi client;
    public final Store store;
    public final Settings cfg;
    public String lastError;
    public ObjectNode lastResult;

    public Poller(OddsApi client, Store store, Settings cfg) {
        this.client = client;
        this.store = store;
        this.cfg = cfg;
    }

    // --- настройки ---
    public List<String> sports() {
        JsonNode s = store.getSetting("tracked_sports");
        if (s == null || !s.isArray()) return cfg.defaultSports();
        List<String> res = new ArrayList<>();
        s.forEach(x -> res.add(x.asText()));
        return res;
    }

    public void setSports(List<String> sports) {
        ArrayNode a = Json.arr();
        sports.forEach(a::add);
        store.setSetting("tracked_sports", a);
    }

    public String sportLastPoll(String sport) { return store.getString("last_poll_at:" + sport); }

    public String lastPollAt() { return store.getString("last_poll_at"); }

    Duration far() { return Times.hours(cfg.linesFarHours()); }
    Duration near() { return Times.hours(cfg.linesNearHours()); }
    Duration nearWindow() { return Times.hours(cfg.linesNearWindowHours()); }
    Duration closeWindow() { return Times.minutes(cfg.closeWindowMin()); }

    // --- квота ---
    /** Квота ключа этой лиги почти исчерпана (у каждой лиги может быть свой ключ). */
    private boolean quotaLow(String sport) {
        String r = client.quotaFor(sport).get("remaining");
        if (r == null) return false;
        try {
            return Double.parseDouble(r) < cfg.minQuota();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Ошибка «квота кончилась» — по одной на лигу, остальные лиги продолжают опрашиваться. */
    private void quotaError(String sport, List<String> errors) {
        String msg = sport + ": квота почти исчерпана (< " + cfg.minQuota() + "), платные запросы остановлены";
        if (!errors.contains(msg)) errors.add(msg);
    }

    // --- разбор ответа ---
    static Store.Meta meta(JsonNode raw) {
        return new Store.Meta(raw.path("id").asText(), raw.path("sport_key").asText(),
                raw.hasNonNull("sport_title") ? raw.get("sport_title").asText() : null,
                raw.path("home_team").asText(), raw.path("away_team").asText(), raw.path("commence_time").asText());
    }

    Map<String, Price> pinnacle(JsonNode raw) {
        for (JsonNode bm : raw.path("bookmakers")) {
            if (BOOKMAKER.equals(bm.path("key").asText())) {
                // В режиме лиги API отдаёт одну основную линию — храним её, даже если она четвертная
                return Markets.flatPrices(bm, raw.path("home_team").asText(), raw.path("away_team").asText(), cfg.leagueMode());
            }
        }
        return Map.of();
    }

    static ObjectNode rawOf(Store.Event e) {
        ObjectNode r = Json.obj();
        r.put("id", e.id).put("sport_key", e.sportKey).put("sport_title", e.sportTitle)
                .put("home_team", e.home).put("away_team", e.away).put("commence_time", e.commenceTime);
        return r;
    }

    /** Запрос рынков одного матча; true, если Pinnacle уже дал линию (пустой ответ бесплатен). */
    private boolean eventSnapshot(JsonNode raw, String markets, String ts, String field) {
        JsonNode data = client.eventOdds(raw.path("sport_key").asText(), raw.path("id").asText(), markets, BOOKMAKER);
        if (data == null || data.isEmpty()) return false;
        ObjectNode merged = ((ObjectNode) raw.deepCopy()).setAll((ObjectNode) data);
        Map<String, Price> prices = pinnacle(merged);
        if (prices.isEmpty()) return false;
        store.savePrices(meta(merged), ts, prices);
        store.mark(raw.path("id").asText(), field, ts);
        if (prices.keySet().stream().anyMatch(c -> c.contains("@"))) store.mark(raw.path("id").asText(), "lines_at", ts);
        return true;
    }

    /** Матч сняли с линии (перенос, отмена): API отвечает «Event not found». Больше его не запрашиваем. */
    private boolean eventGone(RuntimeException e, Store.Event ev) {
        if (e.getMessage() == null || !e.getMessage().toLowerCase().contains("event not found")) return false;
        store.mark(ev.id, "closed_at", Times.iso(Instant.now()));
        return true;
    }

    private static String teams(JsonNode raw) {
        return raw.path("home_team").asText() + " — " + raw.path("away_team").asText();
    }

    // --- расписание ---
    /** Пора ли снять матч: раз в N ч, а когда до старта <= 13 ч — раз в 3 ч; за час — уже закрытие. */
    public boolean linesDue(Store.Event ev, Instant now) {
        Duration toStart = Duration.between(now, Times.parse(ev.commenceTime));
        if (toStart.compareTo(closeWindow()) <= 0 || ev.closedAt != null) return false;
        String last = firstNonNull(ev.linesAt, store.lastLinesTs(ev.id), ev.openedAt);
        if (last == null) return true;
        Instant lastDt = Times.parse(last);
        boolean isNear = toStart.compareTo(nearWindow()) <= 0;
        if (isNear && Duration.between(lastDt, Times.parse(ev.commenceTime)).compareTo(nearWindow()) > 0) {
            return true; // только что вошли в последние 13 ч
        }
        return Duration.between(lastDt, now).compareTo((isNear ? near() : far()).minus(SLACK)) >= 0;
    }

    /** Когда по расписанию следующий снимок матча (для статуса на сайте). */
    public Instant nextSnapshotAt(Store.Event ev, Instant now) {
        Instant start = Times.parse(ev.commenceTime);
        if (!start.isAfter(now) || ev.closedAt != null) return null;
        Instant closeAt = start.minus(closeWindow());
        String last = firstNonNull(ev.linesAt, store.lastLinesTs(ev.id), ev.openedAt);
        if (last == null) return now;
        Instant lastDt = Times.parse(last), nearStart = start.minus(nearWindow());
        Instant due = lastDt.isBefore(nearStart) ? min(lastDt.plus(far()), nearStart) : lastDt.plus(near());
        Instant t = min(due, closeAt);
        return t.isBefore(now) ? now : t;
    }

    public String nextSnapshot(Instant now) {
        Set<String> tracked = Set.copyOf(sports());
        return store.upcoming(Times.iso(now)).stream().filter(e -> tracked.contains(e.sportKey))
                .map(e -> nextSnapshotAt(e, now)).filter(Objects::nonNull).min(Instant::compareTo).map(Times::iso).orElse(null);
    }

    // --- полный режим ---
    private int discover(Instant now, String ts, List<String> errors) {
        int opened = 0;
        for (String sport : sports()) {
            if (sportLastPoll(sport) == null) continue;
            JsonNode listed;
            try {
                listed = client.events(sport);
            } catch (RuntimeException e) {
                errors.add(sport + " (список матчей): " + e.getMessage());
                continue;
            }
            Set<String> known = store.eventIds(sport);
            for (JsonNode raw : listed) {
                String id = raw.path("id").asText();
                if (known.contains(id)) {
                    store.updateCommence(id, raw.path("commence_time").asText()); // перенос матча
                    continue;
                }
                if (!Times.parse(raw.path("commence_time").asText()).isAfter(now)) continue;
                if (quotaLow(sport)) {
                    quotaError(sport, errors);
                    break;
                }
                try {
                    if (eventSnapshot(raw, cfg.openMarkets(), ts, "opened_at")) opened++;
                } catch (RuntimeException e) {
                    errors.add(sport + " " + teams(raw) + ": " + e.getMessage());
                }
            }
        }
        return opened;
    }

    private record LeaguePoll(int saved, List<String> polled, int opened) {}

    private LeaguePoll pollLeagues(List<String> leagues, Instant now, String ts, List<String> errors) {
        int saved = 0, opened = 0;
        List<String> polled = new ArrayList<>();
        for (String sport : leagues) {
            if (quotaLow(sport)) {
                quotaError(sport, errors);
                continue;
            }
            boolean firstPoll = sportLastPoll(sport) == null;
            Set<String> known = store.eventIds(sport);
            JsonNode events;
            try {
                events = client.odds(sport, cfg.leagueMarkets(), BOOKMAKER);
            } catch (RuntimeException e) {
                errors.add(sport + ": " + e.getMessage());
                continue;
            }
            for (JsonNode raw : events) {
                if (!Times.parse(raw.path("commence_time").asText()).isAfter(now)) continue; // только прематч
                Map<String, Price> prices = pinnacle(raw);
                if (prices.isEmpty()) continue;
                boolean isNew = !known.contains(raw.path("id").asText());
                if (isNew && !firstPoll && !quotaLow(sport)) {
                    // Линия появилась между проверками списка — снимаем открытие полным набором рынков
                    try {
                        if (eventSnapshot(raw, cfg.openMarkets(), ts, "opened_at")) {
                            opened++;
                            continue;
                        }
                    } catch (RuntimeException e) {
                        errors.add(sport + " " + teams(raw) + ": " + e.getMessage());
                    }
                }
                store.savePrices(meta(raw), ts, prices);
                if (isNew) store.mark(raw.path("id").asText(), "opened_at", ts);
                saved++;
            }
            store.setString("last_poll_at:" + sport, ts);
            polled.add(sport);
        }
        if (!polled.isEmpty()) store.setString("last_poll_at", ts);
        return new LeaguePoll(saved, polled, opened);
    }

    private int closeLines(Instant now, String ts, List<String> errors) {
        int closed = 0;
        Set<String> tracked = Set.copyOf(sports());
        for (Store.Event ev : store.toClose(Times.iso(now), Times.iso(now.plus(closeWindow())))) {
            if (!tracked.contains(ev.sportKey)) continue;
            if (quotaLow(ev.sportKey)) {
                quotaError(ev.sportKey, errors);
                continue;
            }
            try {
                if (eventSnapshot(rawOf(ev), cfg.closeMarkets(), ts, "closed_at")) closed++;
            } catch (RuntimeException e) {
                if (!eventGone(e, ev)) errors.add(ev.home + " — " + ev.away + " (закрытие): " + e.getMessage());
            }
        }
        return closed;
    }

    private int snapLines(Instant now, String ts, List<String> errors, boolean force) {
        int taken = 0;
        Set<String> tracked = Set.copyOf(sports());
        for (Store.Event ev : store.upcoming(Times.iso(now))) {
            if (!tracked.contains(ev.sportKey) || ev.closedAt != null) continue;
            if (!force && !linesDue(ev, now)) continue;
            if (quotaLow(ev.sportKey)) {
                quotaError(ev.sportKey, errors);
                continue;
            }
            try {
                if (eventSnapshot(rawOf(ev), cfg.linesMarkets(), ts, "lines_at")) taken++;
            } catch (RuntimeException e) {
                if (!eventGone(e, ev)) errors.add(ev.home + " — " + ev.away + " (линии): " + e.getMessage());
            }
        }
        return taken;
    }

    // --- режим лиги ---
    /** Почему пора снять лигу (или null): новая лига, новые матчи, расписание какого-то матча, закрытие. */
    private String leagueDue(String sport, Instant now, List<String> errors) {
        if (sportLastPoll(sport) == null) return "новая лига";
        JsonNode listed = Json.arr();
        try {
            listed = client.events(sport);
        } catch (RuntimeException e) {
            errors.add(sport + " (список матчей): " + e.getMessage());
        }
        Set<String> known = store.eventIds(sport);
        for (JsonNode raw : listed) {
            String id = raw.path("id").asText();
            if (known.contains(id)) store.updateCommence(id, raw.path("commence_time").asText());
            else if (Times.parse(raw.path("commence_time").asText()).isAfter(now)) return "новые матчи";
        }
        for (Store.Event ev : store.upcoming(Times.iso(now))) {
            if (!sport.equals(ev.sportKey) || ev.closedAt != null) continue;
            if (Duration.between(now, Times.parse(ev.commenceTime)).compareTo(closeWindow()) <= 0) return "закрытие";
            if (linesDue(ev, now)) return "расписание";
        }
        return null;
    }

    private ObjectNode runLeagueCycle(boolean force, Instant now) {
        String ts = Times.iso(now);
        List<String> errors = new ArrayList<>();
        int saved = 0, opened = 0, closed = 0;
        List<String> polled = new ArrayList<>();
        for (String sport : sports()) {
            String reason = force ? "вручную" : leagueDue(sport, now, errors);
            if (reason == null) continue;
            if (quotaLow(sport)) {
                quotaError(sport, errors);
                continue;
            }
            Set<String> known = store.eventIds(sport);
            JsonNode events;
            try {
                events = client.odds(sport, cfg.leagueSnapshotMarkets(), BOOKMAKER);
            } catch (RuntimeException e) {
                errors.add(sport + ": " + e.getMessage());
                continue;
            }
            for (JsonNode raw : events) {
                Instant start = Times.parse(raw.path("commence_time").asText());
                if (!start.isAfter(now)) continue;
                Map<String, Price> prices = pinnacle(raw);
                if (prices.isEmpty()) continue;
                String id = raw.path("id").asText();
                store.savePrices(meta(raw), ts, prices);
                if (!known.contains(id)) {
                    store.mark(id, "opened_at", ts);
                    opened++;
                }
                store.mark(id, "lines_at", ts);
                if (Duration.between(now, start).compareTo(closeWindow()) <= 0 && store.event(id).closedAt == null) {
                    store.mark(id, "closed_at", ts);
                    closed++;
                }
                saved++;
            }
            store.setString("last_poll_at:" + sport, ts);
            polled.add(sport);
        }
        if (!polled.isEmpty()) store.setString("last_poll_at", ts);
        ObjectNode result = result(ts, polled, saved, opened, closed, 0, errors);
        boolean changed = !polled.isEmpty() || !sameErrors(errors);
        if (changed) finish(result);
        return result.put("changed", changed);
    }

    // --- цикл ---
    /**
     * Один запуск (cron каждые 15 мин). force — снять все предстоящие матчи сейчас, не глядя на расписание.
     * changed=false, если ничего не сохранено и нет новых ошибок — тогда коммитить нечего.
     */
    public ObjectNode runCycle(boolean force, Instant now) {
        if (cfg.leagueMode()) return runLeagueCycle(force, now);
        String ts = Times.iso(now);
        List<String> errors = new ArrayList<>();
        int opened = discover(now, ts, errors);
        // Запрос по лиге — только для только что подключённых лиг (снять всё, что уже висит)
        List<String> first = sports().stream().filter(s -> sportLastPoll(s) == null).toList();
        LeaguePoll lp = first.isEmpty() ? new LeaguePoll(0, List.of(), 0) : pollLeagues(first, now, ts, errors);
        int closed = closeLines(now, ts, errors);
        int lines = snapLines(now, ts, errors, force);
        if (lines > 0 || closed > 0 || opened > 0 || lp.opened() > 0) store.setString("last_poll_at", ts);
        ObjectNode result = result(ts, lp.polled(), lp.saved(), opened + lp.opened(), closed, lines, errors);
        boolean changed = !lp.polled().isEmpty() || opened + lp.opened() > 0 || closed > 0 || lines > 0 || !sameErrors(errors);
        if (changed) finish(result);
        return result.put("changed", changed);
    }

    private static ObjectNode result(String ts, List<String> polled, int saved, int opened, int closed, int lines, List<String> errors) {
        ObjectNode r = Json.obj();
        r.put("at", ts);
        ArrayNode sp = r.putArray("sports");
        polled.forEach(sp::add);
        r.put("snapshots_saved", saved).put("opened", opened).put("closed", closed).put("lines", lines);
        ArrayNode er = r.putArray("errors");
        errors.forEach(er::add);
        return r;
    }

    private boolean sameErrors(List<String> errors) {
        JsonNode last = store.getSetting("last_result");
        List<String> prev = new ArrayList<>();
        if (last != null) last.path("errors").forEach(x -> prev.add(x.asText()));
        return prev.equals(errors);
    }

    private void finish(ObjectNode result) {
        if (client.quota().get("remaining") != null) {
            ObjectNode q = Json.obj();
            client.quota().forEach(q::put);
            store.setSetting("quota", q);
        }
        // Остаток по ключам лиг (если у лиг свои ключи): копим, чтобы показывать и между запусками
        JsonNode prev = store.getSetting("quotas");
        ObjectNode quotas = prev != null && prev.isObject() ? ((ObjectNode) prev).deepCopy() : Json.obj();
        client.quotasByKey().forEach((label, qm) -> {
            if (qm.get("remaining") == null) return;
            ObjectNode q = Json.obj();
            qm.forEach(q::put);
            quotas.set(label, q);
        });
        if (!quotas.isEmpty()) store.setSetting("quotas", quotas);
        List<String> errors = new ArrayList<>();
        result.path("errors").forEach(x -> errors.add(x.asText()));
        lastError = errors.isEmpty() ? null : String.join("; ", errors);
        lastResult = result.deepCopy();
        store.setSetting("last_result", lastResult);
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... xs) {
        for (T x : xs) if (x != null) return x;
        return null;
    }

    private static Instant min(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
}
