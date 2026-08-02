/*
 * Copyright 2026 Rafael Malheiros Kersting
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.command;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.Command;
import org.traccar.model.Device;
import org.traccar.model.Group;
import org.traccar.model.Permission;
import org.traccar.model.User;
import org.traccar.helper.model.DeviceUtil;
import org.traccar.session.ConnectionManager;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Singleton
public class SystemCommandService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SystemCommandService.class);

    public static final String KEY_SYSTEM_DEFAULT = "systemDefault";
    public static final String KEY_ACTIVE = "systemDefaultActive";
    public static final String KEY_ORDER = "systemDefaultOrder";
    public static final String KEY_CONFIRMATION = "systemDefaultConfirmation";
    public static final String KEY_CRITICAL = "systemDefaultCritical";
    public static final String KEY_NEW_USERS = "systemDefaultNewUsers";
    public static final String KEY_EXISTING_USERS = "systemDefaultExistingUsers";
    public static final String KEY_PROFILES = "systemDefaultProfiles";
    public static final String KEY_CATEGORY = "systemDefaultCategory";
    public static final String KEY_SUMMARY = "systemDefaultSummary";
    public static final String KEY_USER_SCOPE = "systemDefaultUserScope";
    public static final String KEY_USER_IDS = "systemDefaultUserIds";
    public static final String KEY_USER_GROUP_IDS = "systemDefaultUserGroupIds";
    public static final String KEY_DEVICE_SCOPE = "systemDefaultDeviceScope";
    public static final String KEY_DEVICE_IDS = "systemDefaultDeviceIds";
    public static final String KEY_DEVICE_GROUP_IDS = "systemDefaultDeviceGroupIds";

    public static final String SCOPE_ALL = "all";
    public static final String SCOPE_USERS = "users";
    public static final String SCOPE_GROUPS = "groups";
    public static final String SCOPE_DEVICES = "devices";

    private final Storage storage;
    private final CacheManager cacheManager;
    private final ConnectionManager connectionManager;

    @Inject
    public SystemCommandService(
            Storage storage, CacheManager cacheManager, ConnectionManager connectionManager) {
        this.storage = storage;
        this.cacheManager = cacheManager;
        this.connectionManager = connectionManager;
    }

    public boolean isSystemDefault(Command command) {
        return command != null && command.getBoolean(KEY_SYSTEM_DEFAULT);
    }

    public boolean isActive(Command command) {
        return !isSystemDefault(command) || !command.hasAttribute(KEY_ACTIVE) || command.getBoolean(KEY_ACTIVE);
    }

    public boolean isCritical(Command command) {
        return isSystemDefault(command) && command.getBoolean(KEY_CRITICAL);
    }

    public boolean isSystemCommand(long commandId) throws StorageException {
        Command command = storage.getObject(Command.class, new Request(
                new Columns.All(), new Condition.Equals("id", commandId)));
        return isSystemDefault(command);
    }

    public List<Command> getSystemCommands(boolean newUsers) throws StorageException {
        return storage.getObjects(Command.class, new Request(new Columns.All()))
                .stream()
                .filter(this::isSystemDefault)
                .filter(this::isActive)
                .filter(command -> {
                    String key = newUsers ? KEY_NEW_USERS : KEY_EXISTING_USERS;
                    return !command.hasAttribute(key) || command.getBoolean(key);
                })
                .sorted(Comparator.comparingInt(command -> command.getInteger(KEY_ORDER)))
                .toList();
    }

    public List<Command> getSystemCommands(boolean newUsers, Collection<Long> commandIds) throws StorageException {
        return getSystemCommands(newUsers).stream()
                .filter(command -> commandIds == null || commandIds.isEmpty() || commandIds.contains(command.getId()))
                .toList();
    }

    public static List<Long> parseIds(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .map(Long::parseLong)
                .distinct()
                .toList();
    }

    public String getProfile(User user) {
        return user.getAdministrator() ? "administrator" : user.getManager() ? "manager" : "client";
    }

    public boolean matchesProfile(User user, Command command) {
        String profile = getProfile(user);
        String profiles = command.getString(KEY_PROFILES, "");
        return Arrays.stream(profiles.split(",")).map(String::trim).anyMatch(profile::equals);
    }

    public boolean matchesUserScope(User user, Command command) throws StorageException {
        String scope = command.getString(KEY_USER_SCOPE, SCOPE_ALL);
        if (SCOPE_USERS.equals(scope)) {
            return parseIds(command.getString(KEY_USER_IDS, "")).contains(user.getId());
        }
        if (SCOPE_GROUPS.equals(scope)) {
            for (long groupId : parseIds(command.getString(KEY_USER_GROUP_IDS, ""))) {
                if (!storage.getPermissions(User.class, user.getId(), Group.class, groupId).isEmpty()) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    public boolean isEligible(User user, Command command) throws StorageException {
        if (user == null || user.getDisabled() || user.getTemporary() || user.getReadonly()) {
            return false;
        }
        return matchesProfile(user, command) && matchesUserScope(user, command);
    }

    public boolean hasPermission(long userId, long commandId) throws StorageException {
        return !storage.getPermissions(User.class, userId, Command.class, commandId).isEmpty();
    }

    public boolean hasDeviceLink(long deviceId, long commandId) throws StorageException {
        return !storage.getPermissions(Device.class, deviceId, Command.class, commandId).isEmpty();
    }

    private void addPermission(Permission permission) throws Exception {
        storage.addPermission(permission);
        cacheManager.invalidatePermission(
                true, permission.getOwnerClass(), permission.getOwnerId(),
                permission.getPropertyClass(), permission.getPropertyId(), true);
        connectionManager.invalidatePermission(
                true, permission.getOwnerClass(), permission.getOwnerId(),
                permission.getPropertyClass(), permission.getPropertyId(), true);
    }

    private void removePermission(Permission permission) throws Exception {
        storage.removePermission(permission);
        cacheManager.invalidatePermission(
                true, permission.getOwnerClass(), permission.getOwnerId(),
                permission.getPropertyClass(), permission.getPropertyId(), false);
        connectionManager.invalidatePermission(
                true, permission.getOwnerClass(), permission.getOwnerId(),
                permission.getPropertyClass(), permission.getPropertyId(), false);
    }

    public AssignmentResult assignToUser(User user, boolean newUser, boolean applyDeviceLinks) {
        return assignToUser(user, newUser, applyDeviceLinks, List.of());
    }

    public AssignmentResult assignToUser(
            User user, boolean newUser, boolean applyDeviceLinks, Collection<Long> commandIds) {
        int created = 0;
        int existing = 0;
        int ignored = 0;
        List<Long> createdCommandIds = new ArrayList<>();
        List<Long> removedCommandIds = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        try {
            for (Command command : getSystemCommands(newUser, commandIds)) {
                if (!isEligible(user, command)) {
                    if (hasPermission(user.getId(), command.getId())) {
                        removePermission(new Permission(
                                User.class, user.getId(), Command.class, command.getId()));
                        removedCommandIds.add(command.getId());
                    }
                    ignored += 1;
                    continue;
                }
                if (hasPermission(user.getId(), command.getId())) {
                    existing += 1;
                } else {
                    addPermission(new Permission(User.class, user.getId(), Command.class, command.getId()));
                    created += 1;
                    createdCommandIds.add(command.getId());
                }
                if (applyDeviceLinks) {
                    linkAccessibleDevices(user, command, failures);
                }
            }
        } catch (Exception error) {
            LOGGER.warn("Failed to assign system commands to user {}", user.getId(), error);
            failures.add(error.getMessage());
        }
        return new AssignmentResult(
                created, existing, ignored, createdCommandIds, removedCommandIds, failures);
    }

    public AssignmentResult previewForUser(User user, boolean newUser) {
        return previewForUser(user, newUser, List.of());
    }

    public AssignmentResult previewForUser(User user, boolean newUser, Collection<Long> commandIds) {
        int created = 0;
        int existing = 0;
        int ignored = 0;
        List<String> failures = new ArrayList<>();
        try {
            for (Command command : getSystemCommands(newUser, commandIds)) {
                if (!isEligible(user, command)) {
                    ignored += 1;
                } else if (hasPermission(user.getId(), command.getId())) {
                    existing += 1;
                } else {
                    created += 1;
                }
            }
        } catch (Exception error) {
            failures.add(error.getMessage());
        }
        return new AssignmentResult(created, existing, ignored, List.of(), List.of(), failures);
    }

    private boolean isSelectedGroup(long groupId, List<Long> selectedGroups, Map<Long, Long> parents) {
        Set<Long> visited = new HashSet<>();
        while (groupId > 0 && visited.add(groupId)) {
            if (selectedGroups.contains(groupId)) {
                return true;
            }
            groupId = parents.getOrDefault(groupId, 0L);
        }
        return false;
    }

    public void reconcileDeviceLinks(Command command) throws Exception {
        if (!isSystemDefault(command)) {
            return;
        }
        String scope = command.getString(KEY_DEVICE_SCOPE, SCOPE_ALL);
        List<Long> selectedDeviceIds = parseIds(command.getString(KEY_DEVICE_IDS, ""));
        List<Long> selectedGroupIds = parseIds(command.getString(KEY_DEVICE_GROUP_IDS, ""));
        Map<Long, Long> groupParents = new HashMap<>();
        for (Group group : storage.getObjects(Group.class, new Request(new Columns.All()))) {
            groupParents.put(group.getId(), group.getGroupId());
        }
        for (Device device : storage.getObjects(Device.class, new Request(new Columns.All()))) {
            boolean allowed = SCOPE_ALL.equals(scope)
                    || SCOPE_DEVICES.equals(scope) && selectedDeviceIds.contains(device.getId())
                    || SCOPE_GROUPS.equals(scope)
                    && isSelectedGroup(device.getGroupId(), selectedGroupIds, groupParents);
            boolean linked = hasDeviceLink(device.getId(), command.getId());
            Permission permission = new Permission(
                    Device.class, device.getId(), Command.class, command.getId());
            if (allowed && !linked) {
                addPermission(permission);
            } else if (!allowed && linked) {
                removePermission(permission);
            }
        }
    }

    private void linkAccessibleDevices(User user, Command command, List<String> failures) throws Exception {
        String scope = command.getString(KEY_DEVICE_SCOPE, SCOPE_ALL);
        Collection<Device> devices;
        if (SCOPE_GROUPS.equals(scope)) {
            devices = DeviceUtil.getAccessibleDevices(storage, user.getId(), List.of(),
                    parseIds(command.getString(KEY_DEVICE_GROUP_IDS, "")));
        } else if (SCOPE_DEVICES.equals(scope)) {
            devices = DeviceUtil.getAccessibleDevices(storage, user.getId(),
                    parseIds(command.getString(KEY_DEVICE_IDS, "")), List.of());
        } else {
            devices = DeviceUtil.getAccessibleDevices(storage, user.getId(), List.of(), List.of());
        }
        for (Device device : devices) {
            try {
                // Link the catalog permission once. CommandResource applies protocol compatibility
                // dynamically, which also covers devices whose protocol is learned after assignment.
                if (!hasDeviceLink(device.getId(), command.getId())) {
                    addPermission(new Permission(Device.class, device.getId(), Command.class, command.getId()));
                }
            } catch (Exception error) {
                LOGGER.warn("Failed to link system command {} to device {}", command.getId(), device.getId(), error);
                failures.add("deviceId=" + device.getId() + ": " + error.getMessage());
            }
        }
    }

    public record AssignmentResult(
            int created, int existing, int ignored, List<Long> createdCommandIds,
            List<Long> removedCommandIds, List<String> failures) {
    }
}
