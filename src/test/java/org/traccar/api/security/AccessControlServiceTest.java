package org.traccar.api.security;

import jakarta.ws.rs.ForbiddenException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.traccar.model.AccessProfile;
import org.traccar.model.AccessProfilePermission;
import org.traccar.model.User;
import org.traccar.model.UserAccessProfile;
import org.traccar.model.UserPermissionOverride;
import org.traccar.storage.Storage;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AccessControlServiceTest {

    private static final long USER_ID = 10;
    private static final long PROFILE_ID = 20;

    private AccessControlService createService(Storage storage, boolean administrator) throws Exception {
        User user = new User();
        user.setId(USER_ID);
        user.setAdministrator(administrator);
        when(storage.getObject(eq(User.class), any())).thenReturn(user);
        return new AccessControlService(storage);
    }

    @Test
    public void testLegacyUserKeepsAppearanceAccess() throws Exception {
        Storage storage = mock(Storage.class);
        when(storage.getObject(eq(UserAccessProfile.class), any())).thenReturn(null);
        AccessControlService service = createService(storage, false);
        assertTrue(service.getEffectiveAccess(USER_ID).legacy());
        assertTrue(service.hasPermission(USER_ID, AccessPermissions.CARD_IMAGE));
    }

    @Test
    public void testDeniedPermissionUsesForbiddenResponse() throws Exception {
        Storage storage = profileStorage();
        when(storage.getObjects(eq(AccessProfilePermission.class), any())).thenReturn(List.of());
        when(storage.getObjects(eq(UserPermissionOverride.class), any())).thenReturn(List.of());
        AccessControlService service = createService(storage, false);
        assertThrows(ForbiddenException.class,
                () -> service.checkPermission(USER_ID, AccessPermissions.DEVICE_VIEW));
    }

    @Test
    public void testProfilePermission() throws Exception {
        Storage storage = profileStorage();
        AccessProfilePermission permission = new AccessProfilePermission();
        permission.setProfileId(PROFILE_ID);
        permission.setPermissionKey(AccessPermissions.MARKER_3D);
        when(storage.getObjects(eq(AccessProfilePermission.class), any())).thenReturn(List.of(permission));
        when(storage.getObjects(eq(UserPermissionOverride.class), any())).thenReturn(List.of());
        AccessControlService service = createService(storage, false);
        AccessControlService.EffectiveAccess access = service.getEffectiveAccess(USER_ID);
        assertTrue(access.profilePermissions().contains(AccessPermissions.MARKER_3D));
        assertTrue(service.hasPermission(USER_ID, AccessPermissions.MARKER_3D));
        assertFalse(service.hasPermission(USER_ID, AccessPermissions.CARD_IMAGE));
    }

    @Test
    public void testIndividualDenyWins() throws Exception {
        Storage storage = profileStorage();
        AccessProfilePermission permission = new AccessProfilePermission();
        permission.setProfileId(PROFILE_ID);
        permission.setPermissionKey(AccessPermissions.MARKER_3D);
        UserPermissionOverride override = new UserPermissionOverride();
        override.setUserId(USER_ID);
        override.setPermissionKey(AccessPermissions.MARKER_3D);
        override.setEffect(UserPermissionOverride.EFFECT_DENY);
        when(storage.getObjects(eq(AccessProfilePermission.class), any())).thenReturn(List.of(permission));
        when(storage.getObjects(eq(UserPermissionOverride.class), any())).thenReturn(List.of(override));
        AccessControlService service = createService(storage, false);
        assertTrue(service.getEffectiveAccess(USER_ID).denied().contains(AccessPermissions.MARKER_3D));
        assertFalse(service.hasPermission(USER_ID, AccessPermissions.MARKER_3D));
    }

    @Test
    public void testIndividualAllowAddsPermission() throws Exception {
        Storage storage = profileStorage();
        UserPermissionOverride override = new UserPermissionOverride();
        override.setUserId(USER_ID);
        override.setPermissionKey(AccessPermissions.CARD_IMAGE);
        override.setEffect(UserPermissionOverride.EFFECT_ALLOW);
        when(storage.getObjects(eq(AccessProfilePermission.class), any())).thenReturn(List.of());
        when(storage.getObjects(eq(UserPermissionOverride.class), any())).thenReturn(List.of(override));
        AccessControlService service = createService(storage, false);
        assertTrue(service.getEffectiveAccess(USER_ID).allowedOverrides().contains(AccessPermissions.CARD_IMAGE));
        assertTrue(service.hasPermission(USER_ID, AccessPermissions.CARD_IMAGE));
    }

    @Test
    public void testModulePermissionUsesSameProfileAndOverrideRules() throws Exception {
        Storage storage = profileStorage();
        AccessProfilePermission permission = new AccessProfilePermission();
        permission.setProfileId(PROFILE_ID);
        permission.setPermissionKey(AccessPermissions.DRIVER_VIEW);
        when(storage.getObjects(eq(AccessProfilePermission.class), any())).thenReturn(List.of(permission));
        when(storage.getObjects(eq(UserPermissionOverride.class), any())).thenReturn(List.of());
        AccessControlService service = createService(storage, false);
        assertTrue(service.hasPermission(USER_ID, AccessPermissions.DRIVER_VIEW));
        assertFalse(service.hasPermission(USER_ID, AccessPermissions.DRIVER_EDIT));
    }

    @Test
    public void testLegacyPreferenceProfileGetsSafeAccountCompatibility() throws Exception {
        Storage storage = profileStorage();
        AccessProfilePermission view = new AccessProfilePermission();
        view.setPermissionKey(AccessPermissions.PREFERENCE_VIEW);
        AccessProfilePermission edit = new AccessProfilePermission();
        edit.setPermissionKey(AccessPermissions.PREFERENCE_EDIT);
        when(storage.getObjects(eq(AccessProfilePermission.class), any())).thenReturn(List.of(view, edit));
        when(storage.getObjects(eq(UserPermissionOverride.class), any())).thenReturn(List.of());

        AccessControlService service = createService(storage, false);

        AccessControlService.EffectiveAccess access = service.getEffectiveAccess(USER_ID);
        assertTrue(access.compatibilityPermissions().contains(AccessPermissions.ACCOUNT_VIEW));
        assertTrue(access.compatibilityPermissions().contains(AccessPermissions.ACCOUNT_BASIC_EDIT));
        assertTrue(access.compatibilityPermissions().contains(AccessPermissions.ACCOUNT_PASSWORD_CHANGE));
        assertTrue(access.compatibilityPermissions().contains(AccessPermissions.ACCOUNT_SECURITY_EDIT));
        assertTrue(access.compatibilityPermissions().contains(AccessPermissions.ACCOUNT_PREFERENCES_EDIT));
        assertTrue(service.hasPermission(USER_ID, AccessPermissions.ACCOUNT_VIEW));
        assertTrue(service.hasPermission(USER_ID, AccessPermissions.ACCOUNT_BASIC_EDIT));
        assertTrue(service.hasPermission(USER_ID, AccessPermissions.ACCOUNT_PASSWORD_CHANGE));
        assertTrue(service.hasPermission(USER_ID, AccessPermissions.ACCOUNT_PREFERENCES_EDIT));
        assertFalse(service.hasPermission(USER_ID, AccessPermissions.ACCOUNT_EMAIL_EDIT));
        assertFalse(service.hasPermission(USER_ID, AccessPermissions.ACCOUNT_LOCATION_EDIT));
        assertFalse(service.hasPermission(USER_ID, AccessPermissions.ACCOUNT_ATTRIBUTES_EDIT));
    }

    @Test
    public void testExplicitGranularAccountPermissionKeepsProfileOrigin() throws Exception {
        Storage storage = profileStorage();
        AccessProfilePermission preferenceEdit = new AccessProfilePermission();
        preferenceEdit.setPermissionKey(AccessPermissions.PREFERENCE_EDIT);
        AccessProfilePermission accountBasicEdit = new AccessProfilePermission();
        accountBasicEdit.setPermissionKey(AccessPermissions.ACCOUNT_BASIC_EDIT);
        when(storage.getObjects(eq(AccessProfilePermission.class), any()))
                .thenReturn(List.of(preferenceEdit, accountBasicEdit));
        when(storage.getObjects(eq(UserPermissionOverride.class), any())).thenReturn(List.of());

        AccessControlService.EffectiveAccess access = createService(storage, false).getEffectiveAccess(USER_ID);

        assertTrue(access.profilePermissions().contains(AccessPermissions.ACCOUNT_BASIC_EDIT));
        assertTrue(access.compatibilityPermissions().isEmpty());
        assertFalse(access.permissions().contains(AccessPermissions.ACCOUNT_PASSWORD_CHANGE));
    }

    @Test
    public void testAccountCompatibilityKeepsOverridePrecedence() throws Exception {
        Storage storage = profileStorage();
        AccessProfilePermission preferenceEdit = new AccessProfilePermission();
        preferenceEdit.setPermissionKey(AccessPermissions.PREFERENCE_EDIT);
        UserPermissionOverride allow = new UserPermissionOverride();
        allow.setPermissionKey(AccessPermissions.ACCOUNT_EMAIL_EDIT);
        allow.setEffect(UserPermissionOverride.EFFECT_ALLOW);
        UserPermissionOverride deny = new UserPermissionOverride();
        deny.setPermissionKey(AccessPermissions.ACCOUNT_BASIC_EDIT);
        deny.setEffect(UserPermissionOverride.EFFECT_DENY);
        when(storage.getObjects(eq(AccessProfilePermission.class), any())).thenReturn(List.of(preferenceEdit));
        when(storage.getObjects(eq(UserPermissionOverride.class), any())).thenReturn(List.of(allow, deny));

        AccessControlService service = createService(storage, false);
        AccessControlService.EffectiveAccess access = service.getEffectiveAccess(USER_ID);

        assertTrue(access.allowedOverrides().contains(AccessPermissions.ACCOUNT_EMAIL_EDIT));
        assertTrue(access.denied().contains(AccessPermissions.ACCOUNT_BASIC_EDIT));
        assertTrue(access.compatibilityPermissions().contains(AccessPermissions.ACCOUNT_BASIC_EDIT));
        assertFalse(service.hasPermission(USER_ID, AccessPermissions.ACCOUNT_BASIC_EDIT));
        assertThrows(ForbiddenException.class,
                () -> service.checkPermission(USER_ID, AccessPermissions.ACCOUNT_BASIC_EDIT));
    }

    @Test
    public void testAdministratorReceivesCompleteCatalog() throws Exception {
        Storage storage = mock(Storage.class);
        AccessControlService service = createService(storage, true);
        assertTrue(service.getEffectiveAccess(USER_ID).permissions().containsAll(AccessPermissions.ALL));
        assertFalse(service.getEffectiveAccess(USER_ID).legacy());
    }

    @ParameterizedTest
    @MethodSource("migratedPermissions")
    public void testEachMigratedPermissionOffThenOn(String permissionKey) throws Exception {
        Storage storage = profileStorage();
        when(storage.getObjects(eq(AccessProfilePermission.class), any())).thenReturn(List.of());
        when(storage.getObjects(eq(UserPermissionOverride.class), any())).thenReturn(List.of());
        AccessControlService service = createService(storage, false);
        assertFalse(service.hasPermission(USER_ID, permissionKey));

        AccessProfilePermission permission = new AccessProfilePermission();
        permission.setProfileId(PROFILE_ID);
        permission.setPermissionKey(permissionKey);
        when(storage.getObjects(eq(AccessProfilePermission.class), any())).thenReturn(List.of(permission));
        assertTrue(service.hasPermission(USER_ID, permissionKey));
    }

    @Test
    public void testProfileChangesAreEffectiveWithoutStaleServiceCache() throws Exception {
        Storage storage = profileStorage();
        AccessProfilePermission permission = new AccessProfilePermission();
        permission.setProfileId(PROFILE_ID);
        permission.setPermissionKey(AccessPermissions.CARD_IMAGE);
        when(storage.getObjects(eq(AccessProfilePermission.class), any()))
                .thenReturn(List.of(), List.of(permission), List.of());
        when(storage.getObjects(eq(UserPermissionOverride.class), any())).thenReturn(List.of());
        AccessControlService service = createService(storage, false);
        assertFalse(service.hasPermission(USER_ID, AccessPermissions.CARD_IMAGE));
        assertTrue(service.hasPermission(USER_ID, AccessPermissions.CARD_IMAGE));
        assertFalse(service.hasPermission(USER_ID, AccessPermissions.CARD_IMAGE));
    }

    private static Stream<String> migratedPermissions() {
        return AccessPermissions.ALL.stream();
    }

    private Storage profileStorage() throws Exception {
        Storage storage = mock(Storage.class);
        UserAccessProfile assignment = new UserAccessProfile();
        assignment.setUserId(USER_ID);
        assignment.setProfileId(PROFILE_ID);
        AccessProfile profile = new AccessProfile();
        profile.setId(PROFILE_ID);
        profile.setName("Cliente");
        when(storage.getObject(eq(UserAccessProfile.class), any())).thenReturn(assignment);
        when(storage.getObject(eq(AccessProfile.class), any())).thenReturn(profile);
        return storage;
    }
}
