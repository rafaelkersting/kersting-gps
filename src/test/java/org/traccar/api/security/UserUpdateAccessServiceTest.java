package org.traccar.api.security;

import jakarta.ws.rs.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.model.User;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

public class UserUpdateAccessServiceTest {

    private static final long ACTOR_ID = 1;
    private static final long OTHER_ID = 2;

    private AccessControlService accessControlService;
    private UserUpdateAccessService service;

    @BeforeEach
    public void setUp() {
        accessControlService = mock(AccessControlService.class);
        service = new UserUpdateAccessService(accessControlService);
    }

    @Test
    public void testOwnAccountPermissionsByFieldGroup() throws Exception {
        assertOwnPermission(AccessPermissions.ACCOUNT_BASIC_EDIT, user -> user.setName("Changed"));
        assertOwnPermission(AccessPermissions.ACCOUNT_EMAIL_EDIT, user -> user.setEmail("changed@example.com"));
        assertOwnPermission(AccessPermissions.ACCOUNT_PASSWORD_CHANGE, user -> user.setPassword("secret"));
        assertOwnPermission(AccessPermissions.ACCOUNT_SECURITY_EDIT, user -> user.setTotpKey("totp"));
        assertOwnPermission(AccessPermissions.ACCOUNT_PREFERENCES_EDIT, user -> user.setPhone("123"));
        assertOwnPermission(AccessPermissions.ACCOUNT_PREFERENCES_EDIT,
                user -> user.getAttributes().put("speedUnit", "kmh"));
        assertOwnPermission(AccessPermissions.ACCOUNT_LOCATION_EDIT, user -> user.setLatitude(1.0));
        assertOwnPermission(AccessPermissions.ACCOUNT_ATTRIBUTES_EDIT,
                user -> user.getAttributes().put("custom", true));
        assertOwnPermission(AccessPermissions.USER_NATIVE_RESTRICTIONS_EDIT,
                user -> user.setAdministrator(true));
    }

    @Test
    public void testSystemAttributesDoNotRequireAccountPermission() throws Exception {
        User before = createUser(ACTOR_ID);
        User after = createUser(ACTOR_ID);
        after.getAttributes().put("notificationTokens", "token");

        service.checkUpdate(ACTOR_ID, before, after);

        verify(accessControlService, never()).checkPermission(
                ACTOR_ID, AccessPermissions.ACCOUNT_ATTRIBUTES_EDIT);
    }

    @Test
    public void testOtherUserRequiresGeneralEdit() throws Exception {
        User before = createUser(OTHER_ID);
        User after = createUser(OTHER_ID);
        after.setPhone("123");

        service.checkUpdate(ACTOR_ID, before, after);

        verify(accessControlService).checkPermission(ACTOR_ID, AccessPermissions.USER_EDIT);
    }

    @Test
    public void testOtherUserSensitivePermissions() throws Exception {
        User before = createUser(OTHER_ID);
        User after = createUser(OTHER_ID);
        after.setReadonly(true);
        after.getAttributes().put("custom", true);

        service.checkUpdate(ACTOR_ID, before, after);

        verify(accessControlService).checkPermission(
                ACTOR_ID, AccessPermissions.USER_NATIVE_RESTRICTIONS_EDIT);
        verify(accessControlService).checkPermission(
                ACTOR_ID, AccessPermissions.USER_ATTRIBUTES_EDIT);
    }

    @Test
    public void testForbiddenFieldChangePropagatesAsHttpForbidden() throws Exception {
        User before = createUser(ACTOR_ID);
        User after = createUser(ACTOR_ID);
        after.setEmail("blocked@example.com");
        doThrow(new ForbiddenException()).when(accessControlService).checkPermission(
                ACTOR_ID, AccessPermissions.ACCOUNT_EMAIL_EDIT);

        assertThrows(ForbiddenException.class, () -> service.checkUpdate(ACTOR_ID, before, after));
    }

    private void assertOwnPermission(String permission, UserChange change) throws Exception {
        accessControlService = mock(AccessControlService.class);
        service = new UserUpdateAccessService(accessControlService);
        User before = createUser(ACTOR_ID);
        User after = createUser(ACTOR_ID);
        change.apply(after);

        service.checkUpdate(ACTOR_ID, before, after);

        verify(accessControlService).checkPermission(ACTOR_ID, permission);
    }

    private User createUser(long id) {
        User user = new User();
        user.setId(id);
        user.setName("User");
        user.setEmail("user@example.com");
        return user;
    }

    private interface UserChange {
        void apply(User user);
    }
}
