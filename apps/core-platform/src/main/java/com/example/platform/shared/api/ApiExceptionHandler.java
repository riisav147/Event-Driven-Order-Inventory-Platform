package com.example.platform.shared.api;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, Object> validation(MethodArgumentNotValidException exception) {
        return error("VALIDATION_ERROR", exception.getBindingResult().getFieldErrors().stream()
                .map(field -> field.getField() + ": " + field.getDefaultMessage()).toList().toString());
    }

    @ExceptionHandler(EmptyResultDataAccessException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    Map<String, Object> notFound() { return error("NOT_FOUND", "Resource was not found"); }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class,
            com.example.platform.order.domain.InvalidOrderTransitionException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    Map<String, Object> business(RuntimeException exception) {
        return error("BUSINESS_ERROR", exception.getMessage());
    }

    private Map<String, Object> error(String code, String message) {
        return Map.of("timestamp", Instant.now(), "code", code, "message", message);
    }
}
