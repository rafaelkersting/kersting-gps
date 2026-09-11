package org.traccar.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.traccar.helper.DistanceCalculator;

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
            assertEquals("OpenStreetMap/OSRM", route.source());
            assertEquals("2026-09-10", route.generatedAt());
            assertTrue(route.points().size() >= 2);
            assertTrue(route.points().stream().allMatch(point -> point.speedKph() >= 0 && point.speedKph() <= 180));
            for (int index = 1; index < route.points().size(); index++) {
                var previous = route.points().get(index - 1);
                var current = route.points().get(index);
                assertTrue(DistanceCalculator.distance(
                        previous.latitude(), previous.longitude(), current.latitude(), current.longitude())
                        <= route.maximumSegmentMeters() + 1.0);
            }
        }
    }

    @Test
    public void testArbitraryRouteIsRejected() {
        DemoRouteCatalog catalog = new DemoRouteCatalog(new ObjectMapper());
        assertThrows(IllegalArgumentException.class, () -> catalog.get("http://attacker.invalid/route"));
    }
}
