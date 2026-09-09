package org.traccar.demo;

import java.util.Arrays;
import java.util.List;

public enum DemoScenario {

    URBAN("urban", "Percurso urbano", "Aceleração, curvas, parada e retomada", "urban"),
    HIGHWAY("highway", "Rodovia", "Velocidade maior e acompanhamento contínuo", "highway"),
    GEOFENCE("geofence", "Cerca virtual", "Entrada, permanência e saída com eventos reais", "geofence"),
    OVERSPEED("overspeed", "Excesso de velocidade", "Limite de 60 km/h e evento real", "geofence"),
    OFFLINE("offline", "Offline / Online", "Interrupção controlada e reconexão", "urban"),
    COMPLETE("complete", "Demonstração completa", "Roteiro guiado com todos os principais eventos", "complete");

    public record Description(String id, String title, String description, String routeId) {
    }

    private final String id;
    private final String title;
    private final String description;
    private final String routeId;

    DemoScenario(String id, String title, String description, String routeId) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.routeId = routeId;
    }

    public String getId() {
        return id;
    }

    public String getRouteId() {
        return routeId;
    }

    public Description describe() {
        return new Description(id, title, description, routeId);
    }

    public static DemoScenario fromId(String id) {
        return Arrays.stream(values())
                .filter(value -> value.id.equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown demo scenario"));
    }

    public static List<Description> descriptions() {
        return Arrays.stream(values()).map(DemoScenario::describe).toList();
    }
}
