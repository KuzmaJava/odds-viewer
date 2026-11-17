package oddsviewer;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Отдельный ключ API на лигу: запросы по лиге идут её ключом, остальные — ключом по умолчанию.
 * Ключи задаются секретом ODDS_API_KEYS — по строке на лигу: "soccer_epl=KEY" (можно и через запятую).
 */
public final class KeyRouter implements OddsApi {
    private final OddsApi fallback;                  // ODDS_API_KEY, может быть null
    private final Map<String, OddsApi> bySport;      // лига -> клиент с её ключом

    public KeyRouter(OddsApi fallback, Map<String, OddsApi> bySport) {
        this.fallback = fallback;
        this.bySport = bySport;
    }

    /** Для сообщений об ошибке — ключ не печатаем целиком. */
    private static String mask(String s) {
        return s.length() <= 6 ? s : s.substring(0, 3) + "…";
    }

    /**
     * Разбор ODDS_API_KEYS: пары "лига=ключ". Некорректная пара пропускается с предупреждением в лог
     * (остальные лиги работают дальше) — её лига пойдёт общим ключом.
     */
    private static void warn(String msg) {
        System.err.println("ODDS_API_KEYS: " + msg + " — строка пропущена");
    }

    public static Map<String, String> parseKeys(String raw) {
        Map<String, String> res = new LinkedHashMap<>();
        if (raw == null) return res;
        // Пары «лига=ключ» разделяются переводом строки, запятой, «;» или пробелом (ключи API пробелов не содержат);
        // комментарий «#» — до конца строки. Кавычки вокруг ключа убираются.
        for (String rawLine : raw.split("\\R")) {
            int hash = rawLine.indexOf('#');
            String noComment = hash >= 0 ? rawLine.substring(0, hash) : rawLine;
            // (?U)\s — любые пробелы, включая неразрывный и прочие невидимые символы из буфера обмена
            String clean = noComment.replaceAll("[\\u200B-\\u200D\\uFEFF]", " ");
            for (String part : clean.replaceAll("(?U)\\s*=\\s*", "=").split("(?U)[,;\\s]+")) {
                String line = part.trim();
                if (line.isEmpty()) continue;
                int i = line.indexOf('=');
                if (i <= 0) { warn("ожидается «лига=ключ», а здесь без «=»: «" + mask(line) + "»"); continue; }
                String sport = line.substring(0, i).toLowerCase(), key = line.substring(i + 1).replaceAll("^[\"']|[\"']$", "");
                if (!sport.matches("[a-z0-9_]+")) { warn("некорректная лига «" + sport + "»"); continue; }
                if (!key.matches("[A-Za-z0-9]+")) { warn("у лиги «" + sport + "» ключ должен состоять из букв и цифр"); continue; }
                res.put(sport, key);
            }
        }
        return res;
    }

    /** Клиент из окружения: один ключ — обычный клиент, ключи по лигам — маршрутизатор. */
    public static OddsApi fromEnv(Map<String, String> env, BiFunction<String, String, OddsApi> factory) {
        String regions = env.getOrDefault("ODDS_REGIONS", "eu");
        String def = env.getOrDefault("ODDS_API_KEY", "").trim();
        Map<String, String> keys = parseKeys(env.get("ODDS_API_KEYS"));
        OddsApi fallback = def.isEmpty() ? null : factory.apply(def, regions);
        if (keys.isEmpty()) return fallback;
        Map<String, OddsApi> bySport = new LinkedHashMap<>();
        Map<String, OddsApi> byKey = new LinkedHashMap<>(); // одна лига = один ключ, но ключ может быть общим
        keys.forEach((sport, key) -> bySport.put(sport, key.equals(def) && fallback != null ? fallback
                : byKey.computeIfAbsent(key, k -> factory.apply(k, regions))));
        return new KeyRouter(fallback, bySport);
    }

    /** Есть ли у лиги свой ключ в ODDS_API_KEYS (иначе запросы идут общим ODDS_API_KEY). */
    public boolean hasOwnKey(String sport) {
        return bySport.get(sport) != null && bySport.get(sport) != fallback;
    }

    /** Лиги со своим ключом — для лога (без самих ключей). */
    public java.util.Set<String> ownKeySports() {
        java.util.Set<String> res = new java.util.TreeSet<>();
        bySport.forEach((sp, c) -> { if (c != fallback) res.add(sp); });
        return res;
    }

    public boolean hasKeyFor(String sport) {
        return bySport.containsKey(sport) || fallback != null;
    }

    private OddsApi forSport(String sport) {
        OddsApi c = bySport.getOrDefault(sport, fallback);
        if (c == null) throw new ApiException(0, "нет ключа API для лиги " + sport + " (ODDS_API_KEYS)");
        return c;
    }

    private OddsApi any() {
        if (fallback != null) return fallback;
        return bySport.values().iterator().next();
    }

    @Override public Map<String, String> quota() { return any().quota(); }

    @Override public Map<String, String> quotaFor(String sport) {
        OddsApi c = bySport.getOrDefault(sport, fallback);
        return c == null ? Map.of() : c.quota();
    }

    @Override public Map<String, Map<String, String>> quotasByKey() {
        Map<String, Map<String, String>> res = new LinkedHashMap<>();
        if (fallback != null) res.put("default", fallback.quota());
        bySport.forEach((sport, c) -> { if (c != fallback) res.put(sport, c.quota()); });
        return res;
    }

    @Override public JsonNode sports() { return any().sports(); }

    @Override public JsonNode events(String sport) { return forSport(sport).events(sport); }

    @Override public JsonNode odds(String sport, String markets, String bookmakers) {
        return forSport(sport).odds(sport, markets, bookmakers);
    }

    @Override public JsonNode eventOdds(String sport, String eventId, String markets, String bookmakers) {
        return forSport(sport).eventOdds(sport, eventId, markets, bookmakers);
    }
}
