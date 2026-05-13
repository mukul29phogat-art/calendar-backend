package com.childcarewow.calendar.notification;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Push-dispatcher config. Maps onto {@code notifications.push.*} keys in {@code application.yml}.
 *
 * <p>Mirrors {@link EmailDispatcherProperties} from Part 11.4. The same architecture-spec §7.4
 * mandate applies: dev allowlist enforced IN CODE, not at the network / FCM-project level.
 *
 * <ul>
 *   <li><b>{@code enabled}</b> — global kill-switch. Default {@code false} (v1 is web-only, no iOS
 *       / Android clients exist yet; live-fire push has nowhere to send). When {@code false} every
 *       call returns {@code DISABLED} without touching FCM.
 *   <li><b>{@code devAllowlist}</b> — list of allowed FCM device tokens (exact match). Empty list
 *       in prod (permits all). Non-empty list means: only deliver pushes to those specific devices.
 *       Useful for restricting dev-environment pushes to operator-owned test phones.
 * </ul>
 */
@ConfigurationProperties("notifications.push")
public record PushDispatcherProperties(boolean enabled, List<String> devAllowlist) {

  public List<String> devAllowlist() {
    return devAllowlist == null ? List.of() : devAllowlist;
  }
}
