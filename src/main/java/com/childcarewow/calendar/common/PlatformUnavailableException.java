package com.childcarewow.calendar.common;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The platform DB is unreachable (connection timeout, refusal, etc.). Maps to HTTP 503 — the
 * calendar service refuses to validate a write that can't be confirmed against platform data.
 * Fail-closed per architecture spec §7.2.
 */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class PlatformUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public PlatformUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
