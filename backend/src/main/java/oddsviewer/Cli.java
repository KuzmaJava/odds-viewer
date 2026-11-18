package oddsviewer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Запуск трекера без сервера — для GitHub Actions (каждые 15 минут, cron-job.org).
 *
 *   odds-tracker run   [--force] [--sports a,b] [--state data/state.json] [--out _site] [--site frontend/dist]
 *   odds-tracker build                         — только собрать сайт из state (без запросов к API)
 *   odds-tracker normalize                     — перечитать и записать state (проверка формата, без API)
 *
 * Состояние хранится в JSON-файле (data/state.json), который коммитится в репозиторий.
 */
public final class Cli {
    static final int KEEP_DAYS = 14;  // начавшиеся матчи храним две недели
    private static final Pattern SPORT_KEY = Pattern.compile("[a-z0-9_]+");

    /** Для тестов можно подменить клиента и окружение. */
    static Function<Map<String, String>, OddsApi> clientFactory =
            env -> KeyRouter.fromEnv(env, OddsClient::new);

    public static void main(String[] args) {
        System.exit(run(args, System.getenv(), Instant.now()));
    }

    static int run(String[] args, Map<String, String> env, Instant now) {
        String command = null, state = "data/state.json", out = "_site", site = "frontend/dist", sportsArg = "", linesNowArg = "";
        boolean force = false;
        List<String> a = new ArrayList<>(Arrays.asList(args));
        for (int i = 0; i < a.size(); i++) {
            switch (a.get(i)) {
                case "--force" -> force = true;
                case "--state" -> state = a.get(++i);
                case "--out" -> out = a.get(++i);
                case "--site" -> site = a.get(++i);
                case "--sports" -> sportsArg = a.get(++i);
                case "--lines-now" -> linesNowArg = a.get(++i);
                case "run", "build", "normalize" -> command = a.get(i);
                default -> {
                    System.err.println("Неизвестный аргумент: " + a.get(i));
                    return 2;
                }
            }
        }
        if (command == null) {
            System.err.println("Использование: odds-tracker run|build [--force] [--sports a,b] [--lines-now a,b] [--state ...] [--out ...] [--site ...]");
            return 2;
        }

        Path statePath = Path.of(state), outDir = Path.of(out);
        Store store = new Store();
        if (Files.exists(statePath)) store.load(Json.parse(read(statePath)));
        Settings cfg = Settings.fromEnv(env);
        OddsApi client = clientFactory.apply(env);
        if (client == null) {
            if (command.equals("run")) {
                System.err.println("Не задан ни ODDS_API_KEY, ни ODDS_API_KEYS");
                return 1;
            }
            client = new OddsClient("", "eu"); // build/normalize — к API не обращаемся
        }
        Poller poller = new Poller(client, store, cfg);
        JsonNode sports = store.getSetting("sports_catalog");
        boolean changed = command.equals("build");

        // Список лиг: 1) с сайта (--sports), сохраняется в state; 2) ранее сохранённый с сайта;
        // 3) переменная TRACK_SPORTS; 4) soccer_epl.
        List<String> newSports = parseSports(sportsArg);
        boolean fromUi = store.getSetting("tracked_sports_from_ui") != null && store.getSetting("tracked_sports_from_ui").asBoolean();
        if (newSports != null) {
            if (!newSports.equals(poller.sports()) || !fromUi) changed = true;
            poller.setSports(newSports);
            store.setSetting("tracked_sports_from_ui", Json.F.booleanNode(true));
        } else if (!fromUi && Settings.envSports(env) != null) {
            poller.setSports(Settings.envSports(env));
        }

        if (command.equals("run")) {
            if (client instanceof KeyRouter router) {
                List<String> noKey = poller.sports().stream().filter(sp -> !router.hasKeyFor(sp)).toList();
                if (!noKey.isEmpty()) System.err.println("Нет ключа API для лиг: " + noKey + " — они пропускаются");
                System.out.println("Свои ключи (ODDS_API_KEYS): " + router.ownKeySports() + "; остальные лиги — общим ODDS_API_KEY");
            }
            System.out.println("Отслеживаемые лиги: " + poller.sports() + "; следующий снимок по расписанию: "
                    + (poller.nextSnapshot(now) == null ? "нет" : poller.nextSnapshot(now)));
            List<String> linesNow = parseSports(linesNowArg);
            ObjectNode result = poller.runCycle(force, now, linesNow == null ? Set.of() : Set.copyOf(linesNow));
            System.out.println(Json.py(result, false));
            if (result.path("sports").size() > 0 || sports == null) {
                try { // список лиг — бесплатный запрос, нужен странице для вкладки «Лиги»
                    ArrayNode soccer = Json.arr();
                    for (JsonNode s : client.sports()) {
                        if (s.path("active").asBoolean() && !s.path("has_outrights").asBoolean() && "Soccer".equals(s.path("group").asText())) {
                            soccer.add(s);
                        }
                    }
                    store.setSetting("sports_catalog", soccer);
                    sports = soccer;
                } catch (RuntimeException e) {
                    System.err.println("Не удалось получить список лиг: " + e.getMessage());
                }
            }
            changed = changed || result.path("changed").asBoolean();
            if (alerts(store, env, now)) changed = true;
        }

        if (command.equals("normalize")) {
            writeState(store, statePath, now);
            return 0;
        }
        if (changed && command.equals("run")) writeState(store, statePath, now);
        if (changed) buildSite(poller, sports, outDir, Path.of(site), env, now);
        setOutput(env, "changed", changed ? "true" : "false");
        return 0;
    }

