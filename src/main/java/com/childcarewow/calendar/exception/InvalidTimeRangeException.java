package com.childcarewow.calendar.exception;

import org.springframework.http.HttpStatus;

public class InvalidTimeRangeException extends ServiceException {

  private static final long serialVersionUID = 1L;

  public InvalidTimeRangeException() {
    super(
        "INVALID_TIME_RANGE", HttpStatus.BAD_REQUEST, "endDt", "End time must be after start time");
  }
}
