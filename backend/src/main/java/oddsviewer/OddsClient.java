package oddsviewer;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** HTTP-клиент The Odds API v4. */
public final class OddsClient implements OddsApi {
    private static final String BASE_URL = "https://api.the-odds-api.com/v4";

    private final String apiKey;
    private final String regions;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final Map<String, String> quota = new LinkedHashMap<>();

    public OddsClient(String apiKey, String regions) {
        this.apiKey = apiKey;
        this.regions = regions;
        quota.put("remaining", null);
        quota.put("used", null);
        quota.put("last_cost", null);
    }

    @Override public Map<String, String> quota() { return quota; }

    private JsonNode get(String path, Map<String, String> params) {
        Map<String, String> all = new LinkedHashMap<>(params);
        all.put("apiKey", apiKey);
        String q = all.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE_URL + path + "?" + q)).timeout(Duration.ofSeconds(30)).GET().build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ApiException(0, "сеть: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(0, "прервано");
        }
        quota.put("remaining", resp.headers().firstValue("x-requests-remaining").orElse(null));
        quota.put("used", resp.headers().firstValue("x-requests-used").orElse(null));
        quota.put("last_cost", resp.headers().firstValue("x-requests-last").orElse(null));
        if (resp.statusCode() != 200) {
            String msg = resp.body();
            try {
                JsonNode b = Json.parse(resp.body());
                if (b.hasNonNull("message")) msg = b.get("message").asText();
            } catch (IllegalArgumentException ignored) { /* не JSON */ }
            throw new ApiException(resp.statusCode(), msg);
        }
        return Json.parse(resp.body());
    }

    private Map<String, String> oddsParams(String markets, String bookmakers) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("markets", markets);
        p.put("oddsFormat", "decimal");
        p.put("dateFormat", "iso");
        if (bookmakers != null) p.put("bookmakers", bookmakers); else p.put("regions", regions);
        return p;
    }

    @Override public JsonNode sports() { return get("/sports", Map.of()); }

    @Override public JsonNode events(String sport) { return get("/sports/" + sport + "/events", Map.of("dateFormat", "iso")); }

    @Override public JsonNode odds(String sport, String markets, String bookmakers) {
        return get("/sports/" + sport + "/odds", oddsParams(markets, bookmakers));
    }

    @Override public JsonNode eventOdds(String sport, String eventId, String markets, String bookmakers) {
        return get("/sports/" + sport + "/events/" + eventId + "/odds", oddsParams(markets, bookmakers));
    }
}
