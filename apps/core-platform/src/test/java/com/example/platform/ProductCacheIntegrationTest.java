package com.example.platform;

import com.example.platform.catalog.api.ProductDtos.*;
import com.example.platform.catalog.application.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "payment.recovery.enabled=false")
@EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class, FlywayAutoConfiguration.class})
class ProductCacheIntegrationTest {
    private static GenericContainer<?> redis;
    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        String port = System.getProperty("phase6.redis-port");
        if (port == null) {
            redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
            redis.start();
            registry.add("spring.data.redis.host", redis::getHost);
            registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        } else {
            registry.add("spring.data.redis.host", () -> "127.0.0.1");
            registry.add("spring.data.redis.port", () -> port);
        }
    }
    @MockitoBean JdbcTemplate jdbc;
    @MockitoBean org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired ProductService products;
    @Autowired StringRedisTemplate redisTemplate;

    @Test
    @SuppressWarnings("unchecked")
    void readsHitCacheAndWritesInvalidateAndEntriesExpire() throws Exception {
        ProductResponse item = new ProductResponse(UUID.randomUUID(), "CACHE", "Item", new BigDecimal("12.50"), "USD");
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of(item));
        products.create(new CreateProductRequest("CLEAR", "Clear", BigDecimal.ONE, "USD"));
        assertThat(products.listEnabled()).containsExactly(item);
        assertThat(products.listEnabled()).containsExactly(item);
        verify(jdbc, times(1)).query(anyString(), any(RowMapper.class));
        String key = "platform:v1:products:SimpleKey []";
        assertThat(redisTemplate.getExpire(key)).isBetween(1L, 120L);
        products.create(new CreateProductRequest("NEW", "New", BigDecimal.ONE, "USD"));
        assertThat(redisTemplate.hasKey(key)).isFalse();
        products.listEnabled();
        verify(jdbc, times(2)).query(anyString(), any(RowMapper.class));
        redisTemplate.expire(key, Duration.ofMillis(1));
        Thread.sleep(30);
        products.listEnabled();
        verify(jdbc, times(3)).query(anyString(), any(RowMapper.class));

        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(item.id()))).thenReturn(item);
        assertThat(products.getEnabled(item.id())).isEqualTo(item);
        assertThat(products.getEnabled(item.id())).isEqualTo(item);
        verify(jdbc, times(1)).queryForObject(anyString(), any(RowMapper.class), eq(item.id()));
    }
}
