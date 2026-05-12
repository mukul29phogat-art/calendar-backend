package com.childcarewow.calendar.notification;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Dispatcher config. Maps onto {@code notifications.email.*} keys in {@code application.yml}.
 *
 * <p><b>{@code devAllowlist}</b> — list of email-address suffixes (e.g. {@code
 * "@childcarewow.com"}, {@code "@ccw.test"}). In dev/staging this MUST be set; outbound mail to any
 * address that doesn't match a suffix is silently dropped with {@code
 * DispatchResult#BLOCKED_BY_ALLOWLIST}. In prod the list is empty/unset and every recipient is
 * allowed.
 *
 * <p><b>{@code enabled}</b> — global kill-switch. When false, the dispatcher returns {@code
 * DISABLED} for every call without touching SMTP. Useful in CI and for emergency lockdowns.
 */
@ConfigurationProperties("notifications.email")
public record EmailDispatcherProperties(boolean enabled, List<String> devAllowlist) {

  /** Spring Boot's default binder fills {@code null} when the key is absent — coalesce to empty. */
  public List<String> devAllowlist() {
    return devAllowlist == null ? List.of() : devAllowlist;
  }
}
