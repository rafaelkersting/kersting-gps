package org.traccar.api.resource;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.traccar.api.BaseResource;
import org.traccar.api.security.AccessControlService;
import org.traccar.api.security.AccessPermissions;
import org.traccar.model.AccessProfile;
import org.traccar.model.AccessProfilePermission;
import org.traccar.model.Device;
import org.traccar.model.Group;
import org.traccar.model.User;
import org.traccar.model.UserAccessProfile;
import org.traccar.model.UserPermissionOverride;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import java.util.List;
import java.util.Set;

@Path("access")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AccessResource extends BaseResource {

    public record ProfileDetails(AccessProfile profile, Set<String> permissions) {
    }

    public record NativeRestrictions(
            boolean readonly, boolean deviceReadonly,
            boolean limitCommands, boolean disableReports) {
    }

    public record ScopeSummary(int groups, int devices) {
    }

    public record UserAccess(
            long profileId, List<UserPermissionOverride> overrides,
            AccessControlService.EffectiveAccess effectiveAccess,
            NativeRestrictions nativeRestrictions, ScopeSummary scope) {
    }

    @Path("catalog")
    @GET
    public Set<String> getCatalog() throws StorageException {
        permissionsService.checkAdmin(getUserId());
        accessControlService.checkPermission(getUserId(), AccessPermissions.ACCESS_PROFILE_VIEW);
        return AccessPermissions.ALL;
    }

    @Path("session")
    @GET
    public AccessControlService.EffectiveAccess getSessionAccess() throws StorageException {
        return accessControlService.getEffectiveAccess(getUserId());
    }

    @Path("profiles")
    @GET
    public List<AccessProfile> getProfiles() throws StorageException {
        permissionsService.checkAdmin(getUserId());
        accessControlService.checkPermission(getUserId(), AccessPermissions.ACCESS_PROFILE_VIEW);
        return storage.getObjects(AccessProfile.class, new Request(
                new Columns.All(), null, new Order("name", false, 0, 0)));
    }

    @Path("profiles/{id}")
    @GET
    public ProfileDetails getProfile(@PathParam("id") long profileId) throws StorageException {
        permissionsService.checkAdmin(getUserId());
        accessControlService.checkPermission(getUserId(), AccessPermissions.ACCESS_PROFILE_VIEW);
        AccessProfile profile = storage.getObject(AccessProfile.class, new Request(
                new Columns.All(), new Condition.Equals("id", profileId)));
        if (profile == null) {
            throw new IllegalArgumentException("Access profile not found");
        }
        Set<String> permissions = storage.getObjects(AccessProfilePermission.class, new Request(
                        new Columns.All(), new Condition.Equals("profileId", profileId))).stream()
                .map(AccessProfilePermission::getPermissionKey).collect(java.util.stream.Collectors.toSet());
        return new ProfileDetails(profile, permissions);
    }

    @Path("profiles")
    @POST
    public Response addProfile(ProfileDetails details) throws StorageException {
        permissionsService.checkAdmin(getUserId());
        accessControlService.checkPermission(getUserId(), AccessPermissions.ACCESS_PROFILE_CREATE);
        validatePermissions(details.permissions());
        AccessProfile profile = details.profile();
        profile.setId(storage.addObject(profile, new Request(new Columns.Exclude("id"))));
        replaceProfilePermissions(profile.getId(), details.permissions());
        return Response.ok(new ProfileDetails(profile, details.permissions())).build();
    }

    @Path("profiles/{id}")
    @PUT
    public ProfileDetails updateProfile(
            @PathParam("id") long profileId, ProfileDetails details) throws StorageException {
        permissionsService.checkAdmin(getUserId());
        accessControlService.checkPermission(getUserId(), AccessPermissions.ACCESS_PROFILE_EDIT);
        validatePermissions(details.permissions());
        AccessProfile profile = details.profile();
        AccessProfile currentProfile = storage.getObject(AccessProfile.class, new Request(
                new Columns.All(), new Condition.Equals("id", profileId)));
        if (currentProfile == null) {
            throw new IllegalArgumentException("Access profile not found");
        }
        if (currentProfile.getDisabled() != profile.getDisabled()) {
            accessControlService.checkPermission(getUserId(), AccessPermissions.ACCESS_PROFILE_DISABLE);
        }
        profile.setId(profileId);
        storage.updateObject(profile, new Request(
                new Columns.Exclude("id"), new Condition.Equals("id", profileId)));
        replaceProfilePermissions(profileId, details.permissions());
        return new ProfileDetails(profile, details.permissions());
    }

    @Path("users/{id}")
    @GET
    public UserAccess getUserAccess(@PathParam("id") long userId) throws StorageException {
        permissionsService.checkAdmin(getUserId());
        accessControlService.checkPermission(getUserId(), AccessPermissions.USER_VIEW);
        UserAccessProfile assignment = storage.getObject(UserAccessProfile.class, new Request(
                new Columns.All(), new Condition.Equals("userId", userId)));
        List<UserPermissionOverride> overrides = storage.getObjects(UserPermissionOverride.class, new Request(
                new Columns.All(), new Condition.Equals("userId", userId)));
        User user = storage.getObject(User.class, new Request(
                new Columns.All(), new Condition.Equals("id", userId)));
        if (user == null) {
            throw new IllegalArgumentException("User not found");
        }
        boolean administrator = user.getAdministrator();
        NativeRestrictions restrictions = new NativeRestrictions(
                !administrator && (permissionsService.getServer().getReadonly() || user.getReadonly()),
                !administrator && (permissionsService.getServer().getDeviceReadonly() || user.getDeviceReadonly()),
                !administrator && (permissionsService.getServer().getLimitCommands() || user.getLimitCommands()),
                !administrator && user.getDisableReports());
        int groupCount = storage.getObjects(Group.class, new Request(
                new Columns.Include("id"), new Condition.Permission(User.class, userId, Group.class))).size();
        int deviceCount = storage.getObjects(Device.class, new Request(
                new Columns.Include("id"), new Condition.Permission(User.class, userId, Device.class))).size();
        return new UserAccess(
                assignment != null ? assignment.getProfileId() : 0, overrides,
                accessControlService.getEffectiveAccess(userId), restrictions,
                new ScopeSummary(groupCount, deviceCount));
    }

    @Path("users/{id}")
    @PUT
    public UserAccess updateUserAccess(
            @PathParam("id") long userId, UserAccess access) throws StorageException {
        permissionsService.checkAdmin(getUserId());
        accessControlService.checkPermission(getUserId(), AccessPermissions.USER_ASSIGN_PROFILE);
        accessControlService.checkPermission(getUserId(), AccessPermissions.ACCESS_PROFILE_ASSIGN);
        User user = storage.getObject(User.class, new Request(
                new Columns.Include("id"), new Condition.Equals("id", userId)));
        if (user == null) {
            throw new IllegalArgumentException("User not found");
        }
        storage.removeObject(UserAccessProfile.class, new Request(new Condition.Equals("userId", userId)));
        if (access.profileId() > 0) {
            AccessProfile profile = storage.getObject(AccessProfile.class, new Request(
                    new Columns.Include("id"), new Condition.Equals("id", access.profileId())));
            if (profile == null) {
                throw new IllegalArgumentException("Access profile not found");
            }
            UserAccessProfile assignment = new UserAccessProfile();
            assignment.setUserId(userId);
            assignment.setProfileId(access.profileId());
            storage.addObject(assignment, new Request(new Columns.All()));
        }
        storage.removeObject(UserPermissionOverride.class, new Request(new Condition.Equals("userId", userId)));
        for (UserPermissionOverride override : access.overrides()) {
            validateOverride(override);
            override.setUserId(userId);
            storage.addObject(override, new Request(new Columns.All()));
        }
        return getUserAccess(userId);
    }

    private void replaceProfilePermissions(long profileId, Set<String> permissions) throws StorageException {
        storage.removeObject(AccessProfilePermission.class,
                new Request(new Condition.Equals("profileId", profileId)));
        for (String permissionKey : permissions) {
            AccessProfilePermission permission = new AccessProfilePermission();
            permission.setProfileId(profileId);
            permission.setPermissionKey(permissionKey);
            storage.addObject(permission, new Request(new Columns.All()));
        }
    }

    private void validatePermissions(Set<String> permissions) {
        if (permissions == null || !AccessPermissions.ALL.containsAll(permissions)) {
            throw new IllegalArgumentException("Invalid access permission");
        }
    }

    private void validateOverride(UserPermissionOverride override) {
        if (!AccessPermissions.ALL.contains(override.getPermissionKey())
                || !Set.of(UserPermissionOverride.EFFECT_ALLOW, UserPermissionOverride.EFFECT_DENY)
                .contains(override.getEffect())) {
            throw new IllegalArgumentException("Invalid user permission override");
        }
    }
}
