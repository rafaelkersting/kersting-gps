package org.traccar.demo;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.LifecycleObject;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.database.NotificationManager;
import org.traccar.model.DemoSession;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.session.ConnectionManager;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Singleton
public class DemoSimulatorService implements LifecycleObject {

    private static final Logger LOGGER = LoggerFactory.getLogger(DemoSimulatorService.class);

    private static final int INTERPOLATION_STEPS = 4;

    private final Config config;
    private final Storage storage;
    private final DemoRouteCatalog routeCatalog;
    private final ConnectionManager connectionManager;
    private final NotificationManager notificationManager;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final Map<Long, RunContext> runs = new ConcurrentHashMap<>();

    private ScheduledExecutorService executor;
    private URI endpoint;

    private static final class RunContext {
        private final long sessionId;
        private final long deviceId;
        private final String uniqueId;
        private final DemoScenario scenario;
        private final List<DemoRouteCatalog.Point> points;
        private volatile int index;
        private volatile boolean paused;
        private volatile boolean offlinePending;
        private volatile ScheduledFuture<?> future;

        private RunContext(
                long sessionId, long deviceId, String uniqueId, DemoScenario scenario,
                List<DemoRouteCatalog.Point> points) {
            this.sessionId = sessionId;
            this.deviceId = deviceId;
            this.uniqueId = uniqueId;
            this.scenario = scenario;
            this.points = points;
        }
    }

    @Inject
    public DemoSimulatorService(
            Config config, Storage storage, DemoRouteCatalog routeCatalog,
            ConnectionManager connectionManager, NotificationManager notificationManager) {
        this.config = config;
        this.storage = storage;
        this.routeCatalog = routeCatalog;
        this.connectionManager = connectionManager;
        this.notificationManager = notificationManager;
    }

    @Override
    public synchronized void start() throws Exception {
        endpoint = validateEndpoint(config.getString(Keys.DEMO_SIMULATOR_URL));
        executor = Executors.newScheduledThreadPool(4);
    }

    @Override
    public synchronized void stop() {
        for (RunContext context : runs.values()) {
            if (context.future != null) {
                context.future.cancel(false);
            }
        }
        runs.clear();
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private URI validateEndpoint(String value) throws Exception {
        URI uri = URI.create(value);
        if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null) {
            throw new IllegalArgumentException("demo.simulatorUrl must be an internal HTTP endpoint");
        }
        InetAddress address = InetAddress.getByName(uri.getHost());
        if (!address.isLoopbackAddress() && !address.isSiteLocalAddress()) {
            throw new IllegalArgumentException("demo.simulatorUrl must resolve to loopback or a private network");
        }
        return uri;
    }

    public synchronized void start(DemoSession session, DemoScenario scenario) throws StorageException {
        if (executor == null) {
            throw new IllegalStateException("Demo simulator is not running");
        }
        Device device = storage.getObject(Device.class, new Request(
                new Columns.All(), new Condition.Equals("id", session.getDeviceId())));
        if (device == null || device.getLong("demoSessionId") != session.getId()
                || !device.getUniqueId().startsWith("DEMO-")) {
            throw new DemoException(403, "Dispositivo da demonstração inválido");
        }
        stopInternal(session.getId(), false);
        var route = routeCatalog.get(scenario.getRouteId());
        var context = new RunContext(
                session.getId(), device.getId(), device.getUniqueId(), scenario, expand(route.points()));
        runs.put(session.getId(), context);
        schedule(context, 0);
        LOGGER.info("Demo metric=demo_started demoSessionId={} scenario={}", session.getId(), scenario.getId());
    }

    public synchronized void pause(long sessionId) {
        RunContext context = requireRun(sessionId);
        context.paused = true;
    }

    public synchronized void resume(long sessionId) {
        RunContext context = requireRun(sessionId);
        context.paused = false;
    }

    public synchronized void stop(long sessionId) {
        stopInternal(sessionId, true);
    }

    public boolean isRunning(long sessionId) {
        return runs.containsKey(sessionId);
    }

