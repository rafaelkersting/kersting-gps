package org.traccar.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DemoRouteCatalogTest {

    @Test
    public void testAllApprovedRoutesAreValid() {
        DemoRouteCatalog catalog = new DemoRouteCatalog(new ObjectMapper());

        for (String routeId : new String[] {"urban", "highway", "geofence", "complete"}) {
            DemoRouteCatalog.Route route = catalog.get(routeId);
            assertEquals(routeId, route.id());
            assertTrue(route.points().size() >= 2);
            assertTrue(route.points().stream().allMatch(point -> point.speedKph() >= 0 && point.speedKph() <= 180));
        }
    }

    @Test
    public void testArbitraryRouteIsRejected() {
        DemoRouteCatalog catalog = new DemoRouteCatalog(new ObjectMapper());
        assertThrows(IllegalArgumentException.class, () -> catalog.get("http://attacker.invalid/route"));
    }
}
