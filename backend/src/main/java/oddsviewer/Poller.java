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

    LeagueCfg cfgFor(String sport) { return cfg.forSport(sport); }

    Duration far(String sport) {
        Double h = cfgFor(sport).farHours();
        return Times.hours(h != null ? h : cfg.linesFarHours());
    }

    /** Рынки снимка по матчу: по точкам — без реального ДШ (экономия), иначе на открытии/закрытии — с ним. */
    String openMarkets(String sport) { return cfgFor(sport).byPoints() ? cfg.linesMarkets() : cfg.openMarkets(); }

    String closeMarkets(String sport) { return cfgFor(sport).byPoints() ? cfg.linesMarkets() : cfg.closeMarkets(); }
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
        if (client instanceof KeyRouter r && !r.hasOwnKey(sport)) {
            msg += " — своего ключа в ODDS_API_KEYS у лиги нет (или он совпадает с общим), запросы идут общим ODDS_API_KEY";
        }
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
                boolean keepQuarter = cfgFor(raw.path("sport_key").asText()).leagueMode();
                return Markets.flatPrices(bm, raw.path("home_team").asText(), raw.path("away_team").asText(), keepQuarter);
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
    /**
     * Пора ли снять матч. По точкам: «за 13 ч», …, если снимка после этой точки ещё не было
     * (промежуточные точки пропускаются, когда квоты ключа впритык — бережём закрытия).
     * По интервалам: раз в N ч, а когда до старта <= 13 ч — раз в 3 ч. За час — уже закрытие.
     */
    public boolean linesDue(Store.Event ev, Instant now) {
        Instant start = Times.parse(ev.commenceTime);
        Duration toStart = Duration.between(now, start);
        if (toStart.compareTo(closeWindow()) <= 0 || ev.closedAt != null) return false;
        String last = firstNonNull(ev.linesAt, store.lastLinesTs(ev.id), ev.openedAt);
        if (last == null) return true;
        Instant lastDt = Times.parse(last);
        LeagueCfg lc = cfgFor(ev.sportKey);
        if (lc.byPoints()) {
            // «Открытие» линий: матч открыт без тоталов и фор (новую лигу сначала снимаем запросом по лиге — там только 1X2)
            if (!hasLines(ev)) return !budgetTight(ev.sportKey, now);
            for (double p : lc.points()) {
                Instant at = start.minus(Times.hours(p));
                if (!now.isBefore(at.minus(SLACK)) && lastDt.isBefore(at.minus(SLACK))) return !budgetTight(ev.sportKey, now);
            }
            return false;
        }
        return intervalDue(ev.sportKey, start, lastDt, now);
    }

    /** Был ли у матча снимок линий тоталов и фор (или попытка, на которую Pinnacle ответил без них). */
    private boolean hasLines(Store.Event ev) {
        return ev.linesAt != null || store.lastLinesTs(ev.id) != null;
    }

    /** По интервалам: раз в N ч, а когда до старта <= 13 ч — раз в 3 ч. */
    private boolean intervalDue(String sport, Instant start, Instant lastDt, Instant now) {
        boolean isNear = Duration.between(now, start).compareTo(nearWindow()) <= 0;
        if (isNear && Duration.between(lastDt, start).compareTo(nearWindow()) > 0) {
            return true; // только что вошли в последние 13 ч
        }
        return Duration.between(lastDt, now).compareTo((isNear ? near() : far(sport)).minus(SLACK)) >= 0;
    }

    private Instant intervalNext(String sport, Instant start, Instant lastDt) {
        Instant nearStart = start.minus(nearWindow());
        return lastDt.isBefore(nearStart) ? min(lastDt.plus(far(sport)), nearStart) : lastDt.plus(near());
    }

    /** Гибридный режим: пора ли снять 1X2 запросом по лиге (закрытие делает снимок по матчу). */
    public boolean h2hDue(Store.Event ev, Instant now) {
        Instant start = Times.parse(ev.commenceTime);
        if (Duration.between(now, start).compareTo(closeWindow()) <= 0 || ev.closedAt != null) return false;
        String last = firstNonNull(ev.h2hAt, ev.linesAt, store.lastLinesTs(ev.id), ev.openedAt);
        return last == null || intervalDue(ev.sportKey, start, Times.parse(last), now);
    }

    /** Квоты ключа лиги едва хватает на закрытия её предстоящих матчей — промежуточные снимки пропускаем. */
    boolean budgetTight(String sport, Instant now) {
        String r = client.quotaFor(sport).get("remaining");
        if (r == null) return false;
        long pending = store.upcoming(Times.iso(now)).stream()
                .filter(e -> sport.equals(e.sportKey) && e.closedAt == null).count();
        try {
            return Double.parseDouble(r) - SNAPSHOT_COST * pending < cfg.minQuota();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static final int SNAPSHOT_COST = 3; // 1X2 + все тоталы + все форы

    /** Когда по расписанию следующий снимок матча (для статуса на сайте). */
    public Instant nextSnapshotAt(Store.Event ev, Instant now) {
        Instant start = Times.parse(ev.commenceTime);
        if (!start.isAfter(now) || ev.closedAt != null) return null;
        Instant closeAt = start.minus(closeWindow());
        String last = firstNonNull(ev.linesAt, store.lastLinesTs(ev.id), ev.openedAt);
        if (last == null) return now;
        Instant lastDt = Times.parse(last);
        LeagueCfg lc = cfgFor(ev.sportKey);
        Instant due;
        if (lc.byPoints()) {
            if (!hasLines(ev)) return now;
            due = closeAt;
            for (double p : lc.points()) {
                Instant at = start.minus(Times.hours(p));
                if (lastDt.isBefore(at.minus(SLACK)) && at.isBefore(due)) due = at;
            }
            if (lc.hybrid()) {
                String h = firstNonNull(ev.h2hAt, last);
                due = min(due, intervalNext(ev.sportKey, start, Times.parse(h)));
            }
        } else {
            due = intervalNext(ev.sportKey, start, lastDt);
        }
        Instant t = min(due, closeAt);
        return t.isBefore(now) ? now : t;
    }

    /** Для статуса: расписание по лигам, лиги с одинаковыми настройками — вместе. */
    public String scheduleSummary() {
        Map<String, String> titles = new java.util.HashMap<>();
        JsonNode cat = store.getSetting("sports_catalog");
        if (cat != null) cat.forEach(x -> titles.put(x.path("key").asText(), x.path("title").asText()));
        Map<String, List<String>> byDesc = new java.util.LinkedHashMap<>();
        for (String sp : sports()) {
            byDesc.computeIfAbsent(cfgFor(sp).describe(cfg), k -> new ArrayList<>()).add(titles.getOrDefault(sp, sp));
        }
        if (byDesc.size() == 1) return byDesc.keySet().iterator().next();
        List<String> parts = new ArrayList<>();
        byDesc.forEach((desc, names) -> parts.add(String.join(", ", names) + " — " + desc));
        return String.join("; ", parts);
    }

    /**
     * Для сайта: когда у каждой лиги ближайшие обновления коэффициентов по расписанию —
     * 1X2 запросом по лиге, линии тоталов и фор (какой матч, какая точка) и закрытие.
     * Время — по расписанию; прогон идёт каждые 15 мин, так что снимок будет в ближайший после него прогон.
     */
    public ArrayNode leagueSchedule(Instant now) {
        ArrayNode out = Json.arr();
        List<Store.Event> upcoming = store.upcoming(Times.iso(now));
        for (String sport : sports()) {
            LeagueCfg lc = cfgFor(sport);
            ObjectNode row = out.addObject().put("sport", sport);
            Instant h2h = null, lines = null, close = null;
            Store.Event linesEv = null, closeEv = null;
            String linesWhat = null;
            int matches = 0;
            for (Store.Event ev : upcoming) {
                if (!sport.equals(ev.sportKey) || ev.closedAt != null) continue;
                matches++;
                Instant start = Times.parse(ev.commenceTime), closeAt = start.minus(closeWindow());
                Instant c = closeAt.isBefore(now) ? now : closeAt;
                if (close == null || c.isBefore(close)) { close = c; closeEv = ev; }
                if (!closeAt.isAfter(now)) continue;
                // 1X2 по лиге (гибрид) / снимок лиги (режим лиги)
                if (lc.hybrid() || lc.leagueMode()) {
                    String last = lc.hybrid() ? firstNonNull(ev.h2hAt, ev.linesAt, store.lastLinesTs(ev.id), ev.openedAt)
                            : firstNonNull(ev.linesAt, ev.openedAt);
                    Instant t = last == null ? now : intervalNext(sport, start, Times.parse(last));
                    if (t.isBefore(closeAt)) {
                        t = t.isBefore(now) ? now : t;
                        if (h2h == null || t.isBefore(h2h)) h2h = t;
                    }
                }
                if (lc.leagueMode()) continue;
                // Линии по матчу
                Instant t = null;
                String what = null;
                if (lc.byPoints()) {
                    if (!hasLines(ev)) { t = now; what = "открытие"; }
                    else {
                        Instant lastDt = Times.parse(firstNonNull(ev.linesAt, store.lastLinesTs(ev.id)));
                        for (double pt : lc.points()) {
                            Instant at = start.minus(Times.hours(pt));
                            if (lastDt.isBefore(at.minus(SLACK)) && at.isBefore(closeAt) && (t == null || at.isBefore(t))) {
                                t = at;
                                what = "за " + Json.g(pt) + " ч";
                            }
                        }
                    }
                } else {
                    String last = firstNonNull(ev.linesAt, store.lastLinesTs(ev.id), ev.openedAt);
                    t = last == null ? now : intervalNext(sport, start, Times.parse(last));
                    what = "по интервалу";
                    if (!t.isBefore(closeAt)) t = null;
                }
                if (t != null) {
                    t = t.isBefore(now) ? now : t;
                    if (lines == null || t.isBefore(lines)) { lines = t; linesEv = ev; linesWhat = what; }
                }
            }
            row.put("matches", matches);
            row.put("h2h_at", h2h == null ? null : Times.iso(h2h));
            row.put("lines_at", lines == null ? null : Times.iso(lines));
            row.put("lines_point", linesWhat);
            row.put("lines_match", linesEv == null ? null : linesEv.home + " — " + linesEv.away);
            row.put("close_at", close == null ? null : Times.iso(close));
            row.put("close_match", closeEv == null ? null : closeEv.home + " — " + closeEv.away);
        }
        return out;
    }

    public String nextSnapshot(Instant now) {
        Set<String> tracked = Set.copyOf(sports());
        return store.upcoming(Times.iso(now)).stream().filter(e -> tracked.contains(e.sportKey))
                .map(e -> nextSnapshotAt(e, now)).filter(Objects::nonNull).min(Instant::compareTo).map(Times::iso).orElse(null);
    }

    // --- полный режим ---
    private int discover(List<String> sports, Instant now, String ts, List<String> errors) {
        int opened = 0;
        for (String sport : sports) {
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
                    if (eventSnapshot(raw, openMarkets(sport), ts, "opened_at")) opened++;
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
                        if (eventSnapshot(raw, openMarkets(sport), ts, "opened_at")) {
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

    private int closeLines(List<String> sports, Instant now, String ts, List<String> errors) {
        int closed = 0;
        Set<String> tracked = Set.copyOf(sports);
        for (Store.Event ev : store.toClose(Times.iso(now), Times.iso(now.plus(closeWindow())))) {
            if (!tracked.contains(ev.sportKey)) continue;
            if (quotaLow(ev.sportKey)) {
                quotaError(ev.sportKey, errors);
                continue;
            }
            try {
                if (eventSnapshot(rawOf(ev), closeMarkets(ev.sportKey), ts, "closed_at")) closed++;
            } catch (RuntimeException e) {
                if (!eventGone(e, ev)) errors.add(ev.home + " — " + ev.away + " (закрытие): " + e.getMessage());
            }
        }
        return closed;
    }

    private int snapLines(List<String> sports, Instant now, String ts, List<String> errors, boolean force, Set<String> linesNow) {
        int taken = 0;
        Set<String> tracked = Set.copyOf(sports);
        for (Store.Event ev : store.upcoming(Times.iso(now))) {
            if (!tracked.contains(ev.sportKey) || ev.closedAt != null) continue;
            if (!force && !linesNow.contains(ev.sportKey) && !linesDue(ev, now)) continue;
            if (quotaLow(ev.sportKey)) {
                quotaError(ev.sportKey, errors);
                continue;
            }
            try {
                String markets = cfgFor(ev.sportKey).hybrid() ? Settings.HYBRID_LINES : cfg.linesMarkets();
                if (eventSnapshot(rawOf(ev), markets, ts, "lines_at")) taken++;
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
        if (cfgFor(sport).hybrid()) {
            // новые матчи и закрытие снимает запрос по матчу; по лиге — только частый 1X2
            for (Store.Event ev : store.upcoming(Times.iso(now))) {
                if (sport.equals(ev.sportKey) && h2hDue(ev, now)) return "расписание 1X2";
            }
            return null;
        }
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

    private record Part(int saved, List<String> polled, int opened, int closed, int lines) {}

    private Part leaguePart(List<String> sports, boolean force, Instant now, String ts, List<String> errors) {
        int saved = 0, opened = 0, closed = 0;
        List<String> polled = new ArrayList<>();
        for (String sport : sports) {
            String reason = force ? "вручную" : leagueDue(sport, now, errors);
            if (reason == null) continue;
            if (quotaLow(sport)) {
                quotaError(sport, errors);
                continue;
            }
            Set<String> known = store.eventIds(sport);
            JsonNode events;
            try {
                events = client.odds(sport, cfgFor(sport).hybrid() ? "h2h" : cfg.leagueSnapshotMarkets(), BOOKMAKER);
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
                if (cfgFor(sport).hybrid()) {
                    store.mark(id, "h2h_at", ts);
                    saved++;
                    continue;
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
        return new Part(saved, polled, opened, closed, 0);
    }

    // --- цикл ---
    /**
     * Один запуск (cron каждые 15 мин). force — снять все предстоящие матчи сейчас, не глядя на расписание.
     * changed=false, если ничего не сохранено и нет новых ошибок — тогда коммитить нечего.
     */
    public ObjectNode runCycle(boolean force, Instant now) {
        return runCycle(force, now, Set.of());
    }

    /** linesNow — лиги, по матчам которых снять все тоталы и форы сейчас, вне расписания (кнопка на сайте). */
    public ObjectNode runCycle(boolean force, Instant now, Set<String> linesNow) {
        String ts = Times.iso(now);
        List<String> errors = new ArrayList<>();
        List<String> leagueSports = sports().stream().filter(sp -> cfgFor(sp).leagueMode() || cfgFor(sp).hybrid()).toList();
        List<String> full = sports().stream().filter(sp -> !cfgFor(sp).leagueMode()).toList();

        // Полный режим: новые матчи -> новые лиги -> закрытие -> снимки по расписанию (запросы по матчу)
        int opened = discover(full, now, ts, errors);
        // Запрос по лиге — только для только что подключённых лиг (снять всё, что уже висит)
        List<String> first = full.stream().filter(sp -> sportLastPoll(sp) == null).toList();
        LeaguePoll lp = first.isEmpty() ? new LeaguePoll(0, List.of(), 0) : pollLeagues(first, now, ts, errors);
        int closed = closeLines(full, now, ts, errors);
        int lines = snapLines(full, now, ts, errors, force, linesNow);

        // Режим лиги: один запрос на лигу, когда хотя бы одному её матчу пора
        Part lg = leagueSports.isEmpty() ? new Part(0, List.of(), 0, 0, 0) : leaguePart(leagueSports, force, now, ts, errors);

        List<String> polled = new ArrayList<>(lp.polled());
        polled.addAll(lg.polled());
        int totalOpened = opened + lp.opened() + lg.opened(), totalClosed = closed + lg.closed();
        if (lines > 0 || totalClosed > 0 || totalOpened > 0 || !polled.isEmpty()) store.setString("last_poll_at", ts);
        ObjectNode result = result(ts, polled, lp.saved() + lg.saved(), totalOpened, totalClosed, lines, errors);
        boolean changed = !polled.isEmpty() || totalOpened > 0 || totalClosed > 0 || lines > 0 || !sameErrors(errors);
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
