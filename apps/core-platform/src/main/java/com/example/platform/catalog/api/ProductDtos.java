package com.example.platform.catalog.api;

import java.math.BigDecimal;
import java.util.UUID;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public final class ProductDtos {
    private ProductDtos() { }
    public record CreateProductRequest(
            @NotBlank String sku,
            @NotBlank String name,
            @DecimalMin("0.00") BigDecimal price,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency) { }
    public record ProductResponse(UUID id, String sku, String name, BigDecimal price, String currency) { }
}
