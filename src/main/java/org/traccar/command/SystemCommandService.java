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
import org.traccar.model.Permission;
import org.traccar.model.User;
import org.traccar.session.ConnectionManager;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

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
        return !isSystemDefault(command) || command.getBoolean(KEY_ACTIVE);
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
                .filter(command -> command.getBoolean(newUsers ? KEY_NEW_USERS : KEY_EXISTING_USERS))
                .sorted(Comparator.comparingInt(command -> command.getInteger(KEY_ORDER)))
                .toList();
    }

    public boolean isEligible(User user, Command command) {
        if (user == null || user.getDisabled() || user.getTemporary() || user.getReadonly()) {
            return false;
        }
        String profile = user.getAdministrator() ? "administrator" : user.getManager() ? "manager" : "client";
        String profiles = command.getString(KEY_PROFILES, "administrator");
        return Arrays.stream(profiles.split(",")).map(String::trim).anyMatch(profile::equals);
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

    public AssignmentResult assignToUser(User user, boolean newUser, boolean applyDeviceLinks) {
        int created = 0;
        int existing = 0;
        int ignored = 0;
        List<Long> createdCommandIds = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        try {
            for (Command command : getSystemCommands(newUser)) {
                if (!isEligible(user, command)) {
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
        return new AssignmentResult(created, existing, ignored, createdCommandIds, failures);
    }

    public AssignmentResult previewForUser(User user, boolean newUser) {
        int created = 0;
        int existing = 0;
        int ignored = 0;
        List<String> failures = new ArrayList<>();
        try {
            for (Command command : getSystemCommands(newUser)) {
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
        return new AssignmentResult(created, existing, ignored, List.of(), failures);
    }

    private void linkAccessibleDevices(User user, Command command, List<String> failures) throws Exception {
        List<Device> devices = storage.getObjects(Device.class, new Request(
                new Columns.All(), new Condition.Permission(User.class, user.getId(), Device.class)));
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
            int created, int existing, int ignored, List<Long> createdCommandIds, List<String> failures) {
    }
}
