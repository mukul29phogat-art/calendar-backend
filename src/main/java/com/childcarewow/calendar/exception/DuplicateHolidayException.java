package com.childcarewow.calendar.exception;

import java.time.LocalDate;
import org.springframework.http.HttpStatus;

public class DuplicateHolidayException extends ServiceException {

  private static final long serialVersionUID = 1L;

  public DuplicateHolidayException(LocalDate date) {
    super(
        "DUPLICATE_HOLIDAY",
        HttpStatus.CONFLICT,
        "date",
        "An approved holiday already exists on " + date);
  }
}
