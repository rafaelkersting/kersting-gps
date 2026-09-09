package org.traccar.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.database.NotificationManager;
import org.traccar.model.DemoSession;
import org.traccar.model.Device;
import org.traccar.session.ConnectionManager;
import org.traccar.storage.MemoryStorage;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Request;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

public class DemoSimulatorServiceTest {

    @Test
    public void testRejectsPublicOrHttpsProtocolEndpoint() {
        assertThrows(Exception.class, () -> simulator("https://127.0.0.1:5055/").start());
        assertThrows(Exception.class, () -> simulator("http://8.8.8.8:5055/").start());
    }

    @Test
    public void testAcceptsLoopbackEndpoint() throws Exception {
        DemoSimulatorService simulator = simulator("http://127.0.0.1:5055/");
        simulator.start();
        simulator.stop();
    }

    @Test
    public void testRejectsDeviceNotExplicitlyOwnedBySession() throws Exception {
        Config config = config("http://127.0.0.1:5055/");
        MemoryStorage storage = new MemoryStorage();
        Device device = new Device();
        device.setName("Outro dispositivo");
        device.setUniqueId("REAL-123");
        device.setId(storage.addObject(device, new Request(new Columns.Exclude("id"))));
        DemoSession session = new DemoSession();
        session.setId(42);
        session.setDeviceId(device.getId());
        session.setExpiresAt(new Date(System.currentTimeMillis() + 60_000));
        DemoSimulatorService simulator = new DemoSimulatorService(
                config, storage, new DemoRouteCatalog(new ObjectMapper()),
                mock(ConnectionManager.class), mock(NotificationManager.class));
        simulator.start();

        DemoException error = assertThrows(
                DemoException.class, () -> simulator.start(session, DemoScenario.URBAN));

        assertEquals(403, error.getStatus());
        simulator.stop();
    }

    private DemoSimulatorService simulator(String endpoint) {
        return new DemoSimulatorService(
                config(endpoint), new MemoryStorage(), new DemoRouteCatalog(new ObjectMapper()),
                mock(ConnectionManager.class), mock(NotificationManager.class));
    }

    private Config config(String endpoint) {
        Config config = new Config();
        config.setString(Keys.DEMO_SIMULATOR_URL, endpoint);
        return config;
    }
}
