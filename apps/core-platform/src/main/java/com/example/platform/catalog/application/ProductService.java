package com.example.platform.catalog.application;

import com.example.platform.catalog.api.ProductDtos.CreateProductRequest;
import com.example.platform.catalog.api.ProductDtos.ProductResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.UUID;

@Service
public class ProductService {
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    public ProductService(JdbcTemplate jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }
    @Cacheable("products")
    public List<ProductResponse> listEnabled() {
        return jdbc.query("SELECT id, sku, name, price, currency FROM products WHERE enabled = true ORDER BY name",
                (rs, row) -> new ProductResponse(rs.getObject("id", UUID.class), rs.getString("sku"),
                        rs.getString("name"), rs.getBigDecimal("price"), rs.getString("currency")));
    }
    @Cacheable(value = "product", key = "#p0")
    public ProductResponse getEnabled(UUID id) {
        return jdbc.queryForObject("SELECT id, sku, name, price, currency FROM products WHERE id = ? AND enabled = true",
                (rs, row) -> new ProductResponse(rs.getObject("id", UUID.class), rs.getString("sku"),
                        rs.getString("name"), rs.getBigDecimal("price"), rs.getString("currency")), id);
    }
    @Transactional
    public ProductResponse create(CreateProductRequest request) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO products(id, sku, name, price, currency) VALUES (?, ?, ?, ?, ?)", id,
                request.sku(), request.name(), request.price(), request.currency());
        events.publishEvent(new ProductChanged(id));
        return new ProductResponse(id, request.sku(), request.name(), request.price(), request.currency());
    }
    public record ProductChanged(UUID id) { }
}
