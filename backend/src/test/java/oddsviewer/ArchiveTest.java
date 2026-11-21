package oddsviewer;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static oddsviewer.StoreMovementTest.prices;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveTest {
    @TempDir Path tmp;
    static final Instant NOW = Instant.parse("2026-12-01T12:00:00Z");

    static void match(Store s, String id, String home, Instant start) {
        var m = new Store.Meta(id, "soccer_russia_premier_league", "Russia Premier League", home, "Krylia Sovetov", Times.iso(start));
        s.savePrices(m, Times.iso(start.minus(Duration.ofDays(2))), prices("home", 1.93, null, "draw", 3.4, null, "away", 4.0, null));
        s.savePrices(m, Times.iso(start.minus(Duration.ofHours(1))), prices("home", 2.32, null, "draw", 3.3, null, "away", 3.5, null));
        s.mark(id, "closed_at", Times.iso(start.minus(Duration.ofHours(1))));
    }

    @Test
    void finishedMatchesGoToMonthFilesAndStayAfterLeavingState() throws Exception {
        Store s = new Store();
        match(s, "a", "Rostov", Instant.parse("2026-11-30T16:00:00Z"));   // ноябрь, давно начался
        match(s, "b", "Zenit", NOW.minus(Duration.ofHours(1)));            // начался час назад — ещё рано
        match(s, "c", "Spartak", NOW.plus(Duration.ofDays(1)));            // впереди
        Path dir = tmp.resolve("archive");
        assertTrue(Archive.sync(s, dir, NOW));
        assertEquals(1, Archive.load(dir).get("2026-11").events(null).size());
        assertFalse(Files.exists(dir.resolve("2026-12.json")));
        assertFalse(Archive.sync(s, dir, NOW.plus(Duration.ofMinutes(15))));  // без изменений — файлы не трогаем

        assertTrue(Archive.sync(s, dir, NOW.plus(Duration.ofHours(3))));
        assertEquals("Zenit", Archive.load(dir).get("2026-12").events(null).get(0).home);

        // Матч ушёл из state (14 дней) — в архиве остаётся
        assertFalse(Archive.sync(new Store(), dir, NOW.plus(Duration.ofDays(20))));
        assertEquals(1, Archive.load(dir).get("2026-11").events(null).size());

        // Сайт: индекс для поиска по команде и файл месяца с историей
        Path site = tmp.resolve("site");
        Archive.exportSite(dir, site, NOW);
        JsonNode index = Json.parse(Files.readString(site.resolve("archive/index.json")));
        assertEquals(2, index.get("events").size());
        JsonNode rostov = index.get("events").get(0);
        assertEquals("Rostov", rostov.get("home").asText());
        assertEquals("2026-11", rostov.get("month").asText());
        assertEquals(1.93, rostov.get("x").get("home").get(0).asDouble());
        assertEquals(2.32, rostov.get("x").get("home").get(1).asDouble());
        JsonNode nov = Json.parse(Files.readString(site.resolve("archive/2026-11.json")));
        assertEquals(2, nov.get("history").get("a").size());
        assertEquals("a", nov.get("events").get(0).get("id").asText());
    }

    @Test
    void postponedMatchLeavesArchive() {
        Store s = new Store();
        match(s, "a", "Rostov", Instant.parse("2026-11-30T16:00:00Z"));
        Path dir = tmp.resolve("archive");
        Archive.sync(s, dir, NOW);
        s.updateCommence("a", Times.iso(NOW.plus(Duration.ofDays(7))));     // матч перенесли
        assertTrue(Archive.sync(s, dir, NOW.plus(Duration.ofMinutes(15))));
        assertTrue(Archive.load(dir).get("2026-11").events(null).isEmpty());
    }
}
