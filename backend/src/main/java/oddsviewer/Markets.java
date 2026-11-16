package oddsviewer;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Разбор ответа The Odds API в плоские цены одной конторы: код исхода -> (коэффициент, линия).
 * Коды линий: "over@2.5", "under@2.5", "ah_home@-1", "ah_away@1", "it1_over@1.5"...
 * Четвертные линии (x.25 / x.75) по умолчанию не берём.
 */
public final class Markets {
    public static final List<String> LINE_BASES = List.of(
            "over", "under", "ah_home", "ah_away", "it1_over", "it1_under", "it2_over", "it2_under");

    private Markets() {}

    public static boolean isQuarter(double point) {
        double twice = point * 2;
        return twice != Math.rint(twice);
    }

    public static String lineCode(String base, double point) {
        return base + "@" + Json.g(point + 0.0);
    }

    /** "over@2.5" -> ("over", 2.5); "home" -> ("home", null). */
    public static Map.Entry<String, Double> splitCode(String code) {
        int i = code.indexOf('@');
        if (i < 0) return Map.entry(code, Double.NaN);
        return Map.entry(code.substring(0, i), Double.parseDouble(code.substring(i + 1)));
    }

    public static String baseOf(String code) {
        int i = code.indexOf('@');
        return i < 0 ? code : code.substring(0, i);
    }

    public static Double pointOf(String code) {
        int i = code.indexOf('@');
        return i < 0 ? null : Double.parseDouble(code.substring(i + 1));
    }

    /** 1X = 1 / (1/П1 + 1/X) и т.д. Маржа конторы при этом сохраняется (оценка). */
    public static Map<String, Double> deriveDoubleChance(Double p1, Double px, Double p2) {
        Map<String, Double> res = new LinkedHashMap<>();
        res.put("1X", combine(p1, px));
        res.put("12", combine(p1, p2));
        res.put("X2", combine(px, p2));
        return res;
    }

    private static Double combine(Double a, Double b) {
        if (a == null || b == null || a == 0 || b == 0) return null;
        return Json.round(1 / (1 / a + 1 / b), 3);
    }

    public static Map<String, Price> flatPrices(JsonNode bm, String home, String away, boolean keepQuarter) {
        Map<String, List<JsonNode>> markets = new LinkedHashMap<>();
        for (JsonNode m : bm.path("markets")) {
            String key = m.path("key").asText().replace("alternate_", "");
            List<JsonNode> list = markets.computeIfAbsent(key, k -> new ArrayList<>());
            m.path("outcomes").forEach(list::add);
        }
        Map<String, Price> res = new LinkedHashMap<>();
        PutFn put = (code, price, point) -> {
            if (price == null || price == 0) return;
            if (point == null) {
                res.put(code, new Price(price, null));
            } else if (keepQuarter || !isQuarter(point)) {
                double p = point + 0.0;
                res.put(lineCode(code, p), new Price(price, p));
            }
        };

        for (JsonNode o : markets.getOrDefault("h2h", List.of())) {
            String name = o.path("name").asText();
            Double price = num(o, "price");
            if (name.equals(home)) put.put("home", price, null);
            else if (name.equals(away)) put.put("away", price, null);
            else if (name.equals("Draw")) put.put("draw", price, null);
        }
        // двойной шанс: сначала все три, порядок 1X, 12, X2 — как в Python
        Map<String, Double> dc = new LinkedHashMap<>();
        dc.put("1X", null); dc.put("12", null); dc.put("X2", null);
        for (JsonNode o : markets.getOrDefault("double_chance", List.of())) {
            String name = o.path("name").asText();
            boolean h = name.contains(home), a = name.contains(away), d = name.contains("Draw");
            if (h && d) dc.put("1X", num(o, "price"));
            else if (h && a) dc.put("12", num(o, "price"));
            else if (a && d) dc.put("X2", num(o, "price"));
        }
        dc.forEach((c, p) -> put.put(c, p, null));

        for (double[] line : overUnder(markets.getOrDefault("totals", List.of()))) {
            put.put("over", nz(line[1]), line[0]);
            put.put("under", nz(line[2]), line[0]);
        }
        for (JsonNode o : markets.getOrDefault("spreads", List.of())) {
            if (!o.hasNonNull("point")) continue;
            String name = o.path("name").asText();
            if (name.equals(home)) put.put("ah_home", num(o, "price"), o.get("point").asDouble());
            else if (name.equals(away)) put.put("ah_away", num(o, "price"), o.get("point").asDouble());
        }
        Map<String, List<JsonNode>> byTeam = new LinkedHashMap<>();
        byTeam.put("home", new ArrayList<>());
        byTeam.put("away", new ArrayList<>());
        for (JsonNode o : markets.getOrDefault("team_totals", List.of())) {
            String team = o.path("description").asText();
            if (team.equals(home)) byTeam.get("home").add(o);
            else if (team.equals(away)) byTeam.get("away").add(o);
        }
        byTeam.forEach((side, items) -> {
            String prefix = side.equals("home") ? "it1" : "it2";
            for (double[] line : overUnder(items)) {
                put.put(prefix + "_over", nz(line[1]), line[0]);
                put.put(prefix + "_under", nz(line[2]), line[0]);
            }
        });
        return res;
    }

    @FunctionalInterface
    private interface PutFn { void put(String code, Double price, Double point); }

    /** Over/Under по линиям: [линия, over, under], по возрастанию линии (0 — нет цены). */
    private static List<double[]> overUnder(List<JsonNode> outcomes) {
        TreeMap<Double, double[]> lines = new TreeMap<>();
        for (JsonNode o : outcomes) {
            if (!o.hasNonNull("point")) continue;
            double p = o.get("point").asDouble();
            double[] line = lines.computeIfAbsent(p, k -> new double[]{k, 0, 0});
            String name = o.path("name").asText();
            if (name.equals("Over")) line[1] = o.path("price").asDouble();
            else if (name.equals("Under")) line[2] = o.path("price").asDouble();
        }
        return new ArrayList<>(lines.values());
    }

    private static Double num(JsonNode o, String field) {
        return o.hasNonNull(field) ? o.get(field).asDouble() : null;
    }

    private static Double nz(double v) {
        return v == 0 ? null : v;
    }
}
