package com.childcarewow.calendar.exception;

import org.springframework.http.HttpStatus;

/**
 * The platform DB is unreachable. Maps to HTTP 503 — fail-closed per arch spec § 7.2. Carries a
 * cause so the underlying DataAccessException stays in logs.
 */
public class PlatformUnavailableException extends ServiceException {

  private static final long serialVersionUID = 1L;

  public PlatformUnavailableException(String message, Throwable cause) {
    super("PLATFORM_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, null, message, cause);
  }
}
