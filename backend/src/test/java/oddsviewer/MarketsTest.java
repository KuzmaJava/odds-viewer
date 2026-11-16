package oddsviewer;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MarketsTest {
    @Test
    void flatPricesAllMarkets() {
        Map<String, Price> p = Markets.flatPrices(Fixtures.bm(new double[]{1.75, 3.81, 4.98}, new double[]{1.2, 1.3, 2.15},
                new double[]{2.5, 1.95, 1.93}, new double[]{-0.5, 1.98, 1.93}), "Kosovo", "Israel", false);
        assertEquals(new Price(1.75, null), p.get("home"));
        assertEquals(new Price(2.15, null), p.get("X2"));
        assertEquals(new Price(1.3, null), p.get("12"));
        assertEquals(new Price(1.95, 2.5), p.get("over@2.5"));
        assertEquals(new Price(1.93, 2.5), p.get("under@2.5"));
        assertEquals(new Price(1.98, -0.5), p.get("ah_home@-0.5"));
        assertEquals(new Price(1.93, 0.5), p.get("ah_away@0.5"));
    }

    @Test
    void allLinesWithoutQuarterLines() {
        ObjectNode bm = Json.obj();
        var spreads = bm.putArray("markets").addObject().put("key", "alternate_spreads").putArray("outcomes");
        spreads.add(Fixtures.outcome("Kosovo", 1.33, 0.0)).add(Fixtures.outcome("Israel", 3.5, -0.0))
                .add(Fixtures.outcome("Kosovo", 2.03, -0.75)).add(Fixtures.outcome("Israel", 1.88, 0.75))
                .add(Fixtures.outcome("Kosovo", 2.44, -1.0)).add(Fixtures.outcome("Israel", 1.6, 1.0));
        var totals = ((com.fasterxml.jackson.databind.node.ArrayNode) bm.get("markets")).addObject().put("key", "alternate_totals").putArray("outcomes");
        totals.add(Fixtures.outcome("Over", 1.67, 2.25)).add(Fixtures.outcome("Under", 2.28, 2.25))
                .add(Fixtures.outcome("Over", 2.57, 3.0)).add(Fixtures.outcome("Under", 1.54, 3.0));
        Map<String, Price> p = Markets.flatPrices(bm, "Kosovo", "Israel", false);
        assertEquals(Set.of("ah_home@0", "ah_away@0", "ah_home@-1", "ah_away@1", "over@3", "under@3"), p.keySet());
        // в режиме лиги четвертную основную линию храним
        assertEquals(new Price(1.67, 2.25), Markets.flatPrices(bm, "Kosovo", "Israel", true).get("over@2.25"));
    }

    @Test
    void doubleChanceAndChangePct() {
        var dc = Markets.deriveDoubleChance(1.75, 3.81, 4.98);
        assertEquals(1.199, dc.get("1X"));
        assertEquals(2.159, dc.get("X2")); // то же, что Python derive_double_chance
        assertNull(Markets.deriveDoubleChance(null, 3.0, 4.0).get("1X"));
        assertEquals(-10.0, Movement.changePct(2.0, 1.8));
        assertEquals(10.0, Movement.changePct(2.0, 2.2));
        assertNull(Movement.changePct(null, 2.0));
    }

    @Test
    void pythonCompatibleFormatting() {
        assertEquals("2.0", Json.pyFloat(2.0));
        assertEquals("1.25", Json.pyFloat(1.25));
        assertEquals("-1", Json.g(-1.0));
        assertEquals("0", Json.g(-0.0));
        assertEquals("ah_away@0", Markets.lineCode("ah_away", -0.0));
        assertEquals(2.67, Json.round(2.675, 2)); // как round() в Python: 2.675 хранится как 2.67499…
    }
}
