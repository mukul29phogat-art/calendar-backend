package com.childcarewow.calendar.health;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component("platformDb")
public class PlatformDbHealthIndicator implements HealthIndicator {

  private final JdbcTemplate platform;

  public PlatformDbHealthIndicator(@Qualifier("platformJdbcTemplate") JdbcTemplate t) {
    this.platform = t;
  }

  @Override
  public Health health() {
    try {
      platform.queryForObject("SELECT 1", Integer.class);
      return Health.up().build();
    } catch (Exception e) {
      return Health.down(e).build();
    }
  }
}