    /** Отправка алертов: по умолчанию Telegram (секреты TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID), в тестах — подмена. */
    static java.util.function.Function<Map<String, String>, Alerts.Sender> senderFactory =
            env -> new Telegram(env.get("TELEGRAM_BOT_TOKEN"), env.get("TELEGRAM_CHAT_ID"));

    /** Алерты после прогона. true — изменилось состояние (запомнены отправленные), state нужно сохранить. */
    static boolean alerts(Store store, Map<String, String> env, Instant now) {
        String token = env.get("TELEGRAM_BOT_TOKEN"), chat = env.get("TELEGRAM_CHAT_ID");
        if (token == null || token.isBlank()) return false; // бот не настроен — молчим
        if (chat == null || chat.isBlank()) {
            String msg = "Telegram: не задан TELEGRAM_CHAT_ID — напишите боту /start, номер чата появится в логе прогона";
            System.err.println(msg);
            try {
                if (senderFactory.apply(env) instanceof Telegram t) {
                    System.out.println("Чаты бота (TELEGRAM_CHAT_ID): " + t.recentChats());
                }
            } catch (RuntimeException e) {
                System.err.println("Telegram: " + e.getMessage());
            }
            return setAlertsError(store, msg);
        }
        Alerts.Result r = Alerts.run(store, Alerts.Cfg.fromEnv(env), senderFactory.apply(env), now);
        System.out.println("Алерты: сдвигов " + r.moves() + ", отправлено сообщений " + r.sent()
                + (r.error() == null ? "" : ", ошибка: " + r.error()));
        return setAlertsError(store, r.error()) || r.changed();
    }

    private static boolean setAlertsError(Store store, String error) {
        String prev = store.getString("alerts_error");
        if (java.util.Objects.equals(prev, error)) return false;
        if (error == null) store.setSetting("alerts_error", null); else store.setString("alerts_error", error);
        return true;
    }

    /** "a, b" -> [a, b] (отсортировано, без повторов); пусто -> null. Ключи лиг: только a-z, 0-9 и _. */
    static List<String> parseSports(String raw) {
        if (raw == null || raw.isBlank()) return null;
        TreeSet<String> keys = new TreeSet<>();
        for (String k : raw.split(",")) if (!k.isBlank()) keys.add(k.trim());
        List<String> bad = keys.stream().filter(k -> !SPORT_KEY.matcher(k).matches()).toList();
        if (!bad.isEmpty()) throw new IllegalArgumentException("Некорректные ключи лиг: " + bad);
        return List.copyOf(keys);
    }

    /** По строке на матч — чтобы diff в git оставался читаемым (тот же вид, что у Python-версии). */
    static void writeState(Store store, Path path, Instant now) {
        ObjectNode data = store.dump(Times.iso(now.minus(Duration.ofDays(KEEP_DAYS))));
        StringBuilder sb = new StringBuilder();
        sb.append("{\"version\": ").append(data.get("version").asInt()).append(",\n");
        sb.append("\"settings\": ").append(Json.py(data.get("settings"), true)).append(",\n");
        sb.append("\"events\": [\n");
        List<String> lines = new ArrayList<>();
        data.get("events").forEach(e -> lines.add(Json.py(e, false)));
        sb.append(String.join(",\n", lines)).append("\n]}\n");
        write(path, sb.toString());
    }

    static void buildSite(Poller poller, JsonNode sports, Path out, Path site, Map<String, String> env, Instant now) {
        try {
            Files.createDirectories(out);
            if (Files.exists(site.resolve("index.html"))) copyTree(site, out);
            String ref = env.get("DEFAULT_REF") != null && !env.get("DEFAULT_REF").isBlank() ? env.get("DEFAULT_REF") : env.get("GITHUB_REF_NAME");
            JsonNode data = Export.siteData(poller, sports, env.get("GITHUB_REPOSITORY"), ref, now);
            write(out.resolve("data.json"), Json.compact(data));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void setOutput(Map<String, String> env, String name, String value) {
        String ghOut = env.get("GITHUB_OUTPUT");
        if (ghOut == null || ghOut.isBlank()) return;
        try {
            Files.writeString(Path.of(ghOut), name + "=" + value + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path p, String s) {
        try {
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            Files.writeString(p, s, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
