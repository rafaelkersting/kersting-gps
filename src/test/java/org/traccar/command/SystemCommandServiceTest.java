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

import org.junit.jupiter.api.Test;
import org.traccar.model.Command;
import org.traccar.model.User;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SystemCommandServiceTest {

    private final SystemCommandService service = new SystemCommandService(null, null, null);

    private Command command(String profiles) {
        Command command = new Command();
        command.set(SystemCommandService.KEY_SYSTEM_DEFAULT, true);
        command.set(SystemCommandService.KEY_ACTIVE, true);
        command.set(SystemCommandService.KEY_PROFILES, profiles);
        return command;
    }

    @Test
    public void testSystemCommandMetadata() {
        Command command = command("administrator,manager");
        assertTrue(service.isSystemDefault(command));
        assertTrue(service.isActive(command));
        command.set(SystemCommandService.KEY_ACTIVE, false);
        assertFalse(service.isActive(command));
    }

    @Test
    public void testProfileEligibility() {
        User administrator = new User();
        administrator.setAdministrator(true);
        assertTrue(service.isEligible(administrator, command("administrator")));

        User manager = new User();
        manager.setUserLimit(1);
        assertTrue(service.isEligible(manager, command("manager")));
        assertFalse(service.isEligible(manager, command("administrator")));

        User client = new User();
        assertTrue(service.isEligible(client, command("client")));
        client.setReadonly(true);
        assertFalse(service.isEligible(client, command("client")));
    }

}
