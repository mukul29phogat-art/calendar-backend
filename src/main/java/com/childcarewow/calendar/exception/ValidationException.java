package com.childcarewow.calendar.exception;

import org.springframework.http.HttpStatus;

public class ValidationException extends ServiceException {

  private static final long serialVersionUID = 1L;

  public ValidationException(String field, String message) {
    super("VALIDATION_ERROR", HttpStatus.BAD_REQUEST, field, message);
  }
}
