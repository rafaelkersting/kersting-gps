package org.traccar.api.security;

import jakarta.inject.Inject;
import org.traccar.model.User;
import org.traccar.storage.StorageException;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class UserUpdateAccessService {

    private static final Set<String> PREFERENCE_ATTRIBUTES = Set.of(
            "speedUnit", "distanceUnit", "altitudeUnit", "volumeUnit", "timezone");

    private static final Set<String> SYSTEM_ATTRIBUTES = Set.of(
            "notificationTokens", "termsAccepted");

    private final AccessControlService accessControlService;

    @Inject
    public UserUpdateAccessService(AccessControlService accessControlService) {
        this.accessControlService = accessControlService;
    }

    public void checkUpdate(long actorUserId, User before, User after) throws StorageException {
        if (before == null) {
            throw new SecurityException("User not found");
        }
        if (actorUserId == after.getId()) {
            checkOwnAccount(actorUserId, before, after);
        } else {
            checkOtherUser(actorUserId, before, after);
        }
    }

    private void checkOwnAccount(long userId, User before, User after) throws StorageException {
        if (nativeRestrictionsChanged(before, after)) {
            check(userId, AccessPermissions.USER_NATIVE_RESTRICTIONS_EDIT);
        }
        if (!Objects.equals(before.getName(), after.getName())
                || !Objects.equals(before.getLogin(), after.getLogin())) {
            check(userId, AccessPermissions.ACCOUNT_BASIC_EDIT);
        }
        if (!Objects.equals(before.getEmail(), after.getEmail())) {
            check(userId, AccessPermissions.ACCOUNT_EMAIL_EDIT);
        }
        if (after.getHashedPassword() != null) {
            check(userId, AccessPermissions.ACCOUNT_PASSWORD_CHANGE);
        }
        if (!Objects.equals(before.getTotpKey(), after.getTotpKey())) {
            check(userId, AccessPermissions.ACCOUNT_SECURITY_EDIT);
        }
        if (!Objects.equals(before.getPhone(), after.getPhone())
                || !Objects.equals(before.getMap(), after.getMap())
                || !Objects.equals(before.getCoordinateFormat(), after.getCoordinateFormat())
                || !Objects.equals(before.getPoiLayer(), after.getPoiLayer())
                || attributesChanged(before, after, PREFERENCE_ATTRIBUTES)) {
            check(userId, AccessPermissions.ACCOUNT_PREFERENCES_EDIT);
        }
        if (Double.compare(before.getLatitude(), after.getLatitude()) != 0
                || Double.compare(before.getLongitude(), after.getLongitude()) != 0
                || before.getZoom() != after.getZoom()) {
            check(userId, AccessPermissions.ACCOUNT_LOCATION_EDIT);
        }
        if (otherAttributesChanged(before, after)) {
            check(userId, AccessPermissions.ACCOUNT_ATTRIBUTES_EDIT);
        }
    }

    private void checkOtherUser(long userId, User before, User after) throws StorageException {
        if (!onlySystemAttributesChanged(before, after)) {
            check(userId, AccessPermissions.USER_EDIT);
        }
        if (nativeRestrictionsChanged(before, after)) {
            check(userId, AccessPermissions.USER_NATIVE_RESTRICTIONS_EDIT);
        }
        if (otherAttributesChanged(before, after)) {
            check(userId, AccessPermissions.USER_ATTRIBUTES_EDIT);
        }
    }

    private void check(long userId, String permission) throws StorageException {
        accessControlService.checkPermission(userId, permission);
    }

    private boolean nativeRestrictionsChanged(User before, User after) {
        return before.getAdministrator() != after.getAdministrator()
                || before.getReadonly() != after.getReadonly()
                || before.getDeviceReadonly() != after.getDeviceReadonly()
                || before.getLimitCommands() != after.getLimitCommands()
                || before.getDisableReports() != after.getDisableReports()
                || before.getFixedEmail() != after.getFixedEmail()
                || before.getDisabled() != after.getDisabled()
                || before.getDeviceLimit() != after.getDeviceLimit()
                || before.getUserLimit() != after.getUserLimit()
                || before.getTemporary() != after.getTemporary()
                || !Objects.equals(before.getExpirationTime(), after.getExpirationTime());
    }

    private boolean onlySystemAttributesChanged(User before, User after) {
        return before.compare(after, SYSTEM_ATTRIBUTES.toArray(String[]::new));
    }

    private boolean attributesChanged(User before, User after, Set<String> keys) {
        return keys.stream().anyMatch(key ->
                !Objects.equals(before.getAttributes().get(key), after.getAttributes().get(key)));
    }

    private boolean otherAttributesChanged(User before, User after) {
        Map<String, Object> beforeAttributes = filteredAttributes(before);
        Map<String, Object> afterAttributes = filteredAttributes(after);
        return !beforeAttributes.equals(afterAttributes);
    }

    private Map<String, Object> filteredAttributes(User user) {
        Map<String, Object> attributes = new HashMap<>(user.getAttributes());
        PREFERENCE_ATTRIBUTES.forEach(attributes::remove);
        SYSTEM_ATTRIBUTES.forEach(attributes::remove);
        return attributes;
    }
}
