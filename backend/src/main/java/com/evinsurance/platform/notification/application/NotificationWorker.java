package com.evinsurance.platform.notification.application;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@Component @EnableScheduling @ConditionalOnProperty(name="NOTIFICATION_WORKER_ENABLED",havingValue="true",matchIfMissing=true)
public class NotificationWorker {
    private final NotificationProjector projector;
    public NotificationWorker(NotificationProjector projector){this.projector=projector;}
    // Projects committed station events only. This does not run deadline reminders or SMS.
    @Scheduled(fixedDelayString="${NOTIFICATION_POLL_MS:1000}") public void tick(){projector.project();}
}
