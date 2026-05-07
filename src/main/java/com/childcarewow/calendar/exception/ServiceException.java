package com.childcarewow.calendar.exception;

import org.springframework.http.HttpStatus;

/**
 * Base for every business exception the calendar service raises. {@link GlobalExceptionHandler}
 * catches this single type and shapes the response into a {@link ServiceErrorResponse} envelope
 * (architecture spec § 15) — concrete subclasses only need to declare the code, status, and
 * (optionally) the offending field.
 */
public abstract class ServiceException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String code;
  private final HttpStatus status;
  private final String field;

  protected ServiceException(String code, HttpStatus status, String field, String message) {
    this(code, status, field, message, null);
  }

  protected ServiceException(
      String code, HttpStatus status, String field, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
    this.status = status;
    this.field = field;
  }

  public String getCode() {
    return code;
  }

  public HttpStatus getStatus() {
    return status;
  }

  public String getField() {
    return field;
  }
}
