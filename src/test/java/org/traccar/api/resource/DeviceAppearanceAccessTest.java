/*
 * Copyright 2026 Rafael Malheiros Kersting
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

import org.junit.jupiter.api.Test;
import org.traccar.api.security.AccessControlService;
import org.traccar.api.security.AccessPermissions;
import org.traccar.api.security.PermissionsService;
import org.traccar.model.Server;
import org.traccar.model.User;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class DeviceAppearanceAccessTest {

    private static final long USER_ID = 10;
    private static final long DEVICE_ID = 20;

    @Test
    public void testDeviceReadonlyDoesNotBlockDedicatedAppearancePermission() throws Exception {
        PermissionsService permissionsService = mock(PermissionsService.class);
        AccessControlService accessControlService = mock(AccessControlService.class);
        User user = new User();
        user.setDeviceReadonly(true);
        when(permissionsService.getUser(USER_ID)).thenReturn(user);
        when(permissionsService.getServer()).thenReturn(new Server());
        when(accessControlService.getEffectiveAccess(USER_ID)).thenReturn(profileAccess());
        TestDeviceResource resource = new TestDeviceResource(permissionsService, accessControlService);

        assertDoesNotThrow(() -> invokeAppearanceWrite(resource, AccessPermissions.CARD_IMAGE));
        verify(accessControlService).checkPermission(USER_ID, AccessPermissions.APPEARANCE_VIEW);
        verify(accessControlService).checkPermission(USER_ID, AccessPermissions.CARD_IMAGE);
    }

    @Test
    public void testReadonlyStillBlocksAppearanceMutation() throws Exception {
        PermissionsService permissionsService = mock(PermissionsService.class);
        AccessControlService accessControlService = mock(AccessControlService.class);
        User user = new User();
        user.setReadonly(true);
        when(permissionsService.getUser(USER_ID)).thenReturn(user);
        when(permissionsService.getServer()).thenReturn(new Server());
        when(accessControlService.getEffectiveAccess(USER_ID)).thenReturn(profileAccess());
        TestDeviceResource resource = new TestDeviceResource(permissionsService, accessControlService);

        InvocationTargetException error = assertThrows(
                InvocationTargetException.class,
                () -> invokeAppearanceWrite(resource, AccessPermissions.CARD_IMAGE));
        assertInstanceOf(SecurityException.class, error.getCause());
    }

    private AccessControlService.EffectiveAccess profileAccess() {
        return new AccessControlService.EffectiveAccess(
                1, "Cliente", Set.of(AccessPermissions.APPEARANCE_VIEW, AccessPermissions.CARD_IMAGE),
                Set.of(AccessPermissions.APPEARANCE_VIEW, AccessPermissions.CARD_IMAGE),
                Set.of(), Set.of(), Set.of(), false);
    }

    private void invokeAppearanceWrite(TestDeviceResource resource, String permission) throws Exception {
        Method method = DeviceResource.class.getDeclaredMethod("checkAppearanceWrite", long.class, String.class);
        method.setAccessible(true);
        method.invoke(resource, DEVICE_ID, permission);
    }

    private static class TestDeviceResource extends DeviceResource {

        TestDeviceResource(PermissionsService permissionsService, AccessControlService accessControlService) {
            this.permissionsService = permissionsService;
            this.accessControlService = accessControlService;
        }

        @Override
        protected long getUserId() {
            return USER_ID;
        }
    }
}
