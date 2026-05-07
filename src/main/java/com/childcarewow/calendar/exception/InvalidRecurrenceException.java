package com.childcarewow.calendar.exception;

import org.springframework.http.HttpStatus;

public class InvalidRecurrenceException extends ServiceException {

  private static final long serialVersionUID = 1L;

  public InvalidRecurrenceException(String detail) {
    super("INVALID_RECURRENCE", HttpStatus.BAD_REQUEST, "recurrence", detail);
  }
}
