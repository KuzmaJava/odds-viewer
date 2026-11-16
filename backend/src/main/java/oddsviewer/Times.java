package oddsviewer;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Время в формате API и state.json: "2026-11-14T14:00:00Z" (UTC, до секунд). */
public final class Times {
    private Times() {}

    public static String iso(Instant t) {
        return t.truncatedTo(ChronoUnit.SECONDS).toString();
    }

    public static Instant parse(String s) {
        return Instant.parse(s);
    }

    public static Duration hours(double h) {
        return Duration.ofSeconds(Math.round(h * 3600));
    }

    public static Duration minutes(double m) {
        return Duration.ofSeconds(Math.round(m * 60));
    }
}
