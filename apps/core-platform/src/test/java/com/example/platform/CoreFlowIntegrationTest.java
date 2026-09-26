package com.example.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CoreFlowIntegrationTest {
    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        Phase6IntegrationTest.database(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void customerCanCheckoutIntoCreatedOrder() throws Exception {
        String suffix = java.util.UUID.randomUUID().toString();
        String adminEmail = "admin-" + suffix + "@example.com";
        String customerEmail = "customer-" + suffix + "@example.com";
        String adminId = register(adminEmail);
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", java.util.UUID.fromString(adminId));
        String adminToken = login(adminEmail);
        String customerId = register(customerEmail);
        String customerToken = login(customerEmail);

        String product = mvc.perform(post("/api/admin/products").header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content("{\"sku\":\"%s\",\"name\":\"Keyboard\",\"price\":\"50.00\",\"currency\":\"USD\"}".formatted(suffix)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String productId = idFrom(product);

        mvc.perform(post("/api/cart/items").header("Authorization", "Bearer " + customerToken)
                        .header("X-Customer-Id", customerId).contentType("application/json")
                        .content("{\"productId\":\"" + productId + "\",\"quantity\":2}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/orders").header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", "core-flow-checkout")
                        .header("X-Customer-Id", customerId)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.total").value(100.00));
    }

    private String register(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/register").contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"password\":\"secret\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return idFrom(body);
    }

    private String login(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"password\":\"secret\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\\\"accessToken\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }

    private String idFrom(String body) {
        return body.replaceAll(".*\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }
}
