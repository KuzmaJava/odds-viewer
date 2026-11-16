package oddsviewer;

import oddsviewer.Fixtures.FakeApi;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static oddsviewer.Fixtures.bm;
import static oddsviewer.Fixtures.raw;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Отдельный ключ API на лигу. */
class KeyRouterTest {
    static final double[] H2H = {1.75, 3.81, 4.98};

    @Test
    void parsesLinesAndCommas() {
        var keys = KeyRouter.parseKeys("""
                # ключи по лигам
                soccer_epl = AAA
                soccer_spain_la_liga=BBB, soccer_italy_serie_a=CCC
                """);
        assertEquals(Map.of("soccer_epl", "AAA", "soccer_spain_la_liga", "BBB", "soccer_italy_serie_a", "CCC"), keys);
        assertThrows(IllegalArgumentException.class, () -> KeyRouter.parseKeys("soccer_epl"));
        assertThrows(IllegalArgumentException.class, () -> KeyRouter.parseKeys("Bad League=AAA"));
    }

    @Test
    void fromEnvBuildsOneClientPerKey() {
        Map<String, FakeApi> made = new HashMap<>();
        Map<String, String> env = Map.of("ODDS_API_KEY", "DEF", "ODDS_API_KEYS", "soccer_epl=AAA\nsoccer_spain_la_liga=AAA\nsoccer_italy_serie_a=DEF");
        OddsApi api = KeyRouter.fromEnv(env, (key, regions) -> made.computeIfAbsent(key, k -> new FakeApi()));
        assertInstanceOf(KeyRouter.class, api);
        assertEquals(2, made.size()); // DEF и AAA — по одному клиенту на ключ
        assertSame(made.get("AAA").quota(), api.quotaFor("soccer_spain_la_liga"));
        assertSame(made.get("DEF").quota(), api.quotaFor("soccer_germany_bundesliga")); // без своего ключа — общий

        OddsApi single = KeyRouter.fromEnv(Map.of("ODDS_API_KEY", "DEF"), (key, regions) -> made.get("DEF"));
        assertSame(made.get("DEF"), single);
    }

    @Test
    void eachLeagueUsesItsKeyAndExhaustedKeyStopsOnlyItsLeague() {
        FakeApi epl = new FakeApi(), liga = new FakeApi(), def = new FakeApi();
        epl.league.add(raw("e", PollerTest.NOW.plus(Duration.ofDays(2)), bm(H2H, null, null, null)));
        liga.league.add(raw("l", PollerTest.NOW.plus(Duration.ofDays(2)), bm(H2H, null, null, null)));
        liga.quota.put("remaining", "3"); // у Ла Лиги ключ почти пуст
        KeyRouter router = new KeyRouter(def, Map.of("soccer_epl", epl, "soccer_spain_la_liga", liga));
        Store s = new Store();
        Poller p = new Poller(router, s, Settings.defaults(List.of("soccer_epl", "soccer_spain_la_liga")).withLeagueMode(true, 8));

        var res = p.runCycle(false, PollerTest.NOW);
        assertEquals(List.of("odds:soccer_epl:h2h,totals"), epl.paid());
        assertEquals(List.of(), liga.paid());
        assertEquals(List.of(), def.paid());
        assertEquals(List.of("soccer_epl"), Json.MAPPER.convertValue(res.get("sports"), List.class));
        assertTrue(res.get("errors").get(0).asText().startsWith("soccer_spain_la_liga: квота"));
        // остаток по каждому ключу сохраняется для сайта
        assertEquals("400", s.getSetting("quotas").get("soccer_epl").get("remaining").asText());
        assertEquals("3", s.getSetting("quotas").get("soccer_spain_la_liga").get("remaining").asText());
    }

    @Test
    void leagueWithoutAnyKeyIsReportedAndSkipped() {
        FakeApi epl = new FakeApi();
        KeyRouter router = new KeyRouter(null, Map.of("soccer_epl", epl));
        assertThrows(OddsApi.ApiException.class, () -> router.odds("soccer_spain_la_liga", "h2h", "pinnacle"));
        Poller p = new Poller(router, new Store(), Settings.defaults(List.of("soccer_epl", "soccer_spain_la_liga")).withLeagueMode(true, 8));
        var res = p.runCycle(false, PollerTest.NOW);
        assertEquals(1, epl.paid().size());
        assertTrue(res.get("errors").toString().contains("нет ключа API для лиги soccer_spain_la_liga"));
    }
}
