package com.example.platform.inventory.api;

import jakarta.validation.constraints.Min;

public final class InventoryDtos {
    private InventoryDtos() { }
    public record SetStockRequest(@Min(0) int quantity) { }
}
