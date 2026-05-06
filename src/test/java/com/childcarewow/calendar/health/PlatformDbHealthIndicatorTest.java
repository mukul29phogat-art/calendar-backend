package com.childcarewow.calendar.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.jdbc.core.JdbcTemplate;

class PlatformDbHealthIndicatorTest {

  @Test
  void healthUpWhenSelectOneSucceeds() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);

    Health health = new PlatformDbHealthIndicator(jdbc).health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
  }

  @Test
  void healthDownWhenSelectOneThrows() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject("SELECT 1", Integer.class))
        .thenThrow(new RuntimeException("simulated DB outage"));

    Health health = new PlatformDbHealthIndicator(jdbc).health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
  }
}
