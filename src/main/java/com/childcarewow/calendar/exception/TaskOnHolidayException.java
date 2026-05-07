package com.childcarewow.calendar.exception;

import org.springframework.http.HttpStatus;

public class TaskOnHolidayException extends ServiceException {

  private static final long serialVersionUID = 1L;

  public TaskOnHolidayException(String holidayName) {
    super(
        "TASK_ON_HOLIDAY",
        HttpStatus.CONFLICT,
        "dueDate",
        "Cannot schedule task on holiday: " + holidayName);
  }
}
