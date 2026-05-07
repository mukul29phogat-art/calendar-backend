package com.childcarewow.calendar.auth;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when the JWT subject doesn't correspond to a row in {@code platform.users}. Maps to HTTP
 * 401 — the token is technically valid but identifies no real user.
 */
@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class UnknownPrincipalException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public UnknownPrincipalException(UUID userId, Throwable cause) {
    super("No platform user with id=" + userId, cause);
  }
}
