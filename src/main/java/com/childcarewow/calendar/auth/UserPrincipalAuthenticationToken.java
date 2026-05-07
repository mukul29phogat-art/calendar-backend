package com.childcarewow.calendar.auth;

import java.util.Collection;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Carries a {@link UserPrincipal} as the authenticated principal so controllers can inject it via
 * {@code @AuthenticationPrincipal UserPrincipal actor}.
 */
public final class UserPrincipalAuthenticationToken extends AbstractAuthenticationToken {

  private static final long serialVersionUID = 1L;

  // Jwt and UserPrincipal aren't Serializable — mark transient to satisfy -Werror on
  // "non-transient instance field of a serializable class". AbstractAuthenticationToken
  // implements Serializable but we never actually serialize this token in our app
  // (stateless sessions; no HTTP session storage).
  private final transient Jwt jwt;
  private final transient UserPrincipal principal;

  @SuppressWarnings("this-escape") // setAuthenticated() is on a final class — no subclass override
  public UserPrincipalAuthenticationToken(
      Jwt jwt, UserPrincipal principal, Collection<? extends GrantedAuthority> authorities) {
    super(authorities);
    this.jwt = jwt;
    this.principal = principal;
    setAuthenticated(true);
  }

  @Override
  public Object getCredentials() {
    return jwt;
  }

  @Override
  public Object getPrincipal() {
    return principal;
  }
}
