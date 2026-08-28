package org.traccar.api.security;

import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import org.traccar.model.AccessProfile;
import org.traccar.model.AccessProfilePermission;
import org.traccar.model.User;
import org.traccar.model.UserAccessProfile;
import org.traccar.model.UserPermissionOverride;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.HashSet;
import java.util.Set;

public class AccessControlService {

    public record EffectiveAccess(
            long profileId, String profileName, Set<String> permissions,
            Set<String> profilePermissions, Set<String> allowedOverrides,
            Set<String> denied, boolean legacy) {
    }

    private final Storage storage;

    @Inject
    public AccessControlService(Storage storage) {
        this.storage = storage;
    }

    public EffectiveAccess getEffectiveAccess(long userId) throws StorageException {
        User user = storage.getObject(User.class, new Request(
                new Columns.All(), new Condition.Equals("id", userId)));
        if (user == null) {
            throw new SecurityException("User access denied");
        }
        if (user.getAdministrator()) {
            return new EffectiveAccess(
                    0, "Administrador", AccessPermissions.ALL,
                    AccessPermissions.ALL, Set.of(), Set.of(), false);
        }
        UserAccessProfile assignment = storage.getObject(UserAccessProfile.class, new Request(
                new Columns.All(), new Condition.Equals("userId", userId)));
        if (assignment == null) {
            return new EffectiveAccess(0, null, Set.of(), Set.of(), Set.of(), Set.of(), true);
        }
        AccessProfile profile = storage.getObject(AccessProfile.class, new Request(
                new Columns.All(), new Condition.Equals("id", assignment.getProfileId())));
        Set<String> profilePermissions = new HashSet<>();
        Set<String> allowed = new HashSet<>();
        Set<String> allowedOverrides = new HashSet<>();
        Set<String> denied = new HashSet<>();
        if (profile != null && !profile.getDisabled()) {
            for (AccessProfilePermission permission : storage.getObjects(
                    AccessProfilePermission.class, new Request(
                            new Columns.All(), new Condition.Equals("profileId", profile.getId())))) {
                if (AccessPermissions.ALL.contains(permission.getPermissionKey())) {
                    profilePermissions.add(permission.getPermissionKey());
                    allowed.add(permission.getPermissionKey());
                }
            }
            applyAccountCompatibility(profilePermissions, allowed);
        }
        for (UserPermissionOverride override : storage.getObjects(
                UserPermissionOverride.class, new Request(
                        new Columns.All(), new Condition.Equals("userId", userId)))) {
            if (!AccessPermissions.ALL.contains(override.getPermissionKey())) {
                continue;
            }
            if (UserPermissionOverride.EFFECT_DENY.equals(override.getEffect())) {
                allowed.remove(override.getPermissionKey());
                denied.add(override.getPermissionKey());
            } else if (UserPermissionOverride.EFFECT_ALLOW.equals(override.getEffect())
                    && !denied.contains(override.getPermissionKey())) {
                allowedOverrides.add(override.getPermissionKey());
                allowed.add(override.getPermissionKey());
            }
        }
        return new EffectiveAccess(
                profile != null ? profile.getId() : 0,
                profile != null ? profile.getName() : null,
                Set.copyOf(allowed), Set.copyOf(profilePermissions),
                Set.copyOf(allowedOverrides), Set.copyOf(denied), false);
    }

    private void applyAccountCompatibility(Set<String> profilePermissions, Set<String> allowed) {
        boolean hasGranularAccountPermission = profilePermissions.stream()
                .anyMatch(permission -> permission.startsWith("account."));
        if (!hasGranularAccountPermission) {
            if (profilePermissions.contains(AccessPermissions.PREFERENCE_VIEW)) {
                allowed.add(AccessPermissions.ACCOUNT_VIEW);
            }
            if (profilePermissions.contains(AccessPermissions.PREFERENCE_EDIT)) {
                allowed.addAll(Set.of(
                        AccessPermissions.ACCOUNT_BASIC_EDIT,
                        AccessPermissions.ACCOUNT_PASSWORD_CHANGE,
                        AccessPermissions.ACCOUNT_SECURITY_EDIT,
                        AccessPermissions.ACCOUNT_PREFERENCES_EDIT));
            }
        }
    }

    public boolean hasPermission(long userId, String permission) throws StorageException {
        if (!AccessPermissions.ALL.contains(permission)) {
            throw new IllegalArgumentException("Unknown access permission");
        }
        EffectiveAccess access = getEffectiveAccess(userId);
        if (access.legacy()) {
            return true;
        }
        return access.permissions().contains(permission) && !access.denied().contains(permission);
    }

    public void checkPermission(long userId, String permission) throws StorageException {
        if (!hasPermission(userId, permission)) {
            throw new ForbiddenException("Access permission denied");
        }
    }
}
