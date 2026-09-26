package com.example.platform.inventory.api;

import com.example.platform.inventory.application.InventoryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

import static com.example.platform.inventory.api.InventoryDtos.SetStockRequest;

@RestController
@RequestMapping("/api/admin/inventory")
public class InventoryController {
    private final InventoryService inventory;
    public InventoryController(InventoryService inventory) { this.inventory = inventory; }
    @PostMapping("/{productId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setStock(@PathVariable("productId") UUID productId, @Valid @RequestBody SetStockRequest request) {
        inventory.setStock(productId, request.quantity());
    }
}
