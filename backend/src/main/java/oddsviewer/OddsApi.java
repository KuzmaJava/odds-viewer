package oddsviewer;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/** The Odds API v4 — то, что нужно трекеру (интерфейс, чтобы в тестах подменять клиента). */
public interface OddsApi {
    /** Остаток квоты из заголовков последнего ответа: remaining / used / last_cost. */
    Map<String, String> quota();

    /** Остаток квоты ключа, которым опрашивается эта лига. */
    default Map<String, String> quotaFor(String sport) { return quota(); }

    /** Остаток по каждому ключу: "default" или ключ лиги -> квота (для статуса на сайте). */
    default Map<String, Map<String, String>> quotasByKey() { return Map.of(); }

    /** Список лиг. Не тратит квоту. */
    JsonNode sports();

    /** Предстоящие матчи лиги без коэффициентов. Не тратит квоту. */
    JsonNode events(String sport);

    /** Коэффициенты по всем матчам лиги. Стоимость = число вернувшихся рынков. */
    JsonNode odds(String sport, String markets, String bookmakers);

    /** Рынки одного матча (в т.ч. alternate_*, double_chance). Пустой ответ бесплатен. */
    JsonNode eventOdds(String sport, String eventId, String markets, String bookmakers);

    final class ApiException extends RuntimeException {
        public final int status;

        public ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
