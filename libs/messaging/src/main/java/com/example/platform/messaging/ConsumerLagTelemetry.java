package com.example.platform.messaging;

import io.micrometer.core.instrument.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.TopicPartition;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Committed group backlog, including when the consumer is stopped. Not client fetch lag. */
public class ConsumerLagTelemetry implements AutoCloseable {
    private final Admin admin;
    private final String topic;
    private final String group;
    private volatile double orders = Double.NaN, deadLetters = Double.NaN, available;
    public ConsumerLagTelemetry(Map<String, Object> configuration, MeterRegistry registry, String topic, String group) {
        var options = new HashMap<>(configuration);
        options.put("request.timeout.ms", 1000); options.put("default.api.timeout.ms", 2000);
        admin = Admin.create(options); this.topic = topic; this.group = group;
        Gauge.builder("platform.kafka.group.lag", this, t -> t.orders).tag("group", group).register(registry);
        Gauge.builder("platform.kafka.group.lag", this, t -> t.deadLetters).tag("group", group + ".dlt").register(registry);
        Gauge.builder("platform.telemetry.kafka.available", this, t -> t.available).register(registry);
    }
    @Scheduled(fixedDelayString = "${platform.telemetry.poll-ms:10000}", initialDelay = 5000)
    public void poll() {
        try {
            orders = lag(topic, group); deadLetters = lag(topic + ".DLT", group + ".dlt"); available = 1;
        } catch (Exception unavailable) {
            if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
            orders = Double.NaN; deadLetters = Double.NaN; available = 0;
        }
    }
    private double lag(String topic, String group) throws Exception {
        var description = admin.describeTopics(List.of(topic)).allTopicNames().get(2, TimeUnit.SECONDS).get(topic);
        var ends = new HashMap<TopicPartition, OffsetSpec>();
        var starts = new HashMap<TopicPartition, OffsetSpec>();
        description.partitions().forEach(p -> {
            var partition = new TopicPartition(topic, p.partition());
            ends.put(partition, OffsetSpec.latest()); starts.put(partition, OffsetSpec.earliest());
        });
        var committed = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(2, TimeUnit.SECONDS);
        var end = admin.listOffsets(ends).all().get(2, TimeUnit.SECONDS);
        var start = admin.listOffsets(starts).all().get(2, TimeUnit.SECONDS);
        long total = 0;
        for (var partition : ends.keySet()) {
            long position = committed.containsKey(partition) && committed.get(partition) != null
                    ? committed.get(partition).offset() : start.get(partition).offset();
            total += Math.max(0, end.get(partition).offset() - Math.max(position, start.get(partition).offset()));
        }
        return total;
    }
    public void close() { admin.close(Duration.ofSeconds(2)); }
}
