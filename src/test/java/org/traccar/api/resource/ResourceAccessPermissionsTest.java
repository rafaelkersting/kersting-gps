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
import org.traccar.api.security.AccessPermissions;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ResourceAccessPermissionsTest {

    @Test
    public void testDriverResourcePermissionMapping() {
        TestDriverResource resource = new TestDriverResource();
        assertEquals(AccessPermissions.DRIVER_VIEW, resource.view());
        assertEquals(AccessPermissions.DRIVER_CREATE, resource.create());
        assertEquals(AccessPermissions.DRIVER_EDIT, resource.edit());
        assertEquals(AccessPermissions.DRIVER_DELETE, resource.delete());
    }

    @Test
    public void testCalendarResourcePermissionMapping() {
        TestCalendarResource resource = new TestCalendarResource();
        assertEquals(AccessPermissions.CALENDAR_VIEW, resource.view());
        assertEquals(AccessPermissions.CALENDAR_CREATE, resource.create());
        assertEquals(AccessPermissions.CALENDAR_EDIT, resource.edit());
        assertEquals(AccessPermissions.CALENDAR_DELETE, resource.delete());
    }

    @Test
    public void testMaintenanceResourcePermissionMapping() {
        TestMaintenanceResource resource = new TestMaintenanceResource();
        assertEquals(AccessPermissions.MAINTENANCE_VIEW, resource.view());
        assertEquals(AccessPermissions.MAINTENANCE_CREATE, resource.create());
        assertEquals(AccessPermissions.MAINTENANCE_EDIT, resource.edit());
        assertEquals(AccessPermissions.MAINTENANCE_DELETE, resource.delete());
    }

    @Test
    public void testDeviceResourcePermissionMapping() {
        assertCrud(
                new TestDeviceResource(), AccessPermissions.DEVICE_VIEW, AccessPermissions.DEVICE_CREATE,
                AccessPermissions.DEVICE_EDIT, AccessPermissions.DEVICE_DELETE);
    }

    @Test
    public void testGroupResourcePermissionMapping() {
        assertCrud(
                new TestGroupResource(), AccessPermissions.GROUP_VIEW, AccessPermissions.GROUP_CREATE,
                AccessPermissions.GROUP_EDIT, AccessPermissions.GROUP_DELETE);
    }

    @Test
    public void testGeofenceResourcePermissionMapping() {
        assertCrud(
                new TestGeofenceResource(), AccessPermissions.GEOFENCE_VIEW, AccessPermissions.GEOFENCE_CREATE,
                AccessPermissions.GEOFENCE_EDIT, AccessPermissions.GEOFENCE_DELETE);
    }

    @Test
    public void testNotificationResourcePermissionMapping() {
        assertCrud(
                new TestNotificationResource(), AccessPermissions.NOTIFICATION_VIEW,
                AccessPermissions.NOTIFICATION_CREATE, AccessPermissions.NOTIFICATION_EDIT,
                AccessPermissions.NOTIFICATION_DELETE);
    }

    @Test
    public void testCommandResourcePermissionMapping() {
        assertCrud(
                new TestCommandResource(), AccessPermissions.COMMAND_VIEW, AccessPermissions.COMMAND_CREATE,
                AccessPermissions.COMMAND_EDIT, AccessPermissions.COMMAND_DELETE);
    }

    @Test
    public void testAttributeResourcePermissionMapping() {
        assertCrud(
                new TestAttributeResource(), AccessPermissions.ATTRIBUTE_VIEW, AccessPermissions.ATTRIBUTE_CREATE,
                AccessPermissions.ATTRIBUTE_EDIT, AccessPermissions.ATTRIBUTE_DELETE);
    }

    @Test
    public void testUserResourceDistinguishesOwnAccountFromOtherUser() {
        TestUserResource resource = new TestUserResource();
        assertEquals(AccessPermissions.ACCOUNT_VIEW, resource.view(10));
        assertEquals(AccessPermissions.USER_VIEW, resource.view(11));
    }

    private void assertCrud(
            TestResource resource, String view, String create, String edit, String delete) {
        assertEquals(view, resource.view());
        assertEquals(create, resource.create());
        assertEquals(edit, resource.edit());
        assertEquals(delete, resource.delete());
    }

    private interface TestResource {
        String view();
        String create();
        String edit();
        String delete();
    }

    private static class TestDriverResource extends DriverResource implements TestResource {

        public String view() {
            return getViewAccessPermission();
        }

        public String create() {
            return getCreateAccessPermission();
        }

        public String edit() {
            return getEditAccessPermission();
        }

        public String delete() {
            return getDeleteAccessPermission();
        }
    }

    private static class TestCalendarResource extends CalendarResource implements TestResource {

        public String view() {
            return getViewAccessPermission();
        }

        public String create() {
            return getCreateAccessPermission();
        }

        public String edit() {
            return getEditAccessPermission();
        }

        public String delete() {
            return getDeleteAccessPermission();
        }
    }

    private static class TestMaintenanceResource extends MaintenanceResource implements TestResource {

        public String view() {
            return getViewAccessPermission();
        }

        public String create() {
            return getCreateAccessPermission();
        }

        public String edit() {
            return getEditAccessPermission();
        }

        public String delete() {
            return getDeleteAccessPermission();
        }
    }

    private static class TestDeviceResource extends DeviceResource implements TestResource {
        public String view() { return getViewAccessPermission(); }
        public String create() { return getCreateAccessPermission(); }
        public String edit() { return getEditAccessPermission(); }
        public String delete() { return getDeleteAccessPermission(); }
    }

    private static class TestGroupResource extends GroupResource implements TestResource {
        public String view() { return getViewAccessPermission(); }
        public String create() { return getCreateAccessPermission(); }
        public String edit() { return getEditAccessPermission(); }
        public String delete() { return getDeleteAccessPermission(); }
    }

    private static class TestGeofenceResource extends GeofenceResource implements TestResource {
        public String view() { return getViewAccessPermission(); }
        public String create() { return getCreateAccessPermission(); }
        public String edit() { return getEditAccessPermission(); }
        public String delete() { return getDeleteAccessPermission(); }
    }

    private static class TestNotificationResource extends NotificationResource implements TestResource {
        public String view() { return getViewAccessPermission(); }
        public String create() { return getCreateAccessPermission(); }
        public String edit() { return getEditAccessPermission(); }
        public String delete() { return getDeleteAccessPermission(); }
    }

    private static class TestCommandResource extends CommandResource implements TestResource {
        public String view() { return getViewAccessPermission(); }
        public String create() { return getCreateAccessPermission(); }
        public String edit() { return getEditAccessPermission(); }
        public String delete() { return getDeleteAccessPermission(); }
    }

    private static class TestAttributeResource extends AttributeResource implements TestResource {
        public String view() { return getViewAccessPermission(); }
        public String create() { return getCreateAccessPermission(); }
        public String edit() { return getEditAccessPermission(); }
        public String delete() { return getDeleteAccessPermission(); }
    }

    private static class TestUserResource extends UserResource {

        @Override
        protected long getUserId() {
            return 10;
        }

        public String view(long id) {
            return getViewAccessPermission(id);
        }
    }
}
