package com.example.notification;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(EmptyResultDataAccessException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    Map<String, String> missing() { return Map.of("error", "Dead letter not found"); }
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    Map<String, String> invalid(IllegalArgumentException error) { return Map.of("error", error.getMessage()); }
}
