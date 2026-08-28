package org.traccar.api.security;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AccessPermissionsTest {

    @Test
    public void testCatalogContainsUniquePermissionsForMigratedModules() {
        Set<String> collected = new HashSet<>();
        AccessPermissions.MODULES.forEach(module -> {
            module.permissions().forEach(permission -> assertTrue(collected.add(permission)));
        });
        assertEquals(collected, AccessPermissions.ALL);
        assertEquals(83, AccessPermissions.ALL.size());
        assertTrue(AccessPermissions.MODULES.stream().anyMatch(module -> module.key().equals("users")));
        assertTrue(AccessPermissions.MODULES.stream().anyMatch(module -> module.key().equals("devices")));
        assertTrue(AccessPermissions.MODULES.stream().anyMatch(module -> module.key().equals("reports")));
        assertTrue(AccessPermissions.MODULES.stream().anyMatch(module -> module.key().equals("account")));
    }
}
