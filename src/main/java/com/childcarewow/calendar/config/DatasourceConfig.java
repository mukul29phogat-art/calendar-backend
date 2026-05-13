package com.childcarewow.calendar.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Configuration
public class DatasourceConfig {

  @Bean
  @Primary
  @ConfigurationProperties("spring.datasource.calendar")
  DataSourceProperties calendarProps() {
    return new DataSourceProperties();
  }

  @Bean
  @Primary
  @ConfigurationProperties("spring.datasource.calendar.hikari")
  HikariDataSource calendarDataSource(@Qualifier("calendarProps") DataSourceProperties p) {
    return p.initializeDataSourceBuilder().type(HikariDataSource.class).build();
  }

  @Bean
  @Primary
  JdbcTemplate calendarJdbcTemplate(@Qualifier("calendarDataSource") DataSource ds) {
    return new JdbcTemplate(ds);
  }

  @Bean
  @ConfigurationProperties("spring.datasource.platform")
  DataSourceProperties platformProps() {
    return new DataSourceProperties();
  }

  @Bean
  @ConfigurationProperties("spring.datasource.platform.hikari")
  HikariDataSource platformDataSource(@Qualifier("platformProps") DataSourceProperties p) {
    return p.initializeDataSourceBuilder().type(HikariDataSource.class).build();
  }

  @Bean(name = "platformJdbcTemplate")
  JdbcTemplate platformJdbcTemplate(@Qualifier("platformDataSource") DataSource ds) {
    return new JdbcTemplate(ds);
  }

  /**
   * Named-parameter wrapper around the platform JdbcTemplate. Preferred over positional {@code ?}
   * for queries with optional filters (Part 4.1's {@code (:role IS NULL OR u.role = :role)}
   * pattern) — positional binds would require duplicate {@code ?} parameters and risk the
   * bind-twice bug.
   */
  @Bean(name = "platformNamedJdbcTemplate")
  NamedParameterJdbcTemplate platformNamedJdbcTemplate(
      @Qualifier("platformJdbcTemplate") JdbcTemplate jdbc) {
    return new NamedParameterJdbcTemplate(jdbc);
  }

  /**
   * Named-parameter wrapper around the calendar JdbcTemplate. Backs {@code IN (:ids)} batched loads
   * used by the calendar-window reads (Series-11 N+1 fix on {@code EventService}).
   */
  @Bean(name = "calendarNamedJdbcTemplate")
  NamedParameterJdbcTemplate calendarNamedJdbcTemplate(
      @Qualifier("calendarJdbcTemplate") JdbcTemplate jdbc) {
    return new NamedParameterJdbcTemplate(jdbc);
  }
}
