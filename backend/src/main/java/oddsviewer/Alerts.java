package oddsviewer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Алерты в Telegram: исход сдвинулся от открытия на ALERT_MIN_PCT % и больше (просадка или рост).
 * Отправляется после каждого прогона трекера. Чтобы не слать одно и то же, в state хранится
 * последний отправленный % по каждому исходу (настройка alerts_sent): повторно исход приходит,
 * только когда сдвинулся ещё на ALERT_MIN_PCT % дальше или движение сменило направление.
 */
public final class Alerts {
    private Alerts() {}

    static final String SENT = "alerts_sent";
    static final int MAX_MESSAGE = 3500; // у Telegram лимит 4096 символов
    static final ZoneId ZONE = ZoneId.of("Europe/Kyiv");
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("dd.MM HH:mm", Locale.ROOT).withZone(ZONE);

    /** Отправка одного сообщения (Telegram или подмена в тестах). */
    public interface Sender {
        void send(String html);
    }

    public record Cfg(double minPct, Set<String> markets, Set<String> sports, String siteUrl) {
        static Cfg fromEnv(Map<String, String> env) {
            double min = 5;
            try {
                if (notBlank(env.get("ALERT_MIN_PCT"))) min = Double.parseDouble(env.get("ALERT_MIN_PCT").trim().replace(',', '.'));
            } catch (NumberFormatException e) {
                System.err.println("ALERT_MIN_PCT: не число — порог 5%");
            }
            String markets = notBlank(env.get("ALERT_MARKETS")) ? env.get("ALERT_MARKETS") : "1x2,total,spread";
            String sports = env.getOrDefault("ALERT_SPORTS", "");
            String repo = env.get("GITHUB_REPOSITORY");
            String site = notBlank(env.get("SITE_URL")) ? env.get("SITE_URL")
                    : notBlank(repo) && repo.contains("/")
                    ? "https://" + repo.substring(0, repo.indexOf('/')).toLowerCase(Locale.ROOT) + ".github.io/" + repo.substring(repo.indexOf('/') + 1) + "/"
                    : null;
            return new Cfg(Math.max(0.5, min), split(markets), split(sports), site);
        }
    }

    /** Найденный сдвиг исхода. */
    record Move(String key, Store.Event ev, String label, double opening, double current, double pct) {}

    public record Result(int sent, int moves, boolean changed, String error) {}

    /** Исходы, по которым пора слать алерт (с учётом уже отправленных). */
    static List<Move> due(Store store, Cfg cfg, Instant now) {
        JsonNode sentNode = store.getSetting(SENT);
        List<Move> res = new ArrayList<>();
        for (Store.Event ev : store.upcoming(Times.iso(now))) {
            if (!cfg.sports().isEmpty() && !cfg.sports().contains(ev.sportKey)) continue;
            for (JsonNode o : Movement.eventMovement(ev).path("outcomes")) {
                if (!cfg.markets().contains(o.path("market").asText()) || o.path("derived").asBoolean()) continue;
                if (o.hasNonNull("point") && !o.path("main").asBoolean()) continue; // тоталы и форы — только основная линия
                if (!o.hasNonNull("change_pct")) continue;
                double pct = o.get("change_pct").asDouble();
                if (Math.abs(pct) < cfg.minPct()) continue;
                String key = ev.id + "|" + o.path("code").asText();
                JsonNode prev = sentNode == null ? null : sentNode.get(key);
                // Отправленное ниже текущего порога (порог подняли) не считаем — исход придёт как новый
                if (prev != null && Math.abs(prev.asDouble()) >= cfg.minPct()) {
                    double p = prev.asDouble();
                    boolean flipped = Math.signum(p) != Math.signum(pct);
                    if (!flipped && Math.abs(pct) - Math.abs(p) < cfg.minPct()) continue;
                }
                res.add(new Move(key, ev, o.path("label").asText(), o.path("opening").asDouble(), o.path("current").asDouble(), pct));
            }
        }
        return res;
    }