    private RunContext requireRun(long sessionId) {
        RunContext context = runs.get(sessionId);
        if (context == null) {
            throw new DemoException(409, "Nenhuma demonstração está em execução");
        }
        return context;
    }

    private synchronized void stopInternal(long sessionId, boolean offline) {
        RunContext context = runs.remove(sessionId);
        if (context != null) {
            if (context.future != null) {
                context.future.cancel(false);
            }
            if (offline) {
                updateStatus(context.deviceId, Device.STATUS_OFFLINE);
            }
        }
    }

    private void schedule(RunContext context, long delayMillis) {
        context.future = executor.schedule(() -> tick(context), Math.max(0, delayMillis), TimeUnit.MILLISECONDS);
    }

    private void tick(RunContext context) {
        try {
            if (runs.get(context.sessionId) != context) {
                return;
            }
            DemoSession session = storage.getObject(DemoSession.class, new Request(
                    new Columns.All(), new Condition.Equals("id", context.sessionId)));
            if (session == null || session.getDeviceId() != context.deviceId
                    || session.getExpiresAt().before(new Date())
                    || !List.of(DemoSession.STATUS_RUNNING, DemoSession.STATUS_PAUSED).contains(session.getStatus())) {
                stopInternal(context.sessionId, false);
                return;
            }
            if (context.paused || DemoSession.STATUS_PAUSED.equals(session.getStatus())) {
                schedule(context, 1000);
                return;
            }
            if (context.index >= context.points.size()) {
                complete(context);
                return;
            }

            DemoRouteCatalog.Point point = context.points.get(context.index);
            boolean offlineAction = "offline".equals(point.action())
                    || context.scenario == DemoScenario.OFFLINE && context.index == context.points.size() / 2;
            if (offlineAction && !context.offlinePending) {
                context.offlinePending = true;
                updateStatus(context.deviceId, Device.STATUS_OFFLINE);
                updateProgress(context, "Dispositivo offline");
                schedule(context, config.getInteger(Keys.DEMO_OFFLINE_DURATION_SECONDS) * 1000L);
                return;
            }
            boolean resumedFromOffline = context.offlinePending;
            context.offlinePending = false;
            sendPosition(context, point);
            if (context.index == 0 || resumedFromOffline) {
                emitStatusEvent(context.deviceId, Event.TYPE_DEVICE_ONLINE);
            }
            context.index++;
            updateProgress(context, point.stage() != null ? point.stage() : "Veículo em movimento");
            schedule(context, interval(context));
        } catch (Exception error) {
            fail(context, error);
        }
    }

    private long interval(RunContext context) {
        if (context.scenario == DemoScenario.COMPLETE) {
            return config.getInteger(Keys.DEMO_COMPLETE_DURATION_SECONDS) * 1000L / context.points.size();
        }
        return config.getInteger(Keys.DEMO_SIMULATOR_INTERVAL_MILLIS);
    }

