package com.childcarewow.calendar.exception;

import org.springframework.http.HttpStatus;

public class ForbiddenException extends ServiceException {

  private static final long serialVersionUID = 1L;

  public ForbiddenException(String action) {
    super("FORBIDDEN", HttpStatus.FORBIDDEN, null, "Not authorized to: " + action);
  }
}
