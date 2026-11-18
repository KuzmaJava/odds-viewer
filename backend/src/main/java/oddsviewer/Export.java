package oddsviewer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Статус трекера и data.json для сайта — тот же формат, что у Python (app/export.py), см. frontend/src/types.ts. */
public final class Export {
    private Export() {}

    public static ObjectNode status(Poller p, Instant now) {
        ObjectNode st = Json.obj();
        st.put("demo", false);
        ArrayNode tracked = st.putArray("tracked_sports");
        p.sports().forEach(tracked::add);

        Settings c = p.cfg;
        ObjectNode policy = st.putObject("policy");
        policy.put("league_markets", c.leagueMarkets())
                .put("close_window_min", c.closeWindowMin())
                .put("league_mode", c.leagueMode())
                .put("league_snapshot_markets", c.leagueSnapshotMarkets())
                .put("lines_far_hours", c.linesFarHours())
                .put("lines_near_hours", c.linesNearHours())
                .put("lines_near_window_hours", c.linesNearWindowHours())
                .put("summary", p.scheduleSummary());

        st.put("last_poll_at", p.lastPollAt());
        st.put("next_poll_at", p.nextSnapshot(now));
        st.set("schedule", p.leagueSchedule(now));
        JsonNode lastResult = p.lastResult != null ? p.lastResult : p.store.getSetting("last_result");
        st.set("last_result", lastResult == null ? Json.F.nullNode() : lastResult);
        List<String> errors = new ArrayList<>();
        if (lastResult != null) lastResult.path("errors").forEach(x -> errors.add(x.asText()));
        String alertsError = p.store.getString("alerts_error");
        if (alertsError != null && !alertsError.isBlank()) errors.add(alertsError);
        String lastError = p.lastError != null ? p.lastError : errors.isEmpty() ? null : String.join("; ", errors);
        st.put("last_error", lastError);

        if (p.client.quota().get("remaining") != null) {
            ObjectNode q = st.putObject("quota");
            p.client.quota().forEach(q::put);
        } else {
            JsonNode q = p.store.getSetting("quota");
            st.set("quota", q == null ? Json.obj() : q);
        }
        // Остаток по ключам лиг (если у лиг свои ключи): живые значения поверх сохранённых
        ObjectNode quotas = Json.obj();
        JsonNode saved = p.store.getSetting("quotas");
        if (saved != null && saved.isObject()) quotas.setAll((ObjectNode) saved.deepCopy());
        p.client.quotasByKey().forEach((label, qm) -> {
            if (qm.get("remaining") == null) return;
            ObjectNode q = quotas.putObject(label);
            qm.forEach(q::put);
        });
        if (!quotas.isEmpty()) st.set("quotas", quotas);
        return st;
    }

    /** Всё, что нужно странице в статическом режиме, одним файлом data.json. */
    public static ObjectNode siteData(Poller p, JsonNode sports, String repo, String ref, Instant now) {
        ObjectNode d = Json.obj();
        d.put("generated_at", Times.iso(now));
        d.put("repo", repo);
        d.put("ref", ref);
        d.set("status", status(p, now));
        ObjectNode markets = d.putObject("markets");
        Movement.MARKETS.forEach(markets::put);
        ArrayNode events = d.putArray("events");
        ObjectNode history = d.putObject("history");
        for (Store.Event e : p.store.events(null)) {
            events.add(Movement.eventMovement(e));
            history.set(e.id, p.store.history(e.id));
        }
        d.set("sports", sports == null ? Json.arr() : sports);
        return d;
    }
}
