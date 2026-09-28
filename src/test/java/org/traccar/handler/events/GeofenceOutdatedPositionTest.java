/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
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
package org.traccar.handler.events;

import org.junit.jupiter.api.Test;
import org.traccar.BaseTest;
import org.traccar.model.Event;
import org.traccar.model.Geofence;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class GeofenceOutdatedPositionTest extends BaseTest {

    private static final long DEVICE_ID = 1;
    private static final long GEOFENCE_ID = 2;

    private Position createPosition(long id, long time, boolean outdated, Long... geofenceIds) {
        Position position = new Position();
        position.setId(id);
        position.setDeviceId(DEVICE_ID);
        position.setTime(new Date(time));
        position.setOutdated(outdated);
        position.setGeofenceIds(List.of(geofenceIds));
        return position;
    }

    private CacheManager createCacheManager(Position lastPosition) {
        Geofence geofence = new Geofence();
        geofence.setId(GEOFENCE_ID);

        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.getPosition(anyLong())).thenReturn(lastPosition);
        when(cacheManager.getObject(eq(Geofence.class), eq(GEOFENCE_ID))).thenReturn(geofence);
        return cacheManager;
    }

    @Test
    public void testOutdatedPositionDoesNotTriggerEnter() {
        Position lastPosition = createPosition(10, 1000, false);
        Position position = createPosition(11, 1000, true, GEOFENCE_ID);
        GeofenceEventHandler handler = new GeofenceEventHandler(createCacheManager(lastPosition));
        List<Event> events = new ArrayList<>();

        handler.onPosition(position, events::add);

        assertTrue(events.isEmpty());
    }

    @Test
    public void testOutdatedPositionDoesNotTriggerExit() {
        Position lastPosition = createPosition(10, 1000, false, GEOFENCE_ID);
        Position position = createPosition(11, 1000, true);
        GeofenceEventHandler handler = new GeofenceEventHandler(createCacheManager(lastPosition));
        List<Event> events = new ArrayList<>();

        handler.onPosition(position, events::add);

        assertTrue(events.isEmpty());
    }

    @Test
    public void testFreshPositionStillTriggersEnter() {
        Position lastPosition = createPosition(10, 1000, false);
        Position position = createPosition(11, 2000, false, GEOFENCE_ID);
        GeofenceEventHandler handler = new GeofenceEventHandler(createCacheManager(lastPosition));
        List<Event> events = new ArrayList<>();

        handler.onPosition(position, events::add);

        assertEquals(1, events.size());
        assertEquals(Event.TYPE_GEOFENCE_ENTER, events.get(0).getType());
        assertEquals(GEOFENCE_ID, events.get(0).getGeofenceId());
    }

    @Test
    public void testFreshPositionStillTriggersExit() {
        Position lastPosition = createPosition(10, 1000, false, GEOFENCE_ID);
        Position position = createPosition(11, 2000, false);
        GeofenceEventHandler handler = new GeofenceEventHandler(createCacheManager(lastPosition));
        List<Event> events = new ArrayList<>();

        handler.onPosition(position, events::add);

        assertEquals(1, events.size());
        assertEquals(Event.TYPE_GEOFENCE_EXIT, events.get(0).getType());
        assertEquals(GEOFENCE_ID, events.get(0).getGeofenceId());
    }
}
