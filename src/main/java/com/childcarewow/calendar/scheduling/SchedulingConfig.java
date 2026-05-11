package com.childcarewow.calendar.scheduling;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ShedLock wiring against the calendar DB ({@code shedlock} table from V9). Backs
 * {@code @SchedulerLock} annotations on every {@code @Scheduled} job in this codebase so
 * multi-instance ECS deployments fire each tick on exactly one instance.
 *
 * <p><b>Default lock-at-most-for: 30 minutes.</b> Sized for the slowest current consumer — {@code
 * FederalHolidaySyncJob} fetches Nager.Date twice and writes ~200 rows × #schools — and leaves
 * headroom. {@code IdempotencyPurgeJob} (sub-second) overrides downward.
 *
 * <p><b>{@code usingDbTime()}</b> is critical: lock expiry is computed against Postgres {@code
 * now()}, not the JVM clock, so JVM-side clock drift between ECS tasks cannot cause two instances
 * to think a lock is free at the same instant.
 *
 * <p>The {@code LockProvider} constructs its own {@link JdbcTemplate} around the calendar {@link
 * DataSource} rather than reusing the autowired primary one. This sidesteps transactional
 * propagation: the lock acquire/release runs in its own connection, independent of any
 * {@code @Transactional} the scheduled method opens.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class SchedulingConfig {

  @Bean
  public LockProvider lockProvider(@Qualifier("calendarDataSource") DataSource calendarDataSource) {
    return new JdbcTemplateLockProvider(
        JdbcTemplateLockProvider.Configuration.builder()
            .withJdbcTemplate(new JdbcTemplate(calendarDataSource))
            .usingDbTime()
            .build());
  }
}
