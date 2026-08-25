package org.traccar.api.security;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class AccessPermissions {

    public static final String APPEARANCE_VIEW = "device.appearance.view";
    public static final String CARD_IMAGE = "device.appearance.card-image";
    public static final String MAP_MARKER = "device.appearance.map-marker";
    public static final String MARKER_3D = "device.appearance.marker3d";
    public static final String MARKER_MODEL = "device.appearance.marker-model";
    public static final String MARKER_COLOR = "device.appearance.marker-color";
    public static final String CUSTOM_UPLOAD = "device.appearance.custom-upload";

    public static final String MAP_VIEW = "map.view";
    public static final String MAP_DEVICES = "map.devices";
    public static final String MAP_FOLLOW = "map.follow";
    public static final String MAP_HISTORY = "map.history";

    public static final String DEVICE_VIEW = "device.view";
    public static final String DEVICE_CREATE = "device.create";
    public static final String DEVICE_EDIT = "device.edit";
    public static final String DEVICE_DELETE = "device.delete";
    public static final String DEVICE_CHANGE_GROUP = "device.change-group";

    public static final String COMMAND_VIEW = "command.view";
    public static final String COMMAND_CREATE = "command.create";
    public static final String COMMAND_EDIT = "command.edit";
    public static final String COMMAND_DELETE = "command.delete";
    public static final String COMMAND_SEND = "command.send";
    public static final String COMMAND_LOCATE = "command.locate";
    public static final String COMMAND_LOCK = "command.lock";
    public static final String COMMAND_UNLOCK = "command.unlock";

    public static final String REPORT_VIEW = "report.view";
    public static final String REPORT_GENERATE = "report.generate";
    public static final String REPORT_EXPORT = "report.export";

    public static final String GEOFENCE_VIEW = "geofence.view";
    public static final String GEOFENCE_CREATE = "geofence.create";
    public static final String GEOFENCE_EDIT = "geofence.edit";
    public static final String GEOFENCE_DELETE = "geofence.delete";

    public static final String GROUP_VIEW = "group.view";
    public static final String GROUP_CREATE = "group.create";
    public static final String GROUP_EDIT = "group.edit";
    public static final String GROUP_DELETE = "group.delete";

    public static final String DRIVER_VIEW = "driver.view";
    public static final String DRIVER_CREATE = "driver.create";
    public static final String DRIVER_EDIT = "driver.edit";
    public static final String DRIVER_DELETE = "driver.delete";

    public static final String CALENDAR_VIEW = "calendar.view";
    public static final String CALENDAR_CREATE = "calendar.create";
    public static final String CALENDAR_EDIT = "calendar.edit";
    public static final String CALENDAR_DELETE = "calendar.delete";

    public static final String MAINTENANCE_VIEW = "maintenance.view";
    public static final String MAINTENANCE_CREATE = "maintenance.create";
    public static final String MAINTENANCE_EDIT = "maintenance.edit";
    public static final String MAINTENANCE_DELETE = "maintenance.delete";

    public static final String NOTIFICATION_VIEW = "notification.view";
    public static final String NOTIFICATION_CREATE = "notification.create";
    public static final String NOTIFICATION_EDIT = "notification.edit";
    public static final String NOTIFICATION_DELETE = "notification.delete";

    public static final String USER_VIEW = "user.view";
    public static final String USER_CREATE = "user.create";
    public static final String USER_EDIT = "user.edit";
    public static final String USER_DELETE = "user.delete";
    public static final String USER_ASSIGN_PROFILE = "user.assign-profile";
    public static final String USER_LINK_SCOPE = "user.link-scope";

    public static final String ACCESS_PROFILE_VIEW = "access-profile.view";
    public static final String ACCESS_PROFILE_CREATE = "access-profile.create";
    public static final String ACCESS_PROFILE_EDIT = "access-profile.edit";
    public static final String ACCESS_PROFILE_DISABLE = "access-profile.disable";
    public static final String ACCESS_PROFILE_ASSIGN = "access-profile.assign";

    public static final String ATTRIBUTE_VIEW = "attribute.view";
    public static final String ATTRIBUTE_CREATE = "attribute.create";
    public static final String ATTRIBUTE_EDIT = "attribute.edit";
    public static final String ATTRIBUTE_DELETE = "attribute.delete";

    public static final String ANNOUNCEMENT_VIEW = "announcement.view";
    public static final String ANNOUNCEMENT_MANAGE = "announcement.manage";
    public static final String SERVER_VIEW = "server.view";
    public static final String SERVER_MANAGE = "server.manage";
    public static final String PREFERENCE_VIEW = "preference.view";
    public static final String PREFERENCE_EDIT = "preference.edit";

    public record Module(String key, Set<String> permissions) {
    }

    public static final List<Module> MODULES = List.of(
            new Module("map", Set.of(MAP_VIEW, MAP_DEVICES, MAP_FOLLOW, MAP_HISTORY)),
            new Module("devices", Set.of(
                    DEVICE_VIEW, DEVICE_CREATE, DEVICE_EDIT, DEVICE_DELETE, DEVICE_CHANGE_GROUP)),
            new Module("appearance", Set.of(
                    APPEARANCE_VIEW, CARD_IMAGE, MAP_MARKER, MARKER_3D,
                    MARKER_MODEL, MARKER_COLOR, CUSTOM_UPLOAD)),
            new Module("commands", Set.of(
                    COMMAND_VIEW, COMMAND_CREATE, COMMAND_EDIT, COMMAND_DELETE,
                    COMMAND_SEND, COMMAND_LOCATE, COMMAND_LOCK, COMMAND_UNLOCK)),
            new Module("reports", Set.of(REPORT_VIEW, REPORT_GENERATE, REPORT_EXPORT)),
            new Module("geofences", Set.of(
                    GEOFENCE_VIEW, GEOFENCE_CREATE, GEOFENCE_EDIT, GEOFENCE_DELETE)),
            new Module("groups", Set.of(GROUP_VIEW, GROUP_CREATE, GROUP_EDIT, GROUP_DELETE)),
            new Module("drivers", Set.of(
                    DRIVER_VIEW, DRIVER_CREATE, DRIVER_EDIT, DRIVER_DELETE)),
            new Module("calendars", Set.of(
                    CALENDAR_VIEW, CALENDAR_CREATE, CALENDAR_EDIT, CALENDAR_DELETE)),
            new Module("maintenance", Set.of(
                    MAINTENANCE_VIEW, MAINTENANCE_CREATE, MAINTENANCE_EDIT, MAINTENANCE_DELETE)),
            new Module("notifications", Set.of(
                    NOTIFICATION_VIEW, NOTIFICATION_CREATE, NOTIFICATION_EDIT, NOTIFICATION_DELETE)),
            new Module("users", Set.of(
                    USER_VIEW, USER_CREATE, USER_EDIT, USER_DELETE, USER_ASSIGN_PROFILE, USER_LINK_SCOPE)),
            new Module("access-profiles", Set.of(
                    ACCESS_PROFILE_VIEW, ACCESS_PROFILE_CREATE, ACCESS_PROFILE_EDIT,
                    ACCESS_PROFILE_DISABLE, ACCESS_PROFILE_ASSIGN)),
            new Module("attributes", Set.of(
                    ATTRIBUTE_VIEW, ATTRIBUTE_CREATE, ATTRIBUTE_EDIT, ATTRIBUTE_DELETE)),
            new Module("announcement", Set.of(ANNOUNCEMENT_VIEW, ANNOUNCEMENT_MANAGE)),
            new Module("server", Set.of(SERVER_VIEW, SERVER_MANAGE)),
            new Module("preferences", Set.of(PREFERENCE_VIEW, PREFERENCE_EDIT)));

    public static final Set<String> ALL;

    static {
        Set<String> permissions = new LinkedHashSet<>();
        MODULES.forEach(module -> permissions.addAll(module.permissions()));
        ALL = Set.copyOf(permissions);
    }

    private AccessPermissions() {
    }
}
