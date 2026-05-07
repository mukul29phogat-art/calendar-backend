package com.childcarewow.calendar.auth;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Converts a verified Spring Security {@link Jwt} into an {@link AbstractAuthenticationToken}
 * carrying our {@link UserPrincipal}. Subject UUID extraction → platform-DB lookup for full
 * principal. Authorities derive from {@link Role} (e.g. {@code ROLE_PARENT}).
 */
@Component
public class JwtToUserPrincipalConverter implements Converter<Jwt, AbstractAuthenticationToken> {

  private final PlatformUserDirectory directory;

  public JwtToUserPrincipalConverter(PlatformUserDirectory directory) {
    this.directory = directory;
  }

  @Override
  public AbstractAuthenticationToken convert(Jwt jwt) {
    UUID userId;
    try {
      userId = UUID.fromString(jwt.getSubject());
    } catch (IllegalArgumentException e) {
      throw new UnknownPrincipalException(null, e);
    }
    UserPrincipal principal = directory.load(userId);
    Collection<GrantedAuthority> authorities =
        List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name()));
    return new UserPrincipalAuthenticationToken(jwt, principal, authorities);
  }
}
