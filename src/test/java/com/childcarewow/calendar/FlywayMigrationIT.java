package com.childcarewow.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies Flyway migrations against a fresh Postgres container.
 *
 * <p>Runs only on Linux/Mac. On Windows + Docker Desktop 4.71 the Testcontainers npipe handshake
 * fails (DockerClientProviderStrategy returns HTTP 400 from every available named pipe), so the
 * class-level {@link EnabledOnOs} skips this entire class — including the static container init —
 * on Windows. CI runs on ubuntu-latest where the Unix Docker socket works cleanly.
 *
 * <p>For Windows local-dev coverage of "Flyway runs on Spring startup", rely on the live
 * compose-managed calendar-db: Spring Boot's auto-config applies V1 to it during any
 * {@code @SpringBootTest} context load (e.g. {@code DatasourceConfigIT}). This IT adds the stricter
 * "fresh container, no prior state" guarantee on top, exercised by CI.
 */
@SpringBootTest
@Testcontainers
@EnabledOnOs({OS.LINUX, OS.MAC})
class FlywayMigrationIT {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine");

  @DynamicPropertySource
  static void registerProps(DynamicPropertyRegistry registry) {
    // Point both datasources at the same fresh container.
    // Flyway runs only against `calendar` (per spring.flyway.* config in YAML).
    // Platform pool just needs a reachable DB to start; we don't query its tables here.
    registry.add("spring.datasource.calendar.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.calendar.username", POSTGRES::getUsername);
    registry.add("spring.datasource.calendar.password", POSTGRES::getPassword);
    registry.add("spring.datasource.platform.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.platform.username", POSTGRES::getUsername);
    registry.add("spring.datasource.platform.password", POSTGRES::getPassword);
    // Belt-and-suspenders: pin the Flyway connection to the same container directly.
    registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);
  }

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
