package com.example.platform;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PostgresSmokeTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        Phase6IntegrationTest.database(registry);
    }

    @Test
    void connectsToPostgres(@org.springframework.beans.factory.annotation.Autowired DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.isValid(2)).isTrue();
        }
    }
}
