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

    /** Разбор ODDS_API_KEYS: строки или запятые, "лига=ключ"; пустые строки и # — пропускаются. */
    public static Map<String, String> parseKeys(String raw) {
        Map<String, String> res = new LinkedHashMap<>();
        if (raw == null) return res;
        for (String part : raw.split("[\\n,;]")) {
            String line = part.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int i = line.indexOf('=');
            if (i <= 0) throw new IllegalArgumentException("ODDS_API_KEYS: ожидается «лига=ключ» в каждой строке");
            String sport = line.substring(0, i).trim(), key = line.substring(i + 1).trim();
            if (!sport.matches("[a-z0-9_]+") || key.isEmpty()) {
                throw new IllegalArgumentException("ODDS_API_KEYS: некорректная строка для лиги «" + sport + "»");
            }
            res.put(sport, key);
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
