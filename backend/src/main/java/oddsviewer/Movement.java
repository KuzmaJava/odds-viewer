package oddsviewer;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Движение коэффициентов: открытие -> текущее значение по каждому исходу всех рынков.
 * Тоталы и форы: каждая линия — отдельный исход; main — основная линия (коэффициенты пары ближе всего).
 * Двойной шанс без реального значения в снимке — расчёт из 1X2 того же снимка (derived).
 */
public final class Movement {
    record Base(int order, String label, String market) {}

    static final Map<String, Base> BASE = new LinkedHashMap<>();
    static {
        String[][] o = {
                {"home", "П1", "1x2"}, {"draw", "X", "1x2"}, {"away", "П2", "1x2"},
                {"1X", "1X", "dc"}, {"12", "12", "dc"}, {"X2", "X2", "dc"},
                {"over", "ТБ", "total"}, {"under", "ТМ", "total"},
                {"ah_home", "Ф1", "spread"}, {"ah_away", "Ф2", "spread"},
                {"it1_over", "ИТ1Б", "team_total"}, {"it1_under", "ИТ1М", "team_total"},
                {"it2_over", "ИТ2Б", "team_total"}, {"it2_under", "ИТ2М", "team_total"},
        };
        for (int i = 0; i < o.length; i++) BASE.put(o[i][0], new Base(i, o[i][1], o[i][2]));
    }

    public static final Map<String, String> MARKETS = new LinkedHashMap<>();
    static {
        MARKETS.put("1x2", "1X2");
        MARKETS.put("dc", "Двойной шанс");
        MARKETS.put("total", "Тотал");
        MARKETS.put("spread", "Фора");
        MARKETS.put("team_total", "Инд. тотал");
    }

    /** Пары исходов одной линии: A, B и знак линии B относительно A. */
    record Pair(String a, String b, int sign) {}
    static final List<Pair> PAIRS = List.of(new Pair("over", "under", 1), new Pair("ah_home", "ah_away", -1),
            new Pair("it1_over", "it1_under", 1), new Pair("it2_over", "it2_under", 1));
    static final List<String> DC = List.of("1X", "12", "X2");

    record P(double price, Double point, boolean derived) {}

    private Movement() {}

    public static Double changePct(Double opening, Double current) {
        if (opening == null || current == null || opening == 0 || current == 0) return null;
        return Json.round((current - opening) / opening * 100, 2);
    }

    /** Цены снимка + двойной шанс: реальный, если снят, иначе рассчитанный из 1X2 (derived=true). */
    static Map<String, P> withDc(Map<String, Price> prices) {
        Map<String, P> res = new LinkedHashMap<>();
        prices.forEach((c, p) -> res.put(c, new P(p.price(), p.point(), false)));
        boolean allDc = DC.stream().allMatch(prices::containsKey);
        boolean all1x2 = List.of("home", "draw", "away").stream().allMatch(prices::containsKey);
        if (!allDc && all1x2) {
            Map<String, Double> d = Markets.deriveDoubleChance(prices.get("home").price(), prices.get("draw").price(), prices.get("away").price());
            for (String c : DC) {
                if (!res.containsKey(c) && d.get(c) != null) res.put(c, new P(d.get(c), null, true));
            }
        }
        return res;
    }

    static String fmtPoint(String base, double point) {
        String s = Json.g(point);
        return base.startsWith("ah_") && point > 0 ? "+" + s : s;
    }

    /** Основная линия каждого рынка по ценам снимка: где коэффициенты пары ближе всего. */
    static Set<String> mainLines(Map<String, P> current) {
        Set<String> main = new HashSet<>();
        for (Pair pr : PAIRS) {
            double best = Double.MAX_VALUE;
            String ba = null, bb = null;
            for (var en : current.entrySet()) {
                if (!Markets.baseOf(en.getKey()).equals(pr.a()) || en.getValue().point() == null) continue;
                String other = Markets.lineCode(pr.b(), pr.sign() * en.getValue().point());
                P o = current.get(other);
                if (o != null && Math.abs(en.getValue().price() - o.price()) < best) {
                    best = Math.abs(en.getValue().price() - o.price());
                    ba = en.getKey();
                    bb = other;
                }
            }
            if (ba != null) { main.add(ba); main.add(bb); }
        }
        return main;
    }

