package com.example.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.context.annotation.Import({com.example.platform.messaging.KafkaConfiguration.class,
        com.example.platform.messaging.OutboxRelay.class, com.example.platform.messaging.OutboxScheduler.class,
        com.example.platform.messaging.OrderNotificationConsumer.class, com.example.platform.messaging.DeadLetterStore.class,
        com.example.platform.messaging.DeadLetterService.class, com.example.platform.messaging.DeadLetterController.class})
public class NotificationApplication {
    public static void main(String[] args) {
        var app = new SpringApplication(NotificationApplication.class);
        app.setAdditionalProfiles("notification");
        app.run(args);
    }
}
