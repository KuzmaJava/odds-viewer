package oddsviewer;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Telegram Bot API: отправка сообщений. Токен в сообщения об ошибках и в лог не попадает. */
public final class Telegram implements Alerts.Sender {
    private static final String API = "https://api.telegram.org/bot";
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final String token, chatId;

    public Telegram(String token, String chatId) {
        this.token = token.trim();
        this.chatId = chatId == null ? null : chatId.trim();
    }

    @Override
    public void send(String html) {
        sendTo(chatId, html);
    }

    public void sendTo(String chat, String html) {
        var body = Json.obj().put("chat_id", chat).put("text", html).put("parse_mode", "HTML")
                .put("disable_web_page_preview", true);
        call("sendMessage", Json.compact(body));
    }

    /** id чатов, написавших боту (/start) или куда его добавили, — без повторов. */
    public List<String> recentChatIds() {
        List<String> res = new ArrayList<>();
        for (JsonNode u : call("getUpdates", "{}").path("result")) {
            for (String f : List.of("message", "channel_post", "my_chat_member")) {
                JsonNode id = u.path(f).path("chat").path("id");
                if (!id.isMissingNode() && !res.contains(id.asText())) res.add(id.asText());
            }
        }
        return res;
    }

    /** Чаты, написавшие боту (/start) или куда его добавили, — чтобы узнать TELEGRAM_CHAT_ID. */
    public List<String> recentChats() {
        List<String> res = new ArrayList<>();
        for (JsonNode u : call("getUpdates", "{}").path("result")) {
            for (String f : List.of("message", "channel_post", "my_chat_member")) {
                JsonNode chat = u.path(f).path("chat");
                if (chat.isMissingNode()) continue;
                String name = chat.hasNonNull("title") ? chat.get("title").asText()
                        : (chat.path("first_name").asText("") + " " + chat.path("username").asText("")).trim();
                String line = chat.path("id").asText() + " (" + chat.path("type").asText() + ": " + name + ")";
                if (!res.contains(line)) res.add(line);
            }
        }
        return res;
    }

    private JsonNode call(String method, String json) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(API + token + "/" + method))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("нет связи с api.telegram.org: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("прервано");
        }
        JsonNode data;
        try {
            data = Json.parse(resp.body());
        } catch (RuntimeException e) {
            throw new IllegalStateException("HTTP " + resp.statusCode());
        }
        if (!data.path("ok").asBoolean()) {
            String d = data.path("description").asText("HTTP " + resp.statusCode());
            String hint = resp.statusCode() == 401 ? " — проверьте TELEGRAM_BOT_TOKEN"
                    : d.contains("chat not found") ? " — проверьте TELEGRAM_CHAT_ID и что вы написали боту /start (или добавили его в канал админом)"
                    : "";
            throw new IllegalStateException(d + hint);
        }
        return data;
    }
}
