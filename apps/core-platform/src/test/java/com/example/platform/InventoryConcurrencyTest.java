package com.example.platform;

import com.example.platform.inventory.application.InventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class InventoryConcurrencyTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        Phase6IntegrationTest.database(registry);
        ExtractedInventory.configure(registry);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired InventoryService inventory;

    @Test
    void oneHundredAttemptsReserveExactlyAvailableStock() throws Exception {
        UUID customerId = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id, email, password_hash) VALUES (?, ?, 'hash')",
                customerId, "concurrency-" + customerId + "@example.com");
        UUID productId = UUID.randomUUID();
        jdbc.update("INSERT INTO products(id, sku, name, price, currency) VALUES (?, ?, ?, 1.00, 'USD')",
                productId, "CONCURRENCY-" + productId, "Concurrency product");
        inventory.setStock(productId, 10);

        try (var executor = Executors.newFixedThreadPool(20)) {
            List<Callable<Boolean>> attempts = java.util.stream.IntStream.range(0, 100).<Callable<Boolean>>mapToObj(i -> () -> {
                UUID orderId = UUID.randomUUID();
                jdbc.update("INSERT INTO orders(id, order_number, customer_id, status, total, currency) VALUES (?, ?, "
                                + "?, 'CREATED', 1.00, 'USD')",
                        orderId, "CONC-" + orderId.toString().substring(0, 8), customerId);
                try {
                    return "RESERVED".equals(inventory.reserve(orderId, List.of(new InventoryService.Item(productId, 1))).status());
                } catch (IllegalStateException insufficient) {
                    return false;
                }
            }).toList();
            long successful = executor.invokeAll(attempts).stream().filter(future -> {
                try { return future.get(); } catch (Exception e) { throw new RuntimeException(e); }
            }).count();

            assertThat(successful).isEqualTo(10);
        }
        assertThat(ExtractedInventory.jdbc().queryForObject("SELECT available_quantity FROM inventory WHERE product_id = ?", Integer.class, productId)).isZero();
        assertThat(ExtractedInventory.jdbc().queryForObject("SELECT reserved_quantity FROM inventory WHERE product_id = ?", Integer.class, productId)).isEqualTo(10);
    }
}
