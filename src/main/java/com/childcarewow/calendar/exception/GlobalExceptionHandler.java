package com.childcarewow.calendar.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates business exceptions to {@link ServiceErrorResponse} envelopes (arch spec § 15). All
 * concrete error codes derive from {@link ServiceException}, so a single handler catches them.
 * Bean-validation failures from {@code @Valid} bodies are mapped to {@code VALIDATION_ERROR}, and
 * unknown {@code Throwable}s are sanitized to {@code INTERNAL_ERROR} with the original message
 * logged but NOT leaked to the client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(ServiceException.class)
  public ResponseEntity<ServiceErrorResponse> handleService(ServiceException e) {
    return ResponseEntity.status(e.getStatus())
        .body(ServiceErrorResponse.of(e.getCode(), e.getMessage(), e.getField()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ServiceErrorResponse> handleBeanValidation(
      MethodArgumentNotValidException e) {
    var first = e.getBindingResult().getFieldErrors().stream().findFirst();
    String field = first.map(fe -> fe.getField()).orElse(null);
    String message = first.map(fe -> fe.getDefaultMessage()).orElse("Validation failed");
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ServiceErrorResponse.of("VALIDATION_ERROR", message, field));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ServiceErrorResponse> handleUnknown(Exception e) {
    // Log the full stack trace for ops; surface a sanitized message to the client.
    log.error("Unhandled exception in request", e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ServiceErrorResponse.of("INTERNAL_ERROR", "An unexpected error occurred", null));
  }
}
