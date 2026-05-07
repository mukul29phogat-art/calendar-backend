package com.childcarewow.calendar.exception;

import org.springframework.http.HttpStatus;

public class IdempotencyReplayException extends ServiceException {

  private static final long serialVersionUID = 1L;

  public IdempotencyReplayException() {
    super(
        "IDEMPOTENCY_REPLAY",
        HttpStatus.CONFLICT,
        "Idempotency-Key",
        "This idempotency key was used with a different request body");
  }
}
