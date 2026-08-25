/*
 * Copyright 2015 - 2026 Anton Tananaev (anton@traccar.org)
 * Copyright 2016 Gabor Somogyi (gabor.g.somogyi@gmail.com)
 * Copyright 2017 Andrey Kunitsyn (andrey@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.api.resource;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.BaseProtocol;
import org.traccar.ServerManager;
import org.traccar.api.ExtendedObjectResource;
import org.traccar.api.security.AccessPermissions;
import org.traccar.command.CommandSender;
import org.traccar.command.CommandSenderManager;
import org.traccar.command.SystemCommandService;
import org.traccar.database.CommandsManager;
import org.traccar.helper.LogAction;
import org.traccar.helper.model.DeviceUtil;
import org.traccar.model.Command;
import org.traccar.model.Device;
import org.traccar.model.Group;
import org.traccar.model.Position;
import org.traccar.model.QueuedCommand;
import org.traccar.model.Typed;
import org.traccar.model.User;
import org.traccar.model.UserRestrictions;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Path("commands")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CommandResource extends ExtendedObjectResource<Command> {

    private static final Logger LOGGER = LoggerFactory.getLogger(CommandResource.class);

    @Inject
    private CommandsManager commandsManager;

    @Inject
    private ServerManager serverManager;

    @Inject
    private LogAction actionLogger;

    @Inject
    private CommandSenderManager commandSenderManager;

    @Inject
    private SystemCommandService systemCommandService;

    @Context
    private HttpServletRequest request;

    public CommandResource() {
        super(Command.class, "description", List.of("description"));
    }

    @Override
    protected String getViewAccessPermission() {
        return AccessPermissions.COMMAND_VIEW;
    }

    @Override
    protected String getCreateAccessPermission() {
        return AccessPermissions.COMMAND_CREATE;
    }

    @Override
    protected String getEditAccessPermission() {
        return AccessPermissions.COMMAND_EDIT;
    }

    @Override
    protected String getDeleteAccessPermission() {
        return AccessPermissions.COMMAND_DELETE;
    }

    private BaseProtocol getDeviceProtocol(long deviceId) throws StorageException {
        Position position = storage.getObject(Position.class, new Request(
                new Columns.All(), new Condition.LatestPositions(deviceId)));
        if (position != null) {
            return serverManager.getProtocol(position.getProtocol());
        } else {
            return null;
        }
    }

    private void ensureVehicleStopped(long deviceId) throws StorageException {
        Position position = storage.getObject(Position.class, new Request(
                new Columns.All(), new Condition.LatestPositions(deviceId)));
        if (position != null && position.getSpeed() > 0.5) {
            throw new WebApplicationException("Vehicle is moving", Response.Status.CONFLICT);
        }
    }

    @GET
    @Path("send")
    public Collection<Command> get(@QueryParam("deviceId") long deviceId) throws StorageException {
        checkAccessPermission(AccessPermissions.COMMAND_VIEW);
        permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        BaseProtocol protocol = getDeviceProtocol(deviceId);

        var commands = storage.getObjects(baseClass, new Request(
                new Columns.All(),
                Condition.merge(List.of(
                        new Condition.Permission(User.class, getUserId(), baseClass),
                        new Condition.Permission(Device.class, deviceId, baseClass)
                ))));

        return commands.stream().filter(systemCommandService::isActive).filter(command -> {
            String type = command.getType();
            if (protocol != null) {
                return command.getTextChannel() && protocol.getSupportedTextCommands().contains(type)
                        || !command.getTextChannel() && protocol.getSupportedDataCommands().contains(type);
            } else {
                return type.equals(Command.TYPE_CUSTOM);
            }
        }).sorted(Comparator.comparingInt(command -> command.getInteger(SystemCommandService.KEY_ORDER)))
                .toList();
    }

    @POST
    @Path("send")
    public Response send(
            Command entity, @QueryParam("groupId") long groupId,
            @QueryParam("confirmed") boolean confirmed) throws Exception {
        checkAccessPermission(AccessPermissions.COMMAND_SEND);
        if (entity.getId() > 0) {
            permissionsService.checkPermission(baseClass, getUserId(), entity.getId());
            long deviceId = entity.getDeviceId();
            entity = storage.getObject(baseClass, new Request(
                    new Columns.All(), new Condition.Equals("id", entity.getId())));
            entity.setDeviceId(deviceId);
        } else {
            permissionsService.checkRestriction(getUserId(), UserRestrictions::getLimitCommands);
        }

        if (systemCommandService.isCritical(entity)) {
            if (!confirmed) {
                throw new WebApplicationException("Critical command confirmation required", Response.Status.CONFLICT);
            }
            if (Command.TYPE_ENGINE_STOP.equals(entity.getType()) && groupId == 0) {
                ensureVehicleStopped(entity.getDeviceId());
            }
        }
        if (Command.TYPE_ENGINE_STOP.equals(entity.getType())) {
            checkAccessPermission(AccessPermissions.COMMAND_LOCK);
        } else if (Command.TYPE_ENGINE_RESUME.equals(entity.getType())) {
            checkAccessPermission(AccessPermissions.COMMAND_UNLOCK);
        }

        if (groupId > 0) {
            permissionsService.checkPermission(Group.class, getUserId(), groupId);
            var devices = DeviceUtil.getAccessibleDevices(storage, getUserId(), List.of(), List.of(groupId));
            if (systemCommandService.isCritical(entity) && Command.TYPE_ENGINE_STOP.equals(entity.getType())) {
                for (Device device : devices) {
                    ensureVehicleStopped(device.getId());
                }
            }
            List<QueuedCommand> queuedCommands = new ArrayList<>();
            for (Device device : devices) {
                Command command = QueuedCommand.fromCommand(entity).toCommand();
                command.setDeviceId(device.getId());
                QueuedCommand queuedCommand = commandsManager.sendCommand(command);
                if (queuedCommand != null) {
                    queuedCommands.add(queuedCommand);
                }
            }
            if (!queuedCommands.isEmpty()) {
                actionLogger.command(request, getUserId(), groupId, entity.getDeviceId(), entity.getType());
                return Response.accepted(queuedCommands).build();
            }
        } else {
            permissionsService.checkPermission(Device.class, getUserId(), entity.getDeviceId());
            QueuedCommand queuedCommand = commandsManager.sendCommand(entity);
            if (queuedCommand != null) {
                actionLogger.command(request, getUserId(), groupId, entity.getDeviceId(), entity.getType());
                return Response.accepted(queuedCommand).build();
            }
        }

        actionLogger.command(request, getUserId(), groupId, entity.getDeviceId(), entity.getType());
        return Response.ok(entity).build();
    }

    @Override
    @POST
    public Response add(Command entity) throws Exception {
        if (systemCommandService.isSystemDefault(entity)) {
            permissionsService.checkAdmin(getUserId());
            validateSystemCommand(entity);
        }
        Response response = super.add(entity);
        systemCommandService.reconcileDeviceLinks(entity);
        return response;
    }

    @Override
    @Path("{id}")
    @PUT
    public Response update(Command entity) throws Exception {
        Command existing = storage.getObject(Command.class, new Request(
                new Columns.All(), new Condition.Equals("id", entity.getId())));
        if (systemCommandService.isSystemDefault(existing) || systemCommandService.isSystemDefault(entity)) {
            permissionsService.checkAdmin(getUserId());
            validateSystemCommand(entity);
        }
        Response response = super.update(entity);
        systemCommandService.reconcileDeviceLinks(entity);
        return response;
    }

    private void validateSystemCommand(Command command) {
        if (systemCommandService.isSystemDefault(command)
                && systemCommandService.isActive(command)
                && command.getString(SystemCommandService.KEY_PROFILES, "").isBlank()) {
            throw new WebApplicationException(
                    "Selecione pelo menos um perfil autorizado.", Response.Status.BAD_REQUEST);
        }
    }

    @Override
    @Path("{id}")
    @DELETE
    public Response remove(@PathParam("id") long id) throws Exception {
        if (systemCommandService.isSystemCommand(id)) {
            permissionsService.checkAdmin(getUserId());
        }
        return super.remove(id);
    }

    private Command newSystemCommand(
            String description, String type, int order, boolean critical,
            String profiles, String category, String summary) {
        Command command = new Command();
        command.setDescription(description);
        command.setType(type);
        command.set(SystemCommandService.KEY_SYSTEM_DEFAULT, true);
        command.set(SystemCommandService.KEY_ACTIVE, true);
        command.set(SystemCommandService.KEY_ORDER, order);
        command.set(SystemCommandService.KEY_CONFIRMATION, critical);
        command.set(SystemCommandService.KEY_CRITICAL, critical);
        command.set(SystemCommandService.KEY_NEW_USERS, true);
        command.set(SystemCommandService.KEY_EXISTING_USERS, true);
        command.set(SystemCommandService.KEY_PROFILES, profiles);
        command.set(SystemCommandService.KEY_CATEGORY, category);
        command.set(SystemCommandService.KEY_SUMMARY, summary);
        command.set(SystemCommandService.KEY_USER_SCOPE, SystemCommandService.SCOPE_ALL);
        command.set(SystemCommandService.KEY_DEVICE_SCOPE, SystemCommandService.SCOPE_ALL);
        return command;
    }

    private void normalizeLegacyEngineStop(Command command) throws StorageException {
        if (!Command.TYPE_ENGINE_STOP.equals(command.getType())) {
            return;
        }
        boolean changed = false;
        if ("Desligar Motor".equalsIgnoreCase(command.getDescription())) {
            command.setDescription("Bloquear Motor");
            changed = true;
        }
        if (command.getString(SystemCommandService.KEY_PROFILES, "").isBlank()) {
            command.set(SystemCommandService.KEY_PROFILES, "administrator,manager");
            changed = true;
        }
        if (!command.getBoolean(SystemCommandService.KEY_CRITICAL)) {
            command.set(SystemCommandService.KEY_CRITICAL, true);
            command.set(SystemCommandService.KEY_CONFIRMATION, true);
            changed = true;
        }
        if (!"security".equals(command.getString(SystemCommandService.KEY_CATEGORY))) {
            command.set(SystemCommandService.KEY_CATEGORY, "security");
            changed = true;
        }
        if (changed) {
            storage.updateObject(command, new Request(
                    new Columns.Exclude("id"), new Condition.Equals("id", command.getId())));
        }
    }

    @POST
    @Path("defaults/bootstrap")
    public Collection<Command> bootstrapDefaults() throws Exception {
        permissionsService.checkAdmin(getUserId());
        List<Command> definitions = List.of(
                newSystemCommand("Solicitar localização agora", Command.TYPE_POSITION_SINGLE, 10, false,
                        "administrator,manager,client", "location", "Solicita uma nova posição ao rastreador."),
                newSystemCommand("Reiniciar rastreador", Command.TYPE_REBOOT_DEVICE, 20, false,
                        "administrator,manager", "equipment", "Solicita a reinicialização do equipamento."),
                newSystemCommand("Bloquear Motor", Command.TYPE_ENGINE_STOP, 30, true,
                        "administrator,manager", "security", "Solicita o bloqueio seguro do veículo."),
                newSystemCommand("Desbloquear motor", Command.TYPE_ENGINE_RESUME, 40, true,
                        "administrator", "security", "Solicita a liberação do bloqueio do veículo."));
        List<Command> existing = storage.getObjects(Command.class, new Request(new Columns.All()));
        List<Command> result = new ArrayList<>();
        for (Command definition : definitions) {
            Command found = existing.stream()
                    .filter(systemCommandService::isSystemDefault)
                    .filter(command -> command.getType().equals(definition.getType()))
                    .findFirst().orElse(null);
            if (found == null) {
                super.add(definition);
                systemCommandService.reconcileDeviceLinks(definition);
                result.add(definition);
            } else {
                normalizeLegacyEngineStop(found);
                systemCommandService.reconcileDeviceLinks(found);
                result.add(found);
            }
        }
        return result;
    }

    private boolean matchesScope(User user, DefaultCommandApplyRequest scope) throws StorageException {
        if (scope == null || (scope.userIds().isEmpty() && scope.groupIds().isEmpty())) {
            return true;
        }
        if (scope.userIds().contains(user.getId())) {
            return true;
        }
        for (long groupId : scope.groupIds()) {
            if (!storage.getPermissions(User.class, user.getId(), Group.class, groupId).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private DefaultCommandSummary summarize(boolean apply, DefaultCommandApplyRequest scope) throws Exception {
        permissionsService.checkAdmin(getUserId());
        DefaultCommandApplyRequest effectiveScope = scope != null
                ? scope : new DefaultCommandApplyRequest(List.of(), List.of(), List.of());
        List<User> users = storage.getObjects(User.class, new Request(new Columns.All())).stream()
                .filter(user -> {
                    try {
                        return matchesScope(user, effectiveScope);
                    } catch (StorageException error) {
                        throw new WebApplicationException(error);
                    }
                })
                .toList();
        int eligibleUsers = 0;
        int created = 0;
        int existing = 0;
        int ignoredUsers = 0;
        List<String> failures = new ArrayList<>();
        for (User user : users) {
            SystemCommandService.AssignmentResult result = apply
                    ? systemCommandService.assignToUser(user, false, true, effectiveScope.commandIds())
                    : systemCommandService.previewForUser(user, false, effectiveScope.commandIds());
            created += result.created();
            existing += result.existing();
            if (result.created() > 0 || result.existing() > 0) {
                eligibleUsers += 1;
            } else if (result.ignored() > 0) {
                ignoredUsers += 1;
            }
            result.failures().forEach(failure -> failures.add("userId=" + user.getId() + ": " + failure));
            if (apply) {
                for (long commandId : result.createdCommandIds()) {
                    actionLogger.link(request, getUserId(), User.class, user.getId(), Command.class, commandId);
                }
                for (long commandId : result.removedCommandIds()) {
                    actionLogger.unlink(request, getUserId(), User.class, user.getId(), Command.class, commandId);
                }
            }
        }
        List<Map<String, Object>> commands = systemCommandService
                .getSystemCommands(false, effectiveScope.commandIds()).stream()
                .map(command -> Map.<String, Object>of(
                        "id", command.getId(), "description", command.getDescription(), "type", command.getType()))
                .toList();
        return new DefaultCommandSummary(eligibleUsers, created, existing, ignoredUsers, commands, failures);
    }

    @GET
    @Path("defaults/preview")
    public DefaultCommandSummary previewDefaults() throws Exception {
        return summarize(false, new DefaultCommandApplyRequest(List.of(), List.of(), List.of()));
    }

    @POST
    @Path("defaults/preview")
    public DefaultCommandSummary previewDefaults(DefaultCommandApplyRequest body) throws Exception {
        return summarize(false, body);
    }

    @POST
    @Path("defaults/apply")
    public DefaultCommandSummary applyDefaults(DefaultCommandApplyRequest body) throws Exception {
        return summarize(true, body);
    }

    public record DefaultCommandApplyRequest(List<Long> userIds, List<Long> groupIds, List<Long> commandIds) {

        public DefaultCommandApplyRequest {
            userIds = userIds != null ? userIds : List.of();
            groupIds = groupIds != null ? groupIds : List.of();
            commandIds = commandIds != null ? commandIds : List.of();
        }
    }

    @GET
    @Path("defaults/options")
    public DefaultCommandOptions getDefaultCommandOptions() throws Exception {
        permissionsService.checkAdmin(getUserId());
        List<Group> groups = storage.getObjects(Group.class, new Request(new Columns.All()));
        List<UserOption> users = new ArrayList<>();
        for (User user : storage.getObjects(User.class, new Request(new Columns.All()))) {
            List<Long> groupIds = groups.stream().filter(group -> {
                try {
                    return !storage.getPermissions(User.class, user.getId(), Group.class, group.getId()).isEmpty();
                } catch (StorageException error) {
                    throw new WebApplicationException(error);
                }
            }).map(Group::getId).toList();
            List<String> groupNames = groups.stream()
                    .filter(group -> groupIds.contains(group.getId())).map(Group::getName).toList();
            users.add(new UserOption(
                    user.getId(), user.getName(), user.getEmail(), systemCommandService.getProfile(user),
                    groupIds, groupNames, user.getDisabled(), user.getReadonly(), user.getTemporary()));
        }
        List<SelectionOption> groupOptions = groups.stream()
                .map(group -> new SelectionOption(group.getId(), group.getName(), group.getGroupId())).toList();
        List<SelectionOption> deviceOptions = storage.getObjects(Device.class, new Request(new Columns.All())).stream()
                .map(device -> new SelectionOption(device.getId(), device.getName(), device.getGroupId())).toList();
        return new DefaultCommandOptions(users, groupOptions, deviceOptions);
    }

    public record UserOption(
            long id, String name, String email, String profile, List<Long> groupIds, List<String> groupNames,
            boolean disabled, boolean readonly, boolean temporary) {
    }

    public record SelectionOption(long id, String name, long groupId) {
    }

    public record DefaultCommandOptions(
            List<UserOption> users, List<SelectionOption> groups, List<SelectionOption> devices) {
    }

    public record DefaultCommandSummary(
            int users, int created, int existing, int ignoredUsers,
            List<Map<String, Object>> commands, List<String> failures) {
    }

    @GET
    @Path("types")
    public Collection<Typed> get(
            @QueryParam("deviceId") long deviceId,
            @QueryParam("textChannel") boolean textChannel) throws StorageException {
        if (deviceId != 0) {
            permissionsService.checkPermission(Device.class, getUserId(), deviceId);

            Device device = storage.getObject(Device.class, new Request(
                    new Columns.All(), new Condition.Equals("id", deviceId)));
            CommandSender sender = commandSenderManager.getSender(device);
            if (sender != null) {
                return sender.getSupportedCommands().stream().map(Typed::new).toList();
            }

            BaseProtocol protocol = getDeviceProtocol(deviceId);
            if (protocol != null) {
                if (textChannel) {
                    return protocol.getSupportedTextCommands().stream().map(Typed::new).toList();
                } else {
                    return protocol.getSupportedDataCommands().stream().map(Typed::new).toList();
                }
            } else {
                return List.of(new Typed(Command.TYPE_CUSTOM));
            }
        } else {
            List<Typed> result = new ArrayList<>();
            Field[] fields = Command.class.getDeclaredFields();
            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers()) && field.getName().startsWith("TYPE_")) {
                    try {
                        result.add(new Typed(field.get(null).toString()));
                    } catch (IllegalArgumentException | IllegalAccessException error) {
                        LOGGER.warn("Get command types error", error);
                    }
                }
            }
            return result;
        }
    }

}
