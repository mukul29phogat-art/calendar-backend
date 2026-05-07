package com.childcarewow.calendar.common;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Field-level validation failure surfaced to clients as HTTP 400. Carries the offending field name
 * and a human-readable reason matching the standard error envelope (architecture spec §15).
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class ValidationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String field;

  public ValidationException(String field, String message) {
    super(message);
    this.field = field;
  }

  public String getField() {
    return field;
  }
}
