package com.example.platform.messaging;

// Shared transport contract/infrastructure; each application supplies its own datasource.

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@ConditionalOnProperty(name = "platform.messaging.enabled", havingValue = "true")
public class KafkaConfiguration {
    @Bean
    @ConditionalOnProperty(name = "platform.telemetry.kafka-lag-enabled", havingValue = "true")
    ConsumerLagTelemetry consumerLagTelemetry(org.springframework.kafka.core.KafkaAdmin admin,
            io.micrometer.core.instrument.MeterRegistry registry,
            @Value("${platform.messaging.order-topic}") String topic,
            @Value("${platform.messaging.notification-group}") String group) {
        return new ConsumerLagTelemetry(admin.getConfigurationProperties(), registry, topic, group);
    }
    @Bean
    NewTopic orderEventsTopic(@Value("${platform.messaging.order-topic}") String topic) {
        return TopicBuilder.name(topic).partitions(3).replicas(1)
                .config("retention.ms", "604800000").build();
    }

    @Bean
    DefaultErrorHandler notificationErrorHandler(KafkaTemplate<String, String> kafka,
            @Value("${platform.messaging.order-topic}") String topic) {
        var recoverer = new DeadLetterPublishingRecoverer(kafka, (record, error) -> new TopicPartition(topic + ".DLT", record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(java.time.Duration.ofSeconds(12));
        var backoff = new ExponentialBackOffWithMaxRetries(3);
        backoff.setInitialInterval(1000L);
        backoff.setMultiplier(2.0);
        backoff.setMaxInterval(4000L);
        var handler = new DefaultErrorHandler(recoverer, backoff);
        handler.addNotRetryableExceptions(IllegalArgumentException.class, com.fasterxml.jackson.core.JsonProcessingException.class,
                org.springframework.dao.DataIntegrityViolationException.class);
        return handler;
    }

    @Bean
    NewTopic orderDeadLetterTopic(@Value("${platform.messaging.order-topic}") String topic) {
        return TopicBuilder.name(topic + ".DLT").partitions(3).replicas(1).config("retention.ms", "2592000000").build();
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> dltListenerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer, ConsumerFactory<Object, Object> consumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
        configurer.configure(factory, consumerFactory);
        factory.getContainerProperties().setObservationEnabled(true);
        var retry = new DefaultErrorHandler(new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS));
        retry.setClassifications(java.util.Map.of(Exception.class, true), true);
        factory.setCommonErrorHandler(retry);
        return factory;
    }
}
