package org.traccar.demo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.api.security.AccessControlService;
import org.traccar.api.security.AccessPermissions;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.AccessProfile;
import org.traccar.model.AccessProfilePermission;
import org.traccar.model.DemoConfiguration;
import org.traccar.model.DemoSession;
import org.traccar.model.Device;
import org.traccar.model.User;
import org.traccar.session.ConnectionManager;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.MemoryStorage;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class DemoServiceTest {

    private static final List<String> DEMO_PERMISSIONS = List.of(
            AccessPermissions.MAP_VIEW, AccessPermissions.MAP_DEVICES, AccessPermissions.MAP_FOLLOW,
            AccessPermissions.MAP_HISTORY, AccessPermissions.DEVICE_VIEW, AccessPermissions.REPORT_VIEW,
            AccessPermissions.REPORT_GENERATE, AccessPermissions.GEOFENCE_VIEW,
            AccessPermissions.NOTIFICATION_VIEW, AccessPermissions.ACCOUNT_VIEW,
            AccessPermissions.ACCOUNT_PREFERENCES_EDIT, AccessPermissions.PREFERENCE_VIEW,
            AccessPermissions.PREFERENCE_EDIT, AccessPermissions.APPEARANCE_VIEW,
            AccessPermissions.MARKER_3D, AccessPermissions.MARKER_MODEL, AccessPermissions.MARKER_COLOR);

    private Config config;
    private MemoryStorage storage;
    private DemoSimulatorService simulator;
    private DemoService service;

    @BeforeEach
    public void setUp() throws Exception {
        config = new Config();
        config.setString(Keys.DEMO_ENABLED, "true");
        config.setString(Keys.DEMO_HASH_SECRET, "unit-test-only-demo-hash-secret-1234567890");
        config.setString(Keys.DEMO_MAX_SESSIONS_PER_IP, "100");
        config.setString(Keys.DEMO_MAX_SESSIONS_PER_EMAIL, "2");
        config.setString(Keys.DEMO_MAX_CONCURRENT_SESSIONS, "100");
        config.setString(Keys.DEMO_RATE_LIMIT_MAX_ATTEMPTS, "100");
        storage = new MemoryStorage();
        simulator = mock(DemoSimulatorService.class);
        service = new DemoService(
                config, storage, mock(CacheManager.class), mock(ConnectionManager.class), simulator);

        AccessProfile profile = new AccessProfile();
        profile.setName("Demonstração");
        profile.setDescription("Perfil de teste");
        profile.setId(storage.addObject(profile, new Request(new Columns.Exclude("id"))));
        for (String key : DEMO_PERMISSIONS) {
            AccessProfilePermission permission = new AccessProfilePermission();
            permission.setProfileId(profile.getId());
            permission.setPermissionKey(key);
            storage.addObject(permission, new Request(new Columns.All()));
        }
    }

    @Test
    public void testConcurrentSessionsHaveStrictlyIsolatedResourcesAndRestrictedProfile() throws Exception {
        DemoService.Provisioned first = create("Primeiro", "first@example.com", "10.0.0.1");
        DemoService.Provisioned second = create("Segundo", "second@example.com", "10.0.0.2");

        assertNotEquals(first.session().getUserId(), second.session().getUserId());
        assertNotEquals(first.session().getGroupId(), second.session().getGroupId());
        assertNotEquals(first.session().getDeviceId(), second.session().getDeviceId());
        assertNotEquals(first.session().getGeofenceId(), second.session().getGeofenceId());

        assertEquals(List.of(first.session().getDeviceId()), visibleDeviceIds(first.session().getUserId()));
        assertEquals(List.of(second.session().getDeviceId()), visibleDeviceIds(second.session().getUserId()));

        AccessControlService access = new AccessControlService(storage);
        assertTrue(access.hasPermission(first.session().getUserId(), AccessPermissions.MAP_VIEW));
        assertTrue(access.hasPermission(first.session().getUserId(), AccessPermissions.ACCOUNT_PREFERENCES_EDIT));
        assertTrue(access.hasPermission(first.session().getUserId(), AccessPermissions.APPEARANCE_VIEW));
        assertTrue(access.hasPermission(first.session().getUserId(), AccessPermissions.MARKER_COLOR));
        assertFalse(access.hasPermission(first.session().getUserId(), AccessPermissions.USER_VIEW));
        assertFalse(access.hasPermission(first.session().getUserId(), AccessPermissions.DEVICE_EDIT));
        assertFalse(access.hasPermission(first.session().getUserId(), AccessPermissions.ACCOUNT_PASSWORD_CHANGE));
        assertFalse(access.hasPermission(first.session().getUserId(), AccessPermissions.REPORT_EXPORT));

        assertEquals("googleHybrid", first.user().getMap());
        assertTrue(first.user().getString("activeMapStyles").contains("googleHybrid"));
        Device demoDevice = object(Device.class, first.session().getDeviceId());
        assertEquals("car", demoDevice.getCategory());
        assertEquals("car:hatch", demoDevice.getString("mapMarker3d"));
        assertEquals("car", demoDevice.getString("mapMarker3dCategory"));
        assertEquals("hatch", demoDevice.getString("mapMarker3dModel"));
        assertEquals("yellow", demoDevice.getString("mapMarker3dColor"));
    }

    @Test
    public void testScenarioControlCanOnlyUseAuthenticatedUsersOwnSession() throws Exception {
        DemoService.Provisioned first = create("Primeiro", "first@example.com", "10.0.0.1");
        DemoService.Provisioned second = create("Segundo", "second@example.com", "10.0.0.2");

        DemoSession started = service.startScenario(first.session().getUserId(), "urban");

        assertEquals(first.session().getId(), started.getId());
        assertEquals(DemoSession.STATUS_RUNNING, started.getStatus());
        assertEquals(second.session().getId(), service.getForUser(second.session().getUserId()).getId());
        verify(simulator).start(eq(first.session()), eq(DemoScenario.URBAN));
        verify(simulator, org.mockito.Mockito.never()).start(eq(second.session()), any());
    }

    @Test
    public void testEmailDailyLimitDoesNotStoreRawEmail() throws Exception {
        DemoService.Provisioned first = create("Primeiro", "Same@Example.com", "10.0.0.1");
        DemoService.Provisioned second = create("Segundo", "same@example.com", "10.0.0.2");

        DemoException error = assertThrows(
                DemoException.class,
                () -> create("Terceiro", "same@example.com", "10.0.0.3"));

        assertEquals(429, error.getStatus());
        assertEquals(first.session().getEmailHash(), second.session().getEmailHash());
        assertFalse(first.session().getEmailHash().contains("example.com"));
        assertEquals("demo.invalid", first.user().getEmail().substring(first.user().getEmail().indexOf('@') + 1));
    }

    @Test
    public void testDefaultActivationGeneratesPersistentPseudonymizationKey() throws Exception {
        Config automaticConfig = new Config();
        automaticConfig.setString(Keys.DEMO_MAX_SESSIONS_PER_IP, "100");
        automaticConfig.setString(Keys.DEMO_MAX_SESSIONS_PER_EMAIL, "100");
        automaticConfig.setString(Keys.DEMO_RATE_LIMIT_MAX_ATTEMPTS, "100");
        DemoService automaticService = new DemoService(
                automaticConfig, storage, mock(CacheManager.class), mock(ConnectionManager.class), simulator);

        DemoService.Provisioned first = automaticService.create(
                new DemoService.CreateRequest("Primeiro", "persistent@example.com", null), "10.1.0.1");
        DemoService.Provisioned second = automaticService.create(
                new DemoService.CreateRequest("Segundo", "persistent@example.com", null), "10.1.0.2");

        assertTrue(automaticService.isEnabled());
        assertEquals(first.session().getEmailHash(), second.session().getEmailHash());
        List<DemoConfiguration> configurations = storage.getObjects(
                DemoConfiguration.class, new Request(new Columns.All()));
        assertEquals(1, configurations.size());
        assertTrue(configurations.get(0).getHashSecret().length() >= 32);
    }

    @Test
    public void testCleanupIsIdempotentAndKeepsOnlyPseudonymousAuditRow() throws Exception {
        DemoService.Provisioned provisioned = create("Temporário", "temporary@example.com", "10.0.0.1");
        long userId = provisioned.session().getUserId();
        long deviceId = provisioned.session().getDeviceId();

        service.cleanup(provisioned.session().getId(), DemoSession.STATUS_EXPIRED);
        service.cleanup(provisioned.session().getId(), DemoSession.STATUS_EXPIRED);

        assertNull(object(User.class, userId));
        assertNull(object(Device.class, deviceId));
        DemoSession audit = object(DemoSession.class, provisioned.session().getId());
        assertTrue(audit.getCleanupComplete());
        assertEquals(DemoSession.STATUS_CLEANED, audit.getStatus());
        assertEquals(DemoSession.STATUS_EXPIRED, audit.getString("terminalStatus"));
        assertFalse(audit.getEmailHash().contains("@"));
    }

    @Test
    public void testExpiredSessionIsDeniedAndRemovedByRecoveryJob() throws Exception {
        DemoService.Provisioned provisioned = create("Expirado", "expired@example.com", "10.0.0.1");
        provisioned.session().setExpiresAt(new Date(System.currentTimeMillis() - 1));

        DemoException error = assertThrows(
                DemoException.class, () -> service.getForUser(provisioned.session().getUserId()));
        service.cleanupDueSessions();

        assertEquals(403, error.getStatus());
        assertNull(object(User.class, provisioned.session().getUserId()));
        assertTrue(object(DemoSession.class, provisioned.session().getId()).getCleanupComplete());
    }

    private DemoService.Provisioned create(String name, String email, String ip) throws Exception {
        return service.create(new DemoService.CreateRequest(name, email, "Kersting"), ip);
    }

    private List<Long> visibleDeviceIds(long userId) throws Exception {
        return storage.getObjects(Device.class, new Request(
                        new Columns.All(), new Condition.Permission(User.class, userId, Device.class)))
                .stream().map(Device::getId).sorted().toList();
    }

    private <T> T object(Class<T> type, long id) throws Exception {
        return storage.getObject(type, new Request(new Columns.All(), new Condition.Equals("id", id)));
    }
}
