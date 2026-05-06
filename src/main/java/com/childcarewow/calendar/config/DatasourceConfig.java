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
}
