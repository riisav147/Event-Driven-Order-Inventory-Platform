package com.example.platform.messaging;

// Shared transport contract/infrastructure; each application supplies its own datasource.

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = {"platform.messaging.enabled", "platform.messaging.relay-enabled"}, havingValue = "true")
public class OutboxScheduler {
    private final OutboxRelay relay;
    public OutboxScheduler(OutboxRelay relay) { this.relay = relay; }

    @Scheduled(fixedDelayString = "${platform.messaging.poll-interval-ms:1000}")
    public void poll() {
        for (int i = 0; i < 10 && !Thread.currentThread().isInterrupted(); i++) {
            if (!relay.publishNext()) break;
        }
    }
}
