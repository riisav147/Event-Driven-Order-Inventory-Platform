package com.example.platform;

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
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"payment.recovery.enabled=false", "spring.data.redis.port=1", "spring.data.redis.connect-timeout=50ms", "spring.data.redis.timeout=50ms"})
@EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class, FlywayAutoConfiguration.class})
class ProductCacheFallbackTest {
    @MockitoBean JdbcTemplate jdbc;
    @MockitoBean org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired ProductService products;
    @Test @SuppressWarnings("unchecked")
    void unavailableRedisFallsBackToDatabase() {
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of());
        assertThat(products.listEnabled()).isEmpty();
        verify(jdbc).query(anyString(), any(RowMapper.class));
        assertThatCode(() -> products.create(new com.example.platform.catalog.api.ProductDtos.CreateProductRequest(
                "OUTAGE", "Item", java.math.BigDecimal.ONE, "USD"))).doesNotThrowAnyException();
    }
}