    private void sendPosition(RunContext context, DemoRouteCatalog.Point point) throws Exception {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("id", context.uniqueId);
        parameters.put("lat", decimal(point.latitude()));
        parameters.put("lon", decimal(point.longitude()));
        parameters.put("timestamp", Long.toString(System.currentTimeMillis() / 1000));
        parameters.put("speed", decimal(point.speedKph() / 1.852));
        parameters.put("bearing", decimal(point.course()));
        parameters.put("altitude", decimal(point.altitude()));
        parameters.put("accuracy", "5");
        parameters.put("ignition", Boolean.toString(point.ignition()));
        parameters.put("motion", Boolean.toString(point.motion()));
        parameters.put("valid", "true");
        String query = parameters.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right).orElse("");
        URI uri = URI.create(endpoint.toString() + (endpoint.toString().contains("?") ? "&" : "?") + query);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("OsmAnd returned HTTP " + response.statusCode());
        }
    }

    private String decimal(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private void updateStatus(long deviceId, String status) {
        connectionManager.updateDevice(deviceId, status, null);
        emitStatusEvent(deviceId, Device.STATUS_OFFLINE.equals(status)
                ? Event.TYPE_DEVICE_OFFLINE : Event.TYPE_DEVICE_ONLINE);
    }

    private void emitStatusEvent(long deviceId, String eventType) {
        if (!config.getBoolean(Keys.EVENT_STATUS_ENABLE)) {
            notificationManager.updateEvents(Collections.singletonMap(new Event(eventType, deviceId), null));
        }
    }

    private void updateProgress(RunContext context, String step) throws StorageException {
        DemoSession update = new DemoSession();
        update.setId(context.sessionId);
        update.setCurrentStep(step);
        update.setProgress(Math.min(100, context.index * 100 / context.points.size()));
        update.setLastActivityAt(new Date());
        storage.updateObject(update, new Request(
                new Columns.Include("currentStep", "progress", "lastActivityAt"),
                new Condition.Equals("id", context.sessionId)));
    }

    private void complete(RunContext context) throws StorageException {
        stopInternal(context.sessionId, false);
        DemoSession update = new DemoSession();
        update.setId(context.sessionId);
        update.setStatus(DemoSession.STATUS_COMPLETED);
        update.setCurrentStep("Demonstração concluída");
        update.setProgress(100);
        update.setFinishedAt(new Date());
        update.setLastActivityAt(new Date());
        storage.updateObject(update, new Request(
                new Columns.Include("status", "currentStep", "progress", "finishedAt", "lastActivityAt"),
                new Condition.Equals("id", context.sessionId)));
        LOGGER.info("Demo metric=demo_completed demoSessionId={}", context.sessionId);
    }

    private void fail(RunContext context, Exception error) {
        stopInternal(context.sessionId, false);
        try {
            DemoSession update = new DemoSession();
            update.setId(context.sessionId);
            update.setStatus(DemoSession.STATUS_FAILED);
            update.setCurrentStep("Falha na transmissão da demonstração");
            update.setFinishedAt(new Date());
            update.setLastActivityAt(new Date());
            storage.updateObject(update, new Request(
                    new Columns.Include("status", "currentStep", "finishedAt", "lastActivityAt"),
                    new Condition.Equals("id", context.sessionId)));
        } catch (StorageException storageError) {
            LOGGER.warn("Failed to persist demo failure demoSessionId={}", context.sessionId, storageError);
        }
        LOGGER.warn("Demo metric=demo_failed demoSessionId={}", context.sessionId, error);
    }

    private List<DemoRouteCatalog.Point> expand(List<DemoRouteCatalog.Point> controlPoints) {
        List<DemoRouteCatalog.Point> result = new ArrayList<>();
        result.add(controlPoints.getFirst());
        for (int index = 1; index < controlPoints.size(); index++) {
            DemoRouteCatalog.Point from = controlPoints.get(index - 1);
            DemoRouteCatalog.Point to = controlPoints.get(index);
            for (int step = 1; step <= INTERPOLATION_STEPS; step++) {
                double ratio = step / (double) INTERPOLATION_STEPS;
                result.add(new DemoRouteCatalog.Point(
                        interpolate(from.latitude(), to.latitude(), ratio),
                        interpolate(from.longitude(), to.longitude(), ratio),
                        interpolate(from.altitude(), to.altitude(), ratio),
                        interpolateCourse(from.course(), to.course(), ratio),
                        interpolate(from.speedKph(), to.speedKph(), ratio),
                        ratio >= 0.5 ? to.ignition() : from.ignition(),
                        ratio >= 0.5 ? to.motion() : from.motion(),
                        step == INTERPOLATION_STEPS ? to.stage() : null,
                        step == INTERPOLATION_STEPS ? to.action() : null));
            }
        }
        return result;
    }

    private double interpolate(double from, double to, double ratio) {
        return from + (to - from) * ratio;
    }

    private double interpolateCourse(double from, double to, double ratio) {
        double delta = ((to - from + 540) % 360) - 180;
        return (from + delta * ratio + 360) % 360;
    }
}
