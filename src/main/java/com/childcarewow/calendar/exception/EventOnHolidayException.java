package com.childcarewow.calendar.exception;

import org.springframework.http.HttpStatus;

public class EventOnHolidayException extends ServiceException {

  private static final long serialVersionUID = 1L;

  public EventOnHolidayException(String holidayName) {
    super(
        "EVENT_ON_HOLIDAY",
        HttpStatus.CONFLICT,
        "startDt",
        "Cannot schedule event on holiday: " + holidayName);
  }
}
