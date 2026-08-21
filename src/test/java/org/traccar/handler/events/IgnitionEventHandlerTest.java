package org.traccar.handler.events;

import org.junit.jupiter.api.Test;
import org.traccar.BaseTest;
import org.traccar.config.Keys;
import org.traccar.handler.CopyAttributesHandler;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class IgnitionEventHandlerTest extends BaseTest {

    @Test
    public void testIgnitionEventHandler() {
        Device device = new Device();
        device.setId(1);
        Position lastPosition = createPosition(0, null);
        Position position = createPosition(1, true);

        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.getObject(eq(Device.class), anyLong())).thenReturn(device);
        when(cacheManager.getPosition(anyLong())).thenReturn(lastPosition);

        IgnitionEventHandler ignitionEventHandler = new IgnitionEventHandler(cacheManager);
        List<Event> events = new ArrayList<>();
        ignitionEventHandler.analyzePosition(position, events::add);

        assertTrue(events.isEmpty());
    }

    @Test
    public void testIgnitionTransitionsWithIntermittentAttribute() {
        Device device = new Device();
        device.setId(1);
        device.getAttributes().put(Keys.PROCESSING_COPY_ATTRIBUTES.getKey(), Position.KEY_IGNITION);

        Position initialPosition = createPosition(0, false);
        AtomicReference<Position> cachedPosition = new AtomicReference<>(initialPosition);

        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.getObject(eq(Device.class), anyLong())).thenReturn(device);
        when(cacheManager.getPosition(anyLong())).thenAnswer(invocation -> cachedPosition.get());

        CopyAttributesHandler copyAttributesHandler = new CopyAttributesHandler(cacheManager);
        IgnitionEventHandler ignitionEventHandler = new IgnitionEventHandler(cacheManager);
        List<Event> events = new ArrayList<>();

        processPosition(copyAttributesHandler, ignitionEventHandler, cachedPosition, events, createPosition(1, true));
        processPosition(copyAttributesHandler, ignitionEventHandler, cachedPosition, events, createPosition(2, null));
        processPosition(copyAttributesHandler, ignitionEventHandler, cachedPosition, events, createPosition(3, null));
        processPosition(copyAttributesHandler, ignitionEventHandler, cachedPosition, events, createPosition(4, false));
        processPosition(copyAttributesHandler, ignitionEventHandler, cachedPosition, events, createPosition(5, false));
        processPosition(copyAttributesHandler, ignitionEventHandler, cachedPosition, events, createPosition(6, null));
        processPosition(copyAttributesHandler, ignitionEventHandler, cachedPosition, events, createPosition(7, true));

        assertEquals(3, events.size());
        assertEquals(Event.TYPE_IGNITION_ON, events.get(0).getType());
        assertEquals(Event.TYPE_IGNITION_OFF, events.get(1).getType());
        assertEquals(Event.TYPE_IGNITION_ON, events.get(2).getType());
    }

    private Position createPosition(long time, Boolean ignition) {
        Position position = new Position();
        position.setDeviceId(1);
        position.setFixTime(new Date(time));
        if (ignition != null) {
            position.set(Position.KEY_IGNITION, ignition);
        }
        return position;
    }

    private void processPosition(
            CopyAttributesHandler copyAttributesHandler, IgnitionEventHandler ignitionEventHandler,
            AtomicReference<Position> cachedPosition, List<Event> events, Position position) {
        copyAttributesHandler.handlePosition(position, filtered -> { });
        ignitionEventHandler.analyzePosition(position, events::add);
        cachedPosition.set(position);
    }

}
