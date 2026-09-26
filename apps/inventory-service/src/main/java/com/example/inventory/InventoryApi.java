package com.example.inventory;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.util.*;

@RestController
@RequestMapping("/internal/v1")
public class InventoryApi {
    private final InventoryCommands commands;
    private final com.example.telemetry.FlowTelemetry telemetry;
    public InventoryApi(InventoryCommands commands, com.example.telemetry.FlowTelemetry telemetry) { this.commands = commands; this.telemetry = telemetry; }
    public record Stock(int quantity) { }
    @PutMapping("/stock/{product}")
    public void stock(@PathVariable("product") UUID product, @RequestBody Stock stock) { commands.stock(product, stock.quantity()); }
    @PutMapping("/reservations/{order}")
    public InventoryCommands.Result reserve(@PathVariable("order") UUID order, @RequestBody List<InventoryCommands.Item> items) {
        try (var scope = telemetry.open("inventory.reserve", order, null, null, null)) {
            var result = commands.reserve(order, items); scope.outcome(result.status()); return result;
        }
    }
    @PostMapping("/reservations/{order}/{target}")
    public InventoryCommands.Result move(@PathVariable("order") UUID order, @PathVariable("target") String target) {
        try (var scope = telemetry.open("inventory.move", order, null, null, null)) {
            var result = commands.move(order, target); scope.outcome(result.status()); return result;
        }
    }
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflict(RuntimeException error) { return Map.of("error", error.getMessage()); }
}
