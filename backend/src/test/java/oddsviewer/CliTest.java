package oddsviewer;

import com.fasterxml.jackson.databind.JsonNode;
import oddsviewer.Fixtures.FakeApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static oddsviewer.Fixtures.bmDefault;
import static oddsviewer.Fixtures.raw;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliTest {
    @TempDir Path tmp;
    final FakeApi api = new FakeApi();
    final Instant now = Instant.parse("2026-11-20T12:00:00Z");

    @AfterEach
    void restore() {
        Cli.clientFactory = env -> KeyRouter.fromEnv(env, OddsClient::new);
        Cli.senderFactory = env -> new Telegram(env.get("TELEGRAM_BOT_TOKEN"), env.get("TELEGRAM_CHAT_ID"));
    }

    @Test
    void telegramAlertsOnlyWhenConfigured() throws Exception {
        List<String> sent = new java.util.ArrayList<>();
        Cli.senderFactory = e -> sent::add;
        var env = env("soccer_epl");
        api.league.add(raw("e1", now.plus(Duration.ofDays(1)), bmDefault()));
        assertEquals(0, Cli.run(args("run"), env, now));
        assertEquals(List.of(), sent);                                   // без токена бот молчит

        env.put("TELEGRAM_BOT_TOKEN", "t");
        env.put("TELEGRAM_CHAT_ID", "1");
        assertEquals(0, Cli.run(args("run"), env, now.plus(Duration.ofMinutes(15))));
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).startsWith("✅ Бот подключён"));
        JsonNode state = Json.parse(Files.readString(tmp.resolve("state.json")));
        assertTrue(state.get("settings").has("alerts_sent"));            // запомнено в state — повторно не придёт
    }

    Map<String, String> env(String sports) throws Exception {
        Cli.clientFactory = e -> api;
        Map<String, String> env = new HashMap<>();
        env.put("TRACK_SPORTS", sports);
        env.put("GITHUB_OUTPUT", tmp.resolve("gh_out").toString());
        Files.writeString(tmp.resolve("gh_out"), "");
        return env;
    }

    String[] args(String... extra) {
        List<String> a = new java.util.ArrayList<>(List.of(extra));
        a.addAll(List.of("--state", tmp.resolve("state.json").toString(), "--out", tmp.resolve("site").toString(),
                "--site", tmp.resolve("nosite").toString()));
        return a.toArray(String[]::new);
    }

    @Test
    void runPollsThenSkipsUntilNextSlotAndForceSnapshotsMatches() throws Exception {
        var env = env("soccer_epl");
        api.league.add(raw("e1", now.plus(Duration.ofDays(1)), bmDefault()));
        assertEquals(0, Cli.run(args("run"), env, now));
        assertEquals(List.of("odds:soccer_epl:h2h"), api.paid());
        assertTrue(Files.readString(tmp.resolve("gh_out")).contains("changed=true"));
        JsonNode data = Json.parse(Files.readString(tmp.resolve("site/data.json")));
        assertEquals("Kosovo", data.get("events").get(0).get("home").asText());
        assertEquals("400", data.get("status").get("quota").get("remaining").asText());
        assertEquals("soccer_epl", data.get("sports").get(0).get("key").asText());
        JsonNode state = Json.parse(Files.readString(tmp.resolve("state.json")));
        assertEquals(1.75, state.get("events").get(0).get("snapshots").get(0).get(1).get("home").get(0).asDouble());

        // Сразу второй запуск: никому не пора — без платных запросов и без изменений
        Files.writeString(tmp.resolve("gh_out"), "");
        assertEquals(0, Cli.run(args("run"), env, now.plus(Duration.ofMinutes(15))));
        assertEquals(1, api.paid().size());
        assertTrue(Files.readString(tmp.resolve("gh_out")).contains("changed=false"));

        // --force снимает все предстоящие матчи (запросом по матчу)
        assertEquals(0, Cli.run(args("run", "--force"), env, now.plus(Duration.ofMinutes(30))));
        assertEquals("event:e1:" + Settings.LINES_MARKETS, api.paid().get(1));
    }

    @Test
    void sportsFromSiteOverrideEnvAndPersist() throws Exception {
        var env = env("soccer_epl");
        Cli.run(args("run"), env, now);
        assertEquals(List.of("odds:soccer_epl:h2h"), api.paid());
        Cli.run(args("run", "--sports", "soccer_uefa_nations_league, soccer_epl"), env, now.plus(Duration.ofMinutes(15)));
        assertEquals("odds:soccer_uefa_nations_league:h2h", api.paid().get(1));
        JsonNode state = Json.parse(Files.readString(tmp.resolve("state.json")));
        assertEquals("[\"soccer_epl\",\"soccer_uefa_nations_league\"]", state.get("settings").get("tracked_sports").toString());
    }

    @Test
    void stateFileFormatIsStableAcrossRewrite() throws Exception {
        var env = env("soccer_epl");
        api.league.add(raw("e1", now.plus(Duration.ofDays(1)), bmDefault()));
        Cli.run(args("run"), env, now);
        String first = Files.readString(tmp.resolve("state.json"));
        assertTrue(first.startsWith("{\"version\": 2,\n\"settings\": {"));
        Cli.run(args("normalize"), env, now);
        assertEquals(first, Files.readString(tmp.resolve("state.json")));
    }

    @Test
    void badLeagueKeyRejected() {
        assertThrows(IllegalArgumentException.class, () -> Cli.parseSports("soccer_epl; rm -rf /"));
    }
}
