package org.traccar.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class DemoRouteCatalog {

    public record Point(
            double latitude, double longitude, double altitude, double course, double speedKph,
            boolean ignition, boolean motion, String stage, String action) {
    }

    public record Route(String id, String name, List<Point> points) {
    }

    private static final List<String> ALLOWED_ROUTES = List.of("urban", "highway", "geofence", "complete");

    private final ObjectMapper objectMapper;
    private final Map<String, Route> cache = new ConcurrentHashMap<>();

    @Inject
    public DemoRouteCatalog(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Route get(String routeId) {
        if (!ALLOWED_ROUTES.contains(routeId)) {
            throw new IllegalArgumentException("Unknown demo route");
        }
        return cache.computeIfAbsent(routeId, this::load);
    }

    private Route load(String routeId) {
        String resource = "/demo/routes/" + routeId + ".json";
        try (InputStream input = DemoRouteCatalog.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Demo route not found");
            }
            Route route = objectMapper.readValue(input, Route.class);
            if (!routeId.equals(route.id()) || route.points() == null || route.points().size() < 2) {
                throw new IllegalStateException("Invalid demo route");
            }
            for (Point point : route.points()) {
                if (Math.abs(point.latitude()) > 90 || Math.abs(point.longitude()) > 180
                        || point.speedKph() < 0 || point.speedKph() > 180) {
                    throw new IllegalStateException("Invalid demo route point");
                }
            }
            return route;
        } catch (IOException error) {
            throw new IllegalStateException("Unable to load demo route", error);
        }
    }
}
