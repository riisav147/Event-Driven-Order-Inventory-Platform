package com.example.platform;

import com.example.inventory.InventoryApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/** A real HTTP application and a different database, never a stub inventory repository. */
final class ExtractedInventory {
    private static ServletWebServerApplicationContext app;
    private static PostgreSQLContainer<?> database;
    private static String[] arguments;
    static synchronized void configure(DynamicPropertyRegistry registry) {
        if (app == null) {
            String url = System.getProperty("phase11.inventory-url");
            String user = System.getProperty("phase11.inventory-user", "platform");
            String password = System.getProperty("phase11.inventory-password", "");
            if (url == null) {
                database = new PostgreSQLContainer<>("postgres:16-alpine"); database.start();
                url = database.getJdbcUrl(); user = database.getUsername(); password = database.getPassword();
            }
            arguments = new String[] {
                "--spring.config.name=inventory-test", "--server.port=0", "--spring.datasource.url=" + url,
                "--spring.datasource.username=" + user, "--spring.datasource.password=" + password,
                "--spring.flyway.locations=classpath:db/inventory", "--service.password=local-inventory-only",
                "--spring.application.name=inventory-service", "--management.tracing.sampling.probability=1",
                "--management.otlp.tracing.export.enabled=false"};
            restart();
            arguments[1] = "--server.port=" + app.getWebServer().getPort();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> app.close()));
        }
        registry.add("inventory.url", () -> "http://localhost:" + app.getWebServer().getPort());
        registry.add("payment.recovery.enabled", () -> false);
    }
    static JdbcTemplate jdbc() { return app.getBean(JdbcTemplate.class); }
    static String url() { return "http://localhost:" + app.getWebServer().getPort(); }
    static void stop() { app.close(); }
    static void restart() { app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(InventoryApplication.class, TraceCapture.class).run(arguments); }
}
