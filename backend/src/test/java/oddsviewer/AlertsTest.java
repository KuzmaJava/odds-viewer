package oddsviewer;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static oddsviewer.StoreMovementTest.meta;
import static oddsviewer.StoreMovementTest.prices;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertsTest {
    static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");
    static final Instant KICKOFF = NOW.plus(Duration.ofDays(2));
    final Alerts.Cfg cfg = new Alerts.Cfg(5, Set.of("1x2", "total", "spread"), Set.of(), "https://x.github.io/odds-viewer/");
    final List<String> sent = new ArrayList<>();
    final Alerts.Sender sender = sent::add;

    static void snap(Store s, String ts, Object... kv) {
        s.savePrices(meta("m", KICKOFF), ts, prices(kv));
    }

    /** Открытие: П1 2.00, X 3.40, ТБ 2.5 1.90 / ТМ 1.90, неосновная ТБ 3.5 3.00. */
    static Store opened() {
        Store s = new Store();
        snap(s, "2026-11-17T10:00:00Z", "home", 2.0, null, "draw", 3.4, null, "away", 3.8, null,
                "over@2.5", 1.9, 2.5, "under@2.5", 1.9, 2.5, "over@3.5", 3.0, 3.5, "under@3.5", 1.35, 3.5);
        return s;
    }

    @Test
    void firstRunOnlyAnnouncesAndRemembersExistingMoves() {
        Store s = opened();
        snap(s, "2026-11-18T09:00:00Z", "home", 1.8, null, "draw", 3.5, null, "away", 4.2, null);
        Alerts.Result r = Alerts.run(s, cfg, sender, NOW);
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).startsWith("✅ Бот подключён"));
        assertTrue(sent.get(0).contains("Уже сдвинулись: 2"));                 // П1 −10%, П2 +10.5%; X +2.9% — ниже порога
        assertTrue(r.changed());
        sent.clear();
        Alerts.run(s, cfg, sender, NOW.plus(Duration.ofMinutes(15)));          // ничего нового — тишина
        assertEquals(List.of(), sent);
    }

    @Test
    void sendsNewMovesOnceAndAgainOnlyAfterAnotherStep() {
        Store s = opened();
        Alerts.run(s, cfg, sender, NOW);                                       // подключение, сдвигов нет
        sent.clear();

        snap(s, "2026-11-18T09:00:00Z", "home", 1.8, null, "draw", 3.4, null, "away", 3.8, null,
                "over@2.5", 1.7, 2.5, "under@2.5", 2.1, 2.5, "over@3.5", 2.5, 3.5, "under@3.5", 1.5, 3.5);
        Alerts.run(s, cfg, sender, NOW);
        assertEquals(1, sent.size());
        String msg = sent.get(0);
        assertTrue(msg.contains("<b>K — I</b>"));
        assertTrue(msg.contains("EPL · 20.11 12:00"));                         // время по Киеву
        assertTrue(msg.contains("🔻 П1: <b>1.80</b> (открытие 2.00, −10.0%)"));
        assertTrue(msg.contains("🟢 ТМ 2.5: <b>2.10</b> (открытие 1.90, +10.5%)"));
        assertFalse(msg.contains("3.5"));                                      // неосновная линия тотала — без алерта
        assertTrue(msg.endsWith("<a href=\"https://x.github.io/odds-viewer/\">Открыть трекер</a>"));

        sent.clear();
        snap(s, "2026-11-18T12:00:00Z", "home", 1.78, null, "draw", 3.4, null, "away", 3.8, null);
        Alerts.run(s, cfg, sender, NOW.plus(Duration.ofHours(2)));            // П1 −11% — ещё не +5% к прошлому алерту
        assertEquals(List.of(), sent);
        snap(s, "2026-11-18T15:00:00Z", "home", 1.68, null, "draw", 3.4, null, "away", 3.8, null);
        Alerts.run(s, cfg, sender, NOW.plus(Duration.ofHours(5)));            // −16% — следующий шаг
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).contains("П1: <b>1.68</b>"));
        assertFalse(sent.get(0).contains("ТМ"));
    }

    @Test
    void failedSendKeepsMovesForNextRunAndReportsError() {
        Store s = opened();
        Alerts.run(s, cfg, sender, NOW);
        snap(s, "2026-11-18T09:00:00Z", "home", 1.8, null, "draw", 3.4, null, "away", 3.8, null);
        Alerts.Result r = Alerts.run(s, cfg, html -> { throw new IllegalStateException("Forbidden"); }, NOW);
        assertEquals("Telegram: Forbidden", r.error());
        sent.clear();
        Alerts.run(s, cfg, sender, NOW.plus(Duration.ofMinutes(15)));
        assertEquals(1, sent.size());                                          // не потерялось
    }

    @Test
    void finishedMatchesAreForgottenAndCfgReadsEnv() {
        Store s = opened();
        snap(s, "2026-11-18T09:00:00Z", "home", 1.8, null, "draw", 3.4, null, "away", 3.8, null);
        Alerts.run(s, cfg, sender, NOW);
        assertNotNull(s.getSetting(Alerts.SENT).get("m|home"));
        Alerts.run(s, cfg, sender, KICKOFF.plus(Duration.ofHours(3)));
        assertTrue(s.getSetting(Alerts.SENT).isEmpty());

        Alerts.Cfg c = Alerts.Cfg.fromEnv(Map.of("ALERT_MIN_PCT", "7,5", "ALERT_SPORTS", "soccer_epl, soccer_spain_la_liga",
                "GITHUB_REPOSITORY", "KuzmaJava/odds-viewer"));
        assertEquals(7.5, c.minPct());
        assertEquals(Set.of("soccer_epl", "soccer_spain_la_liga"), c.sports());
        assertEquals(Set.of("1x2", "total", "spread"), c.markets());
        assertEquals("https://kuzmajava.github.io/odds-viewer/", c.siteUrl());
    }
}
