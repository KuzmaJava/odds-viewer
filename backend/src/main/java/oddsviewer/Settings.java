package oddsviewer;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Настройки опроса из переменных окружения (те же имена, что у Python-версии и в workflow). */
public record Settings(
        List<String> defaultSports, int minQuota,
        String leagueMarkets, String openMarkets, String linesMarkets, String closeMarkets,
        double linesFarHours, double linesNearHours, double linesNearWindowHours, double closeWindowMin,
        boolean leagueMode, String leagueSnapshotMarkets) {

    public static final String OPEN_MARKETS = "h2h,double_chance,alternate_totals,alternate_spreads,team_totals,alternate_team_totals";
    public static final String LINES_MARKETS = "h2h,alternate_totals,alternate_spreads,team_totals,alternate_team_totals";

    public static Settings defaults(List<String> sports) {
        return new Settings(sports, 10, "h2h", OPEN_MARKETS, LINES_MARKETS, OPEN_MARKETS, 12, 3, 13, 60, false, "h2h,totals");
    }

    public Settings withLeagueMode(boolean on, double farHours) {
        return new Settings(defaultSports, minQuota, leagueMarkets, openMarkets, linesMarkets, closeMarkets,
                farHours, linesNearHours, linesNearWindowHours, closeWindowMin, on, leagueSnapshotMarkets);
    }

    public Settings withMinQuota(int q) {
        return new Settings(defaultSports, q, leagueMarkets, openMarkets, linesMarkets, closeMarkets,
                linesFarHours, linesNearHours, linesNearWindowHours, closeWindowMin, leagueMode, leagueSnapshotMarkets);
    }

    /** TRACK_SPORTS="a,b" -> [a, b]; пусто -> null. */
    public static List<String> envSports(Map<String, String> env) {
        String raw = env.getOrDefault("TRACK_SPORTS", "");
        List<String> s = Arrays.stream(raw.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
        return s.isEmpty() ? null : s;
    }

    public static Settings fromEnv(Map<String, String> env) {
        List<String> sports = envSports(env);
        return new Settings(
                sports == null ? List.of("soccer_epl") : sports,
                Integer.parseInt(env.getOrDefault("MIN_QUOTA_REMAINING", "10").trim()),
                nonEmpty(env.get("LEAGUE_MARKETS"), "h2h"),
                OPEN_MARKETS, LINES_MARKETS, OPEN_MARKETS,
                Double.parseDouble(env.getOrDefault("LINES_FAR_HOURS", "12").trim()),
                Double.parseDouble(env.getOrDefault("LINES_NEAR_HOURS", "3").trim()),
                Double.parseDouble(env.getOrDefault("LINES_NEAR_WINDOW_HOURS", "13").trim()),
                Double.parseDouble(env.getOrDefault("CLOSE_WINDOW_MIN", "60").trim()),
                List.of("1", "true", "yes").contains(env.getOrDefault("LEAGUE_MODE", "0").trim()),
                nonEmpty(env.get("LEAGUE_SNAPSHOT_MARKETS"), "h2h,totals"));
    }

    private static String nonEmpty(String v, String def) {
        return v == null || v.isBlank() ? def : v.trim();
    }
}