    /** Сообщения: матчи по времени начала, внутри матча — сильнейшие сдвиги первыми; не длиннее MAX_MESSAGE. */
    static List<Map.Entry<String, List<Move>>> messages(List<Move> moves, Cfg cfg) {
        Map<Store.Event, List<Move>> byEvent = new LinkedHashMap<>();
        moves.stream()
                .sorted((a, b) -> a.ev().commenceTime.equals(b.ev().commenceTime)
                        ? a.ev().id.compareTo(b.ev().id) : a.ev().commenceTime.compareTo(b.ev().commenceTime))
                .forEach(m -> byEvent.computeIfAbsent(m.ev(), k -> new ArrayList<>()).add(m));
        List<Map.Entry<String, List<Move>>> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        List<Move> curMoves = new ArrayList<>();
        String footer = cfg.siteUrl() == null ? "" : "\n<a href=\"" + esc(cfg.siteUrl()) + "\">Открыть трекер</a>";
        for (var e : byEvent.entrySet()) {
            Store.Event ev = e.getKey();
            List<Move> ms = new ArrayList<>(e.getValue());
            ms.sort((a, b) -> Double.compare(Math.abs(b.pct()), Math.abs(a.pct())));
            StringBuilder block = new StringBuilder();
            block.append("<b>").append(esc(ev.home)).append(" — ").append(esc(ev.away)).append("</b>\n")
                    .append(esc(ev.sportTitle == null ? ev.sportKey : ev.sportTitle)).append(" · ")
                    .append(DT.format(Times.parse(ev.commenceTime))).append('\n');
            for (Move m : ms) {
                block.append(m.pct() < 0 ? "🔻 " : "🟢 ").append(esc(m.label())).append(": <b>").append(fmt(m.current()))
                        .append("</b> (открытие ").append(fmt(m.opening())).append(", ").append(pct(m.pct())).append(")\n");
            }
            block.append('\n');
            if (cur.length() > 0 && cur.length() + block.length() + footer.length() > MAX_MESSAGE) {
                out.add(Map.entry(cur.toString().stripTrailing() + footer, curMoves));
                cur = new StringBuilder();
                curMoves = new ArrayList<>();
            }
            cur.append(block);
            curMoves.addAll(ms);
        }
        if (cur.length() > 0) out.add(Map.entry(cur.toString().stripTrailing() + footer, curMoves));
        return out;
    }

    /**
     * Один проход после прогона трекера. При первом включении (в state ещё нет alerts_sent) уже случившиеся
     * сдвиги не рассылаются, а запоминаются — приходит одно сообщение «бот подключён».
     */
    public static Result run(Store store, Cfg cfg, Sender sender, Instant now) {
        List<Move> moves = due(store, cfg, now);
        ObjectNode sent = store.getSetting(SENT) instanceof ObjectNode o ? o.deepCopy() : Json.obj();
        boolean first = store.getSetting(SENT) == null;
        int count = 0;
        String error = null;
        try {
            if (first) {
                sender.send("✅ Бот подключён к трекеру Pinnacle.\nБуду присылать исходы, сдвинувшиеся от открытия на "
                        + Json.g(cfg.minPct()) + "% и больше (рынки: " + String.join(", ", cfg.markets()) + ")."
                        + (moves.isEmpty() ? "" : "\nУже сдвинулись: " + moves.size() + " — они на сайте во вкладках «Просадки» и «Повышения»; "
                        + "по ним напишу, когда сдвинутся ещё на " + Json.g(cfg.minPct()) + "%.")
                        + (cfg.siteUrl() == null ? "" : "\n" + cfg.siteUrl()));
                moves.forEach(m -> sent.put(m.key(), m.pct()));
                count = 1;
            } else {
                for (var msg : messages(moves, cfg)) {
                    sender.send(msg.getKey());
                    msg.getValue().forEach(m -> sent.put(m.key(), m.pct()));
                    count++;
                }
            }
        } catch (RuntimeException e) {
            error = "Telegram: " + e.getMessage();
            System.err.println(error);
        }
        // Матчи, которых уже нет среди предстоящих, из памяти убираем
        Set<String> upcoming = store.upcoming(Times.iso(now)).stream().map(ev -> ev.id).collect(Collectors.toSet());
        List<String> stale = new ArrayList<>();
        sent.fieldNames().forEachRemaining(k -> { if (!upcoming.contains(k.substring(0, k.indexOf('|')))) stale.add(k); });
        stale.forEach(sent::remove);
        boolean changed = !sent.equals(store.getSetting(SENT));
        if (changed) store.setSetting(SENT, sent);
        return new Result(count, moves.size(), changed, error);
    }

    static String fmt(double x) {
        return String.format(Locale.ROOT, "%.2f", x);
    }

    static String pct(double p) {
        return (p > 0 ? "+" : "−") + String.format(Locale.ROOT, "%.1f", Math.abs(p)) + "%";
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static Set<String> split(String s) {
        return Arrays.stream(s.split("[,;\\s]+")).map(String::trim).filter(x -> !x.isEmpty())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}
