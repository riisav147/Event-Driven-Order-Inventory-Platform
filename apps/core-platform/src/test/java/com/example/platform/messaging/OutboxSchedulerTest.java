package com.example.platform.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.mockito.Mockito.*;

@SpringJUnitConfig(OutboxSchedulerTest.Config.class)
@TestPropertySource(properties = {"platform.messaging.enabled=true", "platform.messaging.relay-enabled=true",
        "platform.messaging.poll-interval-ms=20"})
@DirtiesContext
class OutboxSchedulerTest {
    @Autowired OutboxRelay relay;

    @Test void schedulerRunsAndEachPollHasBoundedWork() {
        verify(relay, timeout(2000).atLeastOnce()).publishNext();
        var busy = mock(OutboxRelay.class);
        when(busy.publishNext()).thenReturn(true);
        new OutboxScheduler(busy).poll();
        verify(busy, times(10)).publishNext();
    }

    @Configuration
    @Import(OutboxScheduler.class)
    static class Config {
        @Bean OutboxRelay relay() { return mock(OutboxRelay.class); }
    }
}
