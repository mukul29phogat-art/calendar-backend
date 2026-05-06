package com.childcarewow.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies Flyway migrations against the calendar datasource.
 *
 * <p>Originally specified in playbook Part 0.4 step 6 to use Testcontainers for fresh-container
 * isolation. Testcontainers does not currently work against Docker Desktop 4.71 on Windows
 * (DockerClientProviderStrategy fails with Status 400 from every available named pipe). Until
 * that's resolved (P0.6 CI runs on Linux where Testcontainers works fine), this IT runs against
 * the live calendar-db managed by docker-compose. Flyway is idempotent, so re-running the test
 * doesn't cause issues; the assertions verify state Flyway should have established on first
 * Spring context load.
 */
@SpringBootTest
class FlywayMigrationIT {

  @Autowired JdbcTemplate calendarJdbcTemplate;

  @Test
  void flywaySchemaHistoryHasV1Success() {
    Long count =
        calendarJdbcTemplate.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success = true",
            Long.class);
    assertThat(count).isEqualTo(1L);
  }

  @Test
  void placeholderTableHasOneRow() {
    Long count =
        calendarJdbcTemplate.queryForObject("SELECT count(*) FROM _flyway_smoke", Long.class);
    assertThat(count).isEqualTo(1L);
  }
}
