package org.traccar.demo;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.api.security.AccessPermissions;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.UnitsConverter;
import org.traccar.model.AccessProfile;
import org.traccar.model.AccessProfilePermission;
import org.traccar.model.DemoConfiguration;
import org.traccar.model.DemoSession;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Geofence;
import org.traccar.model.Group;
import org.traccar.model.Notification;
import org.traccar.model.ObjectOperation;
import org.traccar.model.Permission;
import org.traccar.model.Position;
import org.traccar.model.User;
import org.traccar.model.UserAccessProfile;
import org.traccar.session.ConnectionManager;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Singleton
public class DemoService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DemoService.class);

    private static final String PROFILE_NAME = "Demonstração";
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Set<String> ACTIVE_STATUSES = Set.of(
            DemoSession.STATUS_CREATING, DemoSession.STATUS_ACTIVE, DemoSession.STATUS_RUNNING,
            DemoSession.STATUS_PAUSED, DemoSession.STATUS_STOPPED, DemoSession.STATUS_FAILED,
            DemoSession.STATUS_CLEANING, DemoSession.STATUS_CLEANUP_FAILED);
    private static final Set<String> REQUIRED_PERMISSIONS = Set.of(
            AccessPermissions.MAP_VIEW, AccessPermissions.MAP_DEVICES, AccessPermissions.MAP_FOLLOW,
            AccessPermissions.MAP_HISTORY, AccessPermissions.DEVICE_VIEW, AccessPermissions.REPORT_VIEW,
            AccessPermissions.REPORT_GENERATE, AccessPermissions.GEOFENCE_VIEW,
            AccessPermissions.NOTIFICATION_VIEW, AccessPermissions.ACCOUNT_VIEW,
            AccessPermissions.ACCOUNT_PREFERENCES_EDIT);
    private static final List<String> NOTIFICATION_TYPES = List.of(
            Event.TYPE_DEVICE_ONLINE, Event.TYPE_DEVICE_OFFLINE, Event.TYPE_IGNITION_ON,
            Event.TYPE_IGNITION_OFF, Event.TYPE_GEOFENCE_ENTER, Event.TYPE_GEOFENCE_EXIT,
            Event.TYPE_DEVICE_OVERSPEED);

    public record CreateRequest(String name, String email, String company) {
    }

    public record Provisioned(DemoSession session, User user) {
    }

    private interface CleanupStep {
        void run() throws Exception;
    }

    private final Config config;
    private final Storage storage;
    private final CacheManager cacheManager;
    private final ConnectionManager connectionManager;
    private final DemoSimulatorService simulator;
    private final Map<String, Deque<Long>> attempts = new ConcurrentHashMap<>();
    private volatile String generatedHashSecret;

    @Inject
    public DemoService(
            Config config, Storage storage, CacheManager cacheManager,
            ConnectionManager connectionManager, DemoSimulatorService simulator) {
        this.config = config;
        this.storage = storage;
        this.cacheManager = cacheManager;
        this.connectionManager = connectionManager;
        this.simulator = simulator;
    }

    public boolean isEnabled() {
        return config.getBoolean(Keys.DEMO_ENABLED);
    }

    public int getDurationMinutes() {
        return positive(config.getInteger(Keys.DEMO_SESSION_DURATION_MINUTES), 60);
    }

    public String getDefaultScenario() {
        String scenario = config.getString(Keys.DEMO_DEFAULT_SCENARIO);
        try {
            return DemoScenario.fromId(scenario).getId();
        } catch (IllegalArgumentException error) {
            return DemoScenario.URBAN.getId();
        }
    }

    public synchronized Provisioned create(CreateRequest request, String clientIp) throws Exception {
        requireEnabled();
        String name = normalizeRequired(request != null ? request.name() : null, "Nome", 128);
        String email = normalizeEmail(request != null ? request.email() : null);
        if (request != null && request.company() != null && request.company().trim().length() > 128) {
            throw new DemoException(400, "Empresa deve possuir no máximo 128 caracteres");
        }
        String ipHash = hash(normalizeRequired(clientIp, "Endereço de origem", 128));
        String emailHash = hash(email);
        checkAttemptRate(ipHash);
        checkPersistentLimits(ipHash, emailHash);
        AccessProfile profile = requireProfile();

        Date now = new Date();
        Date expiresAt = new Date(now.getTime() + getDurationMinutes() * 60_000L);
        DemoSession session = new DemoSession();
        session.setEmailHash(emailHash);
        session.setIpHash(ipHash);
        session.setStatus(DemoSession.STATUS_CREATING);
        session.setCurrentStep("Preparando ambiente isolado");
        session.setCreatedAt(now);
        session.setLastActivityAt(now);
        session.setExpiresAt(expiresAt);
        session.setId(storage.addObject(session, new Request(new Columns.Exclude("id"))));

        try {
            String suffix = UUID.randomUUID().toString();
            User user = createUser(session, name, suffix);
            session.setUserId(user.getId());
            persistResources(session);

            UserAccessProfile assignment = new UserAccessProfile();
            assignment.setUserId(user.getId());
            assignment.setProfileId(profile.getId());
            storage.addObject(assignment, new Request(new Columns.All()));

            Group group = createGroup(session, suffix);
            session.setGroupId(group.getId());
            persistResources(session);

            Device device = createDevice(session, group.getId(), suffix);
            session.setDeviceId(device.getId());
            persistResources(session);

            Geofence geofence = createGeofence(session, suffix);
            session.setGeofenceId(geofence.getId());
            persistResources(session);

            List<Long> notificationIds = createNotifications(session, suffix);
            session.set("notificationIds", notificationIds.stream()
                    .map(String::valueOf).reduce((left, right) -> left + "," + right).orElse(""));
            persistResources(session);

            List<Permission> links = new ArrayList<>();
            links.add(new Permission(User.class, user.getId(), Group.class, group.getId()));
            links.add(new Permission(User.class, user.getId(), Device.class, device.getId()));
            links.add(new Permission(User.class, user.getId(), Geofence.class, geofence.getId()));
            links.add(new Permission(Device.class, device.getId(), Geofence.class, geofence.getId()));
            for (long notificationId : notificationIds) {
                links.add(new Permission(User.class, user.getId(), Notification.class, notificationId));
                links.add(new Permission(Device.class, device.getId(), Notification.class, notificationId));
            }
            for (Permission link : links) {
                storage.addPermission(link);
            }
            for (Permission link : links) {
                invalidatePermission(link, true);
            }

            session.setStatus(DemoSession.STATUS_ACTIVE);
            session.setCurrentStep("Escolha uma demonstração");
            session.setProgress(0);
            session.setLastActivityAt(new Date());
            storage.updateObject(session, new Request(
                    new Columns.Include("status", "currentStep", "progress", "lastActivityAt", "attributes"),
                    new Condition.Equals("id", session.getId())));
            LOGGER.info("Demo metric=demo_created demoSessionId={}", session.getId());
            return new Provisioned(session, user);
        } catch (Exception error) {
            LOGGER.warn("Demo provisioning failed demoSessionId={}", session.getId(), error);
            markFailed(session.getId(), "Falha ao preparar a demonstração");
            cleanup(session.getId(), DemoSession.STATUS_FAILED);
            throw error;
        }
    }

    private User createUser(DemoSession session, String name, String suffix) throws StorageException {
        User user = new User();
        user.setName(name);
        user.setEmail("demo-" + suffix + "@demo.invalid");
        user.setAdministrator(false);
        user.setReadonly(false);
        user.setDeviceReadonly(true);
        user.setLimitCommands(true);
        user.setDisableReports(false);
        user.setFixedEmail(true);
        user.setTemporary(true);
        user.setDeviceLimit(0);
        user.setUserLimit(0);
        user.setExpirationTime(session.getExpiresAt());
        user.set("demo", true);
        user.set("demoSessionId", session.getId());
        user.set("demoExpiresAt", session.getExpiresAt().toInstant().toString());
        user.set("mapFollow", true);
        user.set("vehicleFollowMode", "heading");
        user.set("mapOnSelect", true);
        user.set("mapLiveRoutes", "all");
        user.set("web.liveRouteLength", 250);
        user.setId(storage.addObject(user, new Request(new Columns.Exclude("id"))));
        return user;
    }

    private Group createGroup(DemoSession session, String suffix) throws StorageException {
        Group group = new Group();
        group.setName("Grupo Demonstração " + suffix.substring(0, 8));
        group.set("demo", true);
        group.set("demoSessionId", session.getId());
        group.setId(storage.addObject(group, new Request(new Columns.Exclude("id"))));
        return group;
    }

    private Device createDevice(DemoSession session, long groupId, String suffix) throws StorageException {
        Device device = new Device();
        device.setName("Veículo Virtual Kersting");
        device.setUniqueId("DEMO-" + suffix.toUpperCase(Locale.ROOT));
        device.setGroupId(groupId);
        device.setModel("Simulador OsmAnd");
        device.setCategory("car");
        device.setExpirationTime(session.getExpiresAt());
        device.set("demo", true);
        device.set("demoSessionId", session.getId());
        device.setId(storage.addObject(device, new Request(new Columns.Exclude("id"))));
        return device;
    }

    private Geofence createGeofence(DemoSession session, String suffix) throws StorageException {
        Geofence geofence = new Geofence();
        geofence.setName("Geocerca Demonstração " + suffix.substring(0, 8));
        geofence.setDescription("Geocerca temporária exclusiva da demonstração");
        geofence.setArea("CIRCLE (-28.2912 -53.4996, 170)");
        geofence.set(Keys.EVENT_OVERSPEED_LIMIT.getKey(), UnitsConverter.knotsFromKph(60));
        geofence.set("demo", true);
        geofence.set("demoSessionId", session.getId());
        geofence.setId(storage.addObject(geofence, new Request(new Columns.Exclude("id"))));
        return geofence;
    }

    private List<Long> createNotifications(DemoSession session, String suffix) throws StorageException {
        List<Long> result = new ArrayList<>();
        for (String type : NOTIFICATION_TYPES) {
            Notification notification = new Notification();
            notification.setDescription("Demonstração " + type + " " + suffix.substring(0, 8));
            notification.setType(type);
            notification.setNotificators("web");
            notification.setAlways(false);
            notification.set("demo", true);
            notification.set("demoSessionId", session.getId());
            notification.setId(storage.addObject(notification, new Request(new Columns.Exclude("id"))));
            result.add(notification.getId());
        }
        return result;
    }

    public DemoSession getForUser(long userId) throws StorageException {
        DemoSession session = storage.getObject(DemoSession.class, new Request(
                new Columns.All(), new Condition.Equals("userId", userId)));
        if (session == null || session.getCleanupComplete()) {
            throw new DemoException(403, "Sessão de demonstração não encontrada");
        }
        if (session.getExpiresAt().before(new Date())) {
            throw new DemoException(403, "Sessão de demonstração expirada");
        }
        return session;
    }

    public synchronized DemoSession startScenario(long userId, String scenarioId) throws Exception {
        DemoSession session = getForUser(userId);
        DemoScenario scenario;
        try {
            scenario = DemoScenario.fromId(scenarioId);
        } catch (IllegalArgumentException error) {
            throw new DemoException(400, "Cenário de demonstração inválido");
        }
        if (!List.of(DemoSession.STATUS_ACTIVE, DemoSession.STATUS_STOPPED,
                DemoSession.STATUS_COMPLETED, DemoSession.STATUS_FAILED).contains(session.getStatus())) {
            throw new DemoException(409, "A demonstração atual deve ser interrompida antes de iniciar outra");
        }
        session.setScenarioId(scenario.getId());
        session.setRouteId(scenario.getRouteId());
        session.setStatus(DemoSession.STATUS_RUNNING);
        session.setStartedAt(new Date());
        session.setFinishedAt(null);
        session.setCurrentStep("Iniciando cenário");
        session.setProgress(0);
        session.setLastActivityAt(new Date());
        storage.updateObject(session, new Request(
                new Columns.Include(
                        "scenarioId", "routeId", "status", "startedAt", "finishedAt",
                        "currentStep", "progress", "lastActivityAt"),
                new Condition.Equals("id", session.getId())));
        simulator.start(session, scenario);
        LOGGER.info("Demo metric=scenario_selected demoSessionId={} scenario={}", session.getId(), scenario.getId());
        return session;
    }

    public synchronized DemoSession control(long userId, String action) throws Exception {
        DemoSession session = getForUser(userId);
        switch (action) {
            case "pause" -> {
                if (!DemoSession.STATUS_RUNNING.equals(session.getStatus())) {
                    throw new DemoException(409, "A demonstração não está em execução");
                }
                simulator.pause(session.getId());
                session.setStatus(DemoSession.STATUS_PAUSED);
                session.setCurrentStep("Demonstração pausada");
            }
            case "resume" -> {
                if (!DemoSession.STATUS_PAUSED.equals(session.getStatus())) {
                    throw new DemoException(409, "A demonstração não está pausada");
                }
                simulator.resume(session.getId());
                session.setStatus(DemoSession.STATUS_RUNNING);
                session.setCurrentStep("Demonstração retomada");
            }
            case "stop" -> {
                if (!List.of(DemoSession.STATUS_RUNNING, DemoSession.STATUS_PAUSED).contains(session.getStatus())) {
                    throw new DemoException(409, "A demonstração não está em execução");
                }
                simulator.stop(session.getId());
                session.setStatus(DemoSession.STATUS_STOPPED);
                session.setCurrentStep("Demonstração interrompida");
                session.setFinishedAt(new Date());
                LOGGER.info("Demo action=stop demoSessionId={}", session.getId());
            }
            default -> throw new DemoException(400, "Controle de demonstração inválido");
        }
        session.setLastActivityAt(new Date());
        storage.updateObject(session, new Request(
                new Columns.Include("status", "currentStep", "finishedAt", "lastActivityAt"),
                new Condition.Equals("id", session.getId())));
        return session;
    }

    public void recoverInterruptedSessions() throws Exception {
        for (DemoSession session : allSessions()) {
            if (List.of(DemoSession.STATUS_RUNNING, DemoSession.STATUS_PAUSED,
                    DemoSession.STATUS_CREATING, DemoSession.STATUS_CLEANING).contains(session.getStatus())) {
                LOGGER.info("Recovering interrupted demo demoSessionId={} status={}",
                        session.getId(), session.getStatus());
                cleanup(session.getId(), DemoSession.STATUS_FAILED);
            }
        }
    }

    public void cleanupDueSessions() throws Exception {
        Date now = new Date();
        long abandonedBefore = now.getTime() - 5 * 60_000L;
        for (DemoSession session : allSessions()) {
            boolean expired = session.getExpiresAt() != null && !session.getExpiresAt().after(now);
            boolean abandoned = List.of(
                    DemoSession.STATUS_STOPPED, DemoSession.STATUS_FAILED,
                    DemoSession.STATUS_CLEANUP_FAILED).contains(session.getStatus())
                    && session.getLastActivityAt() != null
                    && session.getLastActivityAt().getTime() < abandonedBefore;
            boolean stuck = DemoSession.STATUS_RUNNING.equals(session.getStatus())
                    && !simulator.isRunning(session.getId());
            if (!session.getCleanupComplete() && (expired || abandoned || stuck)) {
                cleanup(session.getId(), expired ? DemoSession.STATUS_EXPIRED : DemoSession.STATUS_FAILED);
            }
        }
        purgeAuditRows();
    }

    public synchronized void cleanup(long sessionId, String terminalStatus) throws Exception {
        DemoSession session = storage.getObject(DemoSession.class, new Request(
                new Columns.All(), new Condition.Equals("id", sessionId)));
        if (session == null || session.getCleanupComplete()) {
            return;
        }
        simulator.stop(sessionId);
        session.setStatus(DemoSession.STATUS_CLEANING);
        session.setLastActivityAt(new Date());
        storage.updateObject(session, new Request(
                new Columns.Include("status", "lastActivityAt"), new Condition.Equals("id", sessionId)));

        List<Exception> failures = new ArrayList<>();
        List<Long> notificationIds = parseIds(session.getString("notificationIds"));
        for (long notificationId : notificationIds) {
            cleanupStep(failures, () -> unlink(User.class, session.getUserId(), Notification.class, notificationId));
            cleanupStep(failures, () -> unlink(
                    Device.class, session.getDeviceId(), Notification.class, notificationId));
        }
        cleanupStep(failures, () -> unlink(
                Device.class, session.getDeviceId(), Geofence.class, session.getGeofenceId()));
        cleanupStep(failures, () -> unlink(User.class, session.getUserId(), Geofence.class, session.getGeofenceId()));
        cleanupStep(failures, () -> unlink(User.class, session.getUserId(), Device.class, session.getDeviceId()));
        cleanupStep(failures, () -> unlink(User.class, session.getUserId(), Group.class, session.getGroupId()));

        cleanupStep(failures, () -> storage.removeObject(
                Event.class, new Request(new Condition.Equals("deviceId", session.getDeviceId()))));
        cleanupStep(failures, () -> storage.removeObject(
                Position.class, new Request(new Condition.Equals("deviceId", session.getDeviceId()))));
        for (long notificationId : notificationIds) {
            cleanupStep(failures, () -> removeObject(Notification.class, notificationId));
        }
        cleanupStep(failures, () -> removeObject(Geofence.class, session.getGeofenceId()));
        cleanupStep(failures, () -> removeObject(Device.class, session.getDeviceId()));
        cleanupStep(failures, () -> removeObject(Group.class, session.getGroupId()));
        cleanupStep(failures, () -> storage.removeObject(
                UserAccessProfile.class, new Request(new Condition.Equals("userId", session.getUserId()))));
        cleanupStep(failures, () -> removeObject(User.class, session.getUserId()));

        session.setFinishedAt(new Date());
        session.setLastActivityAt(new Date());
        session.set("terminalStatus", terminalStatus);
        if (failures.isEmpty()) {
            session.setStatus(DemoSession.STATUS_CLEANED);
            session.setCurrentStep("Recursos temporários removidos");
            session.setCleanupComplete(true);
            LOGGER.info("Demo metric=demo_expired demoSessionId={} terminalStatus={}", sessionId, terminalStatus);
        } else {
            session.setStatus(DemoSession.STATUS_CLEANUP_FAILED);
            session.setCurrentStep("Limpeza pendente de nova tentativa");
            session.setCleanupComplete(false);
            LOGGER.warn("Demo cleanup failed demoSessionId={} failureCount={}", sessionId, failures.size());
        }
        storage.updateObject(session, new Request(
                new Columns.Include(
                        "status", "currentStep", "cleanupComplete", "finishedAt", "lastActivityAt", "attributes"),
                new Condition.Equals("id", sessionId)));
        if (!failures.isEmpty()) {
            throw failures.getFirst();
        }
    }

    private void purgeAuditRows() throws StorageException {
        int retentionDays = positive(config.getInteger(Keys.DEMO_AUDIT_RETENTION_DAYS), 7);
        Date cutoff = Date.from(Instant.now().minusSeconds(retentionDays * 86_400L));
        storage.removeObject(DemoSession.class, new Request(new Condition.And(
                new Condition.Equals("cleanupComplete", true),
                new Condition.Compare("finishedAt", "<", cutoff))));
    }

    private <T extends org.traccar.model.BaseModel> void removeObject(Class<T> type, long id) throws Exception {
        if (id <= 0) {
            return;
        }
        storage.removeObject(type, new Request(new Condition.Equals("id", id)));
        cacheManager.invalidateObject(true, type, id, ObjectOperation.DELETE);
    }

    private <T1 extends org.traccar.model.BaseModel, T2 extends org.traccar.model.BaseModel> void unlink(
            Class<T1> ownerClass, long ownerId, Class<T2> propertyClass, long propertyId) throws Exception {
        if (ownerId <= 0 || propertyId <= 0) {
            return;
        }
        Permission permission = new Permission(ownerClass, ownerId, propertyClass, propertyId);
        storage.removePermission(permission);
        invalidatePermission(permission, false);
    }

    private void invalidatePermission(Permission permission, boolean link) throws Exception {
        cacheManager.invalidatePermission(
                true, permission.getOwnerClass(), permission.getOwnerId(),
                permission.getPropertyClass(), permission.getPropertyId(), link);
        connectionManager.invalidatePermission(
                true, permission.getOwnerClass(), permission.getOwnerId(),
                permission.getPropertyClass(), permission.getPropertyId(), link);
    }

    private void cleanupStep(List<Exception> failures, CleanupStep step) {
        try {
            step.run();
        } catch (Exception error) {
            failures.add(error);
        }
    }

    private void persistResources(DemoSession session) throws StorageException {
        storage.updateObject(session, new Request(
                new Columns.Include("userId", "groupId", "deviceId", "geofenceId", "attributes"),
                new Condition.Equals("id", session.getId())));
    }

    private void markFailed(long sessionId, String message) {
        try {
            DemoSession update = new DemoSession();
            update.setId(sessionId);
            update.setStatus(DemoSession.STATUS_FAILED);
            update.setCurrentStep(message);
            update.setFinishedAt(new Date());
            update.setLastActivityAt(new Date());
            storage.updateObject(update, new Request(
                    new Columns.Include("status", "currentStep", "finishedAt", "lastActivityAt"),
                    new Condition.Equals("id", sessionId)));
        } catch (StorageException error) {
            LOGGER.warn("Unable to persist demo failure demoSessionId={}", sessionId, error);
        }
    }

    private AccessProfile requireProfile() throws StorageException {
        AccessProfile profile = storage.getObject(AccessProfile.class, new Request(
                new Columns.All(), new Condition.Equals("name", PROFILE_NAME)));
        if (profile == null || profile.getDisabled()) {
            throw new DemoException(503, "Perfil Demonstração indisponível");
        }
        Set<String> permissions = storage.getObjects(AccessProfilePermission.class, new Request(
                        new Columns.All(), new Condition.Equals("profileId", profile.getId()))).stream()
                .map(AccessProfilePermission::getPermissionKey).collect(java.util.stream.Collectors.toSet());
        if (!permissions.containsAll(REQUIRED_PERMISSIONS)) {
            throw new DemoException(503, "Perfil Demonstração incompleto");
        }
        return profile;
    }

    private void checkPersistentLimits(String ipHash, String emailHash) throws StorageException {
        Date since = new Date(System.currentTimeMillis() - 86_400_000L);
        int ipCount = storage.getObjects(DemoSession.class, new Request(
                new Columns.Include("id"), new Condition.And(
                        new Condition.Equals("ipHash", ipHash),
                        new Condition.Compare("createdAt", ">=", since)))).size();
        int emailCount = storage.getObjects(DemoSession.class, new Request(
                new Columns.Include("id"), new Condition.And(
                        new Condition.Equals("emailHash", emailHash),
                        new Condition.Compare("createdAt", ">=", since)))).size();
        long activeCount = allSessions().stream()
                .filter(session -> ACTIVE_STATUSES.contains(session.getStatus()))
                .filter(session -> !session.getCleanupComplete())
                .filter(session -> session.getExpiresAt() != null && session.getExpiresAt().after(new Date()))
                .count();
        if (ipCount >= positive(config.getInteger(Keys.DEMO_MAX_SESSIONS_PER_IP), 3)) {
            throw new DemoException(429, "Limite diário de demonstrações atingido para esta conexão");
        }
        if (emailCount >= positive(config.getInteger(Keys.DEMO_MAX_SESSIONS_PER_EMAIL), 2)) {
            throw new DemoException(429, "Limite diário de demonstrações atingido para este e-mail");
        }
        if (activeCount >= positive(config.getInteger(Keys.DEMO_MAX_CONCURRENT_SESSIONS), 20)) {
            throw new DemoException(503, "Todas as demonstrações estão em uso; tente novamente em instantes");
        }
    }

    private void checkAttemptRate(String ipHash) {
        long now = System.currentTimeMillis();
        long window = positive(config.getInteger(Keys.DEMO_RATE_LIMIT_WINDOW_SECONDS), 60) * 1000L;
        int maximum = positive(config.getInteger(Keys.DEMO_RATE_LIMIT_MAX_ATTEMPTS), 8);
        Deque<Long> timestamps = attempts.computeIfAbsent(ipHash, ignored -> new ArrayDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst() < now - window) {
                timestamps.removeFirst();
            }
            if (timestamps.size() >= maximum) {
                throw new DemoException(429, "Muitas tentativas; aguarde antes de tentar novamente");
            }
            timestamps.addLast(now);
        }
    }

    private String normalizeEmail(String value) {
        String email = normalizeRequired(value, "E-mail", 254).toLowerCase(Locale.ROOT);
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new DemoException(400, "Informe um e-mail válido");
        }
        return email;
    }

    private String normalizeRequired(String value, String field, int maximumLength) {
        String normalized = value != null ? value.trim() : "";
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new DemoException(400, field + " inválido");
        }
        return normalized;
    }

    private String hash(String value) throws StorageException {
        String secret = hashSecret();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
    }

    private synchronized String hashSecret() throws StorageException {
        String configuredSecret = config.getString(Keys.DEMO_HASH_SECRET);
        if (configuredSecret != null) {
            if (configuredSecret.length() < 32) {
                throw new DemoException(503, "Proteção de privacidade da demonstração configurada incorretamente");
            }
            return configuredSecret;
        }
        if (generatedHashSecret != null) {
            return generatedHashSecret;
        }
        List<DemoConfiguration> configurations = storage.getObjects(
                DemoConfiguration.class, new Request(new Columns.All()));
        if (!configurations.isEmpty()) {
            generatedHashSecret = configurations.get(0).getHashSecret();
        } else {
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            DemoConfiguration configuration = new DemoConfiguration();
            configuration.setHashSecret(Base64.getEncoder().encodeToString(key));
            configuration.setCreatedAt(new Date());
            storage.addObject(configuration, new Request(new Columns.Exclude("id")));
            generatedHashSecret = configuration.getHashSecret();
            LOGGER.info("Generated persistent demo pseudonymization key");
        }
        if (generatedHashSecret == null || generatedHashSecret.length() < 32) {
            throw new DemoException(503, "Proteção de privacidade da demonstração indisponível");
        }
        return generatedHashSecret;
    }

    private List<DemoSession> allSessions() throws StorageException {
        return storage.getObjects(DemoSession.class, new Request(new Columns.All()));
    }

    private List<Long> parseIds(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split(","))
                .map(Long::parseLong).filter(id -> id > 0).toList();
    }

    private void requireEnabled() {
        if (!isEnabled()) {
            throw new DemoException(503, "Demonstração temporariamente indisponível");
        }
    }

    private int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }
}