    private static Map<String, P> pairOnly(Map<String, P> prices, Pair pr) {
        Map<String, P> res = new LinkedHashMap<>();
        prices.forEach((c, v) -> { String b = Markets.baseOf(c); if (b.equals(pr.a()) || b.equals(pr.b())) res.put(c, v); });
        return res;
    }

    public static ObjectNode eventMovement(Store.Event ev) {
        List<Map.Entry<String, Map<String, P>>> full = new ArrayList<>();
        ev.snaps.forEach((ts, p) -> full.add(Map.entry(ts, withDc(p))));
        Map<String, Map.Entry<String, P>> first = new LinkedHashMap<>(), last = new LinkedHashMap<>();
        Map<String, Integer> count = new HashMap<>();
        for (var snap : full) {
            snap.getValue().forEach((code, v) -> {
                first.putIfAbsent(code, Map.entry(snap.getKey(), v));
                last.put(code, Map.entry(snap.getKey(), v));
                count.merge(code, 1, Integer::sum);
            });
        }
        // Основная линия — по последнему снимку с рынком; на открытии — по первому (для сдвига «2.25 → 2.5»)
        Set<String> main = new HashSet<>();
        Map<String, Double> openMain = new HashMap<>();
        for (Pair pr : PAIRS) {
            List<Map<String, P>> with = full.stream().map(Map.Entry::getValue)
                    .filter(p -> p.keySet().stream().anyMatch(c -> Markets.baseOf(c).equals(pr.a()))).toList();
            if (with.isEmpty()) continue;
            main.addAll(mainLines(pairOnly(with.get(with.size() - 1), pr)));
            mainLines(pairOnly(with.get(0), pr)).stream().filter(c -> Markets.baseOf(c).equals(pr.a()))
                    .findFirst().ifPresent(c -> openMain.put(pr.a(), Markets.pointOf(c)));
        }

        List<String> codes = new ArrayList<>(last.keySet());
        codes.sort(Comparator.comparingInt((String c) -> BASE.containsKey(Markets.baseOf(c)) ? BASE.get(Markets.baseOf(c)).order() : 99)
                .thenComparingDouble(c -> { Double p = Markets.pointOf(c); return p == null ? 0 : p; }));

        ObjectNode res = Json.obj();
        res.put("id", ev.id).put("sport_key", ev.sportKey).put("sport_title", ev.sportTitle)
                .put("home", ev.home).put("away", ev.away).put("commence_time", ev.commenceTime)
                .put("snapshots", ev.snapshotCount()).put("first_ts", ev.firstTs()).put("last_ts", ev.lastTs())
                .put("opened_at", ev.openedAt).put("closed_at", ev.closedAt);
        var outcomes = res.putArray("outcomes");
        for (String code : codes) {
            String base = Markets.baseOf(code);
            Base b = BASE.get(base);
            if (b == null) continue;
            Double point = Markets.pointOf(code);
            var open = first.get(code);
            var cur = last.get(code);
            boolean isMain = main.contains(code) || point == null;
            ObjectNode o = outcomes.addObject();
            o.put("code", code).put("base", base).put("market", b.market())
                    .put("label", point == null ? b.label() : b.label() + " " + fmtPoint(base, point));
            if (point == null) o.putNull("point"); else o.put("point", point);
            o.put("main", isMain);
            Double om = main.contains(code) ? openMain.get(base) : null;
            if (om == null) o.putNull("open_main_point"); else o.put("open_main_point", om);
            o.put("opening", open.getValue().price()).put("current", cur.getValue().price());
            Double ch = changePct(open.getValue().price(), cur.getValue().price());
            if (ch == null) o.putNull("change_pct"); else o.put("change_pct", ch);
            o.put("open_ts", open.getKey()).put("cur_ts", cur.getKey()).put("n", count.get(code))
                    .put("derived", open.getValue().derived() || cur.getValue().derived());
        }
        return res;
    }
}
