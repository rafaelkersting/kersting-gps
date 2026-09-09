package org.traccar.schedule;

import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.demo.DemoService;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class TaskDemoCleanup extends SingleScheduleTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskDemoCleanup.class);

    private final DemoService demoService;
    private final long intervalSeconds;
    private final AtomicBoolean recovered = new AtomicBoolean();

    @Inject
    public TaskDemoCleanup(Config config, DemoService demoService) {
        this.demoService = demoService;
        intervalSeconds = Math.max(10, config.getInteger(Keys.DEMO_CLEANUP_INTERVAL));
    }

    @Override
    public void schedule(ScheduledExecutorService executor) {
        executor.scheduleAtFixedRate(this, 0, intervalSeconds, TimeUnit.SECONDS);
    }

    @Override
    public void run() {
        try {
            if (recovered.compareAndSet(false, true)) {
                demoService.recoverInterruptedSessions();
            }
            demoService.cleanupDueSessions();
        } catch (Exception error) {
            LOGGER.warn("Demo cleanup cycle failed", error);
        }
    }
}
