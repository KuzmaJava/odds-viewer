package oddsviewer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Настройки опроса одной лиги.
 * <ul>
 *   <li>{@code leagueMode} — режим лиги: снимок одним запросом на всю лигу (1X2 + основной тотал);
 *       иначе полный режим — запрос по матчу со всеми нечетвертными линиями тоталов и фор;</li>
 *   <li>{@code points} — точки снимков в часах до старта (например, 13): открытие, эти точки и закрытие.
 *       Пусто — расписание по интервалам (раз в N ч, за 13 ч — раз в 3 ч);</li>
 *   <li>{@code farHours} — свой интервал фоновых снимков (иначе общий LINES_FAR_HOURS).</li>
 * </ul>
 * Задаётся переменной LEAGUE_SETTINGS — по строке (или через «;») на лигу:
 * <pre>
 * default: full, points 13h
 * soccer_uefa_nations_league: league
 * soccer_uefa_champs_league: league, far 24h
 * </pre>
 */
public record LeagueCfg(boolean leagueMode, List<Double> points, Double farHours) {

    public LeagueCfg {
        points = points.stream().sorted(Comparator.reverseOrder()).toList();
    }

    public boolean byPoints() { return !leagueMode && !points.isEmpty(); }

    public static Map<String, LeagueCfg> parse(String raw) {
        Map<String, LeagueCfg> res = new LinkedHashMap<>();
        if (raw == null) return res;
        for (String part : raw.split("[\\n;]")) {
            String line = part.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int i = line.indexOf(':');
            if (i <= 0) throw new IllegalArgumentException("LEAGUE_SETTINGS: ожидается «лига: настройки», получено «" + line + "»");
            String sport = line.substring(0, i).trim();
            if (!sport.equals("default") && !sport.matches("[a-z0-9_]+")) {
                throw new IllegalArgumentException("LEAGUE_SETTINGS: некорректный ключ лиги «" + sport + "»");
            }
            res.put(sport, parseOne(line.substring(i + 1)));
        }
        return res;
    }

    static LeagueCfg parseOne(String spec) {
        boolean league = false;
        List<Double> points = new ArrayList<>();
        Double far = null;
        String[] t = spec.toLowerCase(Locale.ROOT).replace(",", " ").trim().split("\\s+");
        for (int i = 0; i < t.length; i++) {
            switch (t[i]) {
                case "" -> { }
                case "full" -> league = false;
                case "league" -> league = true;
                case "points" -> {
                    while (i + 1 < t.length && t[i + 1].matches("\\d+(\\.\\d+)?h?")) points.add(hours(t[++i]));
                }
                case "far" -> {
                    if (i + 1 >= t.length) throw new IllegalArgumentException("LEAGUE_SETTINGS: после far нужно число часов");
                    far = hours(t[++i]);
                }
                default -> throw new IllegalArgumentException("LEAGUE_SETTINGS: непонятное слово «" + t[i] + "»");
            }
        }
        return new LeagueCfg(league, points, far);
    }

    private static double hours(String s) {
        return Double.parseDouble(s.endsWith("h") ? s.substring(0, s.length() - 1) : s);
    }

    /** Для статуса на сайте: «все линии: открытие, за 13 ч, закрытие». */
    public String describe(Settings s) {
        if (leagueMode) {
            String what = "h2h".equals(s.leagueSnapshotMarkets()) ? "1X2" : "1X2 + основной тотал";
            double far = farHours != null ? farHours : s.linesFarHours();
            return "режим лиги (" + what + "): раз в " + Json.g(far) + " ч, за " + Json.g(s.linesNearWindowHours())
                    + " ч до матча — раз в " + Json.g(s.linesNearHours()) + " ч, закрытие";
        }
        if (byPoints()) {
            StringBuilder sb = new StringBuilder("все линии: открытие");
            points.forEach(p -> sb.append(", за ").append(Json.g(p)).append(" ч"));
            return sb.append(", закрытие").toString();
        }
        double far = farHours != null ? farHours : s.linesFarHours();
        return "все линии: раз в " + Json.g(far) + " ч, за " + Json.g(s.linesNearWindowHours()) + " ч до матча — раз в "
                + Json.g(s.linesNearHours()) + " ч, закрытие";
    }
}
