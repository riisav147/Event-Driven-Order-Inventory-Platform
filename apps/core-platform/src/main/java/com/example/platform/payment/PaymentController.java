package com.example.platform.payment;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
public class PaymentController {
    private final PaymentService payments;

    public PaymentController(PaymentService payments) { this.payments = payments; }

    @PostMapping("/{orderNumber}/payment")
    public ResponseEntity<PaymentService.PaymentResponse> pay(@PathVariable("orderNumber") String orderNumber,
                                                              Principal principal) {
        var response = payments.pay(UUID.fromString(principal.getName()), orderNumber);
        return ResponseEntity.status(!"DONE".equals(response.sagaPhase()) ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(response);
    }
}
