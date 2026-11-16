package oddsviewer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** JSON-утилиты. Вывод совместим с Python json.dumps (разделители ", " и ": "), чтобы state.json не менял вид. */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper();
    public static final JsonNodeFactory F = JsonNodeFactory.instance;

    private Json() {}

    public static ObjectNode obj() { return F.objectNode(); }
    public static ArrayNode arr() { return F.arrayNode(); }

    public static JsonNode parse(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Некорректный JSON: " + e.getOriginalMessage(), e);
        }
    }

    /** Как Python json.dumps(..., ensure_ascii=False[, sort_keys]). */
    public static String py(JsonNode n, boolean sortKeys) {
        StringBuilder sb = new StringBuilder();
        write(sb, n, sortKeys);
        return sb.toString();
    }

    /** Компактный JSON (для data.json сайта). */
    public static String compact(JsonNode n) {
        try {
            return MAPPER.writeValueAsString(n);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void write(StringBuilder sb, JsonNode n, boolean sortKeys) {
        if (n == null || n.isNull()) {
            sb.append("null");
        } else if (n.isObject()) {
            List<String> keys = new ArrayList<>();
            n.fieldNames().forEachRemaining(keys::add);
            if (sortKeys) keys.sort(null);
            sb.append('{');
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(str(keys.get(i))).append(": ");
                write(sb, n.get(keys.get(i)), sortKeys);
            }
            sb.append('}');
        } else if (n.isArray()) {
            sb.append('[');
            Iterator<JsonNode> it = n.elements();
            boolean first = true;
            while (it.hasNext()) {
                if (!first) sb.append(", ");
                first = false;
                write(sb, it.next(), sortKeys);
            }
            sb.append(']');
        } else if (n.isTextual()) {
            sb.append(str(n.asText()));
        } else if (n.isFloatingPointNumber()) {
            sb.append(pyFloat(n.doubleValue()));
        } else {
            sb.append(n.toString()); // целые, boolean
        }
    }

    private static String str(String s) {
        try {
            return MAPPER.writeValueAsString(s);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** repr(float) в Python: 2.0 -> "2.0", 1.25 -> "1.25". */
    static String pyFloat(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e16) return (long) d + ".0";
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    /** Python round(x, n): точное двоичное значение, половина — к чётному. */
    public static double round(double x, int digits) {
        return new BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).doubleValue();
    }

    /** Python f"{x:g}" для линий: 2.5 -> "2.5", -1.0 -> "-1", 0.0 -> "0". */
    public static String g(double x) {
        if (x == 0) return "0";
        if (x == Math.rint(x)) return Long.toString((long) x);
        return BigDecimal.valueOf(x).stripTrailingZeros().toPlainString();
    }

    public static ObjectNode copyOf(Map<String, JsonNode> m) {
        ObjectNode o = obj();
        m.forEach(o::set);
        return o;
    }
}
